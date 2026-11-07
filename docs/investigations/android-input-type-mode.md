# Android: 入力先の inputType に合わせたキーボードモード自動切替（実装計画）

状態: 実装計画（未実装）。対象: `android/` のみ。Windowsは別計画
[windows-input-scope-mode.md](windows-input-scope-mode.md) を使う。

## 目的

フォーカスした入力欄の `EditorInfo.inputType` / `imeOptions` が「数値・電話・日時・
ASCII限定」等を宣言している場合、キーボード表示時にその意図へ
`KeyboardMode`（`JAPANESE` / `ENGLISH` / `NUMERIC`）を合わせる。

- 合わせるのは**入力ビュー開始時のみ**。ユーザーが「あ/A/1」キーでモードを変えたら、
  その選択は次の入力欄まで保持する（再適用しない）。
- 判定できない（`TYPE_CLASS_TEXT` の通常テキスト等）なら**現在のモードを維持**する。
- 設定で無効化できる。既定は無効（既存ユーザーの挙動を変えない）。

## 前提: 取得できる情報と限界

`EditorInfo.inputType` の `TYPE_MASK_CLASS` / `TYPE_MASK_VARIATION` と `imeOptions`。
既に読んでいる箇所:

- `android/app/src/main/java/com/zeroconvert/ime/assist/InputPrivacy.kt:7`
  `isSensitive(inputType, imeOptions)` — パスワード系の判定
- `ZeroConvertInputMethodService.kt:1503` `isSensitiveInput()` — 上記のラッパ
- `ZeroConvertInputMethodService.kt:1475` `inputType = currentInputEditorInfo?.inputType ?: 0`

**Android は「日本語か英語か」を宣言しない。** 判別できるのは以下だけ:

| 判定 | 条件 |
| --- | --- |
| `NUMERIC` | `TYPE_CLASS_NUMBER`, `TYPE_CLASS_PHONE`, `TYPE_CLASS_DATETIME` |
| `ENGLISH` | `imeOptions and IME_FLAG_FORCE_ASCII != 0`、または `TYPE_CLASS_TEXT` + `TYPE_TEXT_VARIATION_URI` / `EMAIL_ADDRESS` / `WEB_EMAIL_ADDRESS` / `EMAIL_SUBJECT` / `PERSON_NAME` / `POSTAL_ADDRESS` |
| 維持（`null`） | 上記以外すべて（`TYPE_CLASS_TEXT` の通常テキスト、`TYPE_NULL`、パスワード系） |

パスワード系は `InputPrivacy.isSensitive` が真なら**必ず維持**（`null`）。
`TYPE_TEXT_VARIATION_FILTER` / `PHONETIC` も維持する（日本語入力のまま使う意図がある）。

## 設計

### 1. 判定は純粋関数に閉じる（新規ファイル）

`android/app/src/main/java/com/zeroconvert/ime/InputTypeMode.kt` を新設する。

```kotlin
object InputTypeMode {
    fun modeFor(inputType: Int, imeOptions: Int): KeyboardMode?
}
```

- 戻り値 `null` は「意見なし = 現在のモードを維持」。
- `KeyboardMode` は `ComposeKeyboardView.kt:56` の既存 enum をそのまま使う
  （新規 enum を作らない）。
- `InputPrivacy.isSensitive(inputType, imeOptions)` が真なら即 `null`。
- `InputType` / `EditorInfo` の定数は `android.text.InputType` /
  `android.view.inputmethod.EditorInfo` を参照する。JVM 単体テストで動くよう、
  `android.jar` の定数（`const`）だけを使い、フレームワーク呼び出しを入れない。

### 2. `KeyboardMode` を Service 側へ持ち上げる（必須の構造変更）

現状 `KeyboardMode` は `ComposeKeyboardView.kt:90` の
`var keyboardMode by remember { mutableStateOf(KeyboardMode.JAPANESE) }` という
**Compose ローカル状態**で、Service から触れない。自動適用には Service からの
初期値注入が必要なので、次のどちらかを選ぶ。**推奨は (a)**。

**(a) Service に `mutableStateOf` を持たせ、Compose はそれを読む（推奨）**

