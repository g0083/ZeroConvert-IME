# Windows最新ソースの復旧記録

2026-10-23、ブランチ `feature/windows-color-themes` で実施。ソースの復旧のみで、実行中IMEの差替え・再起動・インストール・コミット・pushは行っていない。

## 復旧元と経緯

- HEAD `140bb74` は初期整理コミット。主要な追跡ファイルがその内容に戻っており、最新の未追跡ファイルだけが残っていた。
- 最初に02:00のチェックポイント `0c07ee2` から接続コードを復旧したが、既存の追加ファイルとの設定契約がさらに更新されていたため整合しなかった。
- `git fsck --no-reflogs --unreachable --no-progress` で、より新しい17:18:05 JSTの退避コミット `a48be81d968ce9c8fae7d63734a4e3cdc27f9165` を発見。最終的にこちらのWindows変更一式を復旧した。
- この退避は `WIP on fix/typo-correction-pipeline` で、`corrector_port` / `prediction_port`、ZeroCon専用先読み、旧予測モデル設定の廃止、モデル切替管理、設定アプリ・候補UI等を含む。
- resetの実行者・実行方式はGit履歴だけでは確定できない。

## 保持・統合した変更

- Androidの変更と既存の未追跡ファイルを保持。
- テーマ関連の設定・候補UI・設定アプリの変更は3-way mergeで統合。候補UIの `UpdateTheme` と復旧した `CandidateReady` の競合は両方のイベント処理を残して解消。
- 外観ページは現在の新しいテーマ実装を維持し、退避内の旧 `theme_color` 選択ページで上書きしていない。
- チェックポイントより後に変更された `corrector.rs` / `prediction.rs` / `typo.rs` 等は維持。
- 先読み共起表 `collocation.rs` の削除は最新退避に従った。削除前の内容はバックアップ済み。
- Rust書式チェックで指摘された設定アプリのassert式1箇所のみ整形。

## バックアップ

リポジトリルートからの相対パス。

- `.local/recovery/windows-20261023-180810/`: 最初の復旧前ファイル、全管理対象ソースのSHA-256、変更一覧。
- `.local/recovery/windows-20261023-181219/`: 最新退避へ更新する直前のファイル、復旧内容 `recovery.json`、保全確認 `preservation-check.json`。
- `.local/recovery/restored-windows-source-20261023.zip`: 復旧後のWindows管理対象ソースと既存の未追跡ソースのコピー。モデル・依存サブモジュール・ビルド出力は含めない。

復旧対象と比較文書への追記を除く既存ファイルは、最初のSHA-256記録と一致した。Gitの参照・indexは変更していない。

## 検証

以下は `windows/` から実行。protocは制限環境内でDLL再配置エラーになったため、Rustビルド・テストは通常環境で実行した。

| コマンド | 結果 |
|---|---|
| `cargo test -p azookey-windows --lib --offline` | 374成功、2 ignored |
| `cargo test -p azookey-server --lib --offline -- --test-threads=1` | 検証専用APPDATAで54成功、1 ignored |
| `cargo test -p launcher --offline` | 10成功 |
| `cargo test -p shared --lib --offline` | 検証専用APPDATAで32成功、1 ignored。テーマ設定と旧モデル設定廃止のテストも成功 |
| `cargo check -p azookey-server --bin azookey-server -p ui --offline` | 成功。ただしbin指定のためUIは下記で別途確認 |
| `cargo check -p ui --offline` | 成功 |
| `cargo check --offline`（`frontend/src-tauri/`） | 成功、未使用コードの警告あり |
| `cargo fmt --all -- --check` | 最終状態で成功 |
| `git -c diff.ignoreSubmodules=all diff --check` | 成功 |
| `node --test crates/ui/tests/candidate_scroller.test.cjs crates/ui/tests/candidate_hidden.test.cjs` | 9成功 |
| `npm run typecheck`（`frontend/`） | 成功 |
| `npm test`（`frontend/`） | 18成功 |
| `node scripts/sync-version.mjs --check-release` | `frontend/src-tauri/tauri.conf.json` の既存リリース版情報不一致で失敗。今回、版情報は変更していない |

サーバーlibテストは通常のAPPDATA・並列実行では、correctorのmock推論テスト1件が「要求が送られない」で失敗した。検証用APPDATA・逐次実行では全件成功した。失敗原因が学習履歴か並列性かは未確定で、通常条件のテストまで全件成功したとは扱わない。

