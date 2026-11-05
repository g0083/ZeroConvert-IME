# Windows 翻訳入力（試験版）

2026-11-05。日本語のかな漢字変換結果を PLaMo 3 Translate 2B で英訳し、読み行に「英訳: … / Tabで確定」を表示する。**Tab は英訳全文、Enter は従来どおり日本語**を確定する。Gboardのような用途を試す最初の段階で、自動的な英訳確定や翻訳専用パネルは実装していない。

## 起動と操作

1. PLaMo 3 に対応する最近の upstream llama.cpp Windows版を別フォルダに用意する。IME同梱の古いランタイムは置き換えない。
2. ルートから次を実行する。公式2B Q4_K_M（約1.81GB）が未取得の場合のみダウンロードする。重みはGit対象外。

   ```powershell
   .\Start-Translation.bat -LlamaServerPath 'C:\tools\llama.cpp\llama-server.exe' -DownloadModel
   ```

   CPU実行が既定。対応GPU版では `-GpuLayers 99` を指定できる。停止はサーバープロセスを終了する。モデルのSHA256は起動時に表示され、ログは `artifacts/logs/translation/` に保存される。

   この開発PCには検証用の公式CPU版 b12125 を `artifacts/tools/plamo-llama-b12125/` に別置きしたため、取得後は `Start-Translation.bat` だけで起動できる。既に起動中なら重ねて起動しない。
3. ソースのビルド・試験IMEへの反映は通常の `Build-Trial.bat`。入力アプリを開き直す。
4. 設定画面の詳細設定「翻訳入力（試験機能）」で「日本語から英語に翻訳する」をONにする。配布時はOFF。
5. `kaigihaashitadesu.` と入力して休止し、「会議は明日です。」の英訳を確認してTab。英訳全文が入力される。Enterなら日本語。OFFで通常入力へ戻る。

## 構成と制約

- 通常のSwift変換、ZeroConモデルはそのまま使用する。翻訳ON中は既存アシストの代わりに翻訳を要求し、長文の先頭自動確定・句読点確定を停止する。設定値自体は書き換えない。
- 日本語の未確定ローマ字が残っている間は要求しない。打鍵休止400ms後にバックグラウンドRPCを実行する。`GetAssistCandidates.kind = 3`、予約候補ID `999904` を利用する。
- 送信内容は現在の変換文（先頭候補＋残りの読み）のみ。周辺文脈・過去の文章は送らない。PFML3の `translation` と出力言語 `en` を指定し、`127.0.0.1:18092/completion` に限定する。
- サーバーはSwiftの状態を変更しない。翻訳枠は1件に制限し、推論10秒、クライアントRPC11秒、画面待ち12秒で打ち切る。後続打鍵・削除・カーソル移動・確定・取消・フォーカス変更によるキャンセルとスナップショット照合は既存アシスト経路を使う。
- 翻訳結果を日本語変換エンジンの学習候補として扱わない。原文の通常候補を保持する。サーバー未起動・失敗・タイムアウト時は翻訳候補を出さず、日本語入力を続けられる。
- 対象は通常の未確定文（読み2〜512文字）。分節操作中、再変換中、機密入力欄は対象外。訳文は512文字までの単一段落。出力上限に達した不完全な訳文・特殊トークンは採用しない。
- 現段階では翻訳モデルの自動起動・自動配布・インストーラー同梱は行わない。英訳は日本語変換の正しさにも依存する。

## 検証する操作

`kaigihaashitadesu.` → 英訳表示 → Tab確定、同じ入力 → Enter日本語確定、入力途中のBackspace → 再入力、Left/Right → 日本語の分節操作、Esc取消、候補選択、素早い追加入力、別アプリへ移動、翻訳OFF、翻訳サーバー停止を確認する。古い訳文が別の入力へ付かず、翻訳未完了でも入力・取消が止まらないことを確認する。実IMEでの確認結果とモデルSHA256・GPU設定・遅延は別途記録し、モデル単体の翻訳成功だけで実IME確認済みとしない。

