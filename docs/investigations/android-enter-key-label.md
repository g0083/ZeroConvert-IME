# Android Enter キーの表示ラベル

> [!NOTE]
> **状態**: 実装済み（2026-11-26。JVM 単体テストとデバッグ APK のビルドまで確認、実機の入力欄では未確認）
> **現行仕様・実装先**: [android-conversion-logic.md](../android-conversion-logic.md) / [EnterKeyAction.kt](../../android/app/src/main/java/com/zeroconvert/ime/EnterKeyAction.kt)

## 目的

入力欄が `EditorInfo.imeOptions` で宣言した編集アクションを Enter キーに表示し、押したときにそのアクションを送る。QWERTY・数字・12 キーフリック・絵文字パネルの Enter を同じ規則に揃える。

## 表示規則

判定は [EnterKeyAction.kt](../../android/app/src/main/java/com/zeroconvert/ime/EnterKeyAction.kt) に閉じる。

- `IME_FLAG_NO_ENTER_ACTION` がある複数行入力欄では表示しない。Enter は改行のまま。
- 変換中（`currentComposingSurface` が非空）は表示しない。Enter は確定操作なので、QWERTY はリターン矢印、12 キーと数字は「確定」のまま。
- `IME_MASK_ACTION` が `IME_ACTION_GO` / `SEARCH` / `SEND` / `NEXT` / `DONE` / `PREVIOUS` のときだけラベルを出す。表示は「移動」「検索」「送信」「次へ」「完了」「前へ」。
- `IME_ACTION_NONE`、`IME_ACTION_UNSPECIFIED`（未指定）、その他の値はラベルなし。絵文字パネルは従来どおり「改行」。

変換中でも、確定後の次の Enter には入力欄のアクションが表示され、そのまま実行できる。

## 実装

- `ZeroConvertInputMethodService.kt`: `onStartInput` / `onStartInputView` で `imeOptions` を `enterKeyImeOptionsState` に写し、`onFinishInput` で 0 に戻す。1 つの入力ビュー内で入力欄が変わってもラベルが追随する。
- `ComposeKeyboardView.kt`: `KeyboardRootView` が変換状態と `imeOptions` から `LocalEnterKeyAction` を 1 フレームに 1 回決め、配下の全 Enter キーが同じ値を使う。
- `handleEnter()`: 未確定の読み・英字が無いとき、ラベルに対応する `performEditorAction` を送る。入力欄が受け付けない、または接続が無い場合は従来どおり `sendKeyChar('\n')` で改行する。
- ラベルは QWERTY（`SharedQwertyLayout`）、12 キーと数字（`FlickSideEnterButton`）、絵文字パネル（`EmojiPanel`）に適用する。記号パネルに Enter キーは無い。

## 検証

- `EnterKeyActionTest`（JVM）: 各アクションの対応、複数行フラグ、変換中、未指定・NONE、無関係なフラグの除外を確認する。
- 実機で確認する入力欄: 検索ボックス（`IME_ACTION_SEARCH`）、送信ボタン付きメッセージ欄、`nextFocusForward` を設定したフォーム、複数行メモ欄（`IME_FLAG_NO_ENTER_ACTION`）。ラベル、変換中の矢印、押下後の動作（検索開始・フォーカス移動・改行）を順に確認する。
