//! Corrector inference and the evidence needed for reversible automatic correction.
//! This module runs on the assistance worker, never on a composition mutation.
use super::*;
use std::collections::VecDeque;

const MAX_INPUT: usize = 128;
type CacheKey = (String, u16, String, String, u64);
static CACHE: Mutex<VecDeque<(CacheKey, String)>> = Mutex::new(VecDeque::new());

pub fn clear_cache() {
    if let Ok(mut cache) = CACHE.lock() {
        cache.clear();
    }
}

/// Bounded diagnostic evidence for a proposed reading change. A surface with
/// no dictionary reading is not evidence of the user's intended keystrokes.
pub fn reading_change_distance(input: &str, surface: &str) -> Option<usize> {
    if input.chars().count() > MAX_INPUT || surface.chars().count() > MAX_INPUT {
        return None;
    }
    let source_reading = if input.chars().any(|c| ('一'..='鿿').contains(&c)) {
        super::reading_check::reading_for_surface(input)?
    } else {
        input.to_owned()
    };
    if surface.chars().all(|c| {
        ('ぁ'..='ゖ').contains(&c) || ('ァ'..='ヶ').contains(&c) || "ー、。！？!?・".contains(c)
    }) {
        Some(super::damerau_levenshtein_distance(
            &katakana_to_hiragana(&source_reading),
            &katakana_to_hiragana(surface),
        ))
    } else {
        super::reading_check::min_reading_distance(surface, &source_reading)
    }
}

fn supported_reading_change(input: &str, surface: &str) -> bool {
    // A single malformed romaji syllable or transposition can affect up to three kana.
    // This is evidence for a proposal only; automatic application still needs independent proof.
    input.chars().any(|c| {
        ('ぁ'..='ゖ').contains(&c) || ('ァ'..='ヶ').contains(&c) || ('一'..='鿿').contains(&c)
    }) && reading_change_distance(input, surface)
        .is_some_and(|distance| (1..=3).contains(&distance))
}

pub(super) fn explicit_typo_surface(input: &str) -> Option<&'static str> {
    let reading = katakana_to_hiragana(input);
    super::TYPO_TABLE.iter().find_map(|&(typo, surface)| {
        (katakana_to_hiragana(typo) == reading
            && katakana_to_hiragana(surface) != reading
            && !super::reading_check::has_same_reading(surface, input))
        .then_some(surface)
    })
}

fn known_reading(input: &str) -> bool {
    let hira = katakana_to_hiragana(input);
    STANDARD_VOCAB.iter().any(|(reading, _)| *reading == hira)
        || super::typo_variant::lookup_surface(&hira).is_some()
}

fn trailing_phrase<'a>(input: &'a str, baseline: &'a str) -> Option<(&'a str, &'a str)> {
    if input.chars().count() <= MAX_INPUT {
        return Some(("", input));
    }
    // For long compositions, align an actual punctuation boundary in the normal
    // conversion. Its already-converted prefix must not be reconstructed as kana.
    let boundary = |c: char| matches!(c, '。' | '、' | '！' | '？' | '!' | '?' | '\n');
    let (input_pos, separator) = input.char_indices().rfind(|(_, c)| boundary(*c))?;
    let (surface_pos, surface_separator) = baseline.char_indices().rfind(|(_, c)| boundary(*c))?;
    if separator != surface_separator
        || input.chars().filter(|c| boundary(*c)).count()
            != baseline.chars().filter(|c| boundary(*c)).count()
    {
        return None;
    }
    let target = &input[input_pos + separator.len_utf8()..];
    (target.chars().count() <= MAX_INPUT).then_some((
        &baseline[..surface_pos + surface_separator.len_utf8()],
        target,
    ))
}

pub fn suggest_composition(
    context: &str,
    input: &str,
    baseline: &str,
    config: &AiAssistConfig,
) -> Option<TypoSuggestion> {
    suggest_composition_with_channel(context, input, baseline, config, true)
}

