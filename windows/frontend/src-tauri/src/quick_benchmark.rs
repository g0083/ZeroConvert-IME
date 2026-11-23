use crate::benchmark_worker::{self, Worker};
use azookey_server::ai_assist::benchmark::{Case, ConversionScope, BATCH_SIZE};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use shared::AppConfig;
use std::{
    path::Path,
    time::{Duration, Instant},
};

const CASE_GROUP_SIZE: usize = 10;
const WARMUP_INTERVAL: usize = 10;
const MEASUREMENT_PASSES: usize = 3;
const BACKEND_TIMEOUT: Duration = Duration::from_secs(45);

#[derive(Serialize)]
pub(crate) struct Progress {
    backend: &'static str,
    completed: usize,
    total: usize,
}

#[derive(Debug, Serialize)]
pub(crate) struct ResultSummary {
    model_id: String,
    vulkan_gpu_layers: u32,
    case_count: usize,
    results: Vec<BackendResult>,
    recommended: Option<&'static str>,
    cpu_tuning: Option<shared::cpu_tuning::CpuRuntimeTuningConfig>,
}

#[derive(Debug, Serialize)]
struct BackendResult {
    backend: &'static str,
    avg_latency_ms: Option<f64>,
    p95_latency_ms: Option<f64>,
    error: Option<String>,
}

#[derive(Debug, Deserialize, Serialize)]
struct CaseResult {
    id: u32,
    top1: String,
    candidates: Vec<String>,
    is_correct: bool,
    latency_ms: f64,
    nbest_ms: f64,
}

#[derive(Serialize)]
pub(crate) struct SingleResult {
    backend: &'static str,
    gpu_layers: u32,
    elapsed_ms: f64,
    rows: Vec<CaseResult>,
    #[serde(skip_serializing)]
    pub(crate) cpu_tuning: Option<shared::cpu_tuning::CpuRuntimeTuningConfig>,
}

fn conversion_case(mut row: Value) -> Result<Case, String> {
    row["kind"] = "conversion".into();
    serde_json::from_value(row).map_err(|error| error.to_string())
}

fn workload() -> Result<(Vec<Case>, Vec<Case>), String> {
    let data: Vec<Value> = serde_json::from_str(include_str!("../../src/data/benchmark840.json"))
        .map_err(|error| error.to_string())?;
    let mut warmup = Vec::new();
    let mut measured = Vec::new();
    // 全体から1割を使う。まとまって並ぶ類題の中でも抽出位置をずらす。
    for (index, group) in data.chunks(CASE_GROUP_SIZE).enumerate() {
        let offset = index % group.len();
        measured.push(conversion_case(group[offset].clone())?);
        if index % WARMUP_INTERVAL == 0 && group.len() > 1 {
            warmup.push(conversion_case(group[(offset + 1) % group.len()].clone())?);
        }
    }
    if measured.is_empty() {
        return Err("計測用の問題が見つかりません。".into());
    }
    Ok((warmup, measured))
}

fn measure_with_worker(
    worker: &mut Worker,
    warmup: &[Case],
    cases: &[Case],
    mut report_batch: impl FnMut(usize),
) -> Result<Vec<CaseResult>, String> {
    for batch in warmup.chunks(BATCH_SIZE) {
        worker.run_candidates(batch)?;
    }
    let mut samples: Vec<_> = cases
        .iter()
        .map(|_| Vec::with_capacity(MEASUREMENT_PASSES))
        .collect();
    for _ in 0..MEASUREMENT_PASSES {
        for (batch, measurements) in cases.chunks(BATCH_SIZE).zip(samples.chunks_mut(BATCH_SIZE)) {
            for (result, sample) in worker.run_candidates(batch)?.into_iter().zip(measurements) {
                sample.push(serde_json::from_value(result).map_err(|error| error.to_string())?);
            }
            report_batch(batch.len());
        }
    }
    samples.into_iter().map(median_run).collect()
}

