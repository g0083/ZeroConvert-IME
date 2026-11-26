# モデル性能比較レポート (`reports/models/`)

ZeroCon Model の性能比較（共通候補生成・モデル推論のみ・CPU実行）の公開用レポートです。現行配布モデルの正本仕様は [docs/model-assets.md](../../docs/model-assets.md) を参照してください。

## 公開原則

このディレクトリのレポートは**性能の結果だけ**を掲載します（[docs/documentation-guide.md §5](../../docs/documentation-guide.md#5-公開面の原則)）。詳細は（内部）に保存します。

## 840問ベンチマーク

- [common-candidates-840-18models-20261123.md](common-candidates-840-18models-20261123.md): 共通候補生成840問×18モデルの実行時間×正答率比較（v4.2 更新後と最新エンジンでの再測を含む）
- [common-candidates-840-10models-20261123.md](common-candidates-840-10models-20261123.md): 同 10モデルの先行スナップショット
- [common-model-scope-840-18models-20261123.md](common-model-scope-840-18models-20261123.md): モデル推論のみ（N-bestなし）の840問×18モデル比較

## CPU比較・エンジン

- [nano-standard-v4-cpu-comparison.md](nano-standard-v4-cpu-comparison.md): Nano v4 と Standard v4 の CPU 推論性能・精度比較
- [upstream-merge-20261106.md](upstream-merge-20261106.md): llama.cpp API 更新対応の追記