/// Fallback used after `keyboard_hybrid::suggest` already ran channel repair and
/// returned `Some(None)`: evaluates learned/dictionary/floating-consonant and
/// deterministic heuristics without running `keyboard_hybrid::repair_readings` twice.
pub fn suggest_composition_heuristic(
    context: &str,
    input: &str,
    baseline: &str,
    config: &AiAssistConfig,
) -> Option<TypoSuggestion> {
    suggest_composition_with_channel(context, input, baseline, config, false)
}

fn suggest_composition_with_channel(
    context: &str,
    input: &str,
    baseline: &str,
    config: &AiAssistConfig,
    allow_channel_repair: bool,
) -> Option<TypoSuggestion> {
    let (prefix, target) = trailing_phrase(input, baseline)?;
    let mut result = suggest_with_channel(
        &format!("{context}{prefix}"),
        target,
        config,
        allow_channel_repair,
    )?;
    if super::reading_check::has_same_reading(&result.surface, target) {
        return None;
    }
    if !result.learned
        && explicit_typo_surface(target) != Some(result.surface.as_str())
        && !supported_reading_change(target, &result.surface)
    {
        return None;
    }
    result.surface.insert_str(0, prefix);
    Some(result)
}

pub fn can_auto_apply_composition(input: &str, baseline: &str, surface: &str) -> bool {
    trailing_phrase(input, baseline).is_some_and(|(prefix, target)| {
        surface
            .strip_prefix(prefix)
            .is_some_and(|s| can_auto_apply(target, s))
    })
}

fn normalize_ascii_digit(ch: char) -> Option<char> {
    match ch {
        '0'..='9' => Some(ch),
        '０'..='９' => char::from_u32(ch as u32 - 0xFEE0),
        _ => None,
    }
}

/// Replaces contiguous ASCII/fullwidth digit runs with `"nam"` and returns ASCII digit runs.
pub fn mask_digits_with_nam(text: &str) -> (String, Vec<String>) {
    let mut masked = String::with_capacity(text.len());
    let mut digits = Vec::new();
    let mut current = String::new();
    for ch in text.chars() {
        if let Some(ascii) = normalize_ascii_digit(ch) {
            current.push(ascii);
        } else {
            if !current.is_empty() {
                digits.push(std::mem::take(&mut current));
                masked.push_str("nam");
            }
            masked.push(ch);
        }
    }
    if !current.is_empty() {
        digits.push(current);
        masked.push_str("nam");
    }
    (masked, digits)
}

/// Restores `"nam"` placeholders left-to-right, preserving lowercase English words such as `"name"`.
pub fn restore_nam_digits(surface: &str, digits: &[String]) -> String {
    if digits.is_empty() || !surface.contains("nam") {
        return surface.to_string();
    }
    let replace_pass = |input: &str, start_idx: usize, fallback: bool| -> (String, usize) {
        let mut out = String::with_capacity(input.len());
        let mut rest = input;
        let mut digit_idx = start_idx;
        while digit_idx < digits.len() {
            let Some(pos) = rest.find("nam") else {
                break;
            };
            out.push_str(&rest[..pos]);
            let after = &rest[pos + 3..];
            let keep_literal = if !fallback {
                matches!(after.chars().next(), Some('a'..='w' | 'y'..='z'))
            } else {
                let prev_is_lower = out
                    .chars()
                    .next_back()
                    .is_some_and(|c| c.is_ascii_lowercase());
                let is_english_stem = after.starts_with('e')
                    || (after.starts_with("ing")
                        && after
                            .as_bytes()
                            .get(3)
                            .is_none_or(|b| !b.is_ascii_lowercase()));
                prev_is_lower || is_english_stem
            };
            if keep_literal {
                out.push_str("nam");
            } else {
                out.push_str(&digits[digit_idx]);
                digit_idx += 1;
            }
            rest = after;
        }
        out.push_str(rest);
        (out, digit_idx)
    };

    let (first_pass, consumed) = replace_pass(surface, 0, false);
    if consumed < digits.len() && first_pass.contains("nam") {
        return replace_pass(&first_pass, consumed, true).0;
    }
    first_pass
}

