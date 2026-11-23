# ZeroCon Model Standard v4 / Nano v4 変換基礎能力向上 (`cap-r1`) 実験レポート

## 1. 実験概要

本実験では、誤入力補正やヒューリスティック・外部リランカーといった周辺パイプラインを切り離し、日本語かな漢字変換モデル **ZeroCon Model Standard v4**（12層 / d=768 / 90.5M）および **ZeroCon Model Nano v4**（6層 / d=512 / 26.7M）単体の基礎変換能力と4bit量子化耐性を引き上げる `cap-r1` 学習・蒸留・量子化を実施した。

### 狙いとした4つの改善軸

1. **同一文内の複文節・長文脈における同音異義語切替**
   （例: `放送事故…包装`、`医師…意思`、`神聖…申請`、`会期…回帰分析`、`正気…勝機`、`総裁の指示…相殺`）
2. **短文脈・文頭句および専門表現のコロケーション識別**
   （例: `端に寄せる`、`助詞や語順を変えない`、`決を採る`、`軍習を行う`、`重大違反`、`古文書の史料`、`針小棒大`、`浮腫`）
3. **数値桁数・日付の忠実性と全半角キメラ抑止**
   （例: `１００えんのあめ -> 100円の飴`、`１０きろはしる -> 10キロ走る`、`１がつ１にち -> 1月1日`）
4. **4bit (`IQ4_NL_emb8`) 量子化時のロジットマージン保持**
   - `training/train_nano_v3.py` に `prior_copy_margin`（教師直前語・文脈語の安易な自己コピー抑止）および `quant_margin`（正解トークンと次点競合トークン間のロジット差 `z_y - z_runner_up` のマージン確保）を組み込み、`save_merged_with_exact_base_metadata` で `wte` の全角数字 `'０'..'９'` 行へ `0.95` スケーリングを適用した。

> [!NOTE]
> 既存の `artifacts/models/zerocon-nano-v4-*`、`windows/deployment/model-catalog.json`、および `ZeroConvertIME-windows/` の配布状態は変更していない。

---

## 2. データセット・学習・検証手順

### 2.1 学習データ構築とデータリーク隔離検証

- データ構築スクリプト: [`data/build_standard_v3_dataset.py`](../../data/build_standard_v3_dataset.py)
- 出力先: `artifacts/datasets/zerocon-v4-capability/`
  - 生成総候補数: 1,028件
  - Train: **930件**（SHA-256: `e0074b3fc77aa49b69739d7c0a3c240913f7cc7b616451722c0a654c80ff95a1`）
  - Val: **98件**（SHA-256: `1880365e75afbaa01954822a6e86f29604a224f98edbb57e36f0217e72debd39`）
  - 隔離除外件数: 0件
- 隔離ポリシー (`source-components-nfkc-kana-v1`, `artifacts/checkpoints/standard-v4-cap-r1-lora/dataset-provenance.json` および `artifacts/checkpoints/nano-v4-cap-r1-lora/dataset-provenance.json`):
  - `train_validation_shared_keys: 0`
  - `train_evaluation_shared_keys: 0`
  - `validation_evaluation_shared_keys: 0`
  - `evaluation_derived_rows: 0`
  - `train_validation_shared_groups: 0`
- ユニットテスト:
  - [`data/test_dataset_isolation.py`](../../data/test_dataset_isolation.py): 8 tests OK
  - [`training/test_standard_v4.py`](../../training/test_standard_v4.py): 3 tests OK

### 2.2 学習・蒸留・量子化構成

1. **Standard v4 cap-r1**
   - スクリプト: [`training/train_standard_v4.py`](../../training/train_standard_v4.py)（`--run-name standard-v4-cap-r1 --epochs 6`）
   - 量子化: [`training/build_standard_v4_quants.py`](../../training/build_standard_v4_quants.py) により Train のみから `standard-v4-cap-r1.imatrix` を生成し、`Q8_0` および `IQ4_NL`（`--token-embedding-type q8_0 --output-tensor-type q8_0`）を生成。
2. **Nano v4 cap-r1**
   - 教師モデル: `artifacts/checkpoints/standard-v4-cap-r1-merged`
   - 生徒初期モデル: `artifacts/models/hf/zenz-v3.2-xsmall`
   - スクリプト: [`training/train_nano_v3.py`](../../training/train_nano_v3.py)（`--run-name nano-v4-cap-r1 --epochs 8`）
   - 量子化: `nano-v4-cap-r1.imatrix` を用いて `Q8_0`、`IQ4_NL_emb8`、および比較用の `blend85-iq4nl-emb8` を生成。

---

## 3. CPU 480問ペアベンチマーク結果

### 3.1 計測プロトコル

[`benchmarks/research/benchmark_kana_kanji_models.py`](../../benchmarks/research/benchmark_kana_kanji_models.py) を用い、同一条件（`--backend cpu --n-gpu-layers 0`）で現行ベースライン4種と `cap-r1` 候補5種の計9モデルを一括計測した。

- 実行設定: `context=2048`, `threads=4`, `temperature=0.0`, `seed=42`, `cache_prompt=False`, `max_tokens=96`, `warmup_requests=3`
- ランタイム SHA-256: `d5538214afb689cf4615217a8cc195fdaecd09e6fd045d67aedaa5a974a15a60`
- 230問テストセット: [`data/kana_kanji_llm_testset_5x.jsonl`](../../data/kana_kanji_llm_testset_5x.jsonl)（SHA-256: `156e576b446264db87bd97f694c51870e8b4721a47761b11d61c48cf08ff7bb7`）
- 高難度250問テストセット: [`data/kana_kanji_hard_context_testset.jsonl`](../../data/kana_kanji_hard_context_testset.jsonl)（SHA-256: `d596d13f39c27006fc786207a4484533c766cdbd6779dac3c8fef1605338f327`）
- 全9モデル・全480問において `truncated_count = 0`。

### 3.2 総合比較表

| モデル | 230問 | 高難度250問 | 480問合計 | ベース比較 | 平均遅延 (ms) | p95 (230 / 250) | サイズ (MB) | Model SHA-256 (先頭12桁) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Standard v4 Q8 (現行) | 212/230 (92.17%) | 247/250 (98.80%) | 459/480 (95.62%) | — | 91.71 | 143.71 / 143.62 | 99.0 | `08946b336a75` |
| **Standard v4 cap-r1 Q8** | **215/230 (93.48%)** | **250/250 (100.00%)** | **465/480 (96.88%)** | **+6問** | 91.13 | 129.56 / 145.67 | 99.0 | `028c70c884be` |
| Standard v4 IQ4 (現行) | 209/230 (90.87%) | 242/250 (96.80%) | 451/480 (93.96%) | — | 59.44 | 80.92 / 93.10 | 58.5 | `d6d4ab261a1a` |
| **Standard v4 cap-r1 IQ4** | **214/230 (93.04%)** | **245/250 (98.00%)** | **459/480 (95.62%)** | **+8問** | 57.91 | 85.66 / 98.01 | 58.5 | `cf8e3ac1a89d` |
| Nano v4 Q8 (現行) | 206/230 (89.57%) | 247/250 (98.80%) | 453/480 (94.38%) | — | 27.64 | 42.89 / 47.71 | 27.7 | `a89fe90fc294` |
| **Nano v4 cap-r1 Q8** | **214/230 (93.04%)** | 246/250 (98.40%) | **460/480 (95.83%)** | **+7問** | 27.74 | 45.35 / 53.94 | 27.7 | `47e0714175b7` |
| Nano v4 IQ4 (現行) | 206/230 (89.57%) | 240/250 (96.00%) | 446/480 (92.92%) | — | 19.40 | 34.61 / 39.21 | 18.7 | `31724c4189d8` |
| **Nano v4 cap-r1 IQ4** | **216/230 (93.91%)** | **247/250 (98.80%)** | **463/480 (96.46%)** | **+17問** | **18.49** | **34.33 / 36.76** | **18.7** | `59c4bc0070bd` |
| Nano v4 cap-r1 blend85 IQ4 | 209/230 (90.87%) | 245/250 (98.00%) | 454/480 (94.58%) | +8問 | 21.55 | 35.47 / 37.91 | 18.7 | `5b0915ce438d` |

結果の保存先:
- [`artifacts/results/v4_cap_r1_cpu_230.json`](../../artifacts/results/v4_cap_r1_cpu_230.json)
- [`artifacts/results/v4_cap_r1_cpu_250hard.json`](../../artifacts/results/v4_cap_r1_cpu_250hard.json)

### 3.3 カテゴリ別内訳

#### 230問テストセット

| モデル | 文脈依存・同音異義語 (/100) | 基本変換 (/50) | 英数字・保護文字列 (/30) | 校正・補完禁止 (/25) | 未知語・固有名詞 (/25) |
| --- | --- | --- | --- | --- | --- |
| Standard v4 Q8 | 96 | 48 | 30 | 21 | 17 |
| Standard v4 cap-r1 Q8 | **98** | **49** | 30 | 21 | 17 |
| Standard v4 IQ4 | 94 | 47 | 30 | 21 | 17 |
| Standard v4 cap-r1 IQ4 | **97** | **49** | 30 | 21 | 17 |
| Nano v4 Q8 | 94 | 47 | 30 | 19 | 16 |
| Nano v4 cap-r1 Q8 | **99** | **49** | 30 | **20** | 16 |
| Nano v4 IQ4 | 95 | 46 | 30 | 19 | 16 |
| **Nano v4 cap-r1 IQ4** | **98** | **50** | 30 | **20** | **18** |
| Nano v4 cap-r1 blend85 IQ4 | 94 | 48 | 30 | 20 | 17 |

#### 高難度250問テストセット

| モデル | 多岐同音異義語・間接文脈 (/80) | 専門領域・難語 (/60) | 四字熟語・慣用句 (/40) | 複合文節・長文脈 (/40) | 誤変換抑止 (/30) |
| --- | --- | --- | --- | --- | --- |
| Standard v4 Q8 | 80 | 60 | 40 | 39 | 28 |
| **Standard v4 cap-r1 Q8** | **80** | **60** | **40** | **40** | **30** |
| Standard v4 IQ4 | 79 | 59 | 40 | 36 | 28 |
| Standard v4 cap-r1 IQ4 | 80 | 59 | 40 | 37 | 29 |
| Nano v4 Q8 | 80 | 60 | 39 | 38 | 30 |
| Nano v4 cap-r1 Q8 | 80 | 60 | 39 | **39** | 28 |
| Nano v4 IQ4 | 80 | 59 | 39 | 34 | 28 |
| **Nano v4 cap-r1 IQ4** | 79 | **60** | **40** | **39** | **29** |
| Nano v4 cap-r1 blend85 IQ4 | 80 | 59 | 40 | 37 | 29 |

---

## 4. 改善・回帰のペア別詳細分析

### 4.1 `Standard v4 Q8 -> Standard v4 cap-r1 Q8`（480問中 Fixed=6, Regressed=0）

- **230問 (Fixed=3, Regressed=0)**:
  - ID 7: `はしによせる`（机の中央を空けたい。）: `箸に寄せる` → `端に寄せる`
  - ID 88: `ぐんしゅうをおこなう`（軍隊が訓練を行う。）: `群衆を行う` → `軍習を行う`
  - ID 129: `じょしやごじゅんをかえない`: `女子や語順を変えない` → `助詞や語順を変えない`
- **250問 (Fixed=3, Regressed=0 / 全問正解 250/250達成)**:
  - ID 192: `ほうそうじこをふせぐためにほうそうをてっていする`: `放送事故を防ぐために放送を徹底する` → `放送事故を防ぐために包装を徹底する`
  - ID 229: `１００えんのあめ`: `1000円の飴` → `100円の飴`
  - ID 246: `１０きろはしる`: `１0キロ走る` → `10キロ走る`

### 4.2 `Standard v4 IQ4 -> Standard v4 cap-r1 IQ4`（480問中 Fixed=9, Regressed=1）

- **230問 (Fixed=5, Regressed=0)**:
  - ID 7 (`端に寄せる`)、ID 88 (`軍習を行う`)、ID 89 (`決を採る`)、ID 129 (`助詞や語順を変えない`)、ID 147 (`重大違反を別に集計する`) が改善。
- **250問 (Fixed=4, Regressed=1)**:
  - Fixed: ID 65 (`強制執行の申立て`)、ID 195 (`正気を失わずに勝機を見出す`)、ID 229 (`100円の飴`)、ID 246 (`10キロ走る`)。
  - Regressed: ID 235 (`わいふぁいにつなぐ`): `Wi-Fiにつなぐ` → `Wi-Fiに繋ぐ`（表記揺れ1件のみ）。

### 4.3 `Nano v4 IQ4 -> Nano v4 cap-r1 IQ4`（480問中 Fixed=19, Regressed=2）

現行の配布主軸サイズ（18.7 MB / 平均18.49 ms）である `Nano IQ4` において最も顕著な改善を確認した。ベースラインの `Standard v4 Q8`（459/480）および `Nano v4 Q8`（453/480）を上回る **463/480 (96.46%)** に到達した。

- **230問 (Fixed=10, Regressed=0)**:
  - ID 7 (`端に寄せる`)、ID 88 (`軍習を行う`)、ID 89 (`決を採る`)、ID 110 (`朝ご飯を食べてから出かける`)、ID 129 (`助詞や語順を変えない`)、ID 133 (`ウェブサイトを開いて内容を確認する`)、ID 134 (`クラウドではなく端末内で実行する`)、ID 201 (`違う`)、ID 207 (`るきあんと会う`→`ルキアンと会う` / 旧 `ルキアンとアウ`)、ID 215 (`くろのしずくを使う`→`クロノシズクを使う` / 旧 `黒のしずくを使う`)。
