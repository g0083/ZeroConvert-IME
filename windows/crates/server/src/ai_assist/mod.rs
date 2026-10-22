// ai_assist/mod.rs
// ZeroCon IME 実験的機能: 非破壊型「💡 もしかして」誤字補正 &「🔮 先読み」予測変換モジュール
//
// 世代交代のアシスト機能群は以下のように分割されている。
// - `dynamic_macro`: ⚡ 動的マクロ展開 (日付・時刻・年号・インライン計算)
// - `collocation`   : 🔮 格助詞バイグラム (共起語) 先読み
// - `typo_variant`  : 💡 QWERTY 隣接キー変種生成 & 軽量辞書インデックス
// - `learning`      : 🧠 「もしかして」確定時の自己学習フィードバック
//
// いずれも外部ネットワーク・推論エンジン非依存で、IME 単体で完結する。

use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub mod collocation;
pub mod dynamic_macro;
pub mod learning;
pub mod romaji_decoder;
pub mod typo_variant;

pub use collocation::{find_collocation, CollocationCandidate};
pub use dynamic_macro::{current_expansions, expand_dynamic_macros, MacroCandidate, MacroKind};
pub use learning::{lookup_learned_typo, record_learned_typo};
pub use typo_variant::{find_indexed_surface, find_qwerty_correction};

static LAST_KEY_INSTANT: Mutex<Option<Instant>> = Mutex::new(None);
static LFM_IN_FLIGHT: AtomicBool = AtomicBool::new(false);
static LFM_CACHE: Mutex<Option<(String, String)>> = Mutex::new(None);

static USER_HISTORY_CACHE: Mutex<Vec<String>> = Mutex::new(Vec::new());
const MAX_USER_HISTORY: usize = 300;

/// 直近に生成した「💡 もしかして」候補。
///
/// クライアントからの確定通知 (candidate_id 999901) と突き合わせるために保持する。
static LAST_TYPO_CANDIDATE: Mutex<Option<GeneratedTypoCandidate>> = Mutex::new(None);

/// ユーザーが確定したテキストを学習・記録する（先読み用 LRU キャッシュ）
pub fn record_user_committed_text(text: &str) {
    let trimmed = text.trim();
    if trimmed.chars().count() < 3 {
        return;
    }
    if let Ok(mut lock) = USER_HISTORY_CACHE.lock() {
        if lock.first().map(|s| s.as_str()) == Some(trimmed) {
            return;
        }
        if trimmed.contains('。') {
            for sentence in trimmed.split('。') {
                let s_trim = sentence.trim();
                if s_trim.chars().count() >= 3 {
                    let s_with_dot = format!("{}。", s_trim);
                    if !lock.contains(&s_with_dot) {
                        lock.insert(0, s_with_dot);
                    }
                }
            }
        }
        if !lock.contains(&trimmed.to_string()) {
            lock.insert(0, trimmed.to_string());
        }
        if lock.len() > MAX_USER_HISTORY {
            lock.truncate(MAX_USER_HISTORY);
        }
    }
}

/// QWERTY キーボード配列における物理座標
fn qwerty_key_coord(c: char) -> Option<(i8, i8)> {
    match c.to_ascii_lowercase() {
        'q' => Some((0, 0)),
        'w' => Some((0, 1)),
        'e' => Some((0, 2)),
        'r' => Some((0, 3)),
        't' => Some((0, 4)),
        'y' => Some((0, 5)),
        'u' => Some((0, 6)),
        'i' => Some((0, 7)),
        'o' => Some((0, 8)),
        'p' => Some((0, 9)),
        'a' => Some((1, 0)),
        's' => Some((1, 1)),
        'd' => Some((1, 2)),
        'f' => Some((1, 3)),
        'g' => Some((1, 4)),
        'h' => Some((1, 5)),
        'j' => Some((1, 6)),
        'k' => Some((1, 7)),
        'l' => Some((1, 8)),
        'z' => Some((2, 0)),
        'x' => Some((2, 1)),
        'c' => Some((2, 2)),
        'v' => Some((2, 3)),
        'b' => Some((2, 4)),
        'n' => Some((2, 5)),
        'm' => Some((2, 6)),
        _ => None,
    }
}

/// 2つの文字が QWERTY 配列上で物理的に隣接しているかを判定
pub fn is_qwerty_adjacent(c1: char, c2: char) -> bool {
    if c1 == c2 {
        return true;
    }
    if let (Some((r1, col1)), Some((r2, col2))) = (qwerty_key_coord(c1), qwerty_key_coord(c2)) {
        let dr = (r1 - r2).abs();
        let dc = (col1 - col2).abs();
        dr <= 1 && dc <= 1
    } else {
        false
    }
}

/// ひらがなから代表的なローマ字表記を取得
fn hiragana_to_romaji(c: char) -> &'static str {
    match c {
        'あ' => "a",
        'い' => "i",
        'う' => "u",
        'え' => "e",
        'お' => "o",
        'か' => "ka",
        'き' => "ki",
        'く' => "ku",
        'け' => "ke",
        'こ' => "ko",
        'さ' => "sa",
        'し' => "si",
        'す' => "su",
        'せ' => "se",
        'そ' => "so",
        'た' => "ta",
        'ち' => "ti",
        'つ' => "tu",
        'て' => "te",
        'と' => "to",
        'な' => "na",
        'に' => "ni",
        'ぬ' => "nu",
        'ね' => "ne",
        'の' => "no",
        'は' => "ha",
        'ひ' => "hi",
        'ふ' => "hu",
        'へ' => "he",
        'ほ' => "ho",
        'ま' => "ma",
        'み' => "mi",
        'む' => "mu",
        'め' => "me",
        'も' => "mo",
        'や' => "ya",
        'ゆ' => "yu",
        'よ' => "yo",
        'ら' => "ra",
        'り' => "ri",
        'る' => "ru",
        'れ' => "re",
        'ろ' => "ro",
        'わ' => "wa",
        'を' => "wo",
        'ん' => "nn",
        'が' => "ga",
        'ぎ' => "gi",
        'ぐ' => "gu",
        'げ' => "ge",
        'ご' => "go",
        'ざ' => "za",
        'じ' => "zi",
        'ず' => "zu",
        'ぜ' => "ze",
        'ぞ' => "zo",
        'だ' => "da",
        'ぢ' => "di",
        'づ' => "du",
        'で' => "de",
        'ど' => "do",
        'ば' => "ba",
        'び' => "bi",
        'ぶ' => "bu",
        'べ' => "be",
        'ぼ' => "bo",
        'ぱ' => "pa",
        'ぴ' => "pi",
        'ぷ' => "pu",
        'ぺ' => "pe",
        'ぽ' => "po",
        'ゃ' => "xya",
        'ゅ' => "xyu",
        'ょ' => "xyo",
        'っ' => "xtu",
        _ => "",
    }
}

/// 2つのひらがな（または文字）の打鍵が QWERTY 上で隣接キー誤打鍵の可能性があるかを判定
pub fn is_hiragana_qwerty_adjacent(c1: char, c2: char) -> bool {
    let r1 = hiragana_to_romaji(c1);
    let r2 = hiragana_to_romaji(c2);
    if !r1.is_empty() && !r2.is_empty() {
        let r1_chars: Vec<char> = r1.chars().collect();
        let r2_chars: Vec<char> = r2.chars().collect();
        if r1_chars.len() == r2_chars.len() {
            let mut diffs = 0;
            let mut adj = false;
            for (&ch1, &ch2) in r1_chars.iter().zip(r2_chars.iter()) {
                if ch1 != ch2 {
                    diffs += 1;
                    if is_qwerty_adjacent(ch1, ch2) {
                        adj = true;
                    }
                }
            }
            if diffs == 1 && adj {
                return true;
            }
        }
    }
    is_qwerty_adjacent(c1, c2)
}

/// 2つの文字列の間で 1 文字置換があり、かつその置換が QWERTY 隣接キーに由来するかを判定
pub fn has_qwerty_adjacent_difference(s1: &str, s2: &str) -> bool {
    let chars1: Vec<char> = s1.chars().collect();
    let chars2: Vec<char> = s2.chars().collect();
    if chars1.len() == chars2.len() {
        let mut diffs = Vec::new();
        for (&c1, &c2) in chars1.iter().zip(chars2.iter()) {
            if c1 != c2 {
                diffs.push((c1, c2));
            }
        }
        if diffs.len() == 1 {
            let (c1, c2) = diffs[0];
            return is_hiragana_qwerty_adjacent(c1, c2);
        }
    }
    false
}

