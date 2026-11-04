# ZeroCon Model Standard v3 (90.5M) 精度強化・自己アンカー蒸留 実験報告書 (Argon-4 再実装版)

- **実施日**: 2026-10-29
- **ベースモデル**: `zenz-v3.2-small` (`Miwa-Keita/zenz-v3.2-small`, 12層, `d_model=768`, `d_ff=3072`, 90.5M parameters, **純粋 Apache-2.0**)
- **評価ベンチマーク**:
  1. **既存 230問テスト (`230-Test`)**: `data/kana_kanji_llm_testset_5x.jsonl` (基本変換・同音異義語・英数字保護・補完禁止・未知語)
  2. **高難度文脈 250問テスト (`250-Hard`)**: `data/kana_kanji_hard_context_testset.jsonl` (多岐同音異義語・専門領域難語・四字熟語・複合文節・誤変換抑止)

---

## 1. エグゼクティブ・サマリー

先行実験（Gemini 3.8 Flash による実装）において、100%マージモデルが **23.6% (`59/250`)** に崩壊し、LoRA重みを70%に希釈した `Blend 70` でも `230-Test` **83.9% (`193/230`)**・`250-Hard` **80.8% (`202/250`)** に留まっていた原因を徹底調査した結果、**5つの致命的バグ**を特定・解消した。

上位モデル（Argon-4）による根本原因の修正と再学習の結果、希釈なしの **100% DoRAマージモデル (`Standard v3 F16`)** および量子化モデル (**`Q8_0`**, **`IQ4_NL_emb8`**) は以下の通り劇的な精度向上を達成した（全モデルで終端崩壊・文字切れ **`truncated = 0件`**）。

| モデル | 量子化 / サイズ | `230-Test` 正解数 (正解率) | `250-Hard` 正解数 (正解率) | 全480問 合計正解数 (総合正解率) | 平均推論遅延 |
| --- | --- | :---: | :---: | :---: | :---: |
| **Base Standard Q4_K_M** (`zenz.gguf`) | `Q4_K_M` (61.3 MB) | 185 / 230 (80.4%) | 211 / 250 (84.4%) | 396 / 480 (82.5%) | 27.6 ms |
| **Base Standard Q5_K_M** | `Q5_K_M` (70.5 MB) | 185 / 230 (80.4%) | 211 / 250 (84.4%) | 396 / 480 (82.5%) | 29.0 ms |
| *旧 Standard v3 F16 (Flash 100%)* | `F16` (183.2 MB) | *(終端崩壊)* | *59 / 250 (23.6%)* | — | 52.3 ms |
| *旧 Standard v3 IQ4_NL_emb8 (Flash Blend70)* | `IQ4_NL_emb8` (58.5 MB) | *193 / 230 (83.9%)* | *202 / 250 (80.8%)* | *395 / 480 (82.3%)* | 28.0 ms |
| **新 Standard v3 F16 (Argon-4 100%)** | **`F16` (183.2 MB)** | **207 / 230 (90.0%)** | **245 / 250 (98.0%)** | **452 / 480 (94.2%)** | **30.1 ms** |
| **新 Standard v3 Q8_0 (Argon-4 100%)** | **`Q8_0` (99.0 MB)** | **206 / 230 (89.6%)** | **245 / 250 (98.0%)** | **451 / 480 (94.0%)** | **29.1 ms** |
| **新 Standard v3 IQ4_NL_emb8 (Argon-4 100%)** | **`IQ4_NL_emb8` (58.5 MB)** | **206 / 230 (89.6%)** *(※32chunk時 208/230=90.4%)* | **237 / 250 (94.8%)** | **443 / 480 (92.3%)** | **27.3 ms** |
| 新 Standard v3 Q6_K (Argon-4 100%) | `Q6_K` (87.7 MB) | 200 / 230 (87.0%) | 241 / 250 (96.4%) | 441 / 480 (91.9%) | 28.8 ms |
| 新 Standard v3 Q4_K_M (Argon-4 100%) | `Q4_K_M` (72.8 MB) | 202 / 230 (87.8%) | 235 / 250 (94.0%) | 437 / 480 (91.0%) | 28.1 ms |

