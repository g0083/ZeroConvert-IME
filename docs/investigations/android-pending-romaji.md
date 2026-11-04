# Androidの未完ローマ字の誤補正防止（2.0.8-dev）

2026-10-26、Androidで `nan` / `non` / `man` と入力して待つと、それぞれ
「晩」「本」「間」に置き換わり、次の母音で元の読みを続けられなくなる問題を修正した。
vivoの2.0.7-devで3例を再現してから、同じ端末の2.0.8-devで修正を確認した。

## 原因と変更

AndroidのComposingTextでは、`nan` は「なn」であり、末尾のnは次のiで「に」に
なる未完入力。Androidのアシスト呼び出しには、未完入力をモデル補正へ渡さない条件が
不足していた。共通エンジンの補正結果を自動適用すると原打鍵が直接表記に変わり、
続きのiが元のnと結合できなくなる。

Androidホスト側で末尾のASCII英字を未完ローマ字と判定する条件を共通化した。
通常の候補取得は継続し、未完の間は補正・先読み・自動確定を開始しない。
ModelCorrector / ModelPredictorでもWindowsEngineへ渡す前に拒否する。
学習済みの補正や遅れて到着した自動補正も未完入力を置換できないよう、
AutoCorrectionEvidenceとCorrectionSessionにも同じ保護を入れた。

母音で読みが完成したら通常経路へ戻る。`nn`で完成した「ん」の通常変換、
明示的な候補の採用、内部の浮き子音タイポ（例「おkせり」）の扱いは維持する。
Windowsソース、共有Rust/Swift、モデル、既存の学習データはこの修正では変更していない。

## 検証

`scripts/build-android.ps1 -SkipNative` 成功。nativeを変更していないため再ビルドせず、
両ABIのソース/バイナリ指紋、依存閉包、CPUバックエンド、APK内のモデルを照合した。
最終のテストAPKと `:rebuilt:lintDebug` も再ビルド・実行した。

- `:app:testDebugUnitTest` 183件、`:rebuilt:testDebugUnitTest` 79件成功。
  新規PendingRomajiTest 9件を両構成に含む。報告3例、各かな行、末尾英字52種、
  推論前の拒否、学習済み補正・遅延した自動適用の拒否、削除/再入力などを確認。
- lint：エラー0、警告28。
- V2520A / Android 16 / API 36：未完ローマ字の実機10件成功、失敗0。
  ローマ字補正のモデルを呼ばないこと、完成した「ん」の候補取得に加え、
  各3例で待機→iの追加入力→削除→nの再入力/削除→niの再入力を検査。
  ライブ変換ONでは通常プレビューを許し、OFFへ切り替えてからiを追加入力して
  原打鍵が破壊されていないことも検証した。
- 同じ製品APKで既存24項目成功、失敗0。カーソル前変換、部分確定、選択文字数、
  両配列の英字Enterを含む。クリップボードUIも7件成功、失敗0。
- 試験のnative初期化・削除キー操作を既存ランナーと揃えた。
  カーソル試験の初回1件失敗はライブ変換ONを仮定していたためで、
  試験時だけONにして元の状態へ戻すように修正し、全件再実行して成功。
- 設定・配列・学習書込み条件は復元。端末のライブ変換OFFは変更後もOFFを確認。
  本体設定画面の起動、versionCode/versionName、端末内APKのSHA-256一致も確認。

今回の検査は入力状態の保護であり、モデル精度や遅延の全体ベンチマークではない。

## 成果物

`dist/android/20261026-pending-romaji/ZeroConvert-IME-2.0.8-dev-arm64-debug.apk`

- `com.zeroconvert.ime` / versionCode=10 / ARM64 / 署名済みdebug。
- SHA-256：`bbdf2c8aed6fba8646ce044993d63d6805def50067769cbd1b74307e4bd5d3de`。
- vivoへ上書き更新して検証した同じAPKを固定保存。クリップボード期限設定、
  ピン留め除外、カーソル変換、文字数カウントの修正も含む。
- 詳細は同フォルダのartifact.jsonとSHA256SUMS。ログは
  `.local/android-pending-romaji-20261026/`。

正式release署名、ストア公開、Gitコミット/pushは未実施。
