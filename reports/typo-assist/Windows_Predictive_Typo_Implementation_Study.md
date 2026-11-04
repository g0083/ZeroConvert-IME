# Windows版: 🐙「もしかして」/ 🔮「先読み」の実装方式調査

調査日: 2026-10-21
対象: `scratch/batao_src` (batao9/azooKey-Windows) および `ZeroConvertIME-windows` 同梱物

---

## 0. 結論(先に要点)

**追加モデル(LFM2.5 230M/350M)を IME 内に同梱して内部推論させる案は、2 つの致命的な制約により成立しない。**

1. 同梱 llama.cpp ランタイムが **b4846 (2025-03)** で、**LFM2 アーキテクチャを未サポート**。
   実測: `llama-cli -m LFM2.5-230M-Q4_K_M.gguf` → `Unknown model architecture: 'lfm2'`
2. b4846 → LFM2 対応版への更新は、**上流 Swift パッケージの llama.h 契約と非互換**。
   `llama_kv_cache_seq_rm/cp/pos_max` が新 API では `llama_memory_seq_rm/cp/pos_max` に改名済みで、
   `ZenzContext.swift` が旧 API を直接呼んでいるため、ランタイム更新だけでは壊れる。

**逆に好消息が 2 つある。**

- **変換モデル zenz.gguf は既に IME 内に同居し、Swift サーバプロセス内で
  llama.cpp を直接 Linked して動いている。** 追加ランタイム・追加プロセスは一切不要。
- **上流 AzooKeyKanaKanjiConverter (batao9 フォーク, 固定 rev c228776) は
  「先読み」「もしかして」を zenz モデルで実装済みで、public API から到達できる。**
  - 🐙 もしかして: `experimentalRequestTypoCorrection` (**public**)
  - 🔮 先読み: `requestCandidates` + `experimentalZenzaiPredictiveInput` (**public**)

したがって推奨は **Option A: 既存 zenz.gguf を流用し、上流 public API を FFI 経由で呼び出す**。
新規モデル同梱も新規ランタイム更新も不要で、LM Studio 依存は全廃できる。
**上流フォークの修正も不要** (固定 rev の public API だけで完結する)。

---

## 1. 現状の Windows 実装(調査対象)

### 1.1 現在の AI アシストは Rust サーバ内の LM Studio HTTP クライアント

`crates/server/src/ai_assist.rs` が実装本体。

| 項目 | 現状 |
|---|---|
| モデル | LM Studio サーバ `127.0.0.1:1234` の `/v1/completions` |
| モデル選択 | `"230m_q4km"` / `"350m_q4km"` → `liquidai/lfm2.5-230m` / `lfm2.5-350m` |
| タイムアウト | 45ms (ハードコード) |
| 推論方式 | `temperature=0.0`, `max_tokens=10`, `stop=["\n","。","！","!"]` |
| 接続方式 | `std::net::TcpStream` 直叩き (依存 crate なし) |

- `fetch_predictive_candidate()` (ai_assist.rs:202) — 🔮 先読み
  1. ハードコード `prefix_map` 12 エントリ (定型句) を最優先
  2. 無い場合 LM Studio に HTTP POST
- `fetch_moshikashite_candidate()` (ai_assist.rs:112) — 💡 もしかして
  1. `TYPO_TABLE` 24 エントリによる完全一致 / 前方一致 / 語中一致
  2. Zenzai 動的対数尤度による判定は `// 将来の動的ビーム拡張用フック` の**空実装のまま** (ai_assist.rs:150-153)

### 1.2 呼び出し位置はレイテンシ重視経路

`crates/server/src/main.rs:51` `append_ai_assist_candidates()` が
`AppendText` (main.rs:1868) と `ReplaceComposition` (main.rs:1946) から**同期的に**呼ばれる。
AGENTS.md の「key handling, composition updates, candidate work は latency-sensitive」に該当する領域で、
HTTP 往復 45ms がそのまま変換レイテンシに加算される構造になっている。

