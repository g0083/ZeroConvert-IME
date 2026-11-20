//! Corrector inference and the evidence needed for reversible automatic correction.
//! This module runs on the assistance worker, never on a composition mutation.
use super::*;
use shared::assist_policy::AutoTypoCorrectionStrength;
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

fn has_pending_romaji_tail(input: &str) -> bool {
    input
        .chars()
        .last()
        .is_some_and(|c| c.is_ascii_alphabetic() && !"aeiou".contains(c.to_ascii_lowercase()))
}

fn has_floating_ascii(input: &str) -> bool {
    input
        .chars()
        .any(|c| c.is_ascii_alphabetic() || ('ａ'..='ｚ').contains(&c) || ('Ａ'..='Ｚ').contains(&c))
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AutoRefinementContext<'a> {
    pub origin_reading: &'a str,
    pub origin_raw: &'a str,
    pub origin_touch_hints: &'a str,
    pub applied_prefix: &'a str,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CorrectionOutcome {
    None,
    RevertToOrigin,
    Suggestion {
        suggestion: TypoSuggestion,
        auto_apply: bool,
        channel_backed: bool,
    },
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
    suggest_composition_with_input_hints(context, input, baseline, "", "", "", config)
}

/// Like [`suggest_composition`], forwarding raw ASCII romaji, per-position touch hints,
/// and keyboard input mode (`""` / `"qwerty"` / `"flick"`). Raw/touch hints apply only
/// when the whole composition is repaired without splitting a protected prefix.
pub fn suggest_composition_with_input_hints(
    context: &str,
    input: &str,
    baseline: &str,
    raw: &str,
    touch_hints: &str,
    input_mode: &str,
    config: &AiAssistConfig,
) -> Option<TypoSuggestion> {
    suggest_composition_with_channel(
        context,
        input,
        baseline,
        raw,
        touch_hints,
        input_mode,
        config,
        true,
    )
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
    suggest_composition_with_channel(context, input, baseline, "", "", "", config, false)
}