pub const DEBOUNCE_THRESHOLD_MS: u64 = 60;

struct InFlightGuard;
impl Drop for InFlightGuard {
    fn drop(&mut self) {
        LFM_IN_FLIGHT.store(false, Ordering::Release);
    }
}

#[derive(Debug, Clone)]
pub struct AiAssistConfig {
    pub enabled: bool,
    pub predictive_model: String, // "230m_q4km" or "350m_q4km"
    pub engine: String,           // "embedded" or "lm_studio"
    pub lfm_port: u16,
    pub zenzai_port: u16,
}

impl Default for AiAssistConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            predictive_model: "230m_q4km".to_string(),
            engine: "embedded".to_string(),
            lfm_port: 18090,
            zenzai_port: 8090,
        }
    }
}

impl AiAssistConfig {
    pub fn resolved_model_id(&self) -> &str {
        if self.engine == "embedded" {
            "lfm"
        } else if self.predictive_model.contains("350") {
            "liquidai/lfm2.5-350m"
        } else {
            "lfm2.5-230m@q4_k_m"
        }
    }
}

/// 超軽量HTTP POSTリクエスト（依存関係不要・即座にタイムアウト可能）
fn simple_http_post(port: u16, path: &str, body_json: &Value, timeout_ms: u64) -> Option<Value> {
    let addr = format!("127.0.0.1:{}", port);
    let mut stream =
        TcpStream::connect_timeout(&addr.parse().ok()?, Duration::from_millis(timeout_ms)).ok()?;

    let _ = stream.set_read_timeout(Some(Duration::from_millis(timeout_ms)));
    let _ = stream.set_write_timeout(Some(Duration::from_millis(timeout_ms)));

    let body_str = body_json.to_string();
    let request = format!(
        "POST {} HTTP/1.1\r\nHost: 127.0.0.1:{}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        path, port, body_str.len(), body_str
    );

    if stream.write_all(request.as_bytes()).is_err() {
        return None;
    }

    let mut response_buf = Vec::new();
    let _ = stream.read_to_end(&mut response_buf);
    let response_str = String::from_utf8_lossy(&response_buf);

    if let Some(pos) = response_str.find("\r\n\r\n") {
        let json_part = &response_str[pos + 4..];
        serde_json::from_str(json_part).ok()
    } else {
        None
    }
}

/// Damerau-Levenshtein 編集距離アルゴリズム（挿入、削除、置換、隣接文字転倒）
pub fn to_katakana(s: &str) -> String {
    s.chars()
        .map(|c| {
            if ('\u{3041}'..='\u{3096}').contains(&c) {
                std::char::from_u32(c as u32 + 0x60).unwrap_or(c)
            } else {
                c
            }
        })
        .collect()
}

pub fn katakana_to_hiragana(s: &str) -> String {
    s.chars()
        .map(|c| {
            if ('\u{30A1}'..='\u{30F6}').contains(&c) {
                std::char::from_u32(c as u32 - 0x60).unwrap_or(c)
            } else {
                c
            }
        })
        .collect()
}

fn damerau_levenshtein_distance(s1: &str, s2: &str) -> usize {
    let s1_chars: Vec<char> = s1.chars().collect();
    let s2_chars: Vec<char> = s2.chars().collect();
    let len1 = s1_chars.len();
    let len2 = s2_chars.len();

    if len1 == 0 {
        return len2;
    }
    if len2 == 0 {
        return len1;
    }

    let mut dp = vec![vec![0usize; len2 + 1]; len1 + 1];

    for i in 0..=len1 {
        dp[i][0] = i;
    }
    for j in 0..=len2 {
        dp[0][j] = j;
    }

    for i in 1..=len1 {
        for j in 1..=len2 {
            let cost = if s1_chars[i - 1] == s2_chars[j - 1] {
                0
            } else {
                1
            };
            dp[i][j] = (dp[i - 1][j] + 1)
                .min(dp[i][j - 1] + 1)
                .min(dp[i - 1][j - 1] + cost);

            if i > 1
                && j > 1
                && s1_chars[i - 1] == s2_chars[j - 2]
                && s1_chars[i - 2] == s2_chars[j - 1]
            {
                dp[i][j] = dp[i][j].min(dp[i - 2][j - 2] + 1);
            }
        }
    }

    dp[len1][len2]
}

