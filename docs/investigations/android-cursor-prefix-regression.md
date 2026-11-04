# Androidのカーソル前変換の復旧（2.0.6-dev）

2026-10-26、2.0.5-devで、入力中に矢印キーで戻るとカーソル前の変換候補が出ない問題を修正した。選択文字数表示と、2.0.5-devの速度・容量改善は維持する。

## 原因と修正

共通Swiftの `get_composed_text_for_cursor_prefix(requiredInputCount:lengthPtr:)` は、
非負の引数を「原打鍵の厳密な境界」、負数を「呼び出し側からの境界指定なし」として扱う。
AndroidBridge.swiftは0を渡しており、空でないカーソル前入力が境界不一致で拒否されていた。
Androidの適合層で-1を渡し、スナップショットのカーソル位置に従って変換させる。
Windows正本の変換処理、Androidのカーソル管理、文字数カウントは変更しない。

以前のnativeカーソル変換テストはAI無効の再変換経路だけを確認していたため、
今回の不具合を検出できなかった。AI有効・無効それぞれでローマ字/直接かなの
「とうきょう｜にいく」を変換し、「東京」の候補と消費範囲、右側入力の維持を検査する。
実IMEテストも追加し、QWERTY打鍵→左矢印3回→「東京」のプレビュー/キャレット→
右/左矢印→「東京」だけ確定→残りの確定→選択文字数表示を確認する。
テストで変更する配列・自動確定設定はfinallyで元の値に戻し、学習書込みを抑止する。

## 確認結果

- `scripts/build-android.ps1`：両ABIのnativeビルド、指紋検査、APK内依存・モデル照合に成功。
- `:app:testDebugUnitTest` 163件、`:rebuilt:testDebugUnitTest` 59件成功。
- `:rebuilt:lintDebug`：エラー0、警告28。
- `cargo test --manifest-path engine/rust/Cargo.toml --lib -- --test-threads=1`：52件成功、ライブモデル必須の1件を除外。初回の制限環境では一時ファイル・模擬通信のテストが失敗し、必要な権限で再実行して成功した。
- V2520A / Android 16 / API 36：署名互換の上書き更新後、`PortInstrumentationRunner` 24件成功、失敗0。カーソル変換・部分確定・文字数カウントと、両配列の英字 `he` / `get` + Enterを含む。
- 設定画面でZeroCon Model v3.2 Nanoの準備完了を確認。端末内APKと固定配布APKのSHA-256が一致。

検証用の同じ署名済みdebug APKは
`dist/android/20261026-prefix-regression/ZeroConvert-IME-2.0.6-dev-arm64-debug.apk`。
versionCode=8、applicationId=`com.zeroconvert.ime`、SHA-256は
`63b93fb8b55ed171d247e5ae29b17665c0053759df7b1748f56f00b2bf448636`。
詳細は同フォルダのartifact.json、ローカルログは
`.local/android-prefix-regression-20261026/`。正式release署名・ストア公開・Git保存は実施していない。
