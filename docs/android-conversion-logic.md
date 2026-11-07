# Android版の変換ロジック

本ドキュメントは、ZeroConvert-IME の Android 版における「入力 → 変換 → 表示 → 確定」までの処理を、層ごとに解説する。対象は `android/` 配下の Kotlin IME サービス、`azookey` 入力状態、ネイティブ JNI/llama.cpp、設定 UI。Windows 版（Rust/Swift）とは実装が別であり、RPC や IPC は持たない。Windows 側の時間予算やモデル経路をそのまま Android に適用しない。

関連ドキュメント: [architecture.md](architecture.md)（構成）、[file-map.md](file-map.md)（探索先）、[android-development.md](android-development.md)（開発手順）、[android-port-completion-audit.md](../reports/port-history/android-port-completion-audit.md)。

---

## 1. 全体構成

### 1.1 コンポーネント

| コンポーネント | 役割 | 主なファイル |
|---|---|---|
| InputMethodService | Android IME ライフサイクル、InputConnection 操作、キー処理、候補表示連携 | `android/app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt` |
| ComposingText | 原打鍵・読み・カーソル位置・独立境界の管理 | `android/app/src/main/java/com/zeroconvert/ime/azookey/input/ComposingText.kt` |
| KanaKanjiConverter | かな漢字変換・候補統合（学習記憶、ユーザー辞書、ZeroCon AI、LOUDS 辞書、絵文字） | `android/app/src/main/java/com/zeroconvert/ime/KanaKanjiConverter.kt` |
| ZenzNative | JNI ブリッジ。モデルロード・変換・辞書検索・アシスト推論 | `android/app/src/main/java/com/zeroconvert/ime/ZenzNative.kt` |
| ネイティブ層 | llama.cpp 推論 + LOUDS 辞書 | `android/app/src/main/cpp/zenz_jni.cpp`, `azookey_louds.cpp` |
| アシスト | タイポ補正・先読み・採用学習 | `android/app/src/main/java/com/zeroconvert/ime/assist/` |
| モデル管理 | 組み込みモデル展開・選択・切替 | `android/app/src/main/java/com/zeroconvert/ime/model/ModelManager.kt` |
| キーボード UI | Compose / Flick キーボード、候補表示 | `ComposeKeyboardView.kt`, `FlickKeyboardView.kt` |

### 1.2 データフロー

```
入力先アプリ (InputConnection)
  → ZeroConvertInputMethodService (キーイベント)
  → ComposingText (原打鍵 → 読み)
  → KanaKanjiConverter.requestCandidates (候補生成)
      → ZenzNative.convert (ZeroCon AI 変換)
      → ZenzNative.lookupDict (LOUDS 辞書)
      → 学習記憶 / ユーザー辞書 / 絵文字
  → 候補表示 / InputConnection.setComposingText
  → AiAssistManager (補正・先読み、非同期)
  → 確定 → KanaKanjiConverter.recordSelection (学習)
```

Android には Windows のような別プロセス RPC はない。モデル推論は JNI 経由で同一プロセス内 (別スレッド) で実行する。

### 1.3 重要な境界

- メインスレッドのキー処理・InputConnection 操作にモデル推論・ディスク I/O・大きな辞書探索・待機を入れない。
- 原打鍵、読み、通常表記、補正後表記、先読み追加部分、カーソル位置、候補識別を分ける。
- 削除再入力で同じ読みになっても古い推論結果を適用しない（要求世代で破棄）。

---

## 2. 入力処理の基本

### 2.1 キーイベント入口

`ZeroConvertInputMethodService` が `InputMethodService` を継承し、キー入力・確定・削除・カーソル・ライフサイクルを管理する。設定は `SharedPreferences` (`ime_settings`) に保存。

主な設定キー: `realtime_inline_conversion`, `auto_commit_enabled`, `auto_commit_rewind_on_backspace`, `experimental_ai_assist`, `auto_typo_correction`, `experimental_predictive_model`（廃止・互換）、`ai_profile` など。

QWERTY の句読点・シフト記号 (`isImmediateCommitChar`) は composition を即時確定する。数字は Windows の `UserAction::Number` → `AppendText` と同じく composition に加わり、読みと変換に含まれる（`n` 直後の数字は「ん」を確定させる）。翻訳モードでは数字も `isTranslationLiteralChar` で読みへ反映して再翻訳する。

### 2.2 ローマ字→かな