/// 標準頻出語彙テーブル (読み -> 正式表記)
const STANDARD_VOCAB: &[(&str, &str)] = &[
    ("おはようございます", "おはようございます"),
    ("おはよう", "おはよう"),
    ("こんにちは", "こんにちは"),
    ("こんばんわ", "こんばんは"),
    ("こんばんは", "こんばんは"),
    ("おつかれさまです", "お疲れ様です"),
    ("おつかれさまでした", "お疲れ様でした"),
    ("おつかれ", "お疲れ"),
    ("おつかれさま", "お疲れ様"),
    ("ごくろうさまです", "ご苦労様です"),
    ("ごくろうさま", "ご苦労様"),
    ("ありがとうございます", "ありがとうございます"),
    ("ありがとうございました", "ありがとうございました"),
    ("ありがとう", "ありがとう"),
    ("ありがと", "ありがと"),
    ("どういたしまして", "どういたしまして"),
    ("よろしくおねがいします", "よろしくお願いします"),
    ("よろしくおねがいいたします", "よろしくお願いいたします"),
    ("よろしくおねがい", "よろしくお願い"),
    ("よろしく", "よろしく"),
    ("もうしわけありません", "申し訳ありません"),
    ("もうしわけございません", "申し訳ございません"),
    ("ごめんなさい", "ごめんなさい"),
    ("すみません", "すみません"),
    ("しつれいします", "失礼します"),
    ("しつれいいたします", "失礼いたします"),
    ("おせわになっております", "お世話になっております"),
    ("おせわになります", "お世話になります"),
    ("いただきます", "いただきます"),
    ("ごちそうさまでした", "ごちそうさまでした"),
    ("いってらっしゃい", "いってらっしゃい"),
    ("いってきます", "いってきます"),
    ("おかえり", "おかえり"),
    ("おかえりなさい", "おかえりなさい"),
    ("ただいま", "ただいま"),
    ("おやすみなさい", "おやすみなさい"),
    ("さようなら", "さようなら"),
    ("しょうちいたしました", "承知いたしました"),
    ("かしこまりました", "かしこまりました"),
    ("りょうかいしました", "了解しました"),
    ("りょうかいです", "了解です"),
    ("かくにんしました", "確認しました"),
    ("かくにんしてください", "確認してください"),
    ("おまちしております", "お待ちしております"),
    (
        "ごれんらくありがとうございます",
        "ご連絡ありがとうございます",
    ),
    (
        "ごかくにんよろしくおねがいいたします",
        "ご確認よろしくお願いいたします",
    ),
    ("おねがいします", "お願いします"),
    ("かいしゃ", "会社"),
    ("しごと", "仕事"),
    ("かいぎ", "会議"),
    ("しりょう", "資料"),
    ("しんちょく", "進捗"),
    ("ほうこく", "報告"),
    ("れんらく", "連絡"),
    ("そうだん", "相談"),
    ("でんわ", "電話"),
    ("めーる", "メール"),
    ("ちゃっと", "チャット"),
    ("やくそく", "約束"),
    ("じかん", "時間"),
    ("じかんは", "時間は"),
    ("きょう", "今日"),
    ("あした", "明日"),
    ("あしたの", "明日の"),
    ("きのう", "昨日"),
    ("あさって", "明後日"),
    ("こんしゅう", "今週"),
    ("らいしゅう", "来週"),
    ("せんしゅう", "先週"),
    ("こんげつ", "今月"),
    ("らいげつ", "来月"),
    ("せんげつ", "先月"),
    ("ことし", "今年"),
    ("らいねん", "来年"),
    ("きょねん", "去年"),
    ("まいにち", "毎日"),
    ("あさ", "朝"),
    ("ひる", "昼"),
    ("よる", "夜"),
    ("ばん", "晩"),
    ("ごぜん", "午前"),
    ("ごご", "午後"),
    ("てんき", "天気"),
    ("あめ", "雨"),
    ("はれ", "晴れ"),
    ("くもり", "曇り"),
    ("ゆき", "雪"),
    ("たいふう", "台風"),
    ("きせつ", "季節"),
    ("さんぽ", "散歩"),
    ("うんどう", "運動"),
    ("べんきょう", "勉強"),
    ("しゅくだい", "宿題"),
    ("てすと", "テスト"),
    ("しけん", "試験"),
    ("けっか", "結果"),
    ("げんいん", "原因"),
    ("りゆう", "理由"),
    ("もんだい", "問題"),
    ("しつもん", "質問"),
    ("こたえ", "答え"),
    ("かいけつ", "解決"),
    ("せいこう", "成功"),
    ("しっぱい", "失敗"),
    ("あんぜん", "安全"),
    ("きけん", "危険"),
    ("かんたん", "簡単"),
    ("むずかしい", "難しい"),
    ("だいじょうぶ", "大丈夫"),
    ("たいせつ", "大切"),
    ("ひつよう", "必要"),
    ("べんり", "便利"),
    ("ふべん", "不便"),
    ("しんぶん", "新聞"),
    ("てんけん", "点検"),
    ("きっぷ", "切符"),
    ("がっこう", "学校"),
    ("はっけん", "発見"),
    ("ひんと", "ヒント"),
    ("かいいん", "会員"),
    ("こうせい", "校正"),
    ("さーびす", "サービス"),
    ("ゆーざー", "ユーザー"),
    ("こぴー", "コピー"),
    ("りーだー", "リーダー"),
    ("てーま", "テーマ"),
    ("かさをさす", "傘を差す"),
    ("めにゅーをみせて", "メニューを見せて"),
    ("かくにんをすぐに", "確認をすぐに"),
    ("せってい", "設定"),
    ("せってぃんぐ", "セッティング"),
    ("へんかん", "変換"),
    ("へんこう", "変更"),
    ("かくにん", "確認"),
    ("くらうど", "クラウド"),
    ("おきゃくさん", "お客さん"),
    ("ぷろじぇくと", "プロジェクト"),
    ("ちーむ", "チーム"),
    ("みーてぃんぐ", "ミーティング"),
    ("すけじゅーる", "スケジュール"),
    ("たすく", "タスク"),
    ("かれんだー", "カレンダー"),
    ("あぷり", "アプリ"),
    ("しすてむ", "システム"),
    ("そふと", "ソフト"),
    ("ばーじょん", "バージョン"),
    ("りりーす", "リリース"),
    ("ばぐ", "バグ"),
    ("えらー", "エラー"),
    ("しゅうせい", "修正"),
    ("さくじょ", "削除"),
    ("ほぞん", "保存"),
    ("さくせい", "作成"),
    ("へんしゅう", "編集"),
    ("ろぐいん", "ログイン"),
    ("ろぐあうと", "ログアウト"),
    ("ぱすわーど", "パスワード"),
    ("あかうんと", "アカウント"),
    ("めっせーじ", "メッセージ"),
    ("つうち", "通知"),
    ("がめん", "画面"),
    ("もにたー", "モニター"),
    ("ぼたん", "ボタン"),
    ("うぃんどう", "ウィンドウ"),
    ("たぶ", "タブ"),
    ("りんく", "リンク"),
    // 日常形容詞（基本形・過去形・連用形）
    ("うれしい", "嬉しい"),
    ("うれしかった", "嬉しかった"),
    ("うれしく", "嬉しく"),
    ("たのしい", "楽しい"),
    ("たのしかった", "楽しかった"),
    ("たのしく", "楽しく"),
    ("おいしい", "美味しい"),
    ("おいしかった", "美味しかった"),
    ("おいしく", "美味しく"),
    ("かなしい", "悲しい"),
    ("かなしかった", "悲しかった"),
    ("かなしく", "悲しく"),
    ("さびしい", "寂しい"),
    ("さびしかった", "寂しかった"),
    ("つらい", "辛い"),
    ("つらかった", "辛かった"),
    ("ねむい", "眠い"),
    ("ねむかった", "眠かった"),
    ("いそがしい", "忙しい"),
    ("いそがしかった", "忙しかった"),
    ("はやい", "早い"),
    ("おそい", "遅い"),
    ("ちかい", "近い"),
    ("とおい", "遠い"),
    ("あかるい", "明るい"),
    ("くらい", "暗い"),
    ("あたたかい", "暖かい"),
    ("さむい", "寒い"),
    ("あつい", "暑い"),
    ("すずしい", "涼しい"),
    ("あたらしい", "新しい"),
    ("ふるい", "古い"),
    ("いい", "良い"),
    ("よい", "良い"),
    ("わるい", "悪い"),
    ("おおきい", "大きい"),
    ("ちいさい", "小さい"),
    ("たかい", "高い"),
    ("ひくい", "低い"),
    ("ながい", "長い"),
    ("みじかい", "短い"),
    ("おおい", "多い"),
    ("すくない", "少ない"),
    ("ふかい", "深い"),
    ("あさい", "浅い"),
    ("おもい", "重い"),
    ("かるい", "軽い"),
    ("やさしい", "優しい"),
    ("むずかしい", "難しい"),
    ("すごい", "凄い"),
    ("すばらしい", "素晴らしい"),
    ("うつくしい", "美しい"),
    ("めずらしい", "珍しい"),
    ("あぶない", "危ない"),
    ("ただしい", "正しい"),
    ("くるしい", "苦しい"),
    ("はずかしい", "恥ずかしい"),
    ("くわしい", "詳しい"),
    ("かわいい", "可愛い"),
    ("かっこいい", "かっこいい"),
    ("やばい", "やばい"),
    // 日常動詞
    ("いく", "行く"),
    ("いきたい", "行きたい"),
    ("いった", "行った"),
    ("くる", "来る"),
    ("きたい", "来たい"),
    ("きた", "来た"),
    ("かえる", "帰る"),
    ("かえりたい", "帰りたい"),
    ("かえった", "帰った"),
    ("たべる", "食べる"),
    ("たべたい", "食べたい"),
    ("たべた", "食べた"),
    ("のむ", "飲む"),
    ("のみたい", "飲みたい"),
    ("のんだ", "飲んだ"),
    ("ねる", "寝る"),
    ("ねたい", "寝たい"),
    ("ねた", "寝た"),
    ("おきる", "起きる"),
    ("おきたい", "起きたい"),
    ("おきた", "起きた"),
    ("みる", "見る"),
    ("みたい", "見たい"),
    ("みた", "見た"),
    ("きく", "聞く"),
    ("ききたい", "聞きたい"),
    ("きいた", "聞いた"),
    ("はなす", "話す"),
    ("はなしたい", "話したい"),
    ("はなした", "話した"),
    ("よむ", "読む"),
    ("よみたい", "読みたい"),
    ("よんだ", "読んだ"),
    ("かく", "書く"),
    ("かきたい", "書きたい"),
    ("かいた", "書いた"),
    ("あそぶ", "遊ぶ"),
    ("あそびたい", "遊びたい"),
    ("あそんだ", "遊んだ"),
    ("あるく", "歩く"),
    ("あるいた", "歩いた"),
    ("はしる", "走る"),
    ("はしった", "走った"),
    ("まつ", "待つ"),
    ("まった", "待った"),
    ("つくる", "作る"),
    ("つくった", "作った"),
    ("かう", "買う"),
    ("かった", "買った"),
    ("うる", "売る"),
    ("うった", "売った"),
    ("おもう", "思う"),
    ("おもった", "思った"),
    ("いう", "言う"),
    ("いった", "言った"),
    ("しる", "知る"),
    ("しっている", "知っている"),
    ("わかる", "分かる"),
    ("わかった", "分かった"),
    ("わかりました", "分かりました"),
    ("できる", "出来る"),
    ("できた", "出来た"),
    ("がんばる", "頑張る"),
    ("がんばります", "頑張ります"),
    ("たすける", "助ける"),
    ("たすかった", "助かった"),
    ("おしえる", "教える"),
    ("ならう", "習う"),
    ("かんがえる", "考える"),
    // 日常名詞
    ("ともだち", "友達"),
    ("かぞく", "家族"),
    ("こども", "子供"),
    ("おとな", "大人"),
    ("ごはん", "ご飯"),
    ("あさごはん", "朝ご飯"),
    ("ひるごはん", "昼ご飯"),
    ("ばんごはん", "晩ご飯"),
    ("いえ", "家"),
    ("へや", "部屋"),
    ("くるま", "車"),
    ("でんしゃ", "電車"),
    ("ばす", "バス"),
    ("ほん", "本"),
    ("おんがく", "音楽"),
    ("えいが", "映画"),
    ("しゃしん", "写真"),
    ("うみ", "海"),
    ("やま", "山"),
    ("かわ", "川"),
    ("そら", "空"),
    ("はな", "花"),
    ("いぬ", "犬"),
    ("ねこ", "猫"),
    ("きもち", "気持ち"),
    ("こころ", "心"),
    ("からだ", "体"),
    ("あたま", "頭"),
    ("め", "目"),
    ("て", "手"),
    ("あし", "足"),
];

