//! Isolated evaluation of the actual Windows corrector, without personal learning.
use serde_json::{json, Value};
use std::{
    cell::RefCell,
    error::Error,
    io::{Read, Write},
    net::TcpStream,
    path::PathBuf,
    rc::Rc,
    time::{Duration, Instant},
};
use zerocon_engine::ai_assist::{self, corrector, runtime, AiAssistConfig};

fn complete(port: u16, body: &Value, timeout_ms: u64) -> Option<Value> {
    let duration = Duration::from_millis(timeout_ms);
    let mut stream = TcpStream::connect_timeout(&([127, 0, 0, 1], port).into(), duration).ok()?;
    stream.set_read_timeout(Some(duration)).ok()?;
    stream.set_write_timeout(Some(duration)).ok()?;
    let data = body.to_string();
    write!(stream, "POST /completion HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}", data.len(), data).ok()?;
    let mut response = String::new();
    stream.take(128 * 1024).read_to_string(&mut response).ok()?;
    if !response.starts_with("HTTP/1.1 200") && !response.starts_with("HTTP/1.0 200") {
        return None;
    }
    serde_json::from_str(response.split_once("\r\n\r\n")?.1).ok()
}

struct Backend {
    calls: Rc<RefCell<Vec<Value>>>,
    reading_normal_port: Option<u16>,
    original_input: String,
    oracle_surface: Option<String>,
    replay_responses: Option<Value>,
}
impl runtime::CompletionBackend for Backend {
    fn read_learning(&self) -> bool {
        false
    }
    fn allow_native_worker(&self) -> bool {
        self.oracle_surface.is_none()
            && self.replay_responses.is_none()
            && self.reading_normal_port.is_none()
    }
    fn wait_for_native_worker(&self) -> bool {
        self.allow_native_worker()
    }
    fn complete(&self, port: u16, _path: &str, body: &Value, timeout_ms: u64) -> Option<Value> {
        let started = Instant::now();
        let response = if let Some(surface) = &self.oracle_surface {
            Some(json!({"content": surface, "stop_type": "eos", "tokens_predicted": 1}))
        } else if let Some(responses) = &self.replay_responses {
            responses
                .get(body["prompt"].as_str()?)
                .cloned()
                .filter(|v| !v.is_null())
        } else {
            complete(port, body, timeout_ms)
        };
        self.calls.borrow_mut().push(json!({
            "stage": "corrector",
            "prompt": body["prompt"], "request": body, "response": response,
            "elapsed_ms": started.elapsed().as_secs_f64()*1000.0,
        }));
        if let Some(normal_port) = self.reading_normal_port {
            let raw = response.as_ref()?;
            let input = body["prompt"]
                .as_str()?
                .split_once('\u{EE00}')?
                .1
                .split('\u{EE01}')
                .next()?;
            let reading = corrector::completed_surface(raw, input, body["n_predict"].as_u64()?)?;
            if !reading_output_valid(input, &reading) {
                return None;
            }
            // An unchanged composing reading needs no second conversion. Keep
            // conversion for the production romaji decoder's reconstructed input.
            if ai_assist::katakana_to_hiragana(input)
                == ai_assist::katakana_to_hiragana(&self.original_input)
                && ai_assist::katakana_to_hiragana(&reading)
                    == ai_assist::katakana_to_hiragana(input)
            {
                return response;
            }
            let original = body["prompt"].as_str()?;
            let prefix = original.split_once('\u{EE00}')?.0;
            let mut conversion = body.clone();
            conversion["prompt"] = json!(format!(
                "{prefix}\u{EE00}{}\u{EE01}",
                ai_assist::to_katakana(&reading)
            ));
            conversion["n_predict"] = json!(256);
            conversion["cache_prompt"] = json!(false);
            conversion["seed"] = json!(42);
            // Include both requests in the existing assistance time budget.
            let remaining = timeout_ms.checked_sub(started.elapsed().as_millis() as u64)?;
            if remaining == 0 {
                return None;
            }
            let second_started = Instant::now();
            let converted = complete(normal_port, &conversion, remaining);
            self.calls
                .borrow_mut()
                .push(json!({"stage":"reading_conversion",
                "prompt":conversion["prompt"],"corrected_reading":reading,"response":converted,
                "elapsed_ms":second_started.elapsed().as_secs_f64()*1000.0}));
            converted
        } else {
            response
        }
    }
}

