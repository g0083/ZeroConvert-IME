# Android候補バーの先頭表示

2026-10-26。入力欄のライブ変換は最上位候補になっているが、横並び候補バーだけが途中から表示される問題。

## 原因と変更

`LazyRow` はスクロール状態を記憶し、`itemKey` が同じ候補を、候補の並べ替え後も画面内に維持する。
以前の入力で見ていた候補が新しい変換では後順位になると、先頭候補が左の画面外に隠れる。
変換順位そのものや入力欄の候補採用を変える問題ではない。

`ComposeKeyboardView.kt` の候補表示を、`KeyboardCandidateArea.kt` の
`HorizontalCandidateStrip` に分けた。現在の入力と先頭候補の `itemKey` で候補バーだけを
再作成し、新しい入力・先頭候補の変更時は左端から表示する。
同じ入力・同じ先頭候補での通常の再描画、スコア情報の更新、下位候補の追加では
手動スクロール位置を保持する。キー領域や変換・補正・先読み・確定処理は変更しない。

旧APKと識別できるよう、`:rebuilt` を `2.0.1-dev` / versionCode 3へ更新した。
旧構成 `:app` の版・出力先とは別に扱う。

## 検査

`KeyboardUiRegression.kt` の候補欄を製品と同じ部品にし、次の3件を追加した。

- 候補を横に送った後、入力を変更すると、同じ候補が残っていても先頭に戻る。
- 同じ入力で最上位候補が変わると、その候補が左端に出る。
- 手動で横に送った位置は、同じ先頭候補での再描画・スコア更新・下位候補追加では維持される。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-android.ps1 -SkipNative
# 対象はその都度確認し、アプリAPK/テストAPKの更新後に実行
adb -s <対象serial> shell am instrument -w -r -e keyboardUiOnly true com.zeroconvert.ime.test/com.zeroconvert.ime.PortInstrumentationRunner
```

単体は `:app` 124件・`:rebuilt` 17件成功、lintは0 errors / 28 warnings。
共通nativeの正本指紋、arm64-v8a/x86_64の各27ライブラリのABI/依存閉包を照合して
debugアプリAPKとテストAPKを作成した。共通エンジン自体の変更・再ビルドは不要だった。
読み取り専用エミュレーター / API36.1 / x86_64で、UI部品検査は既存5件＋追加3件の8件すべて成功。
前回の英字Enter修正は製品コードに保持している。

現在vivoはADB接続一覧に出ていないため、今回の2.0.1-devの実機更新・実IME確認は未実施。
前回の2.0.0-devの実機成功は、今回の修正版の実機検証として扱わない。
Gitコミット/push・正式release署名・公開は行っていない。

固定APKは `dist/android/20261026-candidate-scroll/ZeroConvert-IME-2.0.1-dev-debug.apk`。
SHA-256: `caa6a819a21ca119590da2b2bfa284cb45e7cc65f9d16b3d6394ea675e0ab42c`。
証明書SHA-256: `ed89c1107e5d4c2e543bf37a18fd63acec06e8f2482e13802c7945a9578ff3a5`。
同フォルダにSHA256SUMS・artifact.json・README.mdを保存。APK内のモデル名とSHA-256も記録した。
