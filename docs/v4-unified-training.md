# v4派生・Standard-QWERTY統合モデルの学習

Standard v4の保存済みHFから、同じ重みでかな変換・ローマ字の読み修復・suffix先読みを学習する研究系。
計画は[統合モデル構築計画](../reports/models/zerocon-v4-unified-ime-build-plan-20261029.md)。
この手順自体はIME、配布カタログ、ユーザー設定を更新しない。Nano-QWERTY／Nano-Flickは別の必須成果物として残る。

## 契約と制限

- `training/v4_unified_contract.py`が既存6000 IDを保持し、不足するByteLevel文字24個と制御18個を末尾へ追加する。語彙は6042。
- 通常変換はひらがな読みの該当かなだけをカタカナへ変換する。修復は`ctx/raw/pending`だけを観測入力とし、出力をひらがなの読み／keep／abstainにする。正解読みは入力しない。
- 先読みは可視表記を入力し、追加suffixまたはabstainを出力する。pendingがある場合は新提案を控える。モデルによる確定・入力状態更新は行わない。
- 文字列値をJSON文字列として符号化し、欠落はJSON nullと区別する。特殊tokenの認識を無効にしたデータencoderを使う。文字列のタグ連結でpromptを作らない。
- QWERTYの初回モデルはromaji／JP-QWERTYの合成学習データで学習する。US配列固有の誤打・Flick・直接かな入力・実編集イベントの品質を、この結果から主張しない。
- 旧形式replayは元のv4 encoderを使用する。ASCII空白を0、出力空白を567へ写像する歴史的な条件は非可逆であり、新codecの空白保持性能と分ける。

## データと親重みの固定

