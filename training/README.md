# モデル学習

## v4派生・Standard-QWERTYの3タスク統合

`train_v4_unified.py`は既存Standard v4から、かな変換・読み修復・先読みを共通重みへ追加学習する。
append-only語彙、データ監査、凍結v4教師、checkpoint、GGUF exportと整数IDでの評価は
[v4統合モデル学習手順](../docs/v4-unified-training.md)を参照。既存IME・モデル・配布設定は更新しない。

## ZeroCon-Origin（ランダム初期化・タグ統合）

`train_origin.py`は自前SentencePiece語彙と小型Denseモデルを作り、変換・読み修復・先読みを
同じ重みで学習するCE基準実装。データ来歴・分割・EOSを検査し、既存IMEを変更しない。
データ形式、依存環境、学習・再開・GGUF/CPU比較は[Origin学習手順](../docs/origin-training.md)、
極小モデルの実測と未達事項は[接続試作の記録](../reports/models/zerocon-origin-t0-20261029.md)を参照。

ルートで `python scripts/run.py training/<script>.py [引数]` を実行する。例: `python scripts/run.py training/train_nano_v2.py --help`。

学習データの生成コードは `data/`、LoRA・マージ済み重み・チェックポイントは各スクリプトの出力設定に従う。既存の出力は `artifacts/checkpoints/` に保持し、Gitから除外する。`export_*.py` はLoRAのマージであり、GGUF化・量子化は `build_*.py` 等の別処理。

依存環境とモデル配置は [セットアップ](../docs/setup.md) を参照。

## 学習データの分離

追加学習の前に [データ分離手順](../docs/dataset-isolation.md) に従って `python scripts/run.py data/build_separated_datasets.py` を実行する。4本の `train_*.py` は分離済みデータを既定入力とし、独自パスを指定した場合も評価・検証への混入をモデル読み込み前に検査する。使用データのハッシュは学習出力の `dataset-provenance.json` に保存する。既存重みは自動更新しない。

`train_typo_corrector.py --seed 42` はTrainerだけでなくLoRA初期化前から乱数seedを固定する。初期化前のseed固定がなかった過去の学習と、重みのバイト一致を保証するものではない。

`run_corrector_ablation.py` は小型／大型×語句の多様化×読み補正分離の8条件を既存の学習・exportコードで順次実行する。`--phase train` と `--phase evaluate` を分け、学習中の負荷をCPU遅延比較へ混ぜない。データ生成・保存先・限界は [実験記録](../reports/typo-assist/corrector-ablation-20261027.md) を参照。実IMEへの適用は行わない。

## Corrector v4（Nano v4からの補正専用学習）

`train_corrector_v4.py` は、保存済みNano v4のHF重みから、最新版zenz v3.2 xsmall correctorと同じ
LoRA r16/alpha32・表記直接生成契約で補正専用モデルを作る。正解全文とEOSを保持する既存encoderを再利用し、
FP32の親重みを凍結したままBF16 autocastで学習する。通常入力とタイポを各元グループ最大1件ずつ選んだ
固定検証集合の生成完全一致率を等重みで評価し、checkpointを選ぶ。合成行が継承した`is_clean`は採点に使わない。

`build_corrector_v4_dataset.py` は従来のtrain/val分割を保持し、新しい評価セットと重なる元グループを除外する。
検証からはNano v4の既知の追加学習・検証データに触れた元グループも除く。
別タスクの制御記号が混在する教師行は、正解を推測して書き換えずに除外する。元のzenz事前学習との非重複は保証しない。
`build_corrector_v4.py` は親とデータのハッシュを照合し、マージ・F16 export・学習行だけによるimatrix・
Q4/Q6/Q8/IQ4量子化を行う。検証比較と固定300/35問のWindows補正ポリシー比較は別スクリプトで行う。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py data/build_corrector_v4_dataset.py --out artifacts/datasets/corrector-v4-surface-20261030
.\.venv_train\Scripts\python.exe scripts/run.py training/test_corrector_v4.py
.\.venv_train\Scripts\python.exe scripts/run.py training/train_corrector_v4.py --run-name <新しい名前> --dataset artifacts/datasets/corrector-v4-surface-20261030
.\.venv_train\Scripts\python.exe scripts/run.py training/build_corrector_v4.py --run-name <同じ名前>
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/evaluate_corrector_v4_validation.py --run-name <同じ名前> --out artifacts/results/<同じ名前>-validation.json
cargo build --offline --manifest-path engine/rust/Cargo.toml --target-dir artifacts/build/corrector-v4-20261030 --example evaluate_corrector
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/run_corrector_v4_comparison.py --run-name <同じ名前> --validation artifacts/results/<同じ名前>-validation.json --out-dir artifacts/results/<同じ名前>-comparison
```

既存のdataset・checkpoint・GGUF・評価結果は上書きしない。既に生成済みのdatasetは最初の生成を省略して使う。
推論評価にはlocalhost通信を許可した環境が必要。専用ポート8460/8470/8471の推論サーバーだけを起動・停止する。
モデルカタログ・設定・実IMEは自動更新しない。実験条件と結果は
[Corrector v4作成記録](../reports/typo-assist/corrector-v4-nano-20261030.md)を参照。

`train_corrector_v4_advanced.py`は、既存corrector v4を初期重みとし、補正用Small教師・
Target-Corrected KL・adapter無効時の自己アンカー・誤り位置の重み付け・EOS margin・
層別学習率をrank64 DoRA（AttentionとMLP）へ組み合わせる。正常入力の正しいコピーは罰しない。
教師の既知学習行との検証重複、語彙ID、勾配の分離を確認し、独自損失の勾配累積スケールを明示する。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/test_corrector_v4_advanced.py
.\.venv_train\Scripts\python.exe scripts/run.py training/train_corrector_v4_advanced.py --run-name <新しい名前>
.\.venv_train\Scripts\python.exe scripts/run.py training/build_corrector_v4.py --run-name <同じ名前>
```

