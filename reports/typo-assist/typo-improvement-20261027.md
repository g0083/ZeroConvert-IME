# タイポ補正改善の記録（2026-10-27）

ユーザー指定: 現在と同程度のサイズ・応答速度を優先する。大型モデルへの切り替えは行っていない。

## 実施内容

- 現在と同じzenz-v3.1-xsmallをベースに、分離済みデータからLoRAを再学習した。
- 英字が残る打鍵ミスを消さずに合成し、正常読み・表記保持の例も追加した。Pythonのローマ字表による合成であり、実IMEで生成した打鍵ログとは区別する。
- 学習時には未確定入力と正解全文・EOSを保持し、長い左文脈から先に切り詰める。従来の末尾切捨てによる不完全な教師出力を避けた。
- 既存の明示補正表の完全一致をモデルより優先する。曖昧な辞書検索は、正常に終了したモデル応答を上書きしない。新しい評価問題の正解を表へ追加していない。
- 通常候補・学習済み補正を保持する。モデル候補は読みを辞書で確認し、原則として1〜2文字の局所的な読み変更だけを提示する。辞書で読みを検証できない候補や、文全体の短縮・書換えは提示しない。
- 未確定の末尾英字子音への補正を抑制する。自動適用条件は緩めていない。
- 漢字・記号・絵文字を含む混在入力をASCIIローマ字デコーダーへ渡さない。修正前300問の途中で実際に起きたUTF-8文字境界panicを修正した。

## 評価結果

実Windowsの補正ソースを直接コンパイルする `engine/rust/examples/evaluate_corrector.rs` で、個人の採用履歴を無効化し、専用ローカル推論サーバーで比較した。通常変換の基準表記はStandardモデルの直接推論で作り、正解を基準候補として渡していない。Swift辞書候補・TSF・画面操作を含む精度ではない。

| 指標 | 修正前 | 最終版 |
|---|---:|---:|
| 300問中、誤入力255件への正しい補正候補 | 11 | 26 |
| 同じ255件への誤った補正候補 | 234 | 57 |
| 正常入力45件への誤提案 | 39 | 1 |
| 基準候補か補正候補に正解がある誤入力 | 17 / 255 | 30 / 255 |
| 誤った自動適用 | 0 | 0 |
| クラッシュしたケース | 1 | 0 |
| 別の35問への正しい補正候補 | 10 | 16 |
| 別の35問への誤った補正候補 | 21 | 3 |

新モデルのみの段階では35問の正しい候補が5件へ減ったため、その状態を最終版にはしていない。既存の完全一致ルールを優先して取りこぼしを回復した。

300問の最終測定は補正処理p50 25.5ms / p95 38.0ms、35問はp50 9.5ms / p95 25.2ms。CPU・4スレッド、温度0、ケースごとに補正キャッシュを消去。バックグラウンド処理の負荷を厳密に統制した性能試験ではなく、実アプリのキー入力→表示時間でもない。

残る制約:

- 255件中の正しい補正候補は26件で、依然として取りこぼしが多い。十分な補正品質に達したとは扱わない。
- 正常入力で「部屋の換気」を「部屋の環境」へ誤提案する例が残った。今回は自動適用にはならなかった。
- 表記の許容解が足りない評価例もある。問題・採点規則を今回の改善に合わせて書き換えていない。
- 現行セットは開発評価用。事前学習・既存ルールと評価問題の独立性まで保証する未使用の最終テストではない。
- 0件の誤自動適用は、このケース集合での結果であり、普遍的な安全性の証明ではない。

## 学習とモデルの来歴

- 作成器: `data/build_corrector_dataset.py`
- 元データ: `artifacts/datasets/separated-v1/lora/{train,val}.jsonl`
- 生成先: `artifacts/datasets/corrector-v2-20261027/`
- 学習40,111行、検証3,081行。生成後に評価と関連する209行を除外し、元読み・正解を共有するグループで再分割した。学習↔検証・学習↔評価・検証↔評価の正規化キー重複は0。
- ベース: キャッシュ内の `Miwa-Keita/zenz-v3.1-xsmall` snapshot `a728931c8b4867a53ca33d1ff3fe7360b0f15cd5`。
- LoRA r16 / alpha32、学習率0.0001、3epoch、batch32、累積2、最大256token。検証損失で選ばれたcheckpointは1881step（eval_loss約1.3026）。
- checkpoint: `artifacts/checkpoints/corrector-v2-20261027-lora/`
- 来歴: 同フォルダの `dataset-provenance.json`、`checkpoint-1881/trainer_state.json`。全モデルの事前学習データの独立性を意味しない。
- マージ済み: `artifacts/checkpoints/corrector-v2-20261027-merged/`
- Q4_K_M: `artifacts/models/corrector-v2-20261027-q4km.gguf`（18,452,288 bytes）
- SHA-256: `d817a1403c9e55e16c57b462fc53db2c7398881e1ce607408e004ea6fea33d39`
- 旧モデル: 18,452,384 bytes、SHA-256 `0641a5be4e7d9f563776275f9904b5cad9875e84e752d146949ead3c5ac7089a`。`.local/model-promotion-20261027/corrector-before.gguf` に退避。

比較結果の詳細はGit対象外の次のファイルに保存した。

- `artifacts/results/corrector-baseline-20261027.json`
- `artifacts/results/corrector-v2-final-300-20261027.json`
- `artifacts/results/corrector-baseline-35-20261027.json`
- `artifacts/results/corrector-v2-explicit-35-20261027.json`

