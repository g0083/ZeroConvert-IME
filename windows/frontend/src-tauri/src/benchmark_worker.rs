use azookey_server::ai_assist::benchmark::{
    self, CandidateCompletion, Case, Completion, ConversionScope,
};
use serde_json::{json, Value};
use std::{
    io::{BufRead, BufReader, Write},
    os::windows::process::CommandExt,
    path::{Path, PathBuf},
    process::{Child, ChildStdin, ChildStdout, Command, Stdio},
    sync::{Arc, Mutex},
    time::Instant,
};

pub struct Worker {
    child: Child,
    input: ChildStdin,
    output: BufReader<ChildStdout>,
}

impl Worker {
    fn start(
        executable: &Path,
        runtime: &Path,
        model: &Path,
        dictionary: &Path,
        layers: u32,
        scope: ConversionScope,
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
            })
            .env("PATH", path)
            .env("AZOOKEY_LLAMA_BACKEND_DIR", runtime)
            .env("AZOOKEY_KEYBOARD_PRIOR_GPU_LAYERS", layers.to_string())
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
        };
        writeln!(worker.input, "{model}").map_err(|e| e.to_string())?;
        if scope == ConversionScope::Candidates {
            let dictionary = dictionary
                .to_str()
                .ok_or("invalid benchmark dictionary path")?;
            writeln!(worker.input, "{dictionary}\n{layers}").map_err(|e| e.to_string())?;
            if worker.candidate_line().as_deref() != Some(b"ready\n") {
                return Err(
                    "共通候補生成を初期化できませんでした。モデルと日本語辞書を確認してください。"
                        .into(),
                );
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

pub fn start(
    executable: PathBuf,
    runtime: PathBuf,
    model: PathBuf,
    dictionary: PathBuf,
    layers: u32,
    scope: ConversionScope,
) -> Result<Value, String> {
    let mut state = STATE.lock().map_err(|e| e.to_string())?;
    if state.run.is_some() {
        return Err("ベンチマークは実行中です。".into());
    }
    let started = Instant::now();
    benchmark::reset_session();
    let worker = Worker::start(&executable, &runtime, &model, &dictionary, layers, scope)?;
    state.next_id += 1;
    let id = state.next_id;
    state.run = Some(Run {
        id,
        started,
        worker: Arc::new(Mutex::new(worker)),
        dictionary,
        scope,
    });
    Ok(json!({"id": id, "batch_size": benchmark::BATCH_SIZE}))
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
        let mut worker = worker.lock().map_err(|e| e.to_string())?;
        return benchmark::run_candidate_batch(&cases, |reading, context| {
            worker.candidates(reading, context)
        });
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
