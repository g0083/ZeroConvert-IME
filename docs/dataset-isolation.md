# 学習・検証・評価データの分離

2026-10-27実装。対象はプロジェクト内の追加学習データ。既存重みの学習済み内容やベースモデルの事前学習は変更しない。

## 作成と検査

ルートから実行する。

```powershell
python scripts/run.py data/build_separated_datasets.py
python scripts/run.py data/check_dataset_isolation.py
```

入力は `data/typo_dataset_{train,val}.jsonl`、`data/typo_dataset_{train,val}_30k.jsonl`、`data/distilled_{train,val}.jsonl` の6ファイル。欠落・不正JSON・prompt/completion欠落はエラーとする。元ファイル、既存の `lora_train.jsonl` / `lora_val.jsonl`、評価問題、モデル重みを上書きしない。

出力はGit対象外の `artifacts/datasets/separated-v1/`。既存フォルダがあれば停止する。新しい入力から作り直す場合は `--out artifacts/datasets/<新しい名前>` を指定する。

| サブフォルダ | 内容 | 初回の学習行数 | 初回の検証行数 |
|---|---|---:|---:|
| typo | 旧6k・30kタイポコーパスを合わせて再分割 | 10,063 | 1,621 |
| distilled | 蒸留データを再分割 | 3,397 | 343 |
| lora | 上記を統合した再分割 | 13,460 | 1,964 |

各フォルダに `train.jsonl` と `val.jsonl` を作る。統合版は分離用の共通コーパスであり、旧CLEANKEEPの品質フィルターを適用したものではない。既存の品質フィルターも使う場合は次を実行する。

```powershell
python scripts/run.py data/build_lora_dataset.py
```

こちらの出力は `artifacts/datasets/lora-cleankeep-v1/` の `lora_train.jsonl` / `lora_val.jsonl`。初回確認では学習12,722行・検証1,834行。再実行時は新しい `--out` を指定する。

## 分割規則

`data/dataset_isolation.py` を共通実装とする。

1. NFKC、カタカナ→ひらがな、空白除去で照合する。文脈が違っても同じ読み・元の正解読み・入力・正解表記を共有する行を同じ連結グループへまとめる。タイポ違いの兄弟も正解読み・表記を介してまとまる。
2. 評価問題の読み・誤入力・正解・許容解・先読みの部分入力・継続・完成文とキーが一致するグループ全体を除外する。`testset_clean` 由来も除外する。
3. 残るグループを固定seedとSHA-256で学習・検証へ分割する。検証の目標はグループ単位で10%のため、行数は厳密な9:1にはならない。
4. グループを決めてから重複行を除去する。重複行に残る元読み情報を先に捨てない。
5. 学習↔検証、学習↔評価、検証↔評価のキー重複が0であることを確認する。空の学習・検証も拒否する。

評価の予約対象は次の4ファイル。UIのかな変換230問は1つ目と内容一致を調査済み。

- `data/kana_kanji_llm_testset_5x.jsonl`（230問）
- `data/typo_benchmark_300.jsonl`（300問）
- `benchmarks/research/typo_testset.jsonl`（35問）
- `benchmarks/research/predictive_testset.jsonl`（30問）

初回は39,830行から評価に関連する9,174行を除外した。正解表記だけが同じ一般語も保守的に除外する。これは意味的類似・言い換え・部分文字列の完全な検出ではない。未登録の外部評価セットや、現行問題を見て調整したことによる開発上の偏りも解消しない。これらの問題セットは開発評価として扱い、最終精度の公表には未使用のテストセットを別途用意する。

## 学習開始時のガードと記録

`train_typo_corrector.py` は `separated-v1/typo/`、`train_nano_v2.py` / `train_nano_v2_gentle.py` / `train_nano_v2_fullft.py` は `separated-v1/distilled/` を既定入力とする。`--train_file` / `--val_file` で指定した独自ファイルにも同じ検査を行い、違反があればモデルのロード前に停止する。

```powershell
python scripts/run.py training/train_typo_corrector.py
python scripts/run.py training/train_nano_v2.py
# CLEANKEEP統合版を使う例（実際の学習を開始するコマンド）
python scripts/run.py training/train_nano_v2.py --train_file artifacts/datasets/lora-cleankeep-v1/lora_train.jsonl --val_file artifacts/datasets/lora-cleankeep-v1/lora_val.jsonl
```

学習出力フォルダへ `dataset-provenance.json` を保存する。学習・検証のパス、行数、SHA-256、評価セットのSHA-256、分離ポリシーと検査結果を記録する。共通ビルダーの `manifest.json` は入力6ファイル・評価4ファイル・出力6ファイルのハッシュ、seed、除外数、グループ数を持つ。確認コマンドは現在の評価・出力ハッシュと分離状態を再検査する。

学習の出力先は空または未作成のフォルダを使う。既存モデルの来歴を上書きしないため、ファイルがある場合は停止する。既定のcheckpoint先が使用済みなら `--output_dir artifacts/checkpoints/<新しい名前>` を指定する。来歴の `training_completed` は開始時falseで、学習とモデル・tokenizer保存の成功後にtrueへ更新する。

既存重みの精度がこれで再評価済みになるわけではない。分離前のデータで追加学習した重みは、今回の検査が通るコーパスからクリーンなベースを使って再学習する必要がある。今回、再学習・推論ベンチマーク・実IME更新は行っていない。

## 検証

```powershell
python scripts/run.py data/test_dataset_isolation.py
python scripts/check_repository.py
```

回帰テストは幅・かな種の正規化、別文脈・変異の連結、評価由来の除外、順序に依存しない分割、旧混入データの拒否、既存出力の保護、生成再現性、学習入力の検査と来歴記録を扱う。依存ライブラリ・GPU・推論サーバーを使わずCIで実行する。元のローカルコーパスがGit対象外のため、CIでは実データ再生成は行わない。
