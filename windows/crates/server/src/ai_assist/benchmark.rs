//! 共通ベンチマーク採点（Windows/Android 共有、結果を同一にする）。
//!
//! 正本データ `benchmarks/common/*.jsonl` の 1 問ずつの判定をここで定義する。
//! Windows Tauri (`run_benchmark_item`) と Android (`BenchmarkRunner`) は同じ関数で
//! 判定するか、言語側のミラーがこのファイルと `parity_cases.jsonl` の一致で検証される。
//! モデル出力の stop 打切り・NAM 復元・PUA 除去はホスト側の出力整形であり、本ファイルの
//! 対象外。ここでは整形済み文字列の比較正規化だけを行う。

use super::{katakana_to_hiragana, to_katakana};

pub use super::{katakana_to_hiragana as to_hiragana, to_katakana as to_katakana_pub};

/// 採点入力の最小正規化。現行 Windows/Android の両実装と一致する
/// （trim + 改行除去）。空白除去・ひらがな変換は別ヘルパで行う。
pub fn clean_scoring_input(s: &str) -> String {
    s.trim().replace('\r', "").replace('\n', "")
}

/// 空白類（半角・全角・タブ等）をすべて除去する。
pub fn normalize_whitespace(s: &str) -> String {
    s.chars()
        .filter(|c| !c.is_whitespace() && *c != '　')
        .collect()
}

/// 変換の判定理由。ホストの表示にそのまま使える安定文字列。
pub struct ConversionScore {
    pub is_correct: bool,
    pub reason: &'static str,
}

/// 変換 1 問の判定。
///
/// 順序: forbidden 含有 → 完全一致 → 空白除去一致 → ひらがな一致
/// （`acceptable` も同じ 3 段階で判定する。Windows の現行実装に寄せ、
/// Android の `acceptable` ひらがな未対応は本契約で拡充される）。
pub fn score_conversion(
    output: &str,
    expected: &str,
    acceptable: &[String],
    forbidden: &[String],
) -> ConversionScore {
    let out = clean_scoring_input(output);
    let exp = expected.trim();

    for fb in forbidden {
        let fb_trim = fb.trim();
        if !fb_trim.is_empty() && out.contains(fb_trim) {
            return ConversionScore {
                is_correct: false,
                reason: "forbidden",
            };
        }
    }

    if out == exp || acceptable.iter().any(|a| a.trim() == out) {
        return ConversionScore {
            is_correct: true,
            reason: "exact",
        };
    }

    if normalize_whitespace(&out) == normalize_whitespace(exp) {
        return ConversionScore {
            is_correct: true,
            reason: "whitespace",
        };
    }

    let hira_out = katakana_to_hiragana(&out);
    if hira_out == katakana_to_hiragana(exp) {
        return ConversionScore {
            is_correct: true,
            reason: "kana",
        };
    }

    for acc in acceptable {
        let acc_trim = acc.trim();
        if normalize_whitespace(&out) == normalize_whitespace(acc_trim) {
            return ConversionScore {
                is_correct: true,
                reason: "whitespace_acceptable",
            };
        }
        if hira_out == katakana_to_hiragana(acc_trim) {
            return ConversionScore {
                is_correct: true,
                reason: "kana_acceptable",
            };
        }
    }

    ConversionScore {
        is_correct: false,
        reason: "mismatch",
    }
}

/// タイポ 1 問の判定。`exact_match` がシステム合算正解（メイン候補も含む）、
/// `suggestion_exact` が補正提案単独の正答。両者を混同しないこと
/// （[corrector-evaluation](../../docs/corrector-evaluation.md) の区別）。
pub enum TypoStatus {
    ExactMatch,
    VariantMatch,
    CleanPreserved,
    FalsePositive,
    Missed,
}

impl TypoStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            TypoStatus::ExactMatch => "exact_match",
            TypoStatus::VariantMatch => "variant_match",
            TypoStatus::CleanPreserved => "clean_preserved",
            TypoStatus::FalsePositive => "false_positive",
            TypoStatus::Missed => "missed",
        }
    }

    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "exact_match" => Some(TypoStatus::ExactMatch),
            "variant_match" => Some(TypoStatus::VariantMatch),
            "clean_preserved" => Some(TypoStatus::CleanPreserved),
            "false_positive" => Some(TypoStatus::FalsePositive),
            "missed" => Some(TypoStatus::Missed),
            _ => None,
        }
    }
}

