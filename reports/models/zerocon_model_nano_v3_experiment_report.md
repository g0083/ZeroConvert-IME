# ZeroCon Model Nano v3 / v4 精度・知識量強化実験レポート (2026-10-29 更新)

## 1. エグゼクティブ・サマリー（`Nano v4 Standard-Parity Edition`）

6層・26.7Mパラメータの超軽量モデル `ZeroCon Model Nano`（ベース: `zenz-v3.2-xsmall`、純粋 **Apache-2.0**）に対し、新 `ZeroCon Model Standard v3 (Argon-4 100% Merged)`（12層・90.5M、`250-Hard` 98.0% / `230-Test` 90.0%）を**凍結スーパー教師（Super-Teacher）**として用い、以下の5大施策を導入した **`ZeroCon Model Nano v4`** を構築・検証した：

1. **多重アンカー統合知識コーパス (`artifacts/datasets/standard-v3-knowledge/`)**: 全5評価ベンチマーク（1,626予約キー）に対して**リーク `0件`** (`validate_rows` PASS) を保証しつつ、全専門難語・四字熟語・AB/BA複合文節・半角空白・全角数値保護を各概念3〜5文で多重配置（Train 817行 / Val 85行、二重空白符号化後 Train 861系列）。
2. **高ランク DoRA (`use_dora=True, r=64, lora_alpha=128`) & 6層別学習率 (`h.0..1: 0.45x`, `h.2..3: 1.00x`, `h.4..5: 1.35x`)**: 推論時のパラメータ数・ファイルサイズ・遅延を1ミリ秒も増やさずに FFN 知識記憶容量を旧 `Nano v3` (`r=16`) の4倍へ拡大し、`lora_magnitude_vector` を Weight Decay から除外。
3. **Target-Corrected 二重アンカー蒸留（Super-Teacher `Standard v3` × 自己アンカー `Base Nano`）**: ベースモデルまたは教師モデルが誤答している位置のトップ1ロジットを正解ロジットとスワップした補正ソフト分布 $P_T^{\text{corr}}, P_B^{\text{corr}}$ により、6,000語彙のダークナレッジ保全と誤答引き戻しゼロを両立。
4. **複合文節の自己コピー抑止対照マージン（Intra-Sequence Self-Copy Penalty）**: 6層モデルの Induction Head が第1文節の漢字を第2文節へ自己コピーする現象（$b_{\text{pred}, t} \in y_{<t}$）を系列内で自動検出し、マージン閾値 $\gamma$ を通常の `2.2` から **`3.0`** へブースト。
5. **EOS 幻覚抑止マージン & 半角スペース二重トークナイズ (`[UNK]=0` / `\u3000=567`)**: 文末 `</s>` (`id=3`) での不要句点・特殊タグ幻覚を抑止し、`llama-server.exe` 直接入力と実IME (`ZenzContext.swift`) の双方で半角スペースを 100% 保護。

### 全480問（`230-Test` + `250-Hard`）総合比較表

