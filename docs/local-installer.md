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

- 登録用の64/32ビットDLL、server、UI、launcher、設定UI、`keyboard-prior.exe`。
- Swift等のランタイムDLL、辞書、絵文字辞書、逆引き辞書。
- CPU/Vulkanエンジン（`llama_cpu/`・`llama_vulkan/`）と、オンデマンド取得対象の `windows/deployment/model-catalog.json` に定義された ZeroCon Model v4.2b 4モデル（インストーラーへは未同梱）。CUDA・LFM・旧名モデル・翻訳モデル（設定画面からオンデマンド取得）・ルートの `zenz.gguf` は含めない。
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

## 歴史的検証メモ（2026-10-23 共通配布フロー導入時）

> [!NOTE]
> 以下は 2026-10-23 に共通パッケージ手順（`Build-Package.bat` / `Apply-Trial.bat` / `Build-Installer.bat` / `Build-Trial.bat`）を導入・検証した際の記録要約です。当時の3モデル構成や CUDA 追加ダウンロード機能はその後廃止され、現行のモデル・バックエンド構成は [model-assets.md](model-assets.md) が正本です。

- **インストーラー検証**: `Build-Installer.bat`（PowerShell 5.1）で共通配布パッケージ（`package-manifest.json` による全ファイル SHA-256 照合）から未署名セットアップ EXE を生成し、`verify_release_artifact.ps1` と `distribution.tests.ps1`（25項目）・`build-installer-sources.tests.ps1`・`incremental-trial.tests.ps1`（18項目）を確認した。
- **旧版アンインストール移行判定**: `LegacyPayloadDetection.iss` により、旧 AppData フォルダに本体9ファイルが存在しない場合は移行不要と判定する変更を追加し、`windows/installer/tests/LegacyPayloadDetection.iss` の39アサーションを確認した。旧版残存環境の診断には `Check-IME-Install.zip`（`windows/scripts/diagnose-ime-install.ps1`）を用意した。
- **テスト隔離**: 設定アプリ単体テストが実稼働IMEへ接続して学習履歴を初期化する事故を受け、`IPCService::new_inner` と `server_process::restart_server` に `cfg(test)` ガードを追加し、テストから実環境への接続を遮断した。
