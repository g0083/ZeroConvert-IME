# Windowsの共通配布データ・試験環境・インストーラー

Windowsソースは `windows/` が正本。ビルド後に **共通配布パッケージを一度作成** し、同じパッケージをこのPCの試験環境と別PC用インストーラーに使用する。ビルド出力のEXEを個別に手でコピーしない。

## 普段の開発：変更箇所だけ試す

ルートの **`Build-Trial.bat`** を実行する。ソースの変更を検知し、必要な部分だけreleaseの増分ビルドを呼び、変わった生成物だけを試験環境へ適用する。`npm ci` は初回・package.json/lockfile変更時・node_modules欠落時だけ実行する。全体の配布データ作成やインストーラー生成は行わない。

- 初回は比較基準がないため各部分を確認してビルドする。Cargo/Swiftの既存キャッシュは利用する。
- 変更なしならビルド・コピー・再起動を行わず終了する。
- frontendの変更はfrontendだけ、clientの変更は64/32ビットIMEだけをビルドする。sharedの変更は全Rust利用先、serverの変更はserver・UI・frontendを対象にする。
- SwiftだけならSwift、辞書だけなら該当辞書と逆引き辞書を更新する。モデルはカタログのハッシュを照合する。
- frontendだけの更新では変換サーバー・モデルを再起動しない。入力DLLの更新は入力アプリを開き直して反映する。個人のAppData設定は保持する。
- `Build-Trial.bat` は適用まで行うため、その後に `Apply-Trial.bat` を実行する必要はない。引数なしの `Apply-Trial.bat` は `dist/packages/latest.json` が指す完全配布パッケージを選び、ビルドはしない。適用済みの増分更新より古い完全パッケージを暗黙に適用する操作は停止する。意図して完全パッケージを適用するときは `-PackageDirectory` を明示する。
- `Build-Trial.bat -WhatIf` は変更判定と対象表示だけ。`-Force` は検知結果にかかわらず全グループを確認する。SDK・ツールチェーン・外部依存を手動更新した場合にも使う。`-NoRestart` は更新後のアプリ再開を省略する。

比較基準は試験環境の `trial-build-state.json`、更新データは `.local/trial-updates/<日時>/`、バックアップは `.local/trial-backups/<日時>/`。通常のコードは内容をハッシュで比較する。大きな辞書・モデルはサイズと更新日時のキャッシュを使うため、日時を維持した外部ファイル差し替えでは `-Force` を使う。ビルド中のソース変更はファイル名を表示し、影響する部分だけ自動で追加ビルドする。たとえばfrontendビルド中のUI変更ではUIだけを追加し、shared変更ではfrontendを含む依存先も再ビルドする。3回続けて変更を検知した場合は適用を止める。失敗したビルドの比較基準は保存しない。

この開発用更新では複数コンポーネントのビルド時点が混在する。`build-info.json` の版だけで判定せず、`trial-deployment.json` の `mode=incremental` と更新manifest・各生成物ハッシュで識別する。全体配布パッケージのlatestは更新しない。開発用更新を適用した後は `Build-Installer.bat -UseLatestPackage` で古い全体パッケージを誤配布しないように拒否する。配布前は下記の全体ビルド・適用・確認を行う。

## 通常の作業

1. ルートの `Build-Package.bat` を実行する。Swift、Rust、64/32ビットIME、Tauri設定画面、逆引き辞書をビルドし、`dist/packages/<日時>/payload/` に共通配布データを作る。成功時だけ `dist/packages/latest.json` を更新する。起動中のIMEは変更しない。
2. 入力途中の文字を確定して `Apply-Trial.bat` を実行する。最新パッケージを検証し、`ZeroConvertIME-windows/` に適用する。対象フォルダ内・現在のセッションのlauncher/server/UI/モデル/設定画面だけを停止し、起動していたlauncherと設定画面を再開する。登録やctfmonの再起動は行わない。既存アプリが保持しているTSF DLLはアプリを開き直すまで旧版のため、実IMEの確認は入力アプリを開き直して行う。
3. 動作確認した同じ内容を配布するには `Build-Installer.bat -UseLatestPackage` を実行する。ソースを再ビルドせず、そのパッケージからインストーラーを作る。

