//! Ordered input/edit checks against the running Windows server. No learning commits.
use hyper_util::rt::TokioIo;
use shared::proto::{self, azookey_service_client::AzookeyServiceClient};
use std::{
    error::Error,
    os::windows::io::IntoRawHandle,
    time::{Duration, Instant},
};
use tokio::net::windows::named_pipe::NamedPipeClient;
use tonic::transport::Endpoint;
use tower::service_fn;

enum Verification {
    Smoke,
    DictionaryCases(String),
    Reconversion(String),
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let mut arguments = std::env::args().skip(1);
    let output = arguments.next().ok_or("supply a new output JSON path")?;
    if std::path::Path::new(&output).exists() {
        return Err("preserving existing results".into());
    }
    let mode = match arguments.next().as_deref() {
        None => Verification::Smoke,
        Some("--dictionary-cases") => {
            Verification::DictionaryCases(arguments.next().ok_or("supply a cases TSV path")?)
        }
        Some("--reconversion") => {
            Verification::Reconversion(arguments.next().ok_or("supply a surface")?)
        }
        _ => {
            return Err(
                "expected --dictionary-cases <cases.tsv> or --reconversion <surface>".into(),
            )
        }
    };
    if arguments.next().is_some() {
        return Err("unexpected arguments".into());
    }
    let pipe = shared::server_pipe_path()?.to_string();
    let channel = Endpoint::from_static("http://[::]:50051")
        .timeout(Duration::from_secs(10))
        .connect_with_connector(service_fn(move |_| {
            let pipe = pipe.clone();
            async move {
                let handle = shared::open_named_pipe_client_handle(&pipe)?;
                let client = unsafe { NamedPipeClient::from_raw_handle(handle.into_raw_handle()) }?;
                Ok::<_, std::io::Error>(TokioIo::new(client))
            }
        }))
        .await?;
    let mut client = AzookeyServiceClient::new(channel);
    match mode {
        Verification::DictionaryCases(cases) => {
            return run_dictionary_cases(&mut client, &output, &cases).await;
        }
        Verification::Reconversion(surface) => {
            return probe_reconversion(&mut client, &output, &surface).await;
        }
        Verification::Smoke => {}
    }
    let mut trace = Vec::new();
    let mut failures = Vec::new();
    for sequence in [
        "okseri",
        "okaeri",
        "otkare",
        "argatou",
        "siryouwookurimssu",
        "siryouwookurimasu",
        "ksiginosiryou",
        "kaiginosiryou",
        "sshitanokaigi",
        "ashitanokaigi",
        "gokakuninkudasa",
    ] {
        client
            .clear_text(proto::ClearTextRequest::default())
            .await?;
        client
            .set_context(proto::SetContextRequest::default())
            .await?;
        let mut current = None;
        for key in sequence.chars() {
            let text = client
                .append_text(proto::AppendTextRequest {
                    text_to_append: key.to_string(),
                    ..Default::default()
                })
                .await?
                .into_inner()
                .composing_text
                .ok_or("missing composition")?;
            trace.push(
                serde_json::json!({"sequence":sequence,"event":"key","key":key.to_string(),
                "reading":text.hiragana,"baseline":text.suggestions.first().map(|s|&s.text)}),
            );
            current = Some(text);
        }
        let current = current.unwrap();
        let baseline = current
            .suggestions
            .first()
            .map(|s| format!("{}{}", s.text, s.subtext))
            .unwrap_or_default();
        let request = proto::GetAssistCandidatesRequest {
            reading: current.hiragana.clone(),
            raw_input: sequence.into(),
            baseline: baseline.clone(),
            input_count: current
                .suggestions
                .iter()
                .map(|s| s.corresponding_count)
                .max()
                .unwrap_or(0),
            kind: 1,
            ..Default::default()
        };
        let mut prediction_request = request.clone();
        prediction_request.kind = 2;
        let mut prediction_client = client.clone();
        let started = std::time::Instant::now();
        let (response, prediction) = tokio::join!(
            client.get_assist_candidates(request),
            prediction_client.get_assist_candidates(prediction_request)
        );
        let combined_ms = started.elapsed().as_millis();
        let response = response?.into_inner();
        let prediction = prediction?.into_inner();
        let correction = response
            .suggestions
            .iter()
            .find(|s| s.candidate_id == shared::ai_candidate::TYPO_CORRECTION);
        if sequence == "okseri" && (correction.is_none() || !response.auto_apply) {
            failures.push("okseri did not produce an automatic adjacent-key correction");
        }
        if sequence == "okaeri" && correction.is_some() {
            failures.push("normal okaeri was proposed as a typo");
        }
        if ["siryouwookurimasu", "kaiginosiryou", "ashitanokaigi"].contains(&sequence)
            && correction.is_some()
        {
            failures.push("normal adjacent-key control was proposed as a typo");
        }
        if ["siryouwookurimssu", "ksiginosiryou"].contains(&sequence) {
            if correction.is_none() {
                failures.push("hybrid typo proposal was absent");
            }
            if response.auto_apply {
                failures.push("hybrid-only proposal was applied automatically");
            }
        }
        if sequence == "gokakuninkudasa" && correction.is_none() {
            failures.push("gokakuninkudasa did not produce a corrector fallback proposal");
        }
        trace.push(serde_json::json!({"sequence":sequence,"event":"assist","reading":current.hiragana,
            "baseline":baseline,"correction":correction.map(|s|&s.text),"auto_apply":response.auto_apply,
            "relevance":response.relevance,"combined_assist_ms":combined_ms,"prediction_count":prediction.suggestions.len()}));
        let removed = client
            .remove_text(proto::RemoveTextRequest::default())
            .await?
            .into_inner();
        trace.push(
            serde_json::json!({"sequence":sequence,"event":"delete","raw":removed.raw_input,
            "reading":removed.composing_text.map(|c|c.hiragana)}),
        );
        for offset in [-1, 1] {
            let moved = client
                .move_cursor(proto::MoveCursorRequest {
                    offset,
                    ..Default::default()
                })
                .await?
                .into_inner();
            trace.push(
                serde_json::json!({"sequence":sequence,"event":"move_cursor","offset":offset,
                "reading":moved.composing_text.map(|c|c.hiragana)}),
            );
        }
        client
            .clear_text(proto::ClearTextRequest::default())
            .await?;
        let refreshed = client
            .move_cursor(proto::MoveCursorRequest::default())
            .await?
            .into_inner();
        if refreshed
            .composing_text
            .as_ref()
            .is_some_and(|c| !c.hiragana.is_empty())
        {
            failures.push("cancel left nonempty server composition");
        }
        trace.push(serde_json::json!({"sequence":sequence,"event":"cancel","reading":refreshed.composing_text.map(|c|c.hiragana)}));
    }
    client
        .clear_text(proto::ClearTextRequest::default())
        .await?;
    std::fs::write(
        output,
        serde_json::to_string_pretty(&serde_json::json!({
            "scope":"live Windows RPC + Swift + corrector; no TSF/UI adoption or learning commits",
            "trace":trace,"failures":failures,
        }))?,
    )?;
    if !failures.is_empty() {
        return Err(format!("{} RPC regression failures", failures.len()).into());
    }
    println!("Windows RPC ordered input/edit/cancel checks passed");
    Ok(())
}

