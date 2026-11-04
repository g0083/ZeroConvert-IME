# Windows版とAndroid版の変換ロジック比較

本ドキュメントは、`docs/windows-conversion-logic.md` と `docs/android-conversion-logic.md` を基に、両プラットフォームの変換機能・アーキテクチャ・実装差を比較する。

---

## 1. アーキテクチャ

| 項目 | Windows | Android |
|---|---|---|
| プロセス構成 | TSF クライアント DLL + 変換サーバー (Rust) + Swift エンジン + launcher + UI.exe + Tauri 設定 | 単一プロセスの InputMethodService (Kotlin) + JNI |
| 通信 | セッション別 Named Pipe 上の Tonic/protobuf RPC | プロセス内直接呼び出し (JNI 経由のネイティブ) |
| 変換エンジン | Swift (AzooKeyKanaKanjiConverter + Zenzai GGUF) | JNI llama.cpp + LOUDS 辞書 |
| 辞書 | Swift 同梱辞書 + Zenzai | LOUDS (native) + ユーザー辞書 + 絵文字辞書 |
| 設定 UI | Tauri (React/TS) | Compose (同一アプリ内) |
| 設定保存 | `%APPDATA%\Azookey\settings.json` | SharedPreferences (`ime_settings`) |
| モデル切替 | launcher がポート 18090/18091 のプロセスを起動・監視・切替 | `ZenzNative.loadModel` で in-process 切替 (epoch 管理) |
| ライフサイクル | TSF コールバック + サーバー所有 composition | Android InputMethodService ライフサイクル |

---

## 2. 通常変換 (かな漢字変換)

| 項目 | Windows | Android |
|---|---|---|
| 候補生成 | Swift `getOptions` → サーバーが返却 | `KanaKanjiConverter.requestCandidates` (Dispatchers.Default) |
| 優先順 | Swift 内部 (辞書 + Zenzai) | 学習記憶 → ユーザー辞書 → ZeroCon AI → LOUDS 辞書 → 絵文字 → フォールバック |
| AI 変換 | Swift Zenzai (zenz.gguf) | JNI `ZenzNative.convert` (ZeroCon GGUF) |
| 辞書検索 | Swift 内部 | `ZenzNative.lookupDict` (native LOUDS) |
| 学習記憶 | Swift の学習 + `ai-assist-learning.json` (AI アシスト専用) | `learningMemory` (LinkedHashMap, 最大 500 件, SharedPreferences) |
| ユーザー辞書 | Swift / 設定 UI | `userDictEntries` (SharedPreferences JSON) |
| プロファイル | Swift に渡す context | `buildEffectiveProfile` (左文脈 + ユーザー辞書ヒント) |
| 再変換 | `StartReconversion` (RPC) | `ReconversionPlan` |

共通: 学習記憶が最優先、AI 変換と辞書を統合、ひらがな/カタカナフォールバック。

---

## 3. ライブ変換 / インライン変換

| 項目 | Windows | Android |
|---|---|---|
| 表示 | TSF composition に preview を設定 | `InputConnection.setComposingText` |
| 読み生成 | `live_conversion_reading` / `_update` | ComposingText が原打鍵から読みを生成 |
| 安定性履歴 | `live_conversion_history` (VecDeque) | `inferenceHistory` (InferenceSnapshot) |

---

## 4. 自動確定 (Auto-commit)

| 項目 | Windows | Android |
|---|---|---|
| 実装場所 | クライアント `find_auto_commit_live_conversion_snapshot` | サービス companion `findStableAutoCommitPrefix` |
| 最小全体読み | 16 文字 | 16 文字 |
| 最小確定部分 | 8 文字 | 8 文字 |
| 最小残り | 7 文字 | 7 文字 |
| 最小接頭部 | 4 文字 | 4 文字 |
| 安定段階 | 5 | 5 |
| 自然境界判定 | `is_natural_auto_commit_prefix` | `isNaturalAutoCommitPrefix` (同一ロジック移植) |
| 巻き戻し | 補正適用後に履歴を作り直し | `AutoCommitRewind` / `AutoCommitRewindRecord` |