pub struct TypoScore {
    pub status: TypoStatus,
    /// 補正提案が `expected`/`acceptable` と一致したか（null 提案は常に false）。
    pub suggestion_exact: bool,
}

fn matches_exact(candidate: &str, expected: &str, acceptable: &[String]) -> bool {
    let cand = clean_scoring_input(candidate);
    let exp = expected.trim();
    if cand == exp || acceptable.iter().any(|a| a.trim() == cand) {
        return true;
    }
    let norm_cand = normalize_whitespace(&cand);
    if norm_cand == normalize_whitespace(exp) {
        return true;
    }
    acceptable
        .iter()
        .any(|a| norm_cand == normalize_whitespace(a.trim()))
}

fn matches_variant(candidate: &str, expected: &str) -> bool {
    let cand = clean_scoring_input(candidate);
    let exp = expected.trim();
    if cand.is_empty() || exp.is_empty() {
        return false;
    }
    cand.contains(exp) || exp.contains(&cand)
}

/// タイポ 1 問の判定。
///
/// - `is_clean=false`（`expected_action=repair`）:
///   メイン出力も含む合算で exact/variant を判定し、提案単独の exact を別掲する。
///   従来 Windows（メインも救済）と Android（提案のみ）の差を本契約で吸収する。
/// - `is_clean=true`（`expected_action=preserve`）:
///   提案なし、または提案が正解/メイン出力/入力読み（空白除去）と等しければ保持。
///   それ以外の他語への改変は false_positive。
pub fn score_typo(
    is_clean: bool,
    expected: &str,
    acceptable: &[String],
    input_reading: &str,
    main_output: &str,
    suggestion: Option<&str>,
) -> TypoScore {
    if is_clean {
        let Some(sugg) = suggestion.map(clean_scoring_input) else {
            return TypoScore {
                status: TypoStatus::CleanPreserved,
                suggestion_exact: false,
            };
        };
        let sugg_exact = matches_exact(&sugg, expected, acceptable);
        if sugg_exact {
            return TypoScore {
                status: TypoStatus::CleanPreserved,
                suggestion_exact: true,
            };
        }
        if sugg == clean_scoring_input(main_output)
            || normalize_whitespace(&sugg) == normalize_whitespace(input_reading)
        {
            return TypoScore {
                status: TypoStatus::CleanPreserved,
                suggestion_exact: false,
            };
        }
        return TypoScore {
            status: TypoStatus::FalsePositive,
            suggestion_exact: false,
        };
    }

    let suggestion_exact = suggestion
        .map(|s| matches_exact(s, expected, acceptable))
        .unwrap_or(false);
    let main_exact = matches_exact(main_output, expected, acceptable);
    if suggestion_exact || main_exact {
        return TypoScore {
            status: TypoStatus::ExactMatch,
            suggestion_exact,
        };
    }
    // variant も提案・メイン両方で判定する（合算の近似）。
    let mut variant = false;
    if let Some(s) = suggestion {
        variant = variant || matches_variant(s, expected);
    }
    variant = variant || matches_variant(main_output, expected);
    if variant {
        return TypoScore {
            status: TypoStatus::VariantMatch,
            suggestion_exact,
        };
    }
    TypoScore {
        status: TypoStatus::Missed,
        suggestion_exact,
    }
}

/// 先読み 1 問の判定。`prediction` は完全文（ベースライン＋継続）で渡すこと。
/// Windows の継続だけの出力はホスト側で完全文に結合してから呼ぶ。
pub fn score_predictive(
    prediction: Option<&str>,
    full_sentence: &str,
    expected_continuation: &str,
    acceptable: &[String],
) -> bool {
    let Some(pred) = prediction else {
        return false;
    };
    let pred = pred.trim();
    if pred.is_empty() {
        return false;
    }
    if pred == full_sentence.trim() {
        return true;
    }
    let expected = expected_continuation.trim();
    if !expected.is_empty() && pred.ends_with(expected) {
        return true;
    }
    acceptable
        .iter()
        .any(|a| !a.trim().is_empty() && pred.ends_with(a.trim()))
}

