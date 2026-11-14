# 共通エンジンの辞書補正とAndroidタッチ重み付け

2026-11-14、`feature/shared-assist-adoption` の未コミット作業を検証した。

## 実装

`experimental_typo_correction` をWindows・Androidとも新規設定／未指定時に有効とした。
Rustのデシリアライズ、Swiftの設定読込・初期状態、Windows設定画面・配布初期値、
Androidの設定画面・IME読込・通常変換APIをそろえた。保存済みのfalseは保持する。

Androidはタッチ位置の候補キーと重みを入力スナップショットに添付する。
Windows正本の `KeyboardCorrectionPolicy.swift` が近接キーを最大4件照合し、
通常候補へ統合する。元の読みの辞書照合は追加で最大1件となる。
完全一致する実在語を先頭に保護し、通常候補に含まれる全カタカナ生成候補はこの判定から外した。
補正候補は元の入力消費数を保持する。採用前の原打鍵と読みを変更せず、自動学習も行わない。

補正候補は `predictionReading` で正しい読みを返す。Windows IPCとAndroid JSONを通して
共通Rustへ渡し、その読みで先読みする。通常候補で補正済みの場合はAIによる再補正を抑える。
アシストの優先順位、採用・取消、学習通知は先行作業の `assist_policy.rs` を両OSが使用する。

「予測タッチ補正（当たり判定）」と「タッチ位置による候補の重み付け」は別の設定とした。
日本語の重み付けは辞書補正がONのときに通常変換で実行する。
英語の補正候補順位にもタッチ位置を反映する。当たり判定はAndroidが扱う。

## 比較方法

`benchmarks/keyboard-correction/run.ps1` で同一入力の補正OFF／ONを比較する。
登録タイポ92件、正常入力156件、互換ローマ字入力45件、文脈付き未登録タイポ255件を使用した。
正常入力156件は登録語の正しい読み92件、追加19件、既存データの正常45件からなる。
既存300件データには学習データとの重複監査を行っていない。
打鍵は `.roman2kana` で再現し、学習を無効にした。Windowsのカスタムローマ字表での実入力は別途確認する。

補正後キー列が現在のパーサーで正しい読みにならない場合は、互換入力の正例として記録した。
正常入力の比較には確定可能なローマ字列を使う。元の `corrected_keys` を一律に正常とすると、
未確定nなど45件の異なる読みが混ざるため、この扱いを記録した。

最初のNano v4 IQ4_NL／embedding Q8比較はCPUで実行した。
モデルSHA-256は `31724c4189d8f8fcec90544d1695d42a8c4e7a870d645384e7284f2cc8891f30`。
登録タイポの先頭一致はOFFの0/92からONの90/92、先頭5候補内は92/92となった。
正常入力156件の先頭表記・読みの変化は0件だった。
処理時間増分の中央値は2.34ms、タッチ処理の追加中央値25.52ms、95パーセンタイル29.43msだった。
この計測は実在語判定の追加照合前のもので、最終版の遅延は別途計測する。

未登録タイポ255件は、この通常変換評価で正解表記が先頭5候補へ入った件数が0件だった。
登録済み誤入力と促音・撥音補正を既定ONにする変更に加え、一般的なタイポへの対応力は改善が必要。
非同期AIアシストを含めた全体の成功率は、この評価から算出していない。

## ビルドと確認

最終版は現在のWindows設定と同じNano v4 Q8で、他のビルド終了後にも単独測定した。
モデルSHA-256は `a89fe90fc29431279834773cdbe7c9afeba30722ee986327b300bf4b334c0e0f`。
正常入力156件の読み・先頭表記の変化と、境界タッチ証拠による誤補正はいずれも0件。
登録92件は先頭90件、先頭5候補内92件、未登録255件は先頭5候補内0件だった。
通常変換の処理時間はOFFの中央値40.15ms、ONの43.32ms、同一ケースの差分中央値0.81ms。
タッチ処理の追加中央値は24.84ms、95パーセンタイル33.05msとなった。
CPUの共有Swift処理時間であり、WindowsのVulkan経路やAndroid端末の表示遅延は未計測。