候補は `Suggestion { text, subtext: "💡 もしかして" / "🔮 先読み", candidate_id: 999901/999902 }` として
通常候補の末尾に追加される。クライアント側 `crates/client/src/engine/composition.rs:1101-1107` が
`" [💡もしかして]"` / `" [🔮先読み]"` の suffix を除去して確定テキストを作る。

---

## 2. 変換モデル (zenz.gguf) はどう IME 内で動いているか

### 2.1 配置

| ファイル | サイズ | 内容 |
|---|---|---|
| `ZeroConvertIME-windows/zenz.gguf` | 61.3 MB | **実行時実際に使われる** (`execURL/zenz.gguf`) |
| `ZeroConvertIME-windows/models/zenz-v3.2-small-q4km.gguf` | 61.3 MB | SHA256 一致 = zenz.gguf と同物 |
| `ZeroConvertIME-windows/models/zenz-v3.2-small-iq4xs.gguf` | 52.6 MB | 未使用の別量子化 |

`zenz.gguf` = `models/zenz-v3.2-small-q4km.gguf` と SHA256 が一致
(`94C290AA772AE9F66BCA45842DD506DBEDEBA5EF6F67368189261FF7364F25F2`)。

メタデータ:
```
general.architecture = gpt2
general.name         = Gpt2 Small Japanese Char
gpt2.block_count     = 12
gpt2.embedding_length= 768
gpt2.context_length  = 1024
file type            = Q4_K - Medium
```

### 2.2 ロード経路 (Swift サーバ内、同一プロセス)

```
server-swift/Sources/azookey-server/azookey_server.swift
  L1889/2183:  zenzaiWeightURL = execURL.appendingPathComponent("zenz.gguf")
  L1170-1181: zenzaiMode: .on(weight: zenzaiWeightURL, inferenceLimit: 5,
                              requestRichCandidates: true,
                              versionDependentMode: .v3(.init(profile:leftSideContext: context)))
       ↓
  KanaKanjiConverter (上流 batao9/AzooKeyKanaKanjiConverter, rev c228776)
       ↓
  Zenz / ZenzContext  (Swift)
       ↓ import llama  (systemLibrary, module.modulemap: link "llama")
  llama_model_load_from_file / llama_init_from_model / llama_decode
       ↓
  llama.dll (llama_cpu / llama_cuda / llama_vulkan ディレクトリから PATH で切替)
```

**重要な点: Rust の `azookey-server.exe` と同じプロセスで Swift の `azookey-server.dll` が
リンクされている。** `crates/server/build.rs` が
`cargo:rustc-link-lib=azookey-server` + `cargo:rustc-link-search=crates/server/target` を発行し、
Rust 側は `unsafe extern "C" { fn GetComposedText(...); fn InferReconversionReadings(...); ... }`
(main.rs:474-520) で Swift 関数を直接呼ぶ。

→ **Swift 側に API を足し、Rust 側から FFI で呼べば、IPC なしで同じプロセスの zenz を使える。**

### 2.3 ランタイムのバージョン制約 (ここが問題)

同梱 `llama.dll` の実測:
- `llama-cli --version` → `build: 4846` (= b4846, 2025-03 相当)
- `llama_cpu/llama.cpp.txt` に upstream commit 記録
- バイナリは `fkunn1326/llama.cpp` の `llama-b4846-bin-win-avx-x64.zip` 等

**LFM2 非サポートの証拠:**
```
$ llama-cli.exe -m LFM2.5-230M-Q4_K_M.gguf -p "..." -n 12
llama_model_loader: - kv   0: general.architecture str = lfm2
error loading model: unknown model architecture: 'lfm2'
```

上流 Swift パッケージは `Sources/llama.cpp/module.modulemap` で
`header "llama.h" / ggml.h / ggml-alloc.h / ggml-backend.h`, `link "llama"` を宣言し、
`ZenzContext.swift` が以下を**直接**呼ぶ:

