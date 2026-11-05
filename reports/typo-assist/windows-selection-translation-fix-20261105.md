# 選択翻訳・DLLエラー・読み込み表示の修正（2026-11-05）

## 問題と変更

利用者から、`keyboard-prior.exe` の `llama_memory_clear` エントリポイント欠落、選択翻訳ボタンでのIME停止、モデル準備前の状態表示不足、選択ツールの見た目の問題が報告された。

- ワーカー探索が配備先より開発ビルドキャッシュを優先し、実行時に未存在DLLだけをコピーしていた。そのためキャッシュ内の古いDLLと新しいEXEを組み合わせ得た。配備済み `llama_cpu/keyboard-prior.exe` を優先し、DLLも同じフォルダを利用する。実行時のDLLコピーを削除。サーバーの子プロセスがDLLロード失敗でモーダルダイアログを出し続けないようプロセスのエラーモードを設定。
- 選択翻訳のHWND操作を入力アプリのTSFスレッドから撤去。有界・非待機のコマンド送信だけを残し、専用スレッドが選択ツールと結果カードを所有する。HTTPも別スレッド。要求の世代を照合し、閉じた・置き換えた要求の結果を無視。同じ翻訳の連打は重複起動しない。
- 入力プロセスのTSFが解除された後もスレッドが安全に終了できるよう、スレッドのコードを含むモジュールをプロセス終了まで保持する。
- 固定サイズの白いSTATIC/BUTTONを置き換え、IMEテーマに合う描画、Segoe UI、角丸枠、DPIに合わせた余白、短い結果向けのコンパクトな高さを採用。結果カードに閉じる・コピーを配置。入力先のフォーカスは維持。
- `/health` の200 / `status: ok` をモデル準備完了とし、「モデルを読み込み中」→「翻訳中」→結果へ遷移。準備待ち30秒・翻訳10秒で上限を設ける。入力バーもバックグラウンドで準備状態を監視する。
- 選択翻訳のHTTPはContent-Lengthまで読んで返す。keep-aliveのEOFを待たない。応答サイズ・接続・読取りに上限を設ける。
- 旧版のイベントと種類の違うカーネルオブジェクト名が衝突しないよう、共有セマフォ名を `translation_sessions_v2` へ変更。

## チェック

- `cargo check -p azookey-windows -p azookey-server -p ui`: 成功。最終カード調整後のclient checkも成功。
- `cargo test -p azookey-windows --lib -- --test-threads=1 --quiet`: 383成功、2無視。その後の変更はポップアップの所有ウィンドウ・枠線・高さとモジュール保持。最終のx64/x86ビルドで確認。
- `cargo test -p shared translation`: 3成功（接続を閉じないローカルHTTP fixtureの応答をContent-Lengthで完了する回帰テストを追加）。
- `cargo test -p azookey-server --lib deployed_worker -- --test-threads=1`: 1成功。配備EXEと同じフォルダのDLLを選択する回帰テスト。
- 配備済みワーカーの無引数起動: usageを表示して正常な引数エラーコード2で終了。エントリポイント欠落なし。
- 入力バーのJavaScriptをDOM stubで実行し、未準備→準備完了・翻訳中→訳文表示を検証: 成功。
- `node windows/scripts/sync-version.mjs --check-release`: 以前からの `frontend/src-tauri/tauri.conf.json` のリリースメタデータ不一致。正式リリースは作成していない。

## 適用

`Build-Trial.bat -NoPause` でビルド・自動適用。

1. `.local/trial-updates/20261105-230526-013`: 7ファイル、検証ハッシュ `743e5dbfb43ea2add592d0626d816608e092b7951e7a9fb6f62524f919b190a3`。通信・サーバー・UI・セッション・クライアントの変更。
2. `.local/trial-updates/20261105-231227-044`: 2ファイル、ハッシュ `12cd923a894c30c8fbdc3884c04ea2ff1b9954175d602db02bff650acf71054e`。所有ウィンドウと重複翻訳防止。
3. 最終 `.local/trial-updates/20261105-232322-592`: 2ファイル、ハッシュ `15c6671be55757ed7e504b1d54e989462d6b289e67f8771ca8eeceaf13a05c55`。枠線とコンパクトなカード。ログ `artifacts/logs/translation/selection-card-build.txt`。

既存の未コミット変更・個人設定を保持。ブランチ `feature/windows-plamo-translation`。コミット・push・インストーラー作成は未実施。

## 実IMEでの確認

WPF TextBoxを持つ専用検証アプリを、新しいDLLの適用後に起動。WinForms RichTextBoxでは選択通知を十分に確認できなかったため、選択翻訳の根拠には使用しない。ブラウザ検証用の独立プロファイルは初回セットアップ画面で止まったため、本確認の根拠には使用しない。

最終DLLでの操作:

1. `The meeting is tomorrow.` をCtrl+Aで選択。24文字と翻訳ボタンの新しいフローティング表示を確認。
2. 翻訳ボタンを実際にクリック。結果カードに「モデルを読み込み中…」「準備ができると自動で翻訳します。入力はそのまま続けられます。」を表示。入力先の選択とフォーカスを保持。
3. 自動で結果 `会議は明日です。` を表示。元の英文は置換されない。
4. Rightで入力先の選択解除とカーソル移動が即座に応答。閉じるボタンでカードだけが消え、入力先のフォーカスを維持。
5. カードの高さ・枠線調整前の同じスレッド分離版では、翻訳後にRight→Enter→aで改行と「あ」のIME入力、Escで追加分の取消も確認。

翻訳モデルは既存設定の0.8B、CPU、threads4、ngl0、ポート18092。ワーカー実行パスも `ZeroConvertIME-windows/llama_cpu/keyboard-prior.exe` であることをプロセス情報から確認。モデル重み・個人設定は変更していない。

未確認: 多数の入力アプリ、長文のスクロール表示、複数DPIをまたぐ移動、モデル2B、コピー操作（既存クリップボードを維持するため未実行）、VM検証。

追加の最終確認: 結果カードを閉じた後、Ctrl+T→a→iで「愛」を入力し、追加入力なしで本文末尾が `Love` へ更新された。Escで追加分だけ取消。最終プロセス確認では翻訳ポート18092が停止し、予測ポート18091を維持。WPF検証アプリと専用プロファイルのブラウザプロセスは終了した。通常の利用中ブラウザは保持。`cargo fmt --all --check` と `git diff --ignore-submodules --check` も成功。
