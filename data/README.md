# データ

学習・蒸留・タイポ生成コードと、追跡可能な小規模の評価データを置く。Windows変換エンジンの辞書選択データは別の `windows/data/` にある。

生成した訓練・検証コーパス（`*train*.jsonl` / `*val*.jsonl`）と中間候補はGitから除外する。データ生成コードはルートから `python scripts/run.py data/<script>.py` で実行する。生成器によってローカルLLMサーバー等を使用するため、入力・出力・接続先を先に確認する。

## 学習・検証・評価の分離

手順・分割規則・既存モデルへの制約は [データ分離手順](../docs/dataset-isolation.md) を参照。

```powershell
python scripts/run.py data/build_separated_datasets.py
python scripts/run.py data/check_dataset_isolation.py
python scripts/run.py data/test_dataset_isolation.py
```

既存の6個の学習・検証JSONLを入力とし、元ファイルを保持して `artifacts/datasets/separated-v1/` に新しいセットを作る。既存の出力先は上書きしない。4つの評価セットとの関連データを除外し、読み・正解文・入力が共通する行をまとめて分割する。訓練スクリプトの既定入力もこのセットへ変更した。

`generate_typo_dataset.py` は評価例を学習用の種として取り込まない。タイポ生成器2本は元の正解読みを保持してグループ分割し、評価由来の行を除外する。ただし別々に生成したコーパスを混ぜるときは、必ず共通ビルダーで再分割する。

`build_lora_dataset.py` は従来の品質フィルターに加えて同じ分離検査を行う。出力先は `artifacts/datasets/lora-cleankeep-v1/` に変更した。再実行時は `--out` で新しいフォルダを指定する。

`build_corrector_dataset.py` は分離済み統合データへ、英字が残る打鍵ミス・正常読み・表記保持例を追加し、評価との衝突を除外して再分割する。出力は `artifacts/datasets/corrector-v2-20261027/`。Pythonのローマ字表による合成データであり、実IMEでの打鍵再現評価とは区別する。

`build_corrector_ablation.py` は元の語句と既存逆引き辞書の語句を同じ方法で増補し、学習行数・誤り種別を揃えた多様化比較用セットを生成する。表記生成／読み生成の入力は同一で、教師出力だけを変更する。全条件の検証グループを共通で予約し、評価由来の語句を除外する。実行例・比較の範囲は [実験記録](../reports/corrector-ablation-20261027.md) を参照。

`build_qwerty_corrector.py`は正規読みから新しいQWERTYキー誤りを作り、`native_qwerty.py`経由でWindowsの
ComposingText DLLへ1キーずつ通す。元グループを分けてから編集し、rawキー・物理編集・native観測・修復/保持/待機を保存する。
古いかな誤りを逆ローマ字化して実打鍵と呼ばず、フリック誤りを混ぜない。
`add_qwerty_preservation.py`は同じ元グループの正常行だけを再生に加え、raw不明を明記する。
実ユーザーの打鍵分布や未使用の外部テストとは区別する。ハッシュと結果は
[QWERTY専用corrector記録](../reports/typo-assist/corrector-qwerty-v4-20261030.md)を参照。