| 旧 API (b4846 / 上流 vendored llama.h) | 新 llama.cpp での改名 |
|---|---|
| `llama_kv_cache_seq_rm` | `llama_memory_seq_rm` |
| `llama_kv_cache_seq_cp` | `llama_memory_seq_cp` |
| `llama_kv_cache_seq_pos_max` | `llama_memory_seq_pos_max` |

Android 版が同梱している新 llama.cpp (android-ime/app/src/main/cpp/llama.cpp, commit 8115b4c) で
実測確認: 旧 3 関数は 0 件、`llama_memory_seq_*` が存在する。
つまり **ランタイムだけ更新すると ZenzContext.swift がコンパイル不能**になり、
上流側 (batao9 フォーク) の修正が先行して必要。

参考: Android 側の新 llama.cpp は `LLM_ARCH_LFM2` に対応済み
(android-ime/app/src/main/cpp/llama.cpp/src/llama-model.cpp に LFM2 定義あり)。
Windows 側 runtime は Android 側より古い。

---

## 3. 上流パッケージは既に両機能を zenz で実装済み

`batao9/AzooKeyKanaKanjiConverter` @ `c228776b0b869f81ee2a1031ff9dbd679f4b3cd9`
(server-swift/Package.resolved で固定済み) を有以下 2 つの API がある。

### 3.1 🐙 もしかして — `experimentalRequestTypoCorrection`

`KanaKanjiConverter.experimentalRequestTypoCorrection(leftSideContext:composingText:options:inputStyle:config:)`
→ `[ZenzaiTypoCandidate]` (**public API**)

- 実装: `ConversionAlgorithms/Zenzai/Zenz/ZenzaiTypoCandidateGenerator.swift`
- `ExperimentalTypoCorrectionConfig.languageModel = .zenz` で **Zenz(llama) の確率で評価**
  (`.ngram(NGramLanguageModel)` も選択可)
- 探索: beamSize=32 / topK=64 / nBest=5、Typo チャネルコスト
  (1文字置換 alpha=2.0 / 脱落 beta=3.0 / 隣接2文字転置 gamma=2.0) + LM スコアで-MSM 的に選択
- 出力 `ZenzaiTypoCandidate`: `correctedInput` / `convertedText` / `score` / `lmScore` / `channelCost` / `prominence`
- **既に 100% public なので追加の権限変更は不要**

### 3.2 🔮 先読み — `predictNextInputText` (package アクセス)

`KanaKanjiConverter.predictNextInputText(leftSideContext:composingText:count:minLength:maxEntropy:options:inputStyle:)`
→ `(predictedText: String, suffixCount: Int)` (KanaKanjiConverter.swift:222)

- 実装: `ConversionAlgorithms/Zenzai/Zenz/ZenzInputTextGenerator.swift`
- プロンプト: `ZenzPromptBuilder.inputPredictionPrompt` = `EE02{leftSideContext}` + `EE00{カタカナ}`
  (**EE01 出力開始タグ無し** = 変換ではなく「入力継続」モード)
- `ZenzContext.inputPredictionLogits` (seqId=1) を使って 1 文字ずつ貪欲生成
- `.v3` モデル必須 (v2 は `print("input prediction requires zenz-v3 models")` で拒否)
- 副作用として `PredictiveInputCache` に予測結果をキャッシュ

**`predictNextInputText` 自体は `package` アクセス (同一 package 内のみ可視)。**

**しかし、上流にはこれを public API 経由いで呼び出す経路が既に存在する:**

`requestCandidates` (public, KanaKanjiConverter.swift:1128) → `processResult` (L858) →
`getPredictionCandidate` (private, L552) の末尾 L616-657:

```swift
guard options.zenzaiMode.enabled, options.experimentalZenzaiPredictiveInput else { return [] }
let leftSideContext = /* zenzaiMode.versionDependentMode から取得 */
let (predictedText, suffixCount) = self.predictNextInputText(
    leftSideContext: leftSideContext, composingText: composingText,
    count: 10, minLength: 1, maxEntropy: 3.0, options: options, inputStyle: inputStyle)
guard !predictedText.isEmpty else { return [] }
// 予測テキストを composingText に挿入 → 別セッションで再変換 → 先頭候補を返す
```

