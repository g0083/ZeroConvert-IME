// ai_assist/mod.rs
// ZeroCon IME 実験的機能: 非破壊型「💡 もしかして」誤字補正 &「🔮 先読み」予測変換モジュール
//
// 世代交代のアシスト機能群は以下のように分割されている。
// - `dynamic_macro`: ⚡ 動的マクロ展開 (日付・時刻・年号・インライン計算)
// - `prediction`    : 🔮 ZeroCon Modelによる先読み
// - `typo_variant`  : 💡 QWERTY 隣接キー変種生成 & 軽量辞書インデックス
// - `learning`      : 🧠 「もしかして」確定時の自己学習フィードバック
//
// 補正はローカル辞書・学習と専用endpoint、先読みはZeroCon専用endpointを使う。
// 入力スレッドから同期呼び出ししない。

use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::TcpStream;
use std::sync::Mutex;
use std::time::{Duration, Instant};

pub mod corrector;
pub mod dynamic_macro;
pub mod keyboard_hybrid;
pub mod learning;
pub mod prediction;
mod reading_check;
pub mod romaji_decoder;
pub mod runtime;
pub mod translation;
pub mod typo_variant;

pub use dynamic_macro::{current_expansions, expand_dynamic_macros, MacroCandidate, MacroKind};
pub use learning::{lookup_learned_typo, record_learned_typo};
pub use typo_variant::{find_indexed_surface, find_qwerty_correction};

/// 直近に生成した「💡 もしかして」候補。
///
/// クライアントからの確定通知 (candidate_id 999901) と突き合わせるために保持する。
static LAST_TYPO_CANDIDATE: Mutex<Option<GeneratedTypoCandidate>> = Mutex::new(None);

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

#[derive(Debug, Clone)]
pub struct AiAssistConfig {
    pub enabled: bool,
    pub engine: String, // Corrector: "embedded" or "lm_studio"
    pub corrector_port: u16,
    pub prediction_port: u16,
}

impl Default for AiAssistConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            engine: "embedded".to_string(),
            corrector_port: 18090,
            prediction_port: runtime::PREDICTION_SERVER_PORT,
        }
    }
}

/// 超軽量HTTP POSTリクエスト（依存関係不要・即座にタイムアウト可能）
fn simple_http_post(port: u16, path: &str, body_json: &Value, timeout_ms: u64) -> Option<Value> {
    if let Some(response) = runtime::complete_override(port, path, body_json, timeout_ms) {
        return response;
    }
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
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
    let mut chunk = [0; 4096];
    loop {
        let remaining = deadline.checked_duration_since(Instant::now())?;
        stream.set_read_timeout(Some(remaining)).ok()?;
        let count = stream.read(&mut chunk).ok()?;
        if count == 0 {
            break;
        }
        response_buf.extend_from_slice(&chunk[..count]);
        if response_buf.len() > 128 * 1024 {
            return None;
        }
    }
    let response_str = String::from_utf8_lossy(&response_buf);

    if let Some(pos) = response_str.find("\r\n\r\n") {
        let json_part = &response_str[pos + 4..];
        serde_json::from_str(json_part).ok()
    } else {
        None
    }
}

/// Cancellable variant for long-running completions (translation). The read
/// loop polls in short slices; on cancellation the socket is dropped mid-read
/// so the server aborts the superseded generation instead of finishing it.
fn simple_http_post_cancellable(
    port: u16,
    path: &str,
    body_json: &Value,
    timeout_ms: u64,
    cancel: &translation::TranslationCancel,
) -> Option<Value> {
    if let Some(response) = runtime::complete_override(port, path, body_json, timeout_ms) {
        return response;
    }
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
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
    let mut chunk = [0; 4096];
    loop {
        if cancel.is_cancelled() {
            return None;
        }
        let remaining = deadline.checked_duration_since(Instant::now())?;
        // Poll in short slices so cancellation is noticed within ~200ms even
        // while the server is still generating the response body.
        stream
            .set_read_timeout(Some(remaining.min(Duration::from_millis(200))))
            .ok()?;
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(count) => response_buf.extend_from_slice(&chunk[..count]),
            Err(error)
                if error.kind() == std::io::ErrorKind::WouldBlock
                    || error.kind() == std::io::ErrorKind::TimedOut =>
            {
                if cancel.is_cancelled() {
                    return None;
                }
                continue;
            }
            Err(_) => return None,
        }
        if response_buf.len() > 128 * 1024 {
            return None;
        }
    }
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

pub use corrector::suggest as fetch_typo_suggestion;

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
fn heuristic_typo_suggestion(
    _context: &str,
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

    // 0.5. ローマ字子音浮き・混入タイポ (例: 「おｋせり」->「おかえり」, 「おｔかれ」->「おつかれ」)
    if let Some(decoded_hira) = romaji_decoder::decode_floating_consonant_typo(raw_hiragana) {
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

#[cfg(test)]
mod tests {
    use super::{damerau_levenshtein_distance, fetch_moshikashite_candidate, AiAssistConfig};

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
            corrector_port: 1,
            prediction_port: 1,
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