const TYPO_TABLE: &[(&str, &str)] = &[
    ("きんにちは", "こんにちは"),
    ("こんちは", "こんにちは"),
    ("こんちわ", "こんにちは"),
    ("ありがとうごづいます", "ありがとうございます"),
    ("よろしくょおねがい", "よろしくおねがい"),
    ("おつくれさま", "お疲れ様"),
    ("おつれさま", "お疲れ様"),
    ("おひょ", "おはよう"),
    ("おはよぅ", "おはよう"),
    ("てそと", "テスト"),
    ("さびす", "サービス"),
    ("くらおど", "クラウド"),
    ("クラおど", "クラウド"),
    ("かたん", "簡単"),
    ("しぶみ", "新聞"),
    ("かさおさす", "傘を差す"),
    ("じかんわ", "時間は"),
    ("だいじょぶ", "大丈夫"),
    ("へんかん", "変換"),
    ("へんこう", "変更"),
    ("かくにん", "確認"),
    ("かくにんします", "確認します"),
    ("にゅうしよく", "入所"),
    ("りようほう", "両方"),
    ("ゆざ", "ユーザー"),
    ("こぴ", "コピー"),
    ("けか", "結果"),
    ("いてらっしゃい", "いってらっしゃい"),
    ("しますた", "しました"),
    ("ごかくに", "ご確認"),
    ("であある", "である"),
    ("でああった", "であった"),
    ("でああり", "であり"),
    ("なっかた", "なかった"),
    ("きかっけ", "きっかけ"),
    ("ことろ", "ところ"),
    ("だたし", "ただし"),
    ("られれて", "られて"),
    ("られれた", "られた"),
    ("かからわ", "かかわら"),
    ("あたな", "あなた"),
    ("おそよ", "およそ"),
    ("いすれ", "いずれ"),
    ("ざまざまな", "さまざまな"),
    ("ずべて", "すべて"),
    ("すれは", "すれば"),
    ("できす", "できず"),
    ("なけければ", "なければ"),
    ("でききる", "できる"),
    ("ししかし", "しかし"),
    ("あるいいは", "あるいは"),
    ("においいて", "において"),
    ("そののまま", "そのまま"),
    ("なかかった", "なかった"),
    ("ファション", "ファッション"),
    ("ショピング", "ショッピング"),
    ("ヨーロパ", "ヨーロッパ"),
    ("フィランド", "フィンランド"),
    ("グリーランド", "グリーンランド"),
    ("エンディグ", "エンディング"),
    ("エンディンググ", "エンディング"),
    ("オープニンググ", "オープニング"),
    ("ラインセンス", "ライセンス"),
    ("キャラクラー", "キャラクター"),
    ("アミニズム", "アニミズム"),
    ("グアマテラ", "グアテマラ"),
    ("スッタフ", "スタッフ"),
    ("そんあ", "そんな"),
    ("ちゃんえる", "チャンネル"),
    ("こんいちは", "こんにちは"),
    ("どんあ", "どんな"),
    ("とんえるず", "とんねるず"),
    ("いろんあ", "いろんな"),
    ("あんあ", "あんな"),
    ("みんあ", "みんな"),
    ("おんあ", "おんな"),
    ("しmばし", "新橋"),
    ("なmば", "難波"),
    ("さmぽ", "散歩"),
    ("しmぶん", "新聞"),
    ("てmぷら", "天ぷら"),
    ("かmぱい", "乾杯"),
    ("せmぱい", "先輩"),
    ("えmぴつ", "鉛筆"),
    ("しmぱい", "心配"),
    ("かmぺき", "完璧"),
    ("きゅじょう", "球場"),
    ("りゅせい", "流星"),
    ("しゅねん", "周年"),
    ("ちゅけい", "中継"),
    ("ちゅこう", "中高"),
    ("ちゅしん", "中心"),
    ("きゅり", "きゅうり"),
    ("ちゅごく", "中国"),
    ("ちゅしゃじょう", "駐車場"),
    ("ちゅい", "注意"),
    ("うちゅせん", "宇宙船"),
    ("きゅけい", "休憩"),
    ("きゅきゅう", "救急"),
    ("きゅじつ", "休日"),
    ("きゅりょう", "給料"),
    ("きゅそく", "休息"),
    ("きゅしゅう", "九州"),
    ("きゅじょ", "救助"),
    ("しゅまつ", "週末"),
    ("しゅにゅう", "収入"),
    ("りゅがく", "留学"),
    ("りゅこう", "流行"),
    ("りゅつう", "流通"),
    ("にゅがく", "入学"),
    ("にゅいん", "入院"),
    ("にゅりょく", "入力"),
    ("れにょうけい", "連用形"),
    ("にほにゅうびん", "日本郵便"),
    ("せにょう", "専用"),
    ("きにょうび", "金曜日"),
    ("ぜにょう", "全容"),
    ("てにょう", "転用"),
    ("せにょうしゃ", "専用車"),
    ("せにょうせん", "専用線"),
];

/// 語と語の境界とみなす助詞・記号。語中の誤入力判定にのみ使う。
const WORD_BOUNDARY_CHARS: &[char] = &[
    ' ', '　', '、', '。', '！', '？', '!', '?', ',', '.', '・', '，', '．', '：', ':', '；', ';',
    'を', 'に', 'は', 'が', 'で', 'と', 'へ', 'も', 'の', 'ね', 'よ', 'な', 'か', 'ば', 'や', 'ら',
];

/// 💡 もしかして 候補。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TypoSuggestion {
    /// 補正後の文字列 (読み全体)。
    pub surface: String,
    /// 補正対象となった読みの断片。
    pub token_reading: String,
    /// 補正後の表記 (断片単位)。
    pub token_surface: String,
    /// 学習済みデータから得られた候補か。
    pub learned: bool,
}

/// 生成直後の「もしかして」誤字補正候補 (確定通知の突合用)。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GeneratedTypoCandidate {
    /// 補正対象の読みの断片。
    pub token_reading: String,
    /// 補正後の表記。
    pub token_surface: String,
    /// 補正後の文字列 (読み全体)。
    pub surface: String,
}

/// 直近に生成した「💡 もしかして」候補を記憶する。
pub fn remember_typo_candidate(suggestion: &TypoSuggestion) {
    if let Ok(mut guard) = LAST_TYPO_CANDIDATE.lock() {
        *guard = Some(GeneratedTypoCandidate {
            token_reading: suggestion.token_reading.clone(),
            token_surface: suggestion.token_surface.clone(),
            surface: suggestion.surface.clone(),
        });
    }
}

/// 確定通知と突き合わせるために直近の候補を取り出す。
///
/// 確定された文字列が直近の候補と一致する場合のみ取り出す (不一致の場合は
/// 候補を保持し、次回の生成で上書きされる)。
pub fn take_typo_candidate(surface: &str) -> Option<GeneratedTypoCandidate> {
    let mut guard = LAST_TYPO_CANDIDATE.lock().ok()?;
    let candidate = guard.as_ref()?;
    if candidate.surface != surface {
        return None;
    }
    guard.take()
}

