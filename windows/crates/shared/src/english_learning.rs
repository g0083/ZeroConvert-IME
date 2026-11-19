//! Shared English word and completion learning store for Windows and Android.
//!
//! Implements Gboard-style promotion rules:
//! - Explicit candidate selection promotes on the first commit and updates the
//!   typed-prefix override.
//! - Existing static dictionary words promote on the first commit.
//! - Out-of-vocabulary (OOV) words committed directly require two commits
//!   before appearing in suggestions, avoiding one-off typos.

use serde::{Deserialize, Serialize};
use std::cmp::Reverse;
use std::collections::{HashMap, HashSet};
use std::fs;
use std::path::PathBuf;

pub const ENGLISH_LEARNING_FILENAME: &str = "english-learning.json";
pub const ENGLISH_LEARNING_VERSION: u32 = 1;
pub const MAX_LEARNED_WORDS: usize = 500;
pub const MAX_PREFIX_OVERRIDES: usize = 500;
pub const MIN_WORD_LEN: usize = 2;
pub const MAX_WORD_LEN: usize = 64;
pub const OOV_PROMOTION_COMMIT_THRESHOLD: u32 = 2;

/// Common email domains suggested in Latin/English mode when typing `@`.
pub const DEFAULT_EMAIL_DOMAINS: &[&str] = &[
    "@gmail.com",
    "@icloud.com",
    "@yahoo.co.jp",
    "@outlook.jp",
    "@outlook.com",
    "@docomo.ne.jp",
    "@ezweb.ne.jp",
    "@softbank.ne.jp",
    "@au.com",
    "@hotmail.com",
    "@i.softbank.jp",
    "@ymobile.ne.jp",
    "@uqmobile.jp",
    "@rakuten.jp",
    "@live.jp",
    "@me.com",
    "@mac.com",
    "@proton.me",
    "@protonmail.com",
    "@yahoo.com",
    "@nifty.com",
    "@biglobe.ne.jp",
    "@so-net.ne.jp",
    "@ocn.ne.jp",
];

fn default_version() -> u32 {
    ENGLISH_LEARNING_VERSION
}

/// A committed English word tracked by the learning store.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LearnedEnglishWord {
    /// Canonical surface casing (for example `ZeroConvert`, `API`, or `hello`).
    pub word: String,
    pub count: u32,
    #[serde(default)]
    pub explicit_count: u32,
    #[serde(default)]
    pub in_dictionary: bool,
    #[serde(default)]
    pub updated_at_ms: u64,
}

impl LearnedEnglishWord {
    /// Whether this entry should be suggested during completion.
    pub fn is_promoted(&self) -> bool {
        self.explicit_count >= 1
            || (self.in_dictionary && self.count >= 1)
            || self.count >= OOV_PROMOTION_COMMIT_THRESHOLD
    }

    /// Ranking weight favoring explicit candidate selections over implicit commits.
    pub fn effective_weight(&self) -> u64 {
        u64::from(self.count).saturating_add(u64::from(self.explicit_count).saturating_mul(2))
    }
}

/// Maps a typed prefix (lowercase) to an explicitly selected target word.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PrefixOverride {
    pub prefix: String,
    pub word: String,
    pub count: u32,
    #[serde(default)]
    pub updated_at_ms: u64,
}

/// Persisted / serialized view of the English learning store.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EnglishLearningSnapshot {
    #[serde(default = "default_version")]
    pub version: u32,
    #[serde(default)]
    pub words: Vec<LearnedEnglishWord>,
    #[serde(default)]
    pub prefix_overrides: Vec<PrefixOverride>,
}

impl Default for EnglishLearningSnapshot {
    fn default() -> Self {
        Self {
            version: ENGLISH_LEARNING_VERSION,
            words: Vec::new(),
            prefix_overrides: Vec::new(),
        }
    }
}

/// Ranked completion candidate produced by merging learned and static words.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EnglishSuggestion {
    pub text: String,
    pub learned: bool,
    /// Lowercase dictionary key of the learned word (`""` for unlearned static entries).
    pub key: String,
}

/// Whether `ch` can continue a `@domain` composition in Latin/English mode.
pub fn is_email_domain_continuation_char(ch: char) -> bool {
    ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_')
}

/// Whether `prefix` is an active `@...` email-domain completion prefix.
pub fn is_email_domain_prefix(prefix: &str) -> bool {
    let Some(rest) = prefix.strip_prefix('@') else {
        return false;
    };
    (1..=MAX_WORD_LEN).contains(&prefix.len())
        && rest.chars().all(is_email_domain_continuation_char)
}