ルートから実行する。Pythonは既存`.venv_train`を使い、依存を無断更新しない。すべて新しい出力先を指定する。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/inspect_v4_unified_parent.py --out artifacts/results/v4-unified-standard-parent-20261029-r1.json
.\.venv_train\Scripts\python.exe scripts/run.py training/v4_unified_contract.py --parent artifacts/checkpoints/standard-v4-nano-transfer-merged --out artifacts/checkpoints/v4-unified-codec-20261029-r1
.\.venv_train\Scripts\python.exe scripts/run.py data/build_v4_unified_dataset.py --codec artifacts/checkpoints/v4-unified-codec-20261029-r1 --out artifacts/datasets/v4-unified-romaji-20261029-r1
.\.venv_train\Scripts\python.exe scripts/run.py data/check_v4_unified_dataset.py --dataset artifacts/datasets/v4-unified-romaji-20261029-r1 --codec artifacts/checkpoints/v4-unified-codec-20261029-r1 --out artifacts/results/v4-unified-romaji-20261029-r1-audit.json
.\.venv_train\Scripts\python.exe scripts/run.py training/test_v4_unified.py
```

親検査はHF→既存F16の149 tensorの丸め一致、既存量子化manifest、固定Q8比較記録を照合する。
そのfloat親自体は上流量子化GGUFから得た既存v4系列であり、元の上流非量子化重みを復元したという意味ではない。

承認済みMagpie由来の既存分割を保持し、trainの元文約20,000、val約1,200を選ぶ。
v4の辞書的アンカーも既存分割で保持する。評価との衝突、検証に接する学習元文、同一観測に矛盾する正解を除外する。
自動読み・合成誤打・元データ順の部分集合という制約は残る。封印文書は選択・語彙追加・校正・学習に使わない。

## 学習・プロファイル・再開

`train_v4_unified.py`は12層rank32 DoRA、FP32 master＋BF16 autocast、SDPA、pin memory、事前token化を使う。
task配合はconvert60／repair25／predict15。convertの半分はv4アンカー、旧形式とタグ形式は各半分。
repair内はkeep65／emit25／abstain10。長さの違いを抑えるため、target CEを例ごとに正規化する。

独立した凍結v4を教師にし、convertのtarget token列が一致する例だけ相対位置を対応させてKLを計算する。
教師にない新IDへの確率流出も別に抑える。token列不一致の例は数を記録し、誤ってKLに揃えない。
未学習taskに教師KLや既存EOS抑制を適用しない。

```powershell
$env:PYTHONIOENCODING='utf-8'
$env:PYTORCH_CUDA_ALLOC_CONF='garbage_collection_threshold:0.7,max_split_size_mb:128'
.\.venv_train\Scripts\python.exe scripts/run.py training/train_v4_unified.py --codec artifacts/checkpoints/v4-unified-codec-20261029-r1 --dataset artifacts/datasets/v4-unified-romaji-20261029-r1 --audit artifacts/results/v4-unified-romaji-20261029-r1-audit.json --out artifacts/checkpoints/zerocon-v4-unified-standard-romaji-profile-20261029-r2 --steps 1000 --effective-batch 192 --batch-size 16 --profile-steps 100 --profile-only
.\.venv_train\Scripts\python.exe scripts/run.py training/train_v4_unified.py --codec artifacts/checkpoints/v4-unified-codec-20261029-r1 --dataset artifacts/datasets/v4-unified-romaji-20261029-r1 --audit artifacts/results/v4-unified-romaji-20261029-r1-audit.json --out artifacts/checkpoints/zerocon-v4-unified-standard-romaji-p2-20261029-r1 --steps 1000 --effective-batch 96 --batch-size 8 --save-every 100
```

プロファイル内では実効batchを一定にして各候補を100 microstep測る。メモリ予算超過の予測値は実測と区別して記録する。
実学習の予算・実効batchはrun manifestで別に固定する。電力値やGPU使用率を品質・スループットの保証としない。
2026-10-29の初回ではbatch16がWDDMのVRAM上限近くで遅くなり、batch8を選んだ。
計画の95%／170WというP3/P4条件を達成済みとはせず、小規模P2候補として記録する。

新token行は共有embedding/headで学習し、旧6000行はgradient maskとweight decay=0で固定する。
PEFT adapterだけに保存を任せず、`best-new-rows.safetensors`も保存する。HF merge時に旧行のbit一致を検査する。
`training-state.pt`にはadapter、新行、optimizer、進捗、標本／Python／NumPy／Torch乱数を保存する。
再開は元と同じ引数に`--resume <同じrun内のtraining-state.pt>`を付ける。manifest・データ・コードが異なれば停止する。
信頼できない外部pickleを指定しない。完了済みHFへの再exportは別出力で行う。

## correctorのデータと知見の継承

初回P2は誤入力100件の修復が1件で、keepへ偏った。長い修復読みの先頭tokenの損失が例全体の平均で薄まる一方、
keepは2tokenだけで学習される違いを修正する。`--repair-first-token-weight 0.65`はrepairだけを
`0.35 * target平均CE + 0.65 * 最初のtarget token CE`にする。convert/predictには適用しない。
損失だけで成功を判定せず、同じ固定開発例で生生成の救済・誤変更・abstainを確認する。

`data/add_v4_corrector_replay.py`はcorrector-v2のかなだけで確認できる正解読みと文脈を再利用する。
旧データのかな化済み誤入力には元打鍵が残っていないため、その逆変換を実打鍵と扱わない。
正解から正規ローマ字を作って固定tableで読みを照合し、correctorの隣接キー・母音脱落・転置の生成器を
ローマ字段階で適用して、合成rawをそのまま保存する。正当な別綴り、削除中の完結prefix、pendingも分ける。
旧correctorのFlickノイズをQWERTYタグへ流用しない。

[corrector ablation](../reports/typo-assist/corrector-ablation-20261027.md)の「読み出力に変えるだけではコピーが残る」
「辞書語を半数混ぜる多様化では悪化した」という具体的な結果を踏まえ、大量の逆引き辞書語は加えない。
現行評価5ファイルに触れる旧correctorの4元グループは派生ごと除外し、新しい統合データを再監査する。
旧開発validationは保持し、新しいcorrector検証例を追加する。既存正常語・元文・文書・観測入力との分割衝突も除外する。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py data/add_v4_corrector_replay.py --parent artifacts/datasets/v4-unified-romaji-20261029-r1 --codec artifacts/checkpoints/v4-unified-codec-20261029-r1 --out artifacts/datasets/v4-unified-romaji-corrector-20261029-r2
.\.venv_train\Scripts\python.exe scripts/run.py data/check_v4_unified_dataset.py --dataset artifacts/datasets/v4-unified-romaji-corrector-20261029-r2 --codec artifacts/checkpoints/v4-unified-codec-20261029-r1 --out artifacts/results/v4-unified-romaji-corrector-20261029-r2-audit.json
.\.venv_train\Scripts\python.exe scripts/run.py training/train_v4_unified.py --codec artifacts/checkpoints/v4-unified-codec-20261029-r1 --dataset artifacts/datasets/v4-unified-romaji-corrector-20261029-r2 --audit artifacts/results/v4-unified-romaji-corrector-20261029-r2-audit.json --student-model artifacts/checkpoints/zerocon-v4-unified-standard-romaji-p2-20261029-r1/hf --out artifacts/checkpoints/zerocon-v4-unified-standard-romaji-corrector-20261029-r2 --steps 600 --batch-size 8 --effective-batch 96 --save-every 100 --lr 0.00003 --kl-weight 1.0 --repair-first-token-weight 0.65 --corrector-replay-fraction 0.5
```

