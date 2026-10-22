// ai_assist/learning.rs
// 次世代アシスト Phase 4: 「もしかして」確定時の自己学習フィードバック
//
// ユーザーが「💡 もしかして」候補を採用した事実を即時記録し、次回同じ誤字を
// 入力したときに 0ms で優先提示する。学習データはサーバー本体に隣接する
// 設定ディレクトリ (`%APPDATA%\\Azookey`) に保存し、外部ネットワークには
// 一切送信しない。

use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

/// 学習データファイル名。
const LEARNING_FILENAME: &str = "ai-assist-learning.json";
/// 学習データファイルのバージョン。
const LEARNING_FILE_VERSION: u32 = 1;
/// 保持する学習エントリ数の上限。
const MAX_LEARNING_ENTRIES: usize = 500;

/// 学習済みの誤字 1 件。
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LearnedTypo {
    /// 誤入力の読み。
    pub reading: String,
    /// 正しい表記。
    pub surface: String,
    /// 採用された回数。
    pub count: u32,
    /// 最終更新時刻 (epoch ミリ秒)。
    pub updated_at_ms: u64,
}

/// 学習データのファイル形式。
#[derive(Debug, Clone, Serialize, Deserialize)]
struct LearningFile {
    version: u32,
    entries: Vec<LearnedTypo>,
}

/// 学習ストア。
#[derive(Debug, Default)]
pub struct TypoLearningStore {
    entries: HashMap<String, LearnedTypo>,
    path: Option<PathBuf>,
    loaded: bool,
}

impl TypoLearningStore {
    /// 学習ストアを生成する。
    pub fn new(path: Option<PathBuf>) -> Self {
        Self {
            entries: HashMap::new(),
            path,
            loaded: false,
        }
    }

    /// 学習データをファイルから読み込む (未読み込みの場合のみ)。
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
        let Ok(parsed) = serde_json::from_str::<LearningFile>(&content) else {
            return;
        };
        for entry in parsed.entries {
            if entry.reading.is_empty() || entry.surface.is_empty() {
                continue;
            }
            self.entries.insert(entry.reading.clone(), entry);
        }
    }

    /// 読みに対応する学習済み表記を返す。
    pub fn lookup(&self, reading: &str) -> Option<&LearnedTypo> {
        self.entries.get(reading)
    }

    /// 誤字と正解の組を記録する。
    pub fn record(&mut self, reading: &str, surface: &str) {
        if reading.is_empty() || surface.is_empty() {
            return;
        }
        let count = self
            .entries
            .get(reading)
            .map(|entry| entry.count.saturating_add(1))
            .unwrap_or(1);
        self.entries.insert(
            reading.to_string(),
            LearnedTypo {
                reading: reading.to_string(),
                surface: surface.to_string(),
                count,
                updated_at_ms: now_millis(),
            },
        );
        self.trim();
    }

    /// 古いエントリを削除して上限を保つ。
    fn trim(&mut self) {
        if self.entries.len() <= MAX_LEARNING_ENTRIES {
            return;
        }
        let mut entries: Vec<LearnedTypo> = self.entries.values().cloned().collect();
        entries.sort_by(|left, right| right.updated_at_ms.cmp(&left.updated_at_ms));
        entries.truncate(MAX_LEARNING_ENTRIES);
        self.entries = entries
            .into_iter()
            .map(|entry| (entry.reading.clone(), entry))
            .collect();
    }

    /// 学習データをファイルへ保存する。
    pub fn save(&self) -> Result<(), String> {
        let Some(path) = self.path.as_ref() else {
            return Ok(());
        };
        let mut entries: Vec<LearnedTypo> = self.entries.values().cloned().collect();
        entries.sort_by(|left, right| right.updated_at_ms.cmp(&left.updated_at_ms));
        let file = LearningFile {
            version: LEARNING_FILE_VERSION,
            entries,
        };
        let serialized = serde_json::to_string_pretty(&file).map_err(|error| error.to_string())?;

        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|error| error.to_string())?;
        }
        let temporary = path.with_extension("json.tmp");
        fs::write(&temporary, serialized.as_bytes()).map_err(|error| error.to_string())?;
        fs::rename(&temporary, path).map_err(|error| error.to_string())
    }

    /// 学習エントリ数。
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// 学習エントリが空かどうか。
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

/// グローバルな学習ストア。
static LEARNING_STORE: OnceLock<Mutex<TypoLearningStore>> = OnceLock::new();

/// 現在の epoch ミリ秒。
fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or_default()
}

/// 学習ストアの既定パス。
pub fn learning_store_path() -> Option<PathBuf> {
    shared::AppConfig::settings_path()
        .ok()
        .and_then(|settings| settings.parent().map(|root| root.join(LEARNING_FILENAME)))
}