| 対象 | コマンド・結果 |
| --- | --- |
| Windows client/shared | `cargo test --offline --locked -p shared -p azookey-windows --lib`、472成功、3除外 |
| Windows server | `cargo test --offline --locked -p azookey-server`、179成功、2除外 |
| 共通Rust | `cargo test --offline --locked --manifest-path engine/rust/Cargo.toml`、94成功、1除外 |
| 共通Swift | `run.ps1 -TestFilter '.*' -ModelPath <Q8モデル>`、158成功 |
| Android | `Build-Android.bat`、両ABI native、JVM316件、lint、APK・モデル・runtime検査成功 |
| Windows設定画面 | `npm run typecheck` と `npm test`、23成功 |
| 依存パッチ | `test_converter_patch.py`、16成功。固定アーカイブのアンカー検査も成功 |
| 増分ビルド検知 | `windows/scripts/tests/incremental-trial.tests.ps1`、30 assertions成功 |

Androidはdebug、`com.zeroconvert.ime`、2.0.14-dev、versionCode16。

| ABI | APK SHA-256 |
| --- | --- |
| arm64-v8a | `277f9239c25a2d720cb3484b8782fe92ed3be75e524642f30b5fb91df0cc5dec` |
| x86_64 | `4efee55b24ee870d5130e5dcbfba36eb51de583ad14236667bc99724dd4f5c3c` |

APKは `android/rebuilt/build/outputs/apk/debug/` にある。接続端末がないため端末更新・実IME確認は未実施。
正式署名・配布・インストーラー・Gitコミット／pushも未実施。
Windowsは `Build-Trial.bat -NoPause` で増分反映した。
適用フォルダは `.local/trial-updates/20261114-093609-649`、
manifest SHA-256は `8b38c8c61f0ec38968333911adda36d53c3d4a77685f482fd1ca5145289531ff`。
既存の基準 `build-info.json` を保持する増分適用のため、今回の識別には適用manifestと入力指紋を使う。
配備Swift DLLは `29f773058dc86da528f94fce5db17c1b6aedca54c655cacb6352887846a38338` で、
現行Swiftビルドシステムの `.build-modern/out/Products/Release-windows-x86_64` の出力と一致した。

新規Win32 EDIT入力アプリで、Nano v4 Q8／Vulkanの実キー入力を確認した。
確認中はAIアシストをOFFにした。

1. 辞書補正OFFで `shimasuta` を順に入力し、先頭候補「しますた」を確認。Escapeで取消した。
2. 辞書補正ONで同じ打鍵列から先頭候補「しました」を確認した。
3. Backspaceで候補が「します」になり、`t` / `a` の再入力後、Left / Rightで末尾へ戻ると「しました」が再表示された。
4. Enter確定後、編集欄のUI Automation Valueと画面表示が「しました」と一致した。
5. 正常入力 `kaigi` の先頭候補は「会議」。Escapeで取り消すと、確定済みの「しました」が保持された。

確認用入力アプリを終了し、個人設定の辞書補正OFF／AIアシストONを元に戻した。
補正候補の確定を自動学習しない動作も共通Swiftの回帰テストで確認している。
このPCの既存アプリは、再起動後に更新したTSF DLLを読み込む。

ビルド中に別作業の共有Rust・設定アプリ変更が入り、増分ビルドが検知して再実行した。
共有Rust／Windows serverテストとAndroid両ABIを再検証した。
終了時の `check-shared-engine.mjs` は両ABIの一致、`build-trial.ps1 -WhatIf` は変更なしだった。
Windows・共通Rustの `cargo fmt --check`、版情報・生成辞書同期、`git diff --check` も成功した。
検証の機械可読記録は `artifacts/results/keyboard-correction-validation.json` に保存する。

converterの準備先をパッチ内容のハッシュ別フォルダに変更した。
`Initialize-ModernLlama` が返すフォルダを使用し、既存SwiftPMチェックアウトを上書きしない。
増分ビルドは `converter_patch.py` の変更も依存変更として追跡する。

全Swiftテストの実行で、既存の学習順位テストに現行実装と異なる期待値があった。
完全一致候補の残り順序と部分読みの学習優先を、現在の実装に合わせて検査するよう更新した。
学習順位の製品コードは変更していない。

独立レビューでは共有方針を維持する判定となり、追加の高確度P1/P2指摘はなかった。