アルゴリズムはほぼ同一に移植されている。

---

## 5. タイポ補正

| 項目 | Windows | Android |
|---|---|---|
| 有効化設定 | `general.experimental_ai_assist` + `experimental_typo_correction` (Swift 側別) | `experimental_ai_assist` + `auto_typo_correction` |
| 実装 | Rust `ai_assist/corrector` + Swift キーボードタイポ補正 (別経路) | Kotlin `ModelCorrector` + `TypoCorrectionEngine` (Windows ルール移植) |
| ニューラル補正 | corrector モデル on port 18090 (`/completion`) | JNI `AssistModel.CORRECTOR` (ZeroCon corrector GGUF) |
| フォールバック | romaji_decoder → 学習 → 固定表 → 音韻規則 → 編集距離 → QWERTY 辞書 → ニューラル | `TypoLearning` → `AssistVocabulary` 保護 → `RomajiDecoder` → モデル → `TypoCorrectionEngine.findCorrection` (Windows ルール) |
| 逆引き辞書保護 | `reverse-v2.bin` (AZR2) 背景ワーカー | `ReadingCheck` (native asset) |
| 自動反映条件 | 辞書語へ一意復元 or 3 回採用 | `AutoCorrectionEvidence.permits` (同条件) |
| 学習記録 | `ai-assist-learning.json` (最大 500 件) | `TypoLearning` |
| relevance | 100/70/30 | 100/70/30 (同一) |

---

## 6. 先読み (予測変換)

| 項目 | Windows | Android |
|---|---|---|
| モデル | ZeroCon のみ (port 18091) | ZeroCon のみ (JNI) |
| 生成方式 | `EE02 + 左文脈 + EE00 + カタカナ読み` → 読み生成 (24 トークン/250ms) → `EE01` で表記変換 (最大 256 トークン/300ms) | 同じプロンプト形式 (`\uEE02...\uEE00...\uEE01`)、24 トークン/800ms、256 トークン/1000ms |
| 左文脈 | 末尾 256 文字 | 末尾 256 文字 |
| キャッシュ | 32 件 (ポート/左文脈/読み/表記) | LRU 64 件 (revision/kind/maxTokens/prompt) |
| relevance | 60 (出力検査通過時) | 60 |
| 除外 | 「この記事」「上記」等 | 同じ |
| 漢字表記の読み復元 | 逆引き辞書 (背景ワーカー) | `ReadingCheck.readingForSurface` |

---

## 7. 候補操作・文節・確定

| 項目 | Windows | Android |
|---|---|---|
| 文節移動 | RPC (`MoveCursor`, `AdvanceClause`, `PrepareFutureClauses`, `AdjustClauseBoundary`) | `ConversionRangeSession` + ComposingText のカーソル操作 |
| clause スナップショット | サーバー側 push/pop/clear | クライアント側 ComposingText |
| 再変換 | `StartReconversion` (RPC, サーバー側読み推定) | `ReconversionPlan` |
| 確定通知 | `CommitLearningCandidate(s)` RPC | `selectCandidate` → `recordSelection` |
| 動的マクロ | `DYNAMIC_MACRO` (999903) | `DynamicMacro` |
| 候補再ランキング | `RerankCandidates` (Stateless RPC) | なし (クライアント側で候補リスト管理) |

---

## 8. UI・表示

| 項目 | Windows | Android |
|---|---|---|
| 候補表示 | `crates/ui` (Rust) + ui.exe | Compose (`ComposeKeyboardView`) |
| 読み表示 (ルビ) | `crates/ui/src/ruby.rs` | Compose 内 |
| 補正表示 | フローティングに変更前→変更後 | `aiAssistResultState` + Compose |
| テーマ | 明暗テーマ・拡大率対応 | `ZeroConTheme` |
| キーボード | TSF 経由 (物理/OSK) | 12 キーフリック / QWERTY Compose |

