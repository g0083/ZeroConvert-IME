# Android英字モードのEnter確定

2026-10-26。QWERTY/12キー英字でEnterが入力中の綴りを先頭補完候補へ置き換える問題。

## 原因と修正

`ComposeKeyboardView.kt` の英字用Enterは、`commitEnglishBuffer()` を呼んだ後に
共通のEnterを呼んでいた。前者は先頭候補を採用する処理であり、その後に改行も送っていた。

両レイアウトのEnterを共通の `handleEnter()` へ渡す。
英字が未確定なら `commitEnglishBuffer(useTopCandidate = false)` で元の綴りを確定して終了する。
候補の自動採用や、その操作に伴う候補選択学習は行わない。確定後のEnterは既存の入力先への
Enter送信処理を使う。通常の複数行入力欄では改行になる。
候補タップでの明示的な補完、Space/句読点での既存の補完動作は維持する。

製品変更は `app/src/main/java/com/zeroconvert/ime/ZeroConvertInputMethodService.kt` と
`ComposeKeyboardView.kt` の2ファイル。既存の未コミット変更を保持した。

## 回帰検査

`PortInstrumentationRunner.kt` に、実IMEの両レイアウト用検査を追加した。

1. `he` を入力し、異なる綴りの補完候補が表示されるまで待つ。
2. 削除→再入力した後、Enterで `he` 自体が未確定範囲を残さず確定することを確認する。
3. 次のEnterで `he\n` になることを確認する。
4. 再度 `he` を入力し、候補をタップすれば補完できることを確認する。

12キーでは `ghi` と `def` を左フリックして実際に `he` を入力する。
検査終了時には元のレイアウト設定を復元する。従来のUI検査5件・基本検査18件と区別する。

```powershell
# リポジトリルート。既存Windows変更を含む共通nativeを両ABIで再ビルド。
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-android.ps1
cargo test --locked --manifest-path engine/rust/Cargo.toml --lib -- --test-threads=1
# 対象端末の確認・互換署名での更新後
adb -s <対象serial> shell am instrument -w -r com.zeroconvert.ime.test/com.zeroconvert.ime.PortInstrumentationRunner
```

実機接続・更新・検査の完了はAPK作成と別に記録する。

## 今回の結果

- `scripts/build-android.ps1` 成功。arm64-v8a/x86_64の共通nativeを再ビルドし、正本指紋・各27ライブラリのABI/依存閉包を照合した。
- 単体テストは `:app` 124件、`:rebuilt` 17件、失敗0。
- 共通Rustは52成功、実モデル用1除外。lintは0 errors / 28 warnings。
- debugアプリAPK・テストAPKの作成成功。
- 接続切れによる保留後、再接続されたOPD2415 / Android16 / API36 / arm64-v8aへ更新した。
  実機のbase.apkと下記固定APKのSHA-256が一致し、既定IME・設定・学習データを保持した。
- 実IME検査20件すべて成功。既存18件に加え、QWERTY/12キー英字の両方で
  削除再入力→補完候補表示→Enterで元の `he` を確定→次のEnterで改行→候補タップで補完を確認した。
- 英字検査の初回はモード切替直後に古い画面を参照して2件失敗した。
  テストの画面キャッシュ更新・モード変更待ち・ケース開始時の入力セッション初期化を修正し、
  同じアプリAPKで20件を再実行して失敗0を確認した。失敗と最終成功を区別して記録する。
- Gitコミット/push・正式署名/配布は未実施。既存ブランチ・差分を保持した。

固定APKは `.local/android-english-enter-20261026/zerocon-english-enter-debug.apk`。
`com.zeroconvert.ime` / `2.0.0-dev` / versionCode 2 / debug / arm64-v8a,x86_64。
SHA-256: `97a8cecdec785796a6b9a3f86396f3842131e5b5c1b0cd3d8460d30672fc44ba`。
署名証明書SHA-256: `ed89c1107e5d4c2e543bf37a18fd63acec06e8f2482e13802c7945a9578ff3a5`。
対応するテストAPKも同フォルダの `zerocon-english-enter-test.apk` に固定した。

## vivoへの再導入と `get` の確認

vivo V2520A / Android16 / arm64-v8aで、選択中IMEはZeroConvertだったが、
導入済みAPKは旧構成の1.0.0 / versionCode 1だった。
SHA-256 `4f08033178cd7eafcc0c950196422eaee0459cf2e447e2e10615929172aeba9c` は、
PCに残っていた `android/app/build/outputs/apk/debug/app-debug.apk` と一致した。
OPDで確認した修正版とは別のAPKであり、vivo固有のEnter不具合とは判断しない。

既存APKを保全し、証明書が互換であることを確認して、OPDと同じ上記97a8…のAPKへ更新した。
端末内base.apkのSHA-256も一致。アンインストール・設定/学習データの消去・既定IME変更は行っていない。

英字の実IME検査を `he` / `get` × QWERTY/12キーの4件へ拡張した。
vivoの両レイアウトで、候補に `getting` が見えていてもEnterでは `get` が確定し、
次のEnterで改行、候補をタップした場合は `getting` が採用されることを確認した。
報告されたケースに対する製品ソースの追加変更は不要だった。

検査側では、直前の `g` / `ge` の候補ではなく `getting` を待つよう調整した。
また、既存のキー座標検査で表示直後の不安定さがあったため、入力前に日本語モードへの
切替完了と300msの座標安定を待つようにした。入力後の座標不変の検査は維持する。
テストAPK更新に必要なvivo側の指紋認証完了後、最終22件の実機検査がすべて成功した。
最初の2回は候補タップ検査・表示直後の座標検査で各1件失敗し、上記の検査待機処理を
調整して再実行した。同じ修正版アプリAPKを使い、製品コードの追加変更はしていない。
テストAPKの再ビルドとlintも成功し、0 errors / 28 warningsを確認した。

再導入用の同一APKは `dist/android/20261026-vivo-english-enter/ZeroConvert-IME-2.0.0-dev-debug.apk`。
SHA256SUMS・artifact.json・README.mdも同フォルダに保存した。これは開発・限定試験用debug版。
