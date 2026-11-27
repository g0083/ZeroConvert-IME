//! Keyboard-channel correction on the assistance worker. Never mutates Swift.
//! The native child owns the active conversion model/dictionary; each request has a deadline.
use super::{corrector, runtime, AiAssistConfig, TypoSuggestion};
use serde_json::Value;
use std::{
    io::{BufRead, BufReader, Write},
    path::{Path, PathBuf},
    process::{Child, ChildStdin, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Mutex,
    },
    time::{Duration, Instant},
};

static WORKER: Mutex<Option<Worker>> = Mutex::new(None);
static STARTING: AtomicBool = AtomicBool::new(false);
const WORKER_STARTUP_TIMEOUT: Duration = Duration::from_secs(45);
const WORKER_STALL_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Clone, Debug, PartialEq, Eq)]
struct WorkerSpec {
    model: PathBuf,
    runtime_dir: PathBuf,
    gpu_layers: u32,
    decode_threads: Option<u32>,
    batch_threads: Option<u32>,
}

struct Worker {
    child: Child,
    input: ChildStdin,
    output: WorkerOutput,
    spec: WorkerSpec,
}

struct WorkerOutput {
    lines: mpsc::Receiver<String>,
    pending_since: Option<Instant>,
}

impl WorkerOutput {
    fn available(&mut self) -> Result<bool, ()> {
        let Some(sent) = self.pending_since else {
            return Ok(true);
        };
        match self.lines.try_recv() {
            Ok(_) => {
                // The previous caller has already moved on. Drop its reply before
                // sending another request, even when the new reading is identical.
                self.pending_since = None;
                Ok(true)
            }
            Err(mpsc::TryRecvError::Empty) if sent.elapsed() < WORKER_STALL_TIMEOUT => Ok(false),
            Err(_) => Err(()),
        }
    }

