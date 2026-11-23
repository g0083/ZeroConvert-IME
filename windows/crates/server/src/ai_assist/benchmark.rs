//! Windows と Android で共有する設定画面ベンチマークの入力整形、実行、採点。

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

/// One serial batch uses the same model session as the preceding batch.
pub const BATCH_SIZE: usize = 16;

pub fn reset_session() {
    reading_check::reset_benchmark_dictionary();
}

use super::{corrector, prediction, reading_check, runtime, AiAssistConfig};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    cell::Cell,
    path::Path,
    rc::Rc,
    time::{Duration, Instant},
};

#[derive(Deserialize, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Case {
    Conversion {
        id: u32,
        left_context: String,
        target: String,
        expected: String,
        #[serde(default)]
        acceptable: Vec<String>,
        #[serde(default)]
        forbidden_examples: Vec<String>,
    },
    Typo {
        id: u32,
        context: String,
        input_reading: String,
        expected: String,
        is_clean: bool,
        #[serde(default)]
        acceptable: Vec<String>,
        #[serde(default)]
        raw_romaji: String,
    },
    Predictive {
        id: u32,
        context: String,
        partial_input: String,
        full_sentence: String,
        expected_continuation: String,
        #[serde(default)]
        acceptable_continuations: Vec<String>,
    },
}

#[derive(Deserialize)]
pub struct Completion {
    reason: String,
    text: String,
    elapsed_ns: u64,
}

#[derive(Clone, Copy, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ConversionScope {
    #[default]
    Model,
    Candidates,
    Typo,
}

#[cfg(windows)]
#[derive(Deserialize, Serialize)]
pub struct WindowsTypoOptions {
    pub enabled: bool,
    pub engine: String,
}

#[derive(Deserialize)]
pub struct CandidateCompletion {
    candidates: Vec<String>,
    elapsed_ns: u64,
    nbest_ns: u64,
    #[serde(default)]
    corrected_reading: Option<String>,
}

pub fn run_typo_case(
    case: &Case,
    dictionary: &Path,
    mut convert: impl FnMut(&str, &str) -> Option<CandidateCompletion>,
    correct: impl FnOnce(&str, &str, &str, &str) -> Option<super::TypoSuggestion>,
) -> Result<Value, String> {
    let Case::Typo {
        id,
        context,
        input_reading,
        expected,
        is_clean,
        acceptable,
        raw_romaji,
    } = case
    else {
        return Err("typo benchmark requires a typo case".into());
    };
    reading_check::with_benchmark_dictionary(dictionary, || {
        let started = Instant::now();
        let context = normalize_context(context);
        let reading = katakana_to_hiragana(&input_reading.replace([' ', '　'], ""));
        let converted = convert(&reading, context)
            .ok_or_else(|| format!("通常候補を生成できませんでした（問題 {id}）。"))?;
        let baseline = converted
            .candidates
            .into_iter()
            .next()
            .unwrap_or_else(|| reading.clone());
        if let Some(corrected) = converted
            .corrected_reading
            .filter(|value| katakana_to_hiragana(value) != reading)
        {
            let score = score_typo(
                *is_clean,
                expected,
                acceptable,
                &reading,
                &reading,
                Some(&baseline),
            );
            return Ok(
                json!({ "id": id, "main_output": baseline, "moshikashite_output": baseline,
                "correction_reading": corrected, "status": score.status.as_str(),
                "latency_ms": started.elapsed().as_secs_f64() * 1000.0 }),
            );
        }
        let correction = correct(context, &reading, &baseline, raw_romaji)
            .filter(|result| !result.surface.is_empty() && result.surface != baseline);
        let mut corrected_reading = None;
        let proposal = if let Some(result) = correction {
            let surface = result.surface;
            // The keyboard channel returns a reading, including any literal characters.
            if result.channel_confidence.is_some()
                || surface.chars().all(|c| {
                    matches!(c, '\u{3041}'..='\u{309e}' | '\u{30a1}'..='\u{30fe}' | '。' | '、' | '！' | '？' | '!' | '?')
                })
            {
                let converted = convert(&surface, context)
                    .ok_or_else(|| format!("補正後の読みを変換できませんでした（問題 {id}）。"))?
                    .candidates
                    .into_iter()
                    .next()
                    .unwrap_or_else(|| surface.clone());
                corrected_reading = (surface != converted).then_some(surface);
                Some(converted)
            } else {
                Some(surface)
            }
        } else {
            None
        };
        let score = score_typo(
            *is_clean,
            expected,
            acceptable,
            &reading,
            &baseline,
            proposal.as_deref(),
        );
        Ok(
            json!({ "id": id, "main_output": baseline, "moshikashite_output": proposal,
            "correction_reading": corrected_reading,
            "status": score.status.as_str(), "latency_ms": started.elapsed().as_secs_f64() * 1000.0 }),
        )
    })?
}