/// Whether `word` is a complete `@domain.tld` candidate eligible for learning.
pub fn is_valid_email_domain(word: &str) -> bool {
    let Some(domain) = word.strip_prefix('@') else {
        return false;
    };
    if !(4..=MAX_WORD_LEN).contains(&word.len()) {
        return false;
    }
    if domain.starts_with('.')
        || domain.ends_with('.')
        || domain.starts_with('-')
        || domain.ends_with('-')
        || domain.contains("..")
        || !domain.contains('.')
    {
        return false;
    }
    domain
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'-' || b == b'_')
}

/// Whether `word` matches one of the built-in `DEFAULT_EMAIL_DOMAINS`.
pub fn is_default_email_domain(word: &str) -> bool {
    if !word.starts_with('@') {
        return false;
    }
    let lower = word.to_ascii_lowercase();
    DEFAULT_EMAIL_DOMAINS.contains(&lower.as_str())
}

fn is_valid_ascii_word(word: &str) -> bool {
    if word.starts_with('@') {
        is_valid_email_domain(word)
    } else {
        (MIN_WORD_LEN..=MAX_WORD_LEN).contains(&word.len())
            && word.bytes().all(|b| b.is_ascii_alphabetic())
    }
}

fn is_valid_ascii_prefix(prefix: &str) -> bool {
    if prefix.starts_with('@') {
        is_email_domain_prefix(prefix)
    } else {
        (1..=MAX_WORD_LEN).contains(&prefix.len())
            && prefix.bytes().all(|b| b.is_ascii_alphabetic())
    }
}

fn is_mixed_internal_case(word: &str) -> bool {
    let bytes = word.as_bytes();
    let has_lower = bytes.iter().any(|b| b.is_ascii_lowercase());
    let has_internal_upper = bytes.iter().skip(1).any(|b| b.is_ascii_uppercase());
    has_lower && has_internal_upper
}

fn is_short_all_uppercase(word: &str) -> bool {
    (2..=5).contains(&word.len()) && word.bytes().all(|b| b.is_ascii_uppercase())
}

fn is_distinctive_dictionary_casing(word: &str) -> bool {
    is_mixed_internal_case(word) || is_short_all_uppercase(word)
}

fn is_plain_title_case(word: &str) -> bool {
    let bytes = word.as_bytes();
    bytes.len() >= 2
        && bytes[0].is_ascii_uppercase()
        && bytes[1..].iter().all(|b| b.is_ascii_lowercase())
}

fn capitalize_first(word: &str) -> String {
    let mut chars = word.chars();
    let Some(first) = chars.next() else {
        return String::new();
    };
    let mut out = String::with_capacity(word.len());
    out.push(first.to_ascii_uppercase());
    out.push_str(chars.as_str());
    out
}

/// Formats a learned canonical word to match the user's current `prefix` casing
/// unless `canonical` carries distinctive internal/acronym capitalization.
pub fn format_learned_candidate(canonical: &str, prefix: &str) -> String {
    if canonical.starts_with('@') || prefix.starts_with('@') {
        return canonical.to_ascii_lowercase();
    }
    if prefix.len() >= 2 && prefix.bytes().all(|b| b.is_ascii_uppercase()) {
        return canonical.to_ascii_uppercase();
    }
    let prefix_lower = prefix.to_ascii_lowercase();
    if canonical.bytes().any(|b| b.is_ascii_uppercase()) {
        if prefix.bytes().all(|b| b.is_ascii_lowercase())
            || canonical.starts_with(prefix)
            || (prefix.len() == 1 && prefix.as_bytes()[0].is_ascii_uppercase())
        {
            return canonical.to_owned();
        }
        if canonical.to_ascii_lowercase().starts_with(&prefix_lower) {
            let mut out = canonical.to_owned();
            out.replace_range(..prefix.len(), prefix);
            return out;
        }
        return canonical.to_owned();
    }

    if canonical.to_ascii_lowercase().starts_with(&prefix_lower) {
        let mut out = canonical.to_owned();
        out.replace_range(..prefix.len(), prefix);
        return out;
    }

    if prefix
        .as_bytes()
        .first()
        .is_some_and(|b| b.is_ascii_uppercase())
    {
        capitalize_first(canonical)
    } else {
        canonical.to_owned()
    }
}