fn measure(
    backend: &str,
    layers: u32,
    model: &Path,
    dictionary: &Path,
    warmup: &[Case],
    cases: &[Case],
    report_batch: impl FnMut(usize),
) -> Result<Vec<CaseResult>, String> {
    let scope = ConversionScope::Candidates;
    let (executable, runtime, _) = crate::benchmark_worker_paths(backend, scope)?;
    let mut worker = Worker::start(
        &executable,
        &runtime,
        model,
        dictionary,
        layers,
        scope,
        Some(BACKEND_TIMEOUT),
        None,
    )?;
    measure_with_worker(&mut worker, warmup, cases, report_batch)
}

pub(crate) fn active_canonical_model_id(config: &AppConfig) -> String {
    shared::runtime_assets::canonical_model_id(&config.zenzai.model)
        .unwrap_or(&config.zenzai.model)
        .to_owned()
}

fn probe_and_select_cpu_tuning(
    worker: &mut Worker,
    model_id: &str,
    model: &Path,
) -> Option<shared::cpu_tuning::CpuRuntimeTuningConfig> {
    let topology = shared::cpu_tuning::detect_topology();
    let model_bytes = std::fs::metadata(model).map(|meta| meta.len()).unwrap_or(0);
    let configs = shared::cpu_tuning::candidate_configs(&topology, model_bytes);
    let measurements = worker.probe_tuning(&configs).ok()?;
    let winner = shared::cpu_tuning::select_best(&measurements)?;
    let cfg = shared::cpu_tuning::build_tuning_config(&winner, model_id, &topology);
    worker
        .configure_tuning(
            cfg.decode_threads,
            cfg.batch_threads,
            cfg.direct_model_first,
        )
        .ok()?;
    Some(cfg)
}

pub(crate) fn ensure_cpu_worker_tuning(
    worker: &mut Worker,
    config: &AppConfig,
    model_id: &str,
    model: &Path,
) -> Option<shared::cpu_tuning::CpuRuntimeTuningConfig> {
    let topology = shared::cpu_tuning::detect_topology();
    if let Some(existing) = config
        .zenzai
        .cpu_runtime_tuning
        .as_ref()
        .filter(|tuned| tuned.is_valid_for(model_id, &topology.signature()))
    {
        let _ = worker.configure_tuning(
            existing.decode_threads,
            existing.batch_threads,
            existing.direct_model_first,
        );
        return None;
    }
    let _probe_lock = shared::cpu_tuning::CpuProbeLock::acquire_with_timeout(15_000);
    probe_and_select_cpu_tuning(worker, model_id, model)
}

fn measure_tuned_cpu(
    model_id: &str,
    model: &Path,
    dictionary: &Path,
    warmup: &[Case],
    cases: &[Case],
    report_batch: impl FnMut(usize),
) -> Result<(Vec<CaseResult>, Option<shared::cpu_tuning::CpuRuntimeTuningConfig>), String> {
    let scope = ConversionScope::Candidates;
    let (executable, runtime, _) = crate::benchmark_worker_paths("cpu", scope)?;
    let mut worker = Worker::start(
        &executable,
        &runtime,
        model,
        dictionary,
        0,
        scope,
        Some(BACKEND_TIMEOUT),
        None,
    )?;
    let tuned = {
        let _probe_lock = shared::cpu_tuning::CpuProbeLock::acquire_with_timeout(15_000);
        probe_and_select_cpu_tuning(&mut worker, model_id, model)
    };
    let rows = measure_with_worker(&mut worker, warmup, cases, report_batch)?;
    Ok((rows, tuned))
}

fn median_run(mut runs: Vec<CaseResult>) -> Result<CaseResult, String> {
    // 中央値と同じ実行の候補・正誤・N-best時間を結果行に残す。
    runs.sort_by(|a, b| a.latency_ms.total_cmp(&b.latency_ms));
    if runs.len() != MEASUREMENT_PASSES {
        return Err("問題ごとの測定結果が不足しています。".into());
    }
    Ok(runs.swap_remove(MEASUREMENT_PASSES / 2))
}