/// 遅延の分位数（最近傍順位、n=0 では 0）。p50/p95/p99 の表示共通契約。
pub fn latency_percentiles(latencies_ms: &[f64]) -> (f64, f64, f64) {
    if latencies_ms.is_empty() {
        return (0.0, 0.0, 0.0);
    }
    let mut sorted: Vec<f64> = latencies_ms.to_vec();
    sorted.sort_by(|a, b| a.total_cmp(b));
    let at = |p: f64| -> f64 {
        let rank = (p / 100.0 * sorted.len() as f64).ceil() as usize;
        sorted[rank.saturating_sub(1).min(sorted.len() - 1)]
    };
    (at(50.0), at(95.0), at(99.0))
}

pub fn to_katakana_for_benchmark(s: &str) -> String {
    to_katakana(s)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    fn test_data() -> Vec<Value> {
        let cases = [
            // (kind, json)
            ("conversion", r#"{"kind":"conversion","output":"記者が取材する","expected":"記者が取材する","acceptable":[],"forbidden":[],"is_correct":true,"reason":"exact"}"#),
            ("conversion", r#"{"kind":"conversion","output":"汽車が駅に到着した","expected":"汽車が駅に到着した","acceptable":[],"forbidden":[],"is_correct":true,"reason":"exact"}"#),
            ("conversion", r#"{"kind":"conversion","output":"記者が駅に到着した","expected":"汽車が駅に到着した","acceptable":[],"forbidden":["記者が駅に到着した"],"is_correct":false,"reason":"forbidden"}"#),
            ("conversion", r#"{"kind":"conversion","output":"今日は 天気が いい","expected":"今日は天気がいい","acceptable":[],"forbidden":[],"is_correct":true,"reason":"whitespace"}"#),
            ("conversion", r#"{"kind":"conversion","output":"今日は天気がイイ","expected":"今日は天気がいい","acceptable":[],"forbidden":[],"is_correct":true,"reason":"kana"}"#),
            ("conversion", r#"{"kind":"conversion","output":"明日は東京駅で待ち合わせる","expected":"明日は東京駅で待ち合わせる","acceptable":["明日は東京駅でまちあわせる"],"forbidden":[],"is_correct":true,"reason":"exact"}"#),
            ("conversion", r#"{"kind":"conversion","output":"電車がおくれている","expected":"電車が遅れている","acceptable":["電車がおくれている"],"forbidden":[],"is_correct":true,"reason":"exact"}"#),
            ("conversion", r#"{"kind":"conversion","output":"まったく別の文","expected":"今日は天気がいい","acceptable":[],"forbidden":[],"is_correct":false,"reason":"mismatch"}"#),
            ("typo", r#"{"kind":"typo","is_clean":false,"expected":"ご確認ください","acceptable":[],"input_reading":"ゴカクニンクダサ","main_output":"ご確認ください","suggestion":null,"status":"exact_match","suggestion_exact":false}"#),
            ("typo", r#"{"kind":"typo","is_clean":false,"expected":"ご確認ください","acceptable":[],"input_reading":"ゴカクニンクダサ","main_output":"ご確認します","suggestion":"ご確認ください","status":"exact_match","suggestion_exact":true}"#),
            ("typo", r#"{"kind":"typo","is_clean":false,"expected":"ご確認ください","acceptable":[],"input_reading":"ゴカクニンクダサ","main_output":"ご確認ください。","suggestion":null,"status":"variant_match","suggestion_exact":false}"#),
            ("typo", r#"{"kind":"typo","is_clean":false,"expected":"ご確認ください","acceptable":[],"input_reading":"ゴカクニンクダサ","main_output":"まったく違う","suggestion":null,"status":"missed","suggestion_exact":false}"#),
            ("typo", r#"{"kind":"typo","is_clean":true,"expected":"検討いたします","acceptable":[],"input_reading":"ケントウイタシマス","main_output":"検討いたします","suggestion":null,"status":"clean_preserved","suggestion_exact":false}"#),
            ("typo", r#"{"kind":"typo","is_clean":true,"expected":"検討いたします","acceptable":[],"input_reading":"ケントウイタシマス","main_output":"検討いたします","suggestion":"検討いたします","status":"clean_preserved","suggestion_exact":true}"#),
            ("typo", r#"{"kind":"typo","is_clean":true,"expected":"検討いたします","acceptable":[],"input_reading":"ケントウイタシマス","main_output":"検討いたします","suggestion":"ケントウイタシマス","status":"clean_preserved","suggestion_exact":false}"#),
            ("typo", r#"{"kind":"typo","is_clean":true,"expected":"検討いたします","acceptable":[],"input_reading":"ケントウイタシマス","main_output":"検討いたします","suggestion":"原因を検討します","status":"false_positive","suggestion_exact":false}"#),
            ("predictive", r#"{"kind":"predictive","prediction":"いつもお世話になっております。","full_sentence":"いつもお世話になっております。","expected_continuation":"なっております。","acceptable_continuations":["なっております","なり、ありがとうございます。"],"is_match":true}"#),
            ("predictive", r#"{"kind":"predictive","prediction":"よろしくお願いいたします。","full_sentence":"よろしくお願いいたします。","expected_continuation":"いたします。","acceptable_continuations":["いたします","します。","申し上げます。"],"is_match":true}"#),
            ("predictive", r#"{"kind":"predictive","prediction":"よろしくお願いします。","full_sentence":"よろしくお願いいたします。","expected_continuation":"いたします。","acceptable_continuations":["いたします","します。","申し上げます。"],"is_match":true}"#),
            ("predictive", r#"{"kind":"predictive","prediction":null,"full_sentence":"いつもお世話になっております。","expected_continuation":"なっております。","acceptable_continuations":["なっております"],"is_match":false}"#),
        ];
        cases
            .iter()
            .map(|(_, j)| serde_json::from_str(j).unwrap())
            .collect()
    }

    #[test]
    fn parity_cases_match() {
        for case in test_data() {
            match case["kind"].as_str().unwrap() {
                "conversion" => {
                    let score = score_conversion(
                        case["output"].as_str().unwrap(),
                        case["expected"].as_str().unwrap(),
                        &case["acceptable"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .map(|v| v.as_str().unwrap().to_string())
                            .collect::<Vec<_>>(),
                        &case["forbidden"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .map(|v| v.as_str().unwrap().to_string())
                            .collect::<Vec<_>>(),
                    );
                    assert_eq!(
                        score.is_correct,
                        case["is_correct"].as_bool().unwrap(),
                        "{case:?}"
                    );
                    assert_eq!(score.reason, case["reason"].as_str().unwrap(), "{case:?}");
                }
                "typo" => {
                    let suggestion = case["suggestion"].as_str();
                    let score = score_typo(
                        case["is_clean"].as_bool().unwrap(),
                        case["expected"].as_str().unwrap(),
                        &case["acceptable"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .map(|v| v.as_str().unwrap().to_string())
                            .collect::<Vec<_>>(),
                        case["input_reading"].as_str().unwrap(),
                        case["main_output"].as_str().unwrap(),
                        suggestion,
                    );
                    assert_eq!(
                        score.status.as_str(),
                        case["status"].as_str().unwrap(),
                        "{case:?}"
                    );
                    assert_eq!(
                        score.suggestion_exact,
                        case["suggestion_exact"].as_bool().unwrap(),
                        "{case:?}"
                    );
                }
                "predictive" => {
                    let ok = score_predictive(
                        case["prediction"].as_str(),
                        case["full_sentence"].as_str().unwrap(),
                        case["expected_continuation"].as_str().unwrap(),
                        &case["acceptable_continuations"]
                            .as_array()
                            .unwrap()
                            .iter()
                            .map(|v| v.as_str().unwrap().to_string())
                            .collect::<Vec<_>>(),
                    );
                    assert_eq!(ok, case["is_match"].as_bool().unwrap(), "{case:?}");
                }
                _ => panic!("unknown kind"),
            }
        }
    }

    #[test]
    fn percentiles_use_nearest_rank() {
        assert_eq!(latency_percentiles(&[]), (0.0, 0.0, 0.0));
        assert_eq!(
            latency_percentiles(&[10.0, 20.0, 30.0, 40.0, 50.0]),
            (30.0, 50.0, 50.0)
        );
        assert_eq!(latency_percentiles(&[5.0]), (5.0, 5.0, 5.0));
    }

    #[test]
    fn typo_status_round_trip() {
        for s in [
            "exact_match",
            "variant_match",
            "clean_preserved",
            "false_positive",
            "missed",
        ] {
            assert_eq!(TypoStatus::from_str(s).unwrap().as_str(), s);
        }
        assert!(TypoStatus::from_str("unknown").is_none());
    }
}
