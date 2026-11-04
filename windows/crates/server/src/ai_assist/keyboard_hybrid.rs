//! Keyboard-channel correction on the assistance worker. Never mutates Swift.
//! The native child owns the prior model/dictionary; each request has a deadline.
use super::{corrector, reading_check, runtime, simple_http_post, AiAssistConfig, TypoSuggestion};
use serde_json::Value;
use std::{
    io::{BufRead, BufReader, Write},
    path::Path,
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
}

impl Drop for Worker {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Worker {
    fn start(root: &Path, dictionary: &Path) -> Option<Self> {
        let executable = root.join("llama_cpu/keyboard-prior.exe");
        let model = root.join("models/zerocon-model-keyboard-prior.gguf");
        if !executable.is_file() || !model.is_file() || !dictionary.is_file() {
            return None;
        }
        let mut command = Command::new(executable);
        command
            .arg("--hybrid")
            .arg(model)
            .arg(dictionary)
            .current_dir(root.join("llama_cpu"))
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
        };
        worker
            .output
            .recv_timeout(Duration::from_secs(15))
            .ok()
            .filter(|ready| ready.starts_with("ready "))?;
        Some(worker)
    }

    fn request(&mut self, keys: &str) -> Option<Value> {
        writeln!(self.input, "{keys}").ok()?;
        self.input.flush().ok()?;
        let response = self.output.recv_timeout(Duration::from_millis(250)).ok()?;
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
            if slot.is_none() {
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
            .all(|c| ('ぁ'..='ゖ').contains(&c) || c == 'ー')
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
    let response = {
        let mut slot = WORKER.try_lock().ok()?;
        let result = slot.as_mut()?.request(raw);
        // A late response cannot be consumed by the next snapshot. Drop the
        // process and its pipes after failure; restart separately from typing.
        if result.is_none() {
            *slot = None;
            drop(slot);
            prewarm();
        }
        result?
    };
    if response.get("observed")?.as_str()? != normalize_reading(input) {
        return None;
    }
    let Some(reading) = validated_reading(&response, input) else {
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
