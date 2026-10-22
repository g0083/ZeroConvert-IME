// ai_assist/dynamic_macro.rs
// 次世代アシスト Phase 1: 動的マクロ展開 (日付・曜日・年号・時刻・インライン計算)
//
// すべてローカル計算で完結し、外部ネットワーク・推論エンジン・辞書照会に依存しない。
// 1 候補あたりの計算量はマイクロ秒級で、常時 0ms で候補を生成できる。

use std::time::{SystemTime, UNIX_EPOCH};

/// 日本標準時 (JST) の UTC オフセット (秒)。
pub const JST_OFFSET_SECONDS: i32 = 9 * 60 * 60;

/// 動的マクロの種類。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MacroKind {
    /// 暦日 (年月日・曜日)。
    Date,
    /// 時刻。
    Time,
    /// 年号付き年。
    EraYear,
    /// インライン四則演算の結果。
    Calculation,
}

/// 動的マクロで展開された 1 候補。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MacroCandidate {
    /// 確定される文字列。
    pub text: String,
    /// 候補の種類 (UI 表示用)。
    pub kind: MacroKind,
}

/// 展開的关键語。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum MacroKeyword {
    Today,
    Tomorrow,
    DayAfterTomorrow,
    Yesterday,
    Now,
    ThisYear,
    NextYear,
}

/// 展開キーワードテーブル。読み (ひらがな) で完全一致判定する。
const MACRO_KEYWORDS: &[(&str, MacroKeyword)] = &[
    ("きょう", MacroKeyword::Today),
    ("あした", MacroKeyword::Tomorrow),
    ("あさって", MacroKeyword::DayAfterTomorrow),
    ("きのう", MacroKeyword::Yesterday),
    ("いま", MacroKeyword::Now),
    ("ことし", MacroKeyword::ThisYear),
    ("らいねん", MacroKeyword::NextYear),
];

/// 曜日の日本語表記 (0 = 日曜日)。
const WEEKDAY_LABELS: [&str; 7] = ["日", "月", "火", "水", "木", "金", "土"];

/// 演算式として扱う入力の最大長 (0ms 維持のための上限)。
const MAX_EXPRESSION_LEN: usize = 32;
/// 演算結果として許容する絶対値の上限。
const MAX_RESULT_MAGNITUDE: i128 = 1_000_000_000_000;

/// 現在時刻を JST で返す。
pub fn now_jst() -> JstDateTime {
    let epoch_seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs() as i64)
        .unwrap_or_default();
    JstDateTime::from_unix_seconds(epoch_seconds)
}

/// JST 基準の年月日・時刻。
///
/// `std` のみに依存し、タイムゾーンデータベースを要求しない固定オフセット表現を
/// 用いるため、環境のタイムゾーン設定に依存せず常に JST を返す。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JstDateTime {
    /// 西暦年。
    pub year: i64,
    /// 月 (1..=12)。
    pub month: u32,
    /// 日 (1..=31)。
    pub day: u32,
    /// 時 (0..=23)。
    pub hour: u32,
    /// 分 (0..=59)。
    pub minute: u32,
    /// 秒 (0..=59)。
    pub second: u32,
    /// 曜日 (0 = 日曜日)。
    pub weekday: u32,
}

impl JstDateTime {
    /// Unix epoch 秒から JST の年月日・時刻を計算する。
    pub fn from_unix_seconds(epoch_seconds: i64) -> Self {
        let local_seconds = epoch_seconds + JST_OFFSET_SECONDS as i64;
        let days = local_seconds.div_euclid(86_400);
        let seconds_of_day = local_seconds.rem_euclid(86_400);
        let (year, month, day) = civil_from_days(days);
        Self {
            year,
            month,
            day,
            hour: (seconds_of_day / 3600) as u32,
            minute: ((seconds_of_day % 3600) / 60) as u32,
            second: (seconds_of_day % 60) as u32,
            weekday: weekday_from_days(days),
        }
    }

