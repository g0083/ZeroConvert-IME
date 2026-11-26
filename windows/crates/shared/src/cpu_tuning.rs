use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::Path;

const MODEL_FIRST_DEFAULT_THRESHOLD_BYTES: u64 = 42 * 1024 * 1024;
pub const CURRENT_TUNING_VERSION: u32 = 2;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CpuTopology {
    pub logical_cores: usize,
    pub physical_cores: usize,
    pub performance_physical_cores: usize,
    /// Pairs of `(efficiency_class, physical_core_count)` ordered from highest performance class down.
    pub efficiency_classes: Vec<(u8, usize)>,
    pub source: String,
}

impl CpuTopology {
    pub fn signature(&self) -> String {
        let classes = if self.efficiency_classes.is_empty() {
            self.physical_cores.to_string()
        } else {
            self.efficiency_classes
                .iter()
                .map(|(class_id, count)| format!("{class_id}={count}"))
                .collect::<Vec<_>>()
                .join("+")
        };
        format!(
            "{}:{}:{}:{}",
            self.source, self.logical_cores, self.physical_cores, classes
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeTuningCandidate {
    pub decode_threads: u32,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub batch_threads: Option<u32>,
    pub direct_model_first: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeTuningMeasurement {
    pub decode_threads: u32,
    #[serde(default)]
    pub batch_threads: Option<u32>,
    pub direct_model_first: bool,
    pub mean_ns: u64,
    pub completed_samples: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CpuRuntimeTuningConfig {
    #[serde(default)]
    pub tuning_version: u32,
    pub decode_threads: u32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub batch_threads: Option<u32>,
    pub direct_model_first: bool,
    pub model_id: String,
    pub topology_signature: String,
}

impl CpuRuntimeTuningConfig {
    pub fn is_valid_for(&self, canonical_model_id: &str, topology_signature: &str) -> bool {
        if self.tuning_version != CURRENT_TUNING_VERSION || self.decode_threads == 0 {
            return false;
        }
        if self.topology_signature != topology_signature || self.model_id.is_empty() {
            return false;
        }
        let stored =
            crate::runtime_assets::canonical_model_id(&self.model_id).unwrap_or(&self.model_id);
        let expected = crate::runtime_assets::canonical_model_id(canonical_model_id)
            .unwrap_or(canonical_model_id);
        stored == expected
    }
}

pub fn build_tuning_config(
    winner: &RuntimeTuningMeasurement,
    model_id: &str,
    topology: &CpuTopology,
) -> CpuRuntimeTuningConfig {
    CpuRuntimeTuningConfig {
        tuning_version: CURRENT_TUNING_VERSION,
        decode_threads: winner.decode_threads,
        batch_threads: winner.batch_threads,
        direct_model_first: winner.direct_model_first,
        model_id: crate::runtime_assets::canonical_model_id(model_id)
            .unwrap_or(model_id)
            .to_owned(),
        topology_signature: topology.signature(),
    }
}

/// Cross-process guard preventing background upgrade probing and interactive
/// benchmarks from tuning CPU threads at the same time.
pub struct CpuProbeLock {
    #[cfg(windows)]
    handle: windows::Win32::Foundation::HANDLE,
}

impl CpuProbeLock {
    #[cfg(windows)]
    fn acquire_timeout(timeout_ms: u32) -> Option<Self> {
        use windows::{
            core::w,
            Win32::{
                Foundation::{CloseHandle, WAIT_ABANDONED, WAIT_OBJECT_0},
                System::Threading::{CreateMutexW, WaitForSingleObject},
            },
        };

        let handle =
            unsafe { CreateMutexW(None, false, w!("Local\\ZeroConCpuTuningProbe")) }.ok()?;
        let wait = unsafe { WaitForSingleObject(handle, timeout_ms) };
        if wait == WAIT_OBJECT_0 || wait == WAIT_ABANDONED {
            Some(Self { handle })
        } else {
            let _ = unsafe { CloseHandle(handle) };
            None
        }
    }

    #[cfg(windows)]
    pub fn try_acquire() -> Option<Self> {
        Self::acquire_timeout(0)
    }

    #[cfg(windows)]
    pub fn acquire_with_timeout(timeout_ms: u32) -> Option<Self> {
        Self::acquire_timeout(timeout_ms)
    }

    #[cfg(not(windows))]
    pub fn try_acquire() -> Option<Self> {
        Some(Self {})
    }

    #[cfg(not(windows))]
    pub fn acquire_with_timeout(_timeout_ms: u32) -> Option<Self> {
        Some(Self {})
    }
}

impl Drop for CpuProbeLock {
    fn drop(&mut self) {
        #[cfg(windows)]
        {
            use windows::Win32::{Foundation::CloseHandle, System::Threading::ReleaseMutex};
            let _ = unsafe { ReleaseMutex(self.handle) };
            let _ = unsafe { CloseHandle(self.handle) };
        }
    }
}

#[cfg(windows)]
pub fn probe_cpu_tuning_via_worker(
    worker_exe: &Path,
    runtime_dir: &Path,
    server_dir: &Path,
    model: &Path,
    dictionary: &Path,
    model_id: &str,
    below_normal_priority: bool,
    timeout: std::time::Duration,
) -> Result<Option<CpuRuntimeTuningConfig>, String> {
    use std::io::{BufRead, BufReader, Write};
    use std::os::windows::{io::AsHandle, io::AsRawHandle, process::CommandExt};
    use std::process::{Command, Stdio};
    use std::sync::{atomic::AtomicBool, mpsc, Arc};
    use windows::Win32::{Foundation::HANDLE, System::Threading::TerminateProcess};

    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    const BELOW_NORMAL_PRIORITY_CLASS: u32 = 0x0000_4000;

    let model_str = model.to_str().ok_or("invalid model path")?;
    let dict_str = dictionary.to_str().ok_or("invalid dictionary path")?;
    let path_env = std::env::join_paths(
        [runtime_dir.to_path_buf(), server_dir.to_path_buf()]
            .into_iter()
            .chain(std::env::split_paths(
                &std::env::var_os("PATH").unwrap_or_default(),
            )),
    )
    .map_err(|e| e.to_string())?;

    let flags = if below_normal_priority {
        CREATE_NO_WINDOW | BELOW_NORMAL_PRIORITY_CLASS
    } else {
        CREATE_NO_WINDOW
    };

    let mut child = Command::new(worker_exe)
        .arg("--conversion-benchmark")
        .current_dir(server_dir)
        .env("PATH", path_env)
        .env("AZOOKEY_LLAMA_BACKEND_DIR", runtime_dir)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .creation_flags(flags)
        .spawn()
        .map_err(|e| e.to_string())?;

    let process = child
        .as_handle()
        .try_clone_to_owned()
        .map_err(|e| e.to_string())?;
    let (cancel_tx, cancel_rx) = mpsc::channel();
    let timed_out = Arc::new(AtomicBool::new(false));
    let expired = Arc::clone(&timed_out);
    let _watchdog = std::thread::Builder::new()
        .name("cpu-tuning-watchdog".into())
        .spawn(move || {
            if cancel_rx.recv_timeout(timeout) == Err(mpsc::RecvTimeoutError::Timeout) {
                expired.store(true, std::sync::atomic::Ordering::Relaxed);
                let _ = unsafe { TerminateProcess(HANDLE(process.as_raw_handle()), 1) };
            }
        })
        .ok();

    let result = (|| -> Result<Option<CpuRuntimeTuningConfig>, String> {
        let mut stdin = child.stdin.take().ok_or("missing worker stdin")?;
        let mut stdout = BufReader::new(child.stdout.take().ok_or("missing worker stdout")?);
        writeln!(stdin, "{model_str}\n{dict_str}\n0").map_err(|e| e.to_string())?;
        stdin.flush().map_err(|e| e.to_string())?;

        let mut line = String::new();
        let mut ready = false;
        loop {
            line.clear();
            if stdout.read_line(&mut line).map_err(|e| e.to_string())? == 0 {
                break;
            }
            if line.trim_end() == "benchmark:ready" {
                ready = true;
                break;
            }
        }
        if !ready {
            return Err(if timed_out.load(std::sync::atomic::Ordering::Relaxed) {
                "CPU tuning worker timed out during init".into()
            } else {
                "CPU tuning worker exited before ready".into()
            });
        }

        let topology = detect_topology();
        let model_bytes = std::fs::metadata(model).map(|meta| meta.len()).unwrap_or(0);
        let configs = candidate_configs(&topology, model_bytes);
        serde_json::to_writer(
            &mut stdin,
            &serde_json::json!({ "op": "probe_tuning", "configs": configs }),
        )
        .map_err(|e| e.to_string())?;
        writeln!(stdin).map_err(|e| e.to_string())?;
        stdin.flush().map_err(|e| e.to_string())?;

        let mut reply: Option<String> = None;
        loop {
            line.clear();
            if stdout.read_line(&mut line).map_err(|e| e.to_string())? == 0 {
                break;
            }
            if let Some(payload) = line.trim_end().strip_prefix("benchmark:") {
                reply = Some(payload.to_owned());
                break;
            }
        }
        drop(stdin);

        let payload = reply.ok_or_else(|| "missing probe_tuning response".to_string())?;
        let measurements: Option<Vec<RuntimeTuningMeasurement>> =
            serde_json::from_str(&payload).map_err(|e| e.to_string())?;
        let Some(measurements) = measurements else {
            return Ok(None);
        };
        Ok(select_best(&measurements)
            .map(|winner| build_tuning_config(&winner, model_id, &topology)))
    })();

    let _ = cancel_tx.send(());
    if result.is_err() {
        let _ = child.kill();
    }
    let _ = child.wait();
    result
}

/// Aggregates `(efficiency_class, logical_threads)` records per physical core.
///
/// Higher Windows `EfficiencyClass` values denote higher-performance cores (e.g., P-cores vs E-cores).
pub fn parse_processor_cores(cores: &[(u8, usize)], logical_fallback: usize) -> CpuTopology {
    let fallback_logical = logical_fallback.max(1);
    if cores.is_empty() {
        return CpuTopology {
            logical_cores: fallback_logical,
            physical_cores: fallback_logical,
            performance_physical_cores: fallback_logical,
            efficiency_classes: vec![(0, fallback_logical)],
            source: "available_parallelism".to_string(),
        };
    }

    let mut by_class: BTreeMap<u8, usize> = BTreeMap::new();
    let mut logical_sum = 0usize;
    for &(eff_class, logical_in_core) in cores {
        *by_class.entry(eff_class).or_default() += 1;
        logical_sum += logical_in_core.max(1);
    }

    let physical_cores = cores.len();
    let efficiency_classes: Vec<(u8, usize)> = by_class.into_iter().rev().collect();
    let performance_physical_cores = efficiency_classes
        .first()
        .map(|&(_, count)| count)
        .unwrap_or(physical_cores);

    CpuTopology {
        logical_cores: logical_sum.max(fallback_logical),
        physical_cores,
        performance_physical_cores,
        efficiency_classes,
        source: "win32_processor_info".to_string(),
    }
}

pub fn detect_topology() -> CpuTopology {
    let fallback = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1)
        .max(1);
    #[cfg(windows)]
    {
        if let Some(cores) = query_windows_processor_cores() {
            return parse_processor_cores(&cores, fallback);
        }
    }
    parse_processor_cores(&[], fallback)
}

#[cfg(windows)]
fn query_windows_processor_cores() -> Option<Vec<(u8, usize)>> {
    use windows::Win32::Foundation::ERROR_INSUFFICIENT_BUFFER;
    use windows::Win32::System::SystemInformation::{
        GetLogicalProcessorInformationEx, RelationProcessorCore,
        SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX,
    };

    let mut bytes_required: u32 = 0;
    let first = unsafe {
        GetLogicalProcessorInformationEx(RelationProcessorCore, None, &mut bytes_required)
    };
    if let Err(err) = first {
        if err.code() != ERROR_INSUFFICIENT_BUFFER.to_hresult() || bytes_required == 0 {
            return None;
        }
    } else if bytes_required == 0 {
        return None;
    }

    let mut buffer = vec![0u8; bytes_required as usize];
    let second = unsafe {
        GetLogicalProcessorInformationEx(
            RelationProcessorCore,
            Some(buffer.as_mut_ptr() as *mut SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX),
            &mut bytes_required,
        )
    };
    if second.is_err() {
        return None;
    }

    let used = (bytes_required as usize).min(buffer.len());
    let mut offset = 0usize;
    let mut cores = Vec::new();
    let min_size = std::mem::size_of::<SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX>();
    while offset + min_size <= used {
        let info = unsafe {
            std::ptr::read_unaligned(
                buffer.as_ptr().add(offset) as *const SYSTEM_LOGICAL_PROCESSOR_INFORMATION_EX
            )
        };
        if info.Size == 0 || offset + info.Size as usize > used {
            break;
        }
        if info.Relationship == RelationProcessorCore {
            let proc_rel = unsafe { info.Anonymous.Processor };
            let logical_in_core = (proc_rel.GroupMask[0].Mask.count_ones() as usize).max(1);
            cores.push((proc_rel.EfficiencyClass, logical_in_core));
        }
        offset += info.Size as usize;
    }

    if cores.is_empty() {
        None
    } else {
        Some(cores)
    }
}

pub fn candidate_configs(
    topology: &CpuTopology,
    model_size_bytes: u64,
) -> Vec<RuntimeTuningCandidate> {
    let logical = u32::try_from(topology.logical_cores.max(1)).unwrap_or(u32::MAX);
    let perf_cores = u32::try_from(topology.performance_physical_cores.max(1)).unwrap_or(u32::MAX);
    let default_decode = (logical / 2).clamp(2, 6).min(logical);
    let default_batch = logical
        .saturating_sub(2)
        .max(default_decode)
        .min(12)
        .min(logical);

    let mut raw_pairs = vec![
        (2u32, 2u32),
        (4, 4),
        (perf_cores.clamp(2, 6), perf_cores.clamp(2, 8)),
    ];
    if perf_cores >= 4 {
        raw_pairs.push((4, perf_cores.clamp(4, 8)));
    }
    raw_pairs.push((default_decode, default_batch));

    let mut distinct_pairs = Vec::with_capacity(raw_pairs.len());
    for (raw_decode, raw_batch) in raw_pairs {
        let decode = raw_decode.clamp(1, logical);
        let batch = raw_batch.clamp(decode, logical);
        if !distinct_pairs.contains(&(decode, batch)) {
            distinct_pairs.push((decode, batch));
        }
    }

    let preferred_direct_first =
        model_size_bytes > 0 && model_size_bytes < MODEL_FIRST_DEFAULT_THRESHOLD_BYTES;
    let modes = [preferred_direct_first, !preferred_direct_first];

    let mut candidates = Vec::with_capacity(distinct_pairs.len() * 2);
    for (decode, batch) in distinct_pairs {
        for direct_model_first in modes {
            candidates.push(RuntimeTuningCandidate {
                decode_threads: decode,
                batch_threads: Some(batch),
                direct_model_first,
            });
        }
    }
    candidates
}

pub fn select_best(measurements: &[RuntimeTuningMeasurement]) -> Option<RuntimeTuningMeasurement> {
    let valid: Vec<&RuntimeTuningMeasurement> = measurements
        .iter()
        .filter(|m| m.completed_samples > 0 && m.mean_ns > 0)
        .collect();
    let min_mean = valid.iter().map(|m| m.mean_ns).min()?;
    let threshold = min_mean.saturating_add(min_mean.saturating_mul(3) / 100);
    valid
        .into_iter()
        .filter(|m| m.mean_ns <= threshold)
        .min_by_key(|m| {
            (
                m.decode_threads,
                m.mean_ns,
                m.batch_threads.unwrap_or(u32::MAX),
                !m.direct_model_first,
            )
        })
        .cloned()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn hybrid(p_cores: usize, p_smt: usize, e_cores: usize) -> Vec<(u8, usize)> {
        let mut cores = Vec::with_capacity(p_cores + e_cores);
        cores.extend(std::iter::repeat_n((1u8, p_smt), p_cores));
        cores.extend(std::iter::repeat_n((0u8, 1usize), e_cores));
        cores
    }

    #[test]
    fn parses_hybrid_and_homogeneous_topologies() {
        let intel_6p_8e = parse_processor_cores(&hybrid(6, 2, 8), 20);
        assert_eq!(intel_6p_8e.logical_cores, 20);
        assert_eq!(intel_6p_8e.physical_cores, 14);
        assert_eq!(intel_6p_8e.performance_physical_cores, 6);
        assert_eq!(intel_6p_8e.efficiency_classes, vec![(1, 6), (0, 8)]);
        assert_eq!(
            intel_6p_8e.signature(),
            "win32_processor_info:20:14:1=6+0=8"
        );

        let intel_8p_16e = parse_processor_cores(&hybrid(8, 2, 16), 32);
        assert_eq!(intel_8p_16e.performance_physical_cores, 8);
        assert_eq!(intel_8p_16e.logical_cores, 32);

        let ryzen_8c = parse_processor_cores(&vec![(0u8, 2); 8], 16);
        assert_eq!(ryzen_8c.logical_cores, 16);
        assert_eq!(ryzen_8c.physical_cores, 8);
        assert_eq!(ryzen_8c.performance_physical_cores, 8);
        assert_eq!(ryzen_8c.efficiency_classes, vec![(0, 8)]);

        let laptop_4c = parse_processor_cores(&vec![(0u8, 2); 4], 8);
        assert_eq!(laptop_4c.logical_cores, 8);
        assert_eq!(laptop_4c.performance_physical_cores, 4);

        let fallback = parse_processor_cores(&[], 6);
        assert_eq!(fallback.source, "available_parallelism");
        assert_eq!(fallback.logical_cores, 6);
        assert_eq!(fallback.performance_physical_cores, 6);
    }

    #[test]
    fn generates_deduplicated_candidates_without_model_hardcoding() {
        let intel_6p_8e = parse_processor_cores(&hybrid(6, 2, 8), 20);
        let small = candidate_configs(&intel_6p_8e, 19_600_000);
        let pairs: Vec<(u32, Option<u32>, bool)> = small
            .iter()
            .map(|c| (c.decode_threads, c.batch_threads, c.direct_model_first))
            .collect();
        assert_eq!(
            pairs,
            vec![
                (2, Some(2), true),
                (2, Some(2), false),
                (4, Some(4), true),
                (4, Some(4), false),
                (6, Some(6), true),
                (6, Some(6), false),
                (4, Some(6), true),
                (4, Some(6), false),
                (6, Some(12), true),
                (6, Some(12), false),
            ]
        );

        let laptop_4c = parse_processor_cores(&vec![(0u8, 2); 4], 8);
        let large = candidate_configs(&laptop_4c, 60_000_000);
        assert_eq!(
            large
                .iter()
                .map(|c| (c.decode_threads, c.batch_threads, c.direct_model_first))
                .collect::<Vec<_>>(),
            vec![
                (2, Some(2), false),
                (2, Some(2), true),
                (4, Some(4), false),
                (4, Some(4), true),
                (4, Some(6), false),
                (4, Some(6), true),
            ]
        );

        let dual = parse_processor_cores(&vec![(0u8, 1); 2], 2);
        let dual_cands = candidate_configs(&dual, 19_600_000);
        assert!(dual_cands.iter().all(|c| c.decode_threads <= 2));
        assert!(dual_cands.iter().all(|c| c.batch_threads.unwrap_or(0) <= 2));
    }

    #[test]
    fn select_best_prefers_fewer_decode_threads_within_three_percent_without_downgrading_batch() {
        let picked_fewer_decode = select_best(&[
            RuntimeTuningMeasurement {
                decode_threads: 2,
                batch_threads: Some(2),
                direct_model_first: true,
                mean_ns: 10_200_000,
                completed_samples: 4,
            },
            RuntimeTuningMeasurement {
                decode_threads: 4,
                batch_threads: Some(6),
                direct_model_first: true,
                mean_ns: 10_000_000,
                completed_samples: 4,
            },
        ])
        .unwrap();
        assert_eq!(picked_fewer_decode.decode_threads, 2);

        let preserved_faster_batch = select_best(&[
            RuntimeTuningMeasurement {
                decode_threads: 4,
                batch_threads: Some(4),
                direct_model_first: false,
                mean_ns: 28_300_000,
                completed_samples: 4,
            },
            RuntimeTuningMeasurement {
                decode_threads: 4,
                batch_threads: Some(6),
                direct_model_first: false,
                mean_ns: 27_450_000,
                completed_samples: 4,
            },
        ])
        .unwrap();
        assert_eq!(preserved_faster_batch.batch_threads, Some(6));

        assert!(select_best(&[RuntimeTuningMeasurement {
            decode_threads: 2,
            batch_threads: Some(2),
            direct_model_first: true,
            mean_ns: 10_000_000,
            completed_samples: 0,
        }])
        .is_none());
    }

    #[test]
    fn detect_topology_succeeds_on_host() {
        let topology = detect_topology();
        assert!(topology.logical_cores >= 1);
        assert!(topology.physical_cores >= 1);
        assert!(topology.performance_physical_cores >= 1);
        #[cfg(windows)]
        assert_eq!(topology.source, "win32_processor_info");
    }

    #[test]
    fn tuning_validity_requires_current_version_topology_and_canonical_model() {
        let legacy: CpuRuntimeTuningConfig = serde_json::from_str(
            r#"{"decode_threads":4,"batch_threads":6,"direct_model_first":true,"model_id":"nano_v4_2_iq4_emb8","topology_signature":"win32_processor_info:20:14:1=6+0=8"}"#,
        )
        .unwrap();
        assert_eq!(legacy.tuning_version, 0);
        assert!(!legacy.is_valid_for("nano_v4_2_iq4_emb8", "win32_processor_info:20:14:1=6+0=8"));

        let current = CpuRuntimeTuningConfig {
            tuning_version: CURRENT_TUNING_VERSION,
            decode_threads: 4,
            batch_threads: Some(6),
            direct_model_first: true,
            model_id: "standard_v4_2".into(),
            topology_signature: "win32_processor_info:20:14:1=6+0=8".into(),
        };
        assert!(current.is_valid_for("standard_v4_2_q8", "win32_processor_info:20:14:1=6+0=8"));
        assert!(!current.is_valid_for("nano_v4_2_iq4_emb8", "win32_processor_info:20:14:1=6+0=8"));
        assert!(!current.is_valid_for("standard_v4_2_q8", "win32_processor_info:16:8:0=8"));
    }
}
