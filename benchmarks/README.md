# 評価コード

ルートで `python scripts/run.py benchmarks/<script>.py` を実行する。研究用コードは `benchmarks/research/`。入出力はルートの `data/`、`artifacts/results/`、`artifacts/logs/`、`artifacts/models/` 等を基準とする。

各スクリプトはモデル・ローカル推論サーバー・CUDA等の前提が異なるため、実行前に先頭の設定を読む。ルールの移植版や独立したモデル評価は、実IMEのパイプラインを検証するものではない。詳細は [ファイル地図](../docs/file-map.md) を参照。