`--student-model`は統合P2からの段階追加学習。`--parent`の凍結v4教師、旧6000行の保存元とは区別する。
repair内のkeep65/emit25/abstain10とtask全体の比率を維持したまま、各actionの半分をcorrector由来にする。
データ追加と損失変更を同時に行う改善パッケージであり、各変更の単独効果を主張しない。
corrector由来データは今回の利用指示に基づくローカル研究用途として記録する。新しい公開配布の条件審査は別途必要。

### R3：観測読みと修復action

R2も読み救済1/100、正常誤変更2/100で不合格だったため、codecに独立した任意機能を追加した。
`--repair-emit-marker`は`<emit>`を末尾ID6042へ追加し、repairだけを`<emit>ひらがな`／keep／abstainにする。
convert/predictのtarget形式を変更しない。decoderはtaskを受け取り、別taskで修復markerを出した場合は形式違反にする。

`--observation-table`と`--observation-license`を指定したcodecは、rawからpendingを除いた部分の観測読みを内部で作る。
入力データや呼出し元が正解読みを渡す欄は追加しない。全actionで同じ計算を使い、未解決の英字を保持する。
固定表のmappingとMIT表示を保存し、hashを照合してから使用する。実際のTSF状態を完全再現したとは扱わない。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/v4_unified_contract.py --parent artifacts/checkpoints/standard-v4-nano-transfer-merged --out artifacts/checkpoints/v4-unified-codec-observed-20261029-r3 --repair-emit-marker --observation-table artifacts/datasets/origin-magpie-full-curated-20261029-r1/source-evidence/defaultRoman2Kana.swift --observation-license artifacts/datasets/origin-magpie-full-curated-20261029-r1/source-evidence/AzooKeyKanaKanjiConverter-MIT-LICENSE
.\.venv_train\Scripts\python.exe scripts/run.py data/recode_v4_unified_dataset.py --parent artifacts/datasets/v4-unified-romaji-corrector-20261029-r2 --codec artifacts/checkpoints/v4-unified-codec-observed-20261029-r3 --out artifacts/datasets/v4-unified-romaji-observed-20261029-r3 --max-length 512
.\.venv_train\Scripts\python.exe scripts/run.py data/check_v4_unified_dataset.py --dataset artifacts/datasets/v4-unified-romaji-observed-20261029-r3 --codec artifacts/checkpoints/v4-unified-codec-observed-20261029-r3 --out artifacts/results/v4-unified-romaji-observed-20261029-r3-audit.json
.\.venv_train\Scripts\python.exe scripts/run.py training/train_v4_unified.py --codec artifacts/checkpoints/v4-unified-codec-observed-20261029-r3 --dataset artifacts/datasets/v4-unified-romaji-observed-20261029-r3 --audit artifacts/results/v4-unified-romaji-observed-20261029-r3-audit.json --student-model artifacts/checkpoints/zerocon-v4-unified-standard-romaji-corrector-20261029-r2/hf --out artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-observed-20261029-r3 --steps 600 --batch-size 8 --effective-batch 96 --micro-token-budget 1536 --save-every 100 --lr 0.00005 --kl-weight 1.0 --repair-first-token-weight 0.65 --corrector-replay-fraction 0.5 --max-length 512
```

R2のtrain/val/source台帳をバイト一致で複製して新codecへ束縛する。最大512は既存位置上限1024内。
長い系列では`--micro-token-budget`に応じてmicrobatchを小さくし、各部分batchの平均lossを
`部分件数/実効batch件数`で重み付けする。実効batchとtask/action配合は保持する。

### R4：誤コピーの変更位置を学習する

R3は同じ100誤入力の救済10件、正常誤変更0件まで進んだが、修復本文で誤った観測読みのコピーが残った。
`training/v4_repair_edits.py`は観測読みと正解のtoken列を学習時だけ整列し、相違位置へ追加CEを与える。
削除は次の正しいtokenまたはEOSを教師にする。誤コピーtokenとgoldが異なる場合だけmargin2.0・係数0.25の対照損失も加える。
同一の正しいコピー・keep・abstain・convert・predictは対象外。注釈はpromptにも推論にも渡さない。
整列はBPE上の操作proxyで、唯一の言語的誤り位置ではない。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/test_v4_repair_edits.py
.\.venv_train\Scripts\python.exe scripts/run.py training/train_v4_unified.py --codec artifacts/checkpoints/v4-unified-codec-observed-20261029-r3 --dataset artifacts/datasets/v4-unified-romaji-observed-20261029-r3 --audit artifacts/results/v4-unified-romaji-observed-20261029-r3-audit.json --student-model artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-observed-20261029-r3/hf --out artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-editfocus-20261029-r4 --steps 600 --batch-size 8 --effective-batch 96 --micro-token-budget 1536 --save-every 100 --lr 0.00005 --kl-weight 1.0 --repair-first-token-weight 0.65 --repair-edit-weight 1.0 --corrector-replay-fraction 0.5 --max-length 512
```

