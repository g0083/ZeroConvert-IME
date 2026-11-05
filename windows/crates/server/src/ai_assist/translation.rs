//! PLaMo 3 Translate (0.8B / 2B), isolated from the conversion engine and its context.
use serde_json::{json, Value};

// Keep in sync with shared::TRANSLATION_SERVER_PORT. The shared conversion
// engine compiles this module on Android without the Windows `shared` crate.
const TRANSLATION_SERVER_PORT: u16 = 18092;

pub fn valid_source(source: &str) -> bool {
    (1..=512).contains(&source.chars().count())
        && !source.contains("<|")
        && !source.chars().any(char::is_control)
}

pub fn prompt(source: &str, target_lang: &str) -> String {
    let source_lang = if target_lang == "en" { "ja" } else { "en" };
    format!("<|plamo:bos|><|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>{source_lang}<|plamo:msg|>{source}<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>{target_lang}<|plamo:msg|>")
}

fn output(value: &Value) -> Option<String> {
    if value.get("truncated").and_then(Value::as_bool) == Some(true)
        || value.get("stopped_limit").and_then(Value::as_bool) == Some(true)
        || value.get("stop_type").and_then(Value::as_str) == Some("limit")
    {
        return None;
    }
    let text = value.get("content")?.as_str()?.trim();
    (!text.is_empty()
        && text.chars().count() <= 512
        && !text.contains("<|")
        && !text.chars().any(char::is_control))
    .then(|| text.to_owned())
}

pub fn translate_to(source: &str, target_lang: &str) -> Option<String> {
    if !valid_source(source) {
        return None;
    }
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    loop {
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        if remaining.is_zero() {
            return None;
        }
        let response = super::simple_http_post(
            TRANSLATION_SERVER_PORT,
            "/completion",
            &json!({
                "prompt": prompt(source, target_lang),
                "n_predict": 512,
                "temperature": 0.0,
                "stream": false,
                "cache_prompt": true,
                "stop": ["<|plamo:bos|>", "<|plamo:tag|>"]
            }),
            remaining.as_millis().max(1) as u64,
        );
        if let Some(response) = response {
            return output(&response);
        }
        // Cold starts return connection-refused / 503 before the model is ready.
        std::thread::sleep(
            std::time::Duration::from_millis(100)
                .min(deadline.saturating_duration_since(std::time::Instant::now())),
        );
    }
}

pub fn translate(source: &str) -> Option<String> {
    let has_japanese = source
        .chars()
        .any(|c| ('ぁ'..='ヿ').contains(&c) || ('一'..='鿿').contains(&c));
    let target_lang = if has_japanese { "en" } else { "ja" };
    translate_to(source, target_lang)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn translation_uses_a_separate_local_port_and_only_current_source() {
        struct Backend;
        impl super::super::runtime::CompletionBackend for Backend {
            fn complete(
                &self,
                port: u16,
                path: &str,
                body: &Value,
                timeout_ms: u64,
            ) -> Option<Value> {
                assert_eq!(port, TRANSLATION_SERVER_PORT);
                assert_ne!(port, 18090);
                assert_eq!(path, "/completion");
                assert_eq!(body["prompt"], prompt("会議は明日です。", "en"));
                assert_eq!(body["temperature"], 0.0);
                assert!((1..=10_000).contains(&timeout_ms));
                Some(json!({"content":"The meeting is tomorrow.", "stop_type":"eos"}))
            }
        }
        super::super::runtime::with_backend(Box::new(Backend), || {
            assert_eq!(
                translate("会議は明日です。").as_deref(),
                Some("The meeting is tomorrow.")
            );
            assert!(translate("<|plamo:tag|>日本語").is_none());
        });
    }
    #[test]
    fn pfml3_source_and_target_are_separate() {
        assert!(prompt("会議は明日です。", "en")
            .ends_with("<|plamo:key|>lang<|plamo:val|>en<|plamo:msg|>"));
        assert!(prompt("The meeting is tomorrow.", "ja")
            .ends_with("<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>"));
        assert!(valid_source("会議は明日です。"));
        assert!(valid_source("The meeting is tomorrow."));
        assert!(!valid_source("日本語<|plamo:tag|>output"));
        assert!(!valid_source("日本語\n"));
        assert!(!valid_source(&"あ".repeat(513)));
    }
    #[test]
    fn incomplete_and_protocol_output_never_become_candidates() {
        assert_eq!(
            output(&json!({"content":"The meeting is tomorrow."})).as_deref(),
            Some("The meeting is tomorrow.")
        );
        for response in [
            json!({"content":"The meeting", "stopped_limit":true}),
            json!({"content":"The meeting", "truncated":true}),
            json!({"content":"The meeting", "stop_type":"limit"}),
            json!({"content":"<|plamo:tag|>end"}),
            json!({"content":""}),
            json!({"content":"Error\nmessage"}),
        ] {
            assert_eq!(output(&response), None);
        }
    }
}