`ComposingText` が `input: MutableList<InputElement>`（原打鍵列）と `convertTarget`（読み/かな列）を管理する。`DefaultRomanToKana.table` でローマ字→かな変換を行う。入力スタイル `ROMAN2KANA` / `DIRECT` / `FROZEN` を区別する。

独立境界 (`getIndependentSegmentBoundaries`) により、カーソル移動・Backspace・長文入力で文字が欠落・破壊されない。

### 2.3 composition 状態

`convertTarget`（読み全体）、`convertTargetCursorPosition`（カーソル位置）、`input`（原打鍵）。`prefixComplete` で確定済み部分を切り落とし、`restoreFrom` で復元（Undo 用、ローマ字を frozen かなに変えない）。

---

## 3. 通常変換 (かな漢字変換)

### 3.1 候補生成 (`KanaKanjiConverter.requestCandidates`)

`withContext(Dispatchers.Default)` で非同期実行。優先順:

1. **学習記憶** (LearningMemory): 同じ読みに対する過去の選択結果を最優先 (cost 2000)
2. **ユーザー辞書**: 完全一致 (cost 1500)
3. **ZeroCon AI**: `ZenzNative.convert(leftContext, katakana, effectiveProfile)` (cost 1000)。`ConversionCandidateMerge.validSurface` で有効性検査
4. **LOUDS 辞書**: `ZenzNative.lookupDict(katakana, prefixSearch=true)`。完全一致 → 絵文字 → 前方一致
5. **絵文字辞書**: `lookupEmojis`
6. **フォールバック**: ひらがな・カタカナ

`fixedSurfacePrefix` がある場合 (補正適用後) は接頭部を保持して tail のみ変換。

### 3.2 プロファイル

`buildEffectiveProfile` が左文脈 + ユーザー辞書ヒント (最大 2 件) を組み合わせて ZeroCon の profile 文字列を生成 (最大 40 文字)。

### 3.3 候補の識別

`Candidate` は `text`, `reading`, `cost`, `isAI`, `assist`, `segments` を持つ。`key` / `itemKey` で識別。

### 3.4 学習

`recordSelection(context, readingHiragana, surface)` が `learningMemory` (LinkedHashMap, 最大 500 件) に記録し、SharedPreferences に永続化。`LearningPolicy` (mode) で読み書き制御。`getLearnedSurface` で読み取り。

---

## 4. ライブ変換 / インライン変換

`isRealtimeInlineEnabled()` でリアルタイムインライン変換の有効/無効を制御。変換結果を `setComposingText` で composition に反映する。`lastInferredResult` / `lastInferredSurface` で直前の推論結果を保持し、差分更新に使う。

---

## 5. 自動確定 (Auto-commit)

Android にも Windows と同様の自動確定がある。`MIN_READING_LEN_FOR_AUTO_COMMIT = 16`, `MIN_TAIL_UNCOMMITTED_READING_LEN = 7`, `STABLE_INFERENCE_COUNT = 5`。

### 5.1 判定 (`findStableAutoCommitPrefix`)

`ZeroConvertInputMethodService` の companion に実装。`InferenceSnapshot` の履歴から、安定した句境界プレフィックス (L, P) を探索する。条件:

- currentReading.length >= 16 かつ末尾に未確定ローマ字子音がない
- 履歴が 5 件以上
- L <= currentReading.length - 7 (少なくとも 7 文字残す)
- L >= 8
- `safeRawBoundary(L)` が true
- currentReading が snap.readingHiragana で始まる
- `isNaturalAutoCommitPrefix(P, currentAiSurface)` が true (句読点、または漢字/カタカナを含み平仮名で終わり、次が内容語)
- 直近 5 件の推論で P が安定 (全て P で始まり P より長い)

最大 L の候補を採用。

### 5.2 巻き戻し (AutoCommitRewind)

`AutoCommitRewind.kt` / `AutoCommitRewindRecord` で、自動確定後に Backspace で巻き戻しできる。`auto_commit_rewind_on_backspace` 設定で制御。巻き戻し時は `originalInput` (ComposingText スナップショット) を復元。

---

## 6. タイポ補正 (Android)

Windows と同様に、Rust ではなく Kotlin + JNI の ZeroCon モデルで補正を行う。`assist/` パッケージに集約。

### 6.1 表示と操作