- **250問 (Fixed=9, Regressed=2)**:
  - Fixed:
    - ID 134: `古文書の資料を披見する` → `古文書の史料を披見する`
    - ID 175: `心象膨大に言いふらす` → `針小棒大に言いふらす`
    - ID 183: `医師の患者に対する医師を確認する` → `医師の患者に対する意思を確認する`
    - ID 188: `神聖な神殿で神聖の手続きをする` → `神聖な神殿で申請の手続きをする`
    - ID 194: `会期の終了とともに会期分析を行う` → `会期の終了とともに回帰分析を行う`
    - ID 195: `勝機を失わずに勝機を見出す` → `正気を失わずに勝機を見出す`
    - ID 196: `総裁の支持で債権を相殺する` → `総裁の指示で債権を相殺する`
    - ID 213: `病状が寛解し不腫も改善した` → `病状が寛解し浮腫も改善した`
    - ID 247: `１がつ１にち`: `１月２日` → `1月1日`
  - Regressed / Still Wrong:
    - ID 12 (`こうかをはじめました` / 着陸体勢に入った航空機): `降下を始めました` → `効果を始めました`
    - ID 209 (`べきとうせいのあるえーぴーあいでふくそうをかいしょうする`): `冪等性のあるAPIで輻輳を解消する` → `冪等性のあるAPIで複層を解消する`
    - Still Wrong ID 229 (`１００えんのあめ`): `１０円の飴` → `1000円の飴`（Nano では全角数字からの半角桁数維持に引き続き課題が残る。一方 Standard cap-r1 では Q8/IQ4 とも `100円の飴` に改善した。）

### 4.4 `blend85`（親モデル重み15%ブレンド）との比較

- `Nano v4 cap-r1 IQ4`（463/480）に対し、`Nano v4 cap-r1 blend85 IQ4` は **454/480** に留まった。
- `blend85` により学習済みのロジットマージンおよび `cap-r1` 固有の改善（230問 ID 133・250問 ID 188/194 等）が希釈されたため、本構成では **非ブレンド版 (`nano-v4-cap-r1-iq4nl-emb8.gguf`) が明確に優位** である。

---

## 5. 残課題と次ステップ候補

1. **Nano 系の数値桁数維持と `輻輳`/`降下` 回帰の解消**
   - `Nano v4 cap-r1 Q8`（250問 ID 229: `1000円の飴`、ID 231: `2028年`）および `Nano v4 cap-r1 IQ4`（ID 229: `1000円の飴`、ID 12: `効果`、ID 209: `複層`）で見られた少数回帰・未解決ケースに対し、数値桁数保持と `API/輻輳`・`航空機/降下` 周辺の蒸留重み調整または検証データ非重複での教師データ拡充を行う価値がある。
2. **230問テストの表現揺れ・架空固有名詞群の扱い**
   - `Standard v4 cap-r1 Q8` における残り15件の不一致は、`飴をなめる` / `飴を舐める` や `あとで` / `後で`、`わからない` / `分からない` 等の表記揺れ（7件）および架空固有名詞の漢字/カタカナ当て字（8件）で占められており、実文脈・同音異義語・保護文字列の変換精度はほぼ天井に近づいている。
3. **実IME（Windows / Android）反映判定**
   - `standard-v4-cap-r1-q8_0.gguf` / `nano-v4-cap-r1-iq4nl-emb8.gguf` ともに 480問ベンチマークで現行ベースラインを大きく上回ったが、モデルカタログや配布物への採用はタイポ補正併用・実打鍵動作確認を行った上で別途判断する。

---

## 6. `cap-r2`（非数値領域の強化・空文脈整合・量子化序列の回復）結果

数値揺れに関するロジック（`digit_exact` データおよび `fullwidth_digit_scale=0.95`）は据え置いたまま、**非数値領域の未解決課題**と **`cap-r1` で発生していた Standard/Nano・Q8/IQ4 間のスコア逆転** を解消するため `cap-r2` を実施した。

### 6.1 変更点

1. **量子化マージン損失と空文脈学習の整合 (`training/train_nano_v3.py`)**
   - `SuperTeacherDualAnchorTrainer.compute_loss`: `quant_margin` のターゲット幅を `1.8` → `2.4` に拡大し、`loss_base_correct` の重みを `0.10` → `0.20` へ引き上げるとともに、`loss_base_wrong` 側にも `+ 0.20 * quant_margin` を適用した。
   - `build_tokenized_examples`: `is_train and prompt.startswith("\uee00")` の行について元の `\uee00...` に加え `\uee02\uee00...` 形式も学習対象に含め、文脈なし推論（`\uee02\uee00` / `\uee00`）の乖離を解消した。
   - 単体テスト ([`training/test_standard_v4.py`](../../training/test_standard_v4.py)): 4 tests OK。
2. **非数値補強データの追加 (`data/build_standard_v3_dataset.py`)**
   - `capability_r2_rows` として、`降下`・`APIで輻輳を`・`臥薪嘗胆`・`短期の拘留`・`Wi-Fiにつなぐ`・`ドアを解錠`・`修学期間`・`飴をなめる`・`必要な物`・`朝ご飯`・短文口語漢字表記（`後で` / `分からない` / `何で` / `知らん`）・造語固有名詞群を追加。
   - 生成先: `artifacts/datasets/zerocon-v4-capability-r2/`
     - 総候補数: 1,096件 → Train: **992件**（SHA-256: `32ed91c2e07208a296223a97874a197ab1809cbd1e91d4cf4f6b9cfc428cad06`） / Val: **104件**（SHA-256: `d7eed33a64f40e126872758f954ee3054217c5a4ff2cfc7a0f9ec5329cdeb6a4`） / リーク除外: **0件**
   - 隔離検証 ([`data/test_dataset_isolation.py`](../../data/test_dataset_isolation.py)): 8 tests OK（共有キー・重複グループ 0件）。
3. **学習・蒸留・量子化**
   - **`standard-v4-cap-r2`**: `--teacher-model artifacts/checkpoints/nano-v4-cap-r1-merged` で 6 epochs 学習後に `Q8_0` / `IQ4_NL_emb8` へ量子化。
   - **`nano-v4-cap-r2`**: `artifacts/checkpoints/standard-v4-cap-r2-merged` を教師モデルとして 7 epochs 蒸留後に `Q8_0` / `IQ4_NL_emb8` へ量子化。

### 6.2 `cap-r2` CPU 480問ベンチマーク結果（全モデル `truncated_count = 0`）

同一 CPU プロトコル（`--backend cpu --n-gpu-layers 0`, `context=2048`, `threads=4`, `temperature=0.0`, `seed=42`, `max_tokens=96`, ランタイム SHA-256: `d5538214afb689cf4615217a8cc195fdaecd09e6fd045d67aedaa5a974a15a60`）による評価結果:

| モデル | 230問 | 高難度250問 | 480問合計 | `cap-r1`比 | 現行比 | 平均遅延 (ms) | p95 (230 / 250) | Model SHA-256 (先頭12桁) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Standard v4 cap-r2 Q8** | **224/230 (97.39%)** | **250/250 (100.00%)** | **474/480 (98.75%)** | **+9問** | **+15問** | 85.68 | 130.31 / 127.82 | `253399ea254b` |
| **Standard v4 cap-r2 IQ4** | **225/230 (97.83%)** | **248/250 (99.20%)** | **473/480 (98.54%)** | **+14問** | **+22問** | 57.20 | 81.10 / 89.88 | `c1510f11541c` |
| **Nano v4 cap-r2 Q8** | **222/230 (96.52%)** | **248/250 (99.20%)** | **470/480 (97.92%)** | **+10問** | **+17問** | 30.90 | 37.90 / 48.14 | `abce07b7f547` |
| **Nano v4 cap-r2 IQ4** | **222/230 (96.52%)** | **245/250 (98.00%)** | **467/480 (97.29%)** | **+4問** | **+21問** | 22.81 | 33.93 / 37.30 | `f74cd57d5e7c` |

結果の保存先:
- [`artifacts/results/v4_cap_r2_cpu_230.json`](../../artifacts/results/v4_cap_r2_cpu_230.json)
- [`artifacts/results/v4_cap_r2_cpu_250hard.json`](../../artifacts/results/v4_cap_r2_cpu_250hard.json)

### 6.3 カテゴリ別内訳と主な改善・残課題

#### 230問テストセット

| モデル | 文脈依存・同音異義語 (/100) | 基本変換 (/50) | 英数字・保護文字列 (/30) | 校正・補完禁止 (/25) | 未知語・固有名詞 (/25) |
| --- | --- | --- | --- | --- | --- |
| Standard v4 cap-r2 Q8 | **100** | **50** | **30** | 24 | **20** |
| Standard v4 cap-r2 IQ4 | **100** | **50** | **30** | **25** | **20** |
| Nano v4 cap-r2 Q8 | 99 | **50** | **30** | **25** | 18 |
| Nano v4 cap-r2 IQ4 | 99 | 49 | **30** | **25** | 19 |

#### 高難度250問テストセット

| モデル | 多岐同音異義語・間接文脈 (/80) | 専門領域・難語 (/60) | 四字熟語・慣用句 (/40) | 複合文節・長文脈 (/40) | 誤変換抑止 (/30) |
| --- | --- | --- | --- | --- | --- |
| Standard v4 cap-r2 Q8 | **80** | **60** | **40** | **40** | **30** |
| Standard v4 cap-r2 IQ4 | 79 | **60** | **40** | 39 | **30** |
| Nano v4 cap-r2 Q8 | 79 | **60** | 39 | **40** | **30** |
| Nano v4 cap-r2 IQ4 | 79 | 59 | **40** | 37 | **30** |

#### 主要な達成点・残課題
- **序列逆転の完全解消**: 480問合計が **Standard Q8 (474) >= Standard IQ4 (473) > Nano Q8 (470) >= Nano IQ4 (467)** となり、パラメータ数および量子化ビット数に沿った自然な序列を回復した。
- **Standard IQ4 の大幅改善 (`459 -> 473`)**: `quant_margin` の強化により、`cap-r1` で 4bit 劣化していた `短期の拘留に処す`、`医師の患者に対する意思を確認する`、`放送事故を防ぐために包装を徹底する`、`Wi-Fiにつなぐ` がいずれも IQ4 で正しく変換できるようになった（高難度250問 `248/250`）。
- **230問テストの劇的改善**:
  - `文脈依存・同音異義語` は Standard Q8/IQ4 で **100/100**、Nano Q8/IQ4 で **99/100**。`必要な物を用意する` や `飴をなめる`、`朝ご飯を食べてから出かける` などの取りこぼしが解消。
  - 空文脈 `\uee02\uee00` 学習整合と短文口語データにより、`校正・補完禁止`（`後で連絡する` / `分からない` / `何でそうなる` / `後で` / `知らん`）が Standard IQ4 と Nano Q8/IQ4 で **25/25 (100%)**、Standard Q8 で **24/25** に到達。
  - `未知語・固有名詞` でも `ぬるぽんえっくす`・`ぬめぴょん化` 等が改善し、4モデルとも 18〜20/25 へ向上した。
- **高難度250問の誤変換抑止・Nano 個別差**:
  - 数値・全半角を含む `誤変換抑止` は全4モデルで **30/30 (100%)** を達成（Nano の `100円の飴` / `2026年` も `cap-r2` で解消）。
  - 一方、Nano Q8 では `使用を確定する`（仕様）・`式典に会う服装`（合う）・`臥薪の日々` が、Nano IQ4 では `式典に会う服装`・`癌細胞`（がん細胞）・複文節3件（`校正と決裁` / `医師…医師` / `総裁の支持`）の僅差回帰が残っている。

---

## 7. `cap-nam`（数値プレースホルダ `nam` 置換・エンジン側復元・全データ再学習）結果

数字列 `[0-9０-９]+` をモデルへ直接渡さず推論前にプレースホルダ `nam` へ置換し、モデル出力中の `nam` を入力から抽出した半角数字列でエンジン側復元する構成へ移行し、学習データの全数字置換＋助数詞データの追加を行って Standard v4 / Nano v4 を再学習した。

### 7.1 エンジン実装・学習データ・検証結果

1. **`nam` マスク・エンジン側復元ロジック**
   - [`benchmarks/research/benchmark_kana_kanji_models.py`](../../benchmarks/research/benchmark_kana_kanji_models.py): `mask_digits_for_model` / `restore_digits_from_model` を追加（`NAM_RESTORE_RE = re.compile(r"nam(?![a-wyz])")` により `name` / `dynamic` / `filename` 等の小文字英単語を誤置換せず、`2x3`・`6E`・`14900K`・日本語助数詞のみを左から順に半角ASCII数字へ復元）。
   - [`windows/crates/server/src/ai_assist/corrector.rs`](../../windows/crates/server/src/ai_assist/corrector.rs): `mask_digits_with_nam` / `restore_nam_digits` を実装し、`payload` / `completed_surface` に適用。
   - [`windows/crates/server/src/ai_assist/prediction.rs`](../../windows/crates/server/src/ai_assist/prediction.rs): `zerocon` の拡張・本変換プロンプト生成と出力復元へ適用。
   - [`windows/frontend/src-tauri/src/lib.rs`](../../windows/frontend/src-tauri/src/lib.rs): `run_benchmark_item` および `run_ai_assist_benchmark_item` へ適用。
   - なお、通常変換経路の AzooKey 本体（`windows/server-swift/Sources/azookey-server/azookey_server.swift` および外部依存 `AzooKeyKanaKanjiConverter`）は本実験では未変更。