    fn receive_reply(&mut self, timeout: Duration) -> Result<Option<Value>, ()> {
        match self.lines.recv_timeout(timeout) {
            Ok(reply) => {
                self.pending_since = None;
                serde_json::from_str(&reply).map(Some).map_err(|_| ())
            }
            // A late GPU pipeline can still finish. Keep the worker and drain its
            // reply later rather than repeating the same cold decode on restart.
            Err(mpsc::RecvTimeoutError::Timeout) => Ok(None),
            Err(mpsc::RecvTimeoutError::Disconnected) => Err(()),
        }
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn repo_root_from_manifest() -> Option<PathBuf> {
    let manifest = Path::new(env!("CARGO_MANIFEST_DIR"));
    for ancestor in manifest.ancestors() {
        if ancestor.join("windows/native/keyboard-prior").is_dir() {
            return Some(ancestor.to_path_buf());
        }
    }
    None
}

fn find_worker_executable(root: &Path) -> Option<PathBuf> {
    let mut candidates = vec![
        root.join("keyboard-prior.exe"),
        root.join("llama_cpu/keyboard-prior.exe"),
    ];
    if let Some(repo) = repo_root_from_manifest() {
        candidates.push(repo.join("ZeroConvertIME-windows/keyboard-prior.exe"));
        candidates.push(repo.join("ZeroConvertIME-windows/llama_cpu/keyboard-prior.exe"));
    }
    candidates.into_iter().find(|path| path.is_file())
}

fn vulkan_loader_available() -> bool {
    let in_path = std::env::var_os("PATH")
        .into_iter()
        .flat_map(|paths| std::env::split_paths(&paths).collect::<Vec<_>>())
        .any(|dir| dir.join("vulkan-1.dll").is_file());
    if in_path {
        return true;
    }
    std::env::var_os("SystemRoot")
        .map(PathBuf::from)
        .is_some_and(|sys| sys.join("System32/vulkan-1.dll").is_file())
}

fn resolve_runtime_preferences_from_str(settings_json: &str) -> (String, String, u32) {
    let value = serde_json::from_str::<Value>(settings_json).ok();
    let zenzai = value.as_ref().and_then(|v| v.get("zenzai"));
    let model = zenzai
        .and_then(|z| z.get("model"))
        .and_then(Value::as_str)
        .map(str::to_owned)
        .unwrap_or_else(shared::runtime_assets::default_model);
    let raw_backend = zenzai
        .and_then(|z| z.get("backend"))
        .and_then(Value::as_str)
        .unwrap_or("vulkan");
    let backend =
        if raw_backend.eq_ignore_ascii_case("vulkan") || raw_backend.eq_ignore_ascii_case("cuda") {
            "vulkan".to_owned()
        } else {
            "cpu".to_owned()
        };
    let configured_layers = zenzai
        .and_then(|z| z.get("gpu_layers"))
        .and_then(Value::as_u64)
        .map(|n| u32::try_from(n).unwrap_or(99))
        .unwrap_or(99)
        .min(99);
    let gpu_layers = if backend == "vulkan" {
        configured_layers
    } else {
        0
    };
    (model, backend, gpu_layers)
}

fn canonical_model_key(raw: &str) -> &str {
    shared::runtime_assets::canonical_model_id(raw).unwrap_or_else(|| raw.trim())
}

fn resolve_cpu_tuning_threads_from_str(
    settings_json: &str,
    selected_model: &str,
    backend: &str,
) -> (Option<u32>, Option<u32>) {
    if backend != "cpu" {
        return (None, None);
    }
    let value = serde_json::from_str::<Value>(settings_json).ok();
    let Some(tuning) = value
        .as_ref()
        .and_then(|v| v.get("zenzai"))
        .and_then(|z| z.get("cpu_runtime_tuning"))
    else {
        return (None, None);
    };
    let tuning_version = tuning
        .get("tuning_version")
        .and_then(Value::as_u64)
        .unwrap_or(0);
    if tuning_version < 2 {
        return (None, None);
    }
    let Some(tuned_model) = tuning.get("model_id").and_then(Value::as_str) else {
        return (None, None);
    };
    if canonical_model_key(tuned_model) != canonical_model_key(selected_model) {
        return (None, None);
    }
    let Some(decode_raw) = tuning.get("decode_threads").and_then(Value::as_u64) else {
        return (None, None);
    };
    let Ok(decode_u32) = u32::try_from(decode_raw) else {
        return (None, None);
    };
    if !(1..=32).contains(&decode_u32) {
        return (None, None);
    }
    let batch_u32 = tuning
        .get("batch_threads")
        .and_then(Value::as_u64)
        .and_then(|n| u32::try_from(n).ok())
        .map(|n| n.clamp(1, 64))
        .unwrap_or(decode_u32);
    (Some(decode_u32), Some(batch_u32))
}

fn read_runtime_preferences_with_tuning() -> (String, String, u32, Option<u32>, Option<u32>) {
    let settings_json = runtime::settings_path()
        .and_then(|p| std::fs::read_to_string(p).ok())
        .unwrap_or_default();
    let (model, backend, configured_layers) = resolve_runtime_preferences_from_str(&settings_json);
    let (decode_threads, batch_threads) =
        resolve_cpu_tuning_threads_from_str(&settings_json, &model, &backend);
    (
        model,
        backend,
        configured_layers,
        decode_threads,
        batch_threads,
    )
}

fn find_runtime_dir_and_layers(
    root: &Path,
    executable: &Path,
    backend: &str,
    gpu_layers: u32,
) -> (PathBuf, u32) {
    let exec_parent = executable.parent().unwrap_or(root);
    // Unified layout and legacy layout place keyboard-prior.exe inside a
    // `llama*` directory, where Windows forces static DLL imports from
    // `executable.parent()` regardless of PATH.
    if exec_parent
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name.starts_with("llama_"))
    {
        let layers = if exec_parent
            .file_name()
            .and_then(|name| name.to_str())
            .is_some_and(|name| name.eq_ignore_ascii_case("llama_vulkan"))
            && vulkan_loader_available()
        {
            gpu_layers
        } else {
            0
        };
        return (exec_parent.to_path_buf(), layers);
    }
    if exec_parent
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name.eq_ignore_ascii_case("llama"))
    {
        let layers = if backend.eq_ignore_ascii_case("vulkan") && vulkan_loader_available() {
            gpu_layers
        } else {
            0
        };
        return (exec_parent.to_path_buf(), layers);
    }

    let install_root = if executable.starts_with(root) {
        root
    } else {
        exec_parent
    };
    // Unified `llama/` serves both backends; Vulkan only differs by -ngl.
    let unified_dir = install_root.join("llama");
    if unified_dir.join("llama.dll").is_file() {
        let layers = if backend.eq_ignore_ascii_case("vulkan") && vulkan_loader_available() {
            gpu_layers
        } else {
            0
        };
        return (unified_dir, layers);
    }
    if backend.eq_ignore_ascii_case("vulkan") {
        let vulkan_dir = install_root.join("llama_vulkan");
        if vulkan_dir.join("llama.dll").is_file() && vulkan_loader_available() {
            return (vulkan_dir, gpu_layers);
        }
    }
    let cpu_dir = install_root.join("llama_cpu");
    if cpu_dir.join("llama.dll").is_file() {
        return (cpu_dir, 0);
    }
    (exec_parent.to_path_buf(), 0)
}

