# 英字モードの単語補完（Windows）

英字モード（A）でアルファベットを入力すると、2文字目から辞書の前方一致候補を最大8件表示する。入力原文は先頭候補に残し、補完は明示的に採用する。先頭大文字・全大文字・入力済み部分の大文字小文字を保持する。

- Tab: 選択中の候補を採用・確定。未選択なら最上位の補完を採用する。Shift+Tabは未選択時に末尾の補完を採用する。
- 上下キー: 候補を選択。Enter: 現在の表示を確定。
- Space・数字・句読点: 現在の表示を確定して、その文字を入力する。
- Backspace: 入力原文を1文字削除して候補を更新する。
- Esc: 補完のプレビューを取り消し、入力原文を確定する。
- 左右キー・Delete: 単語を確定する。その後のキー操作は通常のアプリ入力になる。
- 日本語モード切替・フォーカス移動: 既存のcomposition終了処理で確定する。

実装は `windows/crates/client/src/engine/composition/english.rs` と共通ストア `windows/crates/shared/src/english_learning.rs`。辞書はAndroid側の既存 `android/app/src/main/assets/english_words.txt` を基に、単語と頻度順位をコンパイル時の配列 `english_words.rs` に格納する。検索は二分探索で前方一致範囲を絞り、共有Rustの英字学習結果（`english-learning.json`）と合わせて上位候補を構成する。入力ごとのディスク読み込み・英語LLM推論は行わず、Windows側は非同期RPC＋ローカルキャッシュで同期し、Android側は `engine/rust` 経由で同ロジックを呼ぶ。日本語AIアシストと変換学習から分離し、パスワード等の機密入力欄では新しい英字compositionを開始しない（学習の読み書きも抑止する）。

単語・補完の学習と頻度反映は行うが、文脈からの次単語予測（bigram予測）は行わない。

## 初期検証・適用ログ（2026-10-23・アーカイブ）

> [!NOTE]
> 以下は 2026-10-23 に Windows 英字補完の初期実装（`fix/typo-correction-pipeline` ブランチ時点）を検証した記録です。最新の共有学習仕様（v0.8.0）は下の「Windows・Android 共通の英字学習機能」節を参照してください。

### 当時の検証（2026-10-23）

作業ブランチは既存の `fix/typo-correction-pipeline` を維持し、既存の未コミット変更を保持した。

- `cargo build -p azookey-windows`: Windows x64のdebug DLLビルド成功（既存のリンク警告あり）。
- `cargo test -p azookey-windows --lib -- --quiet`: 374件成功、2件ignore。ローカルIPC接続を伴うため、サンドボックス外で確認した。
- `cargo test -p azookey-windows english --lib -- --quiet`: 英字補完のケース・編集・候補採用・取消・句読点・カーソルキー・モード切替テスト成功。
- 変更Rustファイルの `rustfmt +1.98.1 --edition 2021 --config skip_children=true --check ...` と `git -c diff.ignoreSubmodules=all diff --check`: 成功。
- `cargo fmt --all -- --check`: 今回未変更の `windows/crates/ui/src/main.rs` に書式差分があり失敗。
- `cargo clippy -p azookey-windows --lib -- -D warnings`: 既存の `composition.rs` に `if_same_then_else`、`too_many_arguments`、`needless_borrow` があり失敗。
- `node scripts/sync-version.mjs --check-release`: 既存の `frontend/src-tauri/tauri.conf.json` の版情報不整合で失敗。版情報は変更していない。
- `python windows/scripts/vm.py --help`: Windowsにない `fcntl` のimportで失敗。VM操作は行っておらず、実IME画面・アプリ間切替・フォーカス移動の確認は未実施。インストール済みバイナリの更新も未実施。

## 実IMEへの反映（2026-10-23）