つまり **`ConvertRequestOptions.experimentalZenzaiPredictiveInput = true` と
`requireJapanesePrediction` を `.manualMix`/`.autoMix` にすれば、
public API `requestCandidates` 経由で zenz による先読みが動く。**
固定済み rev c228776 にもこの実装は既に存在する (L616/L627 を確認済み)。

条件:
- `options.zenzaiMode.enabled` (Windows 側は既に `zenzaiMode: .on(...)` を設定済み → 充足)
- `options.experimentalZenzaiPredictiveInput` (既定 false → **要設定**)
- `options.requireJapanesePrediction.isEnabled` (現状 Windows 側は `.disabled` → **要変更**)
- `bestCandidateDataForPrediction` が非 nil (L858 の条件。変換結果の文節から生成)
- 辞書の zero-hint 予測が 1 件も無い場合のみ Zenzai フォールバックへ落ちる (L613-615)

**修正: 上流フォークの public 化は不要。** public API のみで完結する。
(なお `requestPostCompositionPredictionCandidates` も public であり、確定後の予測変換に使える。)

### 3.3 zenz モデルだけで「先読み」が成立するかの実測

同梱 `llama-server.exe` + `zenz.gguf` を起動し、上流と同じ
`EE02{左文脈}EE00{カタカナ}` プロンプトで実測 (temperature 0):

| ケース | 入力 | 予測された読み |
|---|---|---|
| C | (文脈なし) `会議は明日` | `カラデス` (= からです) ✓ |
| D | `今日はいい天気ですね。` + `今日はいい天気ですね。散歩` | `に行きます` ✓ |
| A | `業務が終了しました。同僚に向かって、` + 同上 | `ニムカッテ` (文脈が既に未確定で壊れる) |
| E | (文脈なし) `会議は明日` | `カラカイサイデス` ✓ |

**先読みは zenz.gguf 単体で成立する** (ケース C/E/D)。
ケース A の失敗は「左文脈を確定済みテキストとして渡していない」ことに起因し、
上流実装は `leftSideContext` に**確定済みの日本語**を渡す設計のため、実運用では問題にならない。

「先読み → 変換」の 2 段合成も成立:
```
入力読み '会議は明日' + 予測読み 'カラデス' → 変換 → '会議は明日から開催です'
```

### 3.4 「もしかして」の zenz 対数尤度判定の限界

`test_zenzai_neural_selection.py` と同じ方式 (2候補の avg logprob 比較) を実測:

| 文脈 | タイポ | avg logprob | 正解 | avg logprob | 判定 |
|---|---|---|---|---|---|
| 仕事がやっと終わった。同僚に | オツクレサマ | -0.0403 | オツカレサマ | -0.1053 | ✗ 誤判定 |
| 朝の新聞社で記者が | シンブミヲヨム | -0.2495 | シンブンヲヨム | -0.0198 | ✓ |
| 取引先へのお願いの連絡で、 | ヨロシキョオネガイ | -0.0386 | ヨロシクオネガイ | -0.0257 | ✗ 誤判定 |
| お昼のチャットで、 | キンニチハ | -0.0804 | コンニチハ | -0.0613 | ✗ 誤判定 |

**素の logprob 比較は 1/4 しか当たらない** (zenz は「変換結果の自然さ」を見るので、
タイポ入力でも文脈次第で自然な誤変換を返す)。
既存 research 結果 (`benchmark_results.json`: typo exact=0.0%) と整合する。

→ 精度を出すには、上流の `ZenzaiTypoCandidateGenerator` の
**Typo チャネルコスト (alpha/beta/gamma) + ビーム探索** を使う必要があり、
「自前で logprob 比較する」方針では成立しない。

---

## 4. 実測ベンチマーク (LM Studio 経由, 既存 research 成果物)

`research/benchmark_results.json` (220 ケース):

