//! Small C ABI. Requests run on workers; no JNI, TSF, HTTP server or Android API.
use crate::ai_assist::{self, runtime, AiAssistConfig};
use serde::Deserialize;
use serde_json::{json, Value};
use std::ffi::{c_char, c_void, CStr, CString};
use std::path::PathBuf;

pub type Complete = unsafe extern "C" fn(*mut c_void, bool, *const c_char, u64) -> *mut c_char;
pub type Release = unsafe extern "C" fn(*mut c_void, *mut c_char);

#[derive(Clone)]
struct Host {
    complete: Complete,
    release: Release,
    context: *mut c_void,
    revision: u64,
    read_learning: bool,
    benchmark_dictionary: Option<PathBuf>,
}
impl Host {
    fn keyboard_repair_request(
        &self,
        raw: &str,
        input: &str,
        context: &str,
        touch_hints: &str,
        input_mode: &str,
        timeout_ms: u64,
    ) -> Option<Option<Value>> {
        // Values must survive as bare JSON text for the C++ side: drop controls,
        // quotes and backslashes instead of escaping them.
        let clean = |text: &str| -> String {
            text.chars()
                .filter(|c| !c.is_control() && *c != '"' && *c != '\\')
                .collect()
        };
        let dictionary = self
            .benchmark_dictionary
            .clone()
            .or_else(runtime::reverse_dictionary_path)
            .map(|p| clean(&p.to_string_lossy()))
            .unwrap_or_default();
        let body = json!({
            "keyboard_repair": "1",
            "raw": clean(raw),
            "input": clean(input),
            "context": clean(context),
            "touch": clean(touch_hints),
            "mode": clean(input_mode),
            "dictionary": dictionary,
        });
        Some(
            runtime::CompletionBackend::complete(
                self,
                runtime::PREDICTION_SERVER_PORT,
                "",
                &body,
                timeout_ms,
            )
            .filter(|value| !value.is_null()),
        )
    }
}
impl runtime::CompletionBackend for Host {
    fn revision(&self) -> u64 {
        self.revision
    }
    fn read_learning(&self) -> bool {
        self.read_learning
    }
    fn cache_results(&self) -> bool {
        self.benchmark_dictionary.is_none()
    }
    // Repair runs in-process on the loaded model, so the native-worker gate is open.
    fn allow_native_worker(&self) -> bool {
        true
    }
    fn keyboard_repair(
        &self,
        raw: &str,
        input: &str,
        context: &str,
        timeout_ms: u64,
    ) -> Option<Option<Value>> {
        self.keyboard_repair_request(raw, input, context, "", "", timeout_ms)
    }
    fn keyboard_repair_with_hints(
        &self,
        raw: &str,
        input: &str,
        context: &str,
        touch_hints: &str,
        input_mode: &str,
        timeout_ms: u64,
    ) -> Option<Option<Value>> {
        self.keyboard_repair_request(raw, input, context, touch_hints, input_mode, timeout_ms)
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
    #[serde(default, rename = "baselineReading")]
    baseline_reading: String,
    #[serde(default)]
    raw: String,
    #[serde(default, rename = "touchHints")]
    touch_hints: String,
    #[serde(default, rename = "inputMode")]
    input_mode: String,
    #[serde(default, rename = "autoCorrectionStrength")]
    auto_correction_strength: String,
    #[serde(default, rename = "originReading")]
    origin_reading: String,
    #[serde(default, rename = "originRaw")]
    origin_raw: String,
    #[serde(default, rename = "originTouchHints")]
    origin_touch_hints: String,
    #[serde(default, rename = "appliedPrefix")]
    applied_prefix: String,
}

fn correction_suggestion_json(suggestion: &ai_assist::TypoSuggestion, automatic: bool) -> Value {
    json!({
        "text": suggestion.surface,
        "tokenReading": suggestion.token_reading,
        "tokenSurface": suggestion.token_surface,
        "autoApply": automatic,
        "relevance": if automatic { 100 } else if suggestion.learned { 70 } else { 30 }
    })
}

fn assist_config() -> AiAssistConfig {
    AiAssistConfig {
        enabled: true,
        // Android uses the same loaded model for correction and prediction.
        corrector_port: runtime::PREDICTION_SERVER_PORT,
        ..AiAssistConfig::default()
    }
}

fn initial_correction(
    context: &str,
    reading: &str,
    baseline: &str,
    raw: &str,
    touch_hints: &str,
    input_mode: &str,
    config: &AiAssistConfig,
) -> Option<ai_assist::TypoSuggestion> {
    ai_assist::corrector::suggest_composition_with_input_hints(
        context,
        reading,
        baseline,
        raw,
        touch_hints,
        input_mode,
        config,
    )
    .filter(|suggestion| suggestion.surface != baseline && suggestion.surface != reading)
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
        ai_assist::english_learning::load_english_learning_store();
        return Ok(Value::Null);
    }
    if envelope["op"] == "setVocabulary" {
        let entries = envelope["entries"].as_array().ok_or("missing vocabulary")?;
        if entries.len() > crate::conversion_policy::MAX_USER_DICTIONARY_ENTRIES {
            return Err("too many vocabulary entries".into());
        }
        ai_assist::set_user_dictionary_vocabulary(
            entries
                .iter()
                .filter_map(|entry| Some((entry["reading"].as_str()?, entry["word"].as_str()?))),
        );
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
            policy::push_live_conversion_history(&mut history, surface);
            snapshots.push(policy::LiveConversionPrefixSnapshot {
                raw_input_len: entry["rawCount"].as_u64().ok_or("missing input count")? as usize,
                hiragana_len: entry["readingCount"]
                    .as_u64()
                    .ok_or("missing reading count")? as usize,
                preview: surface.into(),
            });
        }
        let preview = envelope["baseline"].as_str().unwrap_or_default();
        let mode = envelope["mode"]
            .as_str()
            .map(policy::AutoCommitLiveConversionMode::from_str_lossy)
            .unwrap_or_default();
        let top_token_margins = envelope["topTokenMargins"]
            .as_str()
            .map(parse_token_logit_margins)
            .unwrap_or_default();
        return Ok(policy::find_auto_commit_live_conversion_snapshot_with_policy(
            raw,
            reading,
            preview,
            "",
            &snapshots,
            &history,
            mode,
            &top_token_margins,
        )
        .map(|s| json!({"readingLength":s.hiragana_len,"surface":s.preview}))
        .unwrap_or(Value::Null));
    }
    if envelope["op"] == "englishSuggest" {
        let prefix = envelope["input"].as_str().unwrap_or_default();
        if prefix.chars().count() > 64 {
            return Err("english prefix exceeds limits".into());
        }
        let limit = envelope["limit"].as_u64().unwrap_or(8).clamp(1, 32) as usize;
        let read_learning = envelope["readLearning"].as_bool().unwrap_or(false);
        let suggestions =
            ai_assist::english_learning::suggest_english(prefix, limit, read_learning);
        let promoted_prefix =
            read_learning && ai_assist::english_learning::is_promoted_english_word(prefix);
        return Ok(json!({
            "suggestions": suggestions,
            "promotedPrefix": promoted_prefix,
        }));
    }
    if envelope["op"] == "englishRecord" {
        let prefix = envelope["prefix"].as_str().unwrap_or_default();
        let word = envelope["word"].as_str().unwrap_or_default();
        if prefix.chars().count() > 64 || word.chars().count() > 64 {
            return Err("english learning record exceeds limits".into());
        }
        let explicit_selection = envelope["explicitSelection"].as_bool().unwrap_or(false);
        let in_dictionary = envelope["inDictionary"].as_bool().unwrap_or(false);
        let (_, saved) = ai_assist::english_learning::record_english_word(
            prefix,
            word,
            explicit_selection,
            in_dictionary,
        );
        return Ok(json!({ "saved": saved }));
    }
    if envelope["op"] == "englishForget" {
        let word = envelope["word"].as_str().unwrap_or_default();
        let prefix = envelope["prefix"].as_str().filter(|s| !s.is_empty());
        if word.chars().count() > 64 || prefix.is_some_and(|p| p.chars().count() > 64) {
            return Err("english forget request exceeds limits".into());
        }
        return Ok(json!({
            "forgot": ai_assist::english_learning::forget_english_word(word, prefix),
        }));
    }
    if envelope["op"] == "englishForgetAll" {
        return Ok(json!({
            "forgot": ai_assist::english_learning::clear_english_learning(),
        }));
    }
    if envelope["op"] == "englishDictionaryImport" {
        let source = envelope["source"]
            .as_str()
            .ok_or("missing decompressed dictionary path")?;
        let destination = envelope["destination"]
            .as_str()
            .ok_or("missing cache path")?;
        let limit = envelope["limit"]
            .as_u64()
            .unwrap_or(crate::english_dictionary::DEFAULT_WORD_LIMIT as u64)
            .clamp(1_000, 200_000) as usize;
        let bytes = std::fs::read(source).map_err(|error| error.to_string())?;
        if bytes.len() > 32 * 1024 * 1024 {
            return Err("english dictionary payload exceeds limits".into());
        }
        let words =
            crate::english_dictionary::import(&bytes, std::path::Path::new(destination), limit)?;
        return Ok(json!({ "words": words }));
    }
    if envelope["op"] == "englishPromotedWords" {
        let read_learning = envelope["readLearning"].as_bool().unwrap_or(false);
        let words = if read_learning {
            ai_assist::english_learning::promoted_english_words()
        } else {
            Vec::new()
        };
        return Ok(json!({ "words": words }));
    }
    // 共通ベンチマーク採点（純関数、推論なし）。Windows/Android が同一結果にする。
    if envelope["op"] == "benchmarkScoreConversion" {
        let output = envelope["output"].as_str().ok_or("missing output")?;
        let expected = envelope["expected"].as_str().ok_or("missing expected")?;
        let acceptable: Vec<String> = envelope["acceptable"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default();
        let forbidden: Vec<String> = envelope["forbidden"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default();
        let score =
            ai_assist::benchmark::score_conversion(output, expected, &acceptable, &forbidden);
        return Ok(json!({ "isCorrect": score.is_correct, "reason": score.reason }));
    }
    if envelope["op"] == "benchmarkScoreTypo" {
        let is_clean = envelope["isClean"].as_bool().ok_or("missing isClean")?;
        let expected = envelope["expected"].as_str().ok_or("missing expected")?;
        let acceptable: Vec<String> = envelope["acceptable"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default();
        let input_reading = envelope["inputReading"].as_str().unwrap_or("");
        let main_output = envelope["mainOutput"].as_str().unwrap_or("");
        let suggestion = envelope["suggestion"].as_str();
        let score = ai_assist::benchmark::score_typo(
            is_clean,
            expected,
            &acceptable,
            input_reading,
            main_output,
            suggestion,
        );
        return Ok(
            json!({ "status": score.status.as_str(), "suggestionExact": score.suggestion_exact }),
        );
    }
    if envelope["op"] == "benchmarkScorePredictive" {
        let prediction = envelope["prediction"].as_str();
        let full_sentence = envelope["fullSentence"]
            .as_str()
            .ok_or("missing fullSentence")?;
        let expected_continuation = envelope["expectedContinuation"]
            .as_str()
            .ok_or("missing expectedContinuation")?;
        let acceptable: Vec<String> = envelope["acceptableContinuations"]
            .as_array()
            .map(|a| {
                a.iter()
                    .filter_map(|v| v.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default();
        let ok = ai_assist::benchmark::score_predictive(
            prediction,
            full_sentence,
            expected_continuation,
            &acceptable,
        );
        return Ok(json!({ "isMatch": ok }));
    }
    if envelope["op"] == "benchmarkLatencyPercentiles" {
        let latencies: Vec<f64> = envelope["latencies"]
            .as_array()
            .map(|a| a.iter().filter_map(|v| v.as_f64()).collect())
            .unwrap_or_default();
        let (p50, p95, p99) = ai_assist::benchmark::latency_percentiles(&latencies);
        return Ok(json!({ "p50": p50, "p95": p95, "p99": p99 }));
    }
    let request: Request = serde_json::from_str(value).map_err(|e| e.to_string())?;
    if request.input.chars().count() > 512
        || request.baseline.chars().count() > 2048
        || request.context.chars().count() > 4096
        || request.baseline_reading.chars().count() > 512
        || request.raw.chars().count() > 256
        || request.touch_hints.chars().count() > 2048
        || request.origin_reading.chars().count() > 512
        || request.origin_raw.chars().count() > 256
        || request.origin_touch_hints.chars().count() > 2048
        || request.applied_prefix.chars().count() > 512
    {
        return Err("request exceeds engine limits".into());
    }
    if !matches!(request.input_mode.as_str(), "" | "qwerty" | "flick") {
        return Err("invalid inputMode".into());
    }
    let config = assist_config();
    let Some(assist_reading) = crate::assist_policy::assist_reading(
        &request.input,
        &request.baseline_reading,
        request.op == "correction",
    ) else {
        return Ok(Value::Null);
    };
    let strength = if request.auto_correction_strength.trim().is_empty() {
        crate::assist_policy::AutoTypoCorrectionStrength::Standard
    } else {
        crate::assist_policy::AutoTypoCorrectionStrength::from_str_lossy(
            &request.auto_correction_strength,
        )
    };
    match request.op.as_str() {
        "correction" => {
            if !request.origin_reading.is_empty() && !request.applied_prefix.is_empty() {
                let refinement = ai_assist::corrector::AutoRefinementContext {
                    origin_reading: &request.origin_reading,
                    origin_raw: &request.origin_raw,
                    origin_touch_hints: &request.origin_touch_hints,
                    applied_prefix: &request.applied_prefix,
                };
                return Ok(
                    match ai_assist::corrector::suggest_auto_refinement(
                        &request.context,
                        assist_reading,
                        &request.baseline,
                        &request.raw,
                        &request.touch_hints,
                        &request.input_mode,
                        &refinement,
                        strength,
                        &config,
                    ) {
                        ai_assist::corrector::CorrectionOutcome::RevertToOrigin => {
                            json!({"revertToOrigin": true, "autoApply": true, "relevance": 100})
                        }
                        ai_assist::corrector::CorrectionOutcome::Suggestion {
                            suggestion,
                            auto_apply,
                            ..
                        } if suggestion.surface != request.baseline
                            && suggestion.surface != request.input =>
                        {
                            correction_suggestion_json(&suggestion, auto_apply)
                        }
                        _ => Value::Null,
                    },
                );
            }
            Ok(initial_correction(
                &request.context,
                assist_reading,
                &request.baseline,
                &request.raw,
                &request.touch_hints,
                &request.input_mode,
                &config,
            )
            .map(|suggestion| {
                let automatic =
                    ai_assist::corrector::can_auto_apply_composition_suggestion_with_strength(
                        &request.input,
                        &request.baseline,
                        &suggestion,
                        strength,
                    );
                correction_suggestion_json(&suggestion, automatic)
            })
            .unwrap_or(Value::Null))
        }
        "prediction" => Ok(ai_assist::prediction::suggest(
            &request.context,
            assist_reading,
            &request.baseline,
            &config,
        )
        .map(|s| {
            json!({"text": format!("{}{}", request.baseline, s.continuation),
                "continuation": s.continuation, "relevance": s.relevance})
        })
        .unwrap_or(Value::Null)),
        "macros" => Ok(json!(ai_assist::current_expansions(&request.input)
            .iter()
            .map(|m| &m.text)
            .collect::<Vec<_>>())),
        "clear" => {
            ai_assist::corrector::clear_cache();
            ai_assist::prediction::clear_cache();
            Ok(Value::Null)
        }
        "learn" => {
            Ok(json!({"saved": ai_assist::record_learned_typo(&request.input, &request.baseline)}))
        }
        "forget" => Ok(
            json!({"forgot": ai_assist::learning::forget_learned_typo(&request.input, &request.baseline)}),
        ),
        "forgetAll" => Ok(json!({"forgot": ai_assist::learning::clear_learned_typos()})),
        "learningStats" => Ok(json!({
            "typoCount": ai_assist::learning::learned_typo_count(),
            "englishCount": ai_assist::english_learning::learned_english_count(),
        })),
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
        if envelope["op"] == "benchmark_info" {
            ai_assist::benchmark::reset_session();
            return Ok(json!({"batch_size": ai_assist::benchmark::BATCH_SIZE}));
        }
        if envelope["op"] == "benchmark" {
            let cases = serde_json::from_value::<Vec<ai_assist::benchmark::Case>>(
                envelope["cases"].clone(),
            )
            .map_err(|e| e.to_string())?;
            use ai_assist::benchmark::{self, ConversionScope};
            use runtime::CompletionBackend;
            let scope: ai_assist::benchmark::ConversionScope = envelope
                .get("scope")
                .cloned()
                .map(serde_json::from_value)
                .transpose()
                .map_err(|e| e.to_string())?
                .unwrap_or_default();
            let dictionary = std::path::Path::new(envelope["dictionary"].as_str().unwrap_or(""));
            let host = Host {
                complete,
                release,
                context,
                revision: 0,
                read_learning: false,
                benchmark_dictionary: (scope == ConversionScope::Typo)
                    .then(|| dictionary.to_owned()),
            };
            let convert = |reading: &str, context: &str| {
                let reply =
                    host.complete(0, "", &json!({"reading": reading, "context": context}), 0)?;
                serde_json::from_value(reply).ok()
            };
            if scope == ConversionScope::Candidates {
                return benchmark::run_candidate_batch(&cases, convert)
                    .map(|rows| json!({"rows": rows}));
            }
            if scope == ConversionScope::Typo {
                if cases.is_empty() || cases.len() > benchmark::BATCH_SIZE {
                    return Err("invalid benchmark batch size".into());
                }
                let enabled = envelope["typo_enabled"]
                    .as_bool()
                    .ok_or("missing typo_enabled")?;
                let config = assist_config();
                return runtime::with_backend(Box::new(host.clone()), || {
                    let rows = cases
                        .iter()
                        .map(|case| {
                            benchmark::run_typo_case(
                                case,
                                dictionary,
                                convert,
                                |context, reading, baseline, raw| {
                                    if enabled {
                                        initial_correction(
                                            context, reading, baseline, raw, "", "", &config,
                                        )
                                    } else {
                                        None
                                    }
                                },
                            )
                        })
                        .collect::<Result<Vec<_>, _>>()?;
                    Ok(json!({"rows": rows}))
                });
            }
            return benchmark::run_batch(&cases, dictionary, move |prompt, tokens, timeout| {
                let request = json!({"prompt": prompt, "n_predict": tokens});
                let reply = host.complete(0, "", &request, timeout)?;
                serde_json::from_value(reply).ok()
            })
            .map(|rows| json!({"rows": rows}));
        }
        let revision = envelope["revision"].as_u64().unwrap_or(0);
        let read_learning = envelope["readLearning"].as_bool().unwrap_or(false);
        runtime::with_backend(
            Box::new(Host {
                complete,
                release,
                context,
                revision,
                read_learning,
                benchmark_dictionary: None,
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

/// # Safety
/// `request` is a NUL-terminated UTF-8 string, valid until this call returns.
/// Free the returned string once with `zerocon_engine_free`.
#[no_mangle]
pub unsafe extern "C" fn zerocon_assist_policy(request: *const c_char) -> *mut c_char {
    let result = std::panic::catch_unwind(|| {
        if request.is_null() {
            return Err("null policy request".into());
        }
        let request = CStr::from_ptr(request)
            .to_str()
            .map_err(|error| error.to_string())?;
        crate::assist_policy::dispatch(request)
    });
    let value = match result {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => json!({"error": error}),
        Err(_) => json!({"error": "assist policy panic"}),
    };
    CString::new(value.to_string()).unwrap().into_raw()
}

fn parse_token_logit_margins(raw: &str) -> Vec<crate::conversion_policy::TokenLogitMargin> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }
    let mut margins = Vec::new();
    let mut prev_end = 0u32;
    for entry in trimmed.split(',').take(128) {
        let Some((end_str, milli_str)) = entry.split_once(':') else {
            return Vec::new();
        };
        let (Ok(byte_end), Ok(margin_milli)) = (end_str.parse::<u32>(), milli_str.parse::<u32>())
        else {
            return Vec::new();
        };
        if byte_end <= prev_end {
            return Vec::new();
        }
        prev_end = byte_end;
        margins.push(crate::conversion_policy::TokenLogitMargin {
            byte_end,
            margin_milli,
        });
    }
    margins
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, Mutex};

    #[test]
    fn set_vocabulary_accepts_10000_entries_and_rejects_10001_without_mutation() {
        let _guard = ai_assist::USER_DICTIONARY_TEST_LOCK.lock().unwrap();
        let mut entries: Vec<_> = (0..10_000)
            .map(|index| json!({"reading": format!("てすとじしょ{index}"), "word": format!("語{index}")}))
            .collect();
        assert_eq!(
            dispatch(&json!({"op":"setVocabulary","entries":entries}).to_string()),
            Ok(Value::Null)
        );
        assert_eq!(
            ai_assist::find_indexed_surface("てすとじしょ9999").as_deref(),
            Some("語9999")
        );
        entries.push(json!({"reading":"てすとじしょ10000","word":"語10000"}));
        assert!(dispatch(&json!({"op":"setVocabulary","entries":entries}).to_string()).is_err());
        assert_eq!(
            ai_assist::find_indexed_surface("てすとじしょ9999").as_deref(),
            Some("語9999")
        );
        assert!(ai_assist::find_indexed_surface("てすとじしょ10000").is_none());
        dispatch(r#"{"op":"setVocabulary","entries":[]}"#).unwrap();
    }

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
    fn prediction_uses_the_corrected_reading_issued_with_the_normal_candidate() {
        let _guard = ai_assist::prediction::tests::MODEL_TEST_LOCK
            .lock()
            .unwrap();
        ai_assist::prediction::clear_cache();
        struct Capturing(Arc<Mutex<Vec<Value>>>);
        impl runtime::CompletionBackend for Capturing {
            fn read_learning(&self) -> bool {
                false
            }
            fn complete(&self, _: u16, _: &str, body: &Value, _: u64) -> Option<Value> {
                let mut calls = self.0.lock().unwrap();
                calls.push(body.clone());
                Some(if calls.len() == 1 {
                    json!({"content": "デス。"})
                } else {
                    json!({"content": "新しい共通試験です。"})
                })
            }
        }
        let calls = Arc::new(Mutex::new(Vec::new()));
        let result = runtime::with_backend(Box::new(Capturing(calls.clone())), || dispatch(
            r#"{"op":"prediction","input":"あたらしいきょうつうしけな","baselineReading":"あたらしいきょうつうしけん","baseline":"新しい共通試験"}"#
        )).unwrap();
        assert_eq!(result["continuation"], "です。");
        let calls = calls.lock().unwrap();
        assert_eq!(calls.len(), 2);
        assert!(calls[0]["prompt"]
            .as_str()
            .unwrap()
            .ends_with("アタラシイキョウツウシケン"));
        assert!(calls[1]["prompt"]
            .as_str()
            .unwrap()
            .contains("アタラシイキョウツウシケンデス。"));
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

    struct HostedRepair(Value, Value);
    impl runtime::CompletionBackend for HostedRepair {
        fn read_learning(&self) -> bool {
            false
        }
        fn allow_native_worker(&self) -> bool {
            true
        }
        fn keyboard_repair(&self, _: &str, input: &str, _: &str, _: u64) -> Option<Option<Value>> {
            assert_eq!(input, "ああた");
            Some(Some(self.0.clone()))
        }
        fn complete(&self, _: u16, _: &str, _: &Value, _: u64) -> Option<Value> {
            Some(self.1.clone())
        }
    }

    #[test]
    fn hosted_keyboard_repair_replaces_the_worker_process() {
        // Reading validation needs no dictionary; a hosted answer is gated by the
        // same observed-input and gain >= 37 rules as the Windows worker.
        let strong =
            json!({"observed":"ああた","reading":"あした","alt_reading":"あさた","gain":40.0});
        let ask = |reply: Value| {
            runtime::with_backend(Box::new(HostedRepair(reply, Value::Null)), || {
                ai_assist::keyboard_hybrid::repair_readings("", "ああた", "", false)
            })
        };
        assert_eq!(
            ask(strong),
            Some(vec!["あした".to_string(), "あさた".to_string()])
        );
        for weak in [
            json!({"observed":"ああた"}),
            json!({"observed":"ああた","reading":"あした","gain":36.0}),
        ] {
            assert_eq!(ask(weak.clone()), Some(vec![]), "{weak}");
        }
        // An answer for a different observed input is stale and discarded.
        assert_eq!(
            ask(json!({"observed":"あした","reading":"あしだ","gain":50.0})),
            None
        );
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

    #[test]
    fn native_bridge_english_learning_follows_gboard_rules_and_forgets() {
        let _guard = learning_test_lock();
        dispatch(r#"{"op":"englishForgetAll"}"#).unwrap();
        assert_eq!(
            dispatch(r#"{"op":"learningStats"}"#).unwrap()["englishCount"],
            0
        );

        // OOV direct commit once -> pending only, not promoted.
        let first = dispatch(
            &json!({
                "op": "englishRecord",
                "prefix": "ZeroConvert",
                "word": "ZeroConvert",
                "explicitSelection": false,
                "inDictionary": false
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(first["saved"], true);
        assert_eq!(
            dispatch(r#"{"op":"learningStats"}"#).unwrap()["englishCount"],
            0
        );

        // Second direct commit promotes OOV.
        dispatch(
            &json!({
                "op": "englishRecord",
                "prefix": "ZeroConvert",
                "word": "ZeroConvert",
                "explicitSelection": false,
                "inDictionary": false
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(
            dispatch(r#"{"op":"learningStats"}"#).unwrap()["englishCount"],
            1
        );

        // Explicit selection and dictionary direct commit promote immediately.
        dispatch(
            &json!({
                "op": "englishRecord",
                "prefix": "hel",
                "word": "hello",
                "explicitSelection": true,
                "inDictionary": true
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(
            dispatch(r#"{"op":"learningStats"}"#).unwrap()["englishCount"],
            2
        );

        let suggest_on = dispatch(
            &json!({
                "op": "englishSuggest",
                "input": "Zero",
                "limit": 8,
                "readLearning": true
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(suggest_on["suggestions"][0]["text"], "ZeroConvert");
        assert_eq!(suggest_on["suggestions"][0]["learned"], true);
        assert_eq!(suggest_on["promotedPrefix"], false);

        let suggest_exact = dispatch(
            &json!({
                "op": "englishSuggest",
                "input": "ZeroConvert",
                "limit": 8,
                "readLearning": true
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(suggest_exact["promotedPrefix"], true);

        let suggest_off = dispatch(
            &json!({
                "op": "englishSuggest",
                "input": "Zero",
                "limit": 8,
                "readLearning": false
            })
            .to_string(),
        )
        .unwrap();
        assert!(suggest_off["suggestions"].as_array().unwrap().is_empty());
        assert_eq!(suggest_off["promotedPrefix"], false);

        let promoted = dispatch(r#"{"op":"englishPromotedWords","readLearning":true}"#).unwrap();
        assert!(promoted["words"]
            .as_array()
            .unwrap()
            .iter()
            .any(|v| v == "ZeroConvert"));

        // Forget removes the requested word only.
        assert_eq!(
            dispatch(
                &json!({"op":"englishForget","word":"ZeroConvert","prefix":"Zero"}).to_string()
            )
            .unwrap()["forgot"],
            true
        );
        assert_eq!(
            dispatch(r#"{"op":"learningStats"}"#).unwrap()["englishCount"],
            1
        );

        // Forget all clears remaining entries.
        assert_eq!(
            dispatch(r#"{"op":"englishForgetAll"}"#).unwrap()["forgot"],
            true
        );
        assert_eq!(
            dispatch(r#"{"op":"learningStats"}"#).unwrap()["englishCount"],
            0
        );
        assert_eq!(
            dispatch(r#"{"op":"englishForgetAll"}"#).unwrap()["forgot"],
            false
        );
    }

    #[test]
    fn native_bridge_records_and_suggests_email_domains() {
        let _guard = learning_test_lock();
        dispatch(r#"{"op":"englishForgetAll"}"#).unwrap();

        // OOV custom domain requires 2 direct commits before promotion.
        dispatch(
            &json!({
                "op": "englishRecord",
                "prefix": "@example.co.jp",
                "word": "@example.co.jp",
                "explicitSelection": false,
                "inDictionary": false
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(
            dispatch(r#"{"op":"learningStats"}"#).unwrap()["englishCount"],
            0
        );

        dispatch(
            &json!({
                "op": "englishRecord",
                "prefix": "@example.co.jp",
                "word": "@example.co.jp",
                "explicitSelection": false,
                "inDictionary": false
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(
            dispatch(r#"{"op":"learningStats"}"#).unwrap()["englishCount"],
            1
        );

        let suggested = dispatch(
            &json!({
                "op": "englishSuggest",
                "input": "@ex",
                "limit": 8,
                "readLearning": true
            })
            .to_string(),
        )
        .unwrap();
        assert_eq!(suggested["suggestions"][0]["text"], "@example.co.jp");
        assert_eq!(suggested["suggestions"][0]["learned"], true);

        assert_eq!(
            dispatch(
                &json!({"op":"englishForget","word":"@example.co.jp","prefix":"@ex"}).to_string()
            )
            .unwrap()["forgot"],
            true
        );
        dispatch(r#"{"op":"englishForgetAll"}"#).unwrap();
    }

    #[test]
    fn correction_forwards_raw_and_touch_hints_to_keyboard_repair() {
        #[derive(Default)]
        struct Capture {
            seen: Vec<Value>,
        }
        unsafe extern "C" fn mock_complete(
            ctx: *mut c_void,
            _prediction_port: bool,
            payload: *const c_char,
            _timeout_ms: u64,
        ) -> *mut c_char {
            let state = &mut *(ctx as *mut Capture);
            let req = CStr::from_ptr(payload).to_str().unwrap();
            let parsed: Value = serde_json::from_str(req).unwrap();
            let is_repair = parsed.get("keyboard_repair").is_some();
            state.seen.push(parsed);
            if is_repair {
                CString::new(r#"{"observed":"pもってる","reading":"おもってる","gain":65.0}"#)
                    .unwrap()
                    .into_raw()
            } else {
                std::ptr::null_mut()
            }
        }
        unsafe extern "C" fn mock_release(_ctx: *mut c_void, ptr: *mut c_char) {
            if !ptr.is_null() {
                drop(CString::from_raw(ptr));
            }
        }
        let mut capture = Capture::default();
        let req = CString::new(
            json!({
                "op": "correction",
                "context": "それ",
                "input": "pもってる",
                "baseline": "P持ってる",
                "raw": "pmotteru",
                "touchHints": "0:o=0.85,l=0.30"
            })
            .to_string(),
        )
        .unwrap();
        let reply_ptr = unsafe {
            zerocon_assist_request(
                req.as_ptr(),
                mock_complete,
                mock_release,
                (&mut capture as *mut Capture).cast(),
            )
        };
        let reply: Value = unsafe {
            let text = CStr::from_ptr(reply_ptr).to_str().unwrap().to_owned();
            zerocon_engine_free(reply_ptr);
            serde_json::from_str(&text).unwrap()
        };
        let repair_req = capture
            .seen
            .iter()
            .find(|v| v.get("keyboard_repair").is_some())
            .expect("expected keyboard_repair payload");
        assert_eq!(repair_req["raw"], "pmotteru");
        assert_eq!(repair_req["touch"], "0:o=0.85,l=0.30");
        assert_eq!(repair_req["input"], "pもってる");
        assert_eq!(reply["text"], "おもってる");
    }

    #[test]
    fn correction_rejects_oversized_raw_or_touch_hints() {
        let too_long_raw = json!({
            "op": "correction",
            "input": "おもってる",
            "baseline": "思ってる",
            "raw": "a".repeat(257),
        })
        .to_string();
        assert!(dispatch(&too_long_raw).is_err());

        let too_long_touch = json!({
            "op": "correction",
            "input": "おもってる",
            "baseline": "思ってる",
            "touchHints": "x".repeat(2049),
        })
        .to_string();
        assert!(dispatch(&too_long_touch).is_err());

        let bad_mode = json!({
            "op": "correction",
            "input": "おもってる",
            "baseline": "思ってる",
            "inputMode": "12key_unknown",
        })
        .to_string();
        assert!(dispatch(&bad_mode).is_err());
    }

    #[test]
    fn correction_forwards_flick_mode_and_utf8_touch_hints() {
        #[derive(Default)]
        struct Capture {
            seen: Vec<Value>,
        }
        unsafe extern "C" fn mock_complete(
            ctx: *mut c_void,
            _prediction_port: bool,
            payload: *const c_char,
            _timeout_ms: u64,
        ) -> *mut c_char {
            let state = &mut *(ctx as *mut Capture);
            let req = CStr::from_ptr(payload).to_str().unwrap();
            let parsed: Value = serde_json::from_str(req).unwrap();
            let is_repair = parsed.get("keyboard_repair").is_some();
            state.seen.push(parsed);
            if is_repair {
                CString::new(r#"{"observed":"あきがとう","reading":"ありがとう","gain":72.0}"#)
                    .unwrap()
                    .into_raw()
            } else {
                std::ptr::null_mut()
            }
        }
        unsafe extern "C" fn mock_release(_ctx: *mut c_void, ptr: *mut c_char) {
            if !ptr.is_null() {
                drop(CString::from_raw(ptr));
            }
        }
        let mut capture = Capture::default();
        let req = CString::new(
            json!({
                "op": "correction",
                "context": "いつも",
                "input": "あきがとう",
                "baseline": "飽きがとう",
                "touchHints": "1:き=0.82",
                "inputMode": "flick"
            })
            .to_string(),
        )
        .unwrap();
        let reply_ptr = unsafe {
            zerocon_assist_request(
                req.as_ptr(),
                mock_complete,
                mock_release,
                (&mut capture as *mut Capture).cast(),
            )
        };
        let reply: Value = unsafe {
            let text = CStr::from_ptr(reply_ptr).to_str().unwrap().to_owned();
            zerocon_engine_free(reply_ptr);
            serde_json::from_str(&text).unwrap()
        };
        let repair_req = capture
            .seen
            .iter()
            .find(|v| v.get("keyboard_repair").is_some())
            .expect("expected keyboard_repair payload");
        assert_eq!(repair_req["mode"], "flick");
        assert_eq!(repair_req["touch"], "1:き=0.82");
        assert_eq!(repair_req["input"], "あきがとう");
        assert_eq!(reply["text"], "ありがとう");
    }

    #[test]
    fn correction_forwards_qwerty_dash_raw_and_touch_hints() {
        #[derive(Default)]
        struct Capture {
            seen: Vec<Value>,
        }
        unsafe extern "C" fn mock_complete(
            ctx: *mut c_void,
            _prediction_port: bool,
            payload: *const c_char,
            _timeout_ms: u64,
        ) -> *mut c_char {
            let state = &mut *(ctx as *mut Capture);
            let req = CStr::from_ptr(payload).to_str().unwrap();
            let parsed: Value = serde_json::from_str(req).unwrap();
            let is_repair = parsed.get("keyboard_repair").is_some();
            state.seen.push(parsed);
            if is_repair {
                CString::new(r#"{"observed":"ーあそこん","reading":"ぱそこん","gain":84.0}"#)
                    .unwrap()
                    .into_raw()
            } else {
                std::ptr::null_mut()
            }
        }
        unsafe extern "C" fn mock_release(_ctx: *mut c_void, ptr: *mut c_char) {
            if !ptr.is_null() {
                drop(CString::from_raw(ptr));
            }
        }
        let mut capture = Capture::default();
        let req = CString::new(
            json!({
                "op": "correction",
                "context": "",
                "input": "ーあそこん",
                "baseline": "ーアソコン",
                "raw": "-asokon",
                "touchHints": "0:p=0.85,l=0.35",
                "inputMode": "qwerty"
            })
            .to_string(),
        )
        .unwrap();
        let reply_ptr = unsafe {
            zerocon_assist_request(
                req.as_ptr(),
                mock_complete,
                mock_release,
                (&mut capture as *mut Capture).cast(),
            )
        };
        let reply: Value = unsafe {
            let text = CStr::from_ptr(reply_ptr).to_str().unwrap().to_owned();
            zerocon_engine_free(reply_ptr);
            serde_json::from_str(&text).unwrap()
        };
        let repair_req = capture
            .seen
            .iter()
            .find(|v| v.get("keyboard_repair").is_some())
            .expect("expected keyboard_repair payload");
        assert_eq!(repair_req["mode"], "qwerty");
        assert_eq!(repair_req["raw"], "-asokon");
        assert_eq!(repair_req["touch"], "0:p=0.85,l=0.35");
        assert_eq!(repair_req["input"], "ーあそこん");
        assert_eq!(reply["text"], "ぱそこん");
    }

    #[test]
    fn correction_sets_auto_apply_from_plan_b_channel_confidence() {
        unsafe extern "C" fn mock_complete(
            ctx: *mut c_void,
            _prediction_port: bool,
            _payload: *const c_char,
            _timeout_ms: u64,
        ) -> *mut c_char {
            let reply = &*(ctx as *const CString);
            CString::new(reply.as_bytes()).unwrap().into_raw()
        }
        unsafe extern "C" fn mock_release(_ctx: *mut c_void, ptr: *mut c_char) {
            if !ptr.is_null() {
                drop(CString::from_raw(ptr));
            }
        }
        let call = |input: &str, baseline: &str, reply_json: &str| -> Value {
            let mock_reply = CString::new(reply_json).unwrap();
            let req = CString::new(
                json!({
                    "op": "correction",
                    "context": "",
                    "input": input,
                    "baseline": baseline,
                    "inputMode": "qwerty"
                })
                .to_string(),
            )
            .unwrap();
            let ptr = unsafe {
                zerocon_assist_request(
                    req.as_ptr(),
                    mock_complete,
                    mock_release,
                    (&mock_reply as *const CString).cast_mut().cast(),
                )
            };
            unsafe {
                let text = CStr::from_ptr(ptr).to_str().unwrap().to_owned();
                zerocon_engine_free(ptr);
                serde_json::from_str(&text).unwrap()
            }
        };

        // Non-tail clear repair (gain 72.0, no alt_reading) auto-applies under Plan B.
        let clear = call(
            "おせわになております",
            "お世話になております",
            r#"{"observed":"おせわになております","reading":"おせわになっております","gain":72.0}"#,
        );
        assert_eq!(clear["autoApply"], true);
        assert_eq!(clear["relevance"], 100);

        // Contested non-tail repair (has alt_reading with gain 72.0 < 95.0) stays manual proposal.
        let contested = call(
            "おせわになております",
            "お世話になております",
            r#"{"observed":"おせわになております","reading":"おせわになっております","alt_reading":"おせわにないております","gain":72.0}"#,
        );
        assert_eq!(contested["autoApply"], false);
        assert_eq!(contested["relevance"], 30);

        // Tail edit with gain 72.0 < 90.0 stays manual proposal.
        let tail = call(
            "おねがいすます",
            "お願いすます",
            r#"{"observed":"おねがいすます","reading":"おねがいします","gain":72.0}"#,
        );
        // Wait: "おねがいすます" vs "おねがいします" does NOT touch the very last char ('す' == 'す')!
        assert_eq!(tail["autoApply"], true);

        let real_tail = call(
            "おねがいしまし",
            "お願いしまし",
            r#"{"observed":"おねがいしまし","reading":"おねがいします","gain":72.0}"#,
        );
        assert_eq!(real_tail["autoApply"], false);
        assert_eq!(real_tail["relevance"], 30);
    }

    #[test]
    fn correction_respects_auto_correction_strength_parameter() {
        unsafe extern "C" fn mock_complete(
            ctx: *mut c_void,
            _prediction_port: bool,
            _payload: *const c_char,
            _timeout_ms: u64,
        ) -> *mut c_char {
            let reply = &*(ctx as *const CString);
            CString::new(reply.as_bytes()).unwrap().into_raw()
        }
        unsafe extern "C" fn mock_release(_ctx: *mut c_void, ptr: *mut c_char) {
            if !ptr.is_null() {
                drop(CString::from_raw(ptr));
            }
        }
        let call_with_strength = |strength: &str, reply_json: &str| -> Value {
            let mock_reply = CString::new(reply_json).unwrap();
            let req = CString::new(
                json!({
                    "op": "correction",
                    "context": "",
                    "input": "おせわになております",
                    "baseline": "お世話になております",
                    "inputMode": "qwerty",
                    "autoCorrectionStrength": strength,
                })
                .to_string(),
            )
            .unwrap();
            let ptr = unsafe {
                zerocon_assist_request(
                    req.as_ptr(),
                    mock_complete,
                    mock_release,
                    (&mock_reply as *const CString).cast_mut().cast(),
                )
            };
            unsafe {
                let text = CStr::from_ptr(ptr).to_str().unwrap().to_owned();
                zerocon_engine_free(ptr);
                serde_json::from_str(&text).unwrap()
            }
        };

        // gain 67.0 without alt_reading: conservative (>=70.0) stays manual; standard (>=65.0) and aggressive (>=55.0) auto-apply.
        let reply_67 =
            r#"{"observed":"おせわになております","reading":"おせわになっております","gain":67.0}"#;
        assert_eq!(call_with_strength("off", reply_67)["autoApply"], false);
        assert_eq!(
            call_with_strength("conservative", reply_67)["autoApply"],
            false
        );
        assert_eq!(call_with_strength("standard", reply_67)["autoApply"], true);
        assert_eq!(
            call_with_strength("aggressive", reply_67)["autoApply"],
            true
        );

        // Contested gain 72.0: only aggressive auto-applies; off/conservative/standard stay manual.
        let reply_alt_72 = r#"{"observed":"おせわになております","reading":"おせわになっております","alt_reading":"おせわにないております","gain":72.0}"#;
        assert_eq!(call_with_strength("off", reply_alt_72)["autoApply"], false);
        assert_eq!(
            call_with_strength("conservative", reply_alt_72)["autoApply"],
            false
        );
        assert_eq!(
            call_with_strength("standard", reply_alt_72)["autoApply"],
            false
        );
        assert_eq!(
            call_with_strength("aggressive", reply_alt_72)["autoApply"],
            true
        );
    }

    #[test]
    fn correction_auto_refinement_and_revert_via_bridge_json() {
        let replies: Vec<Value> = vec![
            json!({"observed": "しゅてんします", "reading": "しゅってんします", "gain": 82.0}),
            json!({"observed": "しゅてんどうじ"}),
            json!({"observed": "きょおはあしだ", "reading": "きょおはあした", "gain": 85.0}),
            json!({"observed": "きょうはあしだ", "reading": "きょうはあした", "gain": 92.0}),
        ];
        struct QueueHost {
            replies: Vec<Value>,
            index: std::cell::Cell<usize>,
        }
        unsafe extern "C" fn mock_complete(
            ctx: *mut c_void,
            _prediction_port: bool,
            _payload: *const c_char,
            _timeout_ms: u64,
        ) -> *mut c_char {
            let host = &*(ctx as *const QueueHost);
            let i = host.index.get();
            host.index.set(i + 1);
            let val = host.replies.get(i).cloned().unwrap_or(Value::Null);
            CString::new(val.to_string()).unwrap().into_raw()
        }
        unsafe extern "C" fn mock_release(_ctx: *mut c_void, ptr: *mut c_char) {
            if !ptr.is_null() {
                drop(CString::from_raw(ptr));
            }
        }
        let host = QueueHost {
            replies,
            index: std::cell::Cell::new(0),
        };
        let call = |payload: Value| -> Value {
            let req = CString::new(payload.to_string()).unwrap();
            let ptr = unsafe {
                zerocon_assist_request(
                    req.as_ptr(),
                    mock_complete,
                    mock_release,
                    (&host as *const QueueHost).cast_mut().cast(),
                )
            };
            unsafe {
                let text = CStr::from_ptr(ptr).to_str().unwrap().to_owned();
                zerocon_engine_free(ptr);
                serde_json::from_str(&text).unwrap()
            }
        };

        // 1. Refinement auto-apply.
        let refined = call(json!({
            "op": "correction",
            "input": "しゅうてんします",
            "baseline": "終点します",
            "originReading": "しゅてんします",
            "originRaw": "shutenshimasu",
            "appliedPrefix": "しゅうてん",
            "inputMode": "qwerty",
        }));
        assert_eq!(refined["text"], "しゅってんします");
        assert_eq!(refined["autoApply"], true);

        // 2. Clean observed auto-revert.
        let reverted = call(json!({
            "op": "correction",
            "input": "しゅうてんどうじ",
            "baseline": "終点童子",
            "originReading": "しゅてんどうじ",
            "originRaw": "shutendouji",
            "appliedPrefix": "しゅうてん",
            "inputMode": "qwerty",
        }));
        assert_eq!(reverted["revertToOrigin"], true);
        assert_eq!(reverted["autoApply"], true);

        // 3. Trailing 2nd typo preserves appliedPrefix.
        let second_typo = call(json!({
            "op": "correction",
            "input": "きょうはあしだ",
            "baseline": "今日はあしだ",
            "originReading": "きょおはあしだ",
            "originRaw": "kyoohaashida",
            "appliedPrefix": "きょうは",
            "inputMode": "qwerty",
        }));
        assert_eq!(second_typo["text"], "きょうはあした");
        assert_eq!(second_typo["autoApply"], true);
        assert_eq!(host.index.get(), 4);
    }
}
