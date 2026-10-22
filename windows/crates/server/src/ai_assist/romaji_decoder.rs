// ai_assist/romaji_decoder.rs
// ローマ字子音浮き・混入タイポデコーダ
// 例: 「おｋせり」 -> 「おかえり」, 「おｔかれ」 -> 「おつかれ」, 「あｒがとう」 -> 「ありがとう」

/// 全角・半角アルファベットが含まれる入力を検出し、
/// ローマ字打鍵列の隣接キーミスや母音抜けを補正したひらがな候補を返す。
pub fn decode_floating_consonant_typo(raw_input: &str) -> Option<String> {
    let has_ascii_or_fullwidth_alpha = raw_input.chars().any(|c| {
        c.is_ascii_alphabetic()
            || ('\u{FF21}'..='\u{FF3A}').contains(&c)
            || ('\u{FF41}'..='\u{FF5A}').contains(&c)
    });

    if !has_ascii_or_fullwidth_alpha {
        return None;
    }

    // 1. 全角英字・ひらがなをローマ字打鍵列に正規化
    let mut normalized_tokens = Vec::new();
    let chars: Vec<char> = raw_input.chars().collect();
    let mut idx = 0;
    while idx < chars.len() {
        let c = chars[idx];
        if ('\u{FF21}'..='\u{FF3A}').contains(&c) {
            let ch = ((c as u32 - 0xFF21) as u8 + b'a') as char;
            normalized_tokens.push(ch.to_string());
            idx += 1;
        } else if ('\u{FF41}'..='\u{FF5A}').contains(&c) {
            let ch = ((c as u32 - 0xFF41) as u8 + b'a') as char;
            normalized_tokens.push(ch.to_string());
            idx += 1;
        } else if c.is_ascii_alphabetic() {
            normalized_tokens.push(c.to_ascii_lowercase().to_string());
            idx += 1;
        } else {
            // 拗音 (きゃ, しゅ, ちょ 等) の2文字チェック
            if idx + 1 < chars.len() {
                let next_c = chars[idx + 1];
                if let Some(r) = digraph_to_romaji(c, next_c) {
                    normalized_tokens.push(r.to_string());
                    idx += 2;
                    continue;
                }
            }
            // ひらがな1文字をローマ字に変換
            let r = kana_char_to_romaji(c);
            if !r.is_empty() {
                normalized_tokens.push(r.to_string());
            } else {
                normalized_tokens.push(c.to_string());
            }
            idx += 1;
        }
    }

    let raw_romaji = normalized_tokens.join("");

    // パターン 0: そのままローマ字変換するだけで辞書語になる場合 (例: こｎにちは -> konnitiha -> こんにちは)
    let direct_hira = romaji_to_hiragana_str(&raw_romaji);
    if is_valid_japanese_word(&direct_hira) {
        return Some(direct_hira);
    }

    // パターン A: 隣接キー 1 文字置換 (例: おｋせり okseri -> s を a に置換 -> okaeri -> おかえり)
    let rom_chars: Vec<char> = raw_romaji.chars().collect();
    for i in 0..rom_chars.len() {
        let orig = rom_chars[i];
        for &adj in qwerty_adjacent_chars(orig) {
            let mut cand_chars = rom_chars.clone();
            cand_chars[i] = adj;
            let cand_rom: String = cand_chars.into_iter().collect();
            let cand_hira = romaji_to_hiragana_str(&cand_rom);
            if is_valid_japanese_word(&cand_hira) {
                return Some(cand_hira);
            }
        }
    }

    // パターン B: 子音直後の母音抜け（例: otkare -> otukare -> おつかれ, argatou -> arigatou -> ありがとう）
    for i in 0..rom_chars.len() {
        if "bcdfghjklmnpqrstvwxyz".contains(rom_chars[i]) {
            for &v in &['a', 'i', 'u', 'e', 'o'] {
                let mut cand_chars = rom_chars.clone();
                cand_chars.insert(i + 1, v);
                let cand_rom: String = cand_chars.into_iter().collect();
                let cand_hira = romaji_to_hiragana_str(&cand_rom);
                if is_valid_japanese_word(&cand_hira) {
                    return Some(cand_hira);
                }
            }
        }
    }

    // パターン C: 誤打鍵された浮き子音の削除（例: o + k + a + e + r + i の途中に余計な子音が入った場合）
    for i in 0..rom_chars.len() {
        if "bcdfghjklmnpqrstvwxyz".contains(rom_chars[i]) {
            let mut cand_chars = rom_chars.clone();
            cand_chars.remove(i);
            let cand_rom: String = cand_chars.into_iter().collect();
            let cand_hira = romaji_to_hiragana_str(&cand_rom);
            if is_valid_japanese_word(&cand_hira) {
                return Some(cand_hira);
            }
        }
    }

    None
}