| タスク | エンジン | exact | acceptable | レイテンシ |
|---|---|---|---|---|
| typo | **Dictionary** | **85.7%** | 85.7% | 0.3ms |
| typo | ZeroConAI (zenz) | 0.0% | 2.9% | 32.5ms |
| typo | LFM-230M | 0.0% | 0.0% | 87.4ms |
| typo | LFM-350M | 0.0% | 0.0% | 83.1ms |
| typo | LFM-1.2B | 0.0% | 0.0% | 138.5ms |
| predictive | **Dictionary** | **73.3%** | 73.3% | 0.0ms |
| predictive | ZeroConAI (zenz) | 0.0% | 0.0% | 42.8ms |
| predictive | LFM-230M | 3.3% | 3.3% | 78.1ms |
| predictive | LFM-350M | 0.0% | 6.7% | 82.4ms |
| predictive | LFM-1.2B | 0.0% | 3.3% | 149.5ms |

**LFM 系の数値が全て極端に悪い点に注意。** 実測でも zenz 単体の素朴な
プロンプトベース推論は品質が安定しない (§3.3/§3.4)。
上流のチャネルコスト付きビーム探索 (実装済み) を通せば数値は別物になるはずだが、
**いずれにせよ LFM2.5 を IME に同梱する根拠が測定結果から出ない。**

---

## 5. 実装方式の選択肢

### Option A (推奨): 既存 zenz.gguf + 上流 public API を FFI 経由で呼ぶ

- **追加モデル: 0** 追加ランタイム: 0 追加プロセス: 0
- 🐙 もしかして: `experimentalRequestTypoCorrection` (**既に public**、上流変更不要)
- 🔮 先読み: `requestCandidates` に `experimentalZenzaiPredictiveInput = true` +
  `requireJapanesePrediction = .manualMix` を設定 (**既に public**、上流変更不要)
- 経路: Swift `azookey_server.swift` に FFI 関数追加 → Rust `main.rs` の
  `unsafe extern "C"` 宣言に追記 → `ai_assist.rs` の HTTP クライアントを削除
- 効果: LM Studio 依存・ポート 1234・外部プロセス前提が全て消滅。
  推論は同一プロセス内で、IPC コストゼロ。

**前提作業: 上流の public 化は不要。** 固定 rev c228776 の public API のみで
両機能とも成立する (§3.1 / §3.2 参照)。

**残課題:**
- 素朴 logprob 比較では精度が出ないため、🐙 は上流 `ZenzaiTypoCandidateGenerator` の
  チャネルコスト・ビーム探索をそのまま使う。
- Windows で `SwiftyMarisa` が無い (upstream Package.swift は Windows で
  EfficientNGram の SwiftyMarisa 依存を除外、Inference.swift も
  `#if canImport(SwiftyMarisa) && Zenzai` ガード) ため、
  n-gram 系の languageModel ではなく `.zenz` 経路を使う前提。
- 🔮 の Zenzai フォールバックは「辞書の zero-hint 予測が 1 件も無い場合のみ」動く (L613-615)。
  常に Zenzai 先読みを優先したい場合は `requireJapanesePrediction` を `.disabled` にして
  `predictNextInputText` を直接呼ぶ必要があり、これは上流の public 化 (fork 内なので容易) を要する。

### Option B: LFM2.5 を IME に同梱して内部推論

必要工作量:
1. llama ランタイムを LFM2 対応版へ更新 (b4846 → 新版)
2. **上流 `batao9/AzooKeyKanaKanjiConverter` の `ZenzContext.swift` を
   `llama_memory_seq_*` API へ移行** (fork 内なので可能だが変換”二字 Augustus コアの改変になる)
3. `llama_cpu/llama_cuda/llama_vulkan` の 3 系統すべての再取得・再配置
4. LFM2.5 230M (144MB) または 350M (219MB) の同梱と追加メモリ確保
5. Swift 側に LFM2 用の新しい推論器 (Contexts/Samplers) を実装
   — 既存の `ZenzContext` は zenz 専用プロンプト構造で LFM2 とは別物