`cargo build -p azookey-windows --release` と `cargo build -p azookey-windows --target i686-pc-windows-msvc --release` が成功。登録先の `ZeroConvertIME-windows/azookey.dll` と `azookey32.dll` にコピーし、ビルド出力とSHA-256が一致することを確認した。旧DLLは `.local/runtime-backup/english-20261023-020842/` に保存。モデルとユーザー設定は変更していない。

`windows/deployment/Restart-All-ZeroConvertIME.ps1 -InstallDirectory D:\angra\ZeroConvert-IME\ZeroConvertIME-windows -NoNotification` でlauncher・server・UI・ctfmonを再起動。再起動ログは `ZeroConvertIME-windows/restart-ime.log`。64/32ビットのCOM登録先も更新済みのファイルを指している。

Computer Useでメモ帳の確認用ウィンドウに実キーを入力し、`h` → `he` → `hel` の順に確認した。`he` では `here / help / health`、`hel` では `help / held / helpful / hello` が候補表示され、Tabで `help` が確定して候補ウィンドウが閉じることを確認。確認用の `help` 文書は未保存のまま残している。既存の文書は変更していない。

既存アプリが旧DLLを保持している場合は、その入力先アプリを開き直す必要がある。今回の確認は実機のメモ帳で行い、VMの状態は変更していない。

## 英字モードの原文フローティング非表示（2026-10-23）

英字モードでは入力欄と重複する原文フローティングを非表示にする。候補更新の共通経路でreadingを空文字へ明示的に更新するため、入力・削除・上下キーによる候補選択でも表示が復活しない。補完候補一覧は表示を維持し、日本語モードの読み表示には変更を加えない。

## Windows・Android 共通の英字学習機能（2026-11-19）

Windows（`InputMode::Latin`）と Android（`KeyboardMode.ENGLISH`）の双方で、共通の Rust エンジン（`windows/crates/shared/src/english_learning.rs`）による英字単語・補完学習を行う。永続化先は `%APPDATA%\Azookey\english-learning.json`（Android は `Azookey/english-learning.json`）。

### 学習・昇格ルール

- **1回目で昇格**: 候補の明示選択（Windows の `Tab`・候補クリック・上下キー選択後の確定、Android の候補タップ・補完候補への `Space` 自動確定）および静的辞書に存在する単語の直接確定は 1 回目で昇格し、前方一致に対する選択上書き（`prefix -> word`）も優先反映する。
- **2回目で昇格（OOV）**: 静的辞書にない未知語（OOV）を候補選択なしで直接確定した場合、1回目は保留状態に留め、2回目の確定で昇格して補完候補へ表示する。誤入力を1度確定しただけで候補汚染しないための Gboard 型ルールである。
- **入力切り替え確定の学習**: `Space`・`Enter`・記号入力に加えて、Windows の入力モード切替ショートカット／言語バー切替、Android の `beforeKeyboardModeChange()` による未確定英字の確定も通常確定として学習する。一方で Windows の `Escape` 取消、先頭までの `Backspace` 消去、`Delete`・左右キーによる確定終了、フォーカス喪失では学習しない。
- **大文字・小文字の保持**: `ZeroConvert`・`GitHub`・`API` など特徴的な大文字表記を含む確定語はそのままの表記を保持する。通常の小文字語は入力プレフィックスのケースに追従する。

### 削除・プライバシー制御

- **候補ごとの学習削除**: 学習由来として昇格した候補には Windows 側で `ai_candidate::ENGLISH_LEARNING`（右クリック削除対象）、Android 側で `"en:<lowercase>"` ID（長押し削除対象）を付与する。静的辞書のみの未学習候補には削除用IDを付けない。
- **設定画面からの確認・削除**: Windows・Android 双方の設定画面で学習済み昇格単語数（`english_count` / `英字学習`）を表示し、`"english"` スコープとして変換学習・補正学習とは独立に選択削除できる。
- **制限**: 機密・パスワード入力欄では学習の参照・記録を行わない。また設定の学習モード（`enabled` / `read_only` / `disabled`）に従い、`read_only` では記録と個別削除を、`disabled` では読み書き・削除をすべて抑止する。
