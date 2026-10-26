//! Bounded, immutable surface completion. Scores express evidence, not probability.
use super::{simple_http_post, to_katakana, AiAssistConfig};
use serde_json::{json, Value};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Mutex,
};

#[derive(Clone, Debug)]
pub struct Prediction {
    pub continuation: String,
    pub relevance: u32,
}

type CacheKey = (u16, String, String, String, u64);
static CACHE: Mutex<Vec<(CacheKey, Prediction)>> = Mutex::new(Vec::new());
static IN_FLIGHT: AtomicBool = AtomicBool::new(false);
struct Flight;
impl Drop for Flight {
    fn drop(&mut self) {
        IN_FLIGHT.store(false, Ordering::Release);
    }
}

pub fn clear_cache() {
    if let Ok(mut cache) = CACHE.lock() {
        cache.clear();
    }
}

fn useful(text: &str) -> bool {
    let n = text.chars().count();
    (2..=48).contains(&n)
        && !text
            .chars()
            .any(|c| c.is_control() || ('\u{EE00}'..='\u{EE02}').contains(&c))
        && text.chars().any(|c| c.is_alphanumeric())
}

fn complete_response(value: &Value) -> Option<&str> {
    if value.get("truncated").and_then(Value::as_bool) == Some(true)
        || value.get("stopped_limit").and_then(Value::as_bool) == Some(true)
        || value.get("stop_type").and_then(Value::as_str) == Some("limit")
    {
        return None;
    }
    value.get("content")?.as_str().map(str::trim)
}

// A token budget is not a linguistic boundary. Retain a finished short clause
// if one exists, otherwise reject a budget-exhausted reading fragment.
fn reading_extension(value: &Value) -> Option<&str> {
    let text = value.get("content")?.as_str()?.trim();
    if let Some((index, ch)) = text.char_indices().find(|(i, ch)| {
        "、。！？!?".contains(*ch)
            && text[..*i].chars().filter(|c| c.is_alphanumeric()).count() >= 2
    }) {
        let phrase = &text[..index + ch.len_utf8()];
        return useful(phrase).then_some(phrase);
    }
    complete_response(value).filter(|s| useful(s))
}

// A model may extend the text, never silently replace the user's visible prefix.
fn surface_suffix<'a>(surface: &str, completed: &'a str) -> Option<&'a str> {
    let suffix = completed.strip_prefix(surface)?;
    (useful(suffix) && !suffix.contains(surface)).then_some(suffix)
}

fn supported_by_context(context: &str, surface: &str, suffix: &str) -> bool {
    // Do not invent an antecedent just because it is common in training text.
    ["この記事", "その記事", "上記", "前述"]
        .iter()
        .all(|reference| {
            !suffix.contains(reference)
                || context.contains(reference)
                || surface.contains(reference)
        })
}

pub fn suggest(
    context: &str,
    reading: &str,
    surface: &str,
    config: &AiAssistConfig,
) -> Option<Prediction> {
    if !config.enabled || surface.chars().count() < 2 || reading.chars().count() > 512 {
        return None;
    }
    // An unfinished romanization is not a safe point to commit an extension.
    if reading
        .chars()
        .last()
        .is_some_and(|c| c.is_ascii_alphabetic())
    {
        return None;
    }
    let context: String = context
        .chars()
        .map(|c| if c == ' ' { '\u{3000}' } else { c })
        .rev()
        .take(256)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    let key = (
        config.prediction_port,
        context.clone(),
        reading.to_owned(),
        surface.to_owned(),
        super::runtime::backend_revision(),
    );
    if let Some(cached) = CACHE
        .lock()
        .ok()
        .and_then(|c| c.iter().find(|(k, _)| *k == key).map(|(_, p)| p.clone()))
    {
        return Some(cached);
    }
    if IN_FLIGHT
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        return None;
    }
    let _flight = Flight;
    let restored_reading;
    let model_reading = if reading.chars().any(|c| ('一'..='鿿').contains(&c)) {
        restored_reading = super::reading_check::reading_for_surface(surface);
        restored_reading.as_deref()
    } else {
        Some(reading)
    };
    let prediction =
        model_reading.and_then(|reading| zerocon(&context, reading, surface, config))?;
    if let Ok(mut cache) = CACHE.lock() {
        cache.insert(0, (key, prediction.clone()));
        cache.truncate(32);
    }
    Some(prediction)
}

