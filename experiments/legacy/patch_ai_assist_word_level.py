# -*- coding: utf-8 -*-
"""
scratch/batao_src/crates/server/src/ai_assist/mod.rs に
単語・文節単位ウィンドウ最適化 ＆ ニューラル特化モデル(xsmall)フォールバックを追加
"""

path = r"scratch\batao_src\crates\server\src\ai_assist\mod.rs"
with open(path, "r", encoding="utf-8") as f:
    code = f.read()

# 1. ステップ 7 の後にステップ 8 (ニューラルモデル フォールバック) を追加
target_step7 = """    // 7. QWERTY 隣接キー変種 + 軽量辞書インデックス (Phase 3)
    if let Some(correction) = find_qwerty_correction(hiragana) {
        return Some(TypoSuggestion {
            surface: correction.surface,
            token_reading: correction.token_reading,
            token_surface: correction.token_surface,
            learned: false,
        });
    }

    None"""

replacement_step8 = """    // 7. QWERTY 隣接キー変種 + 軽量辞書インデックス (Phase 3)
    if let Some(correction) = find_qwerty_correction(hiragana) {
        return Some(TypoSuggestion {
            surface: correction.surface,
            token_reading: correction.token_reading,
            token_surface: correction.token_surface,
            learned: false,
        });
    }

    // 8. 単語・文節単位 ニューラル特化モデル (xsmall) フォールバック
    // 辞書やルールで解決できなかった未知のタイポを、左文脈 + 単語ウィンドウで高精度に補正する
    if config.predictive_model == "zerocon" || config.predictive_model == "typo_xsmall" {
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
            format!("\\u{EE02}{effective_ctx}\\u{EE00}{target_katakana}\\u{EE01}")
        } else {
            format!("\\u{EE00}{target_katakana}\\u{EE01}")
        };

        let payload = json!({
            "prompt": prompt,
            "n_predict": 8,
            "temperature": 0.0,
            "stop": ["</s>", "<s>", "\\n", "\\r", "\\u{EE00}", "\\u{EE01}", "\\u{EE02}"]
        });

        // zenzai_port (または補正モデルポート) へ超低遅延で問い合わせ
        if let Some(res) = simple_http_post(config.zenzai_port, "/completion", &payload, 45) {
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

    None"""

if target_step7 in code:
    code = code.replace(target_step7, replacement_step8, 1)
    with open(path, "w", encoding="utf-8") as f:
        f.write(code)
    print("Successfully patched mod.rs with word-level neural typo fallback!")
else:
    print("Target step 7 not found in mod.rs")
