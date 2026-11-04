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
    let reading = if surface.chars().all(|c| {
        ('ぁ'..='ゖ').contains(&c) || ('ァ'..='ヶ').contains(&c) || "ー、。！？!?・".contains(c)
    }) {
        surface.to_owned()
    } else {
        super::reading_check::reading_for_surface(surface)?
    };
    let source_reading = if input.chars().any(|c| ('一'..='鿿').contains(&c)) {
        super::reading_check::reading_for_surface(input)?
    } else {
        input.to_owned()
    };
    Some(super::damerau_levenshtein_distance(
        &katakana_to_hiragana(&source_reading),
        &katakana_to_hiragana(&reading),
    ))
}

fn supported_reading_change(input: &str, surface: &str) -> bool {
    // A single malformed romaji syllable can affect two kana. This is evidence
    // for a proposal only; automatic application still needs independent proof.
    input.chars().any(|c| {
        ('ぁ'..='ゖ').contains(&c) || ('ァ'..='ヶ').contains(&c) || ('一'..='鿿').contains(&c)
    }) && reading_change_distance(input, surface)
        .is_some_and(|distance| (1..=2).contains(&distance))
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
    let (prefix, target) = trailing_phrase(input, baseline)?;
    let mut result = suggest(&format!("{context}{prefix}"), target, config)?;
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
    let target = to_katakana(input);
    let prompt = if context.is_empty() {
        format!("\u{EE00}{target}\u{EE01}")
    } else {
        format!("\u{EE02}{context}\u{EE00}{target}\u{EE01}")
    };
    Some(
        json!({"prompt": prompt, "n_predict": (count * 3 + 16).clamp(32, 384),
        "temperature": 0.0,
        "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]}),
    )
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
    let surface = response.get("content")?.as_str()?.trim();
    (!surface.is_empty()
        && surface.chars().count() <= input.chars().count() * 3 + 16
        && !surface
            .chars()
            .any(|c| c.is_control() || ('\u{EE00}'..='\u{EE02}').contains(&c)))
    .then(|| surface.to_owned())
}

fn infer(context: &str, input: &str, config: &AiAssistConfig) -> Option<String> {
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

/// Exact, explicit repairs precede inference. Fuzzy vocabulary matches only supply
/// an offline fallback and never preempt a successfully completed model response.
pub fn suggest(context: &str, input: &str, config: &AiAssistConfig) -> Option<TypoSuggestion> {
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
    // Mixed kana/Latin input is absent from the current corrector training data.
    // A generic one-key reconstruction supplies a candidate, then the corrector
    // converts that candidate to its context-dependent final surface.
    if let Some(reading) = romaji_decoder::decode_floating_consonant_typo(input) {
        let expected = typo_variant::lookup_surface(&reading).unwrap_or_else(|| reading.clone());
        let surface = infer(&context, &reading, config)
            .filter(|s| *s == expected || *s == reading)
            .unwrap_or(expected);
        return Some(TypoSuggestion {
            surface: surface.clone(),
            token_reading: input.into(),
            token_surface: surface,
            learned: false,
        });
    }
    match infer(&context, input, config) {
        Some(surface) if katakana_to_hiragana(&surface) == katakana_to_hiragana(input) => None,
        Some(surface) => Some(TypoSuggestion {
            surface: surface.clone(),
            token_reading: input.into(),
            token_surface: surface,
            learned: false,
        }),
        None => heuristic_typo_suggestion(&context, input, config),
    }
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
    fn mock_inference(input: &str, reply: Value) -> (Option<TypoSuggestion>, Value) {
        use std::{
            io::{BufRead, BufReader},
            net::TcpListener,
        };
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        listener.set_nonblocking(true).unwrap();
        let worker = std::thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(2);
            let mut stream = loop {
                if let Ok((stream, _)) = listener.accept() {
                    break stream;
                }
                assert!(
                    Instant::now() < deadline,
                    "corrector request was never sent"
                );
                std::thread::sleep(Duration::from_millis(5));
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
            let response = reply.to_string();
            write!(
                stream,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                response.len(),
                response
            )
            .unwrap();
            serde_json::from_slice(&body).unwrap()
        });
        let config = AiAssistConfig {
            enabled: true,
            corrector_port: port,
            ..Default::default()
        };
        let result = suggest("確認をお願いします。", input, &config);
        (result, worker.join().unwrap())
    }
    #[test]
    fn completed_model_response_preempts_fuzzy_rules_and_unchanged_response_vetoes_them() {
        let (suggestion, sent) = mock_inference(
            "おせわになております",
            json!({"content":"お世話になっております", "stopped_eos":true}),
        );
        assert_eq!(suggestion.unwrap().surface, "お世話になっております");
        assert!(sent["prompt"]
            .as_str()
            .unwrap()
            .contains("オセワニナテオリマス"));
        let (suggestion, _) = mock_inference(
            "よろすく",
            json!({"content":"ヨロスク", "stopped_eos":true}),
        );
        assert!(
            suggestion.is_none(),
            "a fuzzy dictionary rule must not overrule the model's unchanged result"
        );
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
}