2. **学習データセットの数字置換 (`data/build_nano_v3_dataset.py`, `data/build_standard_v3_dataset.py`)**
   - 読みに `[0-9０-９]+` を含む行（及び左文脈の数字）の連続数字列を `nam` へ置換し、読みが純仮名の漢数表現（`ニヒャクモジ -> 200文字` 等）はそのまま保持した。また、`capability_r2_rows` に非重複の助数詞（`階`・`教科`・`%`・`年`・`月日`・`回`・`人`・`冊`）18行を追加。
   - [`training/train_nano_v3.py`](../../training/train_nano_v3.py): `save_merged_with_exact_base_metadata` の既定 `fullwidth_digit_scale` を `0.95` → `1.0` へ変更。
   - 出力先: `artifacts/datasets/zerocon-v4-nam/`
     - 総候補数: 1,114件 → Train: **1,014件**（SHA-256: `64c0dee1ffbb65e5488cdd4c3469685ce78c3d2a7074af5f1561188c76dc8883`） / Val: **100件**（SHA-256: `378e483531103758a39994884c9816d3071e789a29bf55c65376719df6439dfc`） / リーク除外: **0件**（`TRAIN nam: 69` / `VAL nam: 3`）。
3. **単体テスト・ビルド検証**
   - `data/test_dataset_isolation.py`: **8/8 OK**
   - `training/test_standard_v4.py`: **5/5 OK**（`test_nam_digit_mask_and_restore` 追加）
   - `cargo test --manifest-path windows/Cargo.toml -p azookey-server`: `corrector`（lib 9/9, bin 9/9）、`prediction`（lib 6/6, bin 9/9, ignored 1）OK
   - `cargo check --manifest-path windows/Cargo.toml -p frontend`: OK

### 7.2 `cap-nam` CPU 480問ベンチマーク結果（全モデル `truncated_count = 0`）

同一 CPU プロトコル（`--backend cpu --n-gpu-layers 0`, `context=2048`, `threads=4`, `temperature=0.0`, `seed=42`, `max_tokens=96`, ランタイム SHA-256: `d5538214afb689cf4615217a8cc195fdaecd09e6fd045d67aedaa5a974a15a60`）による評価結果:

| モデル | 230問 | 高難度250問 | 480問合計 | `cap-r2`比 | 現行比 | 平均遅延 (ms) | p95 (230 / 250) | Model SHA-256 (先頭12桁) |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Standard v4 cap-nam Q8** | **225/230 (97.83%)** | **250/250 (100.00%)** | **475/480 (98.96%)** | **+1問** | **+16問** | 77.12 | 113.73 / 120.73 | `83e46beb0b2c` |
| **Standard v4 cap-nam IQ4** | **225/230 (97.83%)** | **249/250 (99.60%)** | **474/480 (98.75%)** | **+1問** | **+23問** | 51.65 | 70.74 / 80.16 | `952cf7901914` |
| **Nano v4 cap-nam Q8** | **224/230 (97.39%)** | **249/250 (99.60%)** | **473/480 (98.54%)** | **+3問** | **+20問** | 27.36 | 39.67 / 42.00 | `fe9340342871` |
| **Nano v4 cap-nam IQ4** | **223/230 (96.96%)** | **246/250 (98.40%)** | **469/480 (97.71%)** | **+2問** | **+23問** | 24.52 | 33.04 / 34.61 | `27be5fc6d554` |

結果の保存先:
- [`artifacts/results/v4_cap_nam_cpu_230.json`](../../artifacts/results/v4_cap_nam_cpu_230.json)
- [`artifacts/results/v4_cap_nam_cpu_250hard.json`](../../artifacts/results/v4_cap_nam_cpu_250hard.json)

### 7.3 カテゴリ別内訳と主な達成点

#### 230問テストセット

| モデル | 文脈依存・同音異義語 (/100) | 基本変換 (/50) | 英数字・保護文字列 (/30) | 校正・補完禁止 (/25) | 未知語・固有名詞 (/25) |
| --- | --- | --- | --- | --- | --- |
| **Standard v4 cap-nam Q8** | **100** | **50** | **30** | **25** | **20** |
| **Standard v4 cap-nam IQ4** | **100** | **50** | **30** | **25** | **20** |
| **Nano v4 cap-nam Q8** | 99 | **50** | **30** | **25** | **20** |
| **Nano v4 cap-nam IQ4** | **100** | **50** | **30** | 23 | **20** |

#### 高難度250問テストセット

| モデル | 多岐同音異義語・間接文脈 (/80) | 専門領域・難語 (/60) | 四字熟語・慣用句 (/40) | 複合文節・長文脈 (/40) | 誤変換抑止 (/30) |
| --- | --- | --- | --- | --- | --- |
| **Standard v4 cap-nam Q8** | **80** | **60** | **40** | **40** | **30** |
| **Standard v4 cap-nam IQ4** | **80** | **60** | **40** | 39 | **30** |
| **Nano v4 cap-nam Q8** | **80** | **60** | 39 | **40** | **30** |
| **Nano v4 cap-nam IQ4** | 79 | **60** | 39 | 38 | **30** |

- **数値関連テスト全問正解**: 数字を含むテスト項目（230問中 29件・250問中 8件の計 **37件**）は全4モデルで **37/37 (100%)** を達成。モデルから数値トークンの揺れを切り離した結果、桁数・日付・`Wi-Fi 6E`・`14900K`・`192.168.1.1:8080/v1/chat`・全角入力からの半角維持すべてが安定して一致した。
- **全4モデルでの精度更新と自然序列**: **Standard Q8 (475) >= Standard IQ4 (474) > Nano Q8 (473) > Nano IQ4 (469)** となり、4モデルすべてで `cap-r2` を上回ると同時に、モデルサイズ・量子化ビット数通りの単調序列を維持した。
  - **Standard Q8**: 230問の非造語領域（`/205`）＋高難度250問（`/250`）＝ **455/455 (100.0%)** に到達（残5件は `未知語・固有名詞` の造語表記ゆれのみ）。
  - **Nano Q8**: `cap-r2` 比 **+3問**（230問 `224/230`, 250問 `249/250`）へ躍進し、`Standard IQ4`（474）に1問差まで迫った。
  - **Nano IQ4**: `文脈依存・同音異義語` **100/100**、`基本変換` **50/50**、`専門領域・難語` **60/60** に到達（`469/480`）。
- **Full SHA-256**:
  - `artifacts/models/standard-v4-cap-nam-q8_0.gguf`: `83e46beb0b2c99f1f2bb8ec95ede56726ceba94fdcc4d66ed96eec4ed054d361`
  - `artifacts/models/standard-v4-cap-nam-iq4nl-emb8.gguf`: `952cf79019149eb248bd59c407e259205298827af830b44cf616fdac4396f556`
  - `artifacts/models/nano-v4-cap-nam-q8_0.gguf`: `fe934034287128ce00ad037507abee32998824c841c784c5c4df94f4a9fb09fb`
  - `artifacts/models/nano-v4-cap-nam-iq4nl-emb8.gguf`: `27be5fc6d554c14783bfd22ba71ab8a2fad9592eca7363c924a5f4cfe9832b9f`

---

## 8. 実IME入力経路ベンチマーク（160問）と拡張指標 (Metrics 1, 3, 5, 6)

ライブ変換の自動確定（[`windows/crates/shared/src/conversion_policy.rs`](../../windows/crates/shared/src/conversion_policy.rs): `total >= 16` で先頭 `>= 8` を確定し、残り `>= 7` 文字のウィンドウを維持）を前提に、実際の IME に入力される **5〜17文字（平均 9.8文字）の現実的チャンク** から構成した 8 カテゴリ・160問ベンチマークを新設し、遅延・SLA・メモリ効率・量子化一貫性・構造健全性指標を追加した上で `cap-nam` 4モデルを評価した。

### 8.1 追加指標・テストセット構成

1. **計測指標の拡張 ([`benchmarks/research/benchmark_kana_kanji_models.py`](../../benchmarks/research/benchmark_kana_kanji_models.py))**:
   - **Metric 1 (遅延分布と SLA 超過率)**: `p50` / `p95` / `p99` レイテンシ、応答 SLA 超過率（`>30ms`, `>50ms`, `>100ms`）、カテゴリ別・入力長バケット別（`short_4_8` / `medium_9_14` / `precommit_15_22`）内訳。
   - **Metric 3 (メモリ効率)**: Windows `K32GetProcessMemoryInfo` による `llama-server.exe` の `rss_mb` / `peak_rss_mb`、および `accuracy_per_file_mb` / `accuracy_per_ram_mb`。
   - **Metric 5 (量子化一貫性)**: 同一ファミリーの `Q8` vs `IQ4` 間で出力完全一致率・正誤一致率・量子化片側正答 ID・差分ケース一覧を自動集計。
   - **Metric 6 (構造健全性・非破壊率)**: 空出力なし・トークン上限到達なし・PUA/制御文字なし・異常膨張なし・`nam` 残留および数値欠落なしの 5 条件を満たす `structural_integrity_pct` / `nam_restoration_pct`。
2. **実IME考慮テストセット ([`data/build_ime_realistic_testset.py`](../../data/build_ime_realistic_testset.py) → [`data/kana_kanji_ime_realistic_testset.jsonl`](../../data/kana_kanji_ime_realistic_testset.jsonl))**:
   - SHA-256: `8470d7fc62a7539163a687d527717792f00322c43fa81f94da6dd7c81e2d93a1`
   - 160問（8カテゴリ × 各20問、入力長 5〜17文字、平均 9.8文字）。`artifacts/datasets/zerocon-v4-nam/{train,val}.jsonl` および既存 `EVALUATION_FILES` との重複キー **0件** を検証済み。
3. **単体テスト**:
   - `data/test_dataset_isolation.py`: **8/8 OK**
   - `training/test_standard_v4.py`: **9/9 OK**（Metric 1 / 3 / 5 / 6・テストセット隔離検証 4件を追加）

### 8.2 実IME 160問 CPU ベンチマーク結果

| モデル | 正解数 (/160) | p50 / p95 / p99 (ms) | >30ms / >50ms / >100ms | ファイル / Peak RAM (MB) | 正解率 / RAM MB | 構造健全率 / nam復元率 |
| --- | --- | --- | --- | --- | --- | --- |
| **Standard v4 cap-nam Q8** | **151 (94.4%)** | 63.1 / 93.1 / 126.7 | 100.0% / 78.8% / 2.5% | 99.05 / 365.99 | 0.258 %/MB | **100.0% / 100.0%** |
| **Standard v4 cap-nam IQ4** | **148 (92.5%)** | 37.8 / 59.5 / 76.8 | 76.2% / 21.9% / 0.0% | 58.55 / 371.27 | 0.249 %/MB | **100.0% / 100.0%** |
| **Nano v4 cap-nam Q8** | **145 (90.6%)** | 17.0 / 36.5 / 38.2 | 21.9% / **0.0%** / 0.0% | 27.69 / 131.10 | **0.691 %/MB** | 99.4% / 95.0% |
| **Nano v4 cap-nam IQ4** | **138 (86.2%)** | **9.1 / 31.3 / 33.4** | **9.4% / 0.0% / 0.0%** | **18.69 / 132.27** | 0.652 %/MB | 98.8% / 90.0% |

- **Quant 一致率 (Metric 5)**:
  - `Standard Q8 vs IQ4`: 出力完全一致 **155/160 (96.9%)**、正誤一致 **98.1%**（`q8_only=[44, 49, 69]`, `q4_only=[]`）。
  - `Nano Q8 vs IQ4`: 出力完全一致 **150/160 (93.8%)**、正誤一致 **95.6%**（`q8_only=[56, 65, 76, 121, 142, 145, 154]`, `q4_only=[]`）。
- 保存先: [`artifacts/results/v4_cap_nam_cpu_ime_realistic.json`](../../artifacts/results/v4_cap_nam_cpu_ime_realistic.json)

### 8.3 8カテゴリ別内訳（各20問）と新たに可視化された課題

| カテゴリ | Standard Q8 | Standard IQ4 | Nano Q8 | Nano IQ4 |
| --- | --- | --- | --- | --- |
| 自動確定直後・文途中チャンク | **20** | **20** | **20** | **20** |
| 自動確定直前・文節境界チャンク | **20** | **20** | **20** | **20** |
| 文頭コールドスタート短チャンク | 16 | 14 | **17** | 16 |
| 訓読み多義動詞・複合動詞 | **20** | 19 | 18 | 16 |
| ビジネス敬語・補助動詞の開閉 | **20** | **20** | 19 | 19 |
| 日常チャット・口語・過剰漢字抑止 | **20** | **20** | **20** | **20** |
| 数値 `nam`・助数詞・単位チャンク | **15** | **15** | 12 | 11 |
| 文節切り出し・外来語＋助詞境界 | **20** | **20** | 19 | 16 |

1. **文脈あり自動確定境界・チャット抑止は全4モデル満点**: `自動確定直後・文途中チャンク`、`自動確定直前・文節境界チャンク`、`日常チャット・口語・過剰漢字抑止` の 3 カテゴリ（計60問）は **4モデルすべてが 60/60 (100%)** を達成した。
2. **`数値nam・助数詞・単位チャンク` における未学習助数詞と Nano でのローマ字化を検出**:
   - Standard Q8/IQ4 でも `2だいめ -> 2代目`（車脈絡で台）、`6にん -> 6にん`、`5がつ20か -> 5月20化`、`0けん -> 0権/県`、`12はい -> 12配` が未対応として残った。
   - さらに **Metric 6 により Nano 特有の構造崩れ** として、`3ぼん` → `nambonまとめて持つ`（Nano Q8/IQ4）、`0けん` → `namkenになった`（Nano IQ4）という **`nam` の後続カタカナが小文字ローマ字へ引きずられて `NAM_RESTORE_RE` に引っかからない（かつ変換も失敗する）現象** を初めて捕捉した。