追加損失は誤修復例の相違位置で平均し、全batch件数で正規化する。taskの標本比率は同じでも、lossと勾配の寄与は変わる。
検証checkpointはmarginを含む`macro_objective`で選択し、CEだけの旧runとは数値を直接比較しない。
実生成で改善しない場合や正常誤変更が増える場合は合格としない。

### R5：修復本文の段階学習

R4は救済4/100・正常誤変更3/100へ悪化したため不採用。R3から分岐し、追加edit損失を使わず、
本文の教師あり学習量を増やす。`training/configs/v4_qwerty_repair_bootstrap.json`に
convert10／repair85／predict5、repair内keep30／emit65／abstain5を明記する。
`--mixture`は正の有限値だけを受け入れ、正規化した実配合とファイルhashをmanifestへ保存する。
元の60/25/15は、未指定時の既定値として残す。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/train_v4_unified.py --codec artifacts/checkpoints/v4-unified-codec-observed-20261029-r3 --dataset artifacts/datasets/v4-unified-romaji-observed-20261029-r3 --audit artifacts/results/v4-unified-romaji-observed-20261029-r3-audit.json --student-model artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-observed-20261029-r3/hf --out artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-repair-bootstrap-20261029-r5 --steps 1000 --batch-size 8 --effective-batch 96 --micro-token-budget 1536 --save-every 100 --lr 0.00005 --kl-weight 6.0 --repair-first-token-weight 0.10 --repair-edit-weight 0 --corrector-replay-fraction 0.5 --mixture training/configs/v4_qwerty_repair_bootstrap.json --max-length 512
```

今回の実runは同内容の`artifacts/results/v4-unified-repair-bootstrap-mixture-20261029-r5.json`を指定した。
同一runの厳密なresumeでは、manifestに記録した元の引数パスを使う。新規再現runでは上の追跡対象configを使える。
サンプリング比率を実際の誤入力事前確率とはせず、採用しきい値は別の校正を必要とする。

## 書き出し・実出力検証

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/export_v4_unified.py --run <run> --out <新しい出力先> --outtype f32
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/verify_v4_unified_parity.py --run <run> --gguf <F32 GGUF> --out <新しいJSON>
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/evaluate_v4_unified.py --run <run> --dataset <監査済みdataset> --regression --out <新しいJSON>
.\.venv_train\Scripts\python.exe scripts/run.py training/export_v4_unified.py --run <run> --out <別の新しい出力先> --outtype f16
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/evaluate_v4_unified.py --run <run> --gguf <GGUF> --regression --out <新しいJSON>
```

