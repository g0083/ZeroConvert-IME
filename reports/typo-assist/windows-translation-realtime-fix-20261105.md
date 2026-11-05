# 翻訳入力のリアルタイム反映修正（2026-11-05）

ブランチ `feature/windows-plamo-translation`。既存の多数の未コミット変更を保持して編集。コミット・push・インストーラー作成は未実施。

## 原因と修正

翻訳完了時には候補UIしか更新せず、入力先の書換えを次のキー入力時のマクロに置いていた。そのマクロを削除し、世代・文脈・TSFコンテキスト・Compositionの照合後、結果到着時にTSF SetTextを実行する。日本語の通常候補と読み・raw_inputは保持する。EnterもTabと同じ英訳全文を確定する。未到着時の日本語誤確定を防ぐ。

Ctrl+Tのモード開始が未接続だったためTSFキー経路へ接続。設定ONとモード開始を分け、通常入力は維持する。翻訳バーは既存の順序付き候補UIチャネルで更新し、存在しないJavaScript関数を呼ぶ旧イベント処理とフォーカス奪取経路を削除。セッションはクライアントが所有し、確定・取消・フォーカス変更で解放する。共有イベントのResetによる所有者競合をセマフォへ変更。起動待ち再試行と失敗表示を追加。

選択翻訳は非アクティブ表示とし、結果表示をPostMessageへ変更。世代番号で前の要求・閉じたウィンドウへの遅延結果を破棄する。

## チェック

- `cargo test -p azookey-windows --lib -- --test-threads=1 --quiet`: 383成功、2無視。初回は新しく追加した通常モード用設定コピーにテスト専用reconversion設定を反映していなかったため1件失敗。テスト補助を修正後に全件成功。
- その後の失敗表示追加後、`cargo test -p azookey-windows --lib translation -- --test-threads=1`: 3成功。
- `cargo test -p shared translation`: 2成功（重複セッションの片方の解放も含む）。
- `cargo test -p azookey-server --lib translation`: 3成功。
- `cargo check -p azookey-windows -p ui -p azookey-server` と追加変更後の `cargo check -p ui`: 成功。
- サンドボックス内のprotocはOSエラー623。通常環境へ権限を切替えて上記を実行。
- `cargo fmt --all --check` と `git diff --ignore-submodules --check`: 成功。
- `node windows/scripts/sync-version.mjs --check-release`: 既存のfrontend/src-tauri/tauri.conf.jsonのリリースメタデータ不一致。正式リリースは作らないため、このタスクでバージョンを変更しない。

## ビルド・実IME

`Build-Trial.bat -NoPause` 成功、自動適用7ファイル、削除0。パッケージ `.local/trial-updates/20261105-224134-518`、検証ハッシュ `a88bcfc289fc1f339389690396ca329f78b56a21a9f483b0af4bd011e6eb4099`。ビルドログ `artifacts/logs/translation/realtime-fix-build.txt`。ビルド識別子 `0.1.0-batao.15.dev.1792739943.g140bb742` は未コミット差分そのものを識別しないため、上記パッケージハッシュと併記する。

適用後に検証用RichTextBoxアプリを開き直した。個人設定は翻訳ON・0.8Bの既存値を維持。実行パスは `ZeroConvertIME-windows/`、翻訳サーバーは同フォルダの `llama_cpu/llama-server.exe`、モデルは `models/plamo-3-translate-0.8b-Q4_K_M.gguf`、CPU・threads4・ngl0・ポート18092。モデルSHA256 `3dbb09295bfd3350231ba0dba92bc72cb14111ef1b034e37dad39d581bd86b9c`。

確認した順序:

1. 空欄でCtrl+T、a → i、「愛」が次のキーを押さずに `Love` に置換された。
2. Backspaceで「あ」へ戻り、iを再入力して再び `Love` へ置換された。
3. Enterで `Love` 確定。次のEnterは改行。
4. モードを再開せずa → i。日本語「愛」のまま保持。
5. その途中でCtrl+T、Left、Right。追加分が `Love` へ翻訳。Escで追加分だけが消え、前の確定済み `Love` は保持。
6. 英文確定後のプロセス読取りで、翻訳ポート18092のプロセスが終了し、予測ポート18091だけが維持されることを確認。
7. 確定済み英文選択→Ctrl+Tでも本文・フォーカスを保持。ただし別のツールウィンドウの結果表示は今回のアプリ単位のキャプチャで確認できていない。

## 未確認範囲

多数アプリでの長文・高速連続打鍵、モデル2B、サーバー異常終了中のUI、翻訳バーの外観・画面端・複数DPI、選択翻訳ポップアップの最終訳文表示は未確認。単語のスモーク確認を翻訳品質・遅延の保証としない。VMによる検証は行わず、このPCの新規入力プロセスで確認した。

最終のプロセス確認でも翻訳ポート18092は停止、予測ポート18091は稼働。検証アプリには確定済みのテスト文字列 `Love` を残した。個人設定の変更なし。
