// ai_assist/typo_variant.rs
// 次世代アシスト Phase 3: QWERTY 隣接キー変種生成 & 軽量辞書インデックス
//
// 静的な誤字テーブルに載っていない誤入力でも、QWERTY 配列上の隣接キー 1 文字
// 置換で正しい読みを生成し、辞書インデックスで妥当な表記を 0ms で判定する。
//
// 変換エンジン側の数十万語辞書は Swift モジュール側にあり Rust からは直接
// 照会できないため、ここでは「軽量辞書インデックス」を構成して使う。
// ユーザー追加の語彙は `%APPDATA%\\Azookey\\ai-assist-vocabulary.json` から
// 読み込む (外部依存・ネットワーク参照は不要)。

use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{OnceLock, RwLock};

use serde_json::Value;

use super::is_hiragana_qwerty_adjacent;

/// 固有名詞・専門用語の読みインデックス。
///
/// 変換エンジンが固有名詞を正しく返さない場合の保険として、変種判定と
/// 表記補完の両方に使用する。
const PROPER_NOUN_VOCAB: &[(&str, &str)] = &[
    ("しんじく", "新宿"),
    ("しんじゅく", "新宿"),
    ("あきはばら", "秋葉原"),
    ("たかはし", "高橋"),
    ("たなか", "田中"),
    ("さとう", "佐藤"),
    ("すずき", "鈴木"),
    ("やまだ", "山田"),
    ("いとう", "伊藤"),
    ("わたなべ", "渡辺"),
    ("けんこう", "健康"),
    ("びょういん", "病院"),
    ("ぎんこう", "銀行"),
    ("しょうせき", "小説"),
    ("でんわ", "電話"),
    ("ぱそこん", "パソコン"),
    ("でーた", "データ"),
];

/// 大規模語彙インデックス (TSV埋め込み: 読み -> 表記)
const EXPANDED_VOCAB_TSV: &str = include_str!("builtin_vocab.tsv");

/// ユーザー追加語彙ファイル名。
const USER_VOCABULARY_FILENAME: &str = "ai-assist-vocabulary.json";

/// 1 読みに対して保持する最大語数。
const MAX_ENTRIES_PER_READING: usize = 8;

/// 誤字補正のために生成する変種の上限。
const MAX_VARIANTS: usize = 8;

/// 読み -> 表記 の軽量辞書インデックス。
#[derive(Debug, Default)]
pub struct VocabularyIndex {
    entries: HashMap<String, Vec<String>>,
}

impl VocabularyIndex {
    /// 索引に読みを追加する。
    pub fn insert(&mut self, reading: &str, surface: &str) {
        if reading.is_empty() || surface.is_empty() {
            return;
        }
        let surfaces = self.entries.entry(reading.to_string()).or_default();
        if surfaces.len() >= MAX_ENTRIES_PER_READING {
            return;
        }
        if !surfaces.iter().any(|existing| existing == surface) {
            surfaces.push(surface.to_string());
        }
    }

    /// 読みに対応する表記を返す。
    pub fn lookup(&self, reading: &str) -> Option<&str> {
        self.entries
            .get(reading)
            .and_then(|surfaces| surfaces.first())
            .map(String::as_str)
    }

    /// 語数。
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    /// 語数が 0 かどうか。
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

/// 組み込みインデックス (標準語彙 + 固有名詞)。
static BUILTIN_INDEX: OnceLock<VocabularyIndex> = OnceLock::new();

/// ユーザー追加インデックス (起動時にバックグラウンドで読み込む)。
static USER_INDEX: OnceLock<RwLock<VocabularyIndex>> = OnceLock::new();

/// 組み込みインデックスを返す。
pub fn builtin_index() -> &'static VocabularyIndex {
    BUILTIN_INDEX.get_or_init(|| {
        let mut index = VocabularyIndex::default();
        for &(reading, surface) in super::STANDARD_VOCAB {
            index.insert(reading, surface);
        }
        for &(reading, surface) in PROPER_NOUN_VOCAB {
            index.insert(reading, surface);
        }
        for line in EXPANDED_VOCAB_TSV.lines() {
            if let Some((r, s)) = line.split_once('\t') {
                index.insert(r.trim(), s.trim());
            }
        }
        index
    })
}

/// ユーザー追加インデックスを返す。
pub fn user_index() -> &'static RwLock<VocabularyIndex> {
    USER_INDEX.get_or_init(|| RwLock::new(VocabularyIndex::default()))
}

/// ユーザー追加語彙ファイルを読み込んでインデックスへ反映する。
///
/// 読み込みに失敗した場合は空のインデックスを継続して使用する (IME の変換
/// 機能に影響を与えない)。
pub fn load_user_vocabulary(path: &Path) -> Result<usize, String> {
    let content = fs::read_to_string(path).map_err(|error| error.to_string())?;
    let parsed: Value = serde_json::from_str(&content).map_err(|error| error.to_string())?;
    let entries = parsed
        .get("entries")
        .and_then(Value::as_array)
        .ok_or_else(|| "ai-assist-vocabulary.json must contain an \"entries\" array".to_string())?;

    let mut index = VocabularyIndex::default();
    for entry in entries {
        let Some(reading) = entry.get("reading").and_then(Value::as_str) else {
            continue;
        };
        let Some(surface) = entry.get("surface").and_then(Value::as_str) else {
            continue;
        };
        index.insert(reading, surface);
    }

    let count = index.len();
    if let Ok(mut guard) = user_index().write() {
        *guard = index;
    }
    Ok(count)
}