fn suggest_composition_with_channel(
    context: &str,
    input: &str,
    baseline: &str,
    raw: &str,
    touch_hints: &str,
    input_mode: &str,
    config: &AiAssistConfig,
    allow_channel_repair: bool,
) -> Option<TypoSuggestion> {
    let (prefix, target) = trailing_phrase(input, baseline)?;
    let (scoped_raw, scoped_touch) = if prefix.is_empty() {
        (raw, touch_hints)
    } else {
        ("", "")
    };
    let mut result = suggest_with_channel_and_hints(
        &format!("{context}{prefix}"),
        target,
        scoped_raw,
        scoped_touch,
        input_mode,
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

pub fn can_auto_apply_composition_suggestion(
    input: &str,
    baseline: &str,
    suggestion: &TypoSuggestion,
) -> bool {
    can_auto_apply_composition_suggestion_with_strength(
        input,
        baseline,
        suggestion,
        AutoTypoCorrectionStrength::Standard,
    )
}

pub fn can_auto_apply_composition_suggestion_with_strength(
    input: &str,
    baseline: &str,
    suggestion: &TypoSuggestion,
    strength: AutoTypoCorrectionStrength,
) -> bool {
    if !strength.is_enabled() {
        return false;
    }
    trailing_phrase(input, baseline).is_some_and(|(prefix, target)| {
        suggestion
            .surface
            .strip_prefix(prefix)
            .is_some_and(|s| can_auto_apply_inner(target, s, suggestion.channel_confidence, strength))
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

#[allow(dead_code)]
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
    suggest_with_channel_and_hints(context, input, "", "", "", config, true)
}

fn suggest_with_channel_and_hints(
    context: &str,
    input: &str,
    raw: &str,
    touch_hints: &str,
    input_mode: &str,
    config: &AiAssistConfig,
    allow_channel_repair: bool,
) -> Option<TypoSuggestion> {
    let context = context.replace(' ', "\u{3000}");
    let input = input.trim();
    if !config.enabled || !(2..=MAX_INPUT).contains(&input.chars().count()) {
        return None;
    }
    if super::reading_check::user_dictionary_contains_reading(input) {
        return None;
    }
    if let Some(matched) = lookup_learned_typo(input).filter(|m| m.reading == input) {
        return Some(TypoSuggestion {
            surface: matched.surface.clone(),
            token_reading: input.into(),
            token_surface: matched.surface,
            learned: true,
            channel_confidence: None,
        });
    }
    if known_reading(input) {
        return None;
    }
    if has_pending_romaji_tail(input) {
        return None;
    }
    if let Some(surface) = explicit_typo_surface(input) {
        return Some(TypoSuggestion {
            surface: surface.into(),
            token_reading: input.into(),
            token_surface: surface.into(),
            learned: false,
            channel_confidence: None,
        });
    }
    // Mixed kana/Latin input is repaired by deterministic one-key reconstruction
    // to its hiragana reading; the main converter converts it when applied.
    if let Some(reading) = romaji_decoder::decode_floating_consonant_typo(input) {
        return Some(TypoSuggestion {
            surface: reading.clone(),
            token_reading: input.into(),
            token_surface: reading,
            learned: false,
            channel_confidence: None,
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
        if let Some(candidate_opt) = super::keyboard_hybrid::repair_candidate_with_hints(
            &context,
            &repair_input,
            raw,
            touch_hints,
            input_mode,
            wait_for_worker,
        ) {
            let candidate = candidate_opt?;
            return (katakana_to_hiragana(&candidate.reading) != katakana_to_hiragana(input))
                .then_some(TypoSuggestion {
                    surface: candidate.reading.clone(),
                    token_reading: input.into(),
                    token_surface: candidate.reading,
                    learned: false,
                    channel_confidence: Some(candidate.confidence),
                });
        }
    }
    heuristic_typo_suggestion(&context, input, config)
}

/// Evaluates whether an active automatic reading-only correction should be
/// refined, silently reverted to unaltered `origin_reading`, confirmed (`None`),
/// or extended with a trailing 2nd typo as subsequent keystrokes add context.
#[allow(clippy::too_many_arguments)]
pub fn suggest_auto_refinement(
    context: &str,
    current_reading: &str,
    baseline: &str,
    current_raw: &str,
    current_touch_hints: &str,
    input_mode: &str,
    refinement: &AutoRefinementContext<'_>,
    strength: AutoTypoCorrectionStrength,
    config: &AiAssistConfig,
) -> CorrectionOutcome {
    if !config.enabled {
        return CorrectionOutcome::None;
    }
    let eval_current_trailing = |allow_channel: bool| -> CorrectionOutcome {
        let Some(suggestion) = suggest_composition_with_channel(
            context,
            current_reading,
            baseline,
            current_raw,
            current_touch_hints,
            input_mode,
            config,
            allow_channel,
        )
        .filter(|s| s.surface != current_reading && s.surface != baseline) else {
            return CorrectionOutcome::None;
        };
        let channel_backed = suggestion.channel_confidence.is_some();
        let auto_apply = can_auto_apply_composition_suggestion_with_strength(
            current_reading,
            baseline,
            &suggestion,
            strength,
        );
        CorrectionOutcome::Suggestion {
            suggestion,
            auto_apply,
            channel_backed,
        }
    };
    let Some(trailing) = current_reading
        .strip_prefix(refinement.applied_prefix)
        .filter(|t| !t.is_empty())
    else {
        return eval_current_trailing(true);
    };
    let origin = refinement.origin_reading.trim();
    let Some(origin_prefix) = origin
        .strip_suffix(trailing)
        .filter(|p| !p.is_empty() && *p != refinement.applied_prefix)
    else {
        return eval_current_trailing(true);
    };
    if has_pending_romaji_tail(current_reading) || has_pending_romaji_tail(origin) {
        return CorrectionOutcome::None;
    }
    if !(2..=MAX_INPUT).contains(&origin.chars().count())
        || !(2..=MAX_INPUT).contains(&current_reading.chars().count())
    {
        return eval_current_trailing(true);
    }

    let context_norm = context.replace(' ', "\u{3000}");
    let curr_hira = katakana_to_hiragana(current_reading);
    let orig_prefix_hira = katakana_to_hiragana(origin_prefix);

    // 1. Learned typos & clean dictionaries on unaltered origin.
    if let Some(matched) = lookup_learned_typo(origin).filter(|m| m.reading == origin) {
        let matched_hira = katakana_to_hiragana(&matched.surface);
        if matched_hira == curr_hira
            || matched.surface == baseline
            || matched_hira.starts_with(&orig_prefix_hira)
        {
            return eval_current_trailing(false);
        }
        if !super::reading_check::has_same_reading(&matched.surface, current_reading) {
            let suggestion = TypoSuggestion {
                surface: matched.surface.clone(),
                token_reading: origin.into(),
                token_surface: matched.surface,
                learned: true,
                channel_confidence: None,
            };
            let auto_apply = can_auto_apply_composition_suggestion_with_strength(
                origin,
                baseline,
                &suggestion,
                strength,
            );
            return CorrectionOutcome::Suggestion {
                suggestion,
                auto_apply,
                channel_backed: false,
            };
        }
        return CorrectionOutcome::None;
    }
    if super::reading_check::user_dictionary_contains_reading(origin) || known_reading(origin) {
        return if strength.is_enabled() {
            CorrectionOutcome::RevertToOrigin
        } else {
            CorrectionOutcome::None
        };
    }

    // 2. Deterministic explicit table / floating-consonant repair on unaltered origin.
    let deterministic_candidate = explicit_typo_surface(origin)
        .map(|s| (s.to_owned(), true))
        .or_else(|| romaji_decoder::decode_floating_consonant_typo(origin).map(|s| (s, false)));
    if let Some((cand, is_explicit_table)) = deterministic_candidate {
        let cand_hira = katakana_to_hiragana(&cand);
        if cand_hira == curr_hira || cand == baseline || cand_hira.starts_with(&orig_prefix_hira)
        {
            return eval_current_trailing(true);
        }
        if !super::reading_check::has_same_reading(&cand, current_reading)
            && (is_explicit_table || supported_reading_change(origin, &cand))
        {
            let suggestion = TypoSuggestion {
                surface: cand.clone(),
                token_reading: origin.into(),
                token_surface: cand,
                learned: false,
                channel_confidence: None,
            };
            let auto_apply = can_auto_apply_composition_suggestion_with_strength(
                origin,
                baseline,
                &suggestion,
                strength,
            );
            return CorrectionOutcome::Suggestion {
                suggestion,
                auto_apply,
                channel_backed: false,
            };
        }
        return CorrectionOutcome::None;
    }

    // 3. Single native channel evaluation on unaltered origin + raw/touch evidence.
    if config.engine == "embedded"
        && config.corrector_port >= 1024
        && runtime::allow_native_worker()
    {
        let wait_for_worker = runtime::wait_for_native_worker();
        match super::keyboard_hybrid::repair_candidate_with_hints(
            &context_norm,
            origin,
            refinement.origin_raw,
            refinement.origin_touch_hints,
            input_mode,
            wait_for_worker,
        ) {
            None => return CorrectionOutcome::None,
            Some(None) => {
                if strength.is_enabled()
                    && !has_floating_ascii(origin)
                    && origin.chars().count() <= 24
                    && trailing.chars().count() >= 2
                    && explicit_typo_surface(origin).is_none()
                {
                    return CorrectionOutcome::RevertToOrigin;
                }
                return CorrectionOutcome::None;
            }
            Some(Some(candidate)) => {
                let cand_hira = katakana_to_hiragana(&candidate.reading);
                if cand_hira == curr_hira {
                    let trailing_hint = has_floating_ascii(trailing)
                        || candidate.alt_reading.as_deref().is_some_and(|alt| {
                            katakana_to_hiragana(alt).starts_with(&orig_prefix_hira)
                        });
                    return eval_current_trailing(trailing_hint);
                }
                if let Some(trailing_cand) = cand_hira.strip_prefix(&orig_prefix_hira) {
                    let trailing_res = eval_current_trailing(true);
                    if trailing_res != CorrectionOutcome::None {
                        return trailing_res;
                    }
                    let recombined = format!("{}{trailing_cand}", refinement.applied_prefix);
                    if !trailing_cand.is_empty()
                        && recombined != curr_hira
                        && recombined != baseline
                        && !super::reading_check::has_same_reading(&recombined, current_reading)
                        && supported_reading_change(current_reading, &recombined)
                    {
                        let suggestion = TypoSuggestion {
                            surface: recombined.clone(),
                            token_reading: current_reading.into(),
                            token_surface: recombined,
                            learned: false,
                            channel_confidence: Some(candidate.confidence),
                        };
                        return CorrectionOutcome::Suggestion {
                            suggestion,
                            auto_apply: false,
                            channel_backed: true,
                        };
                    }
                    return CorrectionOutcome::None;
                }
                if candidate.reading != baseline
                    && !super::reading_check::has_same_reading(&candidate.reading, current_reading)
                    && supported_reading_change(origin, &candidate.reading)
                {
                    let suggestion = TypoSuggestion {
                        surface: candidate.reading.clone(),
                        token_reading: origin.into(),
                        token_surface: candidate.reading,
                        learned: false,
                        channel_confidence: Some(candidate.confidence),
                    };
                    let auto_apply = can_auto_apply_channel_repair_with_strength(
                        origin,
                        &suggestion.surface,
                        candidate.confidence,
                        strength,
                    );
                    return CorrectionOutcome::Suggestion {
                        suggestion,
                        auto_apply,
                        channel_backed: true,
                    };
                }
                return CorrectionOutcome::None;
            }
        }
    }

    eval_current_trailing(false)
}

/// Plan C / B / A auto-apply policy for native channel repairs:
/// - `Conservative` (Plan C): non-tail `>= 70.0`, tail `>= 95.0`, floating ASCII `>= 55.0`, `alt_reading` `>= 100.0`
/// - `Standard` (Plan B): non-tail `>= 65.0`, tail `>= 90.0`, floating ASCII `>= 55.0`, `alt_reading` `>= 95.0`
/// - `Aggressive` (Plan A): non-tail `>= 55.0`, tail `>= 80.0`, floating ASCII `>= 55.0`, no `alt_reading` gate
pub fn can_auto_apply_channel_repair(
    input: &str,
    reading: &str,
    confidence: super::ChannelConfidence,
) -> bool {
    can_auto_apply_channel_repair_with_strength(
        input,
        reading,
        confidence,
        AutoTypoCorrectionStrength::Standard,
    )
}

pub fn can_auto_apply_channel_repair_with_strength(
    input: &str,
    reading: &str,
    confidence: super::ChannelConfidence,
    strength: AutoTypoCorrectionStrength,
) -> bool {
    if !strength.is_enabled() {
        return false;
    }
    if super::reading_check::user_dictionary_contains_reading(input) || known_reading(input) {
        return false;
    }
    if has_pending_romaji_tail(input) {
        return false;
    }
    let orig_hira = katakana_to_hiragana(input.trim());
    let cand_hira = katakana_to_hiragana(reading.trim());
    if orig_hira.is_empty()
        || cand_hira.is_empty()
        || orig_hira == cand_hira
        || !supported_reading_change(input, reading)
    {
        return false;
    }
    let has_floating_ascii = has_floating_ascii(input);
    let touches_tail = orig_hira.chars().last() != cand_hira.chars().last();
    let (non_tail_min, tail_min, alt_min) = match strength {
        AutoTypoCorrectionStrength::Off => return false,
        AutoTypoCorrectionStrength::Conservative => (700, 950, Some(1000)),
        AutoTypoCorrectionStrength::Standard => (650, 900, Some(950)),
        AutoTypoCorrectionStrength::Aggressive => (550, 800, None),
    };
    let gain_tenths = confidence.gain_tenths;
    let passes_base = if has_floating_ascii {
        gain_tenths >= 550
    } else if !touches_tail {
        gain_tenths >= non_tail_min
    } else {
        gain_tenths >= tail_min
    };
    let passes_disambiguation =
        !confidence.has_alt_reading || has_floating_ascii || alt_min.is_none_or(|min| gain_tenths >= min);
    passes_base && passes_disambiguation
}

fn can_auto_apply_inner(
    input: &str,
    surface: &str,
    channel_confidence: Option<super::ChannelConfidence>,
    strength: AutoTypoCorrectionStrength,
) -> bool {
    if !strength.is_enabled() {
        return false;
    }
    can_auto_apply(input, surface)
        || channel_confidence
            .is_some_and(|c| can_auto_apply_channel_repair_with_strength(input, surface, c, strength))
}

pub fn can_auto_apply_suggestion(input: &str, suggestion: &TypoSuggestion) -> bool {
    can_auto_apply_inner(
        input,
        &suggestion.surface,
        suggestion.channel_confidence,
        AutoTypoCorrectionStrength::Standard,
    )
}

/// Automatic correction requires independent, narrow evidence. Model probability
/// or repeated identical generations alone cannot certify the user's intention.
pub fn can_auto_apply(input: &str, surface: &str) -> bool {
    if super::reading_check::user_dictionary_contains_reading(input) {
        return false;
    }
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
            ("くらおど", "くらうど"),
            ("おひょ", "おはよう"),
            ("てそと", "てすと"),
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

        // 1. Repaired reading from keyboard channel is returned directly without /completion.
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
            assert_eq!(suggestion.surface, "おせわになっております");
            assert_eq!(suggestion.token_reading, "おせわになております");
        });
        {
            let recorded = sent_calls.lock().unwrap();
            assert!(recorded.is_empty());
        }

        // 2. Same reading as input returns None without calling /completion.
        clear_cache();
        let unchanged_calls = Arc::new(Mutex::new(Vec::new()));
        let unchanged_backend = MockUnifiedBackend {
            repair: Some(json!({
                "observed": "おせわになております",
                "reading": "おせわになております",
                "gain": 45.0
            })),
            reply: json!({"content": "お世話になっております", "stopped_eos": true}),
            calls: unchanged_calls.clone(),
        };
        runtime::with_backend(Box::new(unchanged_backend), || {
            assert!(suggest("確認をお願いします。", "おせわになております", &config).is_none());
        });
        assert!(unchanged_calls.lock().unwrap().is_empty());

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

    #[test]
    fn input_hints_are_forwarded_only_for_unsplit_compositions() {
        use std::sync::{Arc, Mutex};
        #[derive(Clone)]
        struct CaptureBackend(Arc<Mutex<Vec<(String, String, String, String)>>>);
        impl runtime::CompletionBackend for CaptureBackend {
            fn allow_native_worker(&self) -> bool {
                true
            }
            fn wait_for_native_worker(&self) -> bool {
                true
            }
            fn complete(&self, _: u16, _: &str, _: &Value, _: u64) -> Option<Value> {
                None
            }
            fn keyboard_repair_with_hints(
                &self,
                raw: &str,
                input: &str,
                _context: &str,
                touch_hints: &str,
                input_mode: &str,
                _timeout_ms: u64,
            ) -> Option<Option<Value>> {
                self.0.lock().unwrap().push((
                    raw.to_owned(),
                    input.to_owned(),
                    touch_hints.to_owned(),
                    input_mode.to_owned(),
                ));
                Some(None)
            }
        }
        let calls = Arc::new(Mutex::new(Vec::new()));
        let config = AiAssistConfig {
            enabled: true,
            corrector_port: 12000,
            ..AiAssistConfig::default()
        };
        runtime::with_backend(Box::new(CaptureBackend(calls.clone())), || {
            let _ = suggest_composition_with_input_hints(
                "",
                "pもってる",
                "P持ってる",
                "pmotteru",
                "0:o=0.85",
                "qwerty",
                &config,
            );
            let long_prefix_input = format!("{}。おねがいすます", "あ".repeat(126));
            let long_prefix_base = format!("{}。お願いすます", "あ".repeat(126));
            let _ = suggest_composition_with_input_hints(
                "",
                &long_prefix_input,
                &long_prefix_base,
                "",
                "1:し=0.80",
                "flick",
                &config,
            );
        });
        let recorded = calls.lock().unwrap();
        assert_eq!(recorded.len(), 2);
        assert_eq!(
            recorded[0],
            (
                "pmotteru".to_owned(),
                "pもってる".to_owned(),
                "0:o=0.85".to_owned(),
                "qwerty".to_owned()
            )
        );
        assert_eq!(
            recorded[1],
            (
                String::new(),
                "おねがいすます".to_owned(),
                String::new(),
                "flick".to_owned()
            )
        );
    }

    #[test]
    fn plan_b_channel_auto_apply_balances_tail_ambiguity_and_floating_ascii() {
        let _dict_guard = super::super::USER_DICTIONARY_TEST_LOCK.lock().unwrap();
        let conf = |gain_tenths, has_alt_reading| super::super::ChannelConfidence {
            gain_tenths,
            has_alt_reading,
        };
        // Non-tail kana: >= 65.0 without alt_reading; >= 95.0 with alt_reading.
        assert!(!can_auto_apply_channel_repair(
            "おせわになております",
            "おせわになっております",
            conf(649, false)
        ));
        assert!(can_auto_apply_channel_repair(
            "おせわになております",
            "おせわになっております",
            conf(650, false)
        ));
        assert!(!can_auto_apply_channel_repair(
            "おせわになております",
            "おせわになっております",
            conf(949, true)
        ));
        assert!(can_auto_apply_channel_repair(
            "おせわになております",
            "おせわになっております",
            conf(950, true)
        ));

        // Tail-editing kana: >= 90.0 without alt_reading; >= 95.0 with alt_reading.
        assert!(!can_auto_apply_channel_repair(
            "ふるいしゃしんをいちま",
            "ふるいしゃしんをいちまい",
            conf(899, false)
        ));
        assert!(can_auto_apply_channel_repair(
            "ふるいしゃしんをいちま",
            "ふるいしゃしんをいちまい",
            conf(900, false)
        ));
        assert!(!can_auto_apply_channel_repair(
            "ふるいしゃしんをいちま",
            "ふるいしゃしんをいちまい",
            conf(949, true)
        ));
        assert!(can_auto_apply_channel_repair(
            "ふるいしゃしんをいちま",
            "ふるいしゃしんをいちまい",
            conf(950, true)
        ));

        // Floating ASCII: >= 55.0 even with an alternative reading.
        assert!(!can_auto_apply_channel_repair(
            "pもってる",
            "おもってる",
            conf(549, true)
        ));
        assert!(can_auto_apply_channel_repair(
            "pもってる",
            "おもってる",
            conf(550, true)
        ));
        assert!(can_auto_apply_channel_repair(
            "かlど",
            "かーど",
            conf(550, true)
        ));

        // Pending romaji tail and user dictionary readings remain blocked.
        assert!(!can_auto_apply_channel_repair(
            "さっかl",
            "さっかー",
            conf(1500, false)
        ));
        super::super::set_user_dictionary_vocabulary([("おせわになております", "登録語")]);
        let blocked_by_user_dict = can_auto_apply_channel_repair(
            "おせわになております",
            "おせわになっております",
            conf(1500, false),
        );
        super::super::set_user_dictionary_vocabulary(std::iter::empty());
        assert!(!blocked_by_user_dict);
    }

    #[test]
    fn auto_apply_strength_presets_match_plan_a_b_c_and_off() {
        let _dict_guard = super::super::USER_DICTIONARY_TEST_LOCK.lock().unwrap();
        let conf = |gain_tenths, has_alt_reading| super::super::ChannelConfidence {
            gain_tenths,
            has_alt_reading,
        };
        let non_tail = ("おせわになております", "おせわになっております");
        let tail = ("ふるいしゃしんをいちま", "ふるいしゃしんをいちまい");

        // Off blocks both channel repairs and dictionary/floating-consonant auto-apply.
        assert!(!can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(1500, false),
            AutoTypoCorrectionStrength::Off,
        ));
        let off_floating = TypoSuggestion {
            surface: "おかえり".into(),
            token_reading: "おkせり".into(),
            token_surface: "おかえり".into(),
            learned: false,
            channel_confidence: Some(conf(1500, false)),
        };
        assert!(!can_auto_apply_composition_suggestion_with_strength(
            "おkせり",
            "おkせり",
            &off_floating,
            AutoTypoCorrectionStrength::Off,
        ));

        // Non-tail boundaries: Aggressive 550, Standard 650, Conservative 700.
        assert!(!can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(549, false),
            AutoTypoCorrectionStrength::Aggressive,
        ));
        assert!(can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(550, false),
            AutoTypoCorrectionStrength::Aggressive,
        ));
        assert!(can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(600, false),
            AutoTypoCorrectionStrength::Aggressive,
        ));
        assert!(!can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(600, false),
            AutoTypoCorrectionStrength::Standard,
        ));
        assert!(can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(650, false),
            AutoTypoCorrectionStrength::Standard,
        ));
        assert!(!can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(650, false),
            AutoTypoCorrectionStrength::Conservative,
        ));
        assert!(can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(700, false),
            AutoTypoCorrectionStrength::Conservative,
        ));

        // Tail boundaries: Aggressive 800, Standard 900, Conservative 950.
        assert!(can_auto_apply_channel_repair_with_strength(
            tail.0,
            tail.1,
            conf(800, false),
            AutoTypoCorrectionStrength::Aggressive,
        ));
        assert!(!can_auto_apply_channel_repair_with_strength(
            tail.0,
            tail.1,
            conf(850, false),
            AutoTypoCorrectionStrength::Standard,
        ));
        assert!(can_auto_apply_channel_repair_with_strength(
            tail.0,
            tail.1,
            conf(900, false),
            AutoTypoCorrectionStrength::Standard,
        ));
        assert!(!can_auto_apply_channel_repair_with_strength(
            tail.0,
            tail.1,
            conf(900, false),
            AutoTypoCorrectionStrength::Conservative,
        ));
        assert!(can_auto_apply_channel_repair_with_strength(
            tail.0,
            tail.1,
            conf(950, false),
            AutoTypoCorrectionStrength::Conservative,
        ));

        // Contested (has_alt_reading = true): Aggressive ignores gate, Standard >= 950, Conservative >= 1000.
        assert!(can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(750, true),
            AutoTypoCorrectionStrength::Aggressive,
        ));
        assert!(!can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(750, true),
            AutoTypoCorrectionStrength::Standard,
        ));
        assert!(can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(950, true),
            AutoTypoCorrectionStrength::Standard,
        ));
        assert!(!can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(950, true),
            AutoTypoCorrectionStrength::Conservative,
        ));
        assert!(can_auto_apply_channel_repair_with_strength(
            non_tail.0,
            non_tail.1,
            conf(1000, true),
            AutoTypoCorrectionStrength::Conservative,
        ));
    }

    #[test]
    fn auto_refinement_refines_reverts_confirms_and_detects_trailing_typos() {
        use std::sync::{Arc, Mutex};
        let _dict_guard = super::super::USER_DICTIONARY_TEST_LOCK.lock().unwrap();
        struct SeqBackend {
            calls: Arc<Mutex<Vec<(String, String)>>>,
            replies: Arc<Mutex<Vec<Option<Value>>>>,
        }
        impl runtime::CompletionBackend for SeqBackend {
            fn allow_native_worker(&self) -> bool {
                true
            }
            fn wait_for_native_worker(&self) -> bool {
                true
            }
            fn complete(&self, _: u16, _: &str, _: &Value, _: u64) -> Option<Value> {
                None
            }
            fn keyboard_repair_with_hints(
                &self,
                raw: &str,
                input: &str,
                _context: &str,
                _touch_hints: &str,
                _input_mode: &str,
                _timeout_ms: u64,
            ) -> Option<Option<Value>> {
                self.calls
                    .lock()
                    .unwrap()
                    .push((input.to_owned(), raw.to_owned()));
                self.replies.lock().unwrap().remove(0).map(Some)
            }
        }
        let config = AiAssistConfig {
            enabled: true,
            corrector_port: 12000,
            ..AiAssistConfig::default()
        };

        // 1. Refinement auto-apply & hysteresis.
        let calls = Arc::new(Mutex::new(Vec::new()));
        let replies = Arc::new(Mutex::new(vec![
            Some(json!({"observed": "しゅてんします", "reading": "しゅってんします", "gain": 82.0})),
            Some(json!({"observed": "しゅてんします", "reading": "しゅってんします", "alt_reading": "しゅうてんします", "gain": 82.0})),
        ]));
        runtime::with_backend(
            Box::new(SeqBackend {
                calls: calls.clone(),
                replies,
            }),
            || {
                let ctx = AutoRefinementContext {
                    origin_reading: "しゅてんします",
                    origin_raw: "shutenshimasu",
                    origin_touch_hints: "",
                    applied_prefix: "しゅうてん",
                };
                match suggest_auto_refinement(
                    "",
                    "しゅうてんします",
                    "終点します",
                    "",
                    "",
                    "qwerty",
                    &ctx,
                    AutoTypoCorrectionStrength::Standard,
                    &config,
                ) {
                    CorrectionOutcome::Suggestion {
                        suggestion,
                        auto_apply,
                        channel_backed,
                    } => {
                        assert_eq!(suggestion.surface, "しゅってんします");
                        assert!(auto_apply);
                        assert!(channel_backed);
                    }
                    other => panic!("expected refinement auto-apply, got {other:?}"),
                }
                match suggest_auto_refinement(
                    "",
                    "しゅうてんします",
                    "終点します",
                    "",
                    "",
                    "qwerty",
                    &ctx,
                    AutoTypoCorrectionStrength::Standard,
                    &config,
                ) {
                    CorrectionOutcome::Suggestion {
                        suggestion,
                        auto_apply,
                        ..
                    } => {
                        assert_eq!(suggestion.surface, "しゅってんします");
                        assert!(!auto_apply);
                    }
                    other => panic!("expected manual refinement proposal, got {other:?}"),
                }
            },
        );
        assert_eq!(calls.lock().unwrap()[0].1, "shutenshimasu");

        // 2. 0ms dict/user-dict auto-revert with zero worker calls.
        super::super::set_user_dictionary_vocabulary([("しゅてんどうじ", "酒呑童子")]);
        let dict_calls = Arc::new(Mutex::new(Vec::new()));
        runtime::with_backend(
            Box::new(SeqBackend {
                calls: dict_calls.clone(),
                replies: Arc::new(Mutex::new(Vec::new())),
            }),
            || {
                let ctx = AutoRefinementContext {
                    origin_reading: "しゅてんどうじ",
                    origin_raw: "shutendouji",
                    origin_touch_hints: "",
                    applied_prefix: "しゅうてん",
                };
                assert_eq!(
                    suggest_auto_refinement(
                        "",
                        "しゅうてんどうじ",
                        "終点童子",
                        "",
                        "",
                        "qwerty",
                        &ctx,
                        AutoTypoCorrectionStrength::Standard,
                        &config,
                    ),
                    CorrectionOutcome::RevertToOrigin
                );
            },
        );
        super::super::set_user_dictionary_vocabulary(std::iter::empty());
        assert!(dict_calls.lock().unwrap().is_empty());

        // 3. Model-clean auto-revert vs safety gates (timeout, trailing<2, origin>24, floating ASCII, Off).
        let revert_calls = Arc::new(Mutex::new(Vec::new()));
        let revert_replies = Arc::new(Mutex::new(vec![
            Some(json!({"observed": "しゅてんどうじ"})),
            None,
            Some(json!({"observed": "しゅてんだ"})),
            Some(json!({"observed": "きょおあいうえおかきくけこさしすせそたちつてとなにぬ"})),
            Some(json!({"observed": "しゅてんaあ"})),
            Some(json!({"observed": "しゅてんどうじ"})),
        ]));
        runtime::with_backend(
            Box::new(SeqBackend {
                calls: revert_calls.clone(),
                replies: revert_replies,
            }),
            || {
                assert_eq!(
                    suggest_auto_refinement(
                        "",
                        "しゅうてんどうじ",
                        "しゅうてんどうじ",
                        "",
                        "",
                        "qwerty",
                        &AutoRefinementContext {
                            origin_reading: "しゅてんどうじ",
                            origin_raw: "shutendouji",
                            origin_touch_hints: "",
                            applied_prefix: "しゅうてん",
                        },
                        AutoTypoCorrectionStrength::Standard,
                        &config,
                    ),
                    CorrectionOutcome::RevertToOrigin
                );
                // Timeout returns None, never RevertToOrigin.
                assert_eq!(
                    suggest_auto_refinement(
                        "",
                        "しゅうてんどうじ",
                        "しゅうてんどうじ",
                        "",
                        "",
                        "qwerty",
                        &AutoRefinementContext {
                            origin_reading: "しゅてんどうじ",
                            origin_raw: "shutendouji",
                            origin_touch_hints: "",
                            applied_prefix: "しゅうてん",
                        },
                        AutoTypoCorrectionStrength::Standard,
                        &config,
                    ),
                    CorrectionOutcome::None
                );
                // trailing.chars().count() < 2 must not revert on model clean.
                assert_eq!(
                    suggest_auto_refinement(
                        "",
                        "しゅうてんだ",
                        "しゅうてんだ",
                        "",
                        "",
                        "qwerty",
                        &AutoRefinementContext {
                            origin_reading: "しゅてんだ",
                            origin_raw: "shutenda",
                            origin_touch_hints: "",
                            applied_prefix: "しゅうてん",
                        },
                        AutoTypoCorrectionStrength::Standard,
                        &config,
                    ),
                    CorrectionOutcome::None
                );
                // origin.chars().count() > 24 must not revert on model clean.
                assert_eq!(
                    suggest_auto_refinement(
                        "",
                        "きょうあいうえおかきくけこさしすせそたちつてとなにぬ",
                        "今日あいうえおかきくけこさしすせそたちつてとなにぬ",
                        "",
                        "",
                        "qwerty",
                        &AutoRefinementContext {
                            origin_reading: "きょおあいうえおかきくけこさしすせそたちつてとなにぬ",
                            origin_raw: "",
                            origin_touch_hints: "",
                            applied_prefix: "きょう",
                        },
                        AutoTypoCorrectionStrength::Standard,
                        &config,
                    ),
                    CorrectionOutcome::None
                );
                // Pending romaji tail never sends worker or reverts.
                assert_eq!(
                    suggest_auto_refinement(
                        "",
                        "きょうはp",
                        "きょうはp",
                        "",
                        "",
                        "qwerty",
                        &AutoRefinementContext {
                            origin_reading: "きょおはp",
                            origin_raw: "",
                            origin_touch_hints: "",
                            applied_prefix: "きょうは",
                        },
                        AutoTypoCorrectionStrength::Standard,
                        &config,
                    ),
                    CorrectionOutcome::None
                );
                // Floating Latin inside origin also blocks model-clean auto-revert.
                assert_eq!(
                    suggest_auto_refinement(
                        "",
                        "しゅうてんaあ",
                        "しゅうてんaあ",
                        "",
                        "",
                        "qwerty",
                        &AutoRefinementContext {
                            origin_reading: "しゅてんaあ",
                            origin_raw: "",
                            origin_touch_hints: "",
                            applied_prefix: "しゅうてん",
                        },
                        AutoTypoCorrectionStrength::Standard,
                        &config,
                    ),
                    CorrectionOutcome::None
                );
                // Off strength never reverts.
                assert_eq!(
                    suggest_auto_refinement(
                        "",
                        "しゅうてんどうじ",
                        "しゅうてんどうじ",
                        "",
                        "",
                        "qwerty",
                        &AutoRefinementContext {
                            origin_reading: "しゅてんどうじ",
                            origin_raw: "shutendouji",
                            origin_touch_hints: "",
                            applied_prefix: "しゅうてん",
                        },
                        AutoTypoCorrectionStrength::Off,
                        &config,
                    ),
                    CorrectionOutcome::None
                );
            },
        );

        // 4. Confirmed correction takes exactly 1 worker call and returns None.
        let confirm_calls = Arc::new(Mutex::new(Vec::new()));
        let confirm_replies = Arc::new(Mutex::new(vec![Some(json!({
            "observed": "きょおはいいてんき",
            "reading": "きょうはいいてんき",
            "gain": 80.0
        }))]));
        runtime::with_backend(
            Box::new(SeqBackend {
                calls: confirm_calls.clone(),
                replies: confirm_replies,
            }),
            || {
                assert_eq!(
                    suggest_auto_refinement(
                        "",
                        "きょうはいいてんき",
                        "今日は良い天気",
                        "",
                        "",
                        "qwerty",
                        &AutoRefinementContext {
                            origin_reading: "きょおはいいてんき",
                            origin_raw: "kyoohaiitenki",
                            origin_touch_hints: "",
                            applied_prefix: "きょうは",
                        },
                        AutoTypoCorrectionStrength::Standard,
                        &config,
                    ),
                    CorrectionOutcome::None
                );
            },
        );
        assert_eq!(confirm_calls.lock().unwrap().len(), 1);

        // 5a. Trailing 2nd typo when candidate starts with origin_prefix preserves applied_prefix.
        let trail_replies_a = Arc::new(Mutex::new(vec![
            Some(json!({"observed": "きょおはあしだ", "reading": "きょおはあした", "gain": 85.0})),
            Some(json!({"observed": "きょうはあしだ", "reading": "きょうはあした", "gain": 92.0})),
        ]));
        runtime::with_backend(
            Box::new(SeqBackend {
                calls: Arc::new(Mutex::new(Vec::new())),
                replies: trail_replies_a,
            }),
            || {
                match suggest_auto_refinement(
                    "",
                    "きょうはあしだ",
                    "今日はあしだ",
                    "",
                    "",
                    "qwerty",
                    &AutoRefinementContext {
                        origin_reading: "きょおはあしだ",
                        origin_raw: "kyoohaashida",
                        origin_touch_hints: "",
                        applied_prefix: "きょうは",
                    },
                    AutoTypoCorrectionStrength::Standard,
                    &config,
                ) {
                    CorrectionOutcome::Suggestion {
                        suggestion,
                        auto_apply,
                        ..
                    } => {
                        assert_eq!(suggestion.surface, "きょうはあした");
                        assert!(auto_apply);
                    }
                    other => panic!("expected 2nd typo suggestion, got {other:?}"),
                }
            },
        );

        // 5b. Trailing 2nd typo detected via alt_reading on confirmed prefix.
        let trail_replies_b = Arc::new(Mutex::new(vec![
            Some(json!({"observed": "きょおはあしだ", "reading": "きょうはあしだ", "alt_reading": "きょおはあした", "gain": 88.0})),
            Some(json!({"observed": "きょうはあしだ", "reading": "きょうはあした", "gain": 92.0})),
        ]));
        runtime::with_backend(
            Box::new(SeqBackend {
                calls: Arc::new(Mutex::new(Vec::new())),
                replies: trail_replies_b,
            }),
            || {
                match suggest_auto_refinement(
                    "",
                    "きょうはあしだ",
                    "今日はあしだ",
                    "",
                    "",
                    "qwerty",
                    &AutoRefinementContext {
                        origin_reading: "きょおはあしだ",
                        origin_raw: "kyoohaashida",
                        origin_touch_hints: "",
                        applied_prefix: "きょうは",
                    },
                    AutoTypoCorrectionStrength::Standard,
                    &config,
                ) {
                    CorrectionOutcome::Suggestion {
                        suggestion,
                        auto_apply,
                        ..
                    } => {
                        assert_eq!(suggestion.surface, "きょうはあした");
                        assert!(auto_apply);
                    }
                    other => panic!("expected 2nd typo via alt_reading, got {other:?}"),
                }
            },
        );
    }
}