fn resolve_active_model(root: &Path, selected_id: &str) -> Option<PathBuf> {
    let filename = shared::runtime_assets::model_filename(selected_id)
        .unwrap_or(shared::runtime_assets::STANDARD_IQ4);
    for candidate in [
        root.join("models").join(filename),
        shared::runtime_assets::model_download_root().join(filename),
        root.join("models/zerocon-nano-v4.2b-iq4nl-emb8.gguf"),
        root.join("models/zerocon-nano-v4.2b-q8_0.gguf"),
    ] {
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    let repo_root = repo_root_from_manifest()?;
    for candidate in [
        repo_root.join("artifacts/models/zerocon").join(filename),
        repo_root.join("artifacts/models").join(filename),
        repo_root
            .join("ZeroConvertIME-windows/models")
            .join(filename),
        repo_root.join("artifacts/models/zerocon/zerocon-nano-v4.2b-iq4nl-emb8.gguf"),
        repo_root.join("artifacts/models/zerocon-nano-v4.2b-iq4nl-emb8.gguf"),
    ] {
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

fn resolve_worker_spec(root: &Path, executable: &Path) -> Option<WorkerSpec> {
    let (model_id, backend, configured_layers, cfg_decode, cfg_batch) =
        read_runtime_preferences_with_tuning();
    let model = resolve_active_model(root, &model_id)?;
    let (runtime_dir, gpu_layers) =
        find_runtime_dir_and_layers(root, executable, &backend, configured_layers);
    let (decode_threads, batch_threads) = if gpu_layers == 0 {
        (cfg_decode, cfg_batch)
    } else {
        (None, None)
    };
    Some(WorkerSpec {
        model,
        runtime_dir,
        gpu_layers,
        decode_threads,
        batch_threads,
    })
}

impl Worker {
    fn start(root: &Path, dictionary: &Path) -> Option<Self> {
        let executable = find_worker_executable(root)?;
        let spec = resolve_worker_spec(root, &executable)?;
        Self::start_with_spec(&executable, spec, dictionary)
    }

    fn start_with_spec(executable: &Path, spec: WorkerSpec, dictionary: &Path) -> Option<Self> {
        if !dictionary.is_file() {
            return None;
        }
        let existing_path = std::env::var("PATH").unwrap_or_default();
        let mut command = Command::new(executable);
        command
            .arg("--assist")
            .arg(&spec.model)
            .arg(dictionary)
            .current_dir(&spec.runtime_dir)
            .env(
                "PATH",
                format!("{};{existing_path}", spec.runtime_dir.display()),
            )
            .env("AZOOKEY_LLAMA_BACKEND_DIR", &spec.runtime_dir)
            .env(
                "AZOOKEY_KEYBOARD_PRIOR_GPU_LAYERS",
                spec.gpu_layers.to_string(),
            )
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        if let Some(threads) = spec.decode_threads {
            command.env("AZOOKEY_KEYBOARD_PRIOR_THREADS", threads.to_string());
        }
        if let Some(threads) = spec.batch_threads {
            command.env("AZOOKEY_KEYBOARD_PRIOR_THREADS_BATCH", threads.to_string());
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        Self::from_child(command.spawn().ok()?, spec)
    }

    fn from_child(mut child: Child, spec: WorkerSpec) -> Option<Self> {
        let input = child.stdin.take()?;
        let output = child.stdout.take()?;
        let (send, receive) = mpsc::sync_channel(2);
        std::thread::spawn(move || {
            for line in BufReader::new(output).lines() {
                let Ok(line) = line else { break };
                if line.len() > 4096 || send.send(line).is_err() {
                    break;
                }
            }
        });
        let worker = Self {
            child,
            input,
            output: WorkerOutput {
                lines: receive,
                pending_since: None,
            },
            spec,
        };
        worker
            .output
            .lines
            .recv_timeout(WORKER_STARTUP_TIMEOUT)
            .ok()
            .filter(|ready| ready.starts_with("ready "))?;
        Some(worker)
    }

    fn request(
        &mut self,
        raw: &str,
        input_hira: &str,
        context: &str,
        timeout_ms: u64,
    ) -> Result<Option<Value>, ()> {
        if !self.output.available()? {
            return Ok(None);
        }
        let clean_raw: String = raw
            .chars()
            .filter(|c| !matches!(c, '\t' | '\r' | '\n'))
            .collect();
        let clean_input: String = input_hira
            .chars()
            .filter(|c| !matches!(c, '\t' | '\r' | '\n'))
            .collect();
        let clean_ctx: String = context
            .chars()
            .filter(|c| !matches!(c, '\t' | '\r' | '\n'))
            .collect();
        writeln!(self.input, "{clean_raw}\t{clean_input}\t{clean_ctx}").map_err(|_| ())?;
        self.input.flush().map_err(|_| ())?;
        self.output.pending_since = Some(Instant::now());
        self.output.receive_reply(Duration::from_millis(timeout_ms))
    }
}

#[cfg(windows)]
pub(crate) struct BenchmarkKeyboard(Worker);

#[cfg(windows)]
impl BenchmarkKeyboard {
    pub(crate) fn open(
        model: PathBuf,
        runtime_dir: PathBuf,
        dictionary: &Path,
        gpu_layers: u32,
    ) -> Option<Self> {
        let executable = find_worker_executable(&exe_root()?)?;
        let env_thread = |name: &str, lo: u32, hi: u32| -> Option<u32> {
            if gpu_layers != 0 {
                return None;
            }
            std::env::var(name)
                .ok()
                .and_then(|s| s.trim().parse::<u32>().ok())
                .map(|n| n.clamp(lo, hi))
        };
        let spec = WorkerSpec {
            model,
            runtime_dir,
            gpu_layers,
            decode_threads: env_thread("AZOOKEY_KEYBOARD_PRIOR_THREADS", 1, 32),
            batch_threads: env_thread("AZOOKEY_KEYBOARD_PRIOR_THREADS_BATCH", 1, 64),
        };
        Worker::start_with_spec(&executable, spec, dictionary).map(Self)
    }

    pub(crate) fn repair(
        &mut self,
        raw: &str,
        input: &str,
        context: &str,
        timeout_ms: u64,
    ) -> Result<Option<Value>, ()> {
        self.0.request(raw, input, context, timeout_ms.min(220))
    }
}

fn exe_root() -> Option<PathBuf> {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(Path::to_owned))
}

fn worker_spec_stale(worker: &Worker, root: &Path) -> bool {
    find_worker_executable(root)
        .and_then(|executable| resolve_worker_spec(root, &executable))
        .is_some_and(|spec| worker.spec != spec)
}

/// Initialization happens independently of input callbacks. Requests never wait
/// on this lock: a cold/missing worker simply leaves the ordinary path available.
pub fn prewarm() {
    if STARTING.swap(true, Ordering::AcqRel) {
        return;
    }
    std::thread::spawn(|| {
        struct Reset;
        impl Drop for Reset {
            fn drop(&mut self) {
                STARTING.store(false, Ordering::Release);
            }
        }
        let _reset = Reset;
        let Some(dictionary) = runtime::reverse_dictionary_path() else {
            return;
        };
        let Some(root) = exe_root() else {
            return;
        };
        if let Ok(mut slot) = WORKER.lock() {
            if slot.as_ref().is_none_or(|w| worker_spec_stale(w, &root)) {
                *slot = None;
                *slot = Worker::start(&root, &dictionary);
            }
        }
    });
}

fn normalize_reading(text: &str) -> String {
    super::katakana_to_hiragana(text)
        .chars()
        .map(|c| {
            if ('ａ'..='ｚ').contains(&c) {
                char::from_u32(c as u32 - 0xfee0).unwrap_or(c)
            } else {
                c
            }
        })
        .collect()
}

fn is_valid_reading_str(reading: &str, observed: &str) -> bool {
    reading != observed
        && (2..=128).contains(&reading.chars().count())
        && reading.chars().all(|c| {
            ('ぁ'..='ゖ').contains(&c)
                || "ー、。！？!?".contains(c)
                || c.is_ascii_digit()
                || ('０'..='９').contains(&c)
        })
}

/// Validated top repair reading and ranking confidence returned by `keyboard-prior`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChannelRepairCandidate {
    pub reading: String,
    pub alt_reading: Option<String>,
    pub confidence: super::ChannelConfidence,
}

fn validated_repair_candidate(response: &Value, input: &str) -> Option<ChannelRepairCandidate> {
    let observed = response.get("observed")?.as_str()?;
    let reading = response.get("reading")?.as_str()?;
    let gain = response.get("gain")?.as_f64()?;
    if normalize_reading(input) != observed
        || !gain.is_finite()
        || gain < 37.
        || !is_valid_reading_str(reading, observed)
    {
        return None;
    }
    let alt_reading = response
        .get("alt_reading")
        .and_then(Value::as_str)
        .filter(|alt| *alt != reading && is_valid_reading_str(alt, observed))
        .map(str::to_owned);
    let has_alt_reading = alt_reading.is_some();
    Some(ChannelRepairCandidate {
        reading: reading.to_owned(),
        alt_reading,
        confidence: super::ChannelConfidence {
            gain_tenths: (gain.clamp(0.0, 100_000.0) * 10.0).round() as u32,
            has_alt_reading,
            clear_lead: has_alt_reading && response["clear_lead"].as_bool() == Some(true),
        },
    })
}

fn candidate_readings(candidate: Option<ChannelRepairCandidate>) -> Vec<String> {
    candidate.map_or_else(Vec::new, |c| {
        let mut out = vec![c.reading];
        if let Some(alt) = c.alt_reading {
            out.push(alt);
        }
        out
    })
}

#[cfg(test)]
fn validated_readings(response: &Value, input: &str) -> Vec<String> {
    candidate_readings(validated_repair_candidate(response, input))
}

#[cfg(test)]
fn validated_reading(response: &Value, input: &str) -> Option<String> {
    validated_readings(response, input).into_iter().next()
}

fn legacy_full_word_repair(input: &str) -> bool {
    super::romaji_decoder::decode_floating_consonant_typo(input).is_some_and(|reading| {
        super::STANDARD_VOCAB.iter().any(|(r, _)| *r == reading)
            || super::typo_variant::lookup_surface(&reading).is_some()
    })
}

/// Repair reading via candidate search + main conversion model likelihood scoring.
/// Returns up to 2 ranked reading candidates when the worker finishes in time.
pub fn repair_readings(
    context: &str,
    input: &str,
    raw: &str,
    wait_for_worker: bool,
) -> Option<Vec<String>> {
    repair_readings_with_hints(context, input, raw, "", "", wait_for_worker)
}

fn is_valid_raw_keys(raw: &str) -> bool {
    (4..=192).contains(&raw.len())
        && raw
            .bytes()
            .all(|c| c.is_ascii_lowercase() || matches!(c, b'-' | b',' | b'.' | b'?' | b'!'))
}

/// Like [`repair_readings`], returning the validated top repair candidate and confidence metadata.
pub fn repair_candidate_with_hints(
    context: &str,
    input: &str,
    raw: &str,
    touch_hints: &str,
    input_mode: &str,
    wait_for_worker: bool,
) -> Option<Option<ChannelRepairCandidate>> {
    let norm = normalize_reading(input);
    let char_count = norm.chars().count();
    if !(2..=128).contains(&char_count)
        || norm.chars().any(|c| ('一'..='鿿').contains(&c))
        || norm
            .chars()
            .last()
            .is_some_and(|c| c.is_ascii_alphabetic() && !"aeiou".contains(c.to_ascii_lowercase()))
    {
        return None;
    }
    let valid_raw = if is_valid_raw_keys(raw) { raw } else { "" };
    let normalized_context = context.replace(' ', "\u{3000}");
    let (masked_context, _) = super::corrector::mask_digits_with_nam(&normalized_context);
    let response = if let Some(hosted) = runtime::keyboard_repair_override_with_hints(
        valid_raw,
        &norm,
        &masked_context,
        touch_hints,
        input_mode,
        if wait_for_worker { 30000 } else { 350 },
    ) {
        // The host scores on its own loaded model; no child process exists here.
        hosted?
    } else if wait_for_worker {
        let mut slot = WORKER.lock().ok()?;
        let root = exe_root()?;
        if slot.as_ref().is_none_or(|w| worker_spec_stale(w, &root)) {
            if let Some(dictionary) = runtime::reverse_dictionary_path() {
                *slot = None;
                *slot = Worker::start(&root, &dictionary);
            }
        }
        let result = slot
            .as_mut()?
            .request(valid_raw, &norm, &masked_context, 30000);
        if result.is_err() {
            *slot = None;
        }
        result.ok()??
    } else {
        let mut slot = WORKER.try_lock().ok()?;
        let stale = slot
            .as_ref()
            .is_some_and(|w| exe_root().is_some_and(|root| worker_spec_stale(w, &root)));
        if slot.is_none() || stale {
            drop(slot);
            prewarm();
            return None;
        }
        let result = slot
            .as_mut()?
            .request(valid_raw, &norm, &masked_context, 220);
        if result.is_err() {
            *slot = None;
            drop(slot);
            prewarm();
        }
        result.ok()??
    };
    if response.get("observed")?.as_str()? != norm {
        return None;
    }
    Some(validated_repair_candidate(&response, input))
}

/// Like [`repair_readings`], forwarding optional per-position touch hints (`"0:o=0.85;..."`)
/// and keyboard input mode (`""` / `"qwerty"` / `"flick"`).
pub fn repair_readings_with_hints(
    context: &str,
    input: &str,
    raw: &str,
    touch_hints: &str,
    input_mode: &str,
    wait_for_worker: bool,
) -> Option<Vec<String>> {
    repair_candidate_with_hints(
        context,
        input,
        raw,
        touch_hints,
        input_mode,
        wait_for_worker,
    )
    .map(candidate_readings)
}

/// Repair reading via candidate search + main conversion model likelihood scoring.
/// If the worker is cold and `wait_for_worker` is true (e.g. offline evaluation),
/// starts the worker synchronously; in live IME (`wait_for_worker = false`),
/// triggers `prewarm()` and returns immediately.
pub fn repair_reading(
    context: &str,
    input: &str,
    raw: &str,
    wait_for_worker: bool,
) -> Option<Option<String>> {
    repair_readings(context, input, raw, wait_for_worker).map(|v| v.into_iter().next())
}

pub fn suggest(
    context: &str,
    input: &str,
    raw: &str,
    config: &AiAssistConfig,
) -> Option<Option<TypoSuggestion>> {
    if !config.enabled
        || config.engine != "embedded"
        || !is_valid_raw_keys(raw)
        || super::reading_check::user_dictionary_contains_reading(input)
        || corrector::known_reading(input)
        || super::lookup_learned_typo(input).is_some()
        || corrector::explicit_typo_surface(input).is_some()
        || legacy_full_word_repair(input)
    {
        return None;
    }
    let Some(candidate) = repair_candidate_with_hints(context, input, raw, "", "", false)? else {
        return Some(None);
    };
    Some(Some(TypoSuggestion {
        surface: candidate.reading.clone(),
        token_reading: input.into(),
        token_surface: candidate.reading,
        learned: false,
        channel_confidence: Some(candidate.confidence),
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn a_late_worker_reply_is_discarded_before_the_next_request() {
        let (send, lines) = mpsc::channel();
        let mut output = WorkerOutput {
            lines,
            pending_since: Some(Instant::now()),
        };
        assert_eq!(output.receive_reply(Duration::ZERO), Ok(None));
        assert_eq!(output.available(), Ok(false));

        send.send("{\"reading\":\"previous\"}".into()).unwrap();
        assert_eq!(output.available(), Ok(true));
        output.pending_since = Some(Instant::now());
        send.send("{\"reading\":\"current\"}".into()).unwrap();
        assert_eq!(
            output.receive_reply(Duration::ZERO),
            Ok(Some(json!({"reading":"current"})))
        );
        assert!(output.pending_since.is_none());
    }

    #[test]
    fn an_unresponsive_or_disconnected_worker_can_be_restarted() {
        let (send, lines) = mpsc::channel();
        let mut output = WorkerOutput {
            lines,
            pending_since: Some(Instant::now() - WORKER_STALL_TIMEOUT),
        };
        assert_eq!(output.available(), Err(()));
        drop(send);
        assert_eq!(output.receive_reply(Duration::ZERO), Err(()));
    }

    #[test]
    fn existing_short_word_adjacent_and_missing_vowel_repairs_keep_their_path() {
        for input in ["おkせり", "おtかれ", "あrがとう"] {
            assert!(legacy_full_word_repair(input), "{input}");
        }
        assert!(!legacy_full_word_repair("しりょうをおくりmっす"));
    }

    #[test]
    fn stale_or_different_input_table_never_proposes() {
        let response = json!({"observed":"ああた", "reading":"あした", "gain":40.});
        assert_eq!(
            validated_reading(&response, "アアタ"),
            Some("あした".into())
        );
        assert_eq!(validated_reading(&response, "あした"), None);
        assert_eq!(validated_reading(&response, "ああたs"), None);
    }

    #[test]
    fn legacy_worker_alternatives_do_not_imply_a_clear_lead() {
        let mut response = json!({
            "observed":"しmばし", "reading":"しまし", "alt_reading":"しんばし", "gain":150.
        });
        let candidate = validated_repair_candidate(&response, "しmばし").unwrap();
        assert!(candidate.confidence.has_alt_reading);
        assert!(!candidate.confidence.clear_lead);
        response["clear_lead"] = json!(true);
        let candidate = validated_repair_candidate(&response, "しmばし").unwrap();
        assert!(candidate.confidence.clear_lead);
    }

    #[test]
    fn normal_no_change_and_weak_or_invalid_outputs_are_suppressed() {
        for response in [
            json!({}),
            json!({"observed":"あした","reading":"あした","gain":50.}),
            json!({"observed":"ああた","reading":"あした","gain":36.9}),
            json!({"observed":"ああた","reading":"明日","gain":40.}),
        ] {
            assert_eq!(validated_reading(&response, "ああた"), None);
        }
    }

    #[test]
    fn repair_readings_masks_digits_in_context_before_model_call() {
        struct CaptureBackend(std::sync::Arc<std::sync::Mutex<Option<String>>>);
        impl super::runtime::CompletionBackend for CaptureBackend {
            fn complete(&self, _: u16, _: &str, _: &Value, _: u64) -> Option<Value> {
                None
            }
            fn keyboard_repair(
                &self,
                _raw: &str,
                _input: &str,
                context: &str,
                _timeout_ms: u64,
            ) -> Option<Option<Value>> {
                *self.0.lock().unwrap() = Some(context.to_owned());
                Some(None)
            }
        }

        let captured = std::sync::Arc::new(std::sync::Mutex::new(None));
        let backend = CaptureBackend(std::sync::Arc::clone(&captured));
        let result = super::runtime::with_backend(Box::new(backend), || {
            repair_readings("２０２６ねん10がつに", "ああた", "", false)
        });
        assert!(result.is_none());
        assert_eq!(
            captured.lock().unwrap().as_deref(),
            Some("namねんnamがつに")
        );
    }

    #[test]
    fn repair_readings_forwards_dash_in_raw_and_touch_hints() {
        struct CaptureBackend(std::sync::Arc<std::sync::Mutex<Option<(String, String, String)>>>);
        impl super::runtime::CompletionBackend for CaptureBackend {
            fn complete(&self, _: u16, _: &str, _: &Value, _: u64) -> Option<Value> {
                None
            }
            fn keyboard_repair_with_hints(
                &self,
                raw: &str,
                input: &str,
                _context: &str,
                touch_hints: &str,
                _input_mode: &str,
                _timeout_ms: u64,
            ) -> Option<Option<Value>> {
                *self.0.lock().unwrap() =
                    Some((raw.to_owned(), input.to_owned(), touch_hints.to_owned()));
                Some(None)
            }
        }

        let captured = std::sync::Arc::new(std::sync::Mutex::new(None));
        let backend = CaptureBackend(std::sync::Arc::clone(&captured));
        let result = super::runtime::with_backend(Box::new(backend), || {
            repair_readings_with_hints(
                "",
                "ーあそこん",
                "-asokon",
                "0:p=0.85,l=0.40",
                "qwerty",
                false,
            )
        });
        assert!(result.is_none());
        assert_eq!(
            captured.lock().unwrap().as_ref(),
            Some(&(
                "-asokon".to_owned(),
                "ーあそこん".to_owned(),
                "0:p=0.85,l=0.40".to_owned()
            ))
        );
    }

    #[test]
    fn context_spaces_normalize_and_user_dict_or_known_reading_skip_worker() {
        let _dict_guard = super::super::USER_DICTIONARY_TEST_LOCK.lock().unwrap();
        struct CaptureBackend(std::sync::Arc<std::sync::Mutex<Vec<(String, String)>>>);
        impl super::runtime::CompletionBackend for CaptureBackend {
            fn allow_native_worker(&self) -> bool {
                true
            }
            fn complete(&self, _: u16, _: &str, _: &Value, _: u64) -> Option<Value> {
                None
            }
            fn keyboard_repair_with_hints(
                &self,
                raw: &str,
                _input: &str,
                context: &str,
                _touch_hints: &str,
                _input_mode: &str,
                _timeout_ms: u64,
            ) -> Option<Option<Value>> {
                self.0
                    .lock()
                    .unwrap()
                    .push((raw.to_owned(), context.to_owned()));
                Some(None)
            }
        }

        let calls = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let config = AiAssistConfig {
            enabled: true,
            corrector_port: super::runtime::PREDICTION_SERVER_PORT,
            ..AiAssistConfig::default()
        };
        let long_raw = format!("{}a!", "ka".repeat(33));
        let long_input = format!("{}あ！", "か".repeat(33));

        super::super::set_user_dictionary_vocabulary([("きんにちわ", "登録語")]);
        super::runtime::with_backend(Box::new(CaptureBackend(calls.clone())), || {
            assert!(suggest("今日は 晴れ", "きんにちわ", "kinnitiwa", &config).is_none());
            assert!(suggest(
                "今日は 晴れ",
                "ありがとうございます",
                "arigatougozaimasu",
                &config
            )
            .is_none());
            assert!(calls.lock().unwrap().is_empty());

            let _ = suggest("API key ", &long_input, &long_raw, &config);
            #[cfg(windows)]
            let _ = corrector::suggest_windows_composition(
                "API key ",
                "おせわになております。",
                "お世話になております。",
                "osewaninateorimasu.",
                &config,
            );
        });
        super::super::set_user_dictionary_vocabulary(std::iter::empty());

        let recorded = calls.lock().unwrap();
        assert_eq!(recorded[0], (long_raw, "API\u{3000}key\u{3000}".to_owned()));
        #[cfg(windows)]
        assert_eq!(
            recorded[1],
            (
                "osewaninateorimasu.".to_owned(),
                "API\u{3000}key\u{3000}".to_owned()
            )
        );
    }

    #[test]
    fn mixed_digit_readings_pass_validation_and_reading_distance() {
        let halfwidth = serde_json::json!({
            "observed": "10じにかいぐ",
            "reading": "10じにかいぎ",
            "gain": 50.0
        });
        assert_eq!(
            validated_reading(&halfwidth, "10じにかいぐ"),
            Some("10じにかいぎ".into())
        );
        let fullwidth = serde_json::json!({
            "observed": "２０２６ねんてそと",
            "reading": "２０２６ねんてすと",
            "gain": 50.0
        });
        assert_eq!(
            validated_reading(&fullwidth, "２０２６ねんてそと"),
            Some("２０２６ねんてすと".into())
        );
        assert_eq!(
            corrector::reading_change_distance("10じにかいぐ", "10じにかいぎ"),
            Some(1)
        );
        assert_eq!(
            corrector::reading_change_distance("２０２６ねんてそと", "２０２６ねんてすと"),
            Some(1)
        );
    }
}

#[cfg(test)]
mod deployment_tests {
    use super::*;
    #[test]
    fn deployed_worker_follows_backend_and_keeps_legacy_fallback() {
        let root = std::env::temp_dir().join(format!(
            "worker-deployment-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&root).unwrap();

        let legacy_runtime = root.join("llama_cpu");
        std::fs::create_dir(&legacy_runtime).unwrap();
        let legacy_executable = legacy_runtime.join("keyboard-prior.exe");
        std::fs::write(&legacy_executable, b"test fixture").unwrap();
        assert_eq!(
            find_worker_executable(&root),
            Some(legacy_executable.clone())
        );
        assert_eq!(
            find_runtime_dir_and_layers(&root, &legacy_executable, "vulkan", 99),
            (legacy_runtime.clone(), 0)
        );

        let root_executable = root.join("keyboard-prior.exe");
        std::fs::write(&root_executable, b"test fixture").unwrap();
        std::fs::write(legacy_runtime.join("llama.dll"), b"cpu dll").unwrap();
        let vulkan_runtime = root.join("llama_vulkan");
        std::fs::create_dir(&vulkan_runtime).unwrap();
        std::fs::write(vulkan_runtime.join("llama.dll"), b"vulkan dll").unwrap();

        assert_eq!(find_worker_executable(&root), Some(root_executable.clone()));
        assert_eq!(
            find_runtime_dir_and_layers(&root, &root_executable, "cpu", 99),
            (legacy_runtime.clone(), 0)
        );
        let expected_vulkan = if vulkan_loader_available() {
            (vulkan_runtime.clone(), 99)
        } else {
            (legacy_runtime.clone(), 0)
        };
        assert_eq!(
            find_runtime_dir_and_layers(&root, &root_executable, "vulkan", 99),
            expected_vulkan
        );

        let (default_model, _, _) = resolve_runtime_preferences_from_str("{}");
        assert_eq!(default_model, shared::runtime_assets::default_model());
        assert_eq!(default_model, "standard_v4_2b_iq4_emb8");

        let (parsed_model, parsed_backend, parsed_layers) = resolve_runtime_preferences_from_str(
            r#"{"zenzai":{"backend":"cuda","gpu_layers":16,"model":"nano_v4_2b_q8"}}"#,
        );
        assert_eq!(parsed_model, "nano_v4_2b_q8");
        assert_eq!(parsed_backend, "vulkan");
        assert_eq!(parsed_layers, 16);

        let tuned_json = r#"{
            "zenzai": {
                "backend": "cpu",
                "model": "nano_v4_2b_iq4_emb8",
                "cpu_runtime_tuning": {
                    "tuning_version": 2,
                    "model_id": "nano_v4_2b_iq4_emb8",
                    "decode_threads": 4,
                    "batch_threads": 6
                }
            }
        }"#;
        assert_eq!(
            resolve_cpu_tuning_threads_from_str(tuned_json, "nano_v4_2b_iq4_emb8", "cpu"),
            (Some(4), Some(6))
        );
        assert_eq!(
            resolve_cpu_tuning_threads_from_str(tuned_json, "nano_v4_2b_q8", "cpu"),
            (None, None)
        );
        assert_eq!(
            resolve_cpu_tuning_threads_from_str(tuned_json, "nano_v4_2b_iq4_emb8", "vulkan"),
            (None, None)
        );
        let legacy_json = r#"{
            "zenzai": {
                "cpu_runtime_tuning": {
                    "tuning_version": 1,
                    "model_id": "nano_v4_2b_iq4_emb8",
                    "decode_threads": 4
                }
            }
        }"#;
        assert_eq!(
            resolve_cpu_tuning_threads_from_str(legacy_json, "nano_v4_2b_iq4_emb8", "cpu"),
            (None, None)
        );

        let _ = std::fs::remove_dir_all(&root);
    }
}
