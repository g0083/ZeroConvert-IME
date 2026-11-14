//! Keyboard-channel correction on the assistance worker. Never mutates Swift.
//! The native child owns the active conversion model/dictionary; each request has a deadline.
use super::{corrector, reading_check, runtime, simple_http_post, AiAssistConfig, TypoSuggestion};
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

#[derive(Clone, Debug, PartialEq, Eq)]
struct WorkerSpec {
    model: PathBuf,
    runtime_dir: PathBuf,
    gpu_layers: u32,
}

struct Worker {
    child: Child,
    input: ChildStdin,
    output: mpsc::Receiver<String>,
    spec: WorkerSpec,
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
        .unwrap_or("nano_v4_iq4_emb8")
        .to_owned();
    let raw_backend = zenzai
        .and_then(|z| z.get("backend"))
        .and_then(Value::as_str)
        .unwrap_or("vulkan");
    let backend = if raw_backend.eq_ignore_ascii_case("vulkan")
        || raw_backend.eq_ignore_ascii_case("cuda")
    {
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

fn read_runtime_preferences() -> (String, String, u32) {
    let settings_json = runtime::settings_path()
        .and_then(|p| std::fs::read_to_string(p).ok())
        .unwrap_or_default();
    resolve_runtime_preferences_from_str(&settings_json)
}

fn find_runtime_dir_and_layers(
    root: &Path,
    executable: &Path,
    backend: &str,
    gpu_layers: u32,
) -> (PathBuf, u32) {
    let exec_parent = executable.parent().unwrap_or(root);
    // Legacy layout places keyboard-prior.exe inside `llama_cpu/`, where Windows
    // forces static DLL imports from `executable.parent()` regardless of PATH.
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

    let install_root = if executable.starts_with(root) {
        root
    } else {
        exec_parent
    };
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
    let filename = match selected_id {
        "nano_v4_q8" | "standard" | "standard_q4km" => "zerocon-nano-v4-q8_0.gguf",
        _ => "zerocon-nano-v4-iq4nl-emb8.gguf",
    };
    for candidate in [
        root.join("models").join(filename),
        root.join("models/zerocon-nano-v4-iq4nl-emb8.gguf"),
        root.join("models/zerocon-nano-v4-q8_0.gguf"),
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
        repo_root.join("artifacts/models/zerocon/zerocon-nano-v4-iq4nl-emb8.gguf"),
        repo_root.join("artifacts/models/zerocon-nano-v4-iq4nl-emb8.gguf"),
    ] {
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

fn resolve_worker_spec(root: &Path, executable: &Path) -> Option<WorkerSpec> {
    let (model_id, backend, configured_layers) = read_runtime_preferences();
    let model = resolve_active_model(root, &model_id)?;
    let (runtime_dir, gpu_layers) =
        find_runtime_dir_and_layers(root, executable, &backend, configured_layers);
    Some(WorkerSpec {
        model,
        runtime_dir,
        gpu_layers,
    })
}

impl Worker {
    fn start(root: &Path, dictionary: &Path) -> Option<Self> {
        let executable = find_worker_executable(root)?;
        let spec = resolve_worker_spec(root, &executable)?;
        if !dictionary.is_file() {
            return None;
        }
        let existing_path = std::env::var("PATH").unwrap_or_default();
        let mut command = Command::new(&executable);
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
            output: receive,
            spec,
        };
        worker
            .output
            .recv_timeout(Duration::from_secs(15))
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
    ) -> Option<Value> {
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
        writeln!(self.input, "{clean_raw}\t{clean_input}\t{clean_ctx}").ok()?;
        self.input.flush().ok()?;
        let response = self
            .output
            .recv_timeout(Duration::from_millis(timeout_ms))
            .ok()?;
        serde_json::from_str(&response).ok()
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
        && reading
            .chars()
            .all(|c| ('ぁ'..='ゖ').contains(&c) || "ー、。！？!?".contains(c))
}

fn validated_readings(response: &Value, input: &str) -> Vec<String> {
    let Some(observed) = response.get("observed").and_then(Value::as_str) else {
        return Vec::new();
    };
    let Some(reading) = response.get("reading").and_then(Value::as_str) else {
        return Vec::new();
    };
    let Some(gain) = response.get("gain").and_then(Value::as_f64) else {
        return Vec::new();
    };
    if normalize_reading(input) != observed
        || !gain.is_finite()
        || gain < 37.
        || !is_valid_reading_str(reading, observed)
    {
        return Vec::new();
    }
    let mut out = vec![reading.to_owned()];
    if let Some(alt) = response
        .get("alt_reading")
        .and_then(Value::as_str)
        .filter(|alt| *alt != reading && is_valid_reading_str(alt, observed))
    {
        out.push(alt.to_owned());
    }
    out
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
    let valid_raw = if (4..=64).contains(&raw.len()) && raw.bytes().all(|c| c.is_ascii_lowercase())
    {
        raw
    } else {
        ""
    };
    let response = if let Some(hosted) = runtime::keyboard_repair_override(
        valid_raw,
        &norm,
        context,
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
        let result = slot.as_mut()?.request(valid_raw, &norm, context, 30000);
        if result.is_none() {
            *slot = None;
        }
        result?
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
        let result = slot.as_mut()?.request(valid_raw, &norm, context, 350);
        if result.is_none() {
            *slot = None;
            drop(slot);
            prewarm();
        }
        result?
    };
    if response.get("observed")?.as_str()? != norm {
        return None;
    }
    Some(validated_readings(&response, input))
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
        || !(4..=64).contains(&raw.len())
        || !raw.bytes().all(|c| c.is_ascii_lowercase())
        || super::lookup_learned_typo(input).is_some()
        || corrector::explicit_typo_surface(input).is_some()
        || legacy_full_word_repair(input)
    {
        return None;
    }
    let started = Instant::now();
    let readings = repair_readings(context, input, raw, false)?;
    if readings.is_empty() {
        return Some(None);
    }
    for reading in &readings {
        let remaining = 450u64
            .saturating_sub(started.elapsed().as_millis() as u64)
            .min(200);
        if remaining < 20 {
            break;
        }
        // Reuse ordinary conversion for final spelling, independently of the main
        // Swift composition. Its reading must match the channel's proposed reading.
        let Some(payload) = corrector::payload(context, reading) else {
            continue;
        };
        if let Some(surface) =
            simple_http_post(config.prediction_port, "/completion", &payload, remaining)
                .and_then(|r| {
                    corrector::completed_surface(&r, reading, payload["n_predict"].as_u64()?)
                })
                .filter(|s| reading_check::has_same_reading(s, reading))
        {
            return Some(Some(TypoSuggestion {
                surface: surface.clone(),
                token_reading: input.into(),
                token_surface: surface,
                learned: false,
            }));
        }
    }
    let reading = readings.into_iter().next()?;
    Some(Some(TypoSuggestion {
        surface: reading.clone(),
        token_reading: input.into(),
        token_surface: reading,
        learned: false,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

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
        assert_eq!(find_worker_executable(&root), Some(legacy_executable.clone()));
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

        let (_, parsed_backend, parsed_layers) = resolve_runtime_preferences_from_str(
            r#"{"zenzai":{"backend":"cuda","gpu_layers":16,"model":"nano_v4_q8"}}"#,
        );
        assert_eq!(parsed_backend, "vulkan");
        assert_eq!(parsed_layers, 16);

        let _ = std::fs::remove_dir_all(&root);
    }
}