/// ユーザー追加語彙の既定パス。
pub fn user_vocabulary_path() -> Option<PathBuf> {
    shared::AppConfig::settings_path()
        .ok()
        .and_then(|settings| {
            settings
                .parent()
                .map(|root| root.join(USER_VOCABULARY_FILENAME))
        })
}

/// 読みに対応する表記をインデックスから検索する。
///
/// 組み込みインデックスを優先し、無ければユーザー追加インデックスを見る。
pub fn lookup_surface(reading: &str) -> Option<String> {
    if let Some(surface) = builtin_index().lookup(reading) {
        return Some(surface.to_string());
    }
    user_index()
        .read()
        .ok()
        .and_then(|guard| guard.lookup(reading).map(str::to_string))
}

/// QWERTY 隣接キーによる 1 文字置換で生成しうる変種。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VariantCorrection {
    /// 置換後の読み。
    pub variant: String,
    /// 置換された元の読み。
    pub original: String,
    /// 辞書に一致した表記。
    pub surface: String,
}

/// 誤字補正の全体結果。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TypoVariantCorrection {
    /// 補正後の文字列 (読み全体)。
    pub surface: String,
    /// 補正対象となった読みの断片。
    pub token_reading: String,
    /// 補正後の表記 (断片単位)。
    pub token_surface: String,
    /// 一致した変種読み。
    pub variant_reading: String,
}

/// 判定対象とするひらがな (母音・拗音・小書き・撥音を含む)。
const ALL_KANA: &[char] = &[
    'あ', 'い', 'う', 'え', 'お', 'か', 'き', 'く', 'け', 'こ', 'さ', 'し', 'す', 'せ', 'そ', 'た',
    'ち', 'つ', 'て', 'と', 'な', 'に', 'ぬ', 'ね', 'の', 'は', 'ひ', 'ふ', 'へ', 'ほ', 'ま', 'み',
    'む', 'め', 'も', 'や', 'ゆ', 'よ', 'ら', 'り', 'る', 'れ', 'ろ', 'わ', 'を', 'ん', 'が', 'ぎ',
    'ぐ', 'げ', 'ご', 'ざ', 'じ', 'ず', 'ぜ', 'ぞ', 'だ', 'ぢ', 'づ', 'で', 'ど', 'ば', 'び', 'ぶ',
    'べ', 'ぼ', 'ぱ', 'ぴ', 'ぷ', 'ぺ', 'ぽ', 'ゃ', 'ゅ', 'ょ', 'っ', 'ゔ',
];

/// 隣接文字のキャッシュ。
static ADJACENT_KANA: OnceLock<HashMap<char, Vec<char>>> = OnceLock::new();

/// ひらがな 1 文字を QWERTY 隣接キーで打鍵し間違えた場合に置換されうる文字。
fn adjacent_kana(source: char) -> Vec<char> {
    ALL_KANA
        .iter()
        .copied()
        .filter(|&candidate| candidate != source && is_hiragana_qwerty_adjacent(source, candidate))
        .collect()
}

/// 隣接文字のキャッシュを返す。
fn adjacent_kana_cache() -> &'static HashMap<char, Vec<char>> {
    ADJACENT_KANA.get_or_init(|| {
        let mut cache = HashMap::new();
        for &kana in ALL_KANA {
            let adjacent = adjacent_kana(kana);
            if !adjacent.is_empty() {
                cache.insert(kana, adjacent);
            }
        }
        cache
    })
}

/// QWERTY 隣接キーによる 1 文字置換の変種を生成する。
///
/// 生成順は「打鍵位置が後ろの変種を優先」する。0ms を維持するため生成数は
/// 上限 (`MAX_VARIANTS`) で制限する。
pub fn generate_qwerty_variants(reading: &str) -> Vec<String> {
    let chars: Vec<char> = reading.chars().collect();
    if chars.len() < 2 {
        return Vec::new();
    }
    let cache = adjacent_kana_cache();
    let mut variants = Vec::new();

    for index in (0..chars.len()).rev() {
        let Some(alternatives) = cache.get(&chars[index]) else {
            continue;
        };
        for &alternative in alternatives {
            let mut candidate = chars.clone();
            candidate[index] = alternative;
            let variant: String = candidate.into_iter().collect();
            if variant == reading || variants.contains(&variant) {
                continue;
            }
            variants.push(variant);
            if variants.len() >= MAX_VARIANTS {
                return variants;
            }
        }
    }
    variants
}

