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

struct Worker {
    child: Child,
    input: ChildStdin,
    output: mpsc::Receiver<String>,
    model: PathBuf,
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
    if let Some(repo) = repo_root_from_manifest() {
        let target_dir = repo.join("windows/target/keyboard-prior");
        if let Ok(entries) = std::fs::read_dir(&target_dir) {
            let mut candidates: Vec<_> = entries
                .filter_map(Result::ok)
                .map(|e| e.path().join("keyboard-prior.exe"))
                .filter(|p| p.is_file())
                .collect();
            candidates.sort_by_key(|p| std::fs::metadata(p).and_then(|m| m.modified()).ok());
            if let Some(latest) = candidates.pop() {
                return Some(latest);
            }
        }
    }
    let installed = root.join("llama_cpu/keyboard-prior.exe");
    if installed.is_file() {
        return Some(installed);
    }
    if let Some(repo) = repo_root_from_manifest() {
        let trial = repo.join("ZeroConvertIME-windows/llama_cpu/keyboard-prior.exe");
        if trial.is_file() {
            return Some(trial);
        }
    }
    None
}

fn find_runtime_dir(root: &Path, executable: &Path) -> PathBuf {
    let installed = root.join("llama_cpu");
    if installed.join("llama.dll").is_file() {
        return installed;
    }
    if let Some(repo) = repo_root_from_manifest() {
        for rel in [
            "ZeroConvertIME-windows/llama_cpu",
            "artifacts/runtime/windows/llama_cpu",
        ] {
            let candidate = repo.join(rel);
            if candidate.join("llama.dll").is_file() {
                return candidate;
            }
        }
    }
    if let Some(parent) = executable.parent() {
        if parent.join("llama.dll").is_file() {
            return parent.to_path_buf();
        }
    }
    installed
}

fn ensure_executable_with_dlls(executable: &Path, runtime_dir: &Path) -> PathBuf {
    let Some(parent) = executable.parent() else {
        return executable.to_path_buf();
    };
    if parent == runtime_dir {
        return executable.to_path_buf();
    }
    if let Ok(entries) = std::fs::read_dir(runtime_dir) {
        for entry in entries.filter_map(Result::ok) {
            let src = entry.path();
            if src
                .extension()
                .is_some_and(|ext| ext.eq_ignore_ascii_case("dll"))
            {
                if let Some(name) = src.file_name() {
                    let dst = parent.join(name);
                    if !dst.is_file() {
                        let _ = std::fs::copy(&src, &dst);
                    }
                }
            }
        }
    }
    executable.to_path_buf()
}

fn resolve_active_model(root: &Path) -> Option<PathBuf> {
    let selected_id = runtime::settings_path()
        .and_then(|p| std::fs::read_to_string(p).ok())
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .and_then(|v| {
            v.get("zenzai")
                .and_then(|z| z.get("model"))
                .and_then(Value::as_str)
                .map(str::to_owned)
        })
        .unwrap_or_else(|| "nano_v4_iq4_emb8".to_owned());
    let filename = match selected_id.as_str() {
        "nano_v4_q8" | "standard" | "standard_q4km" => "zerocon-nano-v4-q8_0.gguf",
        _ => "zerocon-nano-v4-iq4nl-emb8.gguf",
    };
    for candidate in [
        root.join("models").join(filename),
        root.join("models/zerocon-nano-v4-iq4nl-emb8.gguf"),
        root.join("models/zerocon-nano-v4-q8_0.gguf"),
        root.join("models/zerocon-model-keyboard-prior.gguf"),
    ] {
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    let repo_root = repo_root_from_manifest()?;
    for candidate in [
        repo_root.join("artifacts/models").join(filename),
        repo_root
            .join("ZeroConvertIME-windows/models")
            .join(filename),
        repo_root.join("artifacts/models/zerocon-nano-v4-iq4nl-emb8.gguf"),
    ] {
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

impl Worker {
    fn start(root: &Path, dictionary: &Path) -> Option<Self> {
        let executable = find_worker_executable(root)?;
        let model = resolve_active_model(root)?;
        if !dictionary.is_file() {
            return None;
        }
        let runtime_dir = find_runtime_dir(root, &executable);
        let executable = ensure_executable_with_dlls(&executable, &runtime_dir);
        let existing_path = std::env::var("PATH").unwrap_or_default();
        let mut command = Command::new(&executable);
        command
            .arg("--assist")
            .arg(&model)
            .arg(dictionary)
            .current_dir(&runtime_dir)
            .env("PATH", format!("{};{existing_path}", runtime_dir.display()))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        let mut child = command.spawn().ok()?;
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
            model,
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
        let Some(root) = std::env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(Path::to_owned))
        else {
            return;
        };
        if let Ok(mut slot) = WORKER.lock() {
            let desired = resolve_active_model(&root);
            if slot
                .as_ref()
                .is_none_or(|w| desired.as_ref().is_some_and(|m| &w.model != m))
            {
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

fn validated_reading(response: &Value, input: &str) -> Option<String> {
    let observed = response.get("observed")?.as_str()?;
    let reading = response.get("reading")?.as_str()?;
    let gain = response.get("gain")?.as_f64()?;
    if normalize_reading(input) != observed
        || reading == observed
        || !gain.is_finite()
        || gain < 37.
        || !(2..=128).contains(&reading.chars().count())
        || !reading
            .chars()
            .all(|c| ('ぁ'..='ゖ').contains(&c) || "ー、。！？!?".contains(c))
    {
        return None;
    }
    Some(reading.to_owned())
}

fn legacy_full_word_repair(input: &str) -> bool {
    super::romaji_decoder::decode_floating_consonant_typo(input).is_some_and(|reading| {
        super::STANDARD_VOCAB.iter().any(|(r, _)| *r == reading)
            || super::typo_variant::lookup_surface(&reading).is_some()
    })
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
    let response = if wait_for_worker {
        let mut slot = WORKER.lock().ok()?;
        if slot.is_none() {
            if let (Some(dictionary), Some(root)) = (
                runtime::reverse_dictionary_path(),
                std::env::current_exe()
                    .ok()
                    .and_then(|p| p.parent().map(Path::to_owned)),
            ) {
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
        if slot.is_none() {
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
    Some(validated_reading(&response, input))
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
    let Some(reading) = repair_reading(context, input, raw, false)? else {
        return Some(None);
    };
    let remaining = 450u64
        .saturating_sub(started.elapsed().as_millis() as u64)
        .min(200);
    if remaining < 20 {
        return Some(None);
    }
    // Reuse ordinary conversion for final spelling, independently of the main
    // Swift composition. Its reading must match the channel's proposed reading.
    let payload = corrector::payload(context, &reading)?;
    let surface = simple_http_post(config.prediction_port, "/completion", &payload, remaining)
        .and_then(|r| corrector::completed_surface(&r, &reading, payload["n_predict"].as_u64()?))
        .filter(|s| reading_check::has_same_reading(s, &reading))
        // The unigram channel's surface can have the right reading but poor
        // spelling (e.g. し両 for 資料). A literal corrected reading is preferable
        // when ordinary conversion is unavailable or fails reading validation.
        .or_else(|| Some(reading.clone()));
    Some(surface.map(|surface| TypoSuggestion {
        surface: surface.clone(),
        token_reading: input.into(),
        token_surface: surface,
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
