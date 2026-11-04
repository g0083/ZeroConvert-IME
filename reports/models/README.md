# モデル実験・比較レポート (`reports/models/`)

ZeroCon Model の学習、蒸留、量子化、CPU/GPU推論比較などの実験レポート群です。

## 主なレポート

- [Standard-QWERTY R7実行記録](zerocon-standard-qwerty-unified-r7-20261030.md): 混在文字列保持データ、共有更新と更新干渉制約の比較
- [R6d以降の学習計画](zerocon-standard-qwerty-unified-next-training-plan-20261029.md): 通常変換の回復、更新の干渉対策、補正候補選択、先読みとNanoへの展開
- [v4派生3機能モデルの構築計画](zerocon-v4-unified-ime-build-plan-20261029.md): Standard-QWERTY／Nano-QWERTY／Nano-Flickを必須とする新計画
- [Standard-QWERTY統合モデルの試作](zerocon-standard-qwerty-unified-20261029.md): 親重み・codec・corrector継承・実生成の結果と未達事項
- [Standard-QWERTY統合モデルの改善案](zerocon-standard-qwerty-unified-improvement-plan-20261029.md): 現行v3.2 correctorとの比較、入出力・候補選択・段階統合の実験順序
- [Standard-QWERTY統合モデルR6の改善検証](zerocon-standard-qwerty-unified-r6-20261029.md): R6dの学習・Q8書き出し、同条件の補正改善、通常変換の退行と採用保留
- `zerocon_model_standard_v4_experiment_report.md`: standard-v4 の学習・評価レポート
- `nano-standard-v4-cpu-comparison.md`: nano-v4 と standard-v4 のCPU推論性能・精度比較
- `zerocon_model_nano_v3_experiment_report.md`: nano-v3 の実験レポート
- `zerocon_model_standard_v3_experiment_report.md`: standard-v3 の実験レポート
- `zerocon_model_standard_v3_plan.md`: standard-v3 の実験計画書
- `zerocon_model_nano_v2_experiment_report.md`: nano-v2 の実験レポート
- `ZeroConvert_Experiment_Report.md`: 初期モデル開発の総合実験レポート