特定のパッケージを使う場合は、どちらも `-PackageDirectory "D:\...\dist\packages\<日時>"` を指定する。`Apply-Trial.bat -WhatIf` で停止・コピーを伴わず検証と変更予定を確認できる。`-NoRestart` は適用後に対象アプリを再開しない。

`Build-Installer.bat` 単独でも、従来通りソースビルド→共通パッケージ作成→インストーラー生成を実行する。**試験済みの配布データを使いたい場合は `-UseLatestPackage` を指定すること。** `-PrepareOnly` は共通パッケージの準備・検証まで、`-SkipBuild` は前回の完全ビルドとその元の版情報をハッシュ検証して再利用する。個別にEXEや生成メタデータが変わっていれば再利用を拒否する。

## 保存先と識別情報

| 役割 | 場所 |
| --- | --- |
| ソース | `windows/` |
| コンパイル中・生成物 | `windows/target/`、`windows/server-swift/.build/` |
| 変換・補正モデルの正本 | `artifacts/models/zerocon/` |
| 配布用CPU/Vulkan・VC++依存ファイル | `artifacts/runtime/windows/` |
| 共通配布パッケージ | `dist/packages/<日時>/` |
| このPCで使う試験環境 | `ZeroConvertIME-windows/` |
| 適用前のファイルと失敗時の退避 | `.local/trial-backups/<日時>/` |
| 配布インストーラー | `dist/installers/<日時>/` |

共通パッケージは `payload/package-manifest.json` に全ファイルのサイズ・SHA-256とビルド識別情報を持つ。`package.json` でそのmanifestとInno Setup用版情報のハッシュも固定する。試験環境の `trial-deployment.json` とインストーラー出力の `package-source.json` に、同じパッケージの場所とmanifestハッシュを記録する。この値が一致すれば同じ配布データを使っている。既存のインストーラーは更新せず、新しい日時フォルダへ作成する。

試験環境はインストール後と同じDLL名 `azookey.dll` / `azookey32.dll` を使う。適用前に全ファイルを検証・ステージし、変更する旧ファイルはバックアップへ移す。コピーや検証に失敗すれば適用済みファイルを巻き戻す。管理対象外の実験ファイル・古いバックアップは削除しない。個人の `%APPDATA%/Azookey/` の設定・履歴・学習には触れない。配布の `settings.json` は初期設定であり、既存のAppData設定には上書きしない。

## 依存ファイルの初回移行・更新