    /// 指定日数だけ後の日付 (時刻・曜日は再計算する)。
    pub fn plus_days(&self, days: i64) -> Self {
        let epoch_seconds = self.to_unix_seconds() + days * 86_400;
        Self::from_unix_seconds(epoch_seconds)
    }

    /// 翌年同日。
    pub fn plus_years(&self, years: i64) -> Self {
        let (year, month, day) = (self.year + years, self.month, self.day);
        let days = days_from_civil(year, month, day);
        Self {
            year,
            month,
            day,
            hour: self.hour,
            minute: self.minute,
            second: self.second,
            weekday: weekday_from_days(days),
        }
    }

    /// この日時を Unix epoch 秒へ変換する。
    pub fn to_unix_seconds(&self) -> i64 {
        days_from_civil(self.year, self.month, self.day) * 86_400
            + self.hour as i64 * 3600
            + self.minute as i64 * 60
            + self.second as i64
            - JST_OFFSET_SECONDS as i64
    }

    /// 曜日の日本語表記 (0 = 日曜日)。
    pub fn weekday_label(&self) -> &'static str {
        WEEKDAY_LABELS[(self.weekday as usize) % WEEKDAY_LABELS.len()]
    }
}

/// civil 日 (1970-01-01 = 0) から年月日を求める (Howard Hinnant's algorithm)。
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let day_of_era = z - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let mp = (5 * day_of_year + 2) / 153;
    let day = (day_of_year - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let year = if month <= 2 { year + 1 } else { year };
    (year, month, day)
}

/// 年月日から civil 日を求める (Howard Hinnant's algorithm)。
fn days_from_civil(year: i64, month: u32, day: u32) -> i64 {
    let year = if month <= 2 { year - 1 } else { year };
    let era = if year >= 0 { year } else { year - 399 } / 400;
    let year_of_era = year - era * 400;
    let month = month as i64;
    let day_of_year =
        (153 * (if month > 2 { month - 3 } else { month + 9 }) + 2) / 5 + day as i64 - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era * 146_097 + day_of_era - 719_468
}

/// civil 日から曜日 (0 = 日曜日) を求める。1970-01-01 は木曜日。
fn weekday_from_days(days: i64) -> u32 {
    (days + 4).rem_euclid(7) as u32
}

/// 年号 (元号) とその年数を返す。
///
/// 令和: 2019-05-01 以降 / 平成: 1989-01-08 以降 / 昭和: 1926-12-25 以降。
/// 対応する元号が無い場合は `None`。
pub fn japanese_era(year: i64, month: u32, day: u32) -> Option<(&'static str, i64)> {
    let date = (year, month, day);
    if date >= (2019, 5, 1) {
        Some(("令和", year - 2018))
    } else if date >= (1989, 1, 8) {
        Some(("平成", year - 1988))
    } else if date >= (1926, 12, 25) {
        Some(("昭和", year - 1925))
    } else {
        None
    }
}