fn summarize(rows: &[CaseResult]) -> (f64, f64) {
    let mut medians: Vec<_> = rows.iter().map(|row| row.latency_ms).collect();
    medians.sort_by(f64::total_cmp);
    let mean = medians.iter().sum::<f64>() / medians.len() as f64;
    let p95 = medians[(95 * medians.len()).div_ceil(100) - 1];
    (mean, p95)
}

pub(crate) fn run_single(
    config: &AppConfig,
    backend: &'static str,
    gpu_layers: u32,
    mut report: impl FnMut(Progress),
) -> Result<SingleResult, String> {
    benchmark_worker::with_exclusive_run(|| {
        let started = Instant::now();
        let model = crate::get_benchmark_model_path(config)?;
        let (_, _, dictionary) =
            crate::benchmark_worker_paths(backend, ConversionScope::Candidates)?;
        let (warmup, cases) = workload()?;
        let total = cases.len() * MEASUREMENT_PASSES;
        let mut completed = 0;
        let mut worker = Worker::start(
            &crate::benchmark_worker_paths(backend, ConversionScope::Candidates)?.0,
            &crate::benchmark_worker_paths(backend, ConversionScope::Candidates)?.1,
            &model,
            &dictionary,
            gpu_layers,
            ConversionScope::Candidates,
            Some(BACKEND_TIMEOUT),
            None,
        )?;
        let model_id = active_canonical_model_id(config);
        let cpu_tuning = if backend == "cpu" {
            ensure_cpu_worker_tuning(&mut worker, config, &model_id, &model)
        } else {
            None
        };
        let rows = measure_with_worker(&mut worker, &warmup, &cases, |count| {
            completed += count;
            report(Progress {
                backend,
                completed,
                total,
            });
        })?;
        Ok(SingleResult {
            backend,
            gpu_layers,
            elapsed_ms: started.elapsed().as_secs_f64() * 1000.0,
            rows,
            cpu_tuning,
        })
    })
}

fn recommend(results: &[BackendResult]) -> Option<&'static str> {
    let mut measured = results
        .iter()
        .filter_map(|result| Some((result.backend, result.avg_latency_ms?)));
    let first = measured.next()?;
    let Some(second) = measured.next() else {
        return Some(first.0);
    };
    let (faster, slower) = if first.1 <= second.1 {
        (first, second)
    } else {
        (second, first)
    };
    // サブミリ秒の微差や、平均に対して小さい差で切替を勧めない。
    let minimum_gap = (faster.1 * 0.05).max(0.5);
    (slower.1 - faster.1 >= minimum_gap).then_some(faster.0)
}