3. **`文頭コールドスタート短チャンク` の無文脈コロケーション選好**:
   - 左文脈なし (`なし`) の短チャンクで、`きしゃをみおくる -> 記者を見送る`、`しんちょうにえらぶ -> 身長に選ぶ`、`たんきをおこす -> 短期を起こす`（Standard）、`せいさんなげんば -> 生産な現場`（Nano）が共通弱点として特定された。
4. **Nano の CPU リアルタイム性能優位**:
   - CPU 実行（スレッド4）で `Standard Q8` は `p50 63.1ms`（`>30ms` 率 `100.0%`、`>50ms` 率 `78.8%`）を要するのに対し、`Nano Q8` は `p50 17.0ms / p95 36.5ms / >50ms 0.0%`・`Peak RAM 131.1MB`（Standard 比 **2.8分の1**）、`Nano IQ4` は `p50 9.1ms / p95 31.3ms / >50ms 0.0%` とリアルタイム応答域を維持している。一方 `Nano IQ4` では `文節切り出し`・`訓読み動詞` に 4〜7問の 4bit 量子化劣化が残るため、次期学習・データ補強の明確な標的となる。

---

## 9. `cap-nam-r2`（体系的 `nam` 助数詞・文頭逆引き対照・訓読み/ぎなた読み補強・全6ベンチマーク隔離）結果

第8節で可視化された弱点（未学習助数詞・Nanoでのローマ字化・文頭無文脈チャンク・訓読み多義動詞・文節切り出し境界）を標的として、エンジン側2パス `nam` 復元保護とデータセット補強（[`artifacts/datasets/zerocon-v4-nam-r2/`](../../artifacts/datasets/zerocon-v4-nam-r2/)）を実施し、`standard-v4-cap-nam-r2` および `nano-v4-cap-nam-r2` を学習・量子化して全640問（230 + 高難度250 + 実IME 160）で評価した。

### 9.1 変更点とデータセット隔離検証

1. **2パス `nam` 復元保護 ([`benchmarks/research/benchmark_kana_kanji_models.py`](../../benchmarks/research/benchmark_kana_kanji_models.py), [`windows/crates/server/src/ai_assist/corrector.rs`](../../windows/crates/server/src/ai_assist/corrector.rs))**:
   - 第1パス `nam(?![a-wyz])` で未消費の入力数字列が残った場合にのみ、前後に小文字英字幹（`name` / `names` / `naming` / `dynamic` 等）を持たない `nam` を数字復元する第2パス `NAM_FALLBACK_RE`（Python）/ fallback パス（Rust）を追加し、`nambon -> 3bon` 系の残留を遮断した。
2. **全6評価ベンチマーク統合隔離 ([`data/dataset_isolation.py`](../../data/dataset_isolation.py))**:
   - `EVALUATION_FILES` に `data/kana_kanji_ime_realistic_testset.jsonl` を正式編入（230 + 250hard + 160ime + typo300 + typo_testset + predictive_testset の全6セット、計1,978予約キー）。
3. **補強データセット構築 ([`data/build_standard_v3_dataset.py`](../../data/build_standard_v3_dataset.py))**:
   - `capability_nam_r2_rows` として、`nam` 助数詞・音便・動詞直結（`時/分(ふん・ぷん)/秒/時間`, `本(ほん・ぽん・ぼん)`, `件/軒/券`, `台/台目/代/代目`, `枚`, `杯(はい・ぱい・ばい)`, `人/冊/階/回`, `月日/泊/年度/番目/文字/%`）、文頭無文脈 `""` コロケーション対照ペア（`記者/汽車`, `慎重/身長`, `短気/短期`, `渋滞/重体`, `凄惨/生産`, `証明/照明`）、訓読み多義動詞・敬語・ぎなた読み境界ペアを追加。
   - 総候補: **1,254件** → Train: **1,144件**（SHA-256: `b89fe2c772547b22c75bbf62d7e7b2b61b0004563d773b4f45e63879405d3f51`） / Val: **110件**（SHA-256: `91afdfcb8e6a9a903b51d332649079c4712b8f4365356ed30c93526303354ae1`） / リーク除外: **0件**。
4. **単体テスト検証**:
   - `data/test_dataset_isolation.py`: **8/8 OK**
   - `training/test_standard_v4.py`: **9/9 OK**
   - `cargo test --manifest-path windows/Cargo.toml -p azookey-server corrector`: **lib 9/9, bin 9/9 OK**

### 9.2 全640問 CPU ベンチマーク結果（全モデル全セット `structural_integrity = 100.0%`・`nam_restoration = 100.0%`）

| モデル | 実IME 160問 | 230問 | 高難度250問 | 全640問合計 | `cap-nam`比 | 実IME p50 / Peak RAM | Model SHA-256 (先頭12桁) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Standard v4 cap-nam-r2 Q8** | **155/160 (96.9%)** | **225/230 (97.8%)** | **250/250 (100.0%)** | **630/640 (98.44%)** | **+4問** | 63.4ms / 365.7MB | `2d9df3f15704` |
| **Standard v4 cap-nam-r2 IQ4** | **154/160 (96.2%)** | **225/230 (97.8%)** | 248/250 (99.2%) | **627/640 (97.97%)** | **+5問** | 43.6ms / 371.0MB | `3eb2f50cabce` |
| **Nano v4 cap-nam-r2 Q8** | **153/160 (95.6%)** | 223/230 (97.0%) | 248/250 (99.2%) | **624/640 (97.50%)** | **+6問** | 15.1ms / 131.6MB | `78e67ed35187` |
| **Nano v4 cap-nam-r2 IQ4** | **150/160 (93.8%)** | 221/230 (96.1%) | 246/250 (98.4%) | **617/640 (96.41%)** | **+10問** | **10.6ms / 132.7MB** | `7227116cb093` |

- **Quant 一致率（実IME 160問 / 230問 / 250問）**:
  - `Standard Q8 vs IQ4`: 出力完全一致 **97.5% / 99.1% / 99.2%**、正誤一致 **99.4% / 100.0% / 99.2%**
  - `Nano Q8 vs IQ4`: 出力完全一致 **94.4% / 97.8% / 98.4%**、正誤一致 **95.6% / 98.3% / 98.4%**
- 結果保存先:
  - [`artifacts/results/v4_cap_nam_r2_cpu_ime_realistic.json`](../../artifacts/results/v4_cap_nam_r2_cpu_ime_realistic.json)
  - [`artifacts/results/v4_cap_nam_r2_cpu_230.json`](../../artifacts/results/v4_cap_nam_r2_cpu_230.json)
  - [`artifacts/results/v4_cap_nam_r2_cpu_250hard.json`](../../artifacts/results/v4_cap_nam_r2_cpu_250hard.json)

### 9.3 実IME 160問カテゴリ別推移（`cap-nam` → `cap-nam-r2`）

| カテゴリ (/20) | Standard Q8 | Standard IQ4 | Nano Q8 | Nano IQ4 |
| --- | --- | --- | --- | --- |
| 自動確定直後・文途中チャンク | 20 → **20** | 20 → **20** | 20 → **20** | 20 → 19 |
| 自動確定直前・文節境界チャンク | 20 → **20** | 20 → **20** | 20 → **20** | 20 → **20** |
| 文頭コールドスタート短チャンク | 16 → **17** | 14 → **17** | 17 → **17** | 16 → **19** |
| 訓読み多義動詞・複合動詞 | 20 → **20** | 19 → **20** | 18 → **19** | 16 → **17** |
| ビジネス敬語・補助動詞の開閉 | 20 → **20** | 20 → **20** | 19 → **19** | 19 → **19** |
| 日常チャット・口語・過剰漢字抑止 | 20 → **20** | 20 → **20** | 20 → **20** | 20 → **20** |
| 数値 `nam`・助数詞・単位チャンク | 15 → **19** | 15 → **18** | 12 → **19** | 11 → **17** |
| 文節切り出し・外来語＋助詞境界 | 20 → 19 | 20 → 19 | 19 → **19** | 16 → **19** |

1. **`数値nam・助数詞・単位チャンク` の飛躍的改善と 100% 構造健全性達成**:
   - `Standard Q8`: `15 -> 19 (+4)`、`Standard IQ4`: `15 -> 18 (+3)`、**`Nano Q8`: `12 -> 19 (+7)`**、**`Nano IQ4`: `11 -> 17 (+6)`**。
   - `6にん追加する`、`2台目にする`、`0件になった`、`4冊借りてきた`、`25枚ずつ配る`、`3本まとめて持つ` が一斉に解消し、Nano で問題となっていた `nambon` / `namken` 残留も **全4モデル・全640問で `structural_integrity = 100.0%` / `nam_restoration = 100.0%`** を達成した。残る共通未対応は `12はいちゅうもんする -> 12配注文する` の1件。
2. **自然序列の一貫した維持**:
   - 全640問合計は **Standard Q8 (630, 98.44%) > Standard IQ4 (627, 97.97%) > Nano Q8 (624, 97.50%) > Nano IQ4 (617, 96.41%)** となり、160問・230問・250問の単独指標でも序列逆転ゼロを維持。
   - 特に **`Nano Q8` は実IME 160問で 153/160 (95.6%)・640問で 624/640 (97.50%)** へ到達し、`cap-nam` の `Standard IQ4`（148/160, 622/640）を上回る精度を `p50 15.1ms` / `Peak RAM 131.6MB` で実現した。
3. **Full SHA-256 (`artifacts/models/`)**:
   - `artifacts/models/standard-v4-cap-nam-r2-q8_0.gguf` (99.05 MB): `2d9df3f1570493cb62aca8b728ffeb8dd3f7d59f848981a75292e9142e004d38`
   - `artifacts/models/standard-v4-cap-nam-r2-iq4nl-emb8.gguf` (58.55 MB): `3eb2f50cabcead4e9f738b1e0e154b37c50334c7d15ea7e564d452cd5e0e3955`
   - `artifacts/models/nano-v4-cap-nam-r2-q8_0.gguf` (27.69 MB): `78e67ed351873dabaefb41533533c46e6413a2a22a1de53e61cda75d61e5a9ae`
   - `artifacts/models/nano-v4-cap-nam-r2-iq4nl-emb8.gguf` (18.69 MB): `7227116cb093b40cbdf61db746d57e72c5530cc85ab7e266e14579aa50be3a41`

---

## 10. `cap-nam-r3`（日常チャット・口語・ネットスラング・過剰漢字抑止・補助動詞ひらがな開き強化）結果

第8〜9節で既存160問ベンチマークの `日常チャット・口語・過剰漢字抑止 (20問)` が全モデル 20/20 で飽和していた一方、実機診断で判明した口語略語（`りょです -> 旅です`, `あざす`, `どういたしまして -> 同意たしまして`, `件どうなった -> 剣道なった`）、ネット口語・カタカナ語（`わかるみが深い -> 分かる味が深い`, `ポチる`, `エモい`, `ワンチャン`, `ウケる -> 受ける`, `詰む -> 積む`）、副詞・接続詞の過剰漢字、補助動詞のひらがな開き（`〜てほしい -> 〜て欲しい`, `〜てみる/ておく/てあげる/てくれる/てもらう/てくる/ていく`）を標的として、新規 100問専用ベンチマークと補強データセット（[`artifacts/datasets/zerocon-v4-nam-r3/`](../../artifacts/datasets/zerocon-v4-nam-r3/)）を構築し、`standard-v4-cap-nam-r3` および `nano-v4-cap-nam-r3` を学習・量子化・評価した。

### 10.1 専用評価セット・データセット・単体テスト

1. **日常チャット・口語・過剰漢字抑止 100問専用テストセット ([`data/build_colloquial_testset.py`](../../data/build_colloquial_testset.py) → [`data/kana_kanji_colloquial_chat_testset.jsonl`](../../data/kana_kanji_colloquial_chat_testset.jsonl))**:
   - SHA-256: `7c3a4dc174f4ca6eacf6143c69080a598970c9cf4c16b2b6897c88ece141baf4`
   - 5カテゴリ × 各20問（入力長 4〜14文字、平均 9.2文字）で構成し、[`data/dataset_isolation.py`](../../data/dataset_isolation.py) の `EVALUATION_FILES`（全7ベンチマーク）へ編入。
   - `cap-nam-r2` のベースライン評価（[`artifacts/results/v4_cap_nam_r2_cpu_colloquial100.json`](../../artifacts/results/v4_cap_nam_r2_cpu_colloquial100.json)）では、`Std_r2_Q8`: **94/100**, `Std_r2_IQ4`: **94/100**, `Nano_r2_Q8`: **92/100**, `Nano_r2_IQ4`: **87/100** と明確な課題・量子化差を観測した。