GGUF converterは依存コードを変更せず、実際のByteLevel/BPE設定を検査する専用subclassから既存のgpt-2 pre-tokenizerを選ぶ。
語彙追加でtoken ID fingerprintが変わるため、上流の既知hash判定をそのまま新語彙へ当てない。

**固定b4846 CPU実行器へはpromptを整数ID配列で渡す。** ネイティブの文字列tokenize endpointには
追加空白tokenの扱いとliteral特殊タグの認識でHFとの差がある。`parse_special=false`だけで安全と仮定しない。
verifierは整数IDの文字往復・tensorの丸め・greedy生成一致と、文字列endpointの不一致を別フィールドに保存する。
このGGUFを既存IMEへファイル名だけ替えて配備しない。新codecと3task APIの実装、実操作回帰が別途必要。

480問は旧採点器の許容解とraw exactを分け、新旧形式を別採点する。補正はkeep／emit／abstainを分け、
先読みは完全一致だけで人の有用性を代用しない。GPU学習と並行したCPU評価は速度比較へ使わない。
最終封印評価・実打鍵・校正・自動採用の承認・配布品質の達成は、この開発診断とは別に確認する。

## 単発で試す

`infer_v4_unified.py`はIMEから独立した単発CLI。HF directory、またはcodecを同梱したGGUF export directoryを指定する。
結果はJSONで表示するだけで、入力アプリへ採用しない。読み出力契約では修復した読みの漢字表記を同じモデルのconvertで別途求める。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/infer_v4_unified.py --codec <run>/hf --task convert --reading かいぎしつのよやく
.\.venv_train\Scripts\python.exe scripts/run.py training/infer_v4_unified.py --codec <run>/hf --task repair --raw argiatou
.\.venv_train\Scripts\python.exe scripts/run.py training/infer_v4_unified.py --codec <run>/hf --task predict --surface 資料を共有
```

## R6：compact入力と表記修復の比較

詳細は[改善検証記録](../reports/models/zerocon-standard-qwerty-unified-r6-20261029.md)。旧R5のひらがな修復契約と、
R6の表記を直接返すrepairは互換ではない。既存IMEへファイル名だけ替えて適用しない。

`v4_unified_contract.py --compact --repair-emit-marker --repair-input observed --repair-output surface`で
旧6042 IDまでを保った6044語彙を作る。文字列はverbatim、欠損は`<missing>`、空文字は空列。
観測読みはホストの実状態か、同梱した固定raw decoderから作り、正解を入力へ足さない。
`--observation-table`と`--observation-license`を指定するとrawからの単発CLIも利用できる。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py data/build_v4_repair_comparison.py --out <新しい比較データ>
.\.venv_train\Scripts\python.exe scripts/run.py data/audit_v4_repair_comparison.py <比較データ>
.\.venv_train\Scripts\python.exe scripts/run.py training/train_v4_repair_comparison.py --dataset <比較データ>/reading --out <新しい読みrun>
.\.venv_train\Scripts\python.exe scripts/run.py training/train_v4_repair_comparison.py --dataset <比較データ>/surface --out <新しい表記run>
```

かな正解を検証できる旧correctorの共通部分だけを同じ入力・分割に揃え、教師を切り替える。
元打鍵がない例は観測読みのまま使う。専用の対照モデルは通常変換の保持を保証しない。

`build_v4_observed_unified.py`は固定decoderによる観測と元correctorの観測例・正常prefix・pendingを合流する。
表記が検証できないrepair emit、矛盾する観測、評価に触れる文書、valへ触れるtrain文書を除く。
`add_v4_literal_replay.py`は固定乱数のliteral copyをtrainへ追加し、valは変えない。
このcopy例はtagged anchorだけに入り、旧lossy codecや旧教師へ渡さない。

**固定教師を使う統合trainerは、学生の全Dropoutを無効にし、非0の要求を拒否する。**
学生だけDropoutを有効にすると同じ重みでも大きなanchor不一致を生むことを対照測定した。
Dropoutを含む専用recipeは、固定教師なしの専用trainerで比較する。
旧R6a/R6bの失敗条件は各runのsource-codeとmanifestへ保存した。現行ソースを古い状態へ破壊的に戻さない。