Windows と同等の UX:
- 補正提案: 通常のライブ変換 + フローティングに「変更前 → 変更後、Tab で補正」
- Tab で補正しても確定せず、続けて入力できる。先読みの Tab は採用・確定。
- 補正適用直後の Esc で原文の打鍵履歴と表示を復元。同じ入力状態では取り消した候補を再表示しない。
- `adoptAiAssist(explicit)` で適用。`corrections.apply(composingText, assist.text, preview, explicit)` で内部状態を更新し、`setComposingText` で composition を更新。
- `aiAssistAdoptedState` で採用済みフラグ管理。`inferenceHistory.clear()` で履歴をクリア。

### 6.2 補正の内部パイプライン (`ModelCorrector`)

`ModelCorrector.suggest(context, input, baseline)`:

1. 入力長 2〜512、unsafe token チェック
2. 128 文字超は句読点境界で末尾の句のみ補正（Windows と同様）
3. `TypoLearning.lookup(target)` で学習済み補正を優先
4. `AssistVocabulary.isKnownCleanReading` で正常な読みを保護（null 返却）
5. `RomajiDecoder.decodeFloatingConsonantTypo(target)` で浮いた子音を復元
6. モデル入力プロンプト: `\uEE02$left\uEE00${toKatakana(modelInput)}\uEE01`
7. `inference.complete(CORRECTOR, prompt, 32..384, 1200ms)`
8. 出力検査: finished, 非空, unsafe token なし, 長さ制限
9. `decoded != null` の場合は `TypoCorrectionEngine.surfaceForReading(decoded)` と一致するか検査
10. `KanaKanjiConverter.toHiragana(completed) == toHiragana(target)` なら null (変更なし保護)
11. フォールバック: `TypoCorrectionEngine.findCorrection(target)` (Windows 移植ルール)
12. `AutoCorrectionEvidence.permits(target, corrected, learned)` で自動反映判定
13. relevance: autoApply=100, learned=70, else=30

### 6.3 フォールバックルール (`TypoCorrectionEngine`)

Windows の `WindowsTypoRules` / `WindowsTypoData` から移植したルールベース補正。QWERTY 隣接キー、12 キーフリック行、Damerau-Levenshtein 距離、標準語彙 (`STANDARD_VOCAB`) を使用。`TypoVariant` で QWERTY 変種を生成。

### 6.4 逆引き辞書による保護

`ReadingCheck` (native asset `reading_check.json`) で、補正表記が元の読みのまま読めるかを検査。`ReadingCheck.hasSameReading` / `readingForSurface` を使用。`AssistVocabulary` で既知のクリーン読みを保護。

### 6.5 自動反映の条件 (`AutoCorrectionEvidence`)

Windows と同様:
- 完全な辞書語へ一意に復元できる隣接キー誤入力
- 同じ補正を明示採用して確定した記録が 3 回以上 (`TypoLearning`)
- 末尾が入力途中の子音、全体が英字だけの識別子、曖昧な候補は自動反映しない

### 6.6 学習 (`TypoLearning`)

`TypoLearning` が採用履歴を管理。`TypoLearning.lookup(target)` で学習済み補正を取得。`TypoLearning.record(target, corrected, ...)` で記録。

### 6.7 関連ファイル

- `android/app/src/main/java/com/zeroconvert/ime/assist/ModelCorrector.kt`
- `android/app/src/main/java/com/zeroconvert/ime/assist/TypoCorrectionEngine.kt`
- `android/app/src/main/java/com/zeroconvert/ime/assist/RomajiDecoder.kt`
- `android/app/src/main/java/com/zeroconvert/ime/assist/TypoVariant.kt`
- `android/app/src/main/java/com/zeroconvert/ime/assist/TypoLearning.kt`
- `android/app/src/main/java/com/zeroconvert/ime/assist/AutoCorrectionEvidence.kt`
- `android/app/src/main/java/com/zeroconvert/ime/assist/ReadingCheck.kt`
- `android/app/src/main/java/com/zeroconvert/ime/assist/AssistVocabulary.kt`
- `android/app/src/main/java/com/zeroconvert/ime/assist/WindowsTypoRules.kt` / `WindowsTypoData.kt`

---

## 7. 先読み (予測変換)

### 7.1 生成と表示

`AiAssistManager.streamAiAssist` が補正 (`ModelCorrector`) と先読み (`ModelPredictor`) を別コルーチンで並列実行し、`Channel` で結果を順次配信。`AssistPresentation.complete` で表示を組み立てる。

- 補正は手動提案なら先読みの応答まで保留。先読みが先に届けば直ちに表示できる。自動反映できる狭い根拠の補正は保留しない。
- `AssistRequestGate` で要求世代を管理。削除・再入力・候補操作・確定・フォーカス変更で世代が変わった結果を破棄。
- `CorrectionSession` で補正の適用状態 (originalSurface, originalReading, originalCandidates, originalResult, originalInferredReading) を保存し、Esc で復元。

