import os

path = r"d:\angra\ZeroConvert-IME\scratch\batao_src\crates\server\src\main.rs"
with open(path, "r", encoding="utf-8") as f:
    content = f.read()

# 1. Add append_ai_assist_candidates
t1 = """fn reload_ai_assist_from_settings(config: &AppConfig) {
    AI_ASSIST_ENABLED.store(config.general.experimental_ai_assist, Ordering::Relaxed);
    let is_350m = config.general.experimental_predictive_model.contains("350");
    PREDICTIVE_MODEL_IS_350M.store(is_350m, Ordering::Relaxed);
}"""

r1 = """fn reload_ai_assist_from_settings(config: &AppConfig) {
    let enabled = config.general.experimental_ai_assist;
    AI_ASSIST_ENABLED.store(enabled, Ordering::Relaxed);
    let is_350m = config.general.experimental_predictive_model.contains("350");
    PREDICTIVE_MODEL_IS_350M.store(is_350m, Ordering::Relaxed);
    eprintln!("[AI Assist] config loaded: enabled={}, model_is_350m={}", enabled, is_350m);
}

fn append_ai_assist_candidates(
    hiragana_text: &str,
    final_suggestions: &mut Vec<Suggestion>,
) {
    if !AI_ASSIST_ENABLED.load(Ordering::Relaxed) || hiragana_text.trim().is_empty() {
        return;
    }

    let config = AiAssistConfig {
        enabled: true,
        predictive_model: if PREDICTIVE_MODEL_IS_350M.load(Ordering::Relaxed) {
            "350m_q4km".to_string()
        } else {
            "230m_q4km".to_string()
        },
        lfm_port: 1234,
        zenzai_port: 8090,
    };

    let first_surface = final_suggestions
        .first()
        .map(|s| s.text.clone())
        .unwrap_or_else(|| hiragana_text.to_string());

    // 1. 💡 もしかして (誤字補正)
    if let Some(fix) = ai_assist::fetch_moshikashite_candidate("", hiragana_text, &config) {
        if !fix.is_empty() && !final_suggestions.iter().any(|s| s.text == fix) {
            final_suggestions.push(Suggestion {
                text: format!("{} [💡もしかして]", fix),
                subtext: "💡 もしかして".to_string(),
                corresponding_count: 0,
                candidate_id: 999901,
            });
        }
    }

    // 2. 🔮 先読み (予測補完)
    if let Some(pred) = ai_assist::fetch_predictive_candidate("", &first_surface, &config) {
        if !pred.is_empty() && !final_suggestions.iter().any(|s| s.text == pred) {
            let display_text = format!("{} [🔮先読み]", pred);
            final_suggestions.push(Suggestion {
                text: display_text,
                subtext: "🔮 先読み".to_string(),
                corresponding_count: 0,
                candidate_id: 999902,
            });
        }
    }
}"""

# 2. In append_text, use append_ai_assist_candidates
t2 = """        if AI_ASSIST_ENABLED.load(Ordering::Relaxed) {
            let config = AiAssistConfig {
                enabled: true,
                predictive_model: if PREDICTIVE_MODEL_IS_350M.load(Ordering::Relaxed) {
                    "350m_q4km".to_string()
                } else {
                    "230m_q4km".to_string()
                },
                lfm_port: 1234,
                zenzai_port: 8090,
            };

            let first_surface = final_suggestions.first().map(|s| s.text.clone()).unwrap_or_else(|| hiragana_text.clone());

            // 1. 💡 もしかして (誤字補正)
            if let Some(fix) = ai_assist::fetch_moshikashite_candidate("", &hiragana_text, &config) {
                if !final_suggestions.iter().any(|s| s.text.contains(&fix)) {
                    final_suggestions.push(Suggestion {
                        text: format!("{} [💡もしかして]", fix),
                        subtext: "💡 もしかして".to_string(),
                        corresponding_count: 0,
                        candidate_id: 999901,
                    });
                }
            }

            // 2. 🔮 先読み (予測補完)
            if let Some(pred) = ai_assist::fetch_predictive_candidate("", &first_surface, &config) {
                let display_text = format!("{} [🔮先読み]", pred);
                if !final_suggestions.iter().any(|s| s.text.contains(&pred)) {
                    final_suggestions.push(Suggestion {
                        text: display_text,
                        subtext: "🔮 先読み".to_string(),
                        corresponding_count: 0,
                        candidate_id: 999902,
                    });
                }
            }
        }"""

r2 = """        append_ai_assist_candidates(&hiragana_text, &mut final_suggestions);"""

# 3. In replace_composition, also add append_ai_assist_candidates
t3 = """        Ok(Response::new(ReplaceCompositionResponse {
            composing_text: Some(ComposingText {
                hiragana: composed_text.hiragana.unwrap_or(composing_text.text),
                suggestions: composed_text.suggestions,
            }),
            server_session_id: server_session_id(),
        }))"""

r3 = """        let mut final_suggestions = composed_text.suggestions;
        let hiragana_text = composed_text.hiragana.unwrap_or(composing_text.text);
        append_ai_assist_candidates(&hiragana_text, &mut final_suggestions);

        Ok(Response::new(ReplaceCompositionResponse {
            composing_text: Some(ComposingText {
                hiragana: hiragana_text,
                suggestions: final_suggestions,
            }),
            server_session_id: server_session_id(),
        }))"""

# 4. Fallback in reload_server_logging_from_settings
t4 = """fn reload_server_logging_from_settings() -> Option<LogPaths> {
    let config = AppConfig::read().unwrap_or_default();
    reload_ai_assist_from_settings(&config);
    configure_server_logging(&config)
}"""

r4 = """fn reload_server_logging_from_settings() -> Option<LogPaths> {
    let mut config = AppConfig::read().unwrap_or_default();
    if !config.general.experimental_ai_assist {
        if let Ok(current_exe) = std::env::current_exe() {
            if let Some(parent) = current_exe.parent() {
                let local_settings = parent.join("settings.json");
                if let Ok(content) = std::fs::read_to_string(&local_settings) {
                    if let Ok(local_config) = serde_json::from_str::<AppConfig>(&content) {
                        if local_config.general.experimental_ai_assist {
                            config.general.experimental_ai_assist = true;
                            config.general.experimental_predictive_model = local_config.general.experimental_predictive_model;
                        }
                    }
                }
            }
        }
    }
    reload_ai_assist_from_settings(&config);
    configure_server_logging(&config)
}"""

def replace_block(text, src, dst, name):
    src_norm = src.replace("\r\n", "\n")
    text_norm = text.replace("\r\n", "\n")
    if src_norm in text_norm:
        text_norm = text_norm.replace(src_norm, dst.replace("\r\n", "\n"), 1)
        print(f"Replaced {name}")
        return text_norm
    else:
        print(f"FAILED to find {name}")
        return text

content = replace_block(content, t1, r1, "t1 (append_ai_assist_candidates)")
content = replace_block(content, t2, r2, "t2 (append_text)")
content = replace_block(content, t3, r3, "t3 (replace_composition)")
content = replace_block(content, t4, r4, "t4 (settings fallback)")

with open(path, "w", encoding="utf-8") as f:
    f.write(content)
print("Saved main.rs")