pub fn run_candidate_batch(
    cases: &[Case],
    mut convert: impl FnMut(&str, &str) -> Option<CandidateCompletion>,
) -> Result<Vec<Value>, String> {
    if cases.is_empty() || cases.len() > BATCH_SIZE {
        return Err("invalid benchmark batch size".into());
    }
    cases.iter().map(|case| {
        let Case::Conversion { id, left_context, target, expected, acceptable, forbidden_examples } = case else {
            return Err("candidate benchmark requires conversion cases".into());
        };
        let reading = target.replace([' ', '　'], "");
        let result = convert(&reading, normalize_context(left_context))
            .ok_or_else(|| format!("候補を生成できませんでした（問題 {id}）。"))?;
        let output = result.candidates.first().map(String::as_str).unwrap_or_default();
        let score = score_conversion(output, expected, acceptable, forbidden_examples);
        Ok(json!({"id": id, "top1": output, "candidates": result.candidates,
            "is_correct": score.is_correct, "reason": score.reason,
            "latency_ms": result.elapsed_ns as f64 / 1e6, "nbest_ms": result.nbest_ns as f64 / 1e6}))
    }).collect()
}

type Complete = dyn Fn(&str, u64, u64) -> Option<Completion>;

struct Inference {
    complete: Rc<Complete>,
    transport: Cell<Duration>,
    generation: Cell<Duration>,
    failed: Cell<bool>,
}

impl runtime::CompletionBackend for Rc<Inference> {
    fn read_learning(&self) -> bool {
        false
    }
    fn cache_results(&self) -> bool {
        false
    }
    fn complete(&self, _: u16, _: &str, request: &Value, timeout: u64) -> Option<Value> {
        self.generate(
            request["prompt"].as_str()?,
            request["n_predict"].as_u64()?,
            timeout,
        )
        .map(|reply| json!({"content": reply.text, "stop_type": reply.reason}))
    }
}

impl Inference {
    fn generate(&self, prompt: &str, max_tokens: u64, timeout: u64) -> Option<Completion> {
        let start = Instant::now();
        let reply = (self.complete)(prompt, max_tokens, timeout);
        self.transport.set(self.transport.get() + start.elapsed());
        if let Some(reply) = &reply {
            self.generation
                .set(self.generation.get() + Duration::from_nanos(reply.elapsed_ns));
        }
        match reply {
            Some(reply) if matches!(reply.reason.as_str(), "eos" | "stop" | "limit") => Some(reply),
            Some(reply) if reply.reason == "timeout" => None,
            _ => {
                self.failed.set(true);
                None
            }
        }
    }

    fn convert(&self, context: &str, reading: &str, tokens: u64) -> String {
        let reading = to_katakana(reading);
        let (context, _) = corrector::mask_digits_with_nam(context);
        let (masked, digits) = corrector::mask_digits_with_nam(&reading);
        let prompt = format!("\u{EE02}{context}\u{EE00}{masked}\u{EE01}");
        let completion = self.generate(&prompt, tokens, 2000);
        if completion.is_none() {
            self.failed.set(true);
        }
        completion
            .map(|result| {
                corrector::restore_nam_digits(
                    result
                        .text
                        .trim()
                        .trim_end_matches(|ch| ('\u{E000}'..='\u{F8FF}').contains(&ch))
                        .trim(),
                    &digits,
                )
            })
            .filter(|text| !text.is_empty())
            .unwrap_or(reading)
    }
}

fn normalize_context(context: &str) -> &str {
    match context.trim() {
        "なし" => "",
        context => context,
    }
}

pub fn run_batch(
    cases: &[Case],
    dictionary: &Path,
    complete: impl Fn(&str, u64, u64) -> Option<Completion> + 'static,
) -> Result<Vec<Value>, String> {
    if cases.is_empty() || cases.len() > BATCH_SIZE {
        return Err("invalid benchmark batch size".into());
    }
    if cases
        .iter()
        .any(|case| !matches!(case, Case::Conversion { .. }))
    {
        return reading_check::with_benchmark_dictionary(dictionary, || {
            execute_batch(cases, complete)
        })?;
    }
    execute_batch(cases, complete)
}