既存環境からの初回移行は次のコマンドで実施済み。以後のビルド・梱包は試験環境から依存ファイルを取得しない。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File windows/scripts/import-runtime-dependencies.ps1 -SourceDirectory .\ZeroConvertIME-windows
```

CPU/Vulkanエンジン、VC++ DLL、存在すればplutilだけをコピーし、`dependencies-manifest.json` に由来・サイズ・SHA-256を保存する。アプリEXE/DLL、Swift DLL、モデル、個人設定は移行対象外。Swift DLLはビルドに使用したSwift SDKから取得する。依存ファイルを更新する場合は新しい保管先へimportし、`Build-Package.bat -DependencyDirectory <新保管先>` を使う。既存の保管先は上書きしない。旧 `-RuntimeDirectory` は廃止し、移行用コマンドを明示する。

必要環境: x64 Windows、Rust/MSVC（i686-pc-windows-msvcを含む）、Visual Studio C++ Build Tools・Windows SDK、SwiftとSDKROOT、protoc、Node.js/npm、Git、Inno Setup。ビルドはnpm lockfileを使う。インストーラー作成元PCへのインストールは行わない。

## 含むものと設定

- 登録用の64/32ビットDLL、server、UI、launcher、設定UI。
- Swift等のランタイムDLL、辞書、絵文字辞書、逆引き辞書。
- CPU/Vulkanエンジンと、`models/zerocon-model-standard.gguf`・`zerocon-model-nano.gguf`・`zerocon-model-corrector.gguf` の3モデル。CUDA・LFM・旧名モデル・ルートの `zenz.gguf` は含めない。
- `windows/deployment/` の配布スクリプト・ライセンス・初期設定。

個人の `%APPDATA%/Azookey/` は読まない。入力履歴・学習データ・個人設定は含まない。バックアップ・一時ファイル・ログも梱包対象外。初期設定には `windows/deployment/settings.json` を使い、インストール先に既存設定がある場合は保持する。

既存の `windows/installer/Installer.iss` を使い、DLL登録、VC++/WebView2依存、起動タスク、更新時のファイル置換を維持する。表示名はZeroCon IMEで、既存版との互換性のため内部のインストール先はProgram Files内のAzookeyのまま。インストール完了後にWindowsの入力切替でZeroCon IMEを選ぶ。更新時はWindows再起動が必要になる場合がある。

## 作成環境

Inno Setupは [公式配布元](https://jrsoftware.org/isdl.php) から導入する。今回の作業環境では署名を確認したInno Setup 6.7.3を `.local/tools/InnoSetup6/` に配置した。batは同ディレクトリ、PATH、一般的なインストール先から `ISCC.exe` を探す。初回以降の自動ダウンロードは行わない。

標準Windows PowerShell 5.1で動作する。別の依存ファイル格納先やコンパイラを使う場合:

```powershell
.\Build-Installer.bat -DependencyDirectory "D:\path\to\runtime-dependencies" -IsccPath "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
```

Swiftランタイムは `SDKROOT` に対応する `Swift/Runtimes/<版>/usr/bin` から探す。カスタム配置では `-SwiftRuntimeDirectory <usr/bin>` を指定する。`-PrepareOnly` では既定でビルドと梱包内容準備まで実行し、EXE生成だけを省略する。

必要なファイル・ツールがない場合は作成を中止し、bat画面に理由を残す。自動処理から呼ぶ場合は環境変数 `ZEROCON_NO_PAUSE=1` で最後のキー待ちを省略できる。終了コード0が成功、それ以外は失敗。

## 出力と検証

日時ごとに新しい作業フォルダを作るため、以前の出力は削除しない。

- `dist/packages/<日時>/payload/`: 梱包内容。
- `dist/installers/<日時>/*.exe`: 別PCへ渡すインストーラー。
- 同ディレクトリの `SHA256SUMS.txt`: EXEのSHA-256。
- `package-manifest.json`: 同梱ファイルのサイズ・SHA-256一覧。
- `build-info.json`: 梱包時の版・ブランチ・revision・installerGeneration。

ローカルの版情報は既存の `sync-version.mjs` から `validation` として生成する。正式版の版番号・タグは変更せず、公開もしない。記録されるrevisionはチェックアウトを示す。既定のビルドには未コミット変更も含むため、コミットだけでは内容を再現できない。`-SkipBuild` は記録した成功ビルドをハッシュ検証して再利用する。`package-manifest.json` の `sourceBuild` と `packageType` に方式を記録し、実ファイルの識別にはハッシュを使う。全ビルドと梱包で同じvalidation版番号を共有し、終了時は呼び出し元の版情報環境変数を復元する。

各ファイルをコピー前後で検証し、取得元や共通配布データが変化すれば失敗させる。生成EXEの存在・サイズを確認し、.NETのSHA-256でハッシュを出力する。署名鍵を設定していないため、今回のインストーラーは未署名。

このPCでの生成・梱包内容の確認と、別PCでの新規インストール・更新・実IME動作確認は分けて扱う。別PCでの確認は未実施。

## 2026-10-23の作成・検証結果

`Build-Installer.bat` をWindows PowerShell 5.1経由で実行し、終了コード0で作成完了。出力は `dist/installers/20261023-030249-716/ZeroConvert-IME-setup-0.1.0-batao.15.dev.1792692208.g140bb742.exe`（954,154,330 bytes、約910MiB）。版は `0.1.0-batao.15.dev.1792692208.g140bb742`、channelは `validation`、revisionは `140bb7427ce9f1ac79728ce803b400ddaf089965`、installerGenerationは2。作業ブランチは既存の `fix/typo-correction-pipeline` を維持した。

- `windows/scripts/verify_release_artifact.ps1` で最終EXEの版・channel・revision・SHA-256一致・署名状態を確認。署名状態は `NotSigned`。インストールを行う `-SilentInstall` は指定していない。
- SHA-256: `e096e450db3cd9110cf8f862fe9961957735e7a8df9d41a77ce3ee060ddce854`。
- ステージ内3,783ファイルすべてのサイズとSHA-256がmanifestと一致。最新の登録用64/32ビットDLLと配布設定の一致、予測用モデル・エンジン・逆引き辞書の同梱、学習履歴・バックアップ・PC固有の絶対パス設定が入っていないことを確認。
- `node --test windows/frontend/tests/versioning.test.mjs`: 7件成功。
- PowerShell構文解析と `git -c diff.ignoreSubmodules=all diff --check` は成功。存在しない実行フォルダを指定したbatがエラー終了することも確認した。
- VMツールは前の調査でWindowsにない `fcntl` のimportが必要なことを確認済みのため、今回もVMインストールテストは未実施。別PCでの新規インストール・更新・実IME動作は未確認。

## 旧azooKeyアンインストール後の移行判定修正（2026-10-23）

旧版 `v0.1.0-alpha.1` をアンインストール・再起動したノートPCで、「旧バージョンのアンインストーラーが既知の正規リリースと一致しない」と停止する報告を受けた。元の自動移行は `0.1.0-batao.10` とその特定のアンインストーラーSHA-256に限定される。ノートPCの残存ファイルは未確認であり、登録情報だけが残ったことはまだ断定していない。

`LegacyPayloadDetection.iss` で、標準の旧AppDataフォルダを列挙し、本体DLL・server・frontend・launcher・UIの9種類がすべて存在しない場合に限って、その登録を移行不要として扱う。設定・学習・バックアップ・アンインストーラーファイル・登録情報はこの判定で削除しない。旧プログラムも実行しない。フォルダが欠落・アクセス不可・reparse point・列挙失敗の場合や、本体名のファイル/ディレクトリが残る場合は従来の検証経路を維持する。版・ハッシュの許可リストは変更していない。

`windows/installer/tests/LegacyPayloadDetection.iss` をInno Setup 6.7.3でコンパイルし、インストール前に終了するネイティブテストを実行。削除後のunins残存、全9種類の本体残存、大小文字、設定・学習・バックアップ保持、欠落ルート等の39アサーションが成功。版情報の `--check-release` は以前からの `frontend/src-tauri/tauri.conf.json` 不整合で失敗しており、正式版メタデータは変更していない。VMでのインストール検証は未実施。

修正版は `dist/installers/20261023-032258-370/` に出力。版は `0.1.0-batao.15.dev.1792693429.g140bb742`、channelはvalidation、revisionは `140bb7427ce9f1ac79728ce803b400ddaf089965`、installerGenerationは2。ローカル配布用インストーラーではセットアップログの自動保存も有効化した。

同フォルダの `Check-IME-Install.zip` は、ノートPCで展開して `Check-IME-Install.bat` を実行すると `IME-install-diagnostic.txt` を作る。旧製品の登録・インストール先・フォルダ直下のファイル名・TSFのDLL登録先だけを読み取る。削除、プロセス停止、レジストリ変更、旧EXEの実行、設定内容や入力履歴の読み取りは行わない。必要な場合に残存状態を確認するために使う。

アプリ一覧・アンインストーラーが見つからない場合、ZeroConの保護されたインストール先も `C:\Program Files\Azookey` である点を確認する。登録・アンインストーラーが欠落している場合は、最新版のセットアップEXEを同じ場所へ上書き実行して復元する経路がある（ノートPCでの復元は未確認）。フォルダの直接削除は避ける。更新した診断スクリプトは、登録がなくてもProgram Files内の標準フォルダ、TSF DLLの親フォルダ、`unins000.exe/.dat` の有無、アプリ一覧の非表示・削除禁止フラグも記録する。最新版出力フォルダの `Check-IME-Install.zip` に同梱し、Windows PowerShell 5.1での実行・レポート生成とZIP整合性を確認した。

## 旧版導入フロー・表示名・ベンチマーク・英字候補の更新（2026-10-23）

出力: `dist/installers/20261023-104209-053/ZeroConvert-IME-setup-0.1.0-batao.15.dev.1792719773.g140bb742.exe`。channelはvalidation、revisionは `140bb7427ce9f1ac79728ce803b400ddaf089965`、installerGenerationは2。SHA-256は `6fe1a2205e6ddfc2f619a9231c9ad58d7ea103d5e83642c7ef259ae56f8ed466`。未署名。既存ブランチ・無関係の変更は維持した。

- `Install-ZeroCon.bat` と `install-with-legacy-removal.ps1` を同じフォルダに配置する。初期alpha.1の標準インストールが登録されている場合、通常ユーザーで旧アンインストーラーを開く。完了・Windows再起動後に同じbatを実行すると同フォルダのZeroConセットアップを開く。旧版の許可リストや管理者インストーラーの移行検証は変更しない。導入用batを管理者として起動した場合は中止する。インストーラーにもWindowsのアプリ削除画面を開く導線を追加した。
- デスクトップショートカットを `ZeroCon IME` に変更し、ZeroConのICOを指定。旧名のショートカットを更新時に削除。IME DLLの先頭アイコンリソースもZeroConに変更し、登録後に64/32ビット両方のTSFプロファイルのDescription・IconFile・IconIndexを更新する。適用後は再起動する。
- ベンチマークがCUDAフォルダだけを探していた処理を変更。現在の設定のbackendに対応するエンジンを使い、起動中の設定アプリと同じフォルダを優先。モデル探索も実行フォルダを優先する。エンジン変更時はベンチマーク専用サーバーを再起動し、CPUの場合はGPUレイヤー0とする。IME用を含む全llama-serverを一括終了していた処理を削除した。
- 英字補完が原文だけの時は候補ウィンドウを出さない。候補UIのページ読み込み完了時に最新データを再送し、非表示WebViewのanimation frame待ちに依存しないサイズ更新に変更。レイアウト未計測でも候補のDOMを作り、非表示・空リスト・ページ準備前の状態ではネイティブの空枠を出さない。

検証:

- `cargo test -p azookey-windows --lib -- --quiet`: 374成功、2ignore。
- `cargo test -p ui --bin ui -- --quiet`: 35成功。
- `cargo test -p frontend --lib benchmark_runtime -- --quiet`: 2成功。Vulkan選択時にCUDAを選ばず、旧フォルダより現在の配置を優先することを確認。
- `node --test windows/crates/ui/tests/candidate_hidden.test.cjs windows/crates/ui/tests/candidate_scroller.test.cjs`: 9成功。animation frameが動かず要素の計測値が0の状態でも候補DOMとサイズ更新を生成することを確認。古いCSSの具体値を固定していたJSアサーションは、長さの単位を検証する既存のチェックに整理した。
- `npm run tauri:build`、`cargo build -p ui -p azookey-windows --release`、x86 DLLのreleaseビルドが成功。配布用frontend・UI・64/32ビットDLLの4ファイルを動作環境へコピーし、梱包manifestとのハッシュ一致を確認した。
- `Build-Installer.bat` が成功し、既存の `verify_release_artifact.ps1` で最終EXEの版情報・SHA-256・署名状態を確認。変更Rustファイルの書式・PowerShell構文・差分チェックも成功。
- Computer Useで新しく起動したメモ帳に `h` → `he` → Backspace → `e` → Tab を実キー入力。1文字では枠を表示せず、2文字で候補が表示され、削除すると非表示、再入力すると再表示、Tabで `here` を確定して閉じることを確認した。既存文書は変更していない。
- 導入用batは `-InspectOnly` で旧alpha.1登録の検出を確認。実際の旧版削除、新インストーラーによるWindowsアイコン/ショートカット更新、ノートPCのCUDA非搭載環境での実推論ベンチマーク、報告されたチャットアプリでの候補表示は未確認。VMは変更していない。既存の正式版メタデータ不整合は今回の変更対象外。


## ソースビルドの一括実行（2026-10-23）

`Build-Installer.bat` の既定動作をソースビルド付きに変更した。`windows/scripts/build-installer-sources.ps1` が各ツールの終了コードと成果物を検証し、成功時だけ梱包用ファイルを返す。現在使用中のIMEファイルには上書きしない。全ステップで同じvalidation版情報を使用する。

最終スクリプトの `Build-Installer.bat` が終了コード0で成功。出力は `dist/installers/20261023-143348-102/ZeroConvert-IME-setup-0.1.0-batao.15.dev.1792733628.g140bb742.exe`。channelはvalidation、revisionは `140bb7427ce9f1ac79728ce803b400ddaf089965`、installerGenerationは2、ブランチは既存の `fix/typo-correction-pipeline`。SHA-256は `39932f11ff338e840645ff9fb360e314d1cc544c0791cd841dc381c89ee73483`。

確認結果:

- Swift release、Rust server/UI/launcher・64/32ビットIME、npm ci・TypeScript/Vite・Tauri release、逆引き辞書生成、Inno Setup EXE生成が成功。
- 3,785ファイルのサイズ・SHA-256がmanifestと一致。新規ビルド・ソースに対応する9ファイルも出力元と一致した。
- `powershell.exe -NoProfile -ExecutionPolicy Bypass -File windows/scripts/tests/build-installer-sources.tests.ps1` が成功。ネイティブコマンドの終了コード0/23、エラー伝播、成功・失敗時の作業ディレクトリ復元を確認。
- 存在しないSwiftランタイムを指定すると梱包前に失敗し、配布出力を作らず、呼び出し元の版情報環境変数を復元することを確認。
- `Build-Installer.bat -SkipBuild -PrepareOnly` が終了コード0。PowerShell構文解析・差分チェックも成功。
- 正式版メタデータの `node windows/scripts/sync-version.mjs --check-release` は既存の `frontend/src-tauri/tauri.conf.json` 不整合で失敗。validationビルドの生成設定でビルドしており、正式版設定は変更していない。
- インストール・アンインストールや実IMEへの反映は実行していない。VMは既知のWindows非対応 `fcntl` 依存のため未使用で、変更していない。この変更によってノートPCのアンインストール登録欠落が解決したとは判断していない。


## 配布アセットの整理・CUDA追加導入（2026-10-23）

最終配布EXE: `dist/installers/20261023-152859-155/ZeroConvert-IME-setup-0.1.0-batao.15.dev.1792736939.g140bb742.exe`（180,198,901 bytes、約180MB）。channel validation、revision `140bb7427ce9f1ac79728ce803b400ddaf089965`、installerGeneration 2。SHA-256: `356732bf82aa7ff46d46c0327e329198cf51bfc6e918899ac6d9c8457948a2a2`。未署名。ブランチは既存の `fix/typo-correction-pipeline`。

詳細と由来は [model-assets.md](model-assets.md)。CUDA・LFMは同梱せず、配布モデルは命名を統一した3つだけ。設定 `zenzai.model` で変換・先読みの重みを共用し、CorrectorもLFMではなく選択バックエンドを使う。CUDAは設定画面で確認後に固定アーカイブ2つを取得し、サイズ・SHA-256検証と限定展開後にキャッシュを公開する。既存のCUDA設定で未導入の場合も確認を表示する。

検証結果:

- `Build-Installer.bat` のSwift・Rust 64/32ビット・設定画面・逆引き辞書・Inno生成が成功。生成EXEの版・ハッシュ・未署名状態を既存のverifyスクリプトで確認。
- 全3,699同梱ファイルのサイズとSHA-256がmanifestに一致。GGUFは3つで合計101,187,360 bytes。CUDA/LFMフォルダが含まれないことを確認。
- `Build-Installer.bat -SkipBuild -PrepareOnly` で成功ビルドのハッシュ照合と再利用が成功。PowerShell構文解析・差分チェック成功。
- 共有Rustテスト31成功・1ignore。設定アプリの全テストは隔離修正後63成功・1ignore、launcherは10成功。
- CUDA専用の `--include-ignored` テスト2成功。ZIP内の任意パスを展開しないこと、固定2アーカイブの実ハッシュ一致・必要11ファイルの実展開・`llama-server --version` 起動を確認。
- 新しい3モデルそれぞれを同梱CPUの専用一時ポートで起動し、healthとcompletionを確認。既存IMEのポートは使っていない。ログは `windows/target/installer-tests/model-<role>-cpu.log`。
- 型検査・Tauri releaseビルド・通常設定での `cargo check -p frontend --release --locked` が成功。設定アプリの起動と描画は確認したが、自動UI操作で画面遷移を確認できず、CUDA確認ダイアログの実操作とGPU推論は未確認。起動した検証用プロセスは終了した。元から開いていた設定画面・文書は閉じていない。
- `--check-release` は既存のtauri.conf.jsonの正式版メタデータ不整合で失敗。正式版設定は変更していない。VMは既知のWindows非対応依存のため未使用で、インストール・アンインストールは今回実行していない。

### テストによる学習初期化の事故と隔離修正

最初の設定アプリ全体テストが、同じWindowsセッションで稼働中の実IMEへ接続した。`reset_learning_history_reports_unavailable_server` が学習初期化成功の応答を受け、15:30頃のLearningMemoryフォルダ更新と一致した。確認時点でLearningMemoryは空。AppData内、既存ローカル保全先、File History設定を確認した範囲では復元可能なバックアップは見つからなかった。事故前の学習データ量は不明。ユーザーへ作業中に説明した。

再発防止として、設定アプリの `IPCService::new_inner` と `server_process::restart_server` は `cfg(test)` で実接続・実IME再起動を無条件に拒否する。テストは接続を明示的に注入したfixtureだけを使い、現行の既定値（AI有効）と不整合な古いテストも修正した。隔離後の全テストは成功した。通常ビルドの機能へこのテスト用遮断は入らない。

## 共通配布フローへの移行確認（2026-10-23）

- 共通パッケージ: `dist/packages/20261023-162457-890/`。管理対象3,701ファイルを検証。
- 試験環境へ同じパッケージを適用済み。`trial-deployment.json` のmanifestハッシュは `8f28064aecda1e7c1def3a809566138922c85ab6f29d75a93787577199d56ba7`。6種類のアプリEXE/DLLも配布パッケージと一致した。適用バックアップは `.local/trial-backups/20261023-163533-468/`。
- 最終インストーラー: `dist/installers/20261023-163713-880/ZeroConvert-IME-setup-0.1.0-batao.15.dev.1792739943.g140bb742.exe`。SHA-256は `cf98f53b9503a0dfb7e5c8c51a178936296436f657e33f769de16ab36e658643`。`package-source.json` のmanifestハッシュと試験環境が一致。
- versionは `0.1.0-batao.15.dev.1792739943.g140bb742`、channelはvalidation、revisionは `140bb7427ce9f1ac79728ce803b400ddaf089965`、installerGenerationは2。ブランチは既存の `fix/typo-correction-pipeline`。ビルドには現在の未コミット変更も含む。
- Swift、Rust server/UI/launcher・64/32ビットIME、Tauri、逆引き辞書のビルド成功。`distribution.tests.ps1` の25項目と `build-installer-sources.tests.ps1` が成功。UTF-8・日本語ファイル名の比較、破損・余分なファイルの拒否、パス逸脱拒否、WhatIf、管理対象外ファイル保持、ロック時の巻き戻し、依存取込範囲、終了済み/列挙後に消えたプロセスを検証。
- 適用後に試験環境のlauncher・server・UI・Vulkan推論サーバー2個の起動パスを確認。既存入力アプリを開き直しての実IME操作、別PCへのインストール・VM検証は未実施。正式版メタデータの既存不整合（`tauri.conf.json`）は変更していない。

## 変更検知ビルドの確認（2026-10-23）

`Build-Trial.bat` の初回比較基準を作成し、11ファイルを試験環境へ差分適用した。更新データは `.local/trial-updates/20261023-172551-018/`。ビルド中に別の編集によるSwiftソース変更があった実行では適用を拒否し、編集停止後の実行で成功した。

実際の `about.tsx` へ一時コメントを追加して `-WhatIf` を実行し、frontendだけが対象になることを確認。元の内容・日時へ戻した後、ビルド・コピー・再起動なしで0.9秒（プロセス起動込み1.24秒）で終了した。実際の変更箇所とコンパイラーのキャッシュ状況によりビルド時間は変わる。frontend単体でもreleaseの埋め込み・リンク時間は必要。

`powershell -NoProfile -File windows/scripts/tests/incremental-trial.tests.ps1` は18項目、`distribution.tests.ps1` は25項目成功。新しいインストーラーはこの開発用更新から生成していない。正式版メタデータの既存不整合は引き続き対象外。