6. Rust 側 API の差し替え

**評価:** 工期・リスク・配布サイズ (追加 144〜219MB) のいずれも大きく、
§4 の測定結果 (LFM 系 0〜6.7%) を踏まえると投資対効果が低い。

### Option C: 辞書を CPU でclassification (LM なし)

`research/dictionary_baseline.py` の pure-lexical 方式。
- typo exact 85.7% / predictive 73.3% / レイテンシ 0.0〜0.3ms — **測定上は最も良い**
- ただし「文脈理解olitics」をPICphireしない。固有名詞・文脈依存の誤りは出ない。
- 副作用: 現在の `TYPO_TABLE` / `prefix_map` のハードコードはこの方式の極小版であり、
  既に `research/` に先行実装がある。

**評価:** 「exam」は既に良好。コスト最小。ただし「_MODEL 内蔵」という要求とは
別解であり、併用が妥当 (辞書が一次、zenz が二次)。

---

## 6. 推奨アーキテクチャ

```
[TSF client azookey.dll]  ←→  [azookey-server.exe (Rust)]
                                    │  gRPC / named pipe
                                    ▼
                              [azookey-server.dll (Swift)]  ← 同一プロセス
                                    │
                                    ├─ KanaKanjiConverter (上流)
                                    │    ├─ 変換 (既存)
                                    │    ├─ 🐙 もしかして : experimentalRequestTypoCorrection
                                    │    └─ 🔮 先読み    : predictNextInputText / postCompositionPrediction
                                    │
                                    └─ ZenzContext → llama.cpp (llama.dll) → zenz.gguf
```

**LM Studio (127.0.0.1:1234) / 外部 llama-server (8090) は完全に撤去可能。**

### 実装手順 (Option A)

1. **Swift 側** (`server-swift/Sources/azookey-server/azookey_server.swift`):
   - `makeConvertRequestOptions` (L1159-1184) に
     `experimentalZenzaiPredictiveInput: true` を追加
   - `requireJapanesePrediction` (L1160) を `.disabled` → `.manualMix` に変更し、
     `converted.predictionResults` を別枠 (🔮 先読み) として取り出す。
     `.autoMix` にすると通常候補に混ざるため、「入力通り 100% 維持」方針
     (research/prototype_moshikashite_pipeline.py の非破壊設計) には `.manualMix` が適合
   - `experimentalRequestTypoCorrection` を呼ぶ FFI 関数を追加
     (`@_silgen_name` + `ffi.h` / `module.modulemap` 追記)
   - `config["experimentalTypoCorrection"]` は既に存在 (L41, L975, L1182) なので設定配線は流用可
2. **Rust 側**:
   - `crates/server/src/main.rs:474` の `unsafe extern "C"` ブロックに新 FFI を宣言
   - `crates/server/src/ai_assist.rs` から `simple_http_post` / `lfm_port` / `zenzai_port` を削除
   - `append_ai_assist_candidates` (main.rs:51) を FFI 呼び出しに置換
3. **設定/UI**:
   - `frontend/src/pages/general.tsx:418` の「先読み推論モデル」選択肢 (230m/350m) を削除
   - `crates/shared/src/lib.rs:662` `experimental_predictive_model` を廃止
   - `settings.json` の `experimental_predictive_model` を削除
4. **配布**: モデル追加なし → 既存 `verify_release_artifact.ps1` / インストーラの
   ファイル一覧変更は不要

### レイテンシ配慮 (AGENTS.md 準拠)

現在 `append_ai_assist_candidates` は HTTP 往復を**同期-blocking** している。
Option A でも zenz 推論は同数十ms級なので、
**入力確定後の非同期実行 + 結果の次候補更新**が望ましい。
AGENTS.md「Avoid blocking I/O, waits ... on those paths」にalignするため、
候補追加は composition 更新とは分離し、バックグラウンド推論結果を
後続の candidate 更新で反映する設計を推奨。

