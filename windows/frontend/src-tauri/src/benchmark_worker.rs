use azookey_server::ai_assist::benchmark::{
    self, CandidateCompletion, Case, Completion, ConversionScope, WindowsTypoOptions,
};
use serde_json::{json, Value};
use std::{
    io::{BufRead, BufReader, Write},
    os::windows::{
        io::{AsHandle, AsRawHandle},
        process::CommandExt,
    },
    path::{Path, PathBuf},
    process::{Child, ChildStdin, ChildStdout, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex, MutexGuard, TryLockError,
    },
    time::{Duration, Instant},
};
use windows::Win32::{Foundation::HANDLE, System::Threading::TerminateProcess};

pub struct Worker {
    child: Child,
    input: ChildStdin,
    output: BufReader<ChildStdout>,
    deadline: Option<Deadline>,
}

struct Deadline {
    _cancel: mpsc::Sender<()>,
    expired: Arc<AtomicBool>,
}

impl Deadline {
    fn start(child: &Child, duration: Duration) -> Result<Self, String> {
        let process = child
            .as_handle()
            .try_clone_to_owned()
            .map_err(|e| e.to_string())?;
        let (cancel, receiver) = mpsc::channel();
        let expired = Arc::new(AtomicBool::new(false));
        let timed_out = Arc::clone(&expired);
        std::thread::Builder::new()
            .name("quick-benchmark-deadline".into())
            .spawn(move || {
                if receiver.recv_timeout(duration) == Err(mpsc::RecvTimeoutError::Timeout) {
                    timed_out.store(true, Ordering::Relaxed);
                    let _ = unsafe { TerminateProcess(HANDLE(process.as_raw_handle()), 1) };
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(Self {
            _cancel: cancel,
            expired,
        })
    }
}

impl Worker {
    pub(crate) fn start(
        executable: &Path,
        runtime: &Path,
        model: &Path,
        dictionary: &Path,
        layers: u32,
        scope: ConversionScope,
        timeout: Option<Duration>,
        typo_options: Option<&WindowsTypoOptions>,
    ) -> Result<Self, String> {
        let model = model.to_str().ok_or("invalid benchmark model path")?;
        let path = std::env::join_paths(std::iter::once(runtime.to_owned()).chain(
            std::env::split_paths(&std::env::var_os("PATH").unwrap_or_default()),
        ))
        .map_err(|e| e.to_string())?;
        let mut child = Command::new(executable)
            .arg(match scope {
                ConversionScope::Model => "--benchmark",
                ConversionScope::Candidates => "--conversion-benchmark",
                ConversionScope::Typo => "--typo-benchmark",
            })
            .env("PATH", path)
            .env("AZOOKEY_LLAMA_BACKEND_DIR", runtime)
            .env("AZOOKEY_KEYBOARD_PRIOR_GPU_LAYERS", layers.to_string())
            .env(
                "AZOOKEY_BENCHMARK_KEYBOARD_TYPO",
                if typo_options.is_some_and(|options| options.enabled) {
                    "1"
                } else {
                    "0"
                },
            )
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .spawn()
            .map_err(|e| format!("cannot start benchmark worker: {e}"))?;
        let input = child.stdin.take().expect("piped stdin");
        let output = BufReader::new(child.stdout.take().expect("piped stdout"));
        let mut worker = Self {
            child,
            input,
            output,
            deadline: None,
        };
        worker.deadline = timeout
            .map(|timeout| Deadline::start(&worker.child, timeout))
            .transpose()?;
        writeln!(worker.input, "{model}").map_err(|e| e.to_string())?;
        if scope != ConversionScope::Model {
            let dictionary = dictionary
                .to_str()
                .ok_or("invalid benchmark dictionary path")?;
            writeln!(worker.input, "{dictionary}\n{layers}").map_err(|e| e.to_string())?;
            if let Some(options) = typo_options {
                serde_json::to_writer(&mut worker.input, options)
                    .map_err(|error| error.to_string())?;
                writeln!(worker.input).map_err(|error| error.to_string())?;
            }
            if worker.candidate_line().as_deref() != Some(b"ready\n") {
                let message = if scope == ConversionScope::Typo {
                    "タイポ補正を初期化できませんでした。モデル・日本語辞書・バックエンドを確認してください。"
                } else {
                    "共通候補生成を初期化できませんでした。モデル・日本語辞書・バックエンドを確認してください。"
                };
                return Err(worker.candidate_error(message.into()));
            }
            return Ok(worker);
        }
        let mut line = String::new();
        worker
            .output
            .read_line(&mut line)
            .map_err(|e| e.to_string())?;
        if line.trim_end() != "ready" {
            return Err("ベンチマーク用モデルを読み込めませんでした。".into());
        }
        Ok(worker)
    }

    pub fn complete(&mut self, prompt: &str, tokens: u64, timeout: u64) -> Option<Completion> {
        writeln!(self.input, "{tokens} {timeout} {}", prompt.len()).ok()?;
        self.input.write_all(prompt.as_bytes()).ok()?;
        let mut line = Vec::new();
        self.output.read_until(b'\n', &mut line).ok()?;
        serde_json::from_slice(&line).ok()
    }

    fn candidate_line(&mut self) -> Option<Vec<u8>> {
        let mut line = Vec::new();
        while self.output.read_until(b'\n', &mut line).ok()? != 0 {
            if let Some(payload) = line.strip_prefix(b"benchmark:") {
                return Some(payload.to_vec());
            }
            line.clear();
        }
        None
    }

    fn candidates(&mut self, reading: &str, context: &str) -> Option<CandidateCompletion> {
        serde_json::to_writer(&mut self.input, &(reading, context)).ok()?;
        writeln!(self.input).ok()?;
        serde_json::from_slice(&self.candidate_line()?).ok()
    }

    fn candidate_error(&self, error: String) -> String {
        if self
            .deadline
            .as_ref()
            .is_some_and(|deadline| deadline.expired.load(Ordering::Relaxed))
        {
            return "共通候補生成の測定がタイムアウトしました。".into();
        }
        error
    }

    pub(crate) fn run_candidates(&mut self, cases: &[Case]) -> Result<Vec<Value>, String> {
        benchmark::run_candidate_batch(cases, |reading, context| self.candidates(reading, context))
            .map_err(|error| self.candidate_error(error))
    }

    pub(crate) fn probe_tuning(
        &mut self,
        configs: &[shared::cpu_tuning::RuntimeTuningCandidate],
    ) -> Result<Vec<shared::cpu_tuning::RuntimeTuningMeasurement>, String> {
        serde_json::to_writer(&mut self.input, &json!({ "op": "probe_tuning", "configs": configs }))
            .map_err(|error| error.to_string())?;
        writeln!(self.input).map_err(|error| error.to_string())?;
        let reply = self
            .candidate_line()
            .ok_or_else(|| self.candidate_error("CPU最適化の計測応答がありません。".into()))?;
        let parsed: Option<Vec<shared::cpu_tuning::RuntimeTuningMeasurement>> =
            serde_json::from_slice(&reply).map_err(|error| error.to_string())?;
        Ok(parsed.unwrap_or_default())
    }

    pub(crate) fn configure_tuning(
        &mut self,
        decode_threads: u32,
        batch_threads: Option<u32>,
        direct_model_first: bool,
    ) -> Result<(), String> {
        serde_json::to_writer(
            &mut self.input,
            &json!({
                "op": "configure_tuning",
                "decodeThreads": decode_threads,
                "batchThreads": batch_threads,
                "directModelFirst": direct_model_first,
            }),
        )
        .map_err(|error| error.to_string())?;
        writeln!(self.input).map_err(|error| error.to_string())?;
        let reply = self
            .candidate_line()
            .ok_or_else(|| self.candidate_error("CPU最適化設定の適用応答がありません。".into()))?;
        let parsed: Value = serde_json::from_slice(&reply).map_err(|error| error.to_string())?;
        if parsed["applied"].as_bool() == Some(true) {
            Ok(())
        } else {
            Err("CPU最適化設定を適用できませんでした。".into())
        }
    }

    fn run_typos(&mut self, cases: &[Case]) -> Result<Vec<Value>, String> {
        if cases.is_empty() || cases.len() > benchmark::BATCH_SIZE {
            return Err("invalid benchmark batch size".into());
        }
        cases
            .iter()
            .map(|case| {
                serde_json::to_writer(&mut self.input, case).map_err(|e| e.to_string())?;
                writeln!(self.input).map_err(|e| e.to_string())?;
                let reply = self
                    .candidate_line()
                    .ok_or("タイポ補正ワーカーから応答がありません。")?;
                let row: Value = serde_json::from_slice(&reply).map_err(|e| e.to_string())?;
                if let Some(error) = row["error"].as_str() {
                    return Err(error.into());
                }
                Ok(row)
            })
            .collect()
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

struct Run {
    id: u64,
    started: Instant,
    worker: Arc<Mutex<Worker>>,
    dictionary: PathBuf,
    scope: ConversionScope,
}

#[derive(Default)]
struct State {
    next_id: u64,
    run: Option<Run>,
}

static STATE: Mutex<State> = Mutex::new(State {
    next_id: 0,
    run: None,
});

fn lock_idle() -> Result<MutexGuard<'static, State>, String> {
    let state = STATE.try_lock().map_err(|error| match error {
        TryLockError::WouldBlock => "ベンチマークは実行中です。".to_owned(),
        TryLockError::Poisoned(_) => error.to_string(),
    })?;
    if state.run.is_some() {
        return Err("ベンチマークは実行中です。".into());
    }
    Ok(state)
}

pub(crate) fn with_exclusive_run<T>(run: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    let _state = lock_idle()?;
    run()
}

pub fn start_with_cpu_tuning(
    executable: PathBuf,
    runtime: PathBuf,
    model: PathBuf,
    dictionary: PathBuf,
    layers: u32,
    scope: ConversionScope,
    typo_options: Option<&WindowsTypoOptions>,
    cpu_tuning_config: Option<(&shared::AppConfig, &str, &str)>,
) -> Result<(Value, Option<shared::cpu_tuning::CpuRuntimeTuningConfig>), String> {
    let mut state = lock_idle()?;
    let started = Instant::now();
    benchmark::reset_session();
    let mut worker = Worker::start(
        &executable,
        &runtime,
        &model,
        &dictionary,
        layers,
        scope,
        None,
        typo_options,
    )?;
    let newly_tuned = match cpu_tuning_config {
        Some((config, backend, model_id))
            if backend == "cpu" && scope != ConversionScope::Model =>
        {
            crate::quick_benchmark::ensure_cpu_worker_tuning(&mut worker, config, model_id, &model)
        }
        _ => None,
    };
    state.next_id += 1;
    let id = state.next_id;
    state.run = Some(Run {
        id,
        started,
        worker: Arc::new(Mutex::new(worker)),
        dictionary,
        scope,
    });
    Ok((
        json!({"id": id, "batch_size": benchmark::BATCH_SIZE}),
        newly_tuned,
    ))
}

#[allow(dead_code)]
pub fn start(
    executable: PathBuf,
    runtime: PathBuf,
    model: PathBuf,
    dictionary: PathBuf,
    layers: u32,
    scope: ConversionScope,
    typo_options: Option<&WindowsTypoOptions>,
) -> Result<Value, String> {
    let (session, _) = start_with_cpu_tuning(
        executable,
        runtime,
        model,
        dictionary,
        layers,
        scope,
        typo_options,
        None,
    )?;
    Ok(session)
}

pub fn run(id: u64, cases: Vec<Case>) -> Result<Vec<Value>, String> {
    let state = STATE.lock().map_err(|e| e.to_string())?;
    let run = state
        .run
        .as_ref()
        .filter(|run| run.id == id)
        .ok_or("benchmark session expired")?;
    let worker = Arc::clone(&run.worker);
    if run.scope == ConversionScope::Candidates {
        return worker
            .lock()
            .map_err(|e| e.to_string())?
            .run_candidates(&cases);
    }
    if run.scope == ConversionScope::Typo {
        return worker.lock().map_err(|e| e.to_string())?.run_typos(&cases);
    }
    benchmark::run_batch(
        &cases,
        &run.dictionary.join("Reverse/reverse-v2.bin"),
        move |prompt, tokens, timeout| worker.lock().ok()?.complete(prompt, tokens, timeout),
    )
}

pub fn finish(id: u64) -> Result<f64, String> {
    let mut state = STATE.lock().map_err(|e| e.to_string())?;
    let run = state
        .run
        .take_if(|run| run.id == id)
        .ok_or("benchmark session expired")?;
    let started = run.started;
    drop(run);
    Ok(started.elapsed().as_secs_f64() * 1000.0)
}

pub fn close() {
    if let Ok(mut state) = STATE.lock() {
        state.run = None;
    }
}
