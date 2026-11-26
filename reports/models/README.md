# モデル実験・比較レポート (`reports/models/`)

ZeroCon Model の学習、蒸留、量子化、CPU/GPU 推論比較などの実験レポート群です。現行配布モデルの正本仕様は [docs/model-assets.md](../../docs/model-assets.md) を参照してください。

## Nano v4 / v4.1・プラットフォーム比較

- [nano-v4-platform-model-switch.md](nano-v4-platform-model-switch.md): Nano v4 への通常変換モデル統一記録（現行は Nano v4.1 へ更新）
- [nano-standard-v4-cpu-comparison.md](nano-standard-v4-cpu-comparison.md): Nano v4 と Standard v4 の CPU 推論性能・精度比較
- [zerocon-v41-q5km-comparison.md](zerocon-v41-q5km-comparison.md): v4.1 Q5_K_M 作成と Nano / Standard 比較（Q5_K_M 不採用）
- [common-candidates-840-10models-20261123.md](common-candidates-840-10models-20261123.md): 共通候補生成840問×10モデルの実行時間×正答率比較
- [common-candidates-840-18models-20261123.md](common-candidates-840-18models-20261123.md): 同上18モデル（v3/v4追加）の実行時間×正答率比較。v4.2更新後と最新エンジン（18:14/18:15ビルド）での再測も含む
- [common-model-scope-840-18models-20261123.md](common-model-scope-840-18models-20261123.md): モデル推論のみ（N-bestなし）の840問×18モデル比較
- [zerocon_v4_capability_r1_report.md](zerocon_v4_capability_r1_report.md): Standard v4 / Nano v4 変換基礎能力向上（`cap-r1`）実験レポート
- [zerocon_model_standard_v4_experiment_report.md](zerocon_model_standard_v4_experiment_report.md): Standard v4 の学習・評価レポート
- [weight-space-programming-zerocon-v4-experiment.md](weight-space-programming-zerocon-v4-experiment.md): 重み空間プログラミング実験の記録
- [upstream-merge-20261106.md](upstream-merge-20261106.md): llama.cpp API 更新（7d516e2 / b12125）対応追記

## Standard-QWERTY 3タスク統合研究（R1〜R7）

- [zerocon-v4-unified-ime-build-plan-20261029.md](zerocon-v4-unified-ime-build-plan-20261029.md): v4 派生3機能モデルの構築計画
- [zerocon-standard-qwerty-unified-20261029.md](zerocon-standard-qwerty-unified-20261029.md): Standard-QWERTY 統合モデルの試作
- [zerocon-standard-qwerty-unified-improvement-plan-20261029.md](zerocon-standard-qwerty-unified-improvement-plan-20261029.md): Standard-QWERTY 統合モデルの改善案
- [zerocon-standard-qwerty-unified-r6-20261029.md](zerocon-standard-qwerty-unified-r6-20261029.md): Standard-QWERTY 統合モデル R6 の改善検証
- [zerocon-standard-qwerty-unified-next-training-plan-20261029.md](zerocon-standard-qwerty-unified-next-training-plan-20261029.md): R6d 以降の学習計画
- [zerocon-standard-qwerty-unified-r7-20261030.md](zerocon-standard-qwerty-unified-r7-20261030.md): Standard-QWERTY R7 実行記録

## ZeroCon-Origin 研究系

- [zerocon-origin-build-plan-20261029.md](zerocon-origin-build-plan-20261029.md): ZeroCon-Origin 構築計画
- [zerocon-origin-t0-20261029.md](zerocon-origin-t0-20261029.md): T0 接続試作の記録
- [zerocon-origin-pilot-20261029.md](zerocon-origin-pilot-20261029.md): 初期 pilot 記録
- [zerocon-origin-n33-50m-20261029.md](zerocon-origin-n33-50m-20261029.md): N33 50M 検証
- [zerocon-origin-s68-spans-20261029.md](zerocon-origin-s68-spans-20261029.md): S68 途中診断・短語句実験
- [zerocon-origin-operating-curriculum-20261029.md](zerocon-origin-operating-curriculum-20261029.md): 補正スコア・継続経路・S68 記録
- [zerocon-origin-t2-final-analysis-20261029.md](zerocon-origin-t2-final-analysis-20261029.md): T2 最終解析

## 初期〜v3 実験レポート（歴史的記録）

- [zerocon_model_nano_v3_experiment_report.md](zerocon_model_nano_v3_experiment_report.md): Nano v3 / v4 系実験レポート
- [zerocon_model_standard_v3_experiment_report.md](zerocon_model_standard_v3_experiment_report.md): Standard v3 実験レポート
- [zerocon_model_standard_v3_plan.md](zerocon_model_standard_v3_plan.md): Standard v3 実験計画書
- [zerocon_model_nano_v2_experiment_report.md](zerocon_model_nano_v2_experiment_report.md): Nano v2 実験レポート
- [ZeroConvert_Experiment_Report.md](ZeroConvert_Experiment_Report.md): 初期モデル開発（汎用LLM〜zenz-v3.2量子化）の総合実験レポート
