//! Small C ABI. Requests run on workers; no JNI, TSF, HTTP server or Android API.
use crate::ai_assist::{self, runtime, AiAssistConfig};
use serde::Deserialize;
use serde_json::{json, Value};
use std::ffi::{c_char, c_void, CStr, CString};

pub type Complete = unsafe extern "C" fn(*mut c_void, bool, *const c_char, u64) -> *mut c_char;
pub type Release = unsafe extern "C" fn(*mut c_void, *mut c_char);

struct Host {
    complete: Complete,
    release: Release,
    context: *mut c_void,
    revision: u64,
    read_learning: bool,
}
impl runtime::CompletionBackend for Host {
    fn revision(&self) -> u64 {
        self.revision
    }
    fn read_learning(&self) -> bool {
        self.read_learning
    }
    fn complete(&self, port: u16, _path: &str, body: &Value, timeout_ms: u64) -> Option<Value> {
        let payload = CString::new(body.to_string()).ok()?;
        // The host returns real UTF-8 with an explicit release function.
        unsafe {
            let response = (self.complete)(
                self.context,
                port == runtime::PREDICTION_SERVER_PORT,
                payload.as_ptr(),
                timeout_ms,
            );
            if response.is_null() {
                return None;
            }
            let parsed = serde_json::from_slice(CStr::from_ptr(response).to_bytes()).ok();
            (self.release)(self.context, response);
            parsed
        }
    }
}

#[derive(Deserialize)]
struct Request {
    op: String,
    #[serde(default)]
    input: String,
    #[serde(default)]
    baseline: String,
    #[serde(default)]
    context: String,
}

pub fn dispatch(value: &str) -> Result<Value, String> {
    let envelope: Value = serde_json::from_str(value).map_err(|e| e.to_string())?;
    if envelope["op"] == "configurePaths" {
        let settings = envelope["settings"]
            .as_str()
            .ok_or("missing settings path")?;
        let reverse = envelope["reverse"]
            .as_str()
            .ok_or("missing reverse dictionary path")?;
        let _ = runtime::configure_paths(runtime::Paths {
            settings: settings.into(),
            reverse_dictionary: reverse.into(),
        });
        ai_assist::learning::load_learning_store();
        return Ok(Value::Null);
    }
    if envelope["op"] == "setVocabulary" {
        let entries = envelope["entries"].as_array().ok_or("missing vocabulary")?;
        if entries.len() > 50 {
            return Err("too many vocabulary entries".into());
        }
        let mut vocabulary = ai_assist::typo_variant::VocabularyIndex::default();
        for entry in entries {
            if let (Some(reading), Some(surface)) =
                (entry["reading"].as_str(), entry["word"].as_str())
            {
                vocabulary.insert(reading, surface);
            }
        }
        *ai_assist::typo_variant::user_index()
            .write()
            .map_err(|_| "vocabulary lock poisoned")? = vocabulary;
        return Ok(Value::Null);
    }
    if envelope["op"] == "inputPolicy" {
        use crate::conversion_policy as policy;
        return Ok(json!({
            "version": policy::INPUT_POLICY_VERSION,
            "immediateCommit": policy::IMMEDIATE_COMMIT_CHARS,
            "punctuation": policy::PUNCTUATION_FOR_READING
                .iter()
                .map(|(from, to)| json!({"from": from.to_string(), "to": to.to_string()}))
                .collect::<Vec<_>>(),
            "symbolFullwidth": policy::CHARACTER_WIDTH_SYMBOL_DEFAULTS
                .iter()
                .map(|(symbol, fullwidth)| json!({"symbol": symbol, "fullwidth": fullwidth}))
                .collect::<Vec<_>>(),
        }));
    }
    if envelope["op"] == "autoCommit" {
        use crate::conversion_policy as policy;
        use std::collections::VecDeque;
        let raw = envelope["raw"].as_str().ok_or("missing raw input")?;
        let reading = envelope["input"].as_str().ok_or("missing reading")?;
        let entries = envelope["history"]
            .as_array()
            .ok_or("missing inference history")?;
        if entries.len() > 128 || raw.chars().count() > 4096 {
            return Err("history exceeds limits".into());
        }
        let mut snapshots = Vec::new();
        let mut history = VecDeque::new();
        for entry in entries {
            let surface = entry["surface"].as_str().ok_or("missing surface")?;
            if history.back().is_none_or(|last| last != surface) {
                history.push_back(surface.to_owned());
            }
            while history.len() > 5 {
                history.pop_front();
            }
            snapshots.push(policy::LiveConversionPrefixSnapshot {
                raw_input_len: entry["rawCount"].as_u64().ok_or("missing input count")? as usize,
                hiragana_len: entry["readingCount"]
                    .as_u64()
                    .ok_or("missing reading count")? as usize,
                preview: surface.into(),
            });
        }
        let preview = envelope["baseline"].as_str().unwrap_or_default();
        return Ok(policy::find_auto_commit_live_conversion_snapshot(
            raw, reading, preview, "", &snapshots, &history,
        )
        .map(|s| json!({"readingLength":s.hiragana_len,"surface":s.preview}))
        .unwrap_or(Value::Null));
    }
    let request: Request = serde_json::from_str(value).map_err(|e| e.to_string())?;
    if request.input.chars().count() > 512
        || request.baseline.chars().count() > 2048
        || request.context.chars().count() > 4096
    {
        return Err("request exceeds engine limits".into());
    }
    let config = AiAssistConfig {
        enabled: true,
        ..AiAssistConfig::default()
    };
    match request.op.as_str() {
        "correction" => Ok(ai_assist::corrector::suggest_composition(&request.context, &request.input, &request.baseline, &config)
            .filter(|s| s.surface != request.baseline && s.surface != request.input)
            .map(|suggestion| {
                let automatic = ai_assist::corrector::can_auto_apply_composition(&request.input, &request.baseline, &suggestion.surface);
                json!({"text": suggestion.surface, "tokenReading": suggestion.token_reading,
                    "tokenSurface": suggestion.token_surface, "autoApply": automatic,
                    "relevance": if automatic { 100 } else if suggestion.learned { 70 } else { 30 }})
            }).unwrap_or(Value::Null)),
        "prediction" => Ok(ai_assist::prediction::suggest(&request.context, &request.input, &request.baseline, &config)
            .map(|s| json!({"text": format!("{}{}", request.baseline, s.continuation),
                "continuation": s.continuation, "relevance": s.relevance})).unwrap_or(Value::Null)),
        "macros" => Ok(json!(ai_assist::current_expansions(&request.input).iter().map(|m| &m.text).collect::<Vec<_>>())),
        "clear" => {
            ai_assist::corrector::clear_cache();
            ai_assist::prediction::clear_cache();
            Ok(Value::Null)
        }
        "learn" => Ok(json!({"saved": ai_assist::record_learned_typo(&request.input, &request.baseline)})),
        "forget" => Ok(json!({"forgot": ai_assist::learning::forget_learned_typo(&request.input, &request.baseline)})),
        "forgetAll" => Ok(json!({"forgot": ai_assist::learning::clear_learned_typos()})),
        "learningStats" => Ok(json!({"typoCount": ai_assist::learning::learned_typo_count()})),
        _ => Err("unknown engine operation".into()),
    }
}