/// Formats a static dictionary word to match the typed `prefix` casing.
pub fn format_static_candidate(word: &str, prefix: &str) -> String {
    if word.starts_with('@') || prefix.starts_with('@') {
        return word.to_ascii_lowercase();
    }
    if prefix.len() >= 2 && prefix.bytes().all(|b| b.is_ascii_uppercase()) {
        return word.to_ascii_uppercase();
    }
    let prefix_lower = prefix.to_ascii_lowercase();
    if word.to_ascii_lowercase().starts_with(&prefix_lower) {
        let mut out = word.to_owned();
        out.replace_range(..prefix.len(), prefix);
        return out;
    }
    if prefix
        .as_bytes()
        .first()
        .is_some_and(|b| b.is_ascii_uppercase())
    {
        capitalize_first(word)
    } else {
        word.to_owned()
    }
}

/// In-memory and JSON-backed store for learned English words and prefix overrides.
#[derive(Debug, Default, Clone)]
pub struct EnglishLearningStore {
    words: HashMap<String, LearnedEnglishWord>,
    prefix_overrides: HashMap<String, PrefixOverride>,
    path: Option<PathBuf>,
    loaded: bool,
}

impl EnglishLearningStore {
    pub fn new(path: Option<PathBuf>) -> Self {
        Self {
            words: HashMap::new(),
            prefix_overrides: HashMap::new(),
            path,
            loaded: false,
        }
    }

    pub fn from_snapshot(snapshot: EnglishLearningSnapshot) -> Self {
        let mut store = Self {
            words: HashMap::new(),
            prefix_overrides: HashMap::new(),
            path: None,
            loaded: true,
        };
        store.apply_snapshot(snapshot);
        store
    }

    pub fn is_loaded(&self) -> bool {
        self.loaded
    }

    pub fn snapshot(&self) -> EnglishLearningSnapshot {
        let mut words: Vec<LearnedEnglishWord> = self.words.values().cloned().collect();
        words.sort_by(|left, right| {
            right
                .updated_at_ms
                .cmp(&left.updated_at_ms)
                .then_with(|| left.word.cmp(&right.word))
        });
        let mut prefix_overrides: Vec<PrefixOverride> =
            self.prefix_overrides.values().cloned().collect();
        prefix_overrides.sort_by(|left, right| {
            right
                .updated_at_ms
                .cmp(&left.updated_at_ms)
                .then_with(|| left.prefix.cmp(&right.prefix))
        });
        EnglishLearningSnapshot {
            version: ENGLISH_LEARNING_VERSION,
            words,
            prefix_overrides,
        }
    }

    fn apply_snapshot(&mut self, snapshot: EnglishLearningSnapshot) {
        self.words.clear();
        self.prefix_overrides.clear();
        for entry in snapshot.words {
            let trimmed = entry.word.trim().to_owned();
            if !is_valid_ascii_word(&trimmed) || entry.count == 0 {
                continue;
            }
            let key = trimmed.to_ascii_lowercase();
            let mut normalized = entry;
            normalized.word = trimmed;
            self.words.insert(key, normalized);
        }
        for entry in snapshot.prefix_overrides {
            let prefix = entry.prefix.trim();
            let word = entry.word.trim();
            if !is_valid_ascii_prefix(prefix) || !is_valid_ascii_word(word) || entry.count == 0 {
                continue;
            }
            let key = prefix.to_ascii_lowercase();
            self.prefix_overrides.insert(
                key.clone(),
                PrefixOverride {
                    prefix: key,
                    word: word.to_owned(),
                    count: entry.count,
                    updated_at_ms: entry.updated_at_ms,
                },
            );
        }
        self.trim();
    }

    pub fn load(&mut self) {
        if self.loaded {
            return;
        }
        self.loaded = true;
        let Some(path) = self.path.clone() else {
            return;
        };
        let Ok(content) = fs::read_to_string(&path) else {
            return;
        };
        let Ok(parsed) = serde_json::from_str::<EnglishLearningSnapshot>(&content) else {
            return;
        };
        self.apply_snapshot(parsed);
    }