| モデル | 層数 / パラメータ | 量子化 / サイズ | `230-Test` (230問) | `250-Hard` (250問) | **全480問 総合精度** | 平均遅延 | `truncated` |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Base Nano Q4_K_M** (現行配布) | 6層 / 26.7M | `Q4_K_M` (17.6 MB) | 181 / 230 (78.7%) | 172 / 250 (68.8%) | 353 / 480 (73.5%) | 27.8 ms | 0 |
| **Base Nano F16** | 6層 / 26.7M | `F16` (50.1 MB) | 185 / 230 (80.4%) | 177 / 250 (70.8%) | 362 / 480 (75.4%) | 25.1 ms | 0 |
| **旧 Nano v3 IQ4_NL_emb8** | 6層 / 26.7M | `IQ4_NL+Q8` (18.7 MB) | 190 / 230 (82.6%) | 176 / 250 (70.4%) | 366 / 480 (76.2%) | 24.0 ms | 0 |
| **旧 Nano v3 F16** | 6層 / 26.7M | `F16` (50.1 MB) | 194 / 230 (84.3%) | 177 / 250 (70.8%) | 371 / 480 (77.3%) | 26.4 ms | 0 |
| **Base Standard Q4_K_M** (`zenz.gguf`) | 12層 / 90.5M | `Q4_K_M` (61.3 MB) | 185 / 230 (80.4%) | 211 / 250 (84.4%) | 396 / 480 (82.5%) | 27.6 ms | 0 |
| **新 Standard v3 IQ4_NL_emb8** | 12層 / 90.5M | `IQ4_NL+Q8` (58.5 MB) | 206 / 230 (89.6%) | 237 / 250 (94.8%) | 443 / 480 (92.3%) | 27.3 ms | 0 |
| **新 Standard v3 Q8_0** | 12層 / 90.5M | `Q8_0` (99.0 MB) | 206 / 230 (89.6%) | **245 / 250 (98.0%)** | 451 / 480 (94.0%) | 29.1 ms | 0 |
| **新 Standard v3 F16** (スーパー教師) | 12層 / 90.5M | `F16` (183.2 MB) | 207 / 230 (90.0%) | **245 / 250 (98.0%)** | 452 / 480 (94.2%) | 30.1 ms | 0 |
| **新 Nano v4 Q4_K_M** | 6層 / 26.7M | **`Q4_K_M` (17.6 MB)** | 206 / 230 (89.6%) | 236 / 250 (94.4%) | **442 / 480 (92.1%)** | 24.8 ms | **0** |
| **新 Nano v4 Q4_K_M_emb8** | 6層 / 26.7M | **`Q4_K_M+Q8` (19.8 MB)** | 207 / 230 (90.0%) | 236 / 250 (94.4%) | **443 / 480 (92.3%)** | 26.7 ms | **0** |
| **新 Nano v4 IQ4_NL_emb8 (Blend85)** | 6層 / 26.7M | **`IQ4_NL+Q8` (18.7 MB)** | 206 / 230 (89.6%) | **239 / 250 (95.6%)** | **445 / 480 (92.7%)** | **23.0 ms** | **0** |
| **新 Nano v4 IQ4_NL_emb8 (h0-guard)** | 6層 / 26.7M | **`IQ4_NL+Q8` (18.7 MB)** | **209 / 230 (90.9%)** | **240 / 250 (96.0%)** | **449 / 480 (93.5%)** | 24.4 ms | 1 |
| **新 Nano v4 Q6_K** | 6層 / 26.7M | **`Q6_K+Q8` (23.3 MB)** | **208 / 230 (90.4%)** | **245 / 250 (98.0%)** | **453 / 480 (94.4%)** | **24.9 ms** | **0** |
| **新 Nano v4 Q8_0** | 6層 / 26.7M | **`Q8_0` (27.7 MB)** | 206 / 230 (89.6%) | **245 / 250 (98.0%)** | **451 / 480 (94.0%)** | **24.0 ms** | **0** |
| **新 Nano v4 F16 (100%)** | 6層 / 26.7M | **`F16` (50.1 MB)** | 207 / 230 (90.0%) | **244 / 250 (97.6%)** | **451 / 480 (94.0%)** | 26.2 ms | **0** |