---

## 7. 未解決/要確認事項

1. 🔮 先読みを「常に Zenzai 優先」にするか「辞書の zero-hint 優先・Zenzai フォールバック」にするか。
   前者なら上流 `predictNextInputText` の public 化 (batao9 フォーク内なので容易) が必要
2. 🐙 もしかしてを `experimentalRequestTypoCorrection` で呼んだときの Windows 実測品質
   (チャネルコスト込みなので §3.4 の素朴 logprob より改善するはずだが実測が必要)
3. `requireJapanesePrediction = .manualMix` 変更が既存変換の挙動・候補数に与える影響
   (既存は `.disabled`。`predictionResults` が別途返る仕様に変わる)
4. zenz の `n_ctx=512` (ZenzContext.swift:31) / `inferenceLimit=5` (L1172) が
   先読み・typo 探索と干渉しないか
5. Swift 側の `@MainActor` 制約 — FFI 追加関数が MainActor 鎖を外れるか
6. `experimentalRequestTypoCorrection` は `options.zenzaiMode.enabled` を要求するため、
   zenzai 無効設定時のフォールバック設計 (辞書の typo correction のみ) の要否

---

## 参照 (file:line)

- 現行 LM Studio 実装: `crates/server/src/ai_assist.rs:39` (HTTP), `:75` (TYPO_TABLE), `:202` (先読み)
- 呼び出し位置: `crates/server/src/main.rs:51`, `:1868`, `:1946`
- FFI 宣言: `crates/server/src/main.rs:474-520`
- Swift→Rust リンク: `crates/server/build.rs`
- zenz 配置/ロード: `server-swift/.../azookey_server.swift:1889`, `:2183`, `:1170-1181`
- 変換オプション: `server-swift/.../azookey_server.swift:1159-1184`
  (`requireJapanesePrediction: .disabled` は L1160)
- llama モジュールマップ: `server-swift/Sources/ffi/module.modulemap`, 上流 `Sources/llama.cpp/module.modulemap`
- 上流 API (固定 rev c228776):
  - `KanaKanjiConverter.experimentalRequestTypoCorrection:290` (**public**)
  - `KanaKanjiConverter.predictNextInputText:222` (package)
  - `KanaKanjiConverter.getPredictionCandidate:552` (private) → L616-657 で public 経路あり
  - `KanaKanjiConverter.requestCandidates:1128` (public)
  - `KanaKanjiConverter.requestPostCompositionPredictionCandidates:1176` (public)
  - `ConvertRequestOptions.experimentalZenzaiPredictiveInput:105` (**public**)
  - `ConversionResult.predictionResults:5` (**public**)
  - `ZenzContext.swift:130-146` (旧 `llama_kv_cache_*` API)
  - `ZenzInputTextGenerator.swift` / `ZenzaiTypoCandidateGenerator.swift`
- ランタイム取得: `scripts/vm_build.sh:377` (zenz URL), `:464` (llama CPU 準備), `:519` (llama.lib)
- 設定/UI: `frontend/src/pages/general.tsx:414-418`, `crates/shared/src/lib.rs:662`, `settings.json`
- 実測データ: `research/benchmark_results.json`, `research/typo_testset.jsonl`, `research/predictive_testset.jsonl`

---

## 付録: 実測環境

- ホスト: Windows 11 / PowerShell 7
- 同梱ランタイム: `ZeroConvertIME-windows/llama_cpu/llama-server.exe` (build 4846)
- モデル: `ZeroConvertIME-windows/zenz.gguf` (= `models/zenz-v3.2-small-q4km.gguf`, SHA256 一致)
- 実測プロンプト形式:
  - 変換: `EE02{左文脈}EE00{カタカナ}EE01`
  - 先読み: `EE02{左文脈}EE00{カタカナ}` (EE01 なし)
- 誤字判定: `/completion` の `completion_probabilities` における平均 logprob 比較
  (= `research/test_zenzai_neural_selection.py` と同じ方式)