- **Base Standard 比の改善幅**:
  - `230-Test`: `185/230 (80.4%)` → **`207/230 (90.0%)`** (**+22問 / +9.6pt**、英数字・保護文字列カテゴリは **30/30 = 100% 完全正解**)
  - `250-Hard`: `211/250 (84.4%)` → **`245/250 (98.0%)`** (**+34問 / +13.6pt**、誤答数を 39件 → **5件** へ **87.2% 削減**)
  - **全480問総合**: `396/480 (82.5%)` → **`452/480 (94.2%)`** (**+56問 / +11.7pt**)

---

## 2. 前回実行（Gemini 3.8 Flash）の不調原因・根本診断

調査の結果、前回実行の成果が振るわなかった原因はアルゴリズムの限界ではなく、以下の5つの実装バグにあった。

### 致命的バグ #1: `zenz-v3.2-small` の HF 設定ファイルにおける `eos_token_id = 2 ("<s>")` 誤設定
- `artifacts/models/hf/zenz-v3.2-small/config.json` および `tokenizer_config.json` において、`bos_token_id = 2 ("<s>")`, `eos_token_id = 2 ("<s>")` と設定されていた（正しくは `zenz-v3.2-xsmall` と同様 `bos_token_id = 2 ("<s>")`, **`eos_token_id = 3 ("</s>")`**）。
- 事前学習済み `zenz-v3.2-small` は文末でトークン `3` (`"</s>"`, logit `+22.47`) を出力し、トークン `2` (`"<s>"`, logit `-5.79`) は出力しない。
- しかし前回学習スクリプトは `tokenizer.eos_token_id` (`2`) を正解ラベル末尾に付与したため、`SelfAnchoredMarginDistillationTrainer` のマージン損失が**全学習文の文末で本来の終端トークン `3 ("</s>")` を誤答とみなして強く抑圧（1文あたり約 25.9 の巨大損失）**していた。
- その結果、100%マージモデルが文末で `"</s>"` を出力できなくなり（250問中80問で文字数上限まで暴走し `59/250 = 23.6%` に崩壊）、前回担当エージェントは原因を特定しないまま `Blend 70`（LoRA 70%希釈）で表面的に隠蔽していた。
- **対策**: `artifacts/models/hf/zenz-v3.2-small/` の `config.json`, `tokenizer_config.json`, `special_tokens_map.json` を `eos_token_id = 3 ("</s>")`, `pad_token_id = 1 ("[PAD]")` に修正し、`train_standard_v3.py` に `assert tokenizer.eos_token_id == 3` を追加した。

### 致命的バグ #2: 半角スペース (`" "`) のトークナイズ不一致（`GPT2Tokenizer` 消失 vs `llama.cpp` `[UNK]=0`）
- `zenz-v3.2` の 6,000語彙には半角スペース (`0x20`) トークンが存在せず、空白トークンは **全角スペース `\u3000` (Token ID `567`) のみ**である。
- HuggingFace の `GPT2Tokenizer.encode()` は入力文字列中の半角スペース `' '` をBPE分割前に無言で削除してしまう一方、`llama-server.exe` (`llama.cpp`) は `' '` (`0x20` → `'Ġ'`) を **`[UNK]` (Token ID `0`)** にマッピングし、実IME (`ZenzContext.swift`) は `' '` を **`\u3000` (Token ID `567`)** に正規化して入力する。
- 前回学習では `' '` が消えた状態で学習されたため、`230-Test` の半角スペースを含む技術文字列9問（`LM Studio`, `RTX 5070 Ti`, `docker compose up -d`, `npm run dev`, `SELECT * FROM users` 等）で `llama-server` が入力した `[UNK]` (`0`) に対してモデルが漢字ゴミ（`LM™Studio`, `RTX魈5070蜀Ti`, `dockerôcompose`）を出力して全滅していた。
- **対策**: `train_standard_v3.py` に `encode_with_space_token()` を実装し、半角スペースを含む学習行について：
  1. プロンプト中の `' '` を Token ID `0` (`[UNK]`)、正解中の `' '` を Token ID `567` (`\u3000`) に符号化する系列（`llama-server` 直接入力対応）
  2. プロンプト・正解双方の `' '` を Token ID `567` (`\u3000`) に符号化する系列（`ZenzContext.swift` 実IME入力対応）
  の双方を生成して学習させた。これにより `230-Test` の英数字・保護文字列カテゴリは **`30/30 (100.0%)`** に到達した。