/// 動的マクロの候補を生成する。
///
/// `input` がキーワードと完全一致する場合のみ展開し、「きょう」「いま」のように
/// 文中の語として頻出するキーワードが構文の一部として使われるケースを誤爆させない。
pub fn expand_dynamic_macros(input: &str, now: &JstDateTime) -> Vec<MacroCandidate> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return Vec::new();
    }

    if let Some(result) = expand_arithmetic(trimmed) {
        return result;
    }

    let Some((_, keyword)) = MACRO_KEYWORDS
        .iter()
        .find(|(keyword, _)| *keyword == trimmed)
    else {
        return Vec::new();
    };

    match keyword {
        MacroKeyword::Today => {
            let today = *now;
            vec![
                MacroCandidate {
                    text: format!("{}年{}月{}日", today.year, today.month, today.day),
                    kind: MacroKind::Date,
                },
                MacroCandidate {
                    text: format!(
                        "{}/{:02}/{:02}({})",
                        today.year,
                        today.month,
                        today.day,
                        today.weekday_label()
                    ),
                    kind: MacroKind::Date,
                },
                era_date_candidate(&today),
            ]
        }
        MacroKeyword::Tomorrow => {
            let tomorrow = now.plus_days(1);
            vec![
                MacroCandidate {
                    text: format!(
                        "{}年{}月{}日({})",
                        tomorrow.year,
                        tomorrow.month,
                        tomorrow.day,
                        tomorrow.weekday_label()
                    ),
                    kind: MacroKind::Date,
                },
                MacroCandidate {
                    text: format!("{}月{}日", tomorrow.month, tomorrow.day),
                    kind: MacroKind::Date,
                },
            ]
        }
        MacroKeyword::DayAfterTomorrow => {
            let day_after = now.plus_days(2);
            vec![MacroCandidate {
                text: format!(
                    "{}年{}月{}日({})",
                    day_after.year,
                    day_after.month,
                    day_after.day,
                    day_after.weekday_label()
                ),
                kind: MacroKind::Date,
            }]
        }
        MacroKeyword::Yesterday => {
            let yesterday = now.plus_days(-1);
            vec![MacroCandidate {
                text: format!(
                    "{}年{}月{}日({})",
                    yesterday.year,
                    yesterday.month,
                    yesterday.day,
                    yesterday.weekday_label()
                ),
                kind: MacroKind::Date,
            }]
        }
        MacroKeyword::Now => {
            let display_hour = match now.hour % 12 {
                0 => 12,
                h => h,
            };
            vec![
                MacroCandidate {
                    text: format!("{}:{:02}", now.hour, now.minute),
                    kind: MacroKind::Time,
                },
                MacroCandidate {
                    text: format!(
                        "{}{}時{}分",
                        meridiem_prefix(now.hour),
                        display_hour,
                        now.minute
                    ),
                    kind: MacroKind::Time,
                },
                MacroCandidate {
                    text: format!("{:02}:{:02}:{:02}", now.hour, now.minute, now.second),
                    kind: MacroKind::Time,
                },
            ]
        }
        MacroKeyword::ThisYear => {
            let this_year = *now;
            vec![
                MacroCandidate {
                    text: format!("{}年", this_year.year),
                    kind: MacroKind::EraYear,
                },
                era_year_candidate(&this_year),
            ]
        }
        MacroKeyword::NextYear => {
            let next_year = now.plus_years(1);
            vec![
                MacroCandidate {
                    text: format!("{}年", next_year.year),
                    kind: MacroKind::EraYear,
                },
                era_year_candidate(&next_year),
            ]
        }
    }
}

/// 現在時刻を使って動的マクロ候補を生成する。
pub fn current_expansions(input: &str) -> Vec<MacroCandidate> {
    expand_dynamic_macros(input, &now_jst())
}

/// 午前のタイムスタンプ (0..=11 は「午前」、12..=23 は「午後」)。
fn meridiem_prefix(hour: u32) -> &'static str {
    if hour < 12 {
        "午前"
    } else {
        "午後"
    }
}

/// 年号付きの暦日候補 (例: 令和8年10月22日)。
fn era_date_candidate(date: &JstDateTime) -> MacroCandidate {
    match japanese_era(date.year, date.month, date.day) {
        Some((era, era_year)) => MacroCandidate {
            text: format!("{era}{era_year}年{}月{}日", date.month, date.day),
            kind: MacroKind::Date,
        },
        None => MacroCandidate {
            text: format!("{}年{}月{}日", date.year, date.month, date.day),
            kind: MacroKind::Date,
        },
    }
}

/// 年号付きの年候補 (例: 令和8年)。
fn era_year_candidate(date: &JstDateTime) -> MacroCandidate {
    match japanese_era(date.year, date.month, date.day) {
        Some((era, era_year)) => MacroCandidate {
            text: format!("{era}{era_year}年"),
            kind: MacroKind::EraYear,
        },
        None => MacroCandidate {
            text: format!("{}年", date.year),
            kind: MacroKind::EraYear,
        },
    }
}