/// 💡 もしかして (AI 誤字補正候補の生成)
pub fn fetch_moshikashite_candidate(
    context: &str,
    raw_hiragana: &str,
    config: &AiAssistConfig,
) -> Option<String> {
    fetch_typo_suggestion(context, raw_hiragana, config).map(|suggestion| suggestion.surface)
}

/// 💡 もしかして 候補の生成 (学習済みデータとトークン情報を含む)。
///
/// 判定順は次の通り。
/// 1. 学習済みデータ (Phase 4) — 0ms で最優先。
/// 2. 静的誤字テーブル・音韻規則 (既存挙動)。
/// 3. Damerau-Levenshtein 編集距離 (既存挙動)。
/// 4. QWERTY 隣接キー変種 + 軽量辞書インデックス (Phase 3)。
pub fn fetch_typo_suggestion(
    context: &str,
    raw_hiragana: &str,
    config: &AiAssistConfig,
) -> Option<TypoSuggestion> {
    if !config.enabled {
        return None;
    }

    let hiragana = raw_hiragana.trim();
    if hiragana.chars().count() < 2 {
        return None;
    }

    eprintln!(
        "[TypoDebug] fetch_typo_suggestion: raw_hiragana='{}'",
        raw_hiragana
    );
    // 0.5. ローマ字子音浮き・混入タイポ (例: 「おｋせり」->「おかえり」, 「おｔかれ」->「おつかれ」)
    if let Some(decoded_hira) = romaji_decoder::decode_floating_consonant_typo(raw_hiragana) {
        eprintln!(
            "[TypoDebug] decode_floating_consonant_typo HIT: raw='{}' -> decoded='{}'",
            raw_hiragana, decoded_hira
        );
        let surface =
            typo_variant::lookup_surface(&decoded_hira).unwrap_or_else(|| decoded_hira.clone());
        return Some(TypoSuggestion {
            surface,
            token_reading: raw_hiragana.to_string(),
            token_surface: decoded_hira,
            learned: false,
        });
    }

    // 0. 学習済みデータ: ユーザーが過去に採用した誤字補正を最優先で提示する。
    if let Some(matched) = lookup_learned_typo(hiragana) {
        let prefix = hiragana.strip_suffix(&matched.reading).unwrap_or_default();
        return Some(TypoSuggestion {
            surface: format!("{prefix}{}", matched.surface),
            token_reading: matched.reading,
            token_surface: matched.surface,
            learned: true,
        });
    }

    // 1. 完全一致 (TYPO_TABLE)
    for &(typo, fix) in TYPO_TABLE {
        if hiragana == typo {
            return Some(TypoSuggestion {
                surface: fix.to_string(),
                token_reading: typo.to_string(),
                token_surface: fix.to_string(),
                learned: false,
            });
        }
    }

    // 2. 先頭一致（誤入力の後に続きがあるケース）。最長一致を優先する。
    let mut prefix_match: Option<(usize, &str)> = None;
    for &(typo, fix) in TYPO_TABLE {
        if !hiragana.starts_with(typo) {
            continue;
        }
        let typo_len = typo.chars().count();
        if prefix_match.is_none_or(|(best_len, _)| typo_len > best_len) {
            prefix_match = Some((typo_len, fix));
        }
    }
    if let Some((typo_len, fix)) = prefix_match {
        let tail: String = hiragana.chars().skip(typo_len).collect();
        return Some(TypoSuggestion {
            surface: format!("{fix}{tail}"),
            token_reading: hiragana.chars().take(typo_len).collect(),
            token_surface: fix.to_string(),
            learned: false,
        });
    }

    // 3. 語中一致（助詞などで区切られた語の中に誤入力がある場合）
    if let Some(correction) = correction_for_embedded_typo(hiragana) {
        return Some(correction);
    }

    // 4. Mozc KeyCorrector / 音韻規則による補正 (促音・撥音・長音・助詞)
    if let Some(rule_fix) = rule_based_typo_correction(hiragana) {
        return Some(TypoSuggestion {
            surface: rule_fix.clone(),
            token_reading: hiragana.to_string(),
            token_surface: rule_fix,
            learned: false,
        });
    }

    // 5. Damerau-Levenshtein 編集距離による標準語彙検索 (全文距離 1、QWERTY 近接ミス優先)
    let mut best_vocab_match: Option<(bool, &str)> = None;
    for &(v_reading, v_surface) in STANDARD_VOCAB {
        if hiragana == v_reading {
            continue;
        }
        let dist = damerau_levenshtein_distance(hiragana, v_reading);
        if dist == 1 {
            let qwerty_adj = has_qwerty_adjacent_difference(hiragana, v_reading);
            if qwerty_adj {
                return Some(TypoSuggestion {
                    surface: v_surface.to_string(),
                    token_reading: hiragana.to_string(),
                    token_surface: v_surface.to_string(),
                    learned: false,
                });
            } else if best_vocab_match.is_none() {
                best_vocab_match = Some((false, v_surface));
            }
        }
    }
    if let Some((_, surface)) = best_vocab_match {
        return Some(TypoSuggestion {
            surface: surface.to_string(),
            token_reading: hiragana.to_string(),
            token_surface: surface.to_string(),
            learned: false,
        });
    }

    // 6. プレフィックス Damerau-Levenshtein 編集距離による誤字補正
    // （例: 「おあようございます」の先頭「おあよう」が「おはよう」に距離1で補正され、「おはようございます」を生成）
    let hiragana_chars: Vec<char> = hiragana.chars().collect();
    let total_len = hiragana_chars.len();

    // プレフィックス誤字は QWERTY 近接ミス優先、次いで最長一致を優先する
    let mut best_prefix_correction: Option<(bool, usize, String, String, String)> = None;

    for &(v_reading, v_surface) in STANDARD_VOCAB {
        let v_len = v_reading.chars().count();
        if v_len < 3 || v_len >= total_len {
            continue;
        }

        // 既に入力先頭が辞書語と完全に一致している場合は、プレフィックス誤字ではない（正しい入力）のでスキップ
        if hiragana.starts_with(v_reading) {
            continue;
        }

        // 辞書語の長さ ±1 の範囲でプレフィックスを切り出して比較
        let min_cand_len = if v_len > 1 { v_len - 1 } else { 1 };
        let max_cand_len = (v_len + 1).min(total_len - 1);

        for cand_len in min_cand_len..=max_cand_len {
            let prefix: String = hiragana_chars[..cand_len].iter().collect();
            if prefix == v_reading {
                continue;
            }

            // 【重要】切り出した prefix 自体が辞書に存在する正当な単語（例: 「きょう」）である場合、
            // それは誤字ではなく意図された入力なので、別の単語（例: 「きのう」）へ勝手に補正しない
            if STANDARD_VOCAB.iter().any(|&(r, _)| r == prefix) {
                continue;
            }

            let dist = damerau_levenshtein_distance(&prefix, v_reading);
            if dist == 1 {
                let qwerty_adj = has_qwerty_adjacent_difference(&prefix, v_reading);
                let tail: String = hiragana_chars[cand_len..].iter().collect();
                let full = format!("{}{}", v_surface, tail);
                // QWERTY隣接ミス (qwerty_adj: bool) を最優先し、次に語長 (v_len: usize) で比較
                if best_prefix_correction
                    .as_ref()
                    .map_or(true, |(best_adj, best_len, _, _, _)| {
                        (qwerty_adj, v_len) > (*best_adj, *best_len)
                    })
                {
                    best_prefix_correction =
                        Some((qwerty_adj, v_len, full, prefix, v_surface.to_string()));
                }
            }
        }
    }

    if let Some((_, _, full, prefix, v_surface)) = best_prefix_correction {
        // ZeroCon Model による文脈リランキング検証 (文脈 context がある場合)
        if !context.is_empty() && config.predictive_model == "zerocon" {
            let target_katakana = to_katakana(&full);
            let prompt = format!("\u{EE02}{}\u{EE00}{}\u{EE01}", context, target_katakana);
            let payload = json!({
                "prompt": prompt,
                "n_predict": 16,
                "temperature": 0.0,
                "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]
            });
            if let Some(res) = simple_http_post(config.zenzai_port, "/completion", &payload, 60) {
                if let Some(content) = res.get("content").and_then(|c| c.as_str()) {
                    let trimmed = content.trim();
                    if !trimmed.is_empty() {
                        return Some(TypoSuggestion {
                            surface: trimmed.to_string(),
                            token_reading: prefix,
                            token_surface: v_surface,
                            learned: false,
                        });
                    }
                }
            }
        }
        return Some(TypoSuggestion {
            surface: full,
            token_reading: prefix,
            token_surface: v_surface,
            learned: false,
        });
    }

    // 7. QWERTY 隣接キー変種 + 軽量辞書インデックス (Phase 3)
    if let Some(correction) = find_qwerty_correction(hiragana) {
        return Some(TypoSuggestion {
            surface: correction.surface,
            token_reading: correction.token_reading,
            token_surface: correction.token_surface,
            learned: false,
        });
    }

    // 8. 単語・文節単位 ニューラル特化モデル (xsmall, port 18090) フォールバック
    // 辞書やルールで解決できなかった未知のタイポを、左文脈 + 単語ウィンドウで高精度に補正する
    if true {
        // 末尾の単語・文節ウィンドウ（最大8文字）を抽出
        let chars: Vec<char> = hiragana.chars().collect();
        let win_len = chars.len().min(8);
        let split_idx = chars.len() - win_len;
        let prefix_part: String = chars[..split_idx].iter().collect();
        let target_part: String = chars[split_idx..].iter().collect();

        let effective_ctx = if !context.is_empty() {
            format!("{context}{prefix_part}")
        } else {
            prefix_part.clone()
        };

        let target_katakana = to_katakana(&target_part);
        let prompt = if !effective_ctx.is_empty() {
            format!("\u{EE02}{effective_ctx}\u{EE00}{target_katakana}\u{EE01}")
        } else {
            format!("\u{EE00}{target_katakana}\u{EE01}")
        };

        let payload = json!({
            "prompt": prompt,
            "n_predict": 8,
            "temperature": 0.0,
            "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]
        });

        // zenzai_port (または補正モデルポート) へ超低遅延で問い合わせ
        if let Some(res) = simple_http_post(config.lfm_port, "/completion", &payload, 150) {
            if let Some(content) = res.get("content").and_then(|c| c.as_str()) {
                let trimmed = content.trim();
                if !trimmed.is_empty() && trimmed != target_part && trimmed != target_katakana {
                    return Some(TypoSuggestion {
                        surface: format!("{prefix_part}{trimmed}"),
                        token_reading: target_part,
                        token_surface: trimmed.to_string(),
                        learned: false,
                    });
                }
            }
        }
    }

    None
}