/// # Safety
/// `request` is NUL-terminated UTF-8. Callbacks and context remain valid until return.
#[no_mangle]
pub unsafe extern "C" fn zerocon_assist_request(
    request: *const c_char,
    complete: Complete,
    release: Release,
    context: *mut c_void,
) -> *mut c_char {
    let result = std::panic::catch_unwind(|| {
        let request = if request.is_null() {
            return Err("null request".into());
        } else {
            CStr::from_ptr(request)
                .to_str()
                .map_err(|e| e.to_string())?
        };
        let envelope = serde_json::from_str::<Value>(request).map_err(|e| e.to_string())?;
        let revision = envelope["revision"].as_u64().unwrap_or(0);
        let read_learning = envelope["readLearning"].as_bool().unwrap_or(false);
        runtime::with_backend(
            Box::new(Host {
                complete,
                release,
                context,
                revision,
                read_learning,
            }),
            || dispatch(request),
        )
    });
    let value = match result {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({"error": error}),
        Err(_) => json!({"error": "engine panic"}),
    };
    CString::new(value.to_string()).unwrap().into_raw()
}

/// # Safety
/// Only free pointers returned by `zerocon_assist_request`, once.
#[no_mangle]
pub unsafe extern "C" fn zerocon_engine_free(value: *mut c_char) {
    if !value.is_null() {
        drop(CString::from_raw(value));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    struct Fixture(Arc<Mutex<Vec<Value>>>, u64);
    impl runtime::CompletionBackend for Fixture {
        fn revision(&self) -> u64 {
            self.1
        }
        fn complete(&self, _: u16, _: &str, _: &Value, _: u64) -> Option<Value> {
            let mut replies = self.0.lock().unwrap();
            if replies.is_empty() {
                None
            } else {
                Some(replies.remove(0))
            }
        }
    }

    #[test]
    fn native_prediction_runs_the_windows_two_stage_pipeline() {
        let _guard = ai_assist::prediction::tests::MODEL_TEST_LOCK
            .lock()
            .unwrap();
        ai_assist::prediction::clear_cache();
        let replies = Arc::new(Mutex::new(vec![
            json!({"content":"デス。"}),
            json!({"content":"新しい共通試験です。"}),
        ]));
        let result = runtime::with_backend(Box::new(Fixture(replies.clone(), 1)), || dispatch(
            r#"{"op":"prediction","input":"あたらしいきょうつうしけん","baseline":"新しい共通試験","context":"独立した試験"}"#
        )).unwrap();
        assert_eq!(result["continuation"], "です。");
        assert!(replies.lock().unwrap().is_empty());
        // Identical input after a model switch must run inference again.
        let replacement = Arc::new(Mutex::new(vec![
            json!({"content":"デシタ。"}),
            json!({"content":"新しい共通試験でした。"}),
        ]));
        let result = runtime::with_backend(Box::new(Fixture(replacement.clone(), 2)), || dispatch(
            r#"{"op":"prediction","input":"あたらしいきょうつうしけん","baseline":"新しい共通試験","context":"独立した試験"}"#
        )).unwrap();
        assert_eq!(result["continuation"], "でした。");
        assert!(replacement.lock().unwrap().is_empty());
    }

    #[test]
    fn native_bridge_publishes_the_shared_input_policy() {
        let policy = dispatch(r#"{"op":"inputPolicy"}"#).unwrap();
        assert_eq!(
            policy["version"],
            crate::conversion_policy::INPUT_POLICY_VERSION
        );
        assert_eq!(
            policy["immediateCommit"],
            crate::conversion_policy::IMMEDIATE_COMMIT_CHARS
        );
        assert_eq!(
            policy["punctuation"].as_array().unwrap().len(),
            crate::conversion_policy::PUNCTUATION_FOR_READING.len()
        );
        assert_eq!(
            policy["symbolFullwidth"].as_array().unwrap().len(),
            crate::conversion_policy::CHARACTER_WIDTH_SYMBOL_DEFAULTS.len()
        );
        let at = policy["symbolFullwidth"]
            .as_array()
            .unwrap()
            .iter()
            .find(|entry| entry["symbol"] == "@")
            .unwrap();
        assert_eq!(at["fullwidth"], false);
    }

    #[test]
    fn native_bridge_rejects_oversized_and_unknown_requests() {
        assert!(
            dispatch(&json!({"op":"correction","input":"あ".repeat(513)}).to_string()).is_err()
        );
        assert!(dispatch(r#"{"op":"unknown"}"#).is_err());
    }

    // Learning dispatch reads and writes one process-wide store, so tests that
    // touch it must not interleave with each other.
    fn learning_test_lock() -> std::sync::MutexGuard<'static, ()> {
        static LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());
        LOCK.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    #[test]
    fn native_bridge_forgets_only_the_requested_typo() {
        let _guard = learning_test_lock();
        // The learning file is shared process state, so this test drives the
        // dispatch contract rather than assuming an empty store.
        let learn = json!({
            "op": "learn",
            "input": "ぶりっじけんしょう",
            "baseline": "ブリッジ検証"
        });
        assert_eq!(dispatch(&learn.to_string()).unwrap()["saved"], true);

        let forget = json!({
            "op": "forget",
            "input": "ぶりっじけんしょう",
            "baseline": "ブリッジ検証"
        });
        assert_eq!(dispatch(&forget.to_string()).unwrap()["forgot"], true);
        // A second removal has nothing left to delete.
        assert_eq!(dispatch(&forget.to_string()).unwrap()["forgot"], false);

        let stats = dispatch(r#"{"op":"learningStats"}"#).unwrap();
        assert!(stats["typoCount"].is_u64());
    }

    #[test]
    fn native_bridge_requires_the_exact_surface_to_forget() {
        let _guard = learning_test_lock();
        let learn = json!({
            "op": "learn",
            "input": "ぶりっじけんしょう",
            "baseline": "ブリッジ検証"
        });
        assert_eq!(dispatch(&learn.to_string()).unwrap()["saved"], true);
        let wrong = json!({
            "op": "forget",
            "input": "ぶりっじけんしょう",
            "baseline": "別の表記"
        });
        assert_eq!(dispatch(&wrong.to_string()).unwrap()["forgot"], false);
        let correct = json!({
            "op": "forget",
            "input": "ぶりっじけんしょう",
            "baseline": "ブリッジ検証"
        });
        assert_eq!(dispatch(&correct.to_string()).unwrap()["forgot"], true);
    }

    #[test]
    fn native_bridge_forgets_all_typos() {
        let _guard = learning_test_lock();
        dispatch(&json!({"op":"learn","input":"ぜんぶけす","baseline":"全部消す"}).to_string())
            .unwrap();
        assert_eq!(dispatch(r#"{"op":"forgetAll"}"#).unwrap()["forgot"], true);
        assert_eq!(
            dispatch(r#"{"op":"learningStats"}"#).unwrap()["typoCount"],
            0
        );
        // Nothing left to clear.
        assert_eq!(dispatch(r#"{"op":"forgetAll"}"#).unwrap()["forgot"], false);
    }
}