fn execute_batch(
    cases: &[Case],
    complete: impl Fn(&str, u64, u64) -> Option<Completion> + 'static,
) -> Result<Vec<Value>, String> {
    let complete = Rc::new(complete) as Rc<Complete>;
    let config = AiAssistConfig {
        enabled: true,
        ..Default::default()
    };
    cases
        .iter()
        .map(|case| {
            let inference = Rc::new(Inference {
                complete: complete.clone(),
                generation: Cell::default(),
                transport: Cell::default(),
                failed: Cell::default(),
            });
            let started = Instant::now();
            let (id, mut result) =
                runtime::with_backend(Box::new(inference.clone()), || match case {
                    Case::Conversion {
                        id,
                        left_context,
                        target,
                        expected,
                        acceptable,
                        forbidden_examples,
                    } => {
                        let context = normalize_context(left_context);
                        let output =
                            inference.convert(context, &target.replace([' ', '　'], ""), 80);
                        let score =
                            score_conversion(&output, expected, acceptable, forbidden_examples);
                        (
                            *id,
                            json!({"top1": output, "candidates": [output],
                                 "is_correct": score.is_correct, "reason": score.reason}),
                        )
                    }
                    Case::Typo {
                        id,
                        context,
                        input_reading,
                        expected,
                        acceptable,
                        is_clean,
                        ..
                    } => {
                        let context = normalize_context(context);
                        let reading = input_reading.replace([' ', '　'], "");
                        let main = inference.convert(context, &reading, 16);
                        let correction = corrector::suggest_composition_heuristic(
                            context, &reading, &main, &config,
                        )
                        .map(|suggestion| suggestion.surface);
                        let score = score_typo(
                            *is_clean,
                            expected,
                            acceptable,
                            input_reading,
                            &main,
                            correction.as_deref(),
                        );
                        (
                            *id,
                            json!({"main_output": main, "moshikashite_output": correction,
                                 "status": score.status.as_str()}),
                        )
                    }
                    Case::Predictive {
                        id,
                        context,
                        partial_input,
                        full_sentence,
                        expected_continuation,
                        acceptable_continuations,
                    } => {
                        let context = normalize_context(context);
                        let partial = partial_input.trim();
                        let reading = reading_check::reading_for_surface(partial)
                            .unwrap_or_else(|| katakana_to_hiragana(partial));
                        let prediction = prediction::suggest(context, &reading, partial, &config)
                            .map(|prediction| format!("{partial}{}", prediction.continuation));
                        let is_match = score_predictive(
                            prediction.as_deref(),
                            full_sentence,
                            expected_continuation,
                            acceptable_continuations,
                        );
                        (*id, json!({"prediction": prediction, "is_match": is_match}))
                    }
                });
            if inference.failed.get() {
                return Err(format!("benchmark inference failed (case {id})"));
            }
            // Measure shared processing and native generation, excluding the host transport.
            let elapsed = started.elapsed().saturating_sub(inference.transport.get())
                + inference.generation.get();
            result["id"] = json!(id);
            result["latency_ms"] = json!(elapsed.as_secs_f64() * 1000.0);
            Ok(result)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    #[test]
    fn typo_benchmark_scores_reconverted_reading_and_swift_corrections() {
        let dictionary =
            std::env::temp_dir().join(format!("zerocon-typo-benchmark-{}.bin", std::process::id()));
        std::fs::write(
            &dictionary,
            b"AZR2\x01\0\0\0\x10\0\0\0\x1a\0\0\0\x01\0\x01\0\0\0\0\0aa",
        )
        .unwrap();
        let case = |is_clean| Case::Typo {
            id: 1,
            context: "なし".into(),
            input_reading: "オカイリ！".into(),
            expected: "お帰り！".into(),
            is_clean,
            acceptable: vec![],
            raw_romaji: String::new(),
        };
        let completion = |surface: &str, reading: Option<&str>| CandidateCompletion {
            candidates: vec![surface.into()],
            elapsed_ns: 0,
            nbest_ns: 0,
            corrected_reading: reading.map(str::to_owned),
        };
        let repaired = run_typo_case(
            &case(false),
            &dictionary,
            |reading, context| {
                assert_eq!(context, "");
                Some(match reading {
                    "おかいり！" => completion("おかいり！", None),
                    "おかえり！" => completion("お帰り！", None),
                    _ => panic!("unexpected reading {reading}"),
                })
            },
            |_, reading, baseline, _| {
                assert_eq!(reading, "おかいり！");
                assert_eq!(baseline, "おかいり！");
                Some(super::super::TypoSuggestion {
                    surface: "おかえり！".into(),
                    token_reading: reading.into(),
                    token_surface: "おかえり！".into(),
                    learned: false,
                    channel_confidence: None,
                })
            },
        )
        .unwrap();
        assert_eq!(repaired["moshikashite_output"], "お帰り！");
        assert_eq!(repaired["correction_reading"], "おかえり！");
        assert_eq!(repaired["status"], "exact_match");

        let changed_clean = run_typo_case(
            &case(true),
            &dictionary,
            |_, _| Some(completion("お買い物", Some("おかいもの"))),
            |_, _, _, _| panic!("Swift has already changed the reading"),
        )
        .unwrap();
        assert_eq!(changed_clean["status"], "false_positive");
        std::fs::remove_file(dictionary).unwrap();
    }

    #[test]
    fn benchmark_batch_uses_shared_prompt_and_scores_the_returned_text() {
        let case = Case::Conversion {
            id: 4,
            left_context: " なし ".into(),
            target: "とう きょう".into(),
            expected: "東京".into(),
            acceptable: Vec::new(),
            forbidden_examples: Vec::new(),
        };
        let rows = run_batch(&[case], Path::new(""), |prompt, tokens, timeout| {
            assert_eq!(prompt, "\u{EE02}\u{EE00}トウキョウ\u{EE01}");
            assert_eq!((tokens, timeout), (80, 2000));
            Some(Completion {
                reason: "eos".into(),
                text: "東京".into(),
                elapsed_ns: 1_000_000,
            })
        })
        .unwrap();
        assert_eq!(rows[0]["id"], 4);
        assert_eq!(rows[0]["top1"], "東京");
        assert_eq!(rows[0]["is_correct"], true);
        assert!(rows[0]["latency_ms"].as_f64().unwrap() >= 1.0);
    }

    #[test]
    fn candidate_benchmark_scores_the_top_candidate_and_keeps_dictionary_timing() {
        let case = Case::Conversion {
            id: 4,
            left_context: " なし ".into(),
            target: "とう きょう".into(),
            expected: "東京".into(),
            acceptable: Vec::new(),
            forbidden_examples: Vec::new(),
        };
        let rows = run_candidate_batch(&[case], |reading, context| {
            assert_eq!((reading, context), ("とうきょう", ""));
            Some(CandidateCompletion {
                candidates: vec!["とうきょう".into(), "東京".into()],
                elapsed_ns: 5_000_000,
                nbest_ns: 2_000_000,
                corrected_reading: None,
            })
        })
        .unwrap();
        assert_eq!(rows[0]["id"], 4);
        assert_eq!(rows[0]["top1"], "とうきょう");
        assert_eq!(rows[0]["candidates"], json!(["とうきょう", "東京"]));
        assert_eq!(rows[0]["is_correct"], false);
        assert_eq!(rows[0]["latency_ms"], 5.0);
        assert_eq!(rows[0]["nbest_ms"], 2.0);
    }

    fn test_data() -> Vec<Value> {
        let cases = [
            // (kind, json)
            (
                "conversion",
                r#"{"kind":"conversion","output":"記者が取材する","expected":"記者が取材する","acceptable":[],"forbidden":[],"is_correct":true,"reason":"exact"}"#,
            ),
            (
                "conversion",
                r#"{"kind":"conversion","output":"汽車が駅に到着した","expected":"汽車が駅に到着した","acceptable":[],"forbidden":[],"is_correct":true,"reason":"exact"}"#,
            ),
            (
                "conversion",
                r#"{"kind":"conversion","output":"記者が駅に到着した","expected":"汽車が駅に到着した","acceptable":[],"forbidden":["記者が駅に到着した"],"is_correct":false,"reason":"forbidden"}"#,
            ),
            (
                "conversion",
                r#"{"kind":"conversion","output":"今日は 天気が いい","expected":"今日は天気がいい","acceptable":[],"forbidden":[],"is_correct":true,"reason":"whitespace"}"#,
            ),
            (
                "conversion",
                r#"{"kind":"conversion","output":"今日は天気がイイ","expected":"今日は天気がいい","acceptable":[],"forbidden":[],"is_correct":true,"reason":"kana"}"#,
            ),
            (
                "conversion",
                r#"{"kind":"conversion","output":"明日は東京駅で待ち合わせる","expected":"明日は東京駅で待ち合わせる","acceptable":["明日は東京駅でまちあわせる"],"forbidden":[],"is_correct":true,"reason":"exact"}"#,
            ),
            (
                "conversion",
                r#"{"kind":"conversion","output":"電車がおくれている","expected":"電車が遅れている","acceptable":["電車がおくれている"],"forbidden":[],"is_correct":true,"reason":"exact"}"#,
            ),
            (
                "conversion",
                r#"{"kind":"conversion","output":"まったく別の文","expected":"今日は天気がいい","acceptable":[],"forbidden":[],"is_correct":false,"reason":"mismatch"}"#,
            ),
            (
                "typo",
                r#"{"kind":"typo","is_clean":false,"expected":"ご確認ください","acceptable":[],"input_reading":"ゴカクニンクダサ","main_output":"ご確認ください","suggestion":null,"status":"exact_match","suggestion_exact":false}"#,
            ),
            (
                "typo",
                r#"{"kind":"typo","is_clean":false,"expected":"ご確認ください","acceptable":[],"input_reading":"ゴカクニンクダサ","main_output":"ご確認します","suggestion":"ご確認ください","status":"exact_match","suggestion_exact":true}"#,
            ),
            (
                "typo",
                r#"{"kind":"typo","is_clean":false,"expected":"ご確認ください","acceptable":[],"input_reading":"ゴカクニンクダサ","main_output":"ご確認ください。","suggestion":null,"status":"variant_match","suggestion_exact":false}"#,
            ),
            (
                "typo",
                r#"{"kind":"typo","is_clean":false,"expected":"ご確認ください","acceptable":[],"input_reading":"ゴカクニンクダサ","main_output":"まったく違う","suggestion":null,"status":"missed","suggestion_exact":false}"#,
            ),
            (
                "typo",
                r#"{"kind":"typo","is_clean":true,"expected":"検討いたします","acceptable":[],"input_reading":"ケントウイタシマス","main_output":"検討いたします","suggestion":null,"status":"clean_preserved","suggestion_exact":false}"#,
            ),
            (
                "typo",
                r#"{"kind":"typo","is_clean":true,"expected":"検討いたします","acceptable":[],"input_reading":"ケントウイタシマス","main_output":"検討いたします","suggestion":"検討いたします","status":"clean_preserved","suggestion_exact":true}"#,
            ),
            (
                "typo",
                r#"{"kind":"typo","is_clean":true,"expected":"検討いたします","acceptable":[],"input_reading":"ケントウイタシマス","main_output":"検討いたします","suggestion":"ケントウイタシマス","status":"clean_preserved","suggestion_exact":false}"#,
            ),
            (
                "typo",
                r#"{"kind":"typo","is_clean":true,"expected":"検討いたします","acceptable":[],"input_reading":"ケントウイタシマス","main_output":"検討いたします","suggestion":"原因を検討します","status":"false_positive","suggestion_exact":false}"#,
            ),
            (
                "predictive",
                r#"{"kind":"predictive","prediction":"いつもお世話になっております。","full_sentence":"いつもお世話になっております。","expected_continuation":"なっております。","acceptable_continuations":["なっております","なり、ありがとうございます。"],"is_match":true}"#,
            ),
            (
                "predictive",
                r#"{"kind":"predictive","prediction":"よろしくお願いいたします。","full_sentence":"よろしくお願いいたします。","expected_continuation":"いたします。","acceptable_continuations":["いたします","します。","申し上げます。"],"is_match":true}"#,
            ),
            (
                "predictive",
                r#"{"kind":"predictive","prediction":"よろしくお願いします。","full_sentence":"よろしくお願いいたします。","expected_continuation":"いたします。","acceptable_continuations":["いたします","します。","申し上げます。"],"is_match":true}"#,
            ),
            (
                "predictive",
                r#"{"kind":"predictive","prediction":null,"full_sentence":"いつもお世話になっております。","expected_continuation":"なっております。","acceptable_continuations":["なっております"],"is_match":false}"#,
            ),
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