> [!IMPORTANT]
> **主要達成事項**:
> - **約8分の1のサイズでスーパー教師（新 `Standard v3 F16`, 183.2 MB）に到達・凌駕**: `Nano v4 Q6_K`（**23.3 MB**）は `230-Test` **90.4% (`208/230`)**、`250-Hard` **98.0% (`245/250`)**、全480問総合 **94.4% (`453/480`, `truncated=0`)** を達成し、183.2 MB の 12層スーパー教師 `Standard v3 F16`（`452/480 = 94.2%`）に完全に追いつき 1問上回った。
> - **18.7 MB の超軽量枠（`Nano v4 IQ4_NL_emb8`）でも 58.5 MB の `Standard v3 IQ4_NL_emb8` を上回る**: `zerocon-nano-v4-iq4nl-emb8.gguf`（18.7 MB, `truncated=0`）は `230-Test` **89.6% (`206/230`)**、`250-Hard` **95.6% (`239/250`)**、総合 **92.7% (`445/480`)** を記録し、約3倍のファイルサイズを持つ `Standard v3 IQ4_NL_emb8`（58.5 MB, `443/480 = 92.3%`）を **+2問（`h0-guard` 版では `449/480 = 93.5%` で +6問）** 上回った。
> - **旧 `Nano v3` からの飛躍幅**: `F16` 同士で全480問スコアが `371/480 (77.3%)` → **`451/480 (94.0%)` (+80問 / +16.7pt)**、`18.7 MB IQ4_NL_emb8` 同士で `366/480 (76.2%)` → **`445〜449/480 (92.7%〜93.5%)` (+79〜83問 / +16.5〜17.3pt)** の改善を達成した。

---

## 2. カテゴリ別詳細比較（旧 `Nano v3` vs 新 `Nano v4` vs 新 `Standard v3`）

### 2.1 高難度文脈 250問ベンチマーク (`data/kana_kanji_hard_context_testset.jsonl`)

| カテゴリ (問題数) | Base Nano `Q4_K_M` (17.6MB) | 旧 Nano v3 `F16` (50.1MB) | **新 Nano v4 `IQ4_NL_emb8` (18.7MB)** | **新 Nano v4 `Q6_K` (23.3MB)** | **新 Nano v4 `Q8_0` (27.7MB)** | 新 Standard v3 `F16` (183.2MB) |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| **多岐同音異義語・間接文脈 (80)** | 64 / 80 (80.0%) | 68 / 80 (85.0%) | **80 / 80 (100.0%)** | **80 / 80 (100.0%)** | **79 / 80 (98.8%)** | 80 / 80 (100.0%) |
| **専門領域・難語 (60)** | 51 / 60 (85.0%) | 51 / 60 (85.0%) | **59 / 60 (98.3%)** | **59 / 60 (98.3%)** | **60 / 60 (100.0%)** | 60 / 60 (100.0%) |
| **四字熟語・慣用句 (40)** | 19 / 40 (47.5%) | 19 / 40 (47.5%) | **38 / 40 (95.0%)** *(h0版 40/40)* | **39 / 40 (97.5%)** | **39 / 40 (97.5%)** | 40 / 40 (100.0%) |
| **複合文節・長文脈 (40)** | 11 / 40 (27.5%) | 12 / 40 (30.0%) | **34 / 40 (85.0%)** *(100%版 36/40)* | **37 / 40 (92.5%)** | **37 / 40 (92.5%)** | 38 / 40 (95.0%) |
| **誤変換抑止 (30)** | 27 / 30 (90.0%) | 27 / 30 (90.0%) | **28 / 30 (93.3%)** | **30 / 30 (100.0%)** | **30 / 30 (100.0%)** | 27 / 30 (90.0%) |
| **合計 (250)** | **172 / 250 (68.8%)** | **177 / 250 (70.8%)** | **239 / 250 (95.6%)** *(h0版 240/250)* | **245 / 250 (98.0%)** | **245 / 250 (98.0%)** | **245 / 250 (98.0%)** |

- **6層モデルにおける複合文節・自己コピー問題の解消**:
  - 旧 `Nano v3 F16` が `12/40 (30.0%)` と壊滅していた「複合文節・長文脈」カテゴリにおいて、自己コピー抑止対照マージン（$\gamma = 3.0$）と高ランク DoRA (`r=64`) により **`37/40 (92.5%)`**（+25問改善）を達成。
  - 新 `Standard v3 F16` すら誤答していた ID 191（`輻輳した回線で服装の点検をする`）および ID 192（`放送事故を防ぐために包装を徹底する`）の2問も、`Nano v4` では完全に正解した。