async fn probe_reconversion(
    client: &mut AzookeyServiceClient<tonic::transport::Channel>,
    output: &str,
    surface: &str,
) -> Result<(), Box<dyn Error>> {
    let converted = client
        .start_reconversion(proto::StartReconversionRequest {
            surface: surface.into(),
            ..Default::default()
        })
        .await?
        .into_inner();
    client
        .clear_text(proto::ClearTextRequest::default())
        .await?;
    let composition = converted.composing_text;
    std::fs::write(
        output,
        serde_json::to_string_pretty(&serde_json::json!({
            "surface": surface, "applied": converted.applied,
            "reading": composition.as_ref().map(|text| &text.hiragana),
            "suggestions": composition.map(|text| text.suggestions.into_iter().map(|s| s.text).collect::<Vec<_>>()),
            "session": converted.server_session_id,
        }))?,
    )?;
    Ok(())
}

async fn run_dictionary_cases(
    client: &mut AzookeyServiceClient<tonic::transport::Channel>,
    output: &str,
    cases: &str,
) -> Result<(), Box<dyn Error>> {
    let source = std::fs::read_to_string(cases)?;
    let mut results = Vec::new();
    let mut ids = std::collections::HashSet::new();
    client
        .clear_text(proto::ClearTextRequest::default())
        .await?;
    client
        .set_context(proto::SetContextRequest::default())
        .await?;
    client
        .append_text(proto::AppendTextRequest {
            text_to_append: "きょうはてんきです".into(),
            input_style: proto::InputStyle::Direct as i32,
            ..Default::default()
        })
        .await?;
    for line in source.lines().filter(|line| !line.is_empty()) {
        let fields: Vec<_> = line.split('\t').collect();
        let [group, id, style, input, expected] = fields.as_slice() else {
            return Err(format!("invalid case row: {line}").into());
        };
        if !matches!(
            *group,
            "sudachi" | "wikidata" | "control" | "bulk" | "collision"
        ) || !ids.insert(*id)
            || input.is_empty()
            || expected.is_empty()
        {
            return Err(format!("invalid or duplicate case: {id}").into());
        }
        let input_style = match *style {
            "kana" => proto::InputStyle::Direct,
            "romaji" => proto::InputStyle::Roman2kana,
            _ => return Err(format!("invalid input style for {id}").into()),
        };
        client
            .clear_text(proto::ClearTextRequest::default())
            .await?;
        client
            .set_context(proto::SetContextRequest::default())
            .await?;
        let mut events = Vec::new();
        for key in input.chars() {
            let start = Instant::now();
            let response = client
                .append_text(proto::AppendTextRequest {
                    text_to_append: key.to_string(),
                    input_style: input_style as i32,
                    ..Default::default()
                })
                .await?
                .into_inner();
            let elapsed_ms = start.elapsed().as_secs_f64() * 1000.0;
            let composition = response.composing_text.ok_or("missing composition")?;
            let candidates: Vec<_> = composition
                .suggestions
                .iter()
                .take(5)
                .map(|suggestion| format!("{}{}", suggestion.text, suggestion.subtext))
                .collect();
            events.push(serde_json::json!({
                "key": key, "reading": composition.hiragana, "ms": elapsed_ms, "top5": candidates,
                "session": response.server_session_id,
            }));
        }
        results.push(serde_json::json!({
            "group": group, "id": id, "style": style, "input": input, "expected": expected, "events": events,
        }));
    }
    client
        .clear_text(proto::ClearTextRequest::default())
        .await?;
    if results.is_empty() {
        return Err("no dictionary cases".into());
    }
    std::fs::write(
        output,
        serde_json::to_string_pretty(&serde_json::json!({"cases": results}))?,
    )?;
    println!("Recorded {} Windows dictionary cases", results.len());
    Ok(())
}