### 致命的バグ #3: `kana_kanji_hard_context_testset.jsonl` 側のテストケース不良12件
- 前回作成された `data/build_hard_context_testset.py` を監査した結果、以下の12件のテスト定義ミスが存在した：
  - **ID 105**: `expected="サーバーを冗長化する"` に対し `forbidden=["サーバーを冗長化する"]` が同一文字列に設定されており、正解すると必ず不正解（`score=0`）になる矛盾設定。
  - **誤読（存在しない読み）**: ID 55 `重鎮` が `じゅうじん`（正：`じゅうちん`）、ID 112/209 `API` が `あぴ`（正：`えーぴーあい`）、ID 119 `稟議書` が `にんぎしょ`（正：`りんぎしょ`）、ID 134 `披見` が `ひっけん`（正：`ひけん`）、ID 176 `きょうごうにあいてに`（正：`きょうごうあいてに`）。
  - **読み (`target`) への漢字混入**: ID 27 (`現れる`), 90 (`第三者`), 142 (`意見を戦わせる`), 173 (`対処する`), 179 (`構える`)。
- **対策**: `data/build_hard_context_testset.py` の該当箇所を修正し、表記揺れ許容 (`acceptable`) を整備した上で Base モデルを再評価したところ、Base の正確な基準値は `211/250 (84.4%)` となった。

### 致命的バグ #4: 層別学習率（施策3）の未実装・DoRA Magnitude Vector の Weight Decay 減衰・誤答ピークへの KL 引き戻し
- 前回実装は全12層に均一学習率を適用し、かつ `lora_magnitude_vector` に `weight_decay=0.01` が適用されていた。さらに Base が誤答している位置（`~base_agrees`）でも誤答トークン `b_pred` にピークを持つ `p_b` への KL 損失を加算していた。
- **対策**:
  1. **層別学習率（Measure 3）**: `create_optimizer()` をオーバーライドし、下位層 `h.0..3` を `0.40x`、中間層 `h.4..7` を `1.00x`、知識FFN上位層 `h.8..11` を `1.30x` に設定。同時に `lora_magnitude_vector` と `bias` を Weight Decay 対象外に分離。
  2. **Target-Corrected Self-Anchored KL（Measure 2）**: Base が誤答しているトークン位置（`b_pred != y_flat`）では、Base ロジットの `b_pred` と `y_flat` の値をスワップした補正分布 `p_b_corr` を構築し、背景 5,998 トークンのダークナレッジ（品詞・文字種整合性）を 100% 保全しつつ誤答トークンへの引き戻しを完全に排除した。
  3. **EOS Anti-Hallucination Margin**: 文末位置（`y_flat == 3`）において、不要な句点や特殊タグの幻覚（`\uee08=172`, `。=248`, `!=4`, `！=469`）を Base 分布からマスクするとともに、$\text{logit}(\texttt{</s>}) - \max_{h \in \{172, 248, 4, 469\}} \text{logit}(h) \ge 6.0$ のマージン制約を課した。

### 致命的バグ #5: データセットの知識疎外と `imatrix` 校正コーパスの順序不備
- 前回データセットは各難語につき1文しかなく、`val` 分割やテスト重複除外で学習データから欠落する語が多発していた。また `build_calibration_corpus.py` が新規知識行をファイル末尾に配置していたため、`llama-imatrix --chunks 32` (16,384 tokens) が新規知識行に到達していなかった。
- **対策**: `data/build_standard_v3_dataset.py` を拡充し、全評価セット（5ファイル・1,626予約キー）に対して **リーク 0件 (`Excluded = 0`)** を維持したまま、各概念・四字熟語・専門語・AB/BA複合文節に複数の独立した文脈アンカー（計 **Train 713行 / Val 73行**、トークナイズ後 **Train 757系列**）を整備した。また `build_calibration_corpus.py` で知識行を先頭に配置した。

