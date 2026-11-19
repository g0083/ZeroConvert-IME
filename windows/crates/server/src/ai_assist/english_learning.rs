use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

pub use shared::english_learning::{
    EnglishLearningSnapshot, EnglishLearningStore, EnglishSuggestion, ENGLISH_LEARNING_FILENAME,
};

#[derive(Debug)]
struct EnglishStoreState {
    store: EnglishLearningStore,
    revision: u64,
}

impl Default for EnglishStoreState {
    fn default() -> Self {
        Self {
            store: EnglishLearningStore::new(english_learning_store_path()),
            revision: 1,
        }
    }
}

static ENGLISH_LEARNING_STORE: OnceLock<Mutex<EnglishStoreState>> = OnceLock::new();

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default()
}

pub fn english_learning_store_path() -> Option<PathBuf> {
    super::runtime::settings_path().and_then(|settings| {
        settings
            .parent()
            .map(|root| root.join(ENGLISH_LEARNING_FILENAME))
    })
}

fn store() -> &'static Mutex<EnglishStoreState> {
    ENGLISH_LEARNING_STORE.get_or_init(|| Mutex::new(EnglishStoreState::default()))
}

fn lock_store() -> std::sync::MutexGuard<'static, EnglishStoreState> {
    match store().lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    }
}

pub fn load_english_learning_store() -> usize {
    let mut guard = lock_store();
    guard.store.load();
    guard.store.promoted_count()
}

pub fn current_revision() -> u64 {
    let mut guard = lock_store();
    guard.store.load();
    guard.revision
}

pub fn snapshot_if_changed(known_revision: u64) -> Option<(u64, EnglishLearningSnapshot)> {
    let mut guard = lock_store();
    guard.store.load();
    if known_revision == guard.revision && guard.revision > 0 {
        return None;
    }
    Some((guard.revision, guard.store.snapshot()))
}

pub fn record_english_word(
    prefix: &str,
    word: &str,
    explicit_selection: bool,
    in_dictionary: bool,
) -> (u64, bool) {
    let mut guard = lock_store();
    guard.store.load();
    if !guard.store.record(
        prefix,
        word,
        explicit_selection,
        in_dictionary,
        now_millis(),
    ) {
        return (guard.revision, false);
    }
    guard.revision = guard.revision.saturating_add(1).max(1);
    let saved = guard.store.save().is_ok();
    (guard.revision, saved)
}

pub fn forget_english_word(word: &str, prefix: Option<&str>) -> bool {
    let mut guard = lock_store();
    guard.store.load();
    if !guard.store.forget(word, prefix) {
        return false;
    }
    guard.revision = guard.revision.saturating_add(1).max(1);
    let _ = guard.store.save();
    true
}

pub fn clear_english_learning() -> bool {
    let mut guard = lock_store();
    guard.store.load();
    if !guard.store.remove_all() {
        return false;
    }
    guard.revision = guard.revision.saturating_add(1).max(1);
    guard.store.save().is_ok()
}

pub fn learned_english_count() -> usize {
    let mut guard = lock_store();
    guard.store.load();
    guard.store.promoted_count()
}

#[allow(dead_code)]
pub fn suggest_english(prefix: &str, limit: usize, read_learning: bool) -> Vec<EnglishSuggestion> {
    if !read_learning {
        return Vec::new();
    }
    let mut guard = lock_store();
    guard.store.load();
    guard.store.suggest_learned(prefix, limit)
}

#[allow(dead_code)]
pub fn is_promoted_english_word(word: &str) -> bool {
    let mut guard = lock_store();
    guard.store.load();
    guard.store.is_promoted_word(word)
}

#[allow(dead_code)]
pub fn promoted_english_words() -> Vec<String> {
    let mut guard = lock_store();
    guard.store.load();
    guard.store.promoted_words()
}

#[allow(dead_code)]
pub fn import_legacy_prefix_overrides(pairs: &[(String, String)]) -> usize {
    let mut guard = lock_store();
    guard.store.load();
    let count = guard
        .store
        .import_legacy_prefix_overrides(pairs, now_millis());
    if count > 0 {
        guard.revision = guard.revision.saturating_add(1).max(1);
        let _ = guard.store.save();
    }
    count
}