pub fn payload(context: &str, input: &str) -> Option<Value> {
    let count = input.chars().count();
    if !(2..=MAX_INPUT).contains(&count)
        || input
            .chars()
            .any(|c| c.is_control() || ('\u{EE00}'..='\u{EE02}').contains(&c))
    {
        return None;
    }
    // Bound left context independently. Never cut the composing input at an arbitrary character.
    let context: String = context
        .chars()
        .filter(|c| !('\u{EE00}'..='\u{EE02}').contains(c))
        .map(|c| if c == ' ' { '\u{3000}' } else { c })
        .rev()
        .take(512)
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    let (masked_context, _) = mask_digits_with_nam(&context);
    let target = to_katakana(input);
    let (masked_target, _) = mask_digits_with_nam(&target);
    let prompt = if masked_context.is_empty() {
        format!("\u{EE00}{masked_target}\u{EE01}")
    } else {
        format!("\u{EE02}{masked_context}\u{EE00}{masked_target}\u{EE01}")
    };
    Some(json!({
        "prompt": prompt,
        "n_predict": (count * 3 + 16).clamp(32, 384),
        "temperature": 0.0,
        "cache_prompt": true,
        "id_slot": 0,
        "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]
    }))
}

pub fn completed_surface(response: &Value, input: &str, budget: u64) -> Option<String> {
    if response.get("truncated").and_then(Value::as_bool) == Some(true)
        || response.get("stopped_limit").and_then(Value::as_bool) == Some(true)
        || response.get("stop_type").and_then(Value::as_str) == Some("limit")
        || (response
            .get("tokens_predicted")
            .and_then(Value::as_u64)
            .is_some_and(|n| n >= budget)
            && response.get("stopped_eos").and_then(Value::as_bool) != Some(true))
    {
        return None;
    }
    let raw_surface = response.get("content")?.as_str()?.trim();
    let (_, input_digits) = mask_digits_with_nam(input);
    let surface = restore_nam_digits(raw_surface, &input_digits);
    (!surface.is_empty()
        && surface.chars().count() <= input.chars().count() * 3 + 16
        && !surface
            .chars()
            .any(|c| c.is_control() || ('\u{EE00}'..='\u{EE02}').contains(&c)))
    .then_some(surface)
}

fn infer(context: &str, input: &str, config: &AiAssistConfig) -> Option<String> {
    if !config.enabled || config.corrector_port < 1024 {
        return None;
    }
    let payload = payload(context, input)?;
    let key = (
        config.engine.clone(),
        config.corrector_port,
        context.into(),
        input.into(),
        runtime::backend_revision(),
    );
    if let Some(surface) = CACHE
        .lock()
        .ok()?
        .iter()
        .find(|(k, _)| k == &key)
        .map(|(_, s)| s.clone())
    {
        return Some(surface);
    }
    let response = simple_http_post(config.corrector_port, "/completion", &payload, 450)?;
    let surface = completed_surface(&response, input, payload["n_predict"].as_u64()?)?;
    if let Ok(mut cache) = CACHE.lock() {
        cache.push_front((key, surface.clone()));
        cache.truncate(32);
    }
    Some(surface)
}

/// Candidate readings come from dictionary/channel repair, then the main conversion
/// model (or lookup fallback) produces the final surface. Raw unrepaired input is
/// never sent to a separate seq2seq corrector model.
pub fn suggest(context: &str, input: &str, config: &AiAssistConfig) -> Option<TypoSuggestion> {
    suggest_with_channel(context, input, config, true)
}