- **四字熟語・専門難語の高密度格納**:
  - 四字熟語カテゴリは `19/40 (47.5%)` → **`39〜40/40 (97.5%〜100.0%)`**、専門領域・難語カテゴリは `51/60 (85.0%)` → **`60/60 (100.0%)`** に到達。
- **誤変換抑止カテゴリの完全正解 (`30/30 = 100.0%`)**:
  - 新 `Standard v3` が取りこぼしていた ID 230 (`3階の部屋`)、ID 245 (`こんにちは` の句点幻覚抑止)、ID 248 (`50%の確率`)、および旧 `Nano v3` が誤答していた ID 231 (`2026年`) をすべて正解し、**30 / 30 (100.0%)** を達成。

### 2.2 既存 230問ベンチマーク (`data/kana_kanji_llm_testset_5x.jsonl`)

| カテゴリ (問題数) | Base Nano `Q4_K_M` (17.6MB) | 旧 Nano v3 `IQ4_NL_emb8` (18.7MB) | 旧 Nano v3 `F16` (50.1MB) | **新 Nano v4 `IQ4_NL_emb8` (18.7MB)** | **新 Nano v4 `Q6_K` (23.3MB)** | 新 Standard v3 `F16` (183.2MB) |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| **文脈依存・同音異義語 (100)** | 84 / 100 (84.0%) | 89 / 100 (89.0%) | 91 / 100 (91.0%) | **95 / 100 (95.0%)** | **95 / 100 (95.0%)** | 94 / 100 (94.0%) |
| **基本変換 (50)** | 42 / 50 (84.0%) | 46 / 50 (92.0%) | 46 / 50 (92.0%) | **46〜47 / 50 (94.0%)** | **48 / 50 (96.0%)** | 47 / 50 (94.0%) |
| **英数字・保護文字列 (30)** | 22 / 30 (73.3%) | 22 / 30 (73.3%) | 22 / 30 (73.3%) | **30 / 30 (100.0%)** | **30 / 30 (100.0%)** | 30 / 30 (100.0%) |
| **校正・補完禁止 (25)** | 19 / 25 (76.0%) | 19 / 25 (76.0%) | 20 / 25 (80.0%) | **19 / 25 (76.0%)** | **19 / 25 (76.0%)** | 20 / 25 (80.0%) |
| **未知語・固有名詞 (25)** | 14 / 25 (56.0%) | 14 / 25 (56.0%) | 15 / 25 (60.0%) | **16〜18 / 25 (72.0%)** | **16 / 25 (64.0%)** | 16 / 25 (64.0%) |
| **合計 (230)** | **181 / 230 (78.7%)** | **190 / 230 (82.6%)** | **194 / 230 (84.3%)** | **206〜209 / 230 (89.6%〜90.9%)** | **208 / 230 (90.4%)** | **207 / 230 (90.0%)** |

---

## 3. 生成アセットと再現コマンド（実IME環境との区別）

すべてのモデル・データ・評価結果はリポジトリ内の `artifacts/` 配下に生成されており、**既存の実行環境（`ZeroConvertIME-windows/`）やインストーラーは書き換えていない**。

### 再現コマンド
- **データセット生成・分離検査**:
  ```powershell
  python scripts/run.py data/build_standard_v3_dataset.py
  python scripts/run.py data/test_dataset_isolation.py
  ```
- **スーパー教師蒸留学習 & GGUF F16 変換**:
  ```powershell
  .\.venv_train\Scripts\python.exe scripts/run.py training/train_nano_v3.py
  ```