/// インライン四則演算を展開する。
///
/// `12*35=` のように `=` で終わる式、または演算子を含む式を検出し、結果を返す。
/// 0 除算・桁あふれ・非整除は候補を生成しない。
fn expand_arithmetic(input: &str) -> Option<Vec<MacroCandidate>> {
    let (expression, has_equals) =
        match input.strip_suffix('=').or_else(|| input.strip_suffix('＝')) {
            Some(expression) => (expression, true),
            None => (input, false),
        };
    if expression.is_empty() || expression.chars().count() > MAX_EXPRESSION_LEN {
        return None;
    }
    if !expression
        .chars()
        .any(|ch| matches!(ch, '+' | '-' | '*' | '/' | '×' | '÷' | '＋' | '－'))
    {
        return None;
    }

    let value = evaluate_arithmetic(expression)?;
    if value.unsigned_abs() as i128 > MAX_RESULT_MAGNITUDE {
        return None;
    }
    let result = value.to_string();

    let mut candidates = Vec::with_capacity(2);
    if has_equals {
        candidates.push(MacroCandidate {
            text: result.clone(),
            kind: MacroKind::Calculation,
        });
        candidates.push(MacroCandidate {
            text: format!("{expression}={result}"),
            kind: MacroKind::Calculation,
        });
    } else {
        candidates.push(MacroCandidate {
            text: result,
            kind: MacroKind::Calculation,
        });
    }
    Some(candidates)
}

/// 四則演算 (括弧対応) を評価する。
///
/// 整数の演算のみを扱い、0 除算と桁あふれは `None` を返す。
fn evaluate_arithmetic(expression: &str) -> Option<i64> {
    let tokens = tokenize_arithmetic(expression)?;
    let mut parser = ArithmeticParser {
        tokens,
        position: 0,
    };
    let value = parser.parse_expression()?;
    if parser.position != parser.tokens.len() {
        return None;
    }
    Some(value)
}

/// 演算トークン。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum ArithmeticToken {
    Number(i64),
    Plus,
    Minus,
    Multiply,
    Divide,
    LeftParen,
    RightParen,
}

/// 演算式をトークン列へ変換する。
fn tokenize_arithmetic(expression: &str) -> Option<Vec<ArithmeticToken>> {
    let mut tokens = Vec::new();
    let mut chars = expression.chars().peekable();
    while let Some(ch) = chars.next() {
        match ch {
            '0'..='9' => {
                let mut digits = String::new();
                digits.push(ch);
                while let Some(&next) = chars.peek() {
                    if next.is_ascii_digit() {
                        digits.push(next);
                        chars.next();
                    } else if ('０'..='９').contains(&next) {
                        let ascii_next =
                            char::from_u32('0' as u32 + (next as u32 - '０' as u32)).unwrap();
                        digits.push(ascii_next);
                        chars.next();
                    } else {
                        break;
                    }
                }
                if digits.len() > 12 {
                    return None;
                }
                tokens.push(ArithmeticToken::Number(digits.parse().ok()?));
            }
            '０'..='９' => {
                let mut digits = String::new();
                let ascii_ch = char::from_u32('0' as u32 + (ch as u32 - '０' as u32)).unwrap();
                digits.push(ascii_ch);
                while let Some(&next) = chars.peek() {
                    if ('０'..='９').contains(&next) {
                        let ascii_next =
                            char::from_u32('0' as u32 + (next as u32 - '０' as u32)).unwrap();
                        digits.push(ascii_next);
                        chars.next();
                    } else if next.is_ascii_digit() {
                        digits.push(next);
                        chars.next();
                    } else {
                        break;
                    }
                }
                if digits.len() > 12 {
                    return None;
                }
                tokens.push(ArithmeticToken::Number(digits.parse().ok()?));
            }
            '+' | '＋' => tokens.push(ArithmeticToken::Plus),
            '-' | '－' => tokens.push(ArithmeticToken::Minus),
            '*' | '×' | 'x' | 'X' => tokens.push(ArithmeticToken::Multiply),
            '/' | '÷' => tokens.push(ArithmeticToken::Divide),
            '(' | '（' => tokens.push(ArithmeticToken::LeftParen),
            ')' | '）' => tokens.push(ArithmeticToken::RightParen),
            ' ' | '\u{3000}' => {}
            _ => return None,
        }
    }
    if tokens.is_empty() {
        return None;
    }
    Some(tokens)
}