### 7.2 先読みのモデル (`ModelPredictor`)

`PredictiveAssistEngine` / `ModelPredictor.suggest(context, reading, surface)`:

1. surface 長 >= 2, reading 長 1〜512, unsafe token チェック
2. reading に漢字が含まれる場合は `ReadingCheck.readingForSurface(surface)` で読みを復元 (復元できなければ null)
3. 左文脈を 256 文字に切り詰め (空白→全角スペース)
4. 読み生成: `inference.complete(PREDICTION, "\uEE02$left\uEE00$target", 24, 800)` (24 トークン・800ms)
5. 句読点境界で短い句を取り出す (最低 2 文字の英数字)
6. かな文字のみ・長さ 2〜48 の検査
7. 表記変換: `inference.complete(PREDICTION, "\uEE02$left\uEE00$extended\uEE01", 32..256, 1000)` (最大 256 トークン・1000ms)
8. 変換結果が現在の表記で始まらなければ破棄
9. suffix を抽出。suffix に surface が含まれる場合は破棄
10. 「この記事」「上記」等の文脈根拠なし追加を除外
11. relevance = 60

### 7.3 妥当性の初期評価 (relevance)

Windows と同様:

| 候補 | 値 |
|---|---:|
| 自動反映の根拠がある補正 | 100 |
| 学習済みの手動補正 | 70 |
| 表記を維持し、出力検査を通過したモデル先読み | 60 |
| 独立した根拠のないモデル・規則補正 | 30 |

`AiAssistManager.choose` が `maxWithOrNull(compareBy relevance thenBy prediction)` で選択。

### 7.4 モデル・JNI

- `ZenzNative.assistSession()` が `AssistInference` を返す。`nativeGenerate(kind, prompt, maxTokens, timeoutMs, epoch)` を呼び出す。
- `nativeInitAssist(converterPath, correctorPath)` でアシスト用モデルを初期化。corrector と prediction の 2 つの kind をサポート (bitmask `assistReady`)。
- モデル epoch で世代管理。`modelRevision` と `nativeAssistEpoch()` で不一致を検出し、古い結果を破棄。
- アシストキャッシュ (LRU 64 件) で同一プロンプトの再計算を回避。
- JNI 実装: `android/app/src/main/cpp/zenz_jni.cpp`

### 7.5 設定・モデル

- モデルは `ModelManager` が assets から `filesDir` に展開。組み込みモデル 3 種 (Nano Q4_K_M 17.6MB, Nano Lite Q3_K_M 15.3MB, Standard Q4_K_M 61.3MB)。
- corrector モデルは別ディレクトリ (`filesDir/assist/corrector-*.gguf`) に SHA-256 で検証して展開。
- 辞書は `filesDir/dictionary` に展開 (LOUDS)。
- `predictiveModelState` は `"zerocon"` 固定 (旧設定は互換のため残存)。

---

## 8. 候補操作・文節・確定・学習

### 8.1 文節・範囲

`ConversionRangeSession` で変換範囲 (clause) を管理。`conversionRangeState` で UI に表示。候補選択時に範囲内の候補のみ許可。

### 8.2 再変換 (Reconversion)

`ReconversionPlan.kt` で再変換計画を管理。確定済み文字列から読みを推定して composition を再構築。

### 8.3 確定と学習

- 候補選択 (`selectCandidate`) で `KanaKanjiConverter.recordSelection` を呼び、学習記憶に記録。
- AI アシスト採用 (`adoptAiAssist`) は `corrections.apply` で内部状態を更新し、`triggerDebouncedConversion` で再変換。
- `TypoLearning` で補正の採用履歴を管理 (3 回以上で自動反映)。

### 8.4 確定後の続き候補 (post-commit continuation)

`commitText` は composition をすべて確定したとき `showPostCommitContinuations` を呼び、`KanaKanjiConverter.postCommitContinuations` が学習済み文節列から「次に続く語」を候補バーに出す。タップすると読み入力なしでその語を挿入する。

- 文の途中で続けられるよう、候補は確定後も表示したままにする。
- スペースと改行は文の区切りなので、その確定では続き候補を出さない。`commitText(offerContinuations = false)` を `confirmAsync` / `confirmCurrent` から渡し、`handleSpace` と `handleEnter` が指定する。`handleEnglishSpace` と改行送信前の `handleEnter` は `clearPostCommitCandidates` で既存の候補も消す。
- `showPostCommitContinuations` はカーソル直前が空白・改行なら何も出さない。確定済み文脈の末尾が区切り文字のときに古い続き候補が復活しないための二重の防御。