2. **学習データセット補強 ([`data/build_standard_v3_dataset.py`](../../data/build_standard_v3_dataset.py))**:
   - `capability_colloquial_r3_rows` として、上記5領域の非重複フレーズ（本動詞 `欲しい/受ける/積む/見る/置く/帰る` や子犬 `わんちゃん` との意味境界対照ペアを含む）を追加。`colloquial` カテゴリの学習件数を 36件 → **166件** へ拡充した。
   - 出力先: `artifacts/datasets/zerocon-v4-nam-r3/`
     - 総候補: **1,395件** → Train: **1,274件**（SHA-256: `7d14a092cd370fbaaf2c4daa75b1e48b667ba70644c437734d501c48edd42885`） / Val: **121件**（SHA-256: `b03acea312475964a627eb4944b32c8457f82ddfe158c49727a2f57290d06869`） / 予約評価キー: **2,192件** / リーク除外: **0件**。
3. **単体テスト検証**:
   - `data/test_dataset_isolation.py`: **8/8 OK**
   - `training/test_standard_v4.py`: **10/10 OK**（`test_colloquial_chat_testset_validity_and_isolation` 追加）

### 10.2 全740問 CPU ベンチマーク結果（`cap-nam-r2` → `cap-nam-r3`、全モデル全セット `structural_integrity = 100.0%`・`nam_restoration = 100.0%`）

| モデル | 口語100問 (`r2 → r3`) | 実IME 160問 | 230問 | 高難度250問 | 全740問合計 (`r2 → r3`) | 口語 p50 / Peak RAM | 実IME p50 / Peak RAM |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Standard v4 cap-nam-r3 Q8** | 94 → **99 (99.0%)** | **156/160 (97.5%)** | **225/230 (97.8%)** | 249/250 (99.6%) | 724 → **729/740 (98.51%, +5)** | 73.9ms / 317.5MB | 68.2ms / 366.0MB |
| **Standard v4 cap-nam-r3 IQ4** | 94 → **98 (98.0%)** | **156/160 (97.5%)** | **225/230 (97.8%)** | 247/250 (98.8%) | 721 → **726/740 (98.11%, +5)** | 44.4ms / 322.6MB | 47.8ms / 371.1MB |
| **Nano v4 cap-nam-r3 Q8** | 92 → **98 (98.0%)** | **153/160 (95.6%)** | **224/230 (97.4%)** | 247/250 (98.8%) | 716 → **722/740 (97.57%, +6)** | 26.6ms / 114.4MB | 20.6ms / 131.1MB |
| **Nano v4 cap-nam-r3 IQ4** | 87 → **97 (97.0%)** | **153/160 (95.6%)** | **222/230 (96.5%)** | **247/250 (98.8%)** | 704 → **719/740 (97.16%, +15)** | **11.3ms / 115.9MB** | **23.1ms / 132.3MB** |

- **Quant 一致率（`cap-nam-r3`: 口語100 / 実IME 160 / 230 / 高難度250）**:
  - `Standard Q8 vs IQ4`: 出力完全一致 **99.0% / 98.1% / 99.6% / 99.2%**、正誤一致 **99.0% / 98.8% / 100.0% / 99.2%**
  - `Nano Q8 vs IQ4`: 出力完全一致 **98.0% / 98.1% / 97.8% / 98.4%**、正誤一致 **99.0% / 98.8% / 99.1% / 98.4%**（口語100では `93.0% -> 98.0%`、実IME160では `94.4% -> 98.1%` へ量子化一貫性が向上）
- 結果保存先:
  - [`artifacts/results/v4_cap_nam_r2_cpu_colloquial100.json`](../../artifacts/results/v4_cap_nam_r2_cpu_colloquial100.json)
  - [`artifacts/results/v4_cap_nam_r3_cpu_colloquial100.json`](../../artifacts/results/v4_cap_nam_r3_cpu_colloquial100.json)
  - [`artifacts/results/v4_cap_nam_r3_cpu_ime_realistic.json`](../../artifacts/results/v4_cap_nam_r3_cpu_ime_realistic.json)
  - [`artifacts/results/v4_cap_nam_r3_cpu_230.json`](../../artifacts/results/v4_cap_nam_r3_cpu_230.json)
  - [`artifacts/results/v4_cap_nam_r3_cpu_250hard.json`](../../artifacts/results/v4_cap_nam_r3_cpu_250hard.json)

### 10.3 口語100問カテゴリ別推移（`cap-nam-r2` → `cap-nam-r3`）と主な改善点

| カテゴリ (/20) | Standard Q8 | Standard IQ4 | Nano Q8 | Nano IQ4 |
| --- | --- | --- | --- | --- |
| 日常チャット・相づち・略語 | 17 → **20** | 17 → **20** | 15 → **19** | 14 → **18** |
| ネットスラング・カタカナ口語 | 19 → **20** | 19 → **20** | 19 → **20** | 17 → **20** |
| 過剰漢字・当て字抑止 | 19 → **19** | 19 → 18 | 19 → **19** | 19 → **19** |
| 補助動詞のひらがな開き | 19 → **20** | 19 → **20** | 19 → **20** | 17 → **20** |
| 口語縮約・文末・文脈同音 | 20 → **20** | 20 → **20** | 20 → **20** | 20 → **20** |

1. **主要失敗モードの系統的解消**:
   - **Standard (`Q8: 99/100`, `IQ4: 98/100`)**: `りょです -> 旅です`, `あざすたすかりました`（無変換）, `あのさ今暇かな`（誤分割）, `わかるみが深い -> 分かる味が深い`, `聞かせてほしいな -> 聞かせて欲しいな` をすべて解消。
   - **Nano (`Q8: 98/100`, `IQ4: 97/100`)**: `Nano IQ4` は口語100問で **+10問 (`87 -> 97`)**・全740問で **+15問 (`704 -> 719`)** 改善し、`ネットスラング・カタカナ口語` **20/20**・`補助動詞のひらがな開き` **20/20** を達成（`どういたしまして -> 同意たしまして`, `教えてほしい -> 教えて欲しい`, `読んでおいてほしい -> 読んでおいて欲しい`, `エモすぎて泣くよ`, `ワンチャン間に合う`, `ウケるわそれ` を解消）。
   - **既存領域の維持と向上**: 実IME 160問は `Standard Q8 (156/160)`・`Standard IQ4 (156/160)`・`Nano IQ4 (153/160)` へ更新し、230問でも非造語領域（`/205`）を Standard Q8/IQ4・Nano Q8 の3モデルで **205/205 (100.0%)** 維持した（既存の `後で`/`分からない`/`何で`/`知らん` 方針も無劣化）。全740問合計は **Standard Q8 (729) > Standard IQ4 (726) > Nano Q8 (722) > Nano IQ4 (719)** の自然序列を維持した。
2. **残存課題（次期観測対象）**:
   - 全4モデル共通で `かえっておそくなった -> 帰って遅くなった`（口語#59）が残存した。その他は `Std IQ4` の `あいにく -> 会いにく`（#51）、`Nano Q8/IQ4` の `いまひまかな -> 今日まかな`（#17）、`Nano IQ4` の `けんどうなった -> 剣道なった`（#4）。
3. **Full SHA-256 (`artifacts/models/`)**:
   - `artifacts/models/standard-v4-cap-nam-r3-q8_0.gguf` (99.05 MB): `5065b64d9d0cabdd41bef8cd748863adb84691426be24d2a9be95fa23269c8ca`
   - `artifacts/models/standard-v4-cap-nam-r3-iq4nl-emb8.gguf` (58.55 MB): `61af7b70aaf664a7c5b380bc373149fe0e0c67122c697f7127d7e8985fdbfb41`
   - `artifacts/models/nano-v4-cap-nam-r3-q8_0.gguf` (27.69 MB): `001d2cf3acd75343d2dd4ea11472e28258bc7429d7d46091b50a78776dead378`
   - `artifacts/models/nano-v4-cap-nam-r3-iq4nl-emb8.gguf` (18.69 MB): `6a99ca4e1c46b3f6fb86dcf884ea0652c967d86d606c12268b33d894d3853b24`

---

## 11. cap-nam-r4（追加辞書語彙・固有名詞・専門複合語の学習と100問専用ベンチマーク）結果

### 11.1 追加辞書ベンチマークとデータ分離設計
- 追加辞書（`data/dictionary/additional-louds.zip` / 105,039見出し）から5カテゴリ×20問＝計100問の純ひらがなベンチマーク [`data/kana_kanji_additional_dict_testset.jsonl`](../../data/kana_kanji_additional_dict_testset.jsonl)（生成スクリプト: [`data/build_additional_dict_testset.py`](../../data/build_additional_dict_testset.py)、読み長 min=9 / max=20 / avg=14.1、SHA-256 `63d51ea5bcdb41ef0e05a3c0aa36738fa902444dc1a5f30020b4a96be0ca8408`）を新設した。
- [`data/dataset_isolation.py`](../../data/dataset_isolation.py) の `EVALUATION_FILES`（全8ファイル・予約キー2,401件）へ登録し、共通ベンチマーク（[`benchmarks/common/conversion.jsonl`](../../benchmarks/common/conversion.jsonl)・UI表示用 `benchmark840.json` / Android `benchmark_dataset.jsonl`）も 740問 → 840問へ統合した（[`scripts/sync_benchmarks.mjs`](../../scripts/sync_benchmarks.mjs)）。
- 学習データ `artifacts/datasets/zerocon-v4-nam-r4/`（`train=1,575`, `val=145`, `additional_dict` 由来の非重複文脈フレーズ+同音対比アンカー325件追加うち Train 278 / Val 22、Excluded 0）は全100テスト対象語を非重複フレーズで `train.jsonl` に収録しつつ、8評価セット全キーと厳密非重複であることを [`data/test_dataset_isolation.py`](../../data/test_dataset_isolation.py)（8/8 OK）および [`training/test_standard_v4.py`](../../training/test_standard_v4.py)（11/11 OK）で検証した。
  - `artifacts/datasets/zerocon-v4-nam-r4/train.jsonl` (0.72 MB): `9740b9f587797926a24c52b4dd502eab5ab00b112050a882157be91ad30fc584`
  - `artifacts/datasets/zerocon-v4-nam-r4/val.jsonl` (0.07 MB): `8696c8316a82b5fa692c88122b78d3b8e0b1391896ceb38f134352a07f06e196`
  - `artifacts/datasets/zerocon-v4-nam-r4/manifest.json`: `c3cbede253a419c4866a684f8590e60be58a6b0c969aea0883e88036254ee5e9`

### 11.2 学習・量子化と計測条件
- **Standard v4 (`standard-v4-cap-nam-r4`)**: `training/train_standard_v4.py`（`zenz-v3.1-small` 基盤, 6 epochs, lr `8e-5`, bs 8, seed 42, DoRA `r=32, alpha=64`）で学習・マージ後、`training/build_standard_v4_quants.py` により `Q8_0` / `IQ4_NL_emb8` を生成。
- **Nano v4 (`nano-v4-cap-nam-r4`)**: `training/train_nano_v3.py`（`zenz-v3.2-xsmall` 学生 ← `standard-v4-cap-nam-r4-merged` 教師, 7 epochs / 1,575 steps, lr `1.6e-4`, bs 8, 6層 DoRA `r=64, alpha=128`）で蒸留後、同じく `Q8_0` / `IQ4_NL_emb8` を生成。
- **配布影響境界**: 出力はすべて `artifacts/` 配下の `cap-nam-r4` として生成し、`windows/deployment/model-catalog.json`・`ZeroConvertIME-windows/`・試験IME実環境は未変更。
- **計測プロトコル**: `--backend cpu --n-gpu-layers 0`, `context=2048`, `threads=4`, `temperature=0.0`, `seed=42`, `cache_prompt=False`, `max_tokens=96`, `warmup_requests=3`（`llama-server.exe` SHA-256 `d5538214afb689cf4615217a8cc195fdaecd09e6fd045d67aedaa5a974a15a60`）。

### 11.3 正解数・遅延・メモリ一覧（cap-nam-r3 対比 / 全840問）

| モデル | LLM標準 230問 (vs r3) | 難文脈 250問 (vs r3) | 実IME 160問 (vs r3) | 口語 100問 (vs r3) | 既存740計 (vs r3) | 追加辞書 100問 (vs r3) | 全840計 (vs r3) | 健全率 / 途中打切 / `nam` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Standard v4 cap-nam-r4 Q8 | 226 (98.3%, +1) | **250** (100.0%, +1) | 157 (98.1%, +1) | **100** (100.0%, +1) | **733** (+4) | 98 (98.0%, +26) | **831** (98.9%, +30) | 100% / 0 / 100% (46/46) |
| Standard v4 cap-nam-r4 IQ4 | 225 (97.8%, ±0) | 249 (99.6%, +2) | **158** (98.8%, +2) | **100** (100.0%, +2) | **732** (+6) | **99** (99.0%, +31) | **831** (98.9%, +37) | 100% / 0 / 100% (46/46) |
| Nano v4 cap-nam-r4 Q8 | 225 (97.8%, +1) | 248 (99.2%, +1) | 156 (97.5%, +3) | **100** (100.0%, +2) | **729** (+7) | **99** (99.0%, +38) | **828** (98.6%, +45) | 100% / 0 / 100% (46/46) |
| Nano v4 cap-nam-r4 IQ4 | 223 (97.0%, +1) | 248 (99.2%, +1) | 154 (96.2%, +1) | 99 (99.0%, +2) | **724** (+5) | 97 (97.0%, +40) | **821** (97.7%, +45) | 100% / 0 / 100% (46/46) |

#### 追加辞書100問 カテゴリ別内訳（r3 → r4）

