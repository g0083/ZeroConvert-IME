# モデル学習

ルートで `python scripts/run.py training/<script>.py [引数]` を実行する。例: `python scripts/run.py training/train_nano_v2.py --help`。

学習データの生成コードは `data/`、LoRA・マージ済み重み・チェックポイントは各スクリプトの出力設定に従う。既存の出力は `artifacts/checkpoints/` に保持し、Gitから除外する。`export_*.py` はLoRAのマージであり、GGUF化・量子化は `build_*.py` 等の別処理。

依存環境とモデル配置は [セットアップ](../docs/setup.md) を参照。
