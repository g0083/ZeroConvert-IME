//! PLaMo 3 Translate (0.8B / 2B), isolated from the conversion engine and its context.
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

// Keep in sync with shared::TRANSLATION_SERVER_PORT. The shared conversion
// engine compiles this module on Android without the Windows `shared` crate.
// The Android host uses its own Kotlin prompt() in
// android/app/src/main/java/com/zeroconvert/ime/translation/TranslationText.kt;
// the literal-match tests on both sides must be updated together when the
// prompt format changes.
const TRANSLATION_SERVER_PORT: u16 = 18092;

/// Cancellation token shared between the request handler and the blocking
/// translation worker. Dropping the token cancels; the worker checks it
/// between read slices so a superseded translation stops holding the slot.
pub struct TranslationCancel {
    cancelled: Arc<AtomicBool>,
}

impl TranslationCancel {
    pub fn new() -> Self {
        Self {
            cancelled: Arc::new(AtomicBool::new(false)),
        }
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }

    pub(super) fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }

    #[cfg(test)]
    pub(crate) fn is_cancelled_for_test(&self) -> bool {
        self.is_cancelled()
    }
}

impl Default for TranslationCancel {
    fn default() -> Self {
        Self::new()
    }
}

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

/// Cancellable auto-direction variant used by the kind==3 request handler.
pub fn translate_cancellable(source: &str, cancel: &TranslationCancel) -> Option<String> {
    let has_japanese = source
        .chars()
        .any(|c| ('ぁ'..='ヿ').contains(&c) || ('一'..='鿿').contains(&c));
    let target_lang = if has_japanese { "en" } else { "ja" };
    translate_to_cancellable(source, target_lang, cancel)
}

/// Cancellable variant. The llama-server HTTP response is read in short slices;
/// when `cancel` fires, the socket is dropped mid-read so llama-server frees the
/// generation slot instead of finishing a superseded prompt.
pub fn translate_to_cancellable(
    source: &str,
    target_lang: &str,
    cancel: &TranslationCancel,
) -> Option<String> {
    if !valid_source(source) {
        return None;
    }
    // The 10s deadline must stay shorter than the kind==3 RPC timeout (11s in
    // the client's assist_candidates_background) so the server-side attempt is
    // never cut off by the client first; a cold start beyond this window is
    // retried by the client's 30s preparation wait instead.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
    loop {
        if cancel.is_cancelled() {
            return None;
        }
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        if remaining.is_zero() {
            return None;
        }
        let response = super::simple_http_post_cancellable(
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
            cancel,
        );
        if let Some(response) = response {
            return output(&response);
        }
        if cancel.is_cancelled() {
            return None;
        }
        // Cold starts return connection-refused / 503 before the model is ready.
        std::thread::sleep(
            std::time::Duration::from_millis(100)
                .min(deadline.saturating_duration_since(std::time::Instant::now())),
        );
    }
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
            let cancel = TranslationCancel::new();
            assert_eq!(
                translate_cancellable("会議は明日です。", &cancel).as_deref(),
                Some("The meeting is tomorrow.")
            );
            assert!(translate_cancellable("<|plamo:tag|>日本語", &cancel).is_none());
        });
    }
    #[test]
    fn cancelled_translation_returns_none_without_waiting_for_the_deadline() {
        struct Backend;
        impl super::super::runtime::CompletionBackend for Backend {
            fn complete(
                &self,
                _port: u16,
                _path: &str,
                _body: &Value,
                _timeout_ms: u64,
            ) -> Option<Value> {
                // Simulate a slow server: the worker observes cancellation
                // between retries instead of producing a superseded result.
                std::thread::sleep(std::time::Duration::from_millis(50));
                Some(json!({"content":"stale result", "stop_type":"eos"}))
            }
        }
        let cancel = TranslationCancel::new();
        cancel.cancel();
        super::super::runtime::with_backend(Box::new(Backend), || {
            let started = std::time::Instant::now();
            assert_eq!(
                translate_cancellable("会議は明日です。", &cancel),
                None,
                "a cancelled request must not return a translation"
            );
            assert!(
                started.elapsed() < std::time::Duration::from_secs(2),
                "cancellation must be observed promptly"
            );
        });
    }
    #[test]
    fn cancelling_after_start_stops_the_worker_before_completion() {
        struct Backend;
        impl super::super::runtime::CompletionBackend for Backend {
            fn complete(
                &self,
                _port: u16,
                _path: &str,
                _body: &Value,
                _timeout_ms: u64,
            ) -> Option<Value> {
                None
            }
        }
        // Without the override the request would retry against a missing
        // server for the full 10s deadline; cancellation must cut it short.
        let cancel = TranslationCancel::new();
        let worker_cancel = TranslationCancel::new();
        std::thread::scope(|scope| {
            scope.spawn(|| {
                std::thread::sleep(std::time::Duration::from_millis(150));
                worker_cancel.cancel();
            });
            super::super::runtime::with_backend(Box::new(Backend), || {
                let started = std::time::Instant::now();
                assert_eq!(
                    translate_cancellable("会議は明日です。", &worker_cancel),
                    None
                );
                assert!(
                    started.elapsed() < std::time::Duration::from_secs(5),
                    "mid-flight cancellation must end the retry loop early"
                );
            });
        });
        let _ = cancel;
    }
    #[test]
    fn prompt_literal_stays_in_sync_with_the_android_kotlin_implementation() {
        // The same literal is expected by TranslationTextTest
        // (android/app/src/test/java/com/zeroconvert/ime/TranslationTextTest.kt).
        // Changing the prompt format requires updating both expectations.
        assert_eq!(
            prompt("会議は明日です。", "en"),
            "<|plamo:bos|><|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>会議は明日です。<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>en<|plamo:msg|>"
        );
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