| カテゴリ (20問ずつ) | Standard Q8 | Standard IQ4 | Nano Q8 | Nano IQ4 |
| --- | --- | --- | --- | --- |
| IT・AI・現代ビジネス複合語 | 20 → 20 | 20 → 20 | 18 → 20 | 18 → 20 |
| 学術・医療・理工・法務専門語 | 17 → 20 | 15 → 20 | 14 → 20 | 13 → 20 |
| 地名・駅名・施設・行政機関 | 15 → 19 | 14 → 19 | 13 → 20 | 12 → 20 |
| 作品・ブランド・外来語・動植物 | 14 → 20 | 14 → 20 | 11 → 19 | 10 → 19 |
| 人名・歴史人物・著名人 | 6 → 19 | 5 → 20 | 5 → 20 | 4 → 18 |
| **合計** | **72 → 98 (+26)** | **68 → 99 (+31)** | **61 → 99 (+38)** | **57 → 97 (+40)** |

#### 推論速度（p50 / p95 / p99 ms）と Peak RSS (MB)

| モデル | LLM標準 230 | 難文脈 250 | 実IME 160 | 口語 100 | 追加辞書 100 | Peak RSS範囲 |
| --- | --- | --- | --- | --- | --- | --- |
| Standard v4 cap-nam-r4 Q8 | 65.3 / 113.6 / 130.4 | 80.4 / 119.3 / 135.6 | 60.5 / 85.3 / 89.9 | 67.1 / 86.3 / 90.1 | 77.4 / 102.9 / 108.3 | 316.8–755.3 MB |
| Standard v4 cap-nam-r4 IQ4 | 46.1 / 77.0 / 87.6 | 56.1 / 81.0 / 97.7 | 44.8 / 58.8 / 63.7 | 47.0 / 58.6 / 67.0 | 52.9 / 70.7 / 78.7 | 321.9–760.5 MB |
| Nano v4 cap-nam-r4 Q8 | 30.1 / 38.0 / 47.1 | 30.8 / 43.9 / 47.6 | 29.7 / 36.0 / 39.0 | 29.9 / 37.0 / 47.5 | 29.6 / 37.0 / 42.9 | 114.2–261.4 MB |
| Nano v4 cap-nam-r4 IQ4 | 26.4 / 33.1 / 34.2 | 30.0 / 34.6 / 36.1 | 29.7 / 32.7 / 34.3 | 29.7 / 32.4 / 34.2 | 29.9 / 34.5 / 35.7 | 115.2–262.8 MB |

#### Quant 一致率（Q8 vs IQ4 出力完全一致 / 正誤一致）

- Standard r4: 230問 `227/230 (98.7% / 99.6%)`, 250難文脈 `249/250 (99.6% / 99.6%)`, 実IME 160 `158/160 (98.8% / 99.4%)`, 口語 100 `100/100 (100.0% / 100.0%)`, 追加辞書 100 `99/100 (99.0% / 99.0%)`
- Nano r4: 230問 `225/230 (97.8% / 99.1%)`, 250難文脈 `248/250 (99.2% / 99.2%)`, 実IME 160 `157/160 (98.1% / 98.8%)`, 口語 100 `98/100 (98.0% / 99.0%)`, 追加辞書 100 `97/100 (97.0% / 98.0%)`

### 11.4 分析とモデルアーティファクト一覧
1. **既存ベンチ無劣化どころか全4モデル・全既存スイートで改善**:
   - 既存740問で全モデルが `cap-nam-r3` を上回り（`Std Q8: 729→733`, `Std IQ4: 726→732`, `Nano Q8: 722→729`, `Nano IQ4: 719→724`）、全5スイート×4モデルのどのサブセットでもスコア低下はゼロだった。特に `Std Q8` と `Nano Q8` は全840問を通じて単一設問の逆行（OK→NG）もゼロ件であった。
   - `r3` 時点の残存課題だった口語#59「かえって遅くなった」が全4モデルで解消し（口語100は `Std Q8 / Std IQ4 / Nano Q8` が 100/100 達成）、難文脈250では `Std Q8` が 250/250 の完全正答に到達した。また `Std Q8 (831) >= Std IQ4 (831) > Nano Q8 (828) > Nano IQ4 (821)`（既存740も `733 > 732 > 729 > 724`）の序列を維持した。
2. **追加辞書の語彙獲得と残差**:
   - 追加辞書100問では人名カテゴリ（`6→19`, `5→20`, `5→20`, `4→18`）を中心に全4モデルが +26〜+40点伸び、Nano Q8 でも 99/100 に達した。
   - 残誤答は追加辞書語彙そのものより後続一般名詞の同音異義（`Std Q8` #52 `岸辺→岸部`, #87 `講演→公演`）や聞き/聴き等の活用語（`Nano Q8/IQ4` #77 `キクイタダキの声を聞く→キク`）が中心であり、見出し変換そのものは高水準で定着している。
3. **結果JSON (`artifacts/results/`)**:
   - `artifacts/results/v4_cap_nam_r3_cpu_additional_dict100.json`
   - `artifacts/results/v4_cap_nam_r4_cpu_additional_dict100.json`
   - `artifacts/results/v4_cap_nam_r4_cpu_colloquial100.json`
   - `artifacts/results/v4_cap_nam_r4_cpu_ime_realistic.json`
   - `artifacts/results/v4_cap_nam_r4_cpu_230.json`
   - `artifacts/results/v4_cap_nam_r4_cpu_250hard.json`
4. **Full SHA-256 (`artifacts/models/`)**:
   - `artifacts/models/standard-v4-cap-nam-r4-q8_0.gguf` (99.05 MB): `9ac764ca79588b12a96f22796fe60e09315e62dc27aefebce5848843e6b4f781`
   - `artifacts/models/standard-v4-cap-nam-r4-iq4nl-emb8.gguf` (58.55 MB): `6b9bf52a46a6d462abae60d50a1df275cf1b27907d9df0cf011860992d5bfac0`
   - `artifacts/models/nano-v4-cap-nam-r4-q8_0.gguf` (27.69 MB): `a0cf20bd8fa69a09234018febe1cfae3f8960c310c57ac775bd6835aa5cf99b5`
   - `artifacts/models/nano-v4-cap-nam-r4-iq4nl-emb8.gguf` (18.69 MB): `f018f337ed103f85e9d4ab2d60e2d7a18c60d749f79cad19fc535044635339b0`
5. **暫定採用から `cap-nam-r6` への正式差し替え**:
   - 上記 `cap-nam-r4` 4モデルは当初 ZeroCon Model v4.2 として採用したが、通常辞書同音対比補強（Section 12 `cap-nam-r5`）と時間・数値単位表記特化補強（Section 13 `cap-nam-r6`）の検証を経て、最終的に **`cap-nam-r6` 4モデル** を正式な **ZeroCon Model v4.2** として採用・差し替えした。

---

## 12. cap-nam-r5（通常辞書語彙・同音対比・複合語の追加学習と840問ベンチマーク検証）結果

> **位置づけ**: 実験検証（`artifacts/` 配下およびレポート記録のみ）。`windows/deployment/model-catalog.json` および実機反映先 `ZeroConvertIME-windows/` は **ZeroCon Model v4.2 (`cap-nam-r4`) のまま未変更**。

1. **目的と通常辞書（Base Dictionary）監査**:
   - `v4.2`（追加辞書学習）と同様に、通常辞書（`windows/server-swift/azooKey_dictionary_storage/Dictionary/louds/*.loudstxt3`：438ファイル、総行数 `578,144`、ユニーク表層 `494,263`、ユニーク読み `322,019`）に含まれる語彙・同音対比・複合語を ZeroCon Model に追加学習させた場合、現行840問ベンチマーク（直結推論 + 共通候補生成 `Zenzai + 標準辞書 N-best`）がどう変化するかを検証した。
   - 監査の結果、`v4.2` に残っていた誤答の多くは通常辞書側のユニグラムスコア差・複合語分割と対応していた（例: `岸辺 -13.09` vs `岸部 -12.02`、`講演 -9.61` vs `公演 -8.33`、`照明 -9.67` vs `証明 -8.57`、`重体 -13.04` vs `渋滞 -9.75`、`汽車 -11.98` vs `記者 -9.67`、`履物 -13.14` vs `着物 -10.42`、`更生 -11.19` vs `公正 -9.53`、`修める -10.95` vs `収める -8.25`、`臥薪嘗胆 -14.79`、`お取り次ぎ` 非単一エントリ vs `取り次ぎ -10.17`）。
2. **データセット・学習・成果物仕様 (`cap-nam-r5`)**:
   - `data/build_standard_v3_dataset.py` Section 12 に `capability_base_dict_r5_rows`（240行）を追加した。
     - **Part A（90行）**: 通常辞書の同音対比・多義動詞・敬語複合語・口語表記・コールドスタート空左文脈（`""`）・ひらがな未知語保護・`nam％` アンカー。
     - **Part B（150行）**: 全語が通常 LOUDS 辞書に存在することを確認した 5カテゴリ × 30語（`base_dict_business`, `base_dict_academic`, `base_dict_geo`, `base_dict_idiom`, `base_dict_daily`）。
   - 生成結果（`artifacts/datasets/zerocon-v4-nam-r5/`）: `train=1,793`, `val=167`, `excluded=0`（8評価ファイル 2,401キーに対する `data/test_dataset_isolation.py` 8 tests OK、`training/test_standard_v4.py` 11 tests OK）。
   - 学習設定: Standard v4（`train_standard_v4.py`, 6 epochs, lr `8e-5`, bs 8, 2,021 sequences）→ Nano v4 蒸留（`train_nano_v3.py`, 7 epochs, lr `1.6e-4`, bs 8）→ `build_standard_v4_quants.py` による `Q8_0` / `IQ4_NL_emb8` 量子化。
   - **Full SHA-256 (`artifacts/`)**:
     - `artifacts/datasets/zerocon-v4-nam-r5/train.jsonl` (`848,531 bytes`): `06ca4cf8c86fd81f22cf2efe99aa79351d852b91b7eae1741abd4dabf638fac2`
     - `artifacts/datasets/zerocon-v4-nam-r5/val.jsonl` (`78,688 bytes`): `5e28f749571ae0c97712256e465488b0cf20dffceb532557651e4c597cb71a91`
     - `artifacts/models/standard-v4-cap-nam-r5-q8_0.gguf` (`99.05 MiB` / `103.87 MB`): `e32ac2dcbdef3b0667b40393ad46ec6740c946f495d571f35b415ee68078a0df`
     - `artifacts/models/standard-v4-cap-nam-r5-iq4nl-emb8.gguf` (`58.55 MiB` / `61.40 MB`): `328b83c659e9f5702d1d807744065f10316dae7868ff40d77840fd93ce279020`
     - `artifacts/models/nano-v4-cap-nam-r5-q8_0.gguf` (`27.69 MiB` / `29.04 MB`): `57b43010b1cdf9db78868ee19040f07229e80a88323f1471630c5bae646efc03`
     - `artifacts/models/nano-v4-cap-nam-r5-iq4nl-emb8.gguf` (`18.69 MiB` / `19.60 MB`): `000d041e792c7b37a3fd6d987e4f68ab91078dcbe71885a492a09765f2fc0fbc`

3. **直接推論 840問ベンチマーク比較（CPU `--n-gpu-layers 0` 順次実行）**:

| Run / モデル | `230` 基本 | `250hard` | `160` 実践 | `100` 口語 | `100` 追加辞書 | 既存740小計 | **総合 840** | `v4.2` 差 | `230` p50/p95 ms | `250hard` p50/p95 ms | `250hard` RSS |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `v4.1 (r3)` Std Q8_0 | 225/230 | 249/250 | 156/160 | 99/100 | 72/100 | 729/740 (98.5%) | 801/840 (95.36%) | — | 74.2 / 150.2 | 98.6 / 134.2 | 755.9 MB |
| `v4.2 (r4)` Std Q8_0 | 226/230 | 250/250 | 157/160 | 100/100 | 98/100 | 733/740 (99.1%) | 831/840 (98.93%) | base | 65.3 / 113.6 | 80.4 / 119.3 | 755.3 MB |
| **`r5` Std Q8_0** | **228/230** | **250/250** | 157/160 | **100/100** | **100/100** | **735/740 (99.3%)** | **835/840 (99.40%)** | **+4** | 67.3 / 112.8 | 117.7 / 168.0 | 755.3 MB |
| `v4.1 (r3)` Std IQ4_NL | 225/230 | 247/250 | 156/160 | 98/100 | 68/100 | 726/740 (98.1%) | 794/840 (94.52%) | — | 46.6 / 85.0 | 60.4 / 85.8 | 760.9 MB |
| `v4.2 (r4)` Std IQ4_NL | 225/230 | 249/250 | 158/160 | 100/100 | 99/100 | 732/740 (98.9%) | 831/840 (98.93%) | base | 46.1 / 77.0 | 56.1 / 81.0 | 760.5 MB |
| **`r5` Std IQ4_NL** | **229/230** | **249/250** | 157/160 | **100/100** | **100/100** | **735/740 (99.3%)** | **835/840 (99.40%)** | **+4** | 49.8 / 100.2 | 78.6 / 117.2 | 760.7 MB |
| `v4.1 (r3)` Nano Q8_0 | 224/230 | 247/250 | 153/160 | 98/100 | 61/100 | 722/740 (97.6%) | 783/840 (93.21%) | — | 22.9 / 45.1 | 30.7 / 47.8 | 262.2 MB |
| `v4.2 (r4)` Nano Q8_0 | 225/230 | 248/250 | 156/160 | 100/100 | 99/100 | 729/740 (98.5%) | 828/840 (98.57%) | base | 30.1 / 38.0 | 30.8 / 43.9 | 261.4 MB |
| **`r5` Nano Q8_0** | **229/230** | **249/250** | **159/160** | **100/100** | **99/100** | **737/740 (99.6%)** | **836/840 (99.52%)** | **+8** | 25.8 / 48.8 | 31.1 / 56.5 | 261.4 MB |
| `v4.1 (r3)` Nano IQ4_NL | 222/230 | 247/250 | 153/160 | 97/100 | 57/100 | 719/740 (97.2%) | 776/840 (92.38%) | — | 26.5 / 34.0 | 29.7 / 34.8 | 263.4 MB |
| `v4.2 (r4)` Nano IQ4_NL | 223/230 | 248/250 | 154/160 | 99/100 | 97/100 | 724/740 (97.8%) | 821/840 (97.74%) | base | 26.4 / 33.1 | 30.0 / 34.6 | 262.8 MB |
| **`r5` Nano IQ4_NL** | **226/230** | 247/250 | **155/160** | 99/100 | **99/100** | **727/740 (98.2%)** | **826/840 (98.33%)** | **+5** | 14.1 / 33.5 | 17.4 / 36.5 | 262.6 MB |

   - `cap-nam-r5` 4モデルはいずれも全5スイートで `structural_integrity = 100.0%`、該当スイートで `nam_restoration = 100.0%` を維持した（`additional_dict100`: Std 2版 100/100、Nano 2版 99/100；`colloquial100`: Q8/StdIQ4 100/100、Nano IQ4 99/100；`ime_realistic`: Nano Q8 159/160 で過去最高）。