- `ZeroConvertInputMethodService.kt` の既存状態群（`:237` 付近、`isRealtimeInlineState`
  などと同じ並び）に追加:
  ```kotlin
  val keyboardModeState = mutableStateOf(KeyboardMode.JAPANESE)
  ```
- `ComposeKeyboardView.kt:90` を削除し、`imeService.keyboardModeState.value` を読む。
  書き換え箇所は `:90`, `:131`, `:284`, `:413`, `:427`, `:456` の6箇所のみ
  （`onCycleMode` の `when` は `imeService.keyboardModeState.value = ...` に変更）。
- 既存の `beforeKeyboardModeChange()`（`ZeroConvertInputMethodService.kt:380`）は
  そのまま使う。モード変更前に未確定入力を確定する既存動作を壊さないこと。
- `onStartInputView`（`:2828`）で `InputTypeMode.modeFor(...)` の結果を
  `keyboardModeState.value` に反映する。`null` なら**触らない**。
- 設定 OFF なら何もしない。

**(b) `KeyboardRootView` に初期モード引数を追加する**

`KeyboardRootView(initialMode: KeyboardMode = KeyboardMode.JAPANESE, ...)` を足し、
`remember(initialMode) { mutableStateOf(initialMode) }` にする。
`onCreateInputView`（`:1680`）で Service の値を渡す。
変更は小さいが、`onCreateInputView` は入力欄ごとに呼ばれないことがあるため
`onStartInputView` での反映が効かない場合がある。**(a) を優先する。**

### 3. 設定項目

`ZeroConvertInputMethodService.kt` の `companion object`（`:86` 付近、既存 `KEY_*` の並び）に追加:

```kotlin
const val KEY_FOLLOW_INPUT_TYPE_MODE = "follow_input_type_mode" // boolean, default false
```

- `reloadPreferences()`（`:1313`）に読み込みを1行追加:
  ```kotlin
  isFollowInputTypeModeState.value = prefs.getBoolean(KEY_FOLLOW_INPUT_TYPE_MODE, false)
  ```
- 状態は既存の並び（`:237` 付近）に `val isFollowInputTypeModeState = mutableStateOf(false)`。
- 設定画面: `MainActivity.kt` の `InputTabContent()`（`:427`）の「キーボード」セクション
  （`:450`）内、`SettingsSwitchRow`（`:640` の「上部の数字キー」等）の並びに追加する。
  ```kotlin
  SettingsSwitchRow(
      title = "入力欄に合わせてモードを切替",
      description = "数値・電話・URL・メール等の入力欄では、その入力欄に合ったモードで"
          + "キーボードを開きます。開いた後に切り替えたモードはそのまま使えます。",
      checked = isFollowInputTypeMode,
      palette = palette,
      onCheckedChange = { checked ->
          isFollowInputTypeMode = checked
          imePrefs.edit()
              .putBoolean(ZeroConvertInputMethodService.KEY_FOLLOW_INPUT_TYPE_MODE, checked)
              .apply()
      }
  )
  ```
  `isFollowInputTypeMode` は `MainActivity.kt` の既存 `remember { mutableStateOf(...) }`
  群（`:178` 付近）に、`imePrefs.getBoolean(KEY_FOLLOW_INPUT_TYPE_MODE, false)` で追加する。

### 4. 適用タイミング

`ZeroConvertInputMethodService.kt:2828` `onStartInputView(info, restarting)`。

```kotlin
override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    exitTranslationMode(revert = false)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    reloadPreferences()
    clearInput()
    applyInputTypeMode(info)          // 追加
    hasSelectionInEditor = ...
}
```

`applyInputTypeMode` の要件:

- `isFollowInputTypeModeState.value` が false なら return。
- `info == null` なら return。
- `InputTypeMode.modeFor(info.inputType, info.imeOptions)` が `null` なら return。
- 現在の `keyboardModeState.value` と同じなら return（無駄な再コンポーズを避ける）。
- 異なる場合のみ `keyboardModeState.value = mode`。
- **`beforeKeyboardModeChange()` を呼ばない**。入力ビュー開始直後は未確定入力が無く、
  呼ぶと `confirmCurrent()` が走って不要な確定処理になる。
- メインスレッドで完結させる。I/O・待機・推論を入れない。