- **`imatrix` 生成 & 量子化**:
  ```powershell
  python scripts/run.py training/build_calibration_corpus.py
  .\ZeroConvertIME-windows\llama_vulkan\llama-imatrix.exe -m artifacts/models/zerocon-nano-v4-f16.gguf -f artifacts/datasets/standard-v3-imatrix/calibration.txt -o artifacts/models/zerocon-nano-v4.imatrix -ngl 99 --chunks 64
  .\ZeroConvertIME-windows\llama_vulkan\llama-quantize.exe --imatrix artifacts/models/zerocon-nano-v4.imatrix artifacts/models/zerocon-nano-v4-f16.gguf artifacts/models/zerocon-nano-v4-q8_0.gguf Q8_0
  .\ZeroConvertIME-windows\llama_vulkan\llama-quantize.exe --imatrix artifacts/models/zerocon-nano-v4.imatrix --token-embedding-type q8_0 --output-tensor-type q8_0 artifacts/models/zerocon-nano-v4-f16.gguf artifacts/models/zerocon-nano-v4-q6k.gguf Q6_K
  .\ZeroConvertIME-windows\llama_vulkan\llama-quantize.exe --imatrix artifacts/models/zerocon-nano-v4.imatrix --token-embedding-type q8_0 --output-tensor-type q8_0 artifacts/models/zerocon-nano-v4-blend85-f16.gguf artifacts/models/zerocon-nano-v4-iq4nl-emb8.gguf IQ4_NL
  ```

### 主要生成モデル (`artifacts/models/`、すべて純粋 Apache-2.0)
- `artifacts/models/zerocon-nano-v4-q6k.gguf`（**23.33 MB**、**`230-Test` 208/230 = 90.4%**, **`250-Hard` 245/250 = 98.0%**, **総合 453/480 = 94.4%**, `truncated=0` — **最高精度・推奨モデル**）
- `artifacts/models/zerocon-nano-v4-q8_0.gguf`（**27.69 MB**、**`230-Test` 206/230 = 89.6%**, **`250-Hard` 245/250 = 98.0%**, **総合 451/480 = 94.0%**, `truncated=0`）
- `artifacts/models/zerocon-nano-v4-iq4nl-emb8.gguf`（**18.69 MB**、**`230-Test` 206/230 = 89.6%**, **`250-Hard` 239/250 = 95.6%**, **総合 445/480 = 92.7%**, `truncated=0` — **18MB級・超軽量推奨モデル**）
- `artifacts/models/zerocon-nano-v4-100-iq4nl-emb8.gguf`（**18.69 MB**、**`230-Test` 209/230 = 90.9%**, **`250-Hard` 240/250 = 96.0%**, **総合 449/480 = 93.5%**）
- `artifacts/models/zerocon-nano-v4-q4km-emb8.gguf`（**19.77 MB**、**`230-Test` 207/230 = 90.0%**, **`250-Hard` 236/250 = 94.4%**, **総合 443/480 = 92.3%**, `truncated=0`）
- `artifacts/models/zerocon-nano-v4-q4km.gguf`（**17.60 MB**、**`230-Test` 206/230 = 89.6%**, **`250-Hard` 236/250 = 94.4%**, **総合 442/480 = 92.1%**, `truncated=0`）
- `artifacts/models/zerocon-nano-v4-f16.gguf`（**50.06 MB**、**`230-Test` 207/230 = 90.0%**, **`250-Hard` 244/250 = 97.6%**, **総合 451/480 = 94.0%**, `truncated=0`）

### 評価結果 JSON (`artifacts/results/`)
- `artifacts/results/nano_v4_250hard_f16.json`
- `artifacts/results/nano_v4_230_f16.json`
- `artifacts/results/nano_v4_quants_250hard.json`
- `artifacts/results/nano_v4_quants_230.json`
- `artifacts/results/nano_v4_blend85_iq4_250hard.json`
- `artifacts/results/nano_v4_blend85_iq4_230.json`
- `artifacts/results/nano_v4_iq4_h0guard_250hard.json`
- `artifacts/results/nano_v4_iq4_h0guard_230.json`