fn qwerty_adjacent_chars(c: char) -> &'static [char] {
    match c {
        'q' => &['w', 'a', 's'],
        'w' => &['q', 'e', 'a', 's', 'd'],
        'e' => &['w', 'r', 's', 'd', 'f'],
        'r' => &['e', 't', 'd', 'f', 'g'],
        't' => &['r', 'y', 'f', 'g', 'h'],
        'y' => &['t', 'u', 'g', 'h', 'j'],
        'u' => &['y', 'i', 'h', 'j', 'k'],
        'i' => &['u', 'o', 'j', 'k', 'l'],
        'o' => &['i', 'p', 'k', 'l'],
        'p' => &['o', 'l'],
        'a' => &['q', 'w', 's', 'z'],
        's' => &['a', 'w', 'e', 'd', 'x', 'z'],
        'd' => &['s', 'e', 'r', 'f', 'c', 'x'],
        'f' => &['d', 'r', 't', 'g', 'v', 'c'],
        'g' => &['f', 't', 'y', 'h', 'b', 'v'],
        'h' => &['g', 'y', 'u', 'j', 'n', 'b'],
        'j' => &['h', 'u', 'i', 'k', 'm', 'n'],
        'k' => &['j', 'i', 'o', 'l', 'm'],
        'l' => &['k', 'o', 'p'],
        'z' => &['a', 's', 'x'],
        'x' => &['z', 's', 'd', 'c'],
        'c' => &['x', 'd', 'f', 'v'],
        'v' => &['c', 'f', 'g', 'b'],
        'b' => &['v', 'g', 'h', 'n'],
        'n' => &['b', 'h', 'j', 'm'],
        'm' => &['n', 'j', 'k'],
        _ => &[],
    }
}

fn digraph_to_romaji(c1: char, c2: char) -> Option<&'static str> {
    match (c1, c2) {
        ('き', 'ゃ') => Some("kya"),
        ('き', 'ゅ') => Some("kyu"),
        ('き', 'ょ') => Some("kyo"),
        ('し', 'ゃ') => Some("sha"),
        ('し', 'ゅ') => Some("shu"),
        ('し', 'ょ') => Some("sho"),
        ('ち', 'ゃ') => Some("cha"),
        ('ち', 'ゅ') => Some("chu"),
        ('ち', 'ょ') => Some("cho"),
        ('に', 'ゃ') => Some("nya"),
        ('に', 'ゅ') => Some("nyu"),
        ('に', 'ょ') => Some("nyo"),
        ('ひ', 'ゃ') => Some("hya"),
        ('ひ', 'ゅ') => Some("hyu"),
        ('ひ', 'ょ') => Some("hyo"),
        ('み', 'ゃ') => Some("mya"),
        ('み', 'ゅ') => Some("myu"),
        ('み', 'ょ') => Some("myo"),
        ('り', 'ゃ') => Some("rya"),
        ('り', 'ゅ') => Some("ryu"),
        ('り', 'ょ') => Some("ryo"),
        ('ぎ', 'ゃ') => Some("gya"),
        ('ぎ', 'ゅ') => Some("gyu"),
        ('ぎ', 'ょ') => Some("gyo"),
        ('じ', 'ゃ') => Some("ja"),
        ('じ', 'ゅ') => Some("ju"),
        ('じ', 'ょ') => Some("jo"),
        ('び', 'ゃ') => Some("bya"),
        ('び', 'ゅ') => Some("byu"),
        ('び', 'ょ') => Some("byo"),
        ('ぴ', 'ゃ') => Some("pya"),
        ('ぴ', 'ゅ') => Some("pyu"),
        ('ぴ', 'ょ') => Some("pyo"),
        _ => None,
    }
}

fn kana_char_to_romaji(c: char) -> &'static str {
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
        'ー' => "-",
        _ => "",
    }
}