/// グローバルな学習ストアを返す。
fn store() -> &'static Mutex<TypoLearningStore> {
    LEARNING_STORE.get_or_init(|| Mutex::new(TypoLearningStore::new(learning_store_path())))
}

/// 学習データを非同期で読み込む。
///
/// 起動時の読み込みは打鍵レイテンシに影響させないため、呼び出し側で
/// バックグラウンドスレッドから実行する。
pub fn load_learning_store() -> usize {
    let mut guard = match store().lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    guard.load();
    guard.len()
}

/// 学習済みデータとの一致結果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LearnedMatch {
    /// 一致した読みの断片 (読み全体と一致した場合は読み全体)。
    pub reading: String,
    /// 正しい表記。
    pub surface: String,
    /// 採用された回数。
    pub count: u32,
}

/// 読みに対して学習済みの表記を返す。
///
/// 1. 読み全体の一致を優先し、2. 読み末尾の語句一致を次に試す。
///
/// 語句一致は 3 文字以上を要求し、短い断片の偶然の一致で誤補正しない。
pub fn lookup_learned_typo(reading: &str) -> Option<LearnedMatch> {
    let trimmed = reading.trim();
    if trimmed.chars().count() < 2 {
        return None;
    }
    let mut guard = match store().lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    guard.load();

    if let Some(entry) = guard.lookup(trimmed) {
        return Some(LearnedMatch {
            reading: entry.reading.clone(),
            surface: entry.surface.clone(),
            count: entry.count,
        });
    }

    let characters: Vec<char> = trimmed.chars().collect();
    for start in 1..characters.len().saturating_sub(1) {
        let suffix: String = characters[start..].iter().collect();
        if suffix.chars().count() < 3 {
            break;
        }
        if let Some(entry) = guard.lookup(&suffix) {
            return Some(LearnedMatch {
                reading: entry.reading.clone(),
                surface: entry.surface.clone(),
                count: entry.count,
            });
        }
    }
    None
}

/// 誤字と正解の組を学習ストアへ記録し、ファイルへ保存する。
pub fn record_learned_typo(reading: &str, surface: &str) -> bool {
    if reading.is_empty() || surface.is_empty() {
        return false;
    }
    let mut guard = match store().lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    guard.load();
    guard.record(reading, surface);
    guard.save().is_ok()
}

/// 学習ストアを読み込む (テスト用)。
#[cfg(test)]
pub(crate) fn store_for_test(path: Option<PathBuf>) -> &'static Mutex<TypoLearningStore> {
    static TEST_STORE: OnceLock<Mutex<TypoLearningStore>> = OnceLock::new();
    TEST_STORE.get_or_init(|| Mutex::new(TypoLearningStore::new(path)))
}

#[cfg(test)]
mod tests {
    use super::{LearnedTypo, TypoLearningStore};
    use std::path::PathBuf;

    fn temp_path(name: &str) -> PathBuf {
        let mut path = std::env::temp_dir();
        path.push(format!(
            "zeroconvert-ai-assist-{name}-{}",
            std::process::id()
        ));
        path
    }

    #[test]
    fn records_and_looks_up_typos() {
        let path = temp_path("record");
        let _ = std::fs::remove_file(&path);
        let mut store = TypoLearningStore::new(Some(path.clone()));
        store.load();
        store.record("きんにちは", "こんにちは");

        let entry = store.lookup("きんにちは").expect("should find entry");
        assert_eq!(entry.surface, "こんにちは");
        assert_eq!(entry.count, 1);

        store.record("きんにちは", "こんにちは");
        assert_eq!(store.lookup("きんにちは").map(|e| e.count), Some(2));
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn persists_across_reload() {
        let path = temp_path("persist");
        let _ = std::fs::remove_file(&path);
        {
            let mut store = TypoLearningStore::new(Some(path.clone()));
            store.load();
            store.record("しんずく", "新宿");
            store.save().expect("should save");
        }
        let mut reloaded = TypoLearningStore::new(Some(path.clone()));
        reloaded.load();
        assert_eq!(
            reloaded
                .lookup("しんずく")
                .map(|entry| entry.surface.clone()),
            Some("新宿".to_string())
        );
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn ignores_incomplete_entries() {
        let mut store = TypoLearningStore::new(None);
        store.record("", "こんにちは");
        store.record("きんにちは", "");
        assert!(store.is_empty());
    }

    #[test]
    fn deserializes_legacy_shape() {
        let entry = LearnedTypo {
            reading: "きんにちは".to_string(),
            surface: "こんにちは".to_string(),
            count: 3,
            updated_at_ms: 1,
        };
        let json = serde_json::to_string(&entry).expect("should serialize");
        let parsed: LearnedTypo = serde_json::from_str(&json).expect("should parse");
        assert_eq!(parsed, entry);
    }
}