    pub fn save(&self) -> Result<(), String> {
        let Some(path) = self.path.as_ref() else {
            return Ok(());
        };
        let snapshot = self.snapshot();
        let serialized =
            serde_json::to_string_pretty(&snapshot).map_err(|error| error.to_string())?;
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|error| error.to_string())?;
        }
        let temporary = path.with_extension("json.tmp");
        fs::write(&temporary, serialized.as_bytes()).map_err(|error| error.to_string())?;
        fs::rename(&temporary, path).map_err(|error| error.to_string())
    }

    fn canonical_word_casing(&self, word: &str, word_lower: &str, in_dictionary: bool) -> String {
        if word.starts_with('@') {
            return word_lower.to_owned();
        }
        if !in_dictionary {
            return word.to_owned();
        }
        if is_distinctive_dictionary_casing(word) {
            return word.to_owned();
        }
        if is_plain_title_case(word) {
            if let Some(existing) = self.words.get(word_lower) {
                if is_distinctive_dictionary_casing(&existing.word) {
                    return existing.word.clone();
                }
            }
            return word_lower.to_owned();
        }
        word_lower.to_owned()
    }

    /// Records a committed English word, returning `true` when the store changed.
    pub fn record(
        &mut self,
        prefix: &str,
        word: &str,
        explicit_selection: bool,
        in_dictionary: bool,
        now_ms: u64,
    ) -> bool {
        let word = word.trim();
        if !is_valid_ascii_word(word) {
            return false;
        }
        let clean_prefix = prefix.trim();
        let clean_prefix = if is_valid_ascii_prefix(clean_prefix) {
            clean_prefix
        } else {
            ""
        };
        let word_lower = word.to_ascii_lowercase();
        let canonical = self.canonical_word_casing(word, &word_lower, in_dictionary);

        let existing = self.words.get(&word_lower);
        let prev_updated = existing.map_or(0, |entry| entry.updated_at_ms);
        let updated_at_ms = now_ms.max(prev_updated.saturating_add(1));
        let count = existing.map_or(1, |entry| entry.count.saturating_add(1));
        let explicit_count = existing
            .map_or(0, |entry| entry.explicit_count)
            .saturating_add(u32::from(explicit_selection));
        let combined_in_dict = in_dictionary || existing.is_some_and(|entry| entry.in_dictionary);

        self.words.insert(
            word_lower.clone(),
            LearnedEnglishWord {
                word: canonical.clone(),
                count,
                explicit_count,
                in_dictionary: combined_in_dict,
                updated_at_ms,
            },
        );

        let prefix_lower = clean_prefix.to_ascii_lowercase();
        if explicit_selection
            && !prefix_lower.is_empty()
            && (prefix_lower != word_lower || canonical != clean_prefix)
        {
            let existing_override = self.prefix_overrides.get(&prefix_lower);
            let prev_override_updated = existing_override.map_or(0, |entry| entry.updated_at_ms);
            let override_updated_at_ms = now_ms.max(prev_override_updated.saturating_add(1));
            let override_count = existing_override
                .filter(|entry| entry.word == canonical)
                .map_or(1, |entry| entry.count.saturating_add(1));
            self.prefix_overrides.insert(
                prefix_lower.clone(),
                PrefixOverride {
                    prefix: prefix_lower,
                    word: canonical,
                    count: override_count,
                    updated_at_ms: override_updated_at_ms,
                },
            );
        }

        self.trim();
        true
    }

    /// Imports legacy `prefix -> word` pairs (for example Android's older Prefs store).
    pub fn import_legacy_prefix_overrides(
        &mut self,
        pairs: &[(String, String)],
        now_ms: u64,
    ) -> usize {
        let mut imported = 0usize;
        for (index, (raw_prefix, raw_word)) in pairs.iter().enumerate() {
            let prefix = raw_prefix.trim();
            let word = raw_word.trim();
            if !is_valid_ascii_word(word) {
                continue;
            }
            let ts = now_ms.saturating_add(index as u64 + 1);
            if self.record(prefix, word, true, false, ts) {
                imported += 1;
            }
        }
        imported
    }

    fn trim(&mut self) {
        if self.words.len() > MAX_LEARNED_WORDS {
            let mut entries: Vec<(String, LearnedEnglishWord)> = self.words.drain().collect();
            entries.sort_by(|left, right| {
                right
                    .1
                    .is_promoted()
                    .cmp(&left.1.is_promoted())
                    .then_with(|| right.1.updated_at_ms.cmp(&left.1.updated_at_ms))
                    .then_with(|| left.0.cmp(&right.0))
            });
            entries.truncate(MAX_LEARNED_WORDS);
            self.words = entries.into_iter().collect();
        }
        if self.prefix_overrides.len() > MAX_PREFIX_OVERRIDES {
            let mut entries: Vec<(String, PrefixOverride)> =
                self.prefix_overrides.drain().collect();
            entries.sort_by(|left, right| {
                right
                    .1
                    .updated_at_ms
                    .cmp(&left.1.updated_at_ms)
                    .then_with(|| left.0.cmp(&right.0))
            });
            entries.truncate(MAX_PREFIX_OVERRIDES);
            self.prefix_overrides = entries.into_iter().collect();
        }
    }

    /// Removes one learned word and all prefix overrides pointing to it.
    pub fn forget(&mut self, word: &str, _prefix: Option<&str>) -> bool {
        let word_lower = word.trim().to_ascii_lowercase();
        if word_lower.is_empty() {
            return false;
        }
        let mut removed = self.words.remove(&word_lower).is_some();
        let before = self.prefix_overrides.len();
        self.prefix_overrides
            .retain(|_, entry| !entry.word.eq_ignore_ascii_case(&word_lower));
        if self.prefix_overrides.len() != before {
            removed = true;
        }
        removed
    }

    /// Clears every learned word and prefix override.
    pub fn remove_all(&mut self) -> bool {
        if self.words.is_empty() && self.prefix_overrides.is_empty() {
            return false;
        }
        self.words.clear();
        self.prefix_overrides.clear();
        true
    }

    /// Number of distinct promoted lowercase words across words and prefix overrides.
    pub fn promoted_count(&self) -> usize {
        let mut distinct: HashSet<String> = self
            .words
            .iter()
            .filter(|(_, entry)| entry.is_promoted())
            .map(|(key, _)| key.clone())
            .collect();
        for entry in self.prefix_overrides.values() {
            distinct.insert(entry.word.to_ascii_lowercase());
        }
        distinct.len()
    }

    pub fn is_promoted_word(&self, word: &str) -> bool {
        let lower = word.trim().to_ascii_lowercase();
        if lower.is_empty() {
            return false;
        }
        self.words
            .get(&lower)
            .is_some_and(LearnedEnglishWord::is_promoted)
            || self
                .prefix_overrides
                .values()
                .any(|entry| entry.word.eq_ignore_ascii_case(&lower))
    }

    /// Promoted canonical words ordered by weight and recency, used for touch-typo recovery.
    pub fn promoted_words(&self) -> Vec<String> {
        let mut entries: Vec<(&String, &LearnedEnglishWord)> = self
            .words
            .iter()
            .filter(|(_, entry)| entry.is_promoted())
            .collect();
        entries.sort_by(|left, right| {
            right
                .1
                .effective_weight()
                .cmp(&left.1.effective_weight())
                .then_with(|| right.1.updated_at_ms.cmp(&left.1.updated_at_ms))
                .then_with(|| left.0.cmp(right.0))
        });

        let mut out = Vec::with_capacity(entries.len());
        let mut seen: HashSet<String> = HashSet::new();
        for (key, entry) in entries {
            if seen.insert(key.clone()) {
                out.push(entry.word.clone());
            }
        }
        let mut overrides: Vec<&PrefixOverride> = self.prefix_overrides.values().collect();
        overrides.sort_by(|left, right| {
            right
                .count
                .cmp(&left.count)
                .then_with(|| right.updated_at_ms.cmp(&left.updated_at_ms))
                .then_with(|| left.word.cmp(&right.word))
        });
        for entry in overrides {
            let key = entry.word.to_ascii_lowercase();
            if seen.insert(key) {
                out.push(entry.word.clone());
            }
        }
        out
    }

    /// Returns promoted learned suggestions matching `prefix`, prioritized by:
    /// 1) exact `prefix` override, then 2) promoted words matching prefix by weight and recency.
    pub fn suggest_learned(&self, prefix: &str, limit: usize) -> Vec<EnglishSuggestion> {
        if limit == 0 || !is_valid_ascii_prefix(prefix) {
            return Vec::new();
        }
        let prefix_lower = prefix.to_ascii_lowercase();
        let mut results = Vec::with_capacity(limit);

        if let Some(override_entry) = self.prefix_overrides.get(&prefix_lower) {
            let formatted = format_learned_candidate(&override_entry.word, prefix);
            if formatted != prefix {
                results.push(EnglishSuggestion {
                    text: formatted,
                    learned: true,
                    key: override_entry.word.to_ascii_lowercase(),
                });
            }
        }

        let mut matches: Vec<(&String, &LearnedEnglishWord)> = self
            .words
            .iter()
            .filter(|(key, entry)| entry.is_promoted() && key.starts_with(&prefix_lower))
            .collect();
        matches.sort_by(|left, right| {
            Reverse((left.1.effective_weight(), left.1.updated_at_ms))
                .cmp(&Reverse((
                    right.1.effective_weight(),
                    right.1.updated_at_ms,
                )))
                .then_with(|| left.0.cmp(right.0))
        });

        for (key, entry) in matches {
            if results.len() >= limit {
                break;
            }
            let formatted = format_learned_candidate(&entry.word, prefix);
            if formatted == prefix || results.iter().any(|existing| existing.text == formatted) {
                continue;
            }
            results.push(EnglishSuggestion {
                text: formatted,
                learned: true,
                key: key.clone(),
            });
        }

        results
    }

    /// Merges learned suggestions (when `read_learning` is true) ahead of `static_words`.
    pub fn merge_with_dictionary(
        &self,
        prefix: &str,
        static_words: &[&str],
        limit: usize,
        read_learning: bool,
    ) -> Vec<EnglishSuggestion> {
        if limit == 0 || !is_valid_ascii_prefix(prefix) {
            return Vec::new();
        }
        let mut results = if read_learning {
            self.suggest_learned(prefix, limit)
        } else {
            Vec::with_capacity(limit)
        };
        for &word in static_words {
            if results.len() >= limit {
                break;
            }
            let formatted = format_static_candidate(word, prefix);
            if formatted == prefix || results.iter().any(|existing| existing.text == formatted) {
                continue;
            }
            results.push(EnglishSuggestion {
                text: formatted,
                learned: false,
                key: String::new(),
            });
        }
        results
    }

    /// Suggests email domain completions matching a `@...` prefix.
    pub fn suggest_email_domains(
        &self,
        prefix: &str,
        limit: usize,
        read_learning: bool,
    ) -> Vec<EnglishSuggestion> {
        if limit == 0 || !is_email_domain_prefix(prefix) {
            return Vec::new();
        }
        let prefix_lower = prefix.to_ascii_lowercase();
        let static_domains: Vec<&str> = DEFAULT_EMAIL_DOMAINS
            .iter()
            .copied()
            .filter(|domain| domain.starts_with(&prefix_lower) && *domain != prefix_lower)
            .take(limit.saturating_add(8))
            .collect();
        self.merge_with_dictionary(prefix, &static_domains, limit, read_learning)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path(name: &str) -> PathBuf {
        let mut path = std::env::temp_dir();
        path.push(format!(
            "zeroconvert-english-learning-{name}-{}.json",
            std::process::id()
        ));
        path
    }

    #[test]
    fn oov_requires_two_direct_commits_unless_explicitly_selected() {
        let mut store = EnglishLearningStore::new(None);
        // 1st direct commit: pending only, not suggested yet (typo guard).
        assert!(store.record("ZeroConvert", "ZeroConvert", false, false, 100));
        assert!(!store.is_promoted_word("ZeroConvert"));
        assert!(store.suggest_learned("Zero", 8).is_empty());
        assert_eq!(store.promoted_count(), 0);

        // 2nd direct commit: promoted!
        assert!(store.record("ZeroConvert", "ZeroConvert", false, false, 200));
        assert!(store.is_promoted_word("ZeroConvert"));
        assert_eq!(store.promoted_count(), 1);
        let hits = store.suggest_learned("zero", 8);
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].text, "ZeroConvert");
        assert!(hits[0].learned);

        // Explicit selection of a new OOV word promotes on 1st commit.
        assert!(store.record("Azoo", "AzooKey", true, false, 300));
        assert!(store.is_promoted_word("AzooKey"));
        let explicit_hits = store.suggest_learned("azo", 8);
        assert_eq!(explicit_hits.len(), 1);
        assert_eq!(explicit_hits[0].text, "AzooKey");
    }

    #[test]
    fn dictionary_word_boosts_on_first_direct_commit_and_override_wins_for_prefix() {
        let mut store = EnglishLearningStore::new(None);
        assert!(store.record("helping", "helping", false, true, 100));
        assert!(store.is_promoted_word("helping"));
        let merged = store.merge_with_dictionary("hel", &["help", "hello", "helping"], 8, true);
        assert_eq!(merged[0].text, "helping");
        assert!(merged[0].learned);

        // Explicit selection on prefix "hel" overrides for "hel", while "he" keeps word weight order.
        assert!(store.record("hel", "hello", true, true, 200));
        let hel_merged = store.merge_with_dictionary("hel", &["help", "hello", "helping"], 8, true);
        assert_eq!(hel_merged[0].text, "hello");
        assert!(hel_merged[0].learned);
        assert_eq!(hel_merged[1].text, "helping");
        assert_eq!(hel_merged[2].text, "help");
        assert!(!hel_merged[2].learned);
    }

    #[test]
    fn casing_rules_preserve_distinctive_casing_and_ignore_sentence_start_caps() {
        let mut store = EnglishLearningStore::new(None);
        // Sentence-start "Hello" in dictionary is normalized to lowercase "hello".
        assert!(store.record("Hello", "Hello", false, true, 100));
        for (prefix, expected) in [
            ("hel", "hello"),
            ("Hel", "Hello"),
            ("HEL", "HELLO"),
            ("hEl", "hEllo"),
        ] {
            let hits = store.suggest_learned(prefix, 8);
            assert_eq!(hits[0].text, expected, "prefix={prefix}");
        }

        // Long all-caps in dictionary is normalized to lowercase.
        assert!(store.record("SYSTEMS", "SYSTEMS", false, true, 150));
        assert_eq!(store.suggest_learned("sys", 8)[0].text, "systems");

        // Short all-uppercase acronym (2..=5) in dictionary keeps uppercase, and sentence-start
        // TitleCase does not clobber it, while explicit lowercase resets it.
        assert!(store.record("API", "API", false, true, 200));
        assert_eq!(store.suggest_learned("ap", 8)[0].text, "API");
        assert!(store.record("Api", "Api", false, true, 210));
        assert_eq!(store.suggest_learned("ap", 8)[0].text, "API");
        assert!(store.record("api", "api", false, true, 220));
        assert_eq!(store.suggest_learned("ap", 8)[0].text, "api");

        // Mixed-case dictionary or OOV word preserves casing even on lowercase prefix.
        assert!(store.record("github", "GitHub", true, true, 300));
        let gh_exact = store.suggest_learned("github", 8);
        assert_eq!(gh_exact.len(), 1);
        assert_eq!(gh_exact[0].text, "GitHub");
        assert_eq!(store.suggest_learned("git", 8)[0].text, "GitHub");
        assert_eq!(store.suggest_learned("GIT", 8)[0].text, "GITHUB");
    }

    #[test]
    fn forget_removes_word_and_prefix_overrides_and_persists_roundtrip() {
        let path = temp_path("forget-roundtrip");
        let _ = fs::remove_file(&path);
        {
            let mut store = EnglishLearningStore::new(Some(path.clone()));
            store.load();
            assert!(store.record("hel", "hello", true, true, 100));
            assert!(store.record("he", "hello", true, true, 110));
            assert!(store.record("ZeroConvert", "ZeroConvert", true, false, 120));
            store.save().expect("save should succeed");
        }
        {
            let mut reloaded = EnglishLearningStore::new(Some(path.clone()));
            reloaded.load();
            assert_eq!(reloaded.promoted_count(), 2);
            assert_eq!(reloaded.suggest_learned("hel", 8)[0].text, "hello");
            assert!(reloaded.forget("Hello", Some("hel")));
            assert!(!reloaded.is_promoted_word("hello"));
            assert!(reloaded.suggest_learned("hel", 8).is_empty());
            assert!(reloaded.suggest_learned("he", 8).is_empty());
            assert_eq!(reloaded.promoted_count(), 1);
            reloaded.save().expect("save after forget");
        }
        let mut final_store = EnglishLearningStore::new(Some(path.clone()));
        final_store.load();
        assert_eq!(final_store.promoted_count(), 1);
        assert!(final_store.is_promoted_word("ZeroConvert"));
        assert!(final_store.remove_all());
        assert_eq!(final_store.promoted_count(), 0);
        assert!(!final_store.remove_all());
        let _ = fs::remove_file(&path);
    }

    #[test]
    fn trimming_evicts_unpromoted_words_before_promoted_words() {
        let mut store = EnglishLearningStore::new(None);
        for i in 0..450 {
            let suffix = format!(
                "{}{}{}",
                (b'a' + ((i / 26 / 26) % 26) as u8) as char,
                (b'a' + ((i / 26) % 26) as u8) as char,
                (b'a' + (i % 26) as u8) as char
            );
            let word = format!("promoted{suffix}");
            assert!(store.record(&word, &word, true, false, 100 + i as u64));
        }
        for i in 0..100 {
            let suffix = format!(
                "{}{}{}",
                (b'a' + ((i / 26 / 26) % 26) as u8) as char,
                (b'a' + ((i / 26) % 26) as u8) as char,
                (b'a' + (i % 26) as u8) as char
            );
            let word = format!("typo{suffix}");
            // Single direct OOV commit: unpromoted.
            assert!(store.record(&word, &word, false, false, 10_000 + i as u64));
        }
        assert_eq!(store.promoted_count(), 450);
        assert!(store.is_promoted_word("promotedaaa"));
    }

    #[test]
    fn read_learning_disabled_hides_learned_candidates() {
        let mut store = EnglishLearningStore::new(None);
        store.record("hel", "hello", true, true, 100);
        let with_learning = store.merge_with_dictionary("hel", &["help", "hello"], 8, true);
        assert_eq!(with_learning[0].text, "hello");
        assert!(with_learning[0].learned);

        let without_learning = store.merge_with_dictionary("hel", &["help", "hello"], 8, false);
        assert_eq!(without_learning[0].text, "help");
        assert!(!without_learning[0].learned);
        assert_eq!(without_learning[1].text, "hello");
        assert!(!without_learning[1].learned);
    }

    #[test]
    fn imports_legacy_prefix_overrides() {
        let mut store = EnglishLearningStore::new(None);
        let imported = store.import_legacy_prefix_overrides(
            &[
                ("hel".to_string(), "hello".to_string()),
                ("git".to_string(), "GitHub".to_string()),
                ("x".to_string(), "bad123".to_string()),
            ],
            500,
        );
        assert_eq!(imported, 2);
        assert_eq!(store.suggest_learned("hel", 8)[0].text, "hello");
        assert_eq!(store.suggest_learned("git", 8)[0].text, "GitHub");
        assert_eq!(store.promoted_count(), 2);
    }

    #[test]
    fn suggests_default_and_learned_email_domains_with_gboard_promotion() {
        let mut store = EnglishLearningStore::new(None);

        let initial = store.suggest_email_domains("@", 8, true);
        assert_eq!(initial.len(), 8);
        assert_eq!(initial[0].text, "@gmail.com");
        assert!(initial.iter().all(|entry| !entry.learned));

        let filtered = store.suggest_email_domains("@Y", 8, true);
        assert_eq!(
            filtered
                .iter()
                .map(|entry| entry.text.as_str())
                .collect::<Vec<_>>(),
            vec!["@yahoo.co.jp", "@ymobile.ne.jp", "@yahoo.com"]
        );

        // Incomplete domains never pollute learning.
        assert!(!store.record("@gm", "@gm", false, false, 100));
        assert!(!store.record("@gmail.", "@gmail.", false, false, 110));

        // Custom domain requires 2 direct commits unless explicitly selected.
        assert!(store.record("@example.co.jp", "@example.co.jp", false, false, 200));
        assert!(!store.is_promoted_word("@example.co.jp"));
        assert!(store.suggest_email_domains("@ex", 8, true).is_empty());

        assert!(store.record("@example.co.jp", "@example.co.jp", false, false, 300));
        assert!(store.is_promoted_word("@example.co.jp"));
        let custom_hits = store.suggest_email_domains("@ex", 8, true);
        assert_eq!(custom_hits.len(), 1);
        assert_eq!(custom_hits[0].text, "@example.co.jp");
        assert!(custom_hits[0].learned);

        // Selecting a built-in domain promotes on 1st commit and overrides `@`.
        assert!(store.record("@", "@icloud.com", true, true, 400));
        let updated = store.suggest_email_domains("@", 8, true);
        assert_eq!(updated[0].text, "@icloud.com");
        assert!(updated[0].learned);
        assert_eq!(updated[1].text, "@example.co.jp");
        assert!(updated[1].learned);
        assert!(updated
            .iter()
            .all(|entry| entry.text != "@gmail.com" || !entry.learned));

        // Exact completed domain produces no self-suggestion.
        assert!(store
            .suggest_email_domains("@gmail.com", 8, true)
            .is_empty());

        // Word completion and email domain learning stay isolated.
        assert!(store.suggest_learned("g", 8).is_empty());
        assert!(store.forget("@example.co.jp", Some("@ex")));
        assert!(!store.is_promoted_word("@example.co.jp"));
    }
}