fn suggest_with_channel(
    context: &str,
    input: &str,
    config: &AiAssistConfig,
    allow_channel_repair: bool,
) -> Option<TypoSuggestion> {
    let context = context.replace(' ', "\u{3000}");
    let input = input.trim();
    if !config.enabled || !(2..=MAX_INPUT).contains(&input.chars().count()) {
        return None;
    }
    if let Some(matched) = lookup_learned_typo(input).filter(|m| m.reading == input) {
        return Some(TypoSuggestion {
            surface: matched.surface.clone(),
            token_reading: input.into(),
            token_surface: matched.surface,
            learned: true,
        });
    }
    if known_reading(input) {
        return None;
    }
    if input
        .chars()
        .last()
        .is_some_and(|c| c.is_ascii_alphabetic() && !"aeiou".contains(c.to_ascii_lowercase()))
    {
        return None;
    }
    if let Some(surface) = explicit_typo_surface(input) {
        return Some(TypoSuggestion {
            surface: surface.into(),
            token_reading: input.into(),
            token_surface: surface.into(),
            learned: false,
        });
    }
    // Mixed kana/Latin input is repaired by deterministic one-key reconstruction,
    // then converted to its context-dependent surface by the main model.
    if let Some(reading) = romaji_decoder::decode_floating_consonant_typo(input) {
        let expected = typo_variant::lookup_surface(&reading).unwrap_or_else(|| reading.clone());
        let surface = infer(&context, &reading, config)
            .filter(|s| {
                *s == expected || *s == reading || reading_check::has_same_reading(s, &reading)
            })
            .unwrap_or(expected);
        return Some(TypoSuggestion {
            surface: surface.clone(),
            token_reading: input.into(),
            token_surface: surface,
            learned: false,
        });
    }
    if allow_channel_repair
        && config.engine == "embedded"
        && config.corrector_port >= 1024
        && runtime::allow_native_worker()
    {
        let wait_for_worker = runtime::wait_for_native_worker();
        let repair_input = if input.chars().any(|c| ('一'..='鿿').contains(&c)) {
            super::reading_check::reading_for_surface(input)
                .map(|r| katakana_to_hiragana(&r))
                .unwrap_or_else(|| input.to_owned())
        } else {
            input.to_owned()
        };
        if let Some(readings) =
            super::keyboard_hybrid::repair_readings(&context, &repair_input, "", wait_for_worker)
        {
            if readings.is_empty() {
                return None;
            }
            let mut saw_invalid_surface = false;
            for reading in &readings {
                match infer(&context, reading, config) {
                    Some(s) if reading_check::has_same_reading(&s, reading) => {
                        return (katakana_to_hiragana(&s) != katakana_to_hiragana(input))
                            .then_some(TypoSuggestion {
                                surface: s.clone(),
                                token_reading: input.into(),
                                token_surface: s,
                                learned: false,
                            });
                    }
                    Some(_) => {
                        saw_invalid_surface = true;
                    }
                    None => {}
                }
            }
            if saw_invalid_surface {
                return None;
            }
            let reading = &readings[0];
            let surface = typo_variant::lookup_surface(reading).unwrap_or_else(|| reading.clone());
            return (katakana_to_hiragana(&surface) != katakana_to_hiragana(input)).then_some(
                TypoSuggestion {
                    surface: surface.clone(),
                    token_reading: input.into(),
                    token_surface: surface,
                    learned: false,
                },
            );
        }
    }
    heuristic_typo_suggestion(&context, input, config)
}