pub(crate) fn run(
    config: &AppConfig,
    mut report: impl FnMut(Progress),
) -> Result<ResultSummary, String> {
    benchmark_worker::with_exclusive_run(|| {
        let model = crate::get_benchmark_model_path(config)?;
        let (warmup, cases) = workload()?;
        let scope = ConversionScope::Candidates;
        let (_, _, dictionary) = crate::benchmark_worker_paths(&config.zenzai.backend, scope)
            .or_else(|_| crate::benchmark_worker_paths("cpu", scope))?;
        let model_id = active_canonical_model_id(config);
        let vulkan_gpu_layers = config.zenzai.gpu_layers.min(99);
        let per_backend = cases.len() * MEASUREMENT_PASSES;
        let total = per_backend * 2;
        let mut cpu_tuning = None;
        let results = [
            ("cpu", 0, shared::zenzai_cpu_backend_supported()),
            ("vulkan", vulkan_gpu_layers, crate::vulkan_available()),
        ]
        .into_iter()
        .enumerate()
        .map(|(index, (backend, layers, available))| {
            let mut completed = index * per_backend;
            report(Progress {
                backend,
                completed,
                total,
            });
            let measurement = if !available {
                Err("このPCでは利用できません".into())
            } else if backend == "vulkan" && layers == 0 {
                Err("GPUに載せる層数が0のため、GPU推論を測定できません".into())
            } else if backend == "cpu" {
                match measure_tuned_cpu(
                    &model_id,
                    &model,
                    &dictionary,
                    &warmup,
                    &cases,
                    |count| {
                        completed += count;
                        report(Progress {
                            backend,
                            completed,
                            total,
                        });
                    },
                ) {
                    Ok((rows, tuned)) => {
                        cpu_tuning = tuned;
                        Ok(rows)
                    }
                    Err(error) => Err(error),
                }
            } else {
                measure(
                    backend,
                    layers,
                    &model,
                    &dictionary,
                    &warmup,
                    &cases,
                    |count| {
                        completed += count;
                        report(Progress {
                            backend,
                            completed,
                            total,
                        });
                    },
                )
            };
            let end = (index + 1) * per_backend;
            if completed < end {
                report(Progress {
                    backend,
                    completed: end,
                    total,
                });
            }
            match measurement {
                Ok(rows) => {
                    let (mean, p95) = summarize(&rows);
                    BackendResult {
                        backend,
                        avg_latency_ms: Some(mean),
                        p95_latency_ms: Some(p95),
                        error: None,
                    }
                }
                Err(error) => BackendResult {
                    backend,
                    avg_latency_ms: None,
                    p95_latency_ms: None,
                    error: Some(error),
                },
            }
        })
        .collect::<Vec<_>>();
        Ok(ResultSummary {
            model_id,
            vulkan_gpu_layers,
            case_count: cases.len(),
            recommended: recommend(&results),
            cpu_tuning,
            results,
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn result(backend: &'static str, mean: Option<f64>) -> BackendResult {
        BackendResult {
            backend,
            avg_latency_ms: mean,
            p95_latency_ms: None,
            error: None,
        }
    }

    #[test]
    fn timing_summary_uses_per_case_medians() {
        let rows: Vec<_> = [[3_486.0, 8.0, 7.0], [5.0, 6.0, 5.0], [30.0, 40.0, 30.0]]
            .into_iter()
            .enumerate()
            .map(|(id, times)| {
                median_run(
                    times
                        .into_iter()
                        .map(|time| CaseResult {
                            id: id as u32,
                            top1: time.to_string(),
                            candidates: vec![time.to_string()],
                            is_correct: time < 10.0,
                            latency_ms: time,
                            nbest_ms: time / 2.0,
                        })
                        .collect(),
                )
                .unwrap()
            })
            .collect();
        assert_eq!(summarize(&rows), (43.0 / 3.0, 30.0));
        assert_eq!(rows[0].top1, "8");
        assert_eq!(rows[0].nbest_ms, 4.0);
    }

    #[test]
    fn recommends_the_faster_backend_only_for_a_meaningful_gap() {
        for (cpu, vulkan, expected) in [
            (Some(9.3), Some(7.9), Some("vulkan")),
            (Some(12.0), Some(20.0), Some("cpu")),
            (Some(7.04), Some(7.0), None),
            (Some(30.0), Some(31.0), None),
            (None, Some(7.9), Some("vulkan")),
            (None, None, None),
        ] {
            assert_eq!(
                recommend(&[result("cpu", cpu), result("vulkan", vulkan)]),
                expected
            );
        }
    }

    #[test]
    fn quick_workload_covers_all_five_subsets_with_separate_warmups() {
        let (warmup, measured) = workload().unwrap();
        let case_id = |case: &Case| match case {
            Case::Conversion { id, .. } => *id,
            _ => panic!("expected a conversion case"),
        };
        let measured_ids: Vec<_> = measured.iter().map(case_id).collect();
        assert_eq!(measured_ids.len(), 84);
        assert_eq!(warmup.len(), 9);
        assert!(warmup
            .iter()
            .map(case_id)
            .all(|id| !measured_ids.contains(&id)));
        for (range, count) in [
            (1..=230, 23),
            (231..=480, 25),
            (481..=640, 16),
            (641..=740, 10),
            (741..=840, 10),
        ] {
            assert_eq!(
                measured_ids.iter().filter(|&id| range.contains(id)).count(),
                count
            );
        }
    }
}
