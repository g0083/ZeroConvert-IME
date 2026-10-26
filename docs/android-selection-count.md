# Androidの選択文字数表示

2026-10-26。入力欄の選択範囲の文字数を、既存の48dpの変換候補バーに表示する。
選択中は「12文字選択」のように表示し、選択解除で通常の候補/ツールバーへ戻る。
クリップボード提案に隠れないようにし、再変換操作は同じ段に置く。

`ZeroConvertInputMethodService.kt` が選択通知と入力開始時の選択範囲を受け取り、
`SelectionCharacterCount.kt` で文字数を求める。選択ハンドルの移動中は75ms待ってから
ワーカーで選択文字列を取得し、見た目の文字単位で数える。
Unicode拡張書記素の判定は [AndroidのPattern仕様](https://developer.android.com/reference/java/util/regex/Pattern.html) の `\X` を使う。
サロゲートペア、濁点などの結合文字、家族絵文字、国旗、肌色付き絵文字のまとまりを扱う。
空白と改行も数に含める。

取得前/取得不能時、秘匿入力欄、131072 UTF-16単位を超える範囲では、通知された範囲長から
「約12文字選択」と表示する。秘匿欄の選択文字列は取得しない。
取得と計算は入力メインスレッドを待たせず、専用Mutexで同時取得を1件に制限する。
計算中も定期的に取消を確認し、範囲変更・入力先変更・入力終了・再変換後の古い応答は反映しない。
選択文字列を設定、学習、ログ、配布記録へ保存しない。表示状態に持つのは文字数だけ。

UI変更は `ComposeKeyboardView.kt` と `KeyboardCandidateArea.kt`。
モデルは新設 `SelectionCharacterCount.kt`、検査は新設 `SelectionCharacterCountTest.kt`。
`:rebuilt` を2.0.4-dev / versionCode 6に更新した。

検査は `scripts/build-android.ps1 -SkipNative`。単体は従来版163件・共通エンジン版59件、
計222件すべて成功。文字数専用12件を両モジュールで実行し、順方向/逆方向、選択解除、
日本語/英字、絵文字/結合文字、空白/改行、取得不能・古い文字列、大範囲、取消、同位置再選択、
入力終了時の世代無効化を検査した。lintは0 errors / 28 warnings。
共通nativeの指紋・両ABIの依存閉包を確認し、debugアプリAPK/テストAPKを作成した。

ユーザーの指示によりADB・実機操作・実機への反映は行っていない。
Gitコミット/push・正式release署名・公開も未実施。既存の差分とブランチを保持している。

固定APK: `dist/android/20261026-selection-count/ZeroConvert-IME-2.0.4-dev-debug.apk`。
SHA-256: `d304b2d88e8363378a7788d32940dff4f8f6cb6d4baac3f9636f93522026a3f9`。
版・署名・モデル・検査は同フォルダのartifact.json、ハッシュはSHA256SUMSに保存した。
