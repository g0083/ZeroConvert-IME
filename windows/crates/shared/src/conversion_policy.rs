//! Input and stability policy shared by the Windows client and Android host.
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;

/// Default fullwidth state of the symbols the Windows client can convert.
///
/// This table is the single source of truth: `shared` re-exports it and the
/// Android host receives it through the shared engine's `inputPolicy`
/// operation. Do not repeat these values in another language.
pub const CHARACTER_WIDTH_SYMBOL_DEFAULTS: [(&str, bool); 42] = [
    ("0", false),
    ("1", false),
    ("2", false),
    ("3", false),
    ("4", false),
    ("5", false),
    ("6", false),
    ("7", false),
    ("8", false),
    ("9", false),
    ("!", true),
    ("\"", true),
    ("#", false),
    ("$", false),
    ("%", false),
    ("&", false),
    ("'", true),
    ("(", true),
    (")", true),
    ("*", true),
    ("+", true),
    (",", true),
    ("-", true),
    (".", true),
    ("/", true),
    (":", true),
    (";", true),
    ("<", true),
    ("=", true),
    (">", true),
    ("?", true),
    ("@", false),
    ("[", true),
    ("\\", false),
    ("]", true),
    ("^", false),
    ("_", false),
    ("`", false),
    ("{", true),
    ("|", false),
    ("}", true),
    ("~", true),
];

/// Shifted symbols that end an Android composition immediately.
///
/// The Windows client has no such key path: the same characters append to the
/// composition and follow [CHARACTER_WIDTH_SYMBOL_DEFAULTS]. The list stays
/// here so both hosts can be checked against `tests/input-policy/cases.tsv`.
pub const IMMEDIATE_COMMIT_CHARS: &str = "!@#$%^&*()";

/// Punctuation normalized to the character the Japanese reading stores.
pub const PUNCTUATION_FOR_READING: [(char, char); 10] = [
    ('、', '、'),
    ('。', '。'),
    ('，', '，'),
    ('．', '．'),
    ('？', '？'),
    ('！', '！'),
    (',', '、'),
    ('.', '。'),
    ('?', '？'),
    ('!', '！'),
];

/// Input policy published to the Android host on initialization.
///
/// Bump when a field is removed or its meaning changes; the Kotlin reader
/// rejects versions it does not understand instead of guessing.
pub const MAX_USER_DICTIONARY_ENTRIES: usize = 10_000;

pub const INPUT_POLICY_VERSION: u32 = 1;

#[inline]
pub fn is_immediate_commit_char(ch: char) -> bool {
    IMMEDIATE_COMMIT_CHARS.contains(ch)
}

pub fn punctuation_for_reading(ch: char) -> Option<char> {
    PUNCTUATION_FOR_READING
        .iter()
        .find_map(|(from, to)| (*from == ch).then_some(*to))
}

/// Punctuation joins the composition unless a pending romaji tail must be
/// finalized first (`n` before punctuation is ん, not part of it).
#[inline]
pub fn should_compose_punctuation(ch: char, pending_romaji: bool) -> bool {
    !pending_romaji && punctuation_for_reading(ch).is_some()
}

/// Digits take the Windows `UserAction::Number -> AppendText` path, so they
/// belong to the reading and take part in conversion.
#[inline]
pub fn is_composition_digit(ch: char) -> bool {
    ch.is_ascii_digit()
}

/// Translation mode reflects digits and punctuation into the reading literally.
#[inline]
pub fn is_translation_literal_char(ch: char) -> bool {
    is_immediate_commit_char(ch)
        || ch.is_ascii_digit()
        || matches!(ch, '０'..='９')
        || punctuation_for_reading(ch).is_some()
}

pub fn symbol_default_fullwidth(symbol: &str) -> Option<bool> {
    CHARACTER_WIDTH_SYMBOL_DEFAULTS
        .iter()
        .find_map(|(key, value)| (*key == symbol).then_some(*value))
}

#[derive(Clone, Copy, Debug, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AutoCommitLiveConversionMode {
    Off,
    #[default]
    Standard,
    Fast,
}