### 8.5 動的マクロ (`DynamicMacro`)

日時・計算などの決定的展開。Windows の `dynamic_macro.rs` に対応。

---

## 9. 設定・モデル

### 9.1 設定の流れ

`SharedPreferences` (`ime_settings`) → `ZeroConvertInputMethodService` の state → UI。設定変更は即時反映 (mutableStateOf)。

### 9.2 主な設定項目

| 設定 | 意味 |
|---|---|
| `experimental_ai_assist` | AI アシスト (補正 + 先読み) の有効化 |
| `auto_typo_correction` | 自動補正の有効化 |
| `auto_commit_enabled` | 自動確定の有効化 |
| `auto_commit_rewind_on_backspace` | 自動確定の巻き戻し |
| `realtime_inline_conversion` | リアルタイムインライン変換 |
| `ai_profile` | ユーザープロファイル (ZeroCon の profile) |
| `experimental_predictive_model` | 廃止 (互換のため残存、"zerocon" 固定) |

### 9.3 プライバシー (`InputPrivacy`)

パスワード欄等の機密入力では補正・先読み・学習を無効化。`isSensitiveInput()` で判定。

---

## 10. 表示 (UI)

- `ComposeKeyboardView` / `FlickKeyboardView`: キーボード + 候補表示 (Compose)
- `aiAssistResultState` で補正/先読みの結果を UI に反映
- `aiAssistAdoptedState` で採用済み状態を表示 (Esc で戻す)
- 候補一覧は `candidatesList` (mutableStateListOf)

---

## 11. 設計上の注意・制約

- メインスレッドのキー処理にモデル推論・ディスク I/O・大きな辞書探索・待機を入れない。
- 削除再入力で同じ読みになっても古い推論結果を適用しない。要求世代・入力状態・入力先・文脈・設定・モデルの変更を確認する。
- `onStartInput` / 入力ビュー開始 / 終了 / 選択範囲変更 / アプリ切替 / 画面回転 / モデル切替時の composition・Job・JNI 資源の扱いを確認する。
- 破棄済み InputConnection へ結果を反映しない。
- QWERTY と 12 キーフリック、濁点・半濁点・小文字、末尾未確定ローマ字、自動確定の巻き戻し等の既存動作を保持する。
- `app/src/main/cpp/llama.cpp/` は外部依存。独自変更は `patches/llama.cpp/` に保持する。
- x86 エミュレータだけで arm64 実機性能を保証しない。

---

## 12. 検証

```powershell
cd D:\angra\ZeroConvert-IME\android
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

JVM テストは Android API の既定値返却が有効で、JNI ロード失敗を捕捉する経路もある。成功だけで native 辞書・モデル・InputConnection・IME ライフサイクルの成功と判断しない。

Instrumentation テスト:

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
# 対象端末を -s で固定して am instrument で実行
```

端末反映:

```powershell
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

更新後は versionCode/versionName とインストール元 APK を照合し、設定画面と別の入力アプリで IME を確認する。

---

## 13. 探索の起点

| 症状 | 最初に読む |
|---|---|
| タイポ候補が生成されない・誤補正する | `assist/ModelCorrector.kt` → `TypoCorrectionEngine.kt` / `RomajiDecoder.kt` / `TypoLearning.kt` |
| 補正があるのに表示・Tab 採用できない | `ZeroConvertInputMethodService.kt` の `adoptAiAssist` / `corrections` → `assist/CorrectionSession.kt` / `AssistPresentation.kt` |
| 先読みが不自然・出ない | `assist/PredictiveAssistEngine.kt` → `assist/ModelPredictor` (in `PredictiveAssistEngine.kt`) → `ZenzNative.assistSession()` |
| 入力遅延・モデル接続 | `ZenzNative.kt` / `model/ModelManager.kt` / `cpp/zenz_jni.cpp` |
| 自動確定・削除・カーソル・文節の不整合 | `ZeroConvertInputMethodService.kt` → `azookey/input/ComposingText.kt` → `AutoCommitRewind.kt` |
| 設定変更が反映されない | `ZeroConvertInputMethodService.kt` の state → `MainActivity.kt` |
| モデル切替 | `model/ModelManager.kt` → `ZenzNative.loadModel` |