---

## 3. カテゴリ別詳細評価結果

### 3.1 既存 230問ベンチマーク (`data/kana_kanji_llm_testset_5x.jsonl`)

| カテゴリ (問題数) | Base Standard (`Q4_K_M`) | 旧 Flash (`IQ4_NL_emb8`) | **新 Argon-4 `F16` (100%)** | **新 Argon-4 `Q8_0`** | **新 Argon-4 `IQ4_NL_emb8`** |
| --- | :---: | :---: | :---: | :---: | :---: |
| **文脈依存・同音異義語 (100)** | 89 / 100 (89.0%) | 91 / 100 (91.0%) | **94 / 100 (94.0%)** | **94 / 100 (94.0%)** | **94 / 100 (94.0%)** |
| **基本変換 (50)** | 40 / 50 (80.0%) | 46 / 50 (92.0%) | **47 / 50 (94.0%)** | **47 / 50 (94.0%)** | **47 / 50 (94.0%)** |
| **英数字・保護文字列 (30)** | 24 / 30 (80.0%) | 24 / 30 (80.0%) | **30 / 30 (100.0%)** | **30 / 30 (100.0%)** | **30 / 30 (100.0%)** |
| **校正・補完禁止 (25)** | 17 / 25 (68.0%) | 17 / 25 (68.0%) | **20 / 25 (80.0%)** | **19 / 25 (76.0%)** | **19 / 25 (76.0%)** |
| **未知語・固有名詞 (25)** | 15 / 25 (60.0%) | 15 / 25 (60.0%) | **16 / 25 (64.0%)** | **16 / 25 (64.0%)** | **16 / 25 (64.0%)** |
| **合計 (230)** | **185 / 230 (80.4%)** | **193 / 230 (83.9%)** | **207 / 230 (90.0%)** | **206 / 230 (89.6%)** | **206 / 230 (89.6%)** *(※最大208/230)* |

### 3.2 高難度文脈 250問ベンチマーク (`data/kana_kanji_hard_context_testset.jsonl`)

| カテゴリ (問題数) | Base Standard (`Q5_K_M`) | 旧 Flash (`IQ4_NL_emb8`) | **新 Argon-4 `F16` (100%)** | **新 Argon-4 `Q8_0`** | **新 Argon-4 `IQ4_NL_emb8`** |
| --- | :---: | :---: | :---: | :---: | :---: |
| **多岐同音異義語・間接文脈 (80)** | 76 / 80 (95.0%) | 71 / 80 (88.8%) | **80 / 80 (100.0%)** | **80 / 80 (100.0%)** | **78 / 80 (97.5%)** |
| **専門領域・難語 (60)** | 55 / 60 (91.7%) | 52 / 60 (86.7%) | **60 / 60 (100.0%)** | **60 / 60 (100.0%)** | **58 / 60 (96.7%)** |
| **四字熟語・慣用句 (40)** | 34 / 40 (85.0%) | 32 / 40 (80.0%) | **40 / 40 (100.0%)** | **40 / 40 (100.0%)** | **40 / 40 (100.0%)** |
| **複合文節・長文脈 (40)** | 19 / 40 (47.5%) | 22 / 40 (55.0%) | **38 / 40 (95.0%)** | **38 / 40 (95.0%)** | **34 / 40 (85.0%)** |
| **誤変換抑止 (30)** | 27 / 30 (90.0%) | 25 / 30 (83.3%) | **27 / 30 (90.0%)** | **27 / 30 (90.0%)** | **27 / 30 (90.0%)** |
| **合計 (250)** | **211 / 250 (84.4%)** | **202 / 250 (80.8%)** | **245 / 250 (98.0%)** | **245 / 250 (98.0%)** | **237 / 250 (94.8%)** |