---

## 9. 設定・モデル

| 項目 | Windows | Android |
|---|---|---|
| 設定 UI | Tauri (React) | Compose (MainActivity) |
| 設定保存 | JSON ファイル | SharedPreferences |
| モデル管理 | launcher がポートで起動、ファイル配置 | `ModelManager` が assets から filesDir に展開 |
| 組み込みモデル | zenz.gguf + typo-corrector-xsmall-q4km.gguf | 3 種 (Nano Q4_K_M, Nano Lite Q3_K_M, Standard Q4_K_M) + corrector GGUF |
| バックエンド | Vulkan/CPU/CUDA (zenzai.backend) | JNI (llama.cpp, Android) |
| プライバシー | (Windows 側は明示なし) | `InputPrivacy` (パスワード欄で補正/先読み/学習を無効化) |

---

## 10. 検証

| 項目 | Windows | Android |
|---|---|---|
| ビルド | cargo build / swift build | Gradle (`assembleDebug`) |
| テスト | cargo test / swift test / node test | `testDebugUnitTest` / `lintDebug` / instrumentation |
| 実機確認 | TSF DLL をロードした入力先アプリ | `adb install -r` + 実 IME 確認 |
| モデル評価 | `live_prediction_evaluation` (ignored test) | `ModelAssistTest` (JVM) |

---

## 11. 主な差異の要約

1. **プロセス境界**: Windows はクライアント/サーバー分離 + Swift FFI で composition をサーバーが所有。Android は単一プロセスで Kotlin が直接状態を管理。
2. **変換エンジン**: Windows は Swift (AzooKey) + Zenzai。Android は JNI llama.cpp + LOUDS 辞書を Kotlin から直接呼び出し。
3. **タイポ補正のフォールバック**: Windows は Rust の多段フォールバック (romaji → 学習 → 固定表 → 音韻 → 編集距離 → QWERTY → ニューラル)。Android は Kotlin で Windows ルールを移植し、JNI モデルで補強。
4. **Swift 側タイポ補正**: Windows には Swift の `experimental_typo_correction` が別経路で存在。Android には対応する別経路はない (Kotlin に統合)。
5. **自動確定**: アルゴリズム (16/8/7/4/5) は同一に移植。Android は `AutoCommitRewind` で巻き戻しを追加。
6. **先読み**: 両者とも ZeroCon のみを使用。プロンプト形式・除外ルールは同一。
7. **候補操作**: Windows は RPC でサーバー側に文節スナップショットを保持。Android はクライアント側で ComposingText を管理。
8. **プライバシー**: Android は `InputPrivacy` で機密入力を明示的に保護。Windows 側の同等機能は確認されていない。
9. **設定**: Windows はファイルベース + Tauri。Android は SharedPreferences + Compose。

---

## 12. 機能パリティ (概要)

| 機能 | Windows | Android | 備考 |
|---|---|---|---|
| かな漢字変換 | ○ | ○ | エンジン異なる |
| ライブ変換 | ○ | ○ | |
| 自動確定 | ○ | ○ | アルゴリズム同一 |
| タイポ補正 (提案) | ○ | ○ | |
| タイポ補正 (自動反映) | ○ | ○ | |
| 先読み | ○ | ○ | ZeroCon のみ |
| 文節移動 | ○ (RPC) | ○ (クライアント) | |
| 再変換 | ○ | ○ | |
| 学習記憶 | ○ | ○ | |
| ユーザー辞書 | ○ | ○ | |
| 動的マクロ | ○ | ○ | |
| 候補 UI | ○ | ○ | |
| 設定 UI | ○ (Tauri) | ○ (Compose) | |
| モデル切替 | ○ (launcher) | ○ (in-process) | |
| プライバシー保護 | 未確認 | ○ | |

○ = 実装あり (品質・接続状態は別途検証が必要)。