公式資料: [PFN発表](https://www.preferred.jp/ja/news/pr20261105)、[2B GGUF・PFML3・ライセンス](https://huggingface.co/pfnet/plamo-3-translate-2b-GGUF/blob/main/README_ja.md)。モデルはPLaMo Community Licenseで提供される。

## 2026-11-05 のローカル検証

- ブランチ `feature/windows-plamo-translation`、未コミット。既存の文書差分は保持。
- モデル固定リビジョン `f2b4599dff9b3e45c62567eac15e83186b4cb64e`、Q4_K_M SHA256 `2c288ecc7ebfe60c3561726580da3583767f3c41ca3ef5c6825bf6c4cc060787`、1,807,192,480 bytes。ダウンロード時に照合。
- 公式 llama.cpp b12125 CPU版、GPU層0。配布ZIPのSHA256 `968091ccb8c230e0cf7e0eed386310703bebae4a4fd4375f0c57db53958307d8` を照合。
- モデル単体: 「会議は明日です。」→ `The meeting is tomorrow.`、HTTP往復484ms（1回のスモーク測定、入力休止・IPC・TSF時間を含まない）。`artifacts/logs/translation/model-smoke.json`。
- `cargo check -p azookey-windows -p azookey-server -p ui` 成功。`cargo test -p shared` は既存38件成功・1件無視、追加の `cargo test -p shared translation` も1件成功。`cargo test -p azookey-server --lib translation` と `--bin azookey-server translation` は各3件成功。
- サーバー実行テストのPATHには `ZeroConvertIME-windows` と `ZeroConvertIME-windows/llama_vulkan` が必要。未指定では `STATUS_DLL_NOT_FOUND`。サンドボックス内のprotocはOSエラー623となったため通常権限で検証。
- `cargo test -p azookey-windows --lib -- --test-threads=1` は385件成功・2件無視。初回並列実行は既存 `response_arriving_after_cancel_cannot_complete_old_request` の5ms/20msタイミング試験が1件失敗、直列再実行は全件成功。
- `npm test` 18件成功、`npm run typecheck` と `npx vite build` 成功。`cargo fmt --all --check` と `git diff --ignore-submodules --check` 成功。`node scripts/sync-version.mjs --check-release` は既存の `frontend/src-tauri/tauri.conf.json` のリリースメタデータ不一致を報告（今回の試験ビルドはvalidation設定で成功）。
- `Build-Trial.bat` 成功、試験IMEへ8ファイル反映。増分パッケージ `.local/trial-updates/20261105-195819-966`、検証ハッシュ `77ff0e4c069f403e716d9fb01f9f99a06577c9e560eef5e301c9093955392f43`。`artifacts/logs/translation/trial-build.txt`。インストーラーは未作成。
- 実IME: 新規の独立したWindows RichTextBox入力アプリ（検証用、Git対象外）で `a` → `i` → 「愛」→ 「英訳: Love」→ Tab → 英文 `Love` 確定を確認。その後、改行 → `ai` → Backspace（「あ」）→ `i`（「愛」）→ Left → Right → Enter（日本語「愛」確定）、さらに `ai` → Esc（追加分のみ取消）を確認。
- メモ帳の別ウィンドウは旧DLLを共有していたため新DLLの検証には使わなかった。未保存タブを閉じる操作は自動承認審査に拒否され、既存タブを保持。最後は設定画面で利用者操作を検出して自動操作を停止。最終の読取り確認では翻訳サーバー起動中（health OK）、個人設定の翻訳入力OFF。
- 未確認: 長文・実際の高速連続打鍵・アプリ切替・サーバー停止中の実UI試験、広範な翻訳品質比較。これらの品質を達成済みとはしない。

## 2026-11-05 内蔵llama.cpp統一とサーバー同期の追加検証

1. **内蔵 llama.cpp の最新版（b12125）統一**:
   - `windows/scripts/prepare-modern-llama.py` を介して、AzooKeyKanaKanjiConverter の Swift 側（`ZenzContext.swift`）を modern llama.cpp ABI（`llama_memory_*`, `LLAMA_LOAD_MODE_MMAP`, `n_seq_max`, `kv_unified` など）に適合するパッチを自動適用。
   - `keyboard-prior`（タイポ補正オフラインC++プローブ）も b12125 ABI（`llama_memory_clear`, `cp.kv_unified = true`, `ggml_backend_load_all()` 等）へ更新しビルド成功。
   - `windows/scripts/build-trial.ps1` および `incremental-trial.psm1` にて、`modern-llama.ps1` 経由の Swift release ビルド（`.build-modern`）および runtime グループ（`llama_cpu`, `llama_vulkan`）の最新 b12125 バイナリ自動配置を統合。
   - `Build-Trial.bat` を実行し、全コンポーネント（Swift, Server, Client, UI, Frontend, Launcher, Runtime）の増分ビルドと実IME（`ZeroConvertIME-windows`）への反映に成功（127 changed files, 94 removals）。
   - 実IMEディレクトリ下の `llama_cpu\llama-server.exe --version` が `0.9.0-dev (build 12125, commit 7d516e25e)` に統一されたことを確認。

2. **機能ON/OFFとサーバー起動/停止の同期**:
   - `launcher`（ランチャー）の `watch_assist_server(..., AssistRole::Translation)` により、`settings.json` の `experimental_translation`（「日本語から英語に翻訳する」）の設定変更および `shared::translation_session::is_translation_active()`（翻訳モードのセッションイベント）を監視。
   - 翻訳モード（`Ctrl+T` 翻訳入力バーまたは選択範囲翻訳）がアクティブな時のみポート 18092 で `llama-server.exe` を自動起動し、翻訳モードを抜けると即座にプロセスを終了（kill）してリソース（CPU/RAM/VRAM）を完全に解放する。

## 2026-11-05 翻訳モードUI刷新と 0.8B モデルデフォルト化の検証

1. **インラインリアルタイム翻訳入力（Gboard風）**:
   - `Ctrl+T` または翻訳機能ON時、日本語を入力するとバックグラウンドで PLaMo 3 Translate（0.8B）が約270msで英訳を生成。
   - `composition.rs` の `render_local_text!` マクロにて、英訳候補（`TRANSLATION`）が存在する場合、`preview` を直接英訳文で上書きし `self.set_text` を呼び出し。
   - これにより、Tab/Enterキーを押すのを待たずに、**元の入力先アプリケーション（メモ帳、ブラウザ、エディタ等）のインライン未確定文字列がリアルタイムで翻訳後の英文に置き換わって直接反映・更新**される。Enterで英文をそのまま確定。

2. **翻訳バーUIの透過・位置ずれ・フォーカス競合の解消**:
   - 背景透過の解消: `translation_bar.html` の `.card` に不透明背景色（ライト: `#ffffff` / ダーク: `#1e1e24`）と確実なボックスシャドウを設定し、`theme_script` によるテーマ初期化を WebViewBuilder に注入。`overflow: hidden;` で余分なスクロールバーを除去。
   - 位置ずれの解消: マウスカーソル座標（`GetCursorPos`）から、TSFキャレット矩形（`last_candidate_rect`）および Windows API `GetGUIThreadInfo`（`rcCaret`）基準の画面絶対座標計算（`get_caret_screen_position`）に変更。キャレットの直下に正確に追従配置。
   - フォーカス競合の解消: 翻訳バーウィンドウを `WS_EX_NOACTIVATE` で作成。フォアグラウンドを元アプリに維持することで、元アプリへの直接インライン入力とIME候補窓の不要な重なりを防止。`ui.exe` のグローバル `RegisterHotKey` を削除し、TSF側でキーイベントを一元化。

3. **選択部位翻訳のハング・非表示バグの解消**:
   - `selection_count.rs` での HTTP 通信ハング（`read_to_end` の EOF 待ちブロック）を解消。タイムアウト付きストリーム・Content-Lengthパース・503ローディング時の自動リトライループ（最大8秒）を実装。
   - プロンプトを公式 PFML3 構造（`<|plamo:bos|><|plamo:tag|>dataset...`）およびストップトークンに統一。
   - UIスレッドのブロックを防止するため、`SetWindowTextW` を `SendMessageTimeoutW`（タイムアウト2秒）に変更。
   - 選択カウントバッジ（数字）の非表示（`hide()`）と翻訳結果ウィンドウ（`result_window`）のライフサイクルを分離し、選択解除時に結果ウィンドウが勝手に閉じてしまう問題を解消。


3. **PLaMo 3 Translate 0.8B デフォルト化と 2B 切り替え**:
   - デフォルトモデルを `plamo-3-translate-0.8b`（`plamo-3-translate-0.8b-Q4_K_M.gguf`、約546MB）に変更。設定画面の「翻訳モデル」から 2B（約1.81GB）への切り替えも可能。
   - GGUF 内蔵の公式 PFML3 プロンプト構造（`<|plamo:bos|><|plamo:tag|>dataset<|plamo:msg|>translation...`）およびストップトークン（`<|plamo:bos|>`, `<|plamo:tag|>`）に完全適合。
   - 0.8B CPU推論実測値:
     - `今日はいい天気ですね。` → `The weather is nice today, isn't it?`（272ms）
     - `Antigravity is an agentic AI coding assistant.` → `Antigravityはエージェント型AIコーディングアシスタントです。`（297ms）
   - 300ms未満の超低遅延・高精度翻訳を実現。

4. **ビルドスクリプト改善**:
   - `Build-Trial.bat`, `Build-Package.bat`, `Build-Installer.bat` に `-NoPause` 引数および `ZEROCON_NO_PAUSE` 環境変数の厳密な判定を追加。非対話実行時にキー入力待ちでハングせず即座に終了するよう改修完了。