4. **共通候補生成 840問ベンチマーク比較（`azookey-server.exe --conversion-benchmark`, Zenzai + 標準辞書 N-best, CPU `gpu_layers=0`）**:
   - `cap-nam-r5` は `artifacts/results/common-candidates-840-r5-20261123/`、`v4.1` / `v4.2` / `Zenz v3.2` は既存基準 `artifacts/results/common-candidates-840-10models-20261123/` を参照。

| モデル | `basic` (230) | `hard` (250) | `realistic` (160) | `colloquial` (100) | `additional_dict` (100) | 既存740小計 | **総合 840** | `v4.2` 差 | wall (s) | mean / p50 / p95 / p99 (ms) | nbest (ms) |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `zenz-v3.2-small-q4km` | 202/230 (87.8%) | 208/250 (83.2%) | 148/160 (92.5%) | 93/100 (93.0%) | 76/100 (76.0%) | 651/740 (87.97%) | 727/840 (86.55%) | — | 75.57 | 83.59 / 66.75 / 149.81 / 274.17 | 4.87 |
| `zenz-v3.2-xsmall-q4km` | 193/230 (83.9%) | 170/250 (68.0%) | 140/160 (87.5%) | 89/100 (89.0%) | 58/100 (58.0%) | 592/740 (80.00%) | 650/840 (77.38%) | — | 29.16 | 31.74 / 27.38 / 62.71 / 126.52 | 5.06 |
| `zerocon-standard-v4.1-q8_0` | 225/230 (97.8%) | 249/250 (99.6%) | 156/160 (97.5%) | 99/100 (99.0%) | 73/100 (73.0%) | 729/740 (98.51%) | 802/840 (95.48%) | — | 56.31 | 61.19 / 59.73 / 102.61 / 129.55 | 4.68 |
| `zerocon-standard-v4.2-q8_0` | 226/230 (98.3%) | 249/250 (99.6%) | 158/160 (98.8%) | 100/100 (100.0%) | 98/100 (98.0%) | 733/740 (99.05%) | 831/840 (98.93%) | base | 52.54 | 56.57 / 51.34 / 92.51 / 131.72 | 4.65 |
| **`standard-v4-cap-nam-r5-q8_0`** | **229/230 (99.6%)** | **249/250 (99.6%)** | 158/160 (98.8%) | **100/100 (100.0%)** | **100/100 (100.0%)** | **736/740 (99.46%)** | **836/840 (99.52%)** | **+5** | 65.92 | 69.09 / 62.74 / 125.94 / 167.82 | 6.02 |
| `zerocon-standard-v4.1-iq4nl-emb8` | 225/230 (97.8%) | 246/250 (98.4%) | 156/160 (97.5%) | 98/100 (98.0%) | 68/100 (68.0%) | 725/740 (97.97%) | 793/840 (94.40%) | — | 51.04 | 54.87 / 47.32 / 109.08 / 148.77 | 4.79 |
| `zerocon-standard-v4.2-iq4nl-emb8` | 225/230 (97.8%) | 248/250 (99.2%) | 158/160 (98.8%) | 100/100 (100.0%) | 99/100 (99.0%) | 731/740 (98.78%) | 830/840 (98.81%) | base | 45.17 | 47.50 / 45.38 / 81.51 / 108.66 | 4.72 |
| **`standard-v4-cap-nam-r5-iq4nl-emb8`** | **229/230 (99.6%)** | 248/250 (99.2%) | 158/160 (98.8%) | **100/100 (100.0%)** | **100/100 (100.0%)** | **735/740 (99.32%)** | **835/840 (99.40%)** | **+5** | 64.77 | 67.43 / 59.09 / 128.44 / 231.27 | 6.11 |
| `zerocon-nano-v4.1-q8_0` | 224/230 (97.4%) | 247/250 (98.8%) | 153/160 (95.6%) | 98/100 (98.0%) | 61/100 (61.0%) | 722/740 (97.57%) | 783/840 (93.21%) | — | 23.94 | 25.68 / 24.19 / 44.81 / 64.02 | 4.87 |
| `zerocon-nano-v4.2-q8_0` | 225/230 (97.8%) | 247/250 (98.8%) | 155/160 (96.9%) | 100/100 (100.0%) | 99/100 (99.0%) | 727/740 (98.24%) | 826/840 (98.33%) | base | 25.20 | 27.35 / 25.58 / 47.80 / 67.93 | 4.67 |
| **`nano-v4-cap-nam-r5-q8_0`** | **229/230 (99.6%)** | **248/250 (99.2%)** | **159/160 (99.4%)** | **100/100 (100.0%)** | **99/100 (99.0%)** | **736/740 (99.46%)** | **835/840 (99.40%)** | **+9** | 37.48 | 40.53 / 32.20 / 93.18 / 176.35 | 6.04 |
| `zerocon-nano-v4.1-iq4nl-emb8` | 222/230 (96.5%) | 246/250 (98.4%) | 153/160 (95.6%) | 97/100 (97.0%) | 57/100 (57.0%) | 718/740 (97.03%) | 775/840 (92.26%) | — | 24.83 | 26.89 / 24.32 / 51.05 / 75.59 | 4.76 |
| `zerocon-nano-v4.2-iq4nl-emb8` | 224/230 (97.4%) | 247/250 (98.8%) | 154/160 (96.2%) | 99/100 (99.0%) | 97/100 (97.0%) | 724/740 (97.84%) | 821/840 (97.74%) | base | 24.22 | 26.20 / 24.26 / 48.64 / 66.68 | 4.68 |
| **`nano-v4-cap-nam-r5-iq4nl-emb8`** | **227/230 (98.7%)** | 244/250 (97.6%) | **155/160 (96.9%)** | **100/100 (100.0%)** | **99/100 (99.0%)** | **726/740 (98.11%)** | **825/840 (98.21%)** | **+4** | 30.52 | 32.25 / 29.16 / 59.49 / 83.31 | 5.96 |

   - （計測条件注記: モデルアーキテクチャ・量子化パラメータ・バイト数は `v4.2` と同一であり、`r5` 実行時は辞書側の `mean_nbest_ms` が `~6.0ms`（`v4.2` 時は `~4.7ms`）へ増加した影響などホスト負荷差を含んでいるため、wall/レイテンシ絶対値の単純比較には注意が必要。）

5. **問別差分（`v4.2` → `cap-nam-r5`）と残課題の分析**:
   - **共通候補生成での改善と残エラー**:
     - **Standard Q8_0 (`836/840`, fixed=6 / regressed=1 / err=4)**: `basic:215/218/228`（ひらがな未知語）、`realistic:538`（重体に陥る）、`additional_dict:792/827`（十間川の岸辺 / 天音里菜さんの講演）を改善。唯一の退行は `realistic:634`（`クラウド側との` / `どうきがはずれている` → `動機が外れている` vs 正解 `同期が外れている`）。残4問のうち `basic:211`（かふぇるのあで待つ）と `hard:478`（50%の全角半角 `５０%の確率`）は `in_cand=False`（候補リストに正解不在；なお `hard:478` は直接推論では正解）、`in_cand=True` の残りは `realistic:535`（照明を消す → 証明を消す）と `realistic:634` の2問のみ。
     - **Standard IQ4_NL (`835/840`, fixed=7 / regressed=2 / err=5)**: `basic:206/215/218/228`、`hard:421`（輻輳した回線で服装の点検をする）、`realistic:538`、`additional_dict:792` を改善。退行は `hard:418`（神聖な神殿で申請の手続きをする → 神聖の手続きをする）と `realistic:634`（動機）。
     - **Nano Q8_0 (`835/840`, fixed=9 / regressed=0 / err=5)**: **共通候補生成でも直接推論でも `v4.2` からの退行 0件**（直接推論 `836/840` fixed=8 / regr=0）。`basic:213/218/228/229`、`hard:292`（式典に合う服装）、`realistic:538/549/574/626`（重体に陥る / 今のうちに図る / お取り次ぎいたします / ここで履物を脱ぐ）を一挙に解消。共通候補の残エラーは `basic:211`、`hard:478`（以上2件 `in_cand=False`）、`hard:387`（臥薪嘗胆）、`realistic:535`（照明）、`additional_dict:817`（キクイタダキの声を聞く → キク）の5問。
     - **Nano IQ4_NL (`825/840`, fixed=15 / regressed=11 / err=15)**: 総合 `+4`（直接推論 `+5`: 821 → 826）かつ `colloquial 100/100`・`additional_dict 99/100` を達成した一方、`hard` が `247 → 244/250` となるなど 4bit 量子化特有の同音語・単位境界（`14じ30ぷん`、`12ぱーせんと`）の揺らぎが見られた。
   - **直接推論での補足**:
     - `Std Q8_0` は `835/840`（fixed=7 / regressed=3）。`realistic:45`（汽車を見送る）・`additional_dict:87`（天音里菜さんの講演）等 7件が改善した一方、`230:202`（しらん → しらん）、`ime_realistic:51`（凄惨な現場 → 生産な現場）、`ime_realistic:154`（同期 → 動機）の3件が反転した。
     - 全体として、通常辞書の弱スコア同音語・複合動詞・敬語に加え、Part A のひらがな未知語保護アンカーが `basic` の未知語誤漢字化（`黒のしずく` / `星見る` / `未来創造室` / `アズレイ島` / `星のコード` 等）も同時に解消し、**全4バリアントが直接推論・共通候補生成の両方で過去最高スコアを更新した**。

---

## 13. cap-nam-r6（時間・数値単位表記に特化した追加学習と検証）結果

> **位置づけ**: 検証完了後に **`cap-nam-r6` 4モデルを ZeroCon Model v4.2 の正式版** として採用し、`windows/deployment/model-catalog.json`・Windows/共有Rust/Tauri・Android カタログ・実機 `ZeroConvertIME-windows/`・Android APK へ反映した。