/// 再帰下降パーサ。
struct ArithmeticParser {
    tokens: Vec<ArithmeticToken>,
    position: usize,
}

impl ArithmeticParser {
    fn peek(&self) -> Option<ArithmeticToken> {
        self.tokens.get(self.position).copied()
    }

    /// 式 = 項 (('+' | '-') 項)*
    fn parse_expression(&mut self) -> Option<i64> {
        let mut value = self.parse_term()?;
        loop {
            match self.peek() {
                Some(ArithmeticToken::Plus) => {
                    self.position += 1;
                    let right = self.parse_term()?;
                    value = value.checked_add(right)?;
                }
                Some(ArithmeticToken::Minus) => {
                    self.position += 1;
                    let right = self.parse_term()?;
                    value = value.checked_sub(right)?;
                }
                _ => return Some(value),
            }
        }
    }

    /// 項 = 因子 (('*' | '/') 因子)*
    fn parse_term(&mut self) -> Option<i64> {
        let mut value = self.parse_factor()?;
        loop {
            match self.peek() {
                Some(ArithmeticToken::Multiply) => {
                    self.position += 1;
                    let right = self.parse_factor()?;
                    value = value.checked_mul(right)?;
                }
                Some(ArithmeticToken::Divide) => {
                    self.position += 1;
                    let right = self.parse_factor()?;
                    if right == 0 {
                        return None;
                    }
                    if value % right != 0 {
                        // 非整除の結果は分数表記になり読みにくいため候補にしない。
                        return None;
                    }
                    value = value.checked_div(right)?;
                }
                _ => return Some(value),
            }
        }
    }