fn zerocon(
    context: &str,
    reading: &str,
    surface: &str,
    config: &AiAssistConfig,
) -> Option<Prediction> {
    if context
        .chars()
        .chain(reading.chars())
        .any(|c| ('\u{EE00}'..='\u{EE02}').contains(&c))
    {
        return None;
    }
    // First extend the reading, then convert the entire extended reading. Treating
    // the first response as final surface used to append raw kana to converted text.
    let target = to_katakana(reading);
    let response = simple_http_post(
        config.prediction_port,
        "/completion",
        &json!({
            "prompt": format!("\u{EE02}{context}\u{EE00}{target}"), "n_predict": 24,
            "temperature": 0.0, "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]
        }),
        250,
    )?;
    let continuation = reading_extension(&response)?;
    if !useful(continuation)
        || !continuation.chars().all(|c| {
            ('ぁ'..='ゖ').contains(&c) || ('ァ'..='ヶ').contains(&c) || "ー、。！？!?・".contains(c)
        })
    {
        return None;
    }
    let extended = format!("{target}{}", to_katakana(continuation));
    let response = simple_http_post(
        config.prediction_port,
        "/completion",
        &json!({
            "prompt": format!("\u{EE02}{context}\u{EE00}{extended}\u{EE01}"),
            "n_predict": (extended.chars().count() * 3 + 16).clamp(32, 256),
            "temperature": 0.0, "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]
        }),
        300,
    )?;
    let completed = complete_response(&response)?;
    let suffix = surface_suffix(surface, completed)?;
    if !supported_by_context(context, surface, suffix) {
        return None;
    }
    Some(Prediction {
        continuation: suffix.into(),
        relevance: 60,
    })
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    pub(crate) static MODEL_TEST_LOCK: Mutex<()> = Mutex::new(());
    #[test]
    #[ignore = "requires an explicitly selected isolated prediction model endpoint"]
    fn live_prediction_evaluation() {
        let port = std::env::var("ZEROCON_PREDICTION_TEST_PORT")
            .expect("set isolated test port")
            .parse()
            .unwrap();
        let config = AiAssistConfig {
            enabled: true,
            prediction_port: port,
            corrector_port: 1,
            ..Default::default()
        };
        for (reading, surface) in [
            ("きょうは", "今日は"),
            ("あしたのかいぎで", "明日の会議で"),
            ("このもんだいについて", "この問題について"),
            ("きのうのよるは", "昨日の夜は"),
            ("ぷろぐらむの", "プログラムの"),
        ] {
            let started = std::time::Instant::now();
            let prediction = suggest("", reading, surface, &config);
            println!(
                "{}",
                json!({"reading":reading, "surface":surface,
                "continuation":prediction.as_ref().map(|p| &p.continuation),
                "relevance":prediction.as_ref().map(|p| p.relevance), "elapsed_ms":started.elapsed().as_millis()})
            );
        }
    }
    pub(crate) fn model_fixture(
        contents: [&'static str; 2],
    ) -> (AiAssistConfig, std::thread::JoinHandle<Vec<String>>) {
        use std::io::{BufRead, BufReader, Read, Write};
        use std::net::TcpListener;
        use std::time::{Duration, Instant};
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        listener.set_nonblocking(true).unwrap();
        let worker = std::thread::spawn(move || {
            let mut prompts = Vec::new();
            for content in contents {
                let deadline = Instant::now() + Duration::from_secs(2);
                let mut stream = loop {
                    if let Ok((stream, _)) = listener.accept() {
                        break stream;
                    }
                    assert!(Instant::now() < deadline, "expected two model requests");
                    std::thread::sleep(Duration::from_millis(1));
                };
                stream.set_nonblocking(false).unwrap();
                stream
                    .set_read_timeout(Some(Duration::from_secs(1)))
                    .unwrap();
                let mut reader = BufReader::new(&mut stream);
                let mut length = 0;
                loop {
                    let mut line = String::new();
                    reader.read_line(&mut line).unwrap();
                    if line == "\r\n" {
                        break;
                    }
                    if let Some(value) = line.strip_prefix("Content-Length: ") {
                        length = value.trim().parse().unwrap();
                    }
                }
                let mut body = vec![0; length];
                reader.read_exact(&mut body).unwrap();
                drop(reader);
                let payload: Value = serde_json::from_slice(&body).unwrap();
                prompts.push(payload["prompt"].as_str().unwrap().to_owned());
                let body = json!({"content":content, "stop_type":"eos"}).to_string();
                write!(
                    stream,
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                )
                .unwrap();
            }
            prompts
        });
        let config = AiAssistConfig {
            enabled: true,
            prediction_port: port,
            corrector_port: 1,
            ..Default::default()
        };
        (config, worker)
    }
    #[test]
    fn zerocon_converts_extended_reading_instead_of_appending_raw_kana() {
        let _guard = MODEL_TEST_LOCK.lock().unwrap();
        let (config, worker) = model_fixture(["マス。", "よろしくお願いします。"]);
        let result = suggest("前文。", "よろしくおねがいし", "よろしくお願いし", &config).unwrap();
        assert_eq!(result.continuation, "ます。");
        assert_eq!(result.relevance, 60);
        let prompts = worker.join().unwrap();
        assert_eq!(prompts[0], "\u{EE02}前文。\u{EE00}ヨロシクオネガイシ");
        assert_eq!(
            prompts[1],
            "\u{EE02}前文。\u{EE00}ヨロシクオネガイシマス。\u{EE01}"
        );
    }
    #[test]
    fn completion_preserves_visible_prefix_and_rejects_fragments() {
        assert_eq!(
            surface_suffix("資料を", "資料を作成します。"),
            Some("作成します。")
        );
        assert_eq!(surface_suffix("資料を", "書類を作成します。"), None);
        assert_eq!(surface_suffix("資料を", "資料を。"), None);
        assert!(complete_response(&json!({"content":"します", "stopped_limit":true})).is_none());
        assert!(!useful("\u{EE01}します"));
        assert!(!supported_by_context(
            "",
            "この問題について",
            "は、この記事でも解説していますが、"
        ));
        assert!(supported_by_context(
            "この記事の内容。",
            "詳細について",
            "は、この記事をご覧ください。"
        ));
        assert_eq!(
            reading_extension(&json!({"content":"サクセイシテ、ツギノ", "stop_type":"limit"})),
            Some("サクセイシテ、")
        );
        assert!(reading_extension(&json!({"content":"サクセイシ", "stop_type":"limit"})).is_none());
    }
    #[test]
    fn no_local_candidates_when_zerocon_is_unavailable() {
        let _guard = MODEL_TEST_LOCK.lock().unwrap();
        // These inputs previously matched the phrase table or collocation data.
        let config = AiAssistConfig {
            enabled: true,
            prediction_port: 1,
            ..Default::default()
        };
        assert!(suggest("", "よろしくおねがいし", "よろしくお願いし", &config).is_none());
        assert!(suggest("", "しりょうを", "資料を", &config).is_none());
    }
}