/// 読みに対して QWERTY 隣接キー変種から正しい表記を探索する。
///
/// 1. 読みそのものがインデックスに存在する (意図された入力) 場合は補正しない。
/// 2. 変種を生成し、インデックスに一致するものを採用する。
pub fn find_qwerty_variant_correction(reading: &str) -> Option<VariantCorrection> {
    let trimmed = reading.trim();
    if trimmed.chars().count() < 2 {
        return None;
    }
    if lookup_surface(trimmed).is_some() {
        return None;
    }

    let mut fallback: Option<VariantCorrection> = None;
    for variant in generate_qwerty_variants(trimmed) {
        let Some(surface) = lookup_surface(&variant) else {
            continue;
        };
        let correction = VariantCorrection {
            variant: variant.clone(),
            original: trimmed.to_string(),
            surface: surface.clone(),
        };
        if surface.chars().count() > 1 {
            return Some(correction);
        }
        if fallback.is_none() {
            fallback = Some(correction);
        }
    }
    fallback
}

/// 読み全体と、その末尾の語句に対する QWERTY 変種補正を探す。
///
/// 文末の語句だけが誤入力である場合にも対応するため、末尾の長い語句から順に試す。
pub fn find_qwerty_correction(reading: &str) -> Option<TypoVariantCorrection> {
    let trimmed = reading.trim();
    if trimmed.chars().count() < 2 {
        return None;
    }

    if let Some(correction) = find_qwerty_variant_correction(trimmed) {
        return Some(TypoVariantCorrection {
            surface: correction.surface.clone(),
            token_reading: trimmed.to_string(),
            token_surface: correction.surface,
            variant_reading: correction.variant,
        });
    }

    let characters: Vec<char> = trimmed.chars().collect();
    for start in 1..characters.len().saturating_sub(1) {
        let suffix: String = characters[start..].iter().collect();
        if suffix.chars().count() < 3 {
            break;
        }
        let Some(correction) = find_qwerty_variant_correction(&suffix) else {
            continue;
        };
        let prefix: String = characters[..start].iter().collect();
        return Some(TypoVariantCorrection {
            surface: format!("{prefix}{}", correction.surface),
            token_reading: suffix,
            token_surface: correction.surface,
            variant_reading: correction.variant,
        });
    }
    None
}

/// 読みと表記の対応がインデックスに存在するか (固有名詞の変換漏れ補完用)。
pub fn find_indexed_surface(reading: &str) -> Option<String> {
    let trimmed = reading.trim();
    if trimmed.is_empty() {
        return None;
    }
    lookup_surface(trimmed)
}

#[cfg(test)]
mod tests {
    use super::{
        find_indexed_surface, find_qwerty_correction, find_qwerty_variant_correction,
        generate_qwerty_variants,
    };

    #[test]
    fn generates_single_character_variants() {
        let variants = generate_qwerty_variants("しんずく");
        assert!(variants.contains(&"しんじく".to_string()));
        assert!(!variants.contains(&"しんずく".to_string()));
    }

    #[test]
    fn corrects_qwerty_adjacent_typo_to_proper_noun() {
        let correction = find_qwerty_variant_correction("しんずく").expect("should correct");
        assert_eq!(correction.variant, "しんじく");
        assert_eq!(correction.surface, "新宿");
    }

    #[test]
    fn corrects_qwerty_adjacent_typo_to_standard_word() {
        let correction = find_qwerty_variant_correction("よろすく").expect("should correct");
        assert_eq!(correction.variant, "よろしく");
        assert_eq!(correction.surface, "よろしく");
    }

    #[test]
    fn corrects_qwerty_adjacent_typo_to_proper_noun_with_suffix() {
        let correction = find_qwerty_variant_correction("たかはす").expect("should correct");
        assert_eq!(correction.variant, "たかはし");
        assert_eq!(correction.surface, "高橋");
    }

    #[test]
    fn leaves_valid_readings_untouched() {
        assert!(find_qwerty_variant_correction("しんじゅく").is_none());
        assert!(find_qwerty_variant_correction("よろしく").is_none());
        assert!(find_qwerty_variant_correction("あ").is_none());
    }

    #[test]
    fn keeps_the_leading_context_of_an_embedded_typo() {
        let correction = find_qwerty_correction("きのうのしんずく").expect("should correct");
        assert_eq!(correction.token_reading, "しんずく");
        assert_eq!(correction.token_surface, "新宿");
        assert_eq!(correction.surface, "きのうの新宿");
    }

    #[test]
    fn looks_up_indexed_proper_noun() {
        assert_eq!(
            find_indexed_surface("あきはばら"),
            Some("秋葉原".to_string())
        );
        assert_eq!(find_indexed_surface("しんじゅく"), Some("新宿".to_string()));
        assert_eq!(find_indexed_surface("はなさない"), None);
    }

    #[test]
    fn variant_generation_stays_bounded() {
        let variants = generate_qwerty_variants("あいうえおかきくけこさしすせそたちつてと");
        assert!(variants.len() <= super::MAX_VARIANTS);
    }
}