評価層・正常入力・自動適用の分母は[補正評価契約](../docs/corrector-evaluation.md)、
通常v4からの移植と測定結果は[追加学習・監査記録](../reports/typo-assist/corrector-v4-advanced-and-benchmark-audit-20261030.md)を参照。

## QWERTY専用Corrector v4

`train_qwerty_corrector.py`は、`build_qwerty_corrector.py`で新しく生成した物理キー編集をWindows native DLLへ
通した観測を学習する。フリック合成誤りは混ぜない。rank32 DoRA、層別学習率、EOS margin、正常例の再生を用い、
検証の実ポリシーF0.5と正常/待機/誤自動適用の上限でcheckpointを選ぶ。
`--clean-anchor`はadapter無効の親が正解へ同意する正常/待機tokenだけにKLを掛ける。
追加正常行とvalidation canaryは`data/add_qwerty_preservation.py`で元グループを保って作る。

`blend_qwerty_corrector.py`は同じ語彙・config・形状の既存correctorとQWERTY学習重みをFP32で補間し、
trainだけのimatrixによるQ4量子化と固定validationで係数を選ぶ。
`package_qwerty_corrector.py`は選択とlive/replay一致の記録を照合して研究用候補を保存する。
現行モデルストアや実IMEは更新しない。rawキーはデータの由来であり、推論prompt契約は従来と同じ。

結果・再現コマンド・旧回帰の退行は[QWERTY専用corrector記録](../reports/typo-assist/corrector-qwerty-v4-20261030.md)、
指標と限界は[補正評価契約](../docs/corrector-evaluation.md)を参照。
単体確認は`python scripts/run.py training/test_qwerty_corrector.py`。

`--input-contract raw-state`は生の観測キー・編集状態・カーソル位置を含める研究契約。
対照の`kana`と同じデータ・学習条件で比較し、imatrixにも同じformatterを使う。
専用promptが必要なので既存モデルへ単純置換しない。追加の同条件学習・状態/物理キー候補検査と
新作文章への汎化結果は[キー列・編集状態の比較](../reports/typo-assist/corrector-qwerty-state-and-channel-20261030.md)を参照。

`train_qwerty_local.py`は単一corrector本体の比較として、同じNano v4・観測入力・学習行で全文表記と
`K/W/E:before:after`の局所指示を学習する。`build_qwerty_local.py`はモードに合ったtrain校正でF16/Q4を生成する。
局所指示は一意な観測キー部分への1編集だけを受理し、推論時の別の読み採点モデルを必要としない。
局所方式の不正指示と表記/遅延の比較、データの同一性監査は
[全文生成と局所修復の記録](../reports/typo-assist/corrector-qwerty-full-vs-local-20261030.md)を参照。

局所方式の損失比較は`--repair-weight 3`、`--copy-margin-weight 0.2 --copy-margin 2`で切り替える。
正解局所指示と自己コピー指示の差分CE、同じtoken接頭辞からの次token対照を学習専用で加える。
K/Wにはコピー負例を作らず、推論契約・モデル形状は変えない。結果と採用不可の理由は
[修復損失の比較](../reports/typo-assist/corrector-qwerty-repair-loss-20261030.md)を参照。

## Standard v4（Nano v4の知見を反映）

`train_standard_v4.py` は保存済みStandard v3から、凍結Nano v4教師・Standard v3自己アンカー・12層rank-64 DoRAで追加学習する。データ分離とtokenizer整合を検査し、検証損失でepochを選ぶ。既存成果物を保護するため、再実行時は新しい `--run-name` を指定する。`build_standard_v4_quants.py` はその学習入力のハッシュを照合し、学習行だけで校正してQ8/IQ4を生成する。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/test_standard_v4.py
.\.venv_train\Scripts\python.exe scripts/run.py training/train_standard_v4.py --run-name standard-v4-nano-transfer
.\.venv_train\Scripts\python.exe scripts/run.py training/build_standard_v4_quants.py --run-name standard-v4-nano-transfer
```

評価コマンド・成果物・数字の退行を含む比較は [Standard v4実験記録](../reports/models/zerocon_model_standard_v4_experiment_report.md) を参照。これらのスクリプトは配布カタログや実IMEを更新しない。

## v4統合モデルの表記修復研究

compact codec、表記／読みの対照、生成validationによる選択、同じRust補正ポリシーでの比較は
[再現手順](../docs/v4-unified-training.md)と[R6検証記録](../reports/models/zerocon-standard-qwerty-unified-r6-20261029.md)を参照。
`train_v4_repair_comparison.py`は診断用の補正専用モデル、`train_v4_unified.py`は固定教師を使う統合モデル。
表記を返すrepairは従来の読み修復契約と互換ではなく、研究CLIと整数ID codecを使う。実IMEへ自動適用しない。

## Standard v4 QWERTY候補順位付け

`train_qwerty_ranker.py`は正解読み・元入力・誤候補を比較し、正常入力では元入力を選ぶ学習。
実打鍵の収集、合成イベント回帰と実打鍵の区別、学習、GGUF出力、validationでの閾値固定とtestの実行は
[候補順位付け手順](../docs/qwerty-ranking-corrector.md)を参照。
`export_qwerty_ranker.py`は完成済みadapterだけをマージし、F16/Q8を研究用に出力する。
この重みは候補の読み尤度を採点する契約であり、既存correctorの自由生成ポートへそのまま置き換えない。