/// Automatic correction requires independent, narrow evidence. Model probability
/// or repeated identical generations alone cannot certify the user's intention.
pub fn can_auto_apply(input: &str, surface: &str) -> bool {
    if let Some(m) = lookup_learned_typo(input) {
        if m.reading == input && m.surface == surface && m.count >= 3 {
            return true;
        }
    }
    if known_reading(input) {
        return false;
    }
    // Require a unique adjacent-key reconstruction, not the first dictionary hit.
    let Some(reading) = romaji_decoder::unique_adjacent_correction(input) else {
        return false;
    };
    surface == reading || typo_variant::lookup_surface(&reading).is_some_and(|s| s == surface)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn native_unresolved_terminal_n_waits_without_calling_a_model() {
        struct NoInference;
        impl runtime::CompletionBackend for NoInference {
            fn read_learning(&self) -> bool {
                false
            }
            fn complete(&self, _: u16, _: &str, _: &Value, _: u64) -> Option<Value> {
                panic!("an unresolved terminal n must wait for the next key");
            }
        }
        let config = AiAssistConfig {
            enabled: true,
            corrector_port: 0,
            ..Default::default()
        };
        runtime::with_backend(Box::new(NoInference), || {
            for input in ["あしたn", "よろすくn", "しりょうn"] {
                assert!(suggest_composition("", input, input, &config).is_none());
            }
        });
    }
    #[test]
    fn exact_repairs_are_available_without_inference_but_partial_keys_are_not() {
        let config = AiAssistConfig {
            enabled: true,
            corrector_port: 1,
            ..Default::default()
        };
        for (input, expected) in [
            ("きんにちは", "こんにちは"),
            ("くらおど", "クラウド"),
            ("おひょ", "おはよう"),
            ("てそと", "テスト"),
        ] {
            let proposal = suggest_composition("", input, input, &config).unwrap();
            assert_eq!(proposal.surface, expected);
            assert!(!proposal.learned);
            assert!(!can_auto_apply(input, expected));
        }
        assert!(suggest("", "きっp", &config).is_none());
        assert!(suggest("", "おkせr", &config).is_none());
        assert!(suggest("", "おかえり", &config).is_none());
    }
    #[test]
    fn model_proposals_need_a_local_reading_change_without_shortening_the_sentence() {
        assert!(supported_reading_change("おkせり", "おかえり"));
        assert!(supported_reading_change(
            "ありがとうございまし",
            "ありがとうございます"
        ));
        assert!(!supported_reading_change(
            "おめでとう！よかったね",
            "おめでとう"
        ));
        assert!(!supported_reading_change(
            "おしらせします",
            "おしらせします"
        ));
        assert!(!supported_reading_change("sushi", "すし"));
        assert!(!supported_reading_change("ai", "あい"));
        assert!(reading_change_distance(&"あ".repeat(129), "あ").is_none());
    }
    #[test]
    fn unified_correction_converts_repaired_readings_via_main_model_and_never_sends_raw_input() {
        use std::sync::{
            atomic::{AtomicUsize, Ordering},
            Arc, Mutex,
        };

        struct MockUnifiedBackend {
            repair: Option<Value>,
            reply: Value,
            calls: Arc<Mutex<Vec<(u16, String)>>>,
        }
        impl runtime::CompletionBackend for MockUnifiedBackend {
            fn read_learning(&self) -> bool {
                false
            }
            fn allow_native_worker(&self) -> bool {
                true
            }
            fn wait_for_native_worker(&self) -> bool {
                true
            }
            fn complete(
                &self,
                port: u16,
                _path: &str,
                body: &Value,
                _timeout_ms: u64,
            ) -> Option<Value> {
                self.calls
                    .lock()
                    .unwrap()
                    .push((port, body["prompt"].as_str().unwrap_or_default().to_owned()));
                Some(self.reply.clone())
            }
            fn keyboard_repair(
                &self,
                _raw: &str,
                _input: &str,
                _context: &str,
                _timeout_ms: u64,
            ) -> Option<Option<Value>> {
                Some(self.repair.clone())
            }
        }

        let config = AiAssistConfig {
            enabled: true,
            corrector_port: runtime::PREDICTION_SERVER_PORT,
            ..Default::default()
        };

        // 1. Repaired reading from keyboard channel is converted using main conversion model.
        clear_cache();
        let sent_calls = Arc::new(Mutex::new(Vec::new()));
        let backend = MockUnifiedBackend {
            repair: Some(json!({
                "observed": "おせわになております",
                "reading": "おせわになっております",
                "gain": 45.0
            })),
            reply: json!({"content": "お世話になっております", "stopped_eos": true}),
            calls: sent_calls.clone(),
        };
        runtime::with_backend(Box::new(backend), || {
            let suggestion =
                suggest("確認をお願いします。", "おせわになております", &config).unwrap();
            assert_eq!(suggestion.surface, "お世話になっております");
            assert_eq!(suggestion.token_reading, "おせわになております");
        });
        {
            let recorded = sent_calls.lock().unwrap();
            assert_eq!(recorded.len(), 1);
            assert_eq!(recorded[0].0, runtime::PREDICTION_SERVER_PORT);
            assert!(recorded[0].1.contains("オセワニナッテオリマス"));
            assert!(!recorded[0].1.contains("オセワニナテオリマス"));
        }

        // 2. When main model returns a surface with a mismatched reading, channel repair rejects it.
        clear_cache();
        let bad_surface_calls = Arc::new(Mutex::new(Vec::new()));
        let bad_surface_backend = MockUnifiedBackend {
            repair: Some(json!({
                "observed": "おせわになております",
                "reading": "おせわになっております",
                "gain": 45.0
            })),
            reply: json!({"content": "全く違う変換結果", "stopped_eos": true}),
            calls: bad_surface_calls.clone(),
        };
        runtime::with_backend(Box::new(bad_surface_backend), || {
            assert!(suggest("確認をお願いします。", "おせわになております", &config).is_none());
        });
        assert_eq!(bad_surface_calls.lock().unwrap().len(), 1);

        // 3. Unrepaired input is never sent to /completion when no repair candidate exists.
        clear_cache();
        struct NoRawInferBackend {
            calls: Arc<Mutex<Vec<String>>>,
            repair_calls: Arc<AtomicUsize>,
        }
        impl runtime::CompletionBackend for NoRawInferBackend {
            fn read_learning(&self) -> bool {
                false
            }
            fn allow_native_worker(&self) -> bool {
                true
            }
            fn wait_for_native_worker(&self) -> bool {
                true
            }
            fn complete(
                &self,
                _port: u16,
                _path: &str,
                body: &Value,
                _timeout_ms: u64,
            ) -> Option<Value> {
                self.calls
                    .lock()
                    .unwrap()
                    .push(body["prompt"].as_str().unwrap_or_default().to_owned());
                Some(json!({"content": "宜しく", "stopped_eos": true}))
            }
            fn keyboard_repair(
                &self,
                _raw: &str,
                input: &str,
                _context: &str,
                _timeout_ms: u64,
            ) -> Option<Option<Value>> {
                self.repair_calls.fetch_add(1, Ordering::SeqCst);
                Some(Some(json!({"observed": input})))
            }
        }
        let calls = Arc::new(Mutex::new(Vec::new()));
        let repair_calls = Arc::new(AtomicUsize::new(0));
        let guard_backend = NoRawInferBackend {
            calls: calls.clone(),
            repair_calls: repair_calls.clone(),
        };
        runtime::with_backend(Box::new(guard_backend), || {
            assert!(suggest("", "よろすく", &config).is_none());
            assert!(calls.lock().unwrap().is_empty());
            assert_eq!(repair_calls.load(Ordering::SeqCst), 1);

            // Heuristic-only entrypoint skips channel repair while preserving deterministic heuristics.
            let heuristic = suggest_composition_heuristic("", "ありあとう", "ありあとう", &config);
            assert_eq!(heuristic.unwrap().surface, "ありがとう");
            assert_eq!(repair_calls.load(Ordering::SeqCst), 1);
            assert!(calls.lock().unwrap().is_empty());
        });
    }
    #[test]
    fn corrector_keeps_whole_input_and_has_room_to_finish() {
        let input = "おせわになております";
        let p = payload("昨日のご相談について。", input).unwrap();
        assert!(p["prompt"]
            .as_str()
            .unwrap()
            .ends_with(&format!("\u{EE00}{}\u{EE01}", to_katakana(input))));
        assert!(p["n_predict"].as_u64().unwrap() >= 32);
        assert_eq!(p["cache_prompt"], json!(true));
        assert_eq!(p["id_slot"], json!(0));
        assert!(payload("", &"あ".repeat(129)).is_none());
    }
    #[test]
    fn long_input_uses_punctuation_and_preserves_converted_prefix() {
        let input = format!("{}。おkせり", "あ".repeat(128));
        let baseline = format!("{}。おkせり", "亜".repeat(128));
        let (prefix, target) = trailing_phrase(&input, &baseline).unwrap();
        assert_eq!(target, "おkせり");
        assert_eq!(prefix, format!("{}。", "亜".repeat(128)));
        assert!(trailing_phrase(&"あ".repeat(150), "亜").is_none());
        assert!(trailing_phrase(&input, "句読点なし").is_none());
    }
    #[test]
    fn corrector_rejects_exhausted_and_malformed_output() {
        for field in [
            json!({"stopped_limit":true}),
            json!({"truncated":true}),
            json!({"stop_type":"limit"}),
            json!({"tokens_predicted":32}),
        ] {
            let mut r = field;
            r["content"] = json!("お世話になってお");
            assert!(completed_surface(&r, "おせわになております", 32).is_none());
        }
        assert_eq!(
            completed_surface(
                &json!({"content":"お世話になっております","stopped_eos":true}),
                "おせわになております",
                32
            )
            .as_deref(),
            Some("お世話になっております")
        );
        assert!(completed_surface(&json!({"content":"a\n別の文"}), "てすと", 32).is_none());
    }
    #[test]
    fn corrector_does_not_rewrite_known_clean_input() {
        let config = AiAssistConfig {
            enabled: true,
            corrector_port: 1,
            ..Default::default()
        };
        assert!(suggest("", "おかえり", &config).is_none());
        assert!(!can_auto_apply("おかえり", "お帰る"));
        assert!(!can_auto_apply("あした", "明後日"));
        assert!(can_auto_apply("おkせり", "おかえり"));
        assert!(!can_auto_apply("おkせr", "おかえり"));
        assert!(!can_auto_apply("okseri", "おかえり"));
    }
    #[test]
    fn nam_masks_and_restores_digits_without_corrupting_english_words() {
        let (masked_candy, digits_candy) = mask_digits_with_nam("１００えんのあめ");
        assert_eq!(masked_candy, "namえんのあめ");
        assert_eq!(digits_candy, vec!["100"]);
        assert_eq!(restore_nam_digits("nam円の飴", &digits_candy), "100円の飴");

        let (masked_date, digits_date) = mask_digits_with_nam("１がつ１にち");
        assert_eq!(masked_date, "namがつnamにち");
        assert_eq!(digits_date, vec!["1", "1"]);
        assert_eq!(restore_nam_digits("nam月nam日", &digits_date), "1月1日");

        let (masked_tech, digits_tech) = mask_digits_with_nam("Wi-Fi 6EとBluetooth 5.4");
        assert_eq!(masked_tech, "Wi-Fi namEとBluetooth nam.nam");
        assert_eq!(digits_tech, vec!["6", "5", "4"]);
        assert_eq!(
            restore_nam_digits("Wi-Fi namEとBluetooth nam.nam", &digits_tech),
            "Wi-Fi 6EとBluetooth 5.4"
        );

        let (masked_sql, digits_sql) = mask_digits_with_nam("SELECT name FROM customers");
        assert_eq!(masked_sql, "SELECT name FROM customers");
        assert!(digits_sql.is_empty());
        assert_eq!(
            restore_nam_digits("SELECT name FROM customers", &[String::from("10")]),
            "SELECT name FROM customers"
        );
        assert_eq!(
            restore_nam_digits("SELECT nameです dynamic", &[String::from("10")]),
            "SELECT nameです dynamic"
        );
        assert_eq!(
            restore_nam_digits("nambonまとめて持つ", &[String::from("3")]),
            "3bonまとめて持つ"
        );
    }
}