impl AutoCommitLiveConversionMode {
    pub fn is_enabled(self) -> bool {
        self != Self::Off
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::Standard => "standard",
            Self::Fast => "fast",
        }
    }

    pub fn from_str_lossy(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().replace('-', "_").as_str() {
            "off" | "disabled" | "none" | "false" => Self::Off,
            "fast" | "early" | "aggressive" | "high" | "quick" => Self::Fast,
            _ => Self::Standard,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct TokenLogitMargin {
    /// Exclusive UTF-8 byte end offset in the top candidate surface.
    pub byte_end: u32,
    /// `top1_logit - top2_logit` in milli-units (e.g. 2500 == 2.500).
    pub margin_milli: u32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct LiveConversionPrefixSnapshot {
    pub raw_input_len: usize,
    pub hiragana_len: usize,
    pub preview: String,
}

const LIVE_CONVERSION_AUTO_COMMIT_MIN_TOTAL_HIRAGANA: usize = 16;
const LIVE_CONVERSION_AUTO_COMMIT_MIN_COMMIT_HIRAGANA: usize = 8;
const LIVE_CONVERSION_AUTO_COMMIT_MIN_REMAINING_HIRAGANA: usize = 7;
const LIVE_CONVERSION_AUTO_COMMIT_MIN_PREFIX_CHARS: usize = 4;
const LIVE_CONVERSION_AUTO_COMMIT_STABLE_STEPS: usize = 5;

const AUTO_COMMIT_MIN_MARGIN_FLOOR_MILLI: u32 = 1200;
const AUTO_COMMIT_PUNCT_MIN_MARGIN_MILLI: u32 = 1500;
const AUTO_COMMIT_STANDARD_FAST_MIN_MARGIN_MILLI: u32 = 2500;
const AUTO_COMMIT_FAST_FAST_MIN_MARGIN_MILLI: u32 = 2200;

#[inline]
pub fn is_hiragana_char(ch: char) -> bool {
    matches!(ch as u32, 0x3041..=0x3096 | 0x309D..=0x309F)
}

#[inline]
pub fn is_kanji_or_katakana_char(ch: char) -> bool {
    matches!(
        ch as u32,
        0x30A1..=0x30FA
            | 0x30FC..=0x30FF
            | 0x3005..=0x3007
            | 0x3400..=0x4DBF
            | 0x4E00..=0x9FFF
            | 0xF900..=0xFAFF
            | 0x20000..=0x2FA1F
    )
}

#[inline]
pub fn is_japanese_clause_punctuation_char(ch: char) -> bool {
    matches!(ch, '、' | '。' | '，' | '．' | '！' | '？' | '!' | '?')
}

pub fn raw_input_has_pending_romaji_tail(raw_input: &str, raw_hiragana: &str) -> bool {
    if raw_hiragana
        .chars()
        .last()
        .is_some_and(|ch| ch.is_ascii_alphabetic())
    {
        return true;
    }
    let mut rev = raw_input.chars().rev();
    let Some(last) = rev.next() else {
        return false;
    };
    if last.eq_ignore_ascii_case(&'n') {
        let prev = rev.next();
        return !prev.is_some_and(|ch| ch.eq_ignore_ascii_case(&'n') || ch == '\'');
    }
    last.is_ascii_alphabetic() && !matches!(last.to_ascii_lowercase(), 'a' | 'i' | 'u' | 'e' | 'o')
}

pub fn raw_input_split_breaks_romaji(raw_input: &str, split_char_index: usize) -> bool {
    let chars: Vec<char> = raw_input.chars().collect();
    if split_char_index == 0 || split_char_index >= chars.len() {
        return true;
    }
    let last = chars[split_char_index - 1];
    let next = chars[split_char_index];
    if last.eq_ignore_ascii_case(&'n') {
        let prev_is_n = split_char_index >= 2
            && (chars[split_char_index - 2].eq_ignore_ascii_case(&'n')
                || chars[split_char_index - 2] == '\'');
        if !prev_is_n
            && matches!(
                next.to_ascii_lowercase(),
                'a' | 'i' | 'u' | 'e' | 'o' | 'y' | 'n'
            )
        {
            return true;
        }
        return false;
    }
    last.is_ascii_alphabetic() && !matches!(last.to_ascii_lowercase(), 'a' | 'i' | 'u' | 'e' | 'o')
}

#[inline]
fn is_auto_commit_single_hiragana_boundary(ch: char) -> bool {
    matches!(
        ch,
        'は' | 'が' | 'を' | 'に' | 'で' | 'と' | 'の' | 'へ' | 'も' | 'や'
            | 'か' | 'て' | 'た' | 'だ' | 'ば' | 'し' | 'な' | 'ね' | 'よ'
    )
}

pub fn is_natural_auto_commit_prefix(candidate_prefix: &str, full_preview: &str) -> bool {
    let Some(remainder) = full_preview.strip_prefix(candidate_prefix) else {
        return false;
    };
    if remainder.is_empty()
        || candidate_prefix.chars().count() < LIVE_CONVERSION_AUTO_COMMIT_MIN_PREFIX_CHARS
    {
        return false;
    }
    let Some(last_char) = candidate_prefix.chars().last() else {
        return false;
    };
    let Some(next_char) = remainder.chars().next() else {
        return false;
    };
    if is_japanese_clause_punctuation_char(last_char) {
        return true;
    }
    if !(candidate_prefix.chars().any(is_kanji_or_katakana_char)
        && is_hiragana_char(last_char)
        && is_kanji_or_katakana_char(next_char))
    {
        return false;
    }
    let prev_char = candidate_prefix.chars().rev().nth(1);
    if prev_char.is_some_and(is_kanji_or_katakana_char) {
        return is_auto_commit_single_hiragana_boundary(last_char);
    }
    true
}

/// Pushes one inference surface into the stable-preview window.
///
/// The bridge and the parity test both build the window through here, so the
/// deduplication and window size have a single definition. A repeated surface is
/// one inference step, not extra evidence.
pub fn push_live_conversion_history(history: &mut VecDeque<String>, preview: &str) {
    if history.back().is_none_or(|last| last != preview) {
        history.push_back(preview.to_string());
    }
    while history.len() > LIVE_CONVERSION_AUTO_COMMIT_STABLE_STEPS {
        history.pop_front();
    }
}

pub fn record_live_conversion_prefix_snapshot(
    raw_input: &str,
    raw_hiragana: &str,
    preview: &str,
    suffix: &str,
    snapshots: &mut Vec<LiveConversionPrefixSnapshot>,
    history: &mut VecDeque<String>,
) {
    if preview.is_empty()
        || !suffix.is_empty()
        || raw_input_has_pending_romaji_tail(raw_input, raw_hiragana)
    {
        return;
    }
    let raw_input_len = raw_input.chars().count();
    let hiragana_len = raw_hiragana.chars().count();
    if raw_input_len == 0 || hiragana_len == 0 {
        return;
    }
    push_live_conversion_history(history, preview);
    if let Some(existing) = snapshots
        .iter_mut()
        .find(|snap| snap.raw_input_len == raw_input_len || snap.hiragana_len == hiragana_len)
    {
        existing.raw_input_len = raw_input_len;
        existing.hiragana_len = hiragana_len;
        existing.preview = preview.to_string();
    } else {
        snapshots.push(LiveConversionPrefixSnapshot {
            raw_input_len,
            hiragana_len,
            preview: preview.to_string(),
        });
    }
}

pub fn prefix_min_margin_milli(prefix: &str, margins: &[TokenLogitMargin]) -> Option<u32> {
    let prefix_bytes = prefix.len();
    if prefix_bytes == 0 || margins.is_empty() {
        return None;
    }
    if (margins.last()?.byte_end as usize) < prefix_bytes {
        return None;
    }
    let mut prev_end = 0usize;
    let mut min_margin: Option<u32> = None;
    for m in margins {
        let end = m.byte_end as usize;
        if end <= prev_end {
            return None;
        }
        if prev_end < prefix_bytes {
            min_margin = Some(match min_margin {
                Some(cur) => cur.min(m.margin_milli),
                None => m.margin_milli,
            });
        }
        prev_end = end;
        if prev_end >= prefix_bytes {
            break;
        }
    }
    min_margin
}

pub fn find_auto_commit_live_conversion_snapshot(
    raw_input: &str,
    raw_hiragana: &str,
    preview: &str,
    suffix: &str,
    snapshots: &[LiveConversionPrefixSnapshot],
    history: &VecDeque<String>,
) -> Option<LiveConversionPrefixSnapshot> {
    find_auto_commit_live_conversion_snapshot_with_policy(
        raw_input,
        raw_hiragana,
        preview,
        suffix,
        snapshots,
        history,
        AutoCommitLiveConversionMode::Standard,
        &[],
    )
}

#[allow(clippy::too_many_arguments)]
pub fn find_auto_commit_live_conversion_snapshot_with_policy(
    raw_input: &str,
    raw_hiragana: &str,
    preview: &str,
    suffix: &str,
    snapshots: &[LiveConversionPrefixSnapshot],
    history: &VecDeque<String>,
    mode: AutoCommitLiveConversionMode,
    top_token_margins: &[TokenLogitMargin],
) -> Option<LiveConversionPrefixSnapshot> {
    if !mode.is_enabled()
        || !suffix.is_empty()
        || raw_input_has_pending_romaji_tail(raw_input, raw_hiragana)
    {
        return None;
    }
    let total_hiragana = raw_hiragana.chars().count();
    snapshots
        .iter()
        .filter(|snap| {
            if raw_input_split_breaks_romaji(raw_input, snap.raw_input_len)
                || !is_natural_auto_commit_prefix(&snap.preview, preview)
            {
                return false;
            }
            let stable_count = history
                .iter()
                .rev()
                .take_while(|entry| {
                    entry.starts_with(&snap.preview) && entry.len() > snap.preview.len()
                })
                .count();
            let commit_hiragana = snap.hiragana_len;
            let remaining_hiragana = total_hiragana.saturating_sub(commit_hiragana);
            let is_punct = snap
                .preview
                .chars()
                .last()
                .is_some_and(is_japanese_clause_punctuation_char);
            let min_margin = prefix_min_margin_milli(&snap.preview, top_token_margins);

            match (mode, min_margin) {
                (AutoCommitLiveConversionMode::Off, _) => false,
                (_, Some(m)) if m < AUTO_COMMIT_MIN_MARGIN_FLOOR_MILLI => false,
                (_, Some(m)) if is_punct && m >= AUTO_COMMIT_PUNCT_MIN_MARGIN_MILLI => {
                    total_hiragana >= 10
                        && commit_hiragana >= 5
                        && remaining_hiragana >= 3
                        && stable_count >= 2
                }
                (AutoCommitLiveConversionMode::Standard, Some(m))
                    if m >= AUTO_COMMIT_STANDARD_FAST_MIN_MARGIN_MILLI =>
                {
                    total_hiragana >= 12
                        && commit_hiragana >= 6
                        && remaining_hiragana >= 4
                        && stable_count >= 3
                }
                (AutoCommitLiveConversionMode::Standard, _) => {
                    total_hiragana >= LIVE_CONVERSION_AUTO_COMMIT_MIN_TOTAL_HIRAGANA
                        && commit_hiragana >= LIVE_CONVERSION_AUTO_COMMIT_MIN_COMMIT_HIRAGANA
                        && remaining_hiragana >= LIVE_CONVERSION_AUTO_COMMIT_MIN_REMAINING_HIRAGANA
                        && stable_count >= LIVE_CONVERSION_AUTO_COMMIT_STABLE_STEPS
                }
                (AutoCommitLiveConversionMode::Fast, Some(m))
                    if m >= AUTO_COMMIT_FAST_FAST_MIN_MARGIN_MILLI =>
                {
                    total_hiragana >= 10
                        && commit_hiragana >= 5
                        && remaining_hiragana >= 4
                        && stable_count >= 2
                }
                (AutoCommitLiveConversionMode::Fast, _) => {
                    total_hiragana >= 14
                        && commit_hiragana >= 7
                        && remaining_hiragana >= 5
                        && stable_count >= 4
                }
            }
        })
        .max_by_key(|snap| snap.hiragana_len)
        .cloned()
}

/// Logical cursor position within the active conversion reading (`convertTarget`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CursorPositionKind {
    Empty,
    AtStart,
    MidSentence,
    AtEnd,
}

/// Classifies `cursor` (0-based scalar index) relative to `total_chars`.
pub fn classify_cursor_position(cursor: usize, total_chars: usize) -> CursorPositionKind {
    if total_chars == 0 {
        CursorPositionKind::Empty
    } else if cursor == 0 {
        CursorPositionKind::AtStart
    } else if cursor < total_chars {
        CursorPositionKind::MidSentence
    } else {
        CursorPositionKind::AtEnd
    }
}

/// Returns `true` when the cursor sits strictly inside a non-empty reading (`0 < cursor < total`).
pub fn is_cursor_mid_sentence(cursor: usize, total_chars: usize) -> bool {
    classify_cursor_position(cursor, total_chars) == CursorPositionKind::MidSentence
}

/// While previewing candidates, typing inserts at the cursor instead of committing whenever the
/// cursor is not at the end of the reading (`cursor < total_chars`).
pub fn should_insert_at_cursor_while_previewing(cursor: usize, total_chars: usize) -> bool {
    total_chars > 0 && cursor < total_chars
}

/// Punctuation auto-commit only applies when the cursor is at the end of the composition.
pub fn should_allow_punctuation_commit(cursor: usize, total_chars: usize) -> bool {
    total_chars == 0 || cursor >= total_chars
}

/// Auto-assist (typo correction, prediction, remote reranking) only runs at the composition tail
/// without an unconverted trailing suffix.
pub fn should_run_live_assist_at_cursor(
    cursor: usize,
    total_chars: usize,
    has_suffix: bool,
) -> bool {
    !has_suffix && cursor >= total_chars
}

/// Shared commit decision when accepting (`Enter` / candidate selection) with a cursor-scoped range.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CursorCommitAction {
    /// Commit only the converted prefix `[0..cursor)` and keep the trailing suffix editable.
    CommitPrefixKeepSuffix,
    /// Cursor is at start (`preview` is empty) with a trailing suffix: commit the suffix and end.
    CommitSuffixAndEnd,
    /// Cursor is at end with no unconverted suffix: commit the whole composition and end.
    CommitWholeAndEnd,
}

pub fn decide_cursor_commit_action(has_prefix: bool, has_suffix: bool) -> CursorCommitAction {
    if !has_suffix {
        CursorCommitAction::CommitWholeAndEnd
    } else if !has_prefix {
        CursorCommitAction::CommitSuffixAndEnd
    } else {
        CursorCommitAction::CommitPrefixKeepSuffix
    }
}

/// Shared deletion decision for `Backspace` and `Delete` (`ForwardDelete`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CursorDeleteAction {
    NoOp,
    DeleteBackward,
    DeleteForward,
}