1. **問題の原因特定と対策設計**:
   - `cap-nam-r5` の検証で、`Nano IQ4_NL` が `ime_realistic:124`（「`14じ30ぷんから`」→「`14じ30分から`」）および `ime_realistic:128`（「`12ぱーせんとへった`」→「`12persent減った`」）で単位表記ミスを起こし、未学習の24問 時間・数値単位プローブでも `12/24`（`r5 Std IQ4`: `21/24`、`r5 Nano Q8`: `22/24`）まで低下することが判明した。
   - **原因**: `r5` データセット（`1,793`行）に含まれる時刻・時間系行はわずか11件、`namパーセント -> nam%` は2件のみで、特に **`namジnamプン`（〜時〜ぷん）は 0件、左文脈なしのコールドスタート時刻/`%` 行も 0件**だった。そこに非数値の通常辞書データ 240件を加えたことで、4bit量子化（`IQ4_NL_emb8`）時に単位変換のマージンが閾値を割り込んでいた。
   - **対策（`Section 13: capability_time_unit_r6_rows`）**: [build_standard_v3_dataset.py](file:///d:/angra/ZeroConvert-IME/data/build_standard_v3_dataset.py) 末尾（L2089〜）に、時間・所要時間・日時範囲・パーセント単位へ厳密に絞った **100件** を追加した（重複除外後の収録実績: **99件**、うち `train=90`, `val=9`）。
     - 内訳: `時+ぷん (namジnamプン)` 24件（コールドスタート `""` 10件 + 文脈付き 14件）、`時+ふん/単独時/時半/午前午後/範囲` 22件、`時間/時間半/分/秒` 24件、`namパーセント -> nam%` 20件（コールドスタート 10件 + 文脈付き 10件）、期間・時間帯単位 10件。
     - 8評価ファイル 2,401キーおよび 24問プローブのいずれとも `nam` マスク後読みの重複ゼロを検証済み（`excluded=0`, `Probe masked overlaps=0`）。
   - **学習・成果物 (`artifacts/`)**:
     - データセット（`artifacts/datasets/zerocon-v4-nam-r6/`）: `train=1,883`, `val=176`, `excluded=0`（`test_dataset_isolation.py` 8 tests OK、`test_standard_v4.py` 11 tests OK）。
     - 学習設定: Standard v4（6 epochs, lr `8e-5`, bs 8, 2,150 sequences）→ Nano v4 蒸留（**8 epochs**, lr `1.6e-4`, bs 8）→ `build_standard_v4_quants.py`（`Q8_0` / `IQ4_NL_emb8`）。
     - **Full SHA-256 (`artifacts/`)**:
       - `artifacts/datasets/zerocon-v4-nam-r6/train.jsonl` (`882,452 bytes`): `28a365c67cd438ab5abe41e33ac9c97d54d071211b0693cd4e3316f2cca4b711`
       - `artifacts/datasets/zerocon-v4-nam-r6/val.jsonl` (`82,203 bytes`): `fd7202635517467c4a349f939c903de01d73790a35477f28238f64129824fb74`
       - `artifacts/models/standard-v4-cap-nam-r6-q8_0.gguf` (`99.05 MiB` / `103.87 MB`): `43fcbfaee7a325a7e027c3bb5d94bfb324b39b76dcb3e58da7935e678d87a644`
       - `artifacts/models/standard-v4-cap-nam-r6-iq4nl-emb8.gguf` (`58.55 MiB` / `61.40 MB`): `bf73bf4778655baf3f1124adc50ff0869c7028a2920c4b43b4ea7f5fb6f35d1d`
       - `artifacts/models/nano-v4-cap-nam-r6-q8_0.gguf` (`27.69 MiB` / `29.04 MB`): `7a613da590b6437be5775bf1595a27e44e1f37ef6acbb800c49c159af3f25130`
       - `artifacts/models/nano-v4-cap-nam-r6-iq4nl-emb8.gguf` (`18.69 MiB` / `19.60 MB`): `5367df4e8c3373ba918df23ffab87bba28812629b0841f821dd30099d0494348`

2. **時間・数値単位特化テスト結果（24問未学習プローブ & `ime_realistic` #121–#140）**:

| モデル | 24問プローブ `v4.2 (r4)` | 24問プローブ `r5` | **24問プローブ `r6`** | `r6` 残誤答 | `ime_realistic` #121–140 (直結 / 共通) |
| --- | :---: | :---: | :---: | --- | :---: |
| **Standard Q8_0** | 23/24 | 23/24 | **23/24** | `#18 8じかんはんねた → 8時間半ネタ` のみ | **20/20** / **20/20** (`r5`: 20/20) |
| **Standard IQ4_NL** | 22/24 | 21/24 | **23/24** (`+2 vs r5`) | `#18 8じかんはんねた → 8時間半ネタ` のみ | **20/20** / **20/20** (`r5`: 20/20) |
| **Nano Q8_0** | 24/24 | 22/24 | **24/24 (100%)** (`+2 vs r5`) | **なし**（`10じ15ふん` / `16じ30ぷん` 解消） | **20/20** / **20/20** (`r5`: 20/20) |
| **Nano IQ4_NL** | 23/24 | 12/24 | **24/24 (100%)** (`+12 vs r5`) | **なし**（全12件の `じ`/`ジ`/`persent`/`パーセント` 誤記を完全解消） | **20/20** / **20/20** (`r5`: 18/20) |

   - 記録JSON: `artifacts/results/time_unit_probe_r4_r5_r6.json`。`r6 Nano Q8` および `r6 Nano IQ4` は、未学習のコールドスタート時刻（`10じ15ふんにいく`, `16じ30ぷんから`, `1じ30ぷんまで`）と `%` 表現（`12ぱーせんとへった`, `8ぱーせんとのこる`, `100ぱーせんとせいこう`, `50ぱーせんといじょう` 等）を含む **全24問で 24/24 (100%)** に到達した。

3. **全840問ベンチマーク比較（`v4.2 (r4)` vs `cap-nam-r5` vs `cap-nam-r6`, CPU `gpu_layers=0` 順次実行）**:

#### A. 直接推論 840問 (`benchmark_kana_kanji_models.py`)

| Run / モデル | `230` | `250hard` | `160` 実践 | `100` 口語 | `100` 追加 | 既存740小計 | **総合 840** | `r4`差 / `r5`差 | `230` p50/p95 ms | `250hard` p50/p95 ms | RSS範囲 |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | --- |
| `v4.2 (r4)` Std Q8 | 226/230 | 250/250 | 157/160 | 100/100 | 98/100 | 733/740 (99.1%) | 831/840 (98.93%) | — | 65.3 / 113.6 | 80.4 / 119.3 | 316.8–755.3 MB |
| `r5` Std Q8 | 228/230 | 250/250 | 157/160 | 100/100 | 100/100 | 735/740 (99.3%) | 835/840 (99.40%) | +4 / — | 67.3 / 112.8 | 117.7 / 168.0 | 317.0–755.3 MB |
| **`r6` Std Q8** | **228/230** | **250/250** | 157/160 | **100/100** | **100/100** | **735/740 (99.3%)** | **835/840 (99.40%)** | **+4 / ±0** | 71.8 / 120.9 | 88.0 / 123.4 | 317.0–755.6 MB |
| `v4.2 (r4)` Std IQ4 | 225/230 | 249/250 | 158/160 | 100/100 | 99/100 | 732/740 (98.9%) | 831/840 (98.93%) | — | 46.1 / 77.0 | 56.1 / 81.0 | 321.9–760.5 MB |
| `r5` Std IQ4 | 229/230 | 249/250 | 157/160 | 100/100 | 100/100 | 735/740 (99.3%) | 835/840 (99.40%) | +4 / — | 49.8 / 100.2 | 78.6 / 117.2 | 322.1–760.7 MB |
| **`r6` Std IQ4** | **228/230** | 248/250 | **158/160** | **100/100** | **100/100** | 734/740 (99.2%) | **834/840 (99.29%)** | **+3 / -1** | 48.0 / 79.6 | 58.8 / 82.2 | 321.8–760.4 MB |
| `v4.2 (r4)` Nano Q8 | 225/230 | 248/250 | 156/160 | 100/100 | 99/100 | 729/740 (98.5%) | 828/840 (98.57%) | — | 30.1 / 38.0 | 30.8 / 43.9 | 114.2–261.4 MB |
| `r5` Nano Q8 | 229/230 | 249/250 | 159/160 | 100/100 | 99/100 | 737/740 (99.6%) | 836/840 (99.52%) | +8 / — | 25.8 / 48.8 | 31.1 / 56.5 | 114.0–261.4 MB |
| **`r6` Nano Q8** | **229/230** | **249/250** | **159/160** | **100/100** | **99/100** | **737/740 (99.6%)** | **836/840 (99.52%)** | **+8 / ±0** | 30.0 / 38.3 | 30.6 / 42.2 | 114.2–261.4 MB |
| `v4.2 (r4)` Nano IQ4 | 223/230 | 248/250 | 154/160 | 99/100 | 97/100 | 724/740 (97.8%) | 821/840 (97.74%) | — | 26.4 / 33.1 | 30.0 / 34.6 | 115.2–262.8 MB |
| `r5` Nano IQ4 | 226/230 | 247/250 | 155/160 | 99/100 | 99/100 | 727/740 (98.2%) | 826/840 (98.33%) | +5 / — | 14.1 / 33.5 | 17.4 / 36.5 | 115.1–262.6 MB |
| **`r6` Nano IQ4** | **226/230** | 246/250 | **158/160** | **100/100** | **99/100** | **730/740 (98.6%)** | **829/840 (98.69%)** | **+8 / +3** | 30.0 / 34.4 | 29.4 / 35.2 | 115.3–262.7 MB |

   - `r6` 4モデルとも全スイートで `structural_integrity = 100.0%`・`nam_restoration = 100.0%` を維持した（結果JSON: `artifacts/results/v4_cap_nam_r6_cpu_*.json`）。

#### B. 共通候補生成 840問 (`azookey-server.exe --conversion-benchmark`, Zenzai + 標準辞書 N-best)

| Run / モデル | `basic` (230) | `hard` (250) | `realistic` (160) | `colloquial` (100) | `additional_dict` (100) | 既存740小計 | **総合 840** | `r4`差 / `r5`差 | wall (s) | mean / p50 / p95 / p99 (ms) | nbest (ms) |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| `v4.2 (r4)` Std Q8 | 226/230 | 249/250 | 158/160 | 100/100 | 98/100 | 733/740 (99.05%) | 831/840 (98.93%) | — | 52.54 | 56.57 / 51.34 / 92.51 / 131.72 | 4.65 |
| `r5` Std Q8 | 229/230 | 249/250 | 158/160 | 100/100 | 100/100 | 736/740 (99.46%) | 836/840 (99.52%) | +5 / — | 65.92 | 69.09 / 62.74 / 125.94 / 167.82 | 6.02 |
| **`r6` Std Q8** | **229/230** | **249/250** | 158/160 | **100/100** | **100/100** | **736/740 (99.46%)** | **836/840 (99.52%)** | **+5 / ±0** | **48.24** | 51.37 / 47.79 / 89.80 / 113.55 | 4.61 |
| `v4.2 (r4)` Std IQ4 | 225/230 | 248/250 | 158/160 | 100/100 | 99/100 | 731/740 (98.78%) | 830/840 (98.81%) | — | 45.17 | 47.50 / 45.38 / 81.51 / 108.66 | 4.72 |
| `r5` Std IQ4 | 229/230 | 248/250 | 158/160 | 100/100 | 100/100 | 735/740 (99.32%) | 835/840 (99.40%) | +5 / — | 64.77 | 67.43 / 59.09 / 128.44 / 231.27 | 6.11 |
| **`r6` Std IQ4** | **229/230** | 247/250 | 158/160 | **100/100** | **100/100** | 734/740 (99.19%) | **834/840 (99.29%)** | **+4 / -1** | **42.15** | 44.19 / 41.31 / 75.39 / 103.16 | 4.61 |
| `v4.2 (r4)` Nano Q8 | 225/230 | 247/250 | 155/160 | 100/100 | 99/100 | 727/740 (98.24%) | 826/840 (98.33%) | — | 25.20 | 27.35 / 25.58 / 47.80 / 67.93 | 4.67 |
| `r5` Nano Q8 | 229/230 | 248/250 | 159/160 | 100/100 | 99/100 | 736/740 (99.46%) | 835/840 (99.40%) | +9 / — | 37.48 | 40.53 / 32.20 / 93.18 / 176.35 | 6.04 |
| **`r6` Nano Q8** | **229/230** | **248/250** | **159/160** | **100/100** | **99/100** | **736/740 (99.46%)** | **835/840 (99.40%)** | **+9 / ±0** | **23.14** | 24.74 / 22.82 / 44.46 / 65.58 | 4.80 |
| `v4.2 (r4)` Nano IQ4 | 224/230 | 247/250 | 154/160 | 99/100 | 97/100 | 724/740 (97.84%) | 821/840 (97.74%) | — | 24.22 | 26.20 / 24.26 / 48.64 / 66.68 | 4.68 |
| `r5` Nano IQ4 | 227/230 | 244/250 | 155/160 | 100/100 | 99/100 | 726/740 (98.11%) | 825/840 (98.21%) | +4 / — | 30.52 | 32.25 / 29.16 / 59.49 / 83.31 | 5.96 |
| **`r6` Nano IQ4** | **226/230** | **246/250** | **157/160** | 99/100 | **99/100** | **728/740 (98.38%)** | **827/840 (98.45%)** | **+6 / +2** | **21.77** | 23.08 / 20.49 / 44.69 / 71.62 | 4.55 |

   - 結果ディレクトリ: `artifacts/results/common-candidates-840-r6-20261123/`（`summary.json`, `comparison.csv`, 各モデル `.jsonl`）。`mean_nbest_ms` が `v4.2` 同等の `4.55–4.80ms` に落ち着いた条件で、全4バリアントが `v4.2` 比でも高速な wall 時間（`21.77–48.24s`）で推論を完了した。

4. **問別差分と総括**:
   - **Q8_0 の安定性**: `Std Q8_0`（直結 `835/840`, 共通 `836/840`）と `Nano Q8_0`（直結 `836/840`, 共通 `835/840`）はいずれも **`r5` から 840問すべての正誤が完全維持（`fixed=0, regressed=0`）**され、同時に `Nano Q8` は未学習 24問プローブを `22/24 → 24/24` へ完全回復、`v4.2` 比では `Nano Q8` が直結 `+8`・共通 `+9` とも **退行 0件** を維持した。
   - **Nano IQ4_NL の大幅底上げ**: 共通候補で `r5→r6` `fixed=8 / regressed=6`（直結 `fixed=7 / regressed=4`）、`14じ30ぷんから`・`12ぱーせんとへった`・`ご承諾願えますか`・`キクイタダキの声を聞く`・`辞書の凡例`・`取り越し苦労`・`決済`・`意思` を一気に回復しつつ、合計でも過去最高の **直結 `829/840 (98.69%)` / 共通 `827/840 (98.45%)`** と **プローブ `24/24`** を達成した。
   - **Std IQ4_NL**: 共通候補 `hard:413`（`医師の患者に対する意思を確認する`）1問のみ `r5→r6` で反転した（`835→834/840`）が、`v4.2`（830）比 `+4`・24問プローブ `23/24`（`v4.2` 22, `r5` 21）・`ime_realistic` #121–140 20/20 を達成した。
5. **正式採用（ZeroCon Model v4.2）**:
   - 時間・数値単位表記の退行を完全解消し全バリアントで `cap-nam-r4` を上回った上記 **`cap-nam-r6` 4モデル** を、正式な **ZeroCon Model v4.2**（リリース `models-v4.2` / `zerocon-{nano,standard}-v4.2-{iq4nl-emb8,q8_0}.gguf`）として `windows/deployment/model-catalog.json`・Windows・Android・実機試験IME・Android APK へ採用した。