## Windowsへの反映と検証

正本モデルストアへ生成済みモデルを配置し、`windows/deployment/model-catalog.json` のSHA-256・サイズを更新。`Build-Trial.bat` で増分ビルド・試験IMEへの自動適用を実施した。続けてApply-Trialは実行していない。

- 最終差分適用先: `.local/trial-updates/20261027-145723-127`
- 適用manifest SHA-256: `37e9d6f2466bd18d641d245de633917e0e05f6aa0c1db825b33b133b9874bc43`
- 適用前退避: `.local/trial-backups/20261027-145724-806`
- 実行中corrector: `ZeroConvertIME-windows/models/zerocon-model-corrector.gguf`、CPU、ポート18090。先読みは別のStandardモデル・18091。
- 配置先モデルのSHA-256一致を確認し、最終の `Build-Trial.bat -WhatIf` は変更なし。
- 個人設定・登録の変更、既存の採用履歴の消去・置換は行っていない。後述の実IMEでの採用確定テストでは、通常動作による採用学習が記録され得る。

`benchmarks/windows-rpc/` の専用クライアントで、実サーバー・Swift・補正経路を確認した。`okseri`→`おkせり`→補正`おかえり`（自動適用判定true）、正常`okaeri`の補正なし、`otkare`→`お疲れ`、`argatou`→`ありがとう`を確認。各ケースの削除・カーソル移動・取消後の空状態も確認した。確定学習は実行していない。

ログ: `artifacts/results/corrector-windows-rpc-final-20261027.json`。これは実サーバーの通信確認であり、実アプリのTab採用・TSF編集結果とは区別する。

当初のメモ帳確認は別IME・英字モードの選択と、ウィンドウの前面化失敗で停止し、ユーザー操作待ち（blocked）とした。その後、ユーザーから「ひらがな入力に切り替えた」との応答を受けて再開し、以下を実画面で確認した。以後、入力方式の切り替えは行っていない。

| 操作 | 実画面で確認した結果 |
|---|---|
| `okseri` を1打鍵ずつ入力 | `お` → `おk` → `おks` → `おkせ` → `おkせr` → 自動補正した`おかえり`。未確定の末尾`r`では補正ヒントが消えた |
| 自動補正後にEscape | 元の`おkせり`へ復元 |
| 復元後Backspace → Left → Right → Escape | 末尾かな`り`を削除して`おkせ`。矢印操作後も表示文字列を保持し、最後のEscapeで未確定入力を取消。内部カーソル座標の正確さまではこの画面確認から断定しない |
| `tesoto` | `てそと`を保持し、2番目に`テスト（補正）`を表示。このケースは先読みがTab対象だったため、Tabを押さず取消 |
| `ohyo` → Tab | `おひょ→おはよう、Tabで補正`の表示どおり、未確定の`おはよう`へ変更 |
| Tab採用後Escape | 元の読み`おひょ`と通常候補の表示`老ひょ`へ復元。さらにEscapeで取消 |
| 再度`ohyo` → Tab → Enter | 補正を再採用し、`おはよう`を文書へ確定。候補・読みの表示が消えた |
| 正常な`okaeri` | `おかえり`と通常候補を表示し、タイポ補正の矢印・ラベルは出なかった。Escapeで取消 |

観測記録: `artifacts/results/corrector-windows-ui-20261027.json`。スクリーンショットはツール応答で直接確認し、画像ファイルへの書出しは行っていない。検証専用タブは残し、未確定入力がない状態で終了した。実アプリでの採用・確定・復元・取消の残作業は解消した。この少数の画面確認を全入力の正答率や遅延の保証には用いない。

## 実施した検査

- Windows server: `cargo test --manifest-path windows/Cargo.toml -p azookey-server --lib ai_assist:: -- --test-threads=1` — 50成功、モデルendpointが必要な1件除外。
- Windows client: `cargo test --manifest-path windows/Cargo.toml -p azookey-windows --lib engine::composition::typo::tests -- --test-threads=1` — 5成功。Tab採用、Escape復元、候補順、古い結果の破棄等。
- 共有Rust: `cargo test --manifest-path engine/rust/Cargo.toml --target-dir artifacts/build/corrector-fixed --lib -- --test-threads=1` — 55成功、1件除外。
- Python分離・補正データ・教師出力保持テスト — 8＋2＋2成功。
- `python scripts/check_repository.py`、変更Rustのrustfmt、対象差分の `git diff --check` — 成功。
- `scripts/build-android.ps1` — 両ABIの共有ソース・ネイティブバイナリ整合、単体テスト、lint、debug APK確認が成功。`Build-Android.bat` 経由の古いPowerShellはGet-FileHash未解決で失敗したため、同じ公式スクリプトを現在のPowerShellから実行した。端末更新・署名配布は未実施。
- Windows VM手順は `windows/scripts/vm.py` のfcntl importで停止し、このホストでは利用できなかった。今回のWindows検証は実ホスト上で行った。
- `node windows/scripts/sync-version.mjs --check-release` は既存のtauri.conf.json版情報不整合で失敗。リリース用メタデータの同期は本タスクでは行っていない。

インストーラー・commit・pushは未実施。作業ブランチは `feature/windows-color-themes` のまま。既存の無関係な差分を保持している。重み・学習出力・実行環境・評価JSONはGit対象外であり、ソースのGit保存やバックアップの代わりにはならない。
