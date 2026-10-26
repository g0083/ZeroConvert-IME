//! Input and stability policy shared by the Windows client and Android host.
use std::collections::VecDeque;

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
    if history.back().is_none_or(|last| last != preview) {
        history.push_back(preview.to_string());
        while history.len() > LIVE_CONVERSION_AUTO_COMMIT_STABLE_STEPS {
            history.pop_front();
        }
    }
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
}
