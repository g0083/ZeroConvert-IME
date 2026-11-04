# Standard v4 QWERTY候補順位付けの開発手順

2026-11-03。実験結果は`reports/typo-assist/`、データ・重み・実行ログは`artifacts/`へ保存する。

## 処理と境界

観測した生キー列から、隣接置換・脱落・挿入（重複を含む）・転置1回で説明できる読み候補を作る。
元入力を必ず先頭に残し、最大24読みへ絞る。近似ローマ字変換と辞書で前処理し、残した各候補のキー列を
Windowsの実ComposingText DLLで再生する。近似前処理による取りこぼしと候補上限があるため、完全探索ではない。

Standard v4は観測キー・読み・直前文脈・編集状態を条件に、各候補の読みとEOSの平均対数尤度を比較する。
元入力との点差がvalidationで決めた閾値を超えたときだけ提案する。自己申告のconfidenceは使わない。
学習時は正解、元入力、高辞書スコアの誤候補など4候補を同時に比較し、正常入力では元入力を正解とする。
候補に正解がない修復例は学習から除外するが、評価の分母には残す。正解を候補へ注入しない。

削除直後・未確定子音・途中カーソル・未対応の英数記号は提案を控える。これらの保護とモデルの保持能力は
別に数える。読み順位付けの正解は表記の正解を保証しない。表記化には通常変換モデルを使い、読み整合を検証する。

## データの扱い

- 旧合成データは学習と開発用の回帰評価。元文章・読み・表記・グループの衝突を検査する。
- 新しい自然文によるordered-event評価も合成打鍵。実打鍵や独立した人手判定とは呼ばない。
- `dev`は開発用、`test`はモデル・閾値を固定した後に1回評価する。結果を見た後は、そのtestも以後の回帰用として扱う。
- 実打鍵は専用画面で収集する。通常のアプリや他のウィンドウへのグローバルフックは行わない。
- 記録者が意図した表記・読み・修復/保持/待機を確認する。独立判定がない例はその事実を残す。
- 実打鍵の保持・修復割合は収集方法に依存する。人工的に誤りを多くした集合から実使用頻度を推定しない。

ルートで次を実行する。各出力には新しい名前を指定する。

```powershell
.venv_train/Scripts/python.exe scripts/run.py benchmarks/research/record_qwerty_trace.py --out artifacts/datasets/qwerty-human-recordings-20261103/recordings.jsonl
.venv_train/Scripts/python.exe scripts/run.py data/import_qwerty_recordings.py --recordings artifacts/datasets/qwerty-human-recordings-20261103/recordings.jsonl --training-dataset artifacts/datasets/standard-qwerty-ranker-20261103-r1 --out artifacts/datasets/qwerty-human-eval-20261103-r1
```

記録画面では英数入力へ切り替える。文脈、意図した表記と読みを入力し、記録開始後に中央欄へ打鍵する。
Backspaceと左右矢印に対応する。貼り付け・変換確定・外部アプリ操作は対象外。
入力途中を保存する場合は`wait`、正常入力や意図した削除後の状態は`keep`を選ぶ。
イベント時刻は記録ハンドラの観測時刻で、ハードウェアやTSFの遅延測定ではない。
同じ元文章の記録をdev/testへ分けない。import時に記録をDLLで再生し、保存した状態が一致することを検査する。
dev/test双方の元グループが揃わない場合や既存学習と衝突する場合はimportを拒否する。

## 学習と固定評価

```powershell
.venv_train/Scripts/python.exe scripts/run.py data/build_qwerty_ranker.py --out artifacts/datasets/<dataset>
.venv_train/Scripts/python.exe scripts/run.py data/build_qwerty_ranker_eval.py --out artifacts/datasets/<prospective>
.venv_train/Scripts/python.exe scripts/run.py training/train_qwerty_ranker.py --dataset artifacts/datasets/<dataset> --run-name <run>
.venv_train/Scripts/python.exe scripts/run.py training/export_qwerty_ranker.py --run-name <run>
.venv_train/Scripts/python.exe scripts/run.py benchmarks/research/build_qwerty_ranker_native.py --out artifacts/build/qwerty-ranker-20261103-r1
.venv_train/Scripts/python.exe scripts/run.py benchmarks/research/evaluate_qwerty_ranker.py --model artifacts/models/<run>-q8.gguf --tokenizer artifacts/checkpoints/<run>-merged --dataset artifacts/datasets/<dataset> --prospective artifacts/datasets/<prospective> --out artifacts/results/<development>
.venv_train/Scripts/python.exe scripts/run.py benchmarks/research/evaluate_qwerty_ranker.py --model artifacts/models/<run>-q8.gguf --tokenizer artifacts/checkpoints/<run>-merged --dataset artifacts/datasets/<dataset> --prospective artifacts/datasets/<prospective> --lock artifacts/results/<development>/policy-lock.json --out artifacts/results/<heldout>
```

現行のCPU採点器はb4846 runtimeと固定headerを使う研究用。production採点実装のスナップショットに対し、
protocolの長さ上限のみ128から256へ広げて別exeを生成する。productionソースや配布exeは変更しない。
1要求16候補まで、合計4096token以内で採点する。モデル、tokenizer、実行器、契約とデータのhashをlockに保存する。

## 手動候補の確認と実IMEへの移行

`run_qwerty_ranker_assist.py`は固定済みpolicyから候補を選び、Standard v4で表記化する独立CLI。
`--baseline-surface`で渡した通常候補は残し、新しい候補は常に`auto_apply=false`で返す。
通常候補生成、TSF、候補UI、自動補正、実アプリへの入力を実装・検証したものではない。

実IMEへ移す前に、実打鍵での誤介入、表記の妥当性、候補生成＋採点＋表記化の遅延を確認する。
研究用最大24候補の全文採点を毎打鍵へ直接追加しない。候補削減、prefix共有、非同期実行、古いrevisionの
結果取消、入力状態照合、通常候補の保持を製品経路の回帰テストと一緒に検証する。
実打鍵が未収集、あるいは遅延・保持に退行がある段階ではモデルの実IME差し替えを行わない。
