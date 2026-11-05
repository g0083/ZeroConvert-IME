# 翻訳サーバーの常時起動設定（2026-11-05）

## 動作

詳細設定の翻訳欄に「翻訳サーバーを常時起動する」を追加。`general.translation_keep_alive` はbool、既定false。翻訳機能がONでこの設定がtrueなら、セッションがなくてもランチャーが翻訳サーバーとモデルを保持する。falseなら従来のセッション連動。翻訳機能自体がOFFなら常時起動設定に関わらず停止。ランチャーの監視周期は約2秒。モデル・ランタイムが未配置なら起動しない。ランチャーが所有しない外部起動サーバーは停止しない。

Rust共通設定、後方互換デフォルト、フロントエンドの型・既定値・スイッチ、配布初期設定、ランチャーの条件を更新。Swiftにはこのランチャー専用設定を渡す処理を追加していない。既存の設定保存処理を利用する。

実行バックエンドはメイン変換と共通の `zenzai.backend` と実効GPU層数を参照。現在の個人設定はCPU。メインモデルと翻訳モデルは別のモデル・プロセスであり、翻訳はポート18092の専用llama-serverを使う。

入力翻訳は最後の打鍵から400msの休止後に要求し、追加入力で待機タスクを取り消す。文末検知ではない。モデルのparallelは1で、Rustサーバーの翻訳枠も1。既に送信され開始した推論はクライアントの取消後も計算を終える場合がある。結果は入力世代・スナップショットで照合する。即時の推論中断や待ち行列ゼロを保証しない。今回、要求キャンセルの実装は変更していない。

## 検証

- `cargo test -p shared translation`: 3成功。既存設定にキーがない場合false、保存・再読込でtrueを保持する検証を拡張。
- `cargo test -p launcher translation_switch -- --test-threads=1`: 1成功。セッション有無、常時起動ON/OFF、翻訳機能OFF、モデル配置と共通バックエンドを検証。OSのグローバルセッション状態に依存しないテストへ変更。
- `npm.cmd run typecheck`: 成功。
- `npm.cmd test`: 18成功。
- `Build-Trial.bat -NoPause`: 成功、自動適用8ファイル・削除0。`.local/trial-updates/20261105-234327-056`、検証ハッシュ `68ca7ef6a76341e59989b7120a113abe80480e28d43bdb5f8f914c916e005b59`。ログ `artifacts/logs/translation/keep-alive-build.txt`。
- 実設定画面で常時起動OFF→ON。個人設定にtrue保存、翻訳モードなしでPLaMo 0.8BサーバーPID46696が起動し、`/health` がok。実行ファイルは `ZeroConvertIME-windows/llama_cpu/llama-server.exe`、CPU・threads4・ngl0・parallel1。
- 実設定画面でON→OFF。個人設定false保存、18092の翻訳プロセス数0を確認。確認後は元のオンデマンド設定。翻訳機能ON・モデル0.8Bなど他の既存設定を保持。

ブランチ `feature/windows-plamo-translation`、未コミット。既存の多数の変更は保持。インストーラー作成、コミット、pushは未実施。VM検証は行わず、このPCの試験IMEで起動・停止を確認した。

最終チェック: `cargo fmt --all --check` と `git diff --ignore-submodules --check` は成功。`node windows/scripts/sync-version.mjs --check-release` は既存の `frontend/src-tauri/tauri.conf.json` のリリースメタデータ不一致を報告。今回の試験ビルド・適用は成功しており、正式リリース設定の変更は行っていない。
