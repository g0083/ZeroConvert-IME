# 評価コード

Standard v4のQWERTY候補順位付け、実打鍵の記録、policy固定後の評価は
[QWERTY候補順位付け手順](../docs/qwerty-ranking-corrector.md)を参照。
`research/evaluate_qwerty_ranker.py`はCPU GGUFを採点し、`record_qwerty_trace.py`は専用画面の打鍵だけを記録する。

ルートで `python scripts/run.py benchmarks/<script>.py` を実行する。研究用コードは `benchmarks/research/`。入出力はルートの `data/`、`artifacts/results/`、`artifacts/logs/`、`artifacts/models/` 等を基準とする。

各スクリプトはモデル・ローカル推論サーバー・CUDA等の前提が異なるため、実行前に先頭の設定を読む。ルールの移植版や独立したモデル評価は、実IMEのパイプラインを検証するものではない。詳細は [ファイル地図](../docs/file-map.md) を参照。

QWERTY専用correctorは`research/qwerty_corrector_policy.py`で独立した通常候補と固定validation/test fixtureを作り、
`select_qwerty_corrector.py`で量子化を選び、`benchmark_qwerty_corrector.py`でlive HTTP＋Rustポリシーを比較する。
`analyze_qwerty_corrector.py`は元グループmacro値・paired bootstrap・読み一致診断を集計する。
フリック問題や旧QWERTYラベル付き問題をnative打鍵集合と合算しない。
prompt完全一致の実応答replayは正解注入のoracleと区別する。
再現手順と限界は[QWERTY専用corrector記録](../reports/typo-assist/corrector-qwerty-v4-20261030.md)を参照。

`research/compare_qwerty_state.py`は読み入力とraw-state入力を同条件で学習したモデルに、
状態保護・nativeで検証する物理キー検査を同じように適用する。`analyze_qwerty_state.py`は元グループbootstrapと
明示的な正解注入診断を保存する。結果と新作20文章への汎化の限界は
[キー列・編集状態の比較](../reports/typo-assist/corrector-qwerty-state-and-channel-20261030.md)を参照。

`research/benchmark_qwerty_reading_search.py`は、実キー編集の読み候補をNano v4のネイティブCPU採点で比較する。
`evaluate_qwerty_reading_surface.py`は既存候補がないケースへ通常モデルによる表記候補を追加し、validationだけで校正する。
`run_qwerty_reading_assist.py`は正解ラベルを受け取らない追加経路の研究CLI。
表記と読みの指標、追加処理の遅延、非適用範囲は[読み探索の記録](../reports/typo-assist/corrector-qwerty-reading-search-20261030.md)を参照。

`research/evaluate_qwerty_local.py`は、単一correctorを全文/局所方式で同条件学習したGGUFを比較する。
raw読み指標、共通の入力途中保護、物理キー証拠を要求する表記指標を別々に保存する。
`analyze_qwerty_local.py`は実入力/教師の同一性、不正指示、グループ単位の差を監査する。
`probe_qwerty_local_export.py`は選択後のFP32/F16/Q4診断であり、testによる再選択には使わない。
結果は[全文生成と局所修復の記録](../reports/typo-assist/corrector-qwerty-full-vs-local-20261030.md)を参照。

`evaluate_qwerty_local.py --additional-local-run <run>`を繰り返すと、同じfixtureで損失等の追加条件を比較できる。
`analyze_qwerty_repair_loss.py`はdata/base/量子化校正の同一性、自己コピーと不正編集、元グループ単位の差を監査する。
修復重み付けとコピー対照の結果は[修復損失の比較](../reports/typo-assist/corrector-qwerty-repair-loss-20261030.md)を参照。

追加学習モデルを比較する場合は [学習・評価データ分離手順](../docs/dataset-isolation.md) を適用し、checkpointの `dataset-provenance.json` と評価データのハッシュを確認する。既存重み・過去の点数は今回の分離では更新されない。現在の固定問題は開発評価用であり、未使用の最終テストセットとは区別する。

`research/benchmark_corrector_pipeline.py` は `engine/rust/examples/evaluate_corrector.rs` を使って現行Windows補正関数を直接評価する。`--corrector`、`--normal`、新しい `--out` を指定し、専用ポートの所有モデルプロセスを終了まで管理する。評価器は `cargo build --manifest-path engine/rust/Cargo.toml --example evaluate_corrector` でビルドする。実IME・個人設定・採用履歴を変更しない。基準の表記は通常モデルの直接推論であり、Swift辞書・TSF・表示操作は対象外。正解表記は採点にだけ使い、基準候補へ渡さない。クラッシュ・通信失敗・誤提案・誤自動適用を区別して保存する。

`windows-rpc/` は稼働中のWindows IMEサーバーへ専用クライアントで接続する確認用ツール。`cargo run --manifest-path benchmarks/windows-rpc/Cargo.toml -- <新しい結果JSONパス>` で実行する。compositionと文脈をテスト用に操作するため、入力を確定してから使う。確定学習は行わない。TSF・画面上のTab採用を代替するものではない。今回の改善結果・適用状態・制約は [2026-10-27の改善記録](../reports/typo-improvement-20261027.md) を参照。

同じ追加学習データ・条件で約18.5MB版と約64.3MB版を比較した結果は [タイポ補正モデル大型化の比較](../reports/corrector-size-comparison-20261027.md) を参照。開発評価300問の再測定、補助35問、誤提案とCPU遅延を記録し、実IMEへの適用とは区別している。

語句の多様化・読み補正分離を両サイズで比較する手順は [8条件の実験記録](../reports/corrector-ablation-20261027.md) を参照。`benchmark_corrector_pipeline.py --reading-stage` は実験用に「かなの修正→固定の通常変換」を仲介し、両段の時間を計上する。`analyze_corrector_ablation.py` は16結果ファイルのモデル・実行器の同一性を確認し、条件間の正答増減を集計する。製品の補正方式を切り替える設定ではない。

ローマ字の隣接キー誤りを「打鍵候補＋辞書＋読み言語モデル」で補正する試作は [ハイブリッド補正の検証](../reports/keyboard-hybrid-20261027.md) を参照。`research/benchmark_keyboard_hybrid.py` はデータ分離・候補生成・HF採点、`benchmark_keyboard_native.py` はQ4 CPU採点、`analyze_keyboard_hybrid.py` は開発用のしきい値固定と別区分の評価を行う。ネイティブ評価器は `build_keyboard_native.py --output <新しいフォルダー>` で既存b4846 DLLと一致するヘッダーからビルドする。候補生成条件・モデル・tokenizer・実行器をhashで照合し、未採点データや別条件の評価を拒否する。これは原文ローマ字由来の読み復元評価で、最終漢字表記や実IMEの動作を保証しない。