`onStartInput`（`:2840`）には**入れない**。`onStartInput` は入力ビューを伴わない
ケース（ハードウェアキーボード等）でも呼ばれ、UI 状態と食い違う可能性がある。
`onStartInputView` のみで完結させる。

### 5. ユーザー操作との関係

- 「あ/A/1」キー（`onCycleMode`、`ComposeKeyboardView.kt:407`）は
  `keyboardModeState.value` を書き換えるだけ。自動適用は `onStartInputView` でしか
  走らないので、入力中に押した切替は上書きされない。
- 同じ入力欄に戻った場合は再度 `onStartInputView` が走るため再適用される（意図どおり）。
- 翻訳モードは `exitTranslationMode(revert = false)` が先に走るため、追加処理はその後。

## テスト

### 必須（JVM 単体テスト）

`android/app/src/test/java/com/zeroconvert/ime/InputTypeModeTest.kt` を新設する。
`InputTypeMode` は Android フレームワークを呼ばないため JVM テストで動く
（`InputPrivacy` の既存テスト `CorrectionSessionTest.kt:125` と同じ方式）。

- `TYPE_CLASS_NUMBER` → `NUMERIC`
- `TYPE_CLASS_PHONE` → `NUMERIC`
- `TYPE_CLASS_DATETIME` → `NUMERIC`
- `TYPE_CLASS_TEXT` 単体 → `null`
- `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI` → `ENGLISH`
- `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_EMAIL_ADDRESS` → `ENGLISH`
- `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS` → `ENGLISH`
- `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PERSON_NAME` → `ENGLISH`
- `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_POSTAL_ADDRESS` → `ENGLISH`
- `TYPE_CLASS_TEXT` + `IME_FLAG_FORCE_ASCII` → `ENGLISH`
- `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD` → `null`（機密優先）
- `TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD` → `null`
- `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_FILTER` → `null`
- `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PHONETIC` → `null`
- `TYPE_NULL` → `null`
- `imeOptions` に `IME_FLAG_NO_PERSONALIZED_LEARNING` がある `TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_URI` → `null`

### 実行する確認

```
cd android
./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`assembleDebug` は APK 作成まで。**端末への反映は行わない**（このタスクの範囲外）。

### 手動確認（この計画では実施しない）

端末反映は依頼された場合に別途行う。観点（後続作業者向け）:

1. 設定で ON にする。
2. メモ帳等の通常テキスト欄 → 日本語のまま。
3. 数値入力欄（電卓・電話番号欄）→ 数字モードで開く。
4. ブラウザのアドレスバー / メールアドレス欄 → 英字モードで開く。
5. パスワード欄 → モードが変わらない。
6. 自動適用後に「あ/A/1」キーでモードを変え、そのまま入力 → 上書きされない。
7. 別の入力欄へ移る → 移動先の inputType で再適用される。
8. 12キー/QWERTY の両方で、モード表示（あ/A/1 の強調）が実際のモードと一致する。

## 触ってはいけないもの

- `windows/` 配下（別エージェントが並行作業中）。
- `docs/investigations/README.md`（統合時に親が更新する）。
- `app/src/main/cpp/llama.cpp/`、`patches/llama.cpp/`、モデル重み、`dist/`。
- 既存の `InputPrivacy.isSensitive` の判定内容（パスワード判定を緩めない）。
- 既存の `beforeKeyboardModeChange()` の確定動作。
- 端末への `adb install`、署名、配布。
- Git コミット・push。

## 注意: 既存の未コミット変更

作業開始時点で `android/app/src/main/java/com/zeroconvert/ime/` 配下に未コミット変更がある
（`ComposeKeyboardView.kt`, `MainActivity.kt`, `ZeroConvertInputMethodService.kt`,
`SettingsUi.kt`, `KeyboardCandidateArea.kt`, `ZeroConTheme.kt` と関連テスト）。
これらは**他者の作業**である。`git reset` / `restore` / `clean` / `stash` を実行せず、
自分の変更と混同しないこと。編集前に必ず現在の内容を読み、既存差分を保持する。

## 完了条件

- 上記の JVM テストが通り、`:app:lintDebug` と `:app:assembleDebug` が成功する。
- 設定画面に項目が表示され、`follow_input_type_mode` として保存される。
- 変更ファイルと実行コマンド・結果を報告する。
- 端末未反映・実IME未確認であることを明記する。