R6cはR6aの選択HFへ戻し、元v4教師・rank32 DoRA・Dropout0・KL6・先頭action重み0.10で補正正例を増やす。
配合は`training/configs/v4_surface_repair_bootstrap.json`、実効96・token予算3072・max512。
`--profile-steps 5 --profile-memory-mib 8500`の計測と選択microも保存する。失敗したR6bの0.25 rebaseは混ぜない。

R6dはR6cの選択HFから再統合する。`training/configs/v4_surface_consolidation.json`に
convert50／repair35／predict15、repair内keep50／emit45／abstain5を記録する。
LR3e-5・KL1.2・先頭action重み0.25・実効64・micro16・token予算3072・max512、Dropout0で1,000step。
valの親正解維持を含む生成probeでcheckpointを選択する。再現時はrun manifest・固定probe・source-codeも照合する。

`v4_generation_selection.py`はvalの固定probeと独立literal canaryを作る。
`--with-parent-reference`ではanchor85例も含め、凍結した親HFが正解した検証例を記録する。
`train_v4_unified.py --generation-validation <probe.jsonl>`はこの生成結果でcheckpointを選び、既知の480問は選択へ使わない。
probeはcodecとdataset manifestへ束縛する。`summarize_v4_unified_run.py`はrun全体と選択stepまでの学習量を分ける。

`evaluate_v4_repair_comparison.py`は共通rawで現行モデルと比較する。辞書による読み照合は意味の正しさや実IME正答率ではない。
`benchmark_v4_surface_pipeline.py`は現行correctorの記録と同じhashのRust評価器に研究用HTTP adapterで接続する。
元の観測読みを保持するkeep/abstainは入力を返して曖昧なfallbackを抑止する。
上流処理で既に修復した読みへのkeepは、元観測との差を確認して通常変換へ渡し、かなのまま最終表記にしない。
未完生成は未完として拒否させる。
`--fixed-normal <GGUF>`で通常候補を固定でき、省略時はconvertとrepairが同じ重み・同じ推論プロセスを使う。
製品ソースやインストール済みIMEを変更しない。Swift・TSF・編集操作は含まず、adapterの時間を旧値と直接比較しない。

R6単発repairは`--reading`に実際の観測読みを指定でき、同梱decoderがあれば`--raw`も利用できる。
`repair_output=surface`、`reading_verification_required=true`は採用前に読み検証が必要という意味で、自動採用は常にfalse。

`package_v4_surface_research.py`は学習完了、HF/Q8の評価hash、F32/F16整数ID parity、
サンプラー監査と固定通常出力300件を照合して研究カードを作る。既存カードは上書きしない。
`--reading-analysis`と`--cli-probe`で副採点・単発の失敗例も保存できる。
モデルのカード作成は実IMEへの適用や製品品質の合格を意味しない。

## R7：混在文字列保持とtask別更新の比較

[R7実行記録](../reports/models/zerocon-standard-qwerty-unified-r7-20261030.md)に実際のデータ・予算・結果を保存する。
`add_v4_mixed_preservation.py`で規則教師の混在保持を追加し、`check_v4_unified_dataset.py`で独立監査する。
新規例は旧lossy codecへ渡さず、compact形式のみで学習する。検証用suffixテンプレートはtrainと分ける。

`train_v4_unified.py --task-gradients measure`は変換CE＋KLと補正／先読みを別々に勾配累積し、そのまま合成する対照。
`--task-gradients project`は負内積のときだけ後者の逆向き成分を除く。clipとoptimizer更新は合成後に一度行う。
FP32勾配と内積・ノルムを`task-gradient-history.jsonl`に保存する。局所の内積から生成品質の維持を保証しない。
`--max-processed-tokens N`は実効batch単位で停止し、最後の1batch分の超過を実数で記録する。
`metrics.json`のstepsは実際の終了step、manifestのstepsはscheduler上限。サンプラー再生は前者を使う。
生成validationの`normal_changed`は誤ったemitを数え、`normal_abstained`は正常入力での保留を別集計する。
異なる採点コードを比較する場合は初期重みも再測定し、保存済み旧結果を書き換えない。
