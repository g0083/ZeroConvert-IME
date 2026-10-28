# Nano v4への通常変換モデル統一（2026-10-29）

Windows・Androidの通常変換選択肢をNano v4 IQ4_NL_emb8（Blend85）とNano v4 Q8_0（100%マージ）に統一した。新規既定値はIQ4_emb8。旧Standard選択をQ8へ、旧Nano/Lite選択をIQ4_emb8へ移行する。旧ファイルとユーザーのインポート済みモデルは削除しない。CorrectorとKeyboard Priorは補正専用として維持する。

カタログ・Android読込側のCorrector識別情報も、作業前から存在する実ファイルのSHA-256とサイズに整合させた。Correctorの重み自体は変更していない。通常変換モデルの名前・サイズ・ハッシュは`windows/deployment/model-catalog.json`を正本とする。

## 実施した検証

- `windows/scripts/sync-distribution-models.ps1`、`scripts/prepare-android-conversion-models.ps1`：モデルのハッシュ検査と配布資産の準備成功。
- Windows：`cargo test -p shared runtime_assets` 3件、`cargo test -p frontend --lib model_catalog` 2件、launcherのモデル経路テスト1件成功。
- Windows：`swift build --package-path server-swift -c release -Xcc -D_CRT_USE_C_COMPLEX_H -Xcxx -D_CRT_USE_C_COMPLEX_H`成功。
- 設定UI：`npm run typecheck`、`npm test`（18件）成功。
- 配布：`windows/scripts/tests/distribution.tests.ps1` 25 assertions成功。試験用fixtureへの適用であり、稼働IMEへは適用していない。
- Android：`:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :rebuilt:verifyConversionModels`成功。
- 共通エンジン：`scripts/build-android-shared-engine.ps1`でarm64-v8a / x86_64のSwift・Rust・JNIを増分ビルド、`node scripts/check-shared-engine.mjs`成功。
- Android現行版：`:rebuilt:testDebugUnitTest :rebuilt:lintDebug :rebuilt:assembleDebug`成功。モデル選択移行テストも`--tests com.zeroconvert.ime.ConversionModelSelectionTest`で1件実行し成功。
- `python scripts/check-android-apk.py`：両APKのABI・ランタイム依存・実GGUF集合・カタログハッシュを検証。旧Standard/Nano/Liteの混入なし。
- `python scripts/check_repository.py`、データ分離の8回帰テスト成功。

初回のRust/Gradle実行はサンドボックス内のprotoc DLLロード・Gradleキャッシュ権限で失敗し、同じ対象を許可された制限外コマンドで実行して成功した。`node windows/scripts/sync-version.mjs --check-release`は、既存の`frontend/src-tauri/tauri.conf.json`が宣言されたrelease版と一致しないため失敗。今回の作業ではrelease版の同期・公開やインストーラー生成は行っていない。

## 生成したAPK

現行`:rebuilt`のdebug版：applicationId=`com.zeroconvert.ime`、versionCode=13、versionName=`2.0.11-dev`。版番号を含む他作業の未コミット差分がある作業ツリーでのビルドであり、今回のコミットだけから作った配布版ではない。

| APK | bytes | SHA-256 |
|---|---:|---|
| `android/rebuilt/build/outputs/apk/debug/rebuilt-arm64-v8a-debug.apk` | 142957350 | `37dfe25536c5442c6afcc1ed63e1621ca6b77fc531f7c2b9c643a695123a0f22` |
| `android/rebuilt/build/outputs/apk/debug/rebuilt-x86_64-debug.apk` | 141683092 | `dfa95630ae1579c5e8d5893e827aaef5bd92875e4406494871497777c1c622b5` |

モデル統一のGit保存時点では、Windows試験IMEへの適用・Android端末更新・入力アプリでの実IME確認は未実施だった。後述の追加検証で適用・実IME確認を完了した。インストーラー・正式配布用署名は未実施。モデル単体の評価値はREADMEとNano v4 / CPU比較レポートに記録している。今回のGit保存では、他作業の入力処理・UI変更・ステージ済み文書移動を混ぜず、モデル統一・README・モデル学習再現用ソースと関連記録を選別する。

## 追加：Windows試験IMEとAndroid実機への適用（2026-10-29）

### Windows

- `Build-Trial.bat`が増分ビルド・適用・再起動まで成功。13ファイル更新、2ファイル除去。続けてApply-Trialは実行していない。
- 適用記録：`.local/trial-updates/20261029-032430-654/`、manifest SHA-256=`0a98e79d43de4c575ecc65faf86ddb6985bbf739b4834f81443bf6fd9c2c2050`。増分適用は既存build-infoの版文字列を保持するため、その旧revisionだけで現在の実行物を識別しない。
- 新規プロセスのWinForms入力アプリで、実際のキーイベント`toukyou`から候補「東京」を確認し、Enterで入力欄へ「東京」が確定したことを画面・アクセシビリティ値で確認。
- Left→Backspaceで「東京」から「京」へ正しい位置の削除を確認。Q8へ一時切替・再起動し、「a」の候補「あ」とEscape取消による既存文字列保持を確認。
- 稼働する先読みモデルプロセスのパスが各切替先と一致することを確認。CPU、4スレッド、GPU offload 0。両モデルの配置済みSHA-256はカタログと一致。
- 最終的に元の`zenzai.model=nano`（IQ4_emb8へ読み替え）へ戻して再起動。他の個人設定もJSONとして検証前と一致。検証専用入力アプリは終了。

### Android実機

- 対象はvivo V2520A、Android 16、arm64-v8a。接続端末を固定し、既存APKとの署名証明書SHA-256一致を確認して`adb install -r`で更新。アンインストール・データ消去・ダウングレードは行っていない。
- 導入した現行`:rebuilt` debug APK：versionCode=13、versionName=`2.0.11-dev`、SHA-256=`37dfe25536c5442c6afcc1ed63e1621ca6b77fc531f7c2b9c643a695123a0f22`。端末内base.apkも同じSHA-256。
- アプリ内設定画面でIQ4_emb8とQ8の2種類を確認し、Q8への切替・保存・ロードを確認。端末内に展開された両GGUFのSHA-256はカタログと一致。
- `PortInstrumentationRunner`を各モデルで実行し、**IQ4_emb8 24件・Q8 24件、計48件、失敗0件**。nativeモデル・共通Swift変換、InputConnection、実IMEキー、削除・再入力、カーソル、確定、QWERTY/フリックの英字Enterとモード切替を含む。学習書き込みを抑止した検証であり、履歴リセットは実行していない。
- 別アプリのAndroid設定検索欄でも、ZeroConソフトキーボードの「あ」をタップ→確定→削除し、入力欄の値を照合した。設定項目の変更は行わず、テスト文字は消去済み。
- 最終選択をIQ4_emb8に戻し、保存されたモデル名・パスを確認。

今回の確認はモデルの全入力に対する精度や端末の長時間安定性を保証するものではない。インストーラー作成・release署名・ストア公開は行っていない。適用した実行物には作業ツリーの既存未コミット変更も含まれる。

機械可読記録：`artifacts/results/nano-v4-real-ime-verification-20261029.json`。Windows適用ログは`artifacts/logs/nano-v4-windows-apply.log`、Android実機ログは`artifacts/logs/nano-v4-vivo-{iq4,q8}-instrumentation.log`。実機内のserialや個人設定そのものはこの公開記録へ記載しない。
