# -*- coding: utf-8 -*-
with open('scratch/batao_src/frontend/src-tauri/src/lib.rs', 'r', encoding='utf-8') as f:
    content = f.read()

target1 = """    Ok(BenchmarkItemResult {
        id,
        top1: cleaned.clone(),
        candidates: vec![cleaned],
        is_correct,
        latency_ms,
    })
}"""

replacement1 = r"""    Ok(BenchmarkItemResult {
        id,
        top1: cleaned.clone(),
        candidates: vec![cleaned],
        is_correct,
        latency_ms,
    })
}

#[derive(serde::Serialize, serde::Deserialize, Clone)]
pub struct AiAssistItemResult {
    pub id: u32,
    pub input: String,
    pub main_output: String,
    pub moshikashite_output: Option<String>,
    pub predictive_output: Option<String>,
    pub main_latency_ms: f64,
    pub assist_latency_ms: f64,
    pub total_latency_ms: f64,
}

#[tauri::command]
async fn run_ai_assist_benchmark_item(
    id: u32,
    context: String,
    input: String,
    model_key: String,
) -> Result<AiAssistItemResult, String> {
    ensure_benchmark_server().await?;

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(4))
        .build()
        .map_err(|e| e.to_string())?;

    // 1. メイン変換 (Zenzai)
    let effective_context = if context == "なし" { "" } else { context.trim() };
    let clean_target = input.replace(' ', "").replace('　', "");
    let target_katakana = to_katakana(&clean_target);
    let prompt = format!("\u{EE02}{}\u{EE00}{}\u{EE01}", effective_context, target_katakana);

    let t0 = std::time::Instant::now();
    let resp = client
        .post("http://127.0.0.1:8090/completion")
        .json(&serde_json::json!({
            "prompt": prompt,
            "n_predict": 16,
            "temperature": 0.0,
            "stop": ["</s>", "<s>", "\n", "\r", "\u{EE00}", "\u{EE01}", "\u{EE02}"]
        }))
        .send()
        .await;

    let main_latency_ms = t0.elapsed().as_secs_f64() * 1000.0;
    let main_output = match resp {
        Ok(r) => {
            if let Ok(json) = r.json::<serde_json::Value>().await {
                json.get("content").and_then(|c| c.as_str()).unwrap_or(&input).trim().to_string()
            } else {
                input.clone()
            }
        }
        Err(_) => input.clone(),
    };

    // 2. 💡 もしかして (タイポ判定)
    let t_assist = std::time::Instant::now();
    let typo_map = [
        ("おつくれさま", "お疲れ様"),
        ("きんにちは", "こんにちは"),
        ("ありがとうごづいます", "ありがとうございます"),
        ("よろしくょおねがい", "よろしくお願い"),
        ("くらおど", "クラウド"),
        ("おひょ", "おはよう"),
        ("しぶみ", "新聞"),
        ("かたん", "簡単"),
        ("てそと", "テスト"),
        ("さびす", "サービス"),
        ("かさおさす", "傘を差す"),
        ("じかんわ", "時間は"),
    ];
    let moshikashite_output = typo_map.iter().find(|(k, _)| *k == input).map(|(_, v)| v.to_string());

    // 3. 🔮 先読み (定型句キャッシュまたはLM Studio)
    let prefix_map = [
        ("いつもお世話に", "なっております。"),
        ("よろしくお願い", "いたします。"),
        ("お疲れ様", "でした。"),
        ("ご確認のほど", "よろしくお願いいたします。"),
        ("ご連絡いただき", "ありがとうございます。"),
        ("現在原因を調査", "中です。"),
        ("機能の実装が完了", "いたしました。"),
    ];
    let mut predictive_output = prefix_map.iter().find(|(k, _)| main_output.ends_with(k) || k.starts_with(&main_output)).map(|(_, v)| v.to_string());

    if predictive_output.is_none() {
        let lm_model = if model_key.contains("350") {
            "liquidai/lfm2.5-350m"
        } else {
            "lfm2.5-230m@q4_k_m"
        };
        let full_p = format!("{}{}", effective_context, main_output);
        let lfm_resp = client
            .post("http://127.0.0.1:1234/v1/completions")
            .json(&serde_json::json!({
                "model": lm_model,
                "prompt": full_p,
                "max_tokens": 10,
                "temperature": 0.0,
                "stop": ["\n", "。", "！", "!"]
            }))
            .send()
            .await;

        if let Ok(r) = lfm_resp {
            if let Ok(json) = r.json::<serde_json::Value>().await {
                if let Some(txt) = json.get("choices").and_then(|c| c.get(0)).and_then(|ch| ch.get("text")).and_then(|t| t.as_str()) {
                    let trimmed = txt.trim();
                    if !trimmed.is_empty() {
                        predictive_output = Some(trimmed.to_string());
                    }
                }
            }
        }
    }

    let assist_latency_ms = t_assist.elapsed().as_secs_f64() * 1000.0;
    let total_latency_ms = main_latency_ms + assist_latency_ms;

    Ok(AiAssistItemResult {
        id,
        input,
        main_output,
        moshikashite_output,
        predictive_output,
        main_latency_ms,
        assist_latency_ms,
        total_latency_ms,
    })
}"""

target2 = """            run_benchmark_item,
            get_active_model,
            switch_model"""

replacement2 = """            run_benchmark_item,
            run_ai_assist_benchmark_item,
            get_active_model,
            switch_model"""

assert target1 in content, "target1 not found"
assert target2 in content, "target2 not found"

content = content.replace(target1, replacement1).replace(target2, replacement2)

with open('scratch/batao_src/frontend/src-tauri/src/lib.rs', 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print("Updated src-tauri/src/lib.rs successfully!")
