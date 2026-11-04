# 隣接キー・辞書・小型AI補正のWindows実IME適用

2026-10-28。研究で選んだ小型・厳しめ設定をWindowsの実IMEへ統合し、Build-Trial.batでビルド・適用した。今回の確認で実使用の「体感50%以上」を達成したとは判断しない。オフラインの読み復元60.7%と、実IMEの最終表記・操作成功を区別する。

## 実装・反映範囲

- 正本は `windows/native/keyboard-prior/`。元の小文字ASCIIローマ字4〜64キーから隣接キー1文字の修正を生成し、AZR2辞書で最大8読みへ絞る。凍結済みの辞書点＋2×読みLM点、元入力との差37以上を使用する。
- Rustの `ai_assist/keyboard_hybrid.rs` が永続子プロセスを背景で初期化する。要求250ms、表記生成最大200ms、全体予算450ms。初期化中・競合中は待たず従来経路へ戻る。返却読みと現在のSwift読みを照合し、異なる入力テーブル・古い入力には提案しない。
- 最終表記は既存の予測モデルを利用し、読み一致を検証する。生成失敗・時間切れでは補正済みひらがなを出す。辞書の単語分割による不自然な漢字表記はそのまま表示しない。
- 新提案はrelevance 80の手動採用候補。既存の既知タイポ自動補正を保持し、新提案の自動適用・自動確定は許可しない。通常候補を保持する。
- clientの `composition/typo.rs` は先読みと補正の初回結果を非同期で最大200ms待ち合わせる。先に表示したTab対象を後着結果で置き換えない。Tab採用・Escape取消・世代照合の既存経路を使う。
- 増分ビルド、配布モデルカタログ、全体パッケージ生成に専用ワーカーと追加モデルを含めた。追加モデルは18,452,288 bytes（Q4_K_M）、SHA256 `494f925dd9078fec88702a05d5516930aa6871be414e915575504be311b1986c`。既存補正モデルを置換せず、ディスク使用量が約18.5MB増える。

## 検証

研究からネイティブへの候補集合・辞書点の移植を開発762件で照合し、762/762一致。実際の打鍵プロトコルによる提案も凍結研究結果と762/762一致した。モデルの初期化はUTF-8変換器の再利用後に約0.357秒。

| 確認 | 結果・証拠 |
| --- | --- |
| 共通Rustエンジン | `cargo test --manifest-path engine/rust/Cargo.toml --lib -- --test-threads=1`：57成功・1除外。その後のkeyboard_hybrid限定3件も成功 |
| Windows server | 辞書・DLLを持つ隔離fixtureでコンパイル済みserverテストを実行：72成功・1除外。`artifacts/logs/keyboard-server-tests-runtime.log` |
| client | 全体381成功・2除外。その後の初回待ち合わせ・優先順を含むtypo限定7件成功。`artifacts/logs/keyboard-client-coalescing-tests.log` |
| 増分ビルド | PowerShell増分テスト28 assertions、retry統合11 assertions成功。Build-Trial.bat成功、追加のApply-Trialは実行していない |
| 実行中RPC | 10入力列をキー順に送り、補正・先読みを並行取得、削除・左右移動・取消まで検証。`artifacts/results/keyboard-hybrid-20261027/installed-hybrid-rpc-final.json` はfailuresなし |
| 子プロセス終了からの復旧 | 実行パスを検証したkeyboard-priorのみ終了し、RPC検証を2回実行。両方failuresなし。PID 49016→18276。`installed-recovery-first-20261028.json` / `installed-recovery-second-20261028.json` |
| 適用状態 | `powershell -ExecutionPolicy Bypass -File windows/scripts/build-trial.ps1 -WhatIf`：No changes、ビルド・再起動なし |

Windowsホスト上の新規Win32 EDIT検証アプリで、Unicode貼付ではなく実キー入力を使用した。ユーザーがZeroConvert IMEの「あ」へ切り替えた後、以下を確認した。