    /// 因子 = 数値 | '(' 式 ')' | ('+' | '-') 因子
    fn parse_factor(&mut self) -> Option<i64> {
        match self.peek()? {
            ArithmeticToken::Number(value) => {
                self.position += 1;
                Some(value)
            }
            ArithmeticToken::LeftParen => {
                self.position += 1;
                let value = self.parse_expression()?;
                if self.peek()? != ArithmeticToken::RightParen {
                    return None;
                }
                self.position += 1;
                Some(value)
            }
            ArithmeticToken::Plus => {
                self.position += 1;
                self.parse_factor()
            }
            ArithmeticToken::Minus => {
                self.position += 1;
                self.parse_factor()?.checked_neg()
            }
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{evaluate_arithmetic, expand_dynamic_macros, japanese_era, JstDateTime, MacroKind};

    /// 2026-10-22 (木) 01:45 JST。
    fn reference_now() -> JstDateTime {
        JstDateTime {
            year: 2026,
            month: 10,
            day: 22,
            hour: 1,
            minute: 45,
            second: 0,
            weekday: 4,
        }
    }

    fn texts(candidates: &[super::MacroCandidate]) -> Vec<&str> {
        candidates.iter().map(|c| c.text.as_str()).collect()
    }

    #[test]
    fn converts_unix_seconds_to_jst_civil_date() {
        // 2026-10-21 16:45:00 UTC = 2026-10-22 01:45:00 JST
        let value = JstDateTime::from_unix_seconds(1_792_601_100);
        assert_eq!(value.year, 2026);
        assert_eq!(value.month, 10);
        assert_eq!(value.day, 22);
        assert_eq!(value.hour, 1);
        assert_eq!(value.minute, 45);
        assert_eq!(value.weekday_label(), "木");
    }

    #[test]
    fn round_trips_civil_days() {
        for days in [-100_000i64, -1, 0, 1, 19_000, 100_000] {
            let (year, month, day) = super::civil_from_days(days);
            assert_eq!(super::days_from_civil(year, month, day), days);
        }
    }

    #[test]
    fn expands_today_with_era_and_weekday() {
        let candidates = expand_dynamic_macros("きょう", &reference_now());
        assert_eq!(
            texts(&candidates),
            vec!["2026年10月22日", "2026/10/22(木)", "令和8年10月22日"]
        );
        assert!(candidates.iter().all(|c| c.kind == MacroKind::Date));
    }

    #[test]
    fn expands_relative_days() {
        assert_eq!(
            texts(&expand_dynamic_macros("あした", &reference_now())),
            vec!["2026年10月23日(金)", "10月23日"]
        );
        assert_eq!(
            texts(&expand_dynamic_macros("あさって", &reference_now())),
            vec!["2026年10月24日(土)"]
        );
        assert_eq!(
            texts(&expand_dynamic_macros("きのう", &reference_now())),
            vec!["2026年10月21日(水)"]
        );
    }

    #[test]
    fn expands_current_time() {
        let candidates = expand_dynamic_macros("いま", &reference_now());
        assert_eq!(texts(&candidates), vec!["1:45", "午前1時45分", "01:45:00"]);
        assert!(candidates.iter().all(|c| c.kind == MacroKind::Time));
    }

    #[test]
    fn uses_afternoon_prefix_for_afternoon_hours() {
        let afternoon = JstDateTime {
            hour: 13,
            minute: 5,
            ..reference_now()
        };
        assert_eq!(
            texts(&expand_dynamic_macros("いま", &afternoon)),
            vec!["13:05", "午後1時5分", "13:05:00"]
        );
    }

    #[test]
    fn expands_years_with_era() {
        assert_eq!(
            texts(&expand_dynamic_macros("ことし", &reference_now())),
            vec!["2026年", "令和8年"]
        );
        assert_eq!(
            texts(&expand_dynamic_macros("らいねん", &reference_now())),
            vec!["2027年", "令和9年"]
        );
    }

    #[test]
    fn does_not_expand_partial_or_empty_input() {
        assert!(expand_dynamic_macros("きょうの", &reference_now()).is_empty());
        assert!(expand_dynamic_macros("", &reference_now()).is_empty());
        assert!(expand_dynamic_macros("   ", &reference_now()).is_empty());
        assert!(expand_dynamic_macros("にほん", &reference_now()).is_empty());
    }

    #[test]
    fn expands_arithmetic_expressions() {
        assert_eq!(
            texts(&expand_dynamic_macros("12*35=", &reference_now())),
            vec!["420", "12*35=420"]
        );
        assert_eq!(
            texts(&expand_dynamic_macros("1000/4=", &reference_now())),
            vec!["250", "1000/4=250"]
        );
        assert_eq!(
            texts(&expand_dynamic_macros("(1+2)*3=", &reference_now())),
            vec!["9", "(1+2)*3=9"]
        );
        assert_eq!(
            texts(&expand_dynamic_macros("１２×３=", &reference_now())),
            vec!["36", "１２×３=36"]
        );
    }

    #[test]
    fn rejects_invalid_arithmetic() {
        assert!(expand_dynamic_macros("1/0=", &reference_now()).is_empty());
        assert!(expand_dynamic_macros("10/3=", &reference_now()).is_empty());
        assert!(expand_dynamic_macros("1+", &reference_now()).is_empty());
        assert!(expand_dynamic_macros("abc=", &reference_now()).is_empty());
        assert!(expand_dynamic_macros("1234567890123=", &reference_now()).is_empty());
    }

    #[test]
    fn evaluates_arithmetic_without_equals_sign() {
        assert_eq!(evaluate_arithmetic("2+3*4"), Some(14));
        assert_eq!(evaluate_arithmetic("(2+3)*4"), Some(20));
        assert_eq!(evaluate_arithmetic("-5+2"), Some(-3));
        assert_eq!(evaluate_arithmetic("7/0"), None);
    }

    #[test]
    fn resolves_japanese_era() {
        assert_eq!(japanese_era(2026, 10, 22), Some(("令和", 8)));
        assert_eq!(japanese_era(2019, 4, 30), Some(("平成", 31)));
        assert_eq!(japanese_era(2019, 5, 1), Some(("令和", 1)));
        assert_eq!(japanese_era(1989, 1, 7), Some(("昭和", 64)));
        assert_eq!(japanese_era(1989, 1, 8), Some(("平成", 1)));
        assert_eq!(japanese_era(1926, 12, 24), None);
    }
}