/// 音韻規則による動的誤入力補正
fn rule_based_typo_correction(hiragana: &str) -> Option<String> {
    let chars: Vec<char> = hiragana.chars().collect();
    let len = chars.len();

    // ルールA: 促音 'っ' の抜け（2文字語: けか -> けっか）
    if len == 2 {
        let candidate: String = [chars[0], 'っ', chars[1]].iter().collect();
        for &(v_reading, v_surface) in STANDARD_VOCAB {
            if candidate == v_reading {
                return Some(v_surface.to_string());
            }
        }
    }

    // ルールB: 撥音 'ん' の抜け（かたん -> かんたん）
    if len >= 2 {
        for idx in 1..len {
            let mut candidate = String::with_capacity(hiragana.len() + 3);
            candidate.extend(&chars[..idx]);
            candidate.push('ん');
            candidate.extend(&chars[idx..]);
            for &(v_reading, v_surface) in STANDARD_VOCAB {
                if candidate == v_reading {
                    return Some(v_surface.to_string());
                }
            }
        }
    }

    // ルールC: 長音 'ー' の脱落（さびす -> サービス）
    let with_chouon = format!("{}ー", hiragana);
    for &(v_reading, v_surface) in STANDARD_VOCAB {
        if with_chouon == v_reading {
            return Some(v_surface.to_string());
        }
    }

    // ルールD: 助詞 "わ" -> "は"
    if hiragana.ends_with('わ') {
        let mut candidate = hiragana[..hiragana.len() - 'わ'.len_utf8()].to_string();
        candidate.push('は');
        for &(v_reading, v_surface) in STANDARD_VOCAB {
            if candidate == v_reading {
                return Some(v_surface.to_string());
            }
        }
    }

    // ルールE: QWERTY母音隣接ミス 'u' <-> 'i' による形容詞誤字「〜すい」 -> 「〜しい」
    // (例: うれすい -> 嬉しい、たのすい -> 楽しい、おいすい -> 美味しい)
    if hiragana.ends_with("すい") && hiragana.chars().count() >= 3 {
        let prefix = &hiragana[..hiragana.len() - "すい".len()];
        let candidate = format!("{}しい", prefix);
        for &(v_reading, v_surface) in STANDARD_VOCAB {
            if candidate == v_reading {
                return Some(v_surface.to_string());
            }
        }
    }

    // ルールF: QWERTY母音隣接ミス 「〜すかった」 -> 「〜しかった」
    if hiragana.ends_with("すかった") && hiragana.chars().count() >= 4 {
        let prefix = &hiragana[..hiragana.len() - "すかった".len()];
        let candidate = format!("{}しかった", prefix);
        for &(v_reading, v_surface) in STANDARD_VOCAB {
            if candidate == v_reading {
                return Some(v_surface.to_string());
            }
        }
    }

    // ルールG: QWERTY母音隣接ミス 「〜すく」 -> 「〜しく」
    if hiragana.ends_with("すく") && hiragana.chars().count() >= 3 {
        let prefix = &hiragana[..hiragana.len() - "すく".len()];
        let candidate = format!("{}しく", prefix);
        for &(v_reading, v_surface) in STANDARD_VOCAB {
            if candidate == v_reading {
                return Some(v_surface.to_string());
            }
        }
    }

    None
}

/// 語中に含まれる誤入力を見付け、その部分だけ正しい読みへ差し替える。
fn correction_for_embedded_typo(hiragana: &str) -> Option<TypoSuggestion> {
    let char_positions: Vec<(usize, char)> = hiragana.char_indices().collect();

    for (byte_index, boundary_char) in char_positions.iter().copied() {
        if !WORD_BOUNDARY_CHARS.contains(&boundary_char) {
            continue;
        }

        let rest = &hiragana[byte_index + boundary_char.len_utf8()..];
        let mut best: Option<(&str, &str)> = None;
        for &(typo, fix) in TYPO_TABLE {
            if typo.chars().count() < 3 {
                continue;
            }
            if !rest.starts_with(typo) {
                continue;
            }
            let after = &rest[typo.len()..];
            let ends_at_boundary = after
                .chars()
                .next()
                .is_none_or(|next| WORD_BOUNDARY_CHARS.contains(&next));
            if !ends_at_boundary {
                continue;
            }
            if best.is_none_or(|(best_typo, _)| typo.chars().count() > best_typo.chars().count()) {
                best = Some((typo, fix));
            }
        }

        if let Some((typo, fix)) = best {
            let mut corrected = String::new();
            corrected.push_str(&hiragana[..byte_index + boundary_char.len_utf8()]);
            corrected.push_str(fix);
            corrected.push_str(&rest[typo.len()..]);
            return Some(TypoSuggestion {
                surface: corrected,
                token_reading: typo.to_string(),
                token_surface: fix.to_string(),
                learned: false,
            });
        }
    }

    None
}

/// 🔮 先読み (AI 予測補完候補の生成)
pub fn fetch_predictive_candidate(
    context: &str,
    partial_surface: &str,
    config: &AiAssistConfig,
) -> Option<String> {
    fetch_predictive_candidate_internal(context, partial_surface, config, false)
}

/// モデル推論で得た先読みが「提示する価値があるか」を判定する。
///
/// 推論出力には辞書ベースの上書きと異なり、的外れや短い断片が混ざる。
/// 誤った先読みは注意を引いて実入力を遅らせるため、明確に使えないものは
/// 提示しない。IME は句点や記号を単独で確定することはないので、
/// 文末記号だけの先読みも提示価値がないものとして弾く。
fn is_useful_predictive_continuation(continuation: &str) -> bool {
    let trimmed = continuation.trim();
    if trimmed.chars().count() < 2 {
        return false;
    }
    // 文末記号だけの先読みは情報がない
    !trimmed.chars().all(|ch| {
        matches!(
            ch,
            '。' | '、' | '！' | '!' | '?' | '？' | '.' | ',' | ' ' | '\t'
        )
    })
}

