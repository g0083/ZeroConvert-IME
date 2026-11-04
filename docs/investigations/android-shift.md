# Android QWERTYのShift操作

2026-10-26。日本語/英字QWERTYのShiftを、1文字だけの大文字入力と二度押しによる大文字固定に変更した。

- Shiftを1回押すと、次の文字だけ大文字/シフト記号になる。入力後は通常へ戻る。
- OSのダブルタップ判定時間以内にShiftを2回押すと、大文字固定になる。
- 固定中にShiftを押すと解除する。固定中はCaps Lockのアイコンと状態説明を表示する。
- 1回押しの状態で時間を空けてShiftをもう一度押すと、固定せず解除する。

`KeyboardShiftState.kt` が状態と文字の決定を扱い、`ComposeKeyboardView.kt` が
両QWERTYから同じ状態を利用する。判定にはmonotonicなuptimeを使い、1回目を遅延させない。
文字入力後には1回押しの状態と二度押しの起点を消す。連続入力時に以前の大文字表示からの
イベントが届いても、現在の状態で文字を決め、次の文字まで大文字になることを防ぐ。
数値行のシフト記号にも適用する。空白入力は1回押しを消費するが、大文字固定は保持する。
12キーのa/Aによる末尾文字の大小切替は今回のShift操作の対象外。

変更ファイルは `ComposeKeyboardView.kt`、新設 `KeyboardShiftState.kt`、
新設 `KeyboardShiftStateTest.kt`、`:rebuilt` の版とテスト選択設定、本文書。
`:rebuilt` は2.0.2-dev / versionCode 4。既存の英字Enter・候補表示修正を含む。

検査は `scripts/build-android.ps1 -SkipNative`。共通nativeの指紋・両ABI各27ライブラリの
依存閉包を照合し、単体は従来版135件・共通エンジン版28件、失敗0。
Shift専用11件を両モジュールで実行し、1文字入力、連打固定、解除、時間境界、文字入力による
二度押し判定の解除、古い大文字/記号表示からの連続入力、空白・句読点を検査した。
lintは0 errors / 28 warnings。debugアプリAPK・テストAPKのビルドも成功。

ユーザーの指示により、ADBコマンド・実機操作・端末更新は行っていない。実際の打鍵はユーザー側で確認する。
Gitコミット/push・正式release署名・公開も未実施。既存の差分とブランチを保持した。

固定APK: `dist/android/20261026-shift/ZeroConvert-IME-2.0.2-dev-debug.apk`。
SHA-256: `5016c5b6ab25a28eac04ef2ebc9e274919a06b32ff2d5769ff1954b31ce65652`。
同フォルダに証明書・モデル・版・検査を記録したartifact.jsonとSHA256SUMSを保存する。