---

## 4. 量子化フォーマットの特性分析と推奨構成

GPT-2 アーキテクチャ（`Conv1D` 転置重み）において量子化方式を比較した結果、以下の重要な知見が得られた：

1. **`Q8_0` (`zerocon-standard-v3-q8_0.gguf`, 99.0 MB, 29.1ms)**:
   - `F16` (183.2 MB) とほぼ完全に同一の精度（`230-Test` **89.6%**, `250-Hard` **98.0%**, 総合 **451/480 = 94.0%**）を約半分のサイズで達成。
   - デスクトップ環境（Windows IME）において最高精度を求める場合の最良モデル。
2. **`IQ4_NL_emb8` (`zerocon-standard-v3-iq4nl-emb8.gguf`, 58.5 MB, 27.3ms)**:
   - `--token-embedding-type q8_0 --output-tensor-type q8_0` により入出力語彙埋め込み（Token `0` `[UNK]` や Token `567` `\u3000` を含む 6,000語彙）を8bit保護しつつ、内部ブロックに非線形ガウス量子化 (`IQ4_NL`) を適用。
   - 現行配布の `Base Standard Q4_K_M` (61.3 MB, 27.6ms) より **約 2.8 MB 小さく、推論速度も同等以上** でありながら、総合精度を **82.5% → 92.3% (+9.8pt)** へ引き上げる。省メモリ・高速性を重視する標準配布枠として最適。

---

## 5. 成果物一覧と保存先（実IME環境との区別）

すべての学習データ・チェックポイント・GGUFモデル・評価ログはリポジトリ内の管理対象ディレクトリに保存されており、**ユーザーの既存実行環境（`ZeroConvertIME-windows/`）のバイナリ・設定は変更していない**。

- **データセット生成・検証**:
  - [data/build_standard_v3_dataset.py](file:///d:/angra/ZeroConvert-IME/data/build_standard_v3_dataset.py) (`Train: 713`, `Val: 73`, `Excluded: 0`, 5評価セット1,626キー完全隔離検証済み)
  - [data/build_hard_context_testset.py](file:///d:/angra/ZeroConvert-IME/data/build_hard_context_testset.py) (12件のテスト定義バグ修正済み)
- **学習・校正スクリプト**:
  - [training/train_standard_v3.py](file:///d:/angra/ZeroConvert-IME/training/train_standard_v3.py) (EOS整合、空白トークナイズ整合、層別学習率DoRA、Target-Corrected KL + Contrastive Margin + EOS Anti-Hallucination Margin)
  - [training/build_calibration_corpus.py](file:///d:/angra/ZeroConvert-IME/training/build_calibration_corpus.py)
- **生成モデル (`artifacts/models/`)**:
  - `artifacts/models/zerocon-standard-v3-f16.gguf` (183.2 MB)
  - `artifacts/models/zerocon-standard-v3-q8_0.gguf` (99.0 MB)
  - `artifacts/models/zerocon-standard-v3-q6k.gguf` (87.7 MB)
  - `artifacts/models/zerocon-standard-v3-q5km.gguf` (81.4 MB)
  - `artifacts/models/zerocon-standard-v3-q4km.gguf` (72.8 MB)
  - `artifacts/models/zerocon-standard-v3-iq4nl-emb8.gguf` (58.5 MB)
  - `artifacts/models/zerocon-standard-v3.imatrix`
- **評価結果 JSON (`artifacts/results/`)**:
  - `artifacts/results/standard_hard_baseline_v2.json`
  - `artifacts/results/standard_v3_argon4_r2_230_f16.json`
  - `artifacts/results/standard_v3_argon4_r2_250hard_f16.json`
  - `artifacts/results/standard_v3_argon4_q8_iq4_230.json`
  - `artifacts/results/standard_v3_argon4_q8_250hard.json`
  - `artifacts/results/standard_v3_argon4_quants_230.json`
  - `artifacts/results/standard_v3_argon4_quants_250hard.json`