pub fn decide_backspace_action(cursor: usize, total_chars: usize) -> CursorDeleteAction {
    if total_chars == 0 || cursor == 0 {
        CursorDeleteAction::NoOp
    } else {
        CursorDeleteAction::DeleteBackward
    }
}

pub fn decide_delete_forward_action(cursor: usize, total_chars: usize) -> CursorDeleteAction {
    if total_chars == 0 || cursor >= total_chars {
        CursorDeleteAction::NoOp
    } else {
        CursorDeleteAction::DeleteForward
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SCENARIO_TSV: &str = include_str!("../../../../tests/input-policy/cases.tsv");

    struct Scenario {
        id: String,
        operation: String,
        input: char,
        expect: String,
        line: usize,
    }

    fn scenarios() -> Vec<Scenario> {
        SCENARIO_TSV
            .lines()
            .enumerate()
            .filter_map(|(index, line)| {
                let line = line.trim_end_matches('\r');
                if line.trim().is_empty() || line.starts_with('#') {
                    return None;
                }
                let columns: Vec<&str> = line.split('\t').collect();
                assert_eq!(
                    columns.len(),
                    4,
                    "cases.tsv:{}: expected 4 tab-separated columns",
                    index + 1
                );
                let input: Vec<char> = columns[2].chars().collect();
                assert_eq!(
                    input.len(),
                    1,
                    "cases.tsv:{}: input must be exactly one character",
                    index + 1
                );
                Some(Scenario {
                    id: columns[0].to_string(),
                    operation: columns[1].to_string(),
                    input: input[0],
                    expect: columns[3].to_string(),
                    line: index + 1,
                })
            })
            .collect()
    }

    fn outcome(scenario: &Scenario) -> String {
        let outcome = match scenario.operation.as_str() {
            "immediate" => {
                if is_immediate_commit_char(scenario.input) {
                    "commit"
                } else {
                    "compose"
                }
            }
            "punctuation" => {
                return match punctuation_for_reading(scenario.input) {
                    Some(ch) => ch.to_string(),
                    None => "none".to_string(),
                }
            }
            "compose_punctuation" => {
                if should_compose_punctuation(scenario.input, false) {
                    "compose"
                } else {
                    "commit"
                }
            }
            "compose_punctuation_pending" => {
                if should_compose_punctuation(scenario.input, true) {
                    "compose"
                } else {
                    "commit"
                }
            }
            "digit" => {
                if is_composition_digit(scenario.input) {
                    "compose"
                } else {
                    "commit"
                }
            }
            "translation_literal" => {
                if is_translation_literal_char(scenario.input) {
                    "literal"
                } else {
                    "plain"
                }
            }
            "width" => match symbol_default_fullwidth(&scenario.input.to_string()) {
                Some(true) => "full",
                Some(false) => "half",
                None => "none",
            },
            other => panic!("cases.tsv:{}: unknown operation {other}", scenario.line),
        };
        outcome.to_string()
    }

    #[test]
    fn shared_input_policy_matches_every_scenario() {
        let scenarios = scenarios();
        assert!(
            scenarios.len() >= 40,
            "cases.tsv should cover the whole policy surface"
        );
        let mut seen = std::collections::HashSet::new();
        for scenario in &scenarios {
            assert!(
                seen.insert(scenario.id.clone()),
                "cases.tsv:{}: duplicate id {}",
                scenario.line,
                scenario.id
            );
            assert_eq!(
                outcome(scenario),
                scenario.expect,
                "cases.tsv:{}: {} ({:?})",
                scenario.line,
                scenario.id,
                scenario.input
            );
        }
    }

    #[test]
    fn every_operation_in_the_table_is_exercised() {
        let operations: std::collections::HashSet<String> = scenarios()
            .iter()
            .map(|scenario| scenario.operation.clone())
            .collect();
        for expected in [
            "immediate",
            "punctuation",
            "compose_punctuation",
            "compose_punctuation_pending",
            "digit",
            "translation_literal",
            "width",
        ] {
            assert!(operations.contains(expected), "no cases for {expected}");
        }
    }

    #[test]
    fn width_defaults_have_no_duplicate_symbols() {
        let mut symbols: Vec<&str> = CHARACTER_WIDTH_SYMBOL_DEFAULTS
            .iter()
            .map(|(symbol, _)| *symbol)
            .collect();
        symbols.sort_unstable();
        let count = symbols.len();
        symbols.dedup();
        assert_eq!(symbols.len(), count);
    }

    #[test]
    fn repeated_identical_inference_does_not_manufacture_stability() {
        let mut snapshots = Vec::new();
        let mut history = VecDeque::new();
        for _ in 0..8 {
            record_live_conversion_prefix_snapshot(
                "watashiwagakkouni",
                "わたしはがっこうに",
                "私は学校に",
                "",
                &mut snapshots,
                &mut history,
            );
        }
        assert_eq!(history.len(), 1);
        assert!(find_auto_commit_live_conversion_snapshot(
            "watashiwagakkouniittebennkyoushi",
            "わたしはがっこうにいってべんきょうし",
            "私は学校に行って勉強し",
            "",
            &snapshots,
            &history
        )
        .is_none());
    }

    #[test]
    fn pending_n_and_consonant_boundaries_cannot_be_committed() {
        assert!(raw_input_has_pending_romaji_tail("gakkoun", "がっこうん"));
        assert!(!raw_input_has_pending_romaji_tail("gakkounn", "がっこうん"));
        assert!(raw_input_split_breaks_romaji("nya", 1));
        assert!(raw_input_split_breaks_romaji("kya", 1));
        assert!(!raw_input_split_breaks_romaji("kan'i", 4));
    }

    #[test]
    fn natural_boundaries_preserve_inflections_and_supplementary_kanji() {
        assert!(!is_natural_auto_commit_prefix(
            "私は学校に行",
            "私は学校に行って勉強する"
        ));
        assert!(!is_natural_auto_commit_prefix(
            "大気に飛び",
            "大気に飛び交う可能性"
        ));
        assert!(!is_natural_auto_commit_prefix(
            "今すぐ取り",
            "今すぐ取り戻す"
        ));
        assert!(!is_natural_auto_commit_prefix(
            "技術を持ち",
            "技術を持ち合わせる"
        ));
        assert!(is_natural_auto_commit_prefix(
            "私は学校に",
            "私は学校に行って勉強する"
        ));
        assert!(is_natural_auto_commit_prefix(
            "私は学校に行って",
            "私は学校に行って勉強する"
        ));
        assert!(is_kanji_or_katakana_char('𠮷'));
    }

    const AUTO_COMMIT_TSV: &str =
        include_str!("../../../../tests/input-policy/auto-commit-cases.tsv");

    struct AutoCommitScenario {
        id: String,
        reading: String,
        preview: String,
        history: Vec<LiveConversionPrefixSnapshot>,
        expect: String,
        line: usize,
    }

    fn auto_commit_scenarios() -> Vec<AutoCommitScenario> {
        AUTO_COMMIT_TSV
            .lines()
            .enumerate()
            .filter_map(|(index, line)| {
                let line = line.trim_end_matches('\r');
                if line.trim().is_empty() || line.starts_with('#') {
                    return None;
                }
                let columns: Vec<&str> = line.split('\t').collect();
                assert_eq!(
                    columns.len(),
                    5,
                    "auto-commit-cases.tsv:{}: expected 5 tab-separated columns",
                    index + 1
                );
                let reading = columns[1];
                let preview = columns[2];
                let history = columns[3]
                    .split(';')
                    .map(|entry| {
                        let (count, surface) = entry.split_once(':').unwrap_or_else(|| {
                            panic!("auto-commit-cases.tsv:{}: bad history entry '{entry}'", index + 1)
                        });
                        let count: usize = count.trim().parse().unwrap_or_else(|_| {
                            panic!("auto-commit-cases.tsv:{}: bad reading count in '{entry}'", index + 1)
                        });
                        assert!(
                            count <= reading.chars().count(),
                            "auto-commit-cases.tsv:{}: reading count {count} exceeds '{reading}'",
                            index + 1
                        );
                        assert!(
                            preview.starts_with(surface),
                            "auto-commit-cases.tsv:{}: surface '{surface}' is not a prefix of the preview",
                            index + 1
                        );
                        LiveConversionPrefixSnapshot {
                            raw_input_len: count,
                            hiragana_len: count,
                            preview: surface.to_string(),
                        }
                    })
                    .collect::<Vec<_>>();
                assert_eq!(
                    history.last().map(|snapshot| snapshot.preview.as_str()),
                    Some(preview),
                    "auto-commit-cases.tsv:{}: the last history surface must equal the preview",
                    index + 1
                );
                Some(AutoCommitScenario {
                    id: columns[0].to_string(),
                    reading: reading.to_string(),
                    preview: preview.to_string(),
                    history,
                    expect: columns[4].to_string(),
                    line: index + 1,
                })
            })
            .collect()
    }

    /// Runs the shared rule the way the bridge does: history entries are appended
    /// in order, deduplicated by surface, and capped at the stable-step window, so a
    /// repeated preview cannot count as separate evidence.
    fn run_auto_commit(scenario: &AutoCommitScenario) -> String {
        let mut history = VecDeque::new();
        for snapshot in &scenario.history {
            push_live_conversion_history(&mut history, &snapshot.preview);
        }
        let found = find_auto_commit_live_conversion_snapshot(
            &scenario.reading,
            &scenario.reading,
            &scenario.preview,
            "",
            &scenario.history,
            &history,
        );
        match found {
            Some(snapshot) => format!("{}:{}", snapshot.hiragana_len, snapshot.preview),
            None => "none".to_string(),
        }
    }

    #[test]
    fn shared_auto_commit_matches_every_scenario() {
        let scenarios = auto_commit_scenarios();
        assert!(
            scenarios.len() >= 6,
            "auto-commit-cases.tsv should cover the commit and no-commit paths"
        );
        let mut seen = std::collections::HashSet::new();
        for scenario in &scenarios {
            assert!(
                seen.insert(scenario.id.clone()),
                "auto-commit-cases.tsv:{}: duplicate id {}",
                scenario.line,
                scenario.id
            );
            assert_eq!(
                run_auto_commit(scenario),
                scenario.expect,
                "auto-commit-cases.tsv:{}: {}",
                scenario.line,
                scenario.id
            );
        }
    }

    /// The commit target must be the longest stable prefix, not merely a stable one.
    #[test]
    fn auto_commit_prefers_the_longest_stable_prefix() {
        let scenario = auto_commit_scenarios()
            .into_iter()
            .find(|scenario| scenario.id == "auto_commit_stable_clause")
            .expect("auto_commit_stable_clause scenario");
        let result = run_auto_commit(&scenario);
        let prefix_length: usize = result
            .split(':')
            .next()
            .and_then(|value| value.parse().ok())
            .expect("reading length");
        for snapshot in &scenario.history {
            if snapshot.hiragana_len > prefix_length
                && is_natural_auto_commit_prefix(&snapshot.preview, &scenario.preview)
            {
                assert!(
                    !scenario
                        .history
                        .iter()
                        .all(|entry| entry.preview.starts_with(&snapshot.preview)),
                    "a longer natural prefix should have won: {}",
                    snapshot.preview
                );
            }
        }
    }

    #[test]
    fn cursor_classification_and_insert_rules_cover_all_positions() {
        assert_eq!(classify_cursor_position(0, 0), CursorPositionKind::Empty);
        assert_eq!(classify_cursor_position(0, 4), CursorPositionKind::AtStart);
        assert_eq!(
            classify_cursor_position(2, 4),
            CursorPositionKind::MidSentence
        );
        assert_eq!(classify_cursor_position(4, 4), CursorPositionKind::AtEnd);
        assert_eq!(classify_cursor_position(9, 4), CursorPositionKind::AtEnd);

        assert!(!is_cursor_mid_sentence(0, 4));
        assert!(is_cursor_mid_sentence(2, 4));
        assert!(!is_cursor_mid_sentence(4, 4));

        assert!(should_insert_at_cursor_while_previewing(0, 4));
        assert!(should_insert_at_cursor_while_previewing(2, 4));
        assert!(!should_insert_at_cursor_while_previewing(4, 4));
        assert!(!should_insert_at_cursor_while_previewing(0, 0));

        assert!(!should_allow_punctuation_commit(0, 4));
        assert!(!should_allow_punctuation_commit(2, 4));
        assert!(should_allow_punctuation_commit(4, 4));

        assert!(!should_run_live_assist_at_cursor(2, 4, true));
        assert!(!should_run_live_assist_at_cursor(4, 4, true));
        assert!(should_run_live_assist_at_cursor(4, 4, false));
    }

    #[test]
    fn cursor_commit_and_delete_decisions_preserve_suffix_and_bounds() {
        assert_eq!(
            decide_cursor_commit_action(true, false),
            CursorCommitAction::CommitWholeAndEnd
        );
        assert_eq!(
            decide_cursor_commit_action(false, true),
            CursorCommitAction::CommitSuffixAndEnd
        );
        assert_eq!(
            decide_cursor_commit_action(true, true),
            CursorCommitAction::CommitPrefixKeepSuffix
        );

        assert_eq!(decide_backspace_action(0, 0), CursorDeleteAction::NoOp);
        assert_eq!(decide_backspace_action(0, 3), CursorDeleteAction::NoOp);
        assert_eq!(
            decide_backspace_action(1, 3),
            CursorDeleteAction::DeleteBackward
        );
        assert_eq!(
            decide_backspace_action(3, 3),
            CursorDeleteAction::DeleteBackward
        );

        assert_eq!(decide_delete_forward_action(0, 0), CursorDeleteAction::NoOp);
        assert_eq!(
            decide_delete_forward_action(0, 3),
            CursorDeleteAction::DeleteForward
        );
        assert_eq!(
            decide_delete_forward_action(2, 3),
            CursorDeleteAction::DeleteForward
        );
        assert_eq!(decide_delete_forward_action(3, 3), CursorDeleteAction::NoOp);
    }

    #[test]
    fn prefix_min_margin_milli_handles_boundaries_and_invalid_inputs() {
        let prefix = "私は学校に";
        let prefix_bytes = prefix.len() as u32;
        let margins = vec![
            TokenLogitMargin {
                byte_end: 3,
                margin_milli: 4200,
            },
            TokenLogitMargin {
                byte_end: 6,
                margin_milli: 3100,
            },
            TokenLogitMargin {
                byte_end: prefix_bytes,
                margin_milli: 2600,
            },
            TokenLogitMargin {
                byte_end: prefix_bytes + 6,
                margin_milli: 500,
            },
        ];
        assert_eq!(prefix_min_margin_milli(prefix, &margins), Some(2600));
        assert_eq!(prefix_min_margin_milli("", &margins), None);
        assert_eq!(prefix_min_margin_milli(prefix, &[]), None);
        assert_eq!(
            prefix_min_margin_milli("私は学校に行って", &margins[..2]),
            None
        );
        let non_monotonic = vec![
            TokenLogitMargin {
                byte_end: 6,
                margin_milli: 3000,
            },
            TokenLogitMargin {
                byte_end: 6,
                margin_milli: 2000,
            },
            TokenLogitMargin {
                byte_end: prefix_bytes,
                margin_milli: 2500,
            },
        ];
        assert_eq!(prefix_min_margin_milli(prefix, &non_monotonic), None);
    }

    #[test]
    fn auto_commit_policy_respects_modes_margin_hold_and_early_tiers() {
        let reading = "わたしはがっこうにいく"; // 11 kana
        let preview = "私は学校に行く";
        let prefix = "私は学校に"; // 6 kana
        let rem_kana = 5usize;
        assert_eq!(reading.chars().count(), 6 + rem_kana);

        let snapshots = vec![
            LiveConversionPrefixSnapshot {
                raw_input_len: 17,
                hiragana_len: 6,
                preview: prefix.to_string(),
            },
            LiveConversionPrefixSnapshot {
                raw_input_len: 19,
                hiragana_len: 8,
                preview: "私は学校にい".to_string(),
            },
            LiveConversionPrefixSnapshot {
                raw_input_len: 21,
                hiragana_len: 10,
                preview: "私は学校に行".to_string(),
            },
        ];
        let mut history = VecDeque::new();
        for s in &snapshots {
            push_live_conversion_history(&mut history, &s.preview);
        }

        let high_margins = vec![
            TokenLogitMargin {
                byte_end: prefix.len() as u32,
                margin_milli: 2400,
            },
            TokenLogitMargin {
                byte_end: preview.len() as u32,
                margin_milli: 3000,
            },
        ];
        // Off never commits.
        assert!(find_auto_commit_live_conversion_snapshot_with_policy(
            "watashiwagakkouniiku",
            reading,
            preview,
            "",
            &snapshots,
            &history,
            AutoCommitLiveConversionMode::Off,
            &high_margins,
        )
        .is_none());

        // Margin 2400 with total=11, commit=6, rem=5, stable=2 (after prefix) -> Fast commits, Standard waits.
        assert!(find_auto_commit_live_conversion_snapshot_with_policy(
            "watashiwagakkouniiku",
            reading,
            preview,
            "",
            &snapshots,
            &history,
            AutoCommitLiveConversionMode::Standard,
            &high_margins,
        )
        .is_none());
        let fast_hit = find_auto_commit_live_conversion_snapshot_with_policy(
            "watashiwagakkouniiku",
            reading,
            preview,
            "",
            &snapshots,
            &history,
            AutoCommitLiveConversionMode::Fast,
            &high_margins,
        );
        assert_eq!(fast_hit.as_ref().map(|s| s.preview.as_str()), Some(prefix));

        // Standard fast tier commits at total>=12, commit>=6, rem>=4, stable>=3, margin>=2500.
        let reading12 = "わたしはがっこうにいくよ";
        let preview12 = "私は学校に行くよ";
        let mut hist3 = history.clone();
        push_live_conversion_history(&mut hist3, preview12);
        let std_margins = vec![
            TokenLogitMargin {
                byte_end: prefix.len() as u32,
                margin_milli: 2600,
            },
            TokenLogitMargin {
                byte_end: preview12.len() as u32,
                margin_milli: 3000,
            },
        ];
        let std_hit = find_auto_commit_live_conversion_snapshot_with_policy(
            "watashiwagakkouniikuyo",
            reading12,
            preview12,
            "",
            &snapshots,
            &hist3,
            AutoCommitLiveConversionMode::Standard,
            &std_margins,
        );
        assert_eq!(std_hit.as_ref().map(|s| s.preview.as_str()), Some(prefix));

        // Low-confidence hold: even long + stable (5+ steps) is held when min_margin < 1200.
        let long_reading = "こうせいをみなおしてからていしゅつする";
        let long_preview = "構成を見直してから提出する";
        let long_prefix = "構成を見直してから"; // 10 kana
        let long_snaps = vec![
            LiveConversionPrefixSnapshot {
                raw_input_len: 10,
                hiragana_len: 10,
                preview: long_prefix.to_string(),
            },
            LiveConversionPrefixSnapshot {
                raw_input_len: 12,
                hiragana_len: 12,
                preview: "構成を見直してからて".to_string(),
            },
            LiveConversionPrefixSnapshot {
                raw_input_len: 13,
                hiragana_len: 13,
                preview: "構成を見直してからてい".to_string(),
            },
            LiveConversionPrefixSnapshot {
                raw_input_len: 15,
                hiragana_len: 15,
                preview: "構成を見直してからていしゅ".to_string(),
            },
            LiveConversionPrefixSnapshot {
                raw_input_len: 17,
                hiragana_len: 17,
                preview: "構成を見直してから提出".to_string(),
            },
            LiveConversionPrefixSnapshot {
                raw_input_len: 19,
                hiragana_len: 19,
                preview: long_preview.to_string(),
            },
        ];
        let mut long_hist = VecDeque::new();
        for s in &long_snaps {
            push_live_conversion_history(&mut long_hist, &s.preview);
        }
        let low_margins = vec![
            TokenLogitMargin {
                byte_end: 3,
                margin_milli: 800,
            },
            TokenLogitMargin {
                byte_end: long_preview.len() as u32,
                margin_milli: 4000,
            },
        ];
        assert!(find_auto_commit_live_conversion_snapshot_with_policy(
            long_reading,
            long_reading,
            long_preview,
            "",
            &long_snaps,
            &long_hist,
            AutoCommitLiveConversionMode::Fast,
            &low_margins,
        )
        .is_none());
        // Without margins (fallback), the baseline thresholds still commit it.
        assert!(find_auto_commit_live_conversion_snapshot_with_policy(
            long_reading,
            long_reading,
            long_preview,
            "",
            &long_snaps,
            &long_hist,
            AutoCommitLiveConversionMode::Standard,
            &[],
        )
        .is_some());

        // Punctuation early commit at total>=10, commit>=5, rem>=3, stable>=2, margin>=1500.
        let punct_reading = "きょうはあめ、あしたは"; // 11 kana
        let punct_preview = "今日は雨、明日は";
        let punct_prefix = "今日は雨、"; // 6 kana
        let punct_snaps = vec![
            LiveConversionPrefixSnapshot {
                raw_input_len: 6,
                hiragana_len: 6,
                preview: punct_prefix.to_string(),
            },
            LiveConversionPrefixSnapshot {
                raw_input_len: 9,
                hiragana_len: 9,
                preview: "今日は雨、あした".to_string(),
            },
            LiveConversionPrefixSnapshot {
                raw_input_len: 11,
                hiragana_len: 11,
                preview: punct_preview.to_string(),
            },
        ];
        let mut punct_hist = VecDeque::new();
        for s in &punct_snaps {
            push_live_conversion_history(&mut punct_hist, &s.preview);
        }
        let punct_margins = vec![TokenLogitMargin {
            byte_end: punct_preview.len() as u32,
            margin_milli: 1600,
        }];
        let punct_hit = find_auto_commit_live_conversion_snapshot_with_policy(
            punct_reading,
            punct_reading,
            punct_preview,
            "",
            &punct_snaps,
            &punct_hist,
            AutoCommitLiveConversionMode::Standard,
            &punct_margins,
        );
        assert_eq!(
            punct_hit.as_ref().map(|s| s.preview.as_str()),
            Some(punct_prefix)
        );
    }
}