pub fn romaji_to_hiragana_str(text: &str) -> String {
    let rules: &[(&str, &str)] = &[
        ("kya", "きゃ"),
        ("kyu", "きゅ"),
        ("kyo", "きょ"),
        ("sya", "しゃ"),
        ("syu", "しゅ"),
        ("syo", "しょ"),
        ("sha", "しゃ"),
        ("shu", "しゅ"),
        ("sho", "しょ"),
        ("tya", "ちゃ"),
        ("tyu", "ちゅ"),
        ("tyo", "ちょ"),
        ("cha", "ちゃ"),
        ("chu", "ちゅ"),
        ("cho", "ちょ"),
        ("nya", "にゃ"),
        ("nyu", "にゅ"),
        ("nyo", "にょ"),
        ("hya", "ひゃ"),
        ("hyu", "ひゅ"),
        ("hyo", "ひょ"),
        ("mya", "みゃ"),
        ("myu", "みゅ"),
        ("myo", "みょ"),
        ("rya", "りゃ"),
        ("ryu", "りゅ"),
        ("ryo", "りょ"),
        ("gya", "ぎゃ"),
        ("gyu", "ぎゅ"),
        ("gyo", "ぎょ"),
        ("ja", "じゃ"),
        ("ju", "じゅ"),
        ("jo", "じょ"),
        ("zya", "じゃ"),
        ("zyu", "じゅ"),
        ("zyo", "じょ"),
        ("bya", "びゃ"),
        ("byu", "びゅ"),
        ("byo", "びょ"),
        ("pya", "ぴゃ"),
        ("pyu", "ぴゅ"),
        ("pyo", "ぴょ"),
        ("ka", "か"),
        ("ki", "き"),
        ("ku", "く"),
        ("ke", "け"),
        ("ko", "こ"),
        ("sa", "さ"),
        ("si", "し"),
        ("su", "す"),
        ("se", "せ"),
        ("so", "そ"),
        ("shi", "し"),
        ("tsu", "つ"),
        ("chi", "ち"),
        ("ta", "た"),
        ("ti", "ち"),
        ("tu", "つ"),
        ("te", "て"),
        ("to", "と"),
        ("na", "な"),
        ("ni", "に"),
        ("nu", "ぬ"),
        ("ne", "ね"),
        ("no", "の"),
        ("ha", "は"),
        ("hi", "ひ"),
        ("hu", "ふ"),
        ("he", "へ"),
        ("ho", "ほ"),
        ("fu", "ふ"),
        ("ma", "ま"),
        ("mi", "み"),
        ("mu", "む"),
        ("me", "め"),
        ("mo", "も"),
        ("ya", "や"),
        ("yu", "ゆ"),
        ("yo", "よ"),
        ("ra", "ら"),
        ("ri", "り"),
        ("ru", "る"),
        ("re", "れ"),
        ("ro", "ろ"),
        ("wa", "わ"),
        ("wo", "を"),
        ("nn", "ん"),
        ("ga", "が"),
        ("gi", "ぎ"),
        ("gu", "ぐ"),
        ("ge", "げ"),
        ("go", "ご"),
        ("za", "ざ"),
        ("zi", "じ"),
        ("zu", "ず"),
        ("ze", "ぜ"),
        ("zo", "ぞ"),
        ("ji", "じ"),
        ("da", "だ"),
        ("di", "ぢ"),
        ("du", "づ"),
        ("de", "で"),
        ("do", "ど"),
        ("ba", "ば"),
        ("bi", "び"),
        ("bu", "ぶ"),
        ("be", "べ"),
        ("bo", "ぼ"),
        ("pa", "ぱ"),
        ("pi", "ぴ"),
        ("pu", "ぷ"),
        ("pe", "ぺ"),
        ("po", "ぽ"),
        ("a", "あ"),
        ("i", "い"),
        ("u", "う"),
        ("e", "え"),
        ("o", "お"),
        ("-", "ー"),
    ];

    let mut res = String::new();
    let mut i = 0;
    let b = text.as_bytes();
    let n = b.len();

    while i < n {
        // 促音
        if i + 1 < n
            && b[i] == b[i + 1]
            && b[i].is_ascii_alphabetic()
            && b[i] != b'n'
            && !"aiueo".contains(b[i] as char)
        {
            res.push('っ');
            i += 1;
            continue;
        }

        // 'n' の撥音 (ん) 判定: 末尾の 'n'、または母音・'y'・'n' 以外の子音の前の 'n'
        if b[i] == b'n' {
            if i + 1 == n || (i + 1 < n && !"aiueoyn".contains(b[i + 1] as char)) {
                res.push('ん');
                i += 1;
                continue;
            }
        }

        let mut matched = false;
        for &(rom, kana) in rules {
            if text[i..].starts_with(rom) {
                res.push_str(kana);
                i += rom.len();
                matched = true;
                break;
            }
        }
        if !matched {
            res.push(b[i] as char);
            i += 1;
        }
    }
    res
}

fn is_valid_japanese_word(hira: &str) -> bool {
    // アルファベットが一切残っていない純粋なひらがなであること
    if hira.is_empty() || hira.chars().any(|c| c.is_ascii_alphabetic()) {
        return false;
    }
    // 組み込みインデックス（標準語彙 + 蒸留語彙 2,300語以上）および標準語彙前方一致判定
    if super::typo_variant::builtin_index().lookup(hira).is_some() {
        return true;
    }
    if super::STANDARD_VOCAB
        .iter()
        .any(|&(r, _)| r == hira || hira.starts_with(r))
    {
        return true;
    }
    false
}