1. `siryouwookurimssu` → 元の「資料を送りmっす」を保持したまま「しりょうをおくります [補正]」が到着。Tabでかなの補正文を採用し、Escapeで元の誤入力へ戻った。
2. 削除・左右移動・再入力でcompositionと候補が更新されることを確認した。この編集列は正しい入力へ戻す操作ではなく、最終表記の精度試験には数えない。
3. 新しい入力 `ksiginosiryou` → 「会議の資料 [補正]」。Tab採用後、Enter確定の編集欄Valueが正確に「会議の資料」で、表示ラベルが本文に入らないことを確認した。
4. 正常入力 `kaiginosiryou` は「会議の資料」となり、補正ラベルなし。Enterで同じ文字列を確定した。

検証確定で生じた合成例1件の補正学習は、退避の上で該当キーだけを検証前へ戻した。他の学習記録を保持し、試験サーバーをlauncher経由で再起動してメモリ上の合成学習も除いた。検証前後の退避は `.local/keyboard-ime-verification-20261027/` に保管し、Git対象外。

除去・再起動後の最終RPCも10入力列すべてfailuresなし（`installed-clean-final-20261028.json`）。`ksiginosiryou` は学習済みのrelevance 70ではなく、新経路の80で「会議の資料」を返した。正常入力の補正なし、旧自動補正の保持も再確認した。最終時点のserver PIDは51276、補正ワーカーPIDは46296。今回の変更ファイル `composition/typo.rs` と `ai_assist/keyboard_hybrid.rs` の個別 `rustfmt --check --edition 2021` は成功した。

## 制限・保存状況

漢字表記はまだ不安定。前回RPCでは「資料を送ります」、今回GUIと復旧確認では同じ読みのかな表記へフォールバックした。読みの修復成功を漢字表記の安定成功と扱わない。`sshitanokaigi` の取りこぼしも残る。復旧直後の呼出しでは従来経路が使われ得る。実際のタイポ発生率を反映した長期の誤提案率・正答率は未測定。GUIの画面取得を含む時間から表示遅延のp95を推定しない。

今回のアシスト並列RPCは初回記録で106〜224ms、後日の復旧2回目は50〜296msだった（選択例10件、全体の性能分布ではない）。候補ウィンドウが検証アプリの取得画像の左側で一部切れる制限があるが、採用後の本文と確定Valueは確認した。

VM用 `python windows/scripts/vm.py --help` はWindowsホストで `ModuleNotFoundError: fcntl` のため実施不可。ホスト検証で代替し、VMディスクは変更していない。`node windows/scripts/sync-version.mjs --check-release` は既存のtauri.conf.json版情報不整合で失敗。最新 `cargo fmt --manifest-path windows/Cargo.toml --all --check` は今回のtypo.rs以外の既存変更 `composition.rs` の整形差分1箇所で失敗した。無関係な変更を整形し直していない。`git diff --check --ignore-submodules` は成功。

ブランチは `feature/windows-color-themes`。既存Windows/Android変更を保持し、コミット・push・インストーラー作成は未実施。適用は増分試験ビルドであり配布インストーラーとの同一性は主張しない。既存アプリは新しいTSF DLLを読むため開き直す必要がある。

本タスクの最終client適用ログは `artifacts/logs/keyboard-ime-build-trial-coalesced.log`（2026-10-27 23:31 JST）。継続時点の設置済みtrial-deployment.jsonは2026-10-28 00:41:38 JSTの増分適用を記録していた。版識別は `0.1.0-batao.15.dev.1792739943.g140bb742`、validation、installerGeneration 2で、build-info内の旧ブランチ名は現在の作業ブランチを表さない。専用ワーカーの設置済みSHA256は `1fcdb22e9d429e3bbf766ad5e692f6ea14b4384f14053c2f0ec669b450e12df2`。ソース・設置物の照合にはtrial-build-state.jsonの各コンポーネント指紋を使う。
