//! Input and stability policy shared by the Windows client and Android host.
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
    candidate_prefix.chars().any(is_kanji_or_katakana_char)
        && is_hiragana_char(last_char)
        && is_kanji_or_katakana_char(next_char)
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

pub fn find_auto_commit_live_conversion_snapshot(
    raw_input: &str,
    raw_hiragana: &str,
    preview: &str,
    suffix: &str,
    snapshots: &[LiveConversionPrefixSnapshot],
    history: &VecDeque<String>,
) -> Option<LiveConversionPrefixSnapshot> {
    let total_hiragana = raw_hiragana.chars().count();
    if total_hiragana < LIVE_CONVERSION_AUTO_COMMIT_MIN_TOTAL_HIRAGANA
        || !suffix.is_empty()
        || raw_input_has_pending_romaji_tail(raw_input, raw_hiragana)
        || history.len() < LIVE_CONVERSION_AUTO_COMMIT_STABLE_STEPS
    {
        return None;
    }
    snapshots
        .iter()
        .filter(|snap| {
            snap.hiragana_len >= LIVE_CONVERSION_AUTO_COMMIT_MIN_COMMIT_HIRAGANA
                && total_hiragana.saturating_sub(snap.hiragana_len)
                    >= LIVE_CONVERSION_AUTO_COMMIT_MIN_REMAINING_HIRAGANA
                && !raw_input_split_breaks_romaji(raw_input, snap.raw_input_len)
                && is_natural_auto_commit_prefix(&snap.preview, preview)
                && history.iter().all(|entry| {
                    entry.starts_with(&snap.preview) && entry.len() > snap.preview.len()
                })
        })
        .max_by_key(|snap| snap.hiragana_len)
        .cloned()
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
        assert!(is_natural_auto_commit_prefix(
            "私は学校に",
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
}