Swiftの再ビルド、サーバーbinのリンク・テスト、ブラウザ描画テスト、モデル推論評価、VM・実IME操作は未実施。VM設定 `windows/.local/vm.json` は存在しない。実行バイナリがこの復旧ソースへ更新されたとは主張しない。

移植比較の [機能差一覧](windows-android-feature-gap.md) は復旧前の状態を記録していたため、未接続という記述が現在には当てはまらない旨を追記した。

## 実IMEへの適用（2026-10-23 18:26 JST）

その後、ユーザーから実IMEに反映されないとの報告を受け、ルートの `Build-Trial.bat` を実行。Swift release、Rust server/UI/launcher・x64/x86 DLL、TypeScript/Vite/Tauri releaseをビルドし、11ファイルをバックアップ付きで適用した。上記の「ソース復旧のみ」は最初の復旧時点の記録であり、現在は実行環境にも反映済み。

- 適用時刻: `2026-10-23T18:26:02.6971667+09:00`。
- 更新パッケージ: `.local/trial-updates/20261023-182557-217`。
- バックアップ: `.local/trial-backups/20261023-182558-847`。
- manifest SHA-256: `831737a9cdc45ebbe63daf22deca00fe8967275d567d2b2f1b8df6ae646b4274`。
- 差替えたSwift DLL、64/32ビットTSF DLL、server/UI/launcher、frontend/updater helper、README・配布初期設定・ライセンスの11ファイルがmanifestと一致。
- launcher PID 17092、server PID 1916、UI PID 21256は適用後の新プロセス。起動先は `ZeroConvertIME-windows/`。既存アプリが読み込んだTSF DLLまで更新されたとは扱わない。
- 補正: `models/zerocon-model-corrector.gguf`、Vulkan、port 18090、health `ok`。
- 先読み: `models/zerocon-model-standard.gguf`、Vulkan、port 18091、health `ok`。
- 適用後に `Build-Trial.bat -WhatIf` 相当の確認を行い、変更なし・再ビルド不要であることを確認。
- `cargo test -p azookey-windows --lib --offline test_live_server_okseri -- --ignored --nocapture` が実サーバーに対して成功。打鍵順、非同期補正、direct適用、追加入力、削除、左右カーソル、打鍵履歴の復元、復元後の削除まで確認。補正要求から応答はこの1回で72ms（60msの停止待ち込み）。一般的な遅延性能の評価ではない。
- メモ帳のComputer Use起動は許可されなかったため、画面上の実打鍵・TSF表示操作の確認は未実施。VMは使用していない。

ビルド版表示は増分更新の既存identity `0.1.0-batao.15.dev.1792739943.g140bb742` を維持する設計のため、版番号だけでは今回の更新を識別できない。適用日時とmanifest・各ファイルのハッシュで識別する。

### Build-Trial / Apply-Trialの切り分けと修正

両batと呼出し先には、Git reset / stash / checkout等のソース復元処理はない。ソースが巻き戻った操作主体は引き続き未確定。

一方、`Build-Trial.bat` は増分ビルドと適用まで行うのに対し、引数なしの `Apply-Trial.bat` は `dist/packages/latest.json` の完全パッケージを選ぶ。バックアップ履歴では17:51の増分適用のあと17:53に16:25作成の完全パッケージが適用されていた。これはソースと別に実行ファイルが古い状態へ戻る経路である。

`apply-trial-package.ps1` と `distribution.psm1` を変更し、新しい増分更新より古い完全パッケージの暗黙適用を、検証・停止・コピーの前に拒否するようにした。意図して完全パッケージを使う場合の明示的な `-PackageDirectory` は維持する。`Apply-Trial.bat` の案内と [開発手順](local-installer.md) も更新。

- 増分テスト: 26 assertions成功。古い完全パッケージの拒否・新しい完全パッケージの許可・初回/完全適用済み環境の許可・検査時の状態非変更を含む。
- 配布テスト: 25 assertions成功。
- ビルド中の入力変更再検査テスト: 11 assertions成功。
- 実際の `apply-trial-package.ps1 -WhatIf` も古い16:25パッケージを拒否し、ファイル・プロセスを変更しないことを確認。

普段の変更反映は **Build-Trial.batだけ** でよい。更新前から開いているCodexやエディタ等は終了・再起動して、新しいTSF DLLを読み込ませる。