/// 内部用・テスト用（skip_debounce を指定可能）
pub fn fetch_predictive_candidate_internal(
    context: &str,
    partial_surface: &str,
    config: &AiAssistConfig,
    skip_debounce: bool,
) -> Option<String> {
    if !config.enabled || partial_surface.chars().count() < 2 {
        return None;
    }

    // 1. 高頻度定型句キャッシュ（0ms 即応、22句）
    // （※定型句はデバウンス判定の前に行い、連打中であっても即座にヒットさせる）
    const PREDICTIVE_PHRASES: &[(&str, &str)] = &[
        ("いつもお世話に", "なっております。"),
        ("よろしくお願い", "いたします。"),
        ("よろしくお願いし", "ます。"),
        ("お疲れ様", "でした。"),
        ("お疲れ様です", "。お疲れ様でした。"),
        ("おはよう", "ございます！"),
        ("おはようご", "ざいます。"),
        ("ありがとうご", "ざいます！"),
        ("ありがとうございます", "。助かりました。"),
        ("申し訳", "ございません。"),
        ("申し訳あり", "ません。"),
        ("ご確認のほど", "よろしくお願いいたします。"),
        ("ご連絡いただき", "ありがとうございます。"),
        ("ご対応いただき", "ありがとうございます。"),
        ("お手数をおかけ", "しますが、よろしくお願いいたします。"),
        ("引き続きよろしく", "お願いいたします。"),
        ("何卒よろしく", "お願いいたします。"),
        ("ご検討のほど", "よろしくお願いいたします。"),
        ("確認いた", "しました。"),
        ("承知いた", "しました。"),
        ("かしこまり", "ました。"),
        ("了解いた", "しました。"),
        ("失礼いた", "します。"),
        ("返信が遅れ", "申し訳ありません。"),
        ("現在原因を調査", "中です。"),
        ("機能の実装が完了", "いたしました。"),
        ("進捗状況をご報告", "いたします。"),
        ("メンテナンス作業は無事終了", "しました。"),
        ("最新バージョンを公開", "しました。"),
        ("ご都合のよろしい", "日時をご教示ください。"),
        ("日程の調整を", "お願いできますでしょうか。"),
        ("お忙しいところ恐縮ですが、", "ご確認をお願いいたします。"),
        ("添付の資料を", "ご覧ください。"),
        ("出欠のご連絡を", "お願いいたします。"),
        ("プルリクエストを", "作成しました。"),
        ("デプロイが正常に", "完了しました。"),
        ("エラーログを", "確認してください。"),
        ("タイポの修正と", "リファクタリングを行いました。"),
        ("今からそちらへ", "向かいます。"),
        ("ぜひ参加させて", "いただきます。"),
        ("今後ともよろしく", "お願いいたします。"),
        ("取り急ぎ", "ご報告まで。"),
    ];

    for &(prefix, cont) in PREDICTIVE_PHRASES {
        // 入力が定型句 prefix を含んで (以上に) 打たれている場合 → 続きだけを返す
        if partial_surface.ends_with(prefix)
            && partial_surface.chars().count() >= prefix.chars().count()
        {
            return Some(cont.to_string());
        }
        // 入力が prefix の途中 (prefix の前方一致) の場合 → 残りの prefix + 続きを返す
        let prefix_chars: Vec<char> = prefix.chars().collect();
        let partial_chars: Vec<char> = partial_surface.chars().collect();
        if prefix_chars.starts_with(&partial_chars) && partial_chars.len() >= 2 {
            let rest: String = prefix_chars[partial_chars.len()..].iter().collect();
            return Some(format!("{}{}", rest, cont));
        }
    }

    // 1.5 ユーザー確定履歴キャッシュ照合 (User History Cache / 0ms 即応)
    // ユーザー自身が直近に入力・確定したフレーズに前方一致すれば、その続きを最優先で提示
    if let Ok(guard) = USER_HISTORY_CACHE.lock() {
        for history_item in guard.iter() {
            if history_item.starts_with(partial_surface) && history_item != partial_surface {
                let continuation = &history_item[partial_surface.len()..];
                if !continuation.trim().is_empty() {
                    return Some(continuation.to_string());
                }
            }
        }
    }

    // 2. プロンプト構築 & 直近キャッシュチェック（同一プロンプトなら 0ms で即応）
    let full_prompt = if context.is_empty() {
        partial_surface.to_string()
    } else {
        format!("{}{}", context, partial_surface)
    };

    if let Ok(guard) = LFM_CACHE.lock() {
        if let Some((cached_prompt, cached_res)) = &*guard {
            if cached_prompt == &full_prompt {
                return Some(cached_res.clone());
            }
        }
    }

    // 3. デバウンス判定（直前打鍵からの経過時間チェック）
    // 高速タイピング中（打鍵間隔が DEBOUNCE_THRESHOLD_MS 未満）は LFM 2.5 の同期呼び出しをスキップ
    if !skip_debounce {
        let now = Instant::now();
        let is_rapid_typing = {
            let mut last = LAST_KEY_INSTANT.lock().unwrap();
            let rapid = if let Some(prev) = *last {
                now.duration_since(prev) < Duration::from_millis(DEBOUNCE_THRESHOLD_MS)
            } else {
                false
            };
            *last = Some(now);
            rapid
        };

        if is_rapid_typing {
            return None;
        }
    }

    // 4. 重複リクエスト抑制（In-flight ガード: 前のリクエストが処理中なら即座にスキップ）
    if LFM_IN_FLIGHT
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        return None;
    }
    let _in_flight_guard = InFlightGuard;

    // 5. 先読み推論エンジン実行 (ZeroCon Model on RTX 4070 / LFM 2.5)
    if config.predictive_model == "zerocon" {
        // ZeroCon Model v3.2 (RTX 4070 Vulkan / Port 8090) による超高速・日本語特化先読み
        let target_katakana = to_katakana(partial_surface);
        let prompt = format!("\u{EE02}{}\u{EE00}{}", context, target_katakana);
        let payload = json!({
            "prompt": prompt,
            "n_predict": 16,
            "temperature": 0.0,
            "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]
        });

        if let Some(res) = simple_http_post(config.zenzai_port, "/completion", &payload, 100) {
            if let Some(content) = res.get("content").and_then(|c| c.as_str()) {
                let trimmed = content.trim();
                if !trimmed.is_empty() {
                    // カタカナ出力をひらがな変換（句読点等の記号は維持）
                    let hiragana_cand = katakana_to_hiragana(trimmed);
                    let sentence_end = hiragana_cand
                        .find('。')
                        .map(|i| i + '。'.len_utf8())
                        .unwrap_or(hiragana_cand.len());
                    let result_str = hiragana_cand[..sentence_end].trim().to_string();
                    if is_useful_predictive_continuation(&result_str) {
                        if let Ok(mut guard) = LFM_CACHE.lock() {
                            *guard = Some((full_prompt, result_str.clone()));
                        }
                        return Some(result_str);
                    }
                }
            }
        }
        // ポート 8090 (Zenzai) が未起動・未接続の場合は、常駐している内蔵推論エンジン (Port 18090) へフォールバック
    }

    // 内蔵 LFM 2.5 Text Completion (Port 18090)
    let payload = json!({
        "model": config.resolved_model_id(),
        "prompt": full_prompt,
        "max_tokens": 12,
        "temperature": 0.1,
        "stop": ["\n"]
    });

    let timeout_ms = 150;
    if let Some(res) = simple_http_post(config.lfm_port, "/v1/completions", &payload, timeout_ms) {
        if let Some(choices) = res.get("choices").and_then(|c| c.as_array()) {
            if let Some(text) = choices
                .get(0)
                .and_then(|c| c.get("text"))
                .and_then(|t| t.as_str())
            {
                let trimmed = text.trim();
                if !trimmed.is_empty() && trimmed.chars().count() >= 2 {
                    let sentence_end = trimmed
                        .find('。')
                        .map(|i| i + '。'.len_utf8())
                        .unwrap_or(trimmed.len());
                    let result_str = trimmed[..sentence_end].trim().to_string();
                    if is_useful_predictive_continuation(&result_str) {
                        if let Ok(mut guard) = LFM_CACHE.lock() {
                            *guard = Some((full_prompt, result_str.clone()));
                        }
                        return Some(result_str);
                    }
                }
            }
        }
    }

    None
}