fn reading_output_valid(input: &str, reading: &str) -> bool {
    reading.chars().all(|c| {
        ('ぁ'..='ゖ').contains(&c) || ('ァ'..='ヶ').contains(&c) || "ー、。！？!?・".contains(c)
    }) && corrector::reading_change_distance(input, reading).is_some_and(|n| n <= 2)
}

fn main() -> Result<(), Box<dyn Error>> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() == 5 && args[1] == "--analyze" {
        runtime::configure_paths(runtime::Paths {
            settings: PathBuf::from("unused-evaluation-settings.json"),
            reverse_dictionary: PathBuf::from(&args[3]),
        })
        .map_err(|_| "runtime paths already configured")?;
        let mut report: Value = serde_json::from_str(&std::fs::read_to_string(&args[2])?)?;
        for case in report["cases"].as_array_mut().ok_or("missing cases")? {
            let distance = case["correction"].as_str().and_then(|surface| {
                corrector::reading_change_distance(case["input"].as_str().unwrap(), surface)
            });
            case["reading_edit_distance"] = json!(distance);
            case["gold_reading_distance"] = json!(case["expected"].as_str().and_then(|surface| {
                corrector::reading_change_distance(case["input"].as_str().unwrap(), surface)
            }));
        }
        std::fs::write(&args[4], serde_json::to_string_pretty(&report)?)?;
        return Ok(());
    }
    let reading_mode = args.len() == 7 && args[6] == "--reading-stage";
    let oracle_mode = args.len() == 7 && args[6] == "--oracle";
    let replay_mode = args.len() == 7 && args[6] == "--replay";
    if args.len() != 6 && !reading_mode && !oracle_mode && !replay_mode {
        return Err("usage: evaluate_corrector CASES_JSONL CORRECTOR_PORT NORMAL_PORT REVERSE_DICTIONARY OUTPUT_JSON [--reading-stage|--oracle|--replay]".into());
    }
    let corrector_port = args[2].parse()?;
    let normal_port = args[3].parse()?;
    runtime::configure_paths(runtime::Paths {
        settings: PathBuf::from("unused-evaluation-settings.json"),
        reverse_dictionary: PathBuf::from(&args[4]),
    })
    .map_err(|_| "runtime paths already configured")?;
    let config = AiAssistConfig {
        enabled: true,
        corrector_port,
        ..Default::default()
    };
    if !oracle_mode && !replay_mode {
        let _ = ai_assist::keyboard_hybrid::repair_reading("", "てすと", "", true);
    }
    let rows = std::fs::read_to_string(&args[1])?;
    let mut results = Vec::new();
    for line in rows.lines().filter(|s| !s.trim().is_empty()) {
        let case: Value = serde_json::from_str(line)?;
        let input = case["input_reading"]
            .as_str()
            .or_else(|| case["raw_input"].as_str())
            .or_else(|| case["input"].as_str())
            .ok_or("missing input")?;
        let input = ai_assist::katakana_to_hiragana(input);
        let context = case["context"].as_str().unwrap_or("");
        // A baseline must be generated independently; never supply the gold answer.
        let prompt = format!(
            "\u{EE02}{context}\u{EE00}{}\u{EE01}",
            ai_assist::to_katakana(&input)
        );
        let normal = if replay_mode {
            case.get("normal_response")
                .filter(|v| v.is_object())
                .cloned()
        } else {
            complete(
                normal_port,
                &json!({"prompt":prompt,"n_predict":256,
            "temperature":0.0,"seed":42,"cache_prompt":false,
            "stop":["</s>","<s>","\n","\r","\u{EE00}","\u{EE01}","\u{EE02}"]}),
                5000,
            )
        }
        .ok_or("normal conversion endpoint failed")?;
        let baseline = normal["content"]
            .as_str()
            .filter(|s| !s.is_empty())
            .ok_or("normal conversion returned no content")?;
        corrector::clear_cache();
        let calls = Rc::new(RefCell::new(Vec::new()));
        let started = Instant::now();
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            runtime::with_backend(
                Box::new(Backend {
                    calls: calls.clone(),
                    reading_normal_port: reading_mode.then_some(normal_port),
                    original_input: input.clone(),
                    oracle_surface: oracle_mode
                        .then(|| case["expected"].as_str().unwrap().to_owned()),
                    replay_responses: replay_mode
                        .then(|| case.get("responses").cloned().unwrap_or_else(|| json!({}))),
                }),
                || {
                    let suggestion =
                        corrector::suggest_composition(context, &input, baseline, &config);
                    let auto_apply = suggestion.as_ref().is_some_and(|s| {
                        corrector::can_auto_apply_composition(&input, baseline, &s.surface)
                    });
                    (suggestion, auto_apply)
                },
            )
        }));
        let elapsed = started.elapsed().as_secs_f64() * 1000.0;
        let crashed = result.is_err();
        let (suggestion, auto_apply) = result.unwrap_or((None, false));
        results.push(json!({"id":case["id"],"context":context,"input":input,
            "expected":case["expected"],"acceptable":case["acceptable"],"expected_reading":case["target_reading"],
            "is_clean":case["is_clean"],"typo_type":case["typo_type"],
            "baseline":baseline,"normal_response":normal,
            "correction":suggestion.map(|s|s.surface),"auto_apply":auto_apply,"crashed":crashed,
            "assist_ms":elapsed,"calls":*calls.borrow()}));
        results.last_mut().unwrap()["gold_reading_distance"] = json!(
            corrector::reading_change_distance(&input, case["expected"].as_str().unwrap())
        );
        if results.len() % 25 == 0 {
            eprintln!("evaluated {} cases", results.len());
        }
    }
    std::fs::write(
        &args[5],
        serde_json::to_string_pretty(&json!({
            "evaluation_scope":"actual corrector with model-direct baseline; not Swift/TSF",
            "reading_stage":reading_mode,
            "oracle":oracle_mode,
            "replay":replay_mode,
            "personal_learning":false,"cache":"cleared per case","cases":results,
        }))?,
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn oracle_is_explicit_and_needs_no_model_endpoint() {
        let calls = Rc::new(RefCell::new(Vec::new()));
        let backend = Backend {
            calls: calls.clone(),
            reading_normal_port: None,
            original_input: "てそと".into(),
            oracle_surface: Some("テスト".into()),
            replay_responses: None,
        };
        let response = runtime::CompletionBackend::complete(
            &backend,
            0,
            "/completion",
            &json!({"prompt":"test"}),
            1,
        )
        .unwrap();
        assert_eq!(response["content"], "テスト");
        assert_eq!(response["stop_type"], "eos");
        assert_eq!(calls.borrow().len(), 1);
    }

    #[test]
    fn replay_never_reuses_a_response_for_a_different_prompt() {
        let backend = Backend {
            calls: Rc::new(RefCell::new(Vec::new())),
            reading_normal_port: None,
            original_input: "てそと".into(),
            oracle_surface: None,
            replay_responses: Some(json!({"observed": {"content":"テスト","stop_type":"eos"}})),
        };
        assert!(runtime::CompletionBackend::complete(
            &backend,
            0,
            "/completion",
            &json!({"prompt":"other"}),
            1
        )
        .is_none());
        assert_eq!(
            runtime::CompletionBackend::complete(
                &backend,
                0,
                "/completion",
                &json!({"prompt":"observed"}),
                1
            )
            .unwrap()["content"],
            "テスト"
        );
    }

    #[test]
    fn reading_adapter_accepts_bounded_kana_repairs_and_rejects_rewrites() {
        assert!(reading_output_valid("てそと", "てすと"));
        assert!(reading_output_valid("てすと", "テスト"));
        assert!(!reading_output_valid("てそと", "試験"));
        assert!(!reading_output_valid("てそと", "これはながいぶんしょう"));
        assert!(!reading_output_valid("てそと", "てすと\n"));
        assert!(!reading_output_valid("てそと", "てsと"));
    }
}