#[cfg(test)]
mod tests {
    use super::{damerau_levenshtein_distance, fetch_moshikashite_candidate, AiAssistConfig};

    #[test]
    fn test_user_history_cache_prediction() {
        let conf = config();
        super::record_user_committed_text("お世話になっております。本日の打ち合わせの件です。");
        let pred = super::fetch_predictive_candidate("", "本日の打ち合わせ", &conf);
        assert_eq!(pred, Some("の件です。".to_string()));
    }

    #[test]
    fn test_qwerty_adjacent() {
        assert!(super::is_qwerty_adjacent('h', 'g'));
        assert!(super::is_qwerty_adjacent('h', 'j'));
        assert!(super::is_qwerty_adjacent('o', 'p'));
        assert!(!super::is_qwerty_adjacent('a', 'p'));
        assert!(super::is_hiragana_qwerty_adjacent('き', 'こ')); // ki vs ko (i and o are adjacent)
        assert!(super::has_qwerty_adjacent_difference(
            "きんにちは",
            "こんにちは"
        ));
    }

    #[test]
    fn test_kana_converters() {
        assert_eq!(super::to_katakana("よろしく"), "ヨロシク");
        assert_eq!(super::katakana_to_hiragana("シマス。"), "します。");
    }

    fn config() -> AiAssistConfig {
        AiAssistConfig {
            enabled: true,
            // 推論ポートは未使用ポートへ寄せる。誤字補正のフォールバックは
            // ローカルの推論サーバーへ HTTP 接続するため、その起動の有無で
            // 結果が変わるテストを避け、決定的にする。
            lfm_port: 1,
            zenzai_port: 1,
            ..AiAssistConfig::default()
        }
    }

    #[test]
    fn test_damerau_levenshtein() {
        assert_eq!(damerau_levenshtein_distance("てそと", "てすと"), 1);
        assert_eq!(damerau_levenshtein_distance("きんにちは", "こんにちは"), 1);
        assert_eq!(damerau_levenshtein_distance("abc", "abc"), 0);
        assert_eq!(damerau_levenshtein_distance("abc", "acb"), 1);
    }

    #[test]
    fn corrects_an_exact_typing_mistake() {
        assert_eq!(
            fetch_moshikashite_candidate("", "きんにちは", &config()),
            Some("こんにちは".to_string())
        );
    }

    #[test]
    fn corrects_a_typing_mistake_followed_by_more_input() {
        assert_eq!(
            fetch_moshikashite_candidate("", "きんにちはそんべーる", &config()),
            Some("こんにちはそんべーる".to_string())
        );
    }

    #[test]
    fn corrects_a_typing_mistake_inside_a_sentence() {
        assert_eq!(
            fetch_moshikashite_candidate("", "きのうのきんにちは", &config()),
            Some("きのうのこんにちは".to_string())
        );
    }

    #[test]
    fn corrects_missing_double_consonant() {
        assert_eq!(
            fetch_moshikashite_candidate("", "けか", &config()),
            Some("結果".to_string())
        );
    }

    #[test]
    fn corrects_missing_nasal() {
        assert_eq!(
            fetch_moshikashite_candidate("", "かたん", &config()),
            Some("簡単".to_string())
        );
    }

    #[test]
    fn leaves_correct_readings_untouched() {
        assert_eq!(
            fetch_moshikashite_candidate("", "こんにちは", &config()),
            None
        );
        assert_eq!(
            fetch_moshikashite_candidate("", "きょうはいいてんき", &config()),
            None
        );
    }

    #[test]
    fn does_nothing_when_the_feature_is_disabled() {
        let mut disabled = config();
        disabled.enabled = false;
        assert_eq!(
            fetch_moshikashite_candidate("", "きんにちは", &disabled),
            None
        );
    }

    #[test]
    fn ignores_readings_that_are_too_short() {
        assert_eq!(fetch_moshikashite_candidate("", "あ", &config()), None);
    }

    #[test]
    fn resolves_predictive_continuation_and_full_text() {
        let conf = config();
        let first_surface = "いつもお世話に";
        let pred = super::fetch_predictive_candidate("", first_surface, &conf)
            .expect("should match prefix");

        let (full_text, continuation) = if let Some(stripped) = pred.strip_prefix(first_surface) {
            (pred.clone(), stripped.to_string())
        } else {
            (format!("{}{}", first_surface, pred), pred.clone())
        };

        assert_eq!(full_text, "いつもお世話になっております。");
        assert_eq!(continuation, "なっております。");
    }

    #[test]
    fn corrects_prefix_typo_with_suffix() {
        assert_eq!(
            fetch_moshikashite_candidate("", "おあようございます", &config()),
            Some("おはようございます".to_string())
        );
        assert_eq!(
            fetch_moshikashite_candidate("", "てそとする", &config()),
            Some("テストする".to_string())
        );
    }

    #[test]
    fn test_debounce_rapid_typing_skips_lfm() {
        let conf = config();
        // 直前打鍵時刻を現在時刻に明示的に設定
        if let Ok(mut last) = super::LAST_KEY_INSTANT.lock() {
            *last = Some(std::time::Instant::now());
        }
        // 直後（即座）の呼び出しは DEBOUNCE_THRESHOLD_MS (60ms) 未満のためスキップされること
        let res = super::fetch_predictive_candidate("", "てすとのにゅうりょくそのに", &conf);
        assert_eq!(res, None);
    }

    #[test]
    fn test_zerocon_mode_predictive_fallback() {
        let mut conf = config();
        conf.predictive_model = "zerocon".to_string();
        // 定型句キャッシュ
        let pred = super::fetch_predictive_candidate("", "よろしくお願い", &conf);
        assert_eq!(pred, Some("いたします。".to_string()));

        // ユーザー確定履歴キャッシュ
        super::record_user_committed_text("明日の会議室予約完了しました。");
        let pred_hist = super::fetch_predictive_candidate("", "明日の会議室", &conf);
        assert_eq!(pred_hist, Some("予約完了しました。".to_string()));
    }

    #[test]
    fn test_qwerty_adjacent_typo_uresui() {
        let conf = config();
        // 単体「うれすい」 -> 「嬉しい」
        assert_eq!(
            fetch_moshikashite_candidate("", "うれすい", &conf),
            Some("嬉しい".to_string())
        );
        // 接尾辞付き「うれすいです」 -> 「嬉しいです」
        assert_eq!(
            fetch_moshikashite_candidate("", "うれすいです", &conf),
            Some("嬉しいです".to_string())
        );
        // 「たのすい」 -> 「楽しい」
        assert_eq!(
            fetch_moshikashite_candidate("", "たのすい", &conf),
            Some("楽しい".to_string())
        );
        // 「おいすい」 -> 「美味しい」
        assert_eq!(
            fetch_moshikashite_candidate("", "おいすい", &conf),
            Some("美味しい".to_string())
        );
        // 「あすた」 -> 「明日」 (suta -> sita: u と i)
        assert_eq!(
            fetch_moshikashite_candidate("", "あすた", &conf),
            Some("明日".to_string())
        );
        // 「すごと」 -> 「仕事」 (sugoto -> sigoto: u と i)
        assert_eq!(
            fetch_moshikashite_candidate("", "すごと", &conf),
            Some("仕事".to_string())
        );
    }

    #[test]
    fn test_floating_consonant_typo_okseri() {
        let conf = config();
        assert_eq!(
            fetch_moshikashite_candidate("", "おｋせり", &conf),
            Some("おかえり".to_string())
        );
        assert_eq!(
            fetch_moshikashite_candidate("", "おｔかれ", &conf),
            Some("お疲れ".to_string())
        );
        assert_eq!(
            fetch_moshikashite_candidate("", "あｒがとう", &conf),
            Some("ありがとう".to_string())
        );
    }
}
