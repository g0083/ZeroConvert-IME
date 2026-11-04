# ZeroCon-Origin の学習・互換検証

ZeroCon-Originは、同じstudent重みでかな漢字変換・読み修復・先読みを行う研究用学習系。
現行IMEから独立し、既存の重み・語彙ID・設定・配布カタログを変更しない。
方針は[構築計画](../reports/models/zerocon-origin-build-plan-20261029.md)、
最初の実測と未達事項は[T0接続試作の記録](../reports/models/zerocon-origin-t0-20261029.md)を参照する。

## 実装範囲

`training/origin_contract.py`が入力状態と制御ID、`training/train_origin.py`が語彙学習・ランダム初期化・CE学習、
`training/export_origin.py`がGGUF変換、`benchmarks/research/benchmark_origin_parity.py`が所有CPUサーバーとの比較を担当する。
RoPE、Pre-RMSNorm、SwiGLU、GQA、共有埋め込みはTransformersのLlama実装を利用する。
初段はランダム初期化。追加学習は来歴を照合した自前Origin重みから行い、同じ学習の厳密な再開と区別する。

v4の正常入力保持、EOS、データ隔離、初期化前seed、来歴保存、量子化前後の比較を反映した。
今回参照したv4の学習はCE/KL・DoRAによる追加学習。Origin初期版はA0のCEのみで、
DoRA・自己コピー罰則・自己アンカー・教師KL・強化学習は実装していない。
新語彙へv4のtoken IDやγを流用しない。異なる語彙の教師から学ぶ場合は、来歴を付けた生成系列を先に扱う。

## 実行環境

ルートから `python scripts/run.py <対象.py>` で実行する。測定時はPython 3.12.11、
`training/origin-requirements.txt`の版を使用した。専用の`.venv_origin`へSentencePieceを導入し、
ローカルの`zerocon_training_runtime.pth`から既存`.venv_train/Lib/site-packages`を読み取り参照している。
既存環境のパッケージは更新していない。再構築する場合は専用Python 3.12環境へrequirementsを導入する。
CPU単独の場合は適したPyTorch wheelを選び、測定環境が変わったことを記録する。

## データ契約

入力ディレクトリに`train.jsonl`、`val.jsonl`、`sources.json`を置く。
以下は形式の説明であり、本学習コーパスや封印評価の代用品ではない。

```json
{"id":"example-1","source_id":"authored-1","document_id":"document-1","source_text":"棚の奥へ。","task":"convert","input":{"ctx":"","reading":"たなのおくへ"},"target":{"action":"emit","text":"棚の奥へ"}}
```

| task | 主な入力 | 出力 |
|---|---|---|
| `convert` | `ctx`, `reading`, `protected` | 入力範囲の表記 |
| `repair_qwerty` | `raw`, `surface`, `pending`, `cursor` | ひらがなの修復読み、`keep`、`abstain` |
| `repair_flick` | `surface` | ひらがなの修復読み、`keep`、`abstain`。現時点は契約のみ |
| `predict` | `ctx`, `surface`, `raw`, `pending` | 追加suffix、`abstain` |

`cursor`は未確定の`surface`内のUnicodeコードポイント位置。UTF-16単位ではない。
`protected`は`{"field":"surface","start":0,"end":3,"text":"１２３"}`等の配列。
範囲と元文字列の一致を検査するが、この検査だけで生成後の保護・読み一致・適切な自動採用を保証しない。
実IMEへの統合時は出力の読み、保護領域、世代、カーソル、取消を別に検査する。

`sources.json`はsource_idをキーにし、次の記録を必須とする。

```json
{
  "authored-1": {
    "license": "Apache-2.0",
    "origin": "元文の作成者・資料・版",
    "evidence": "利用条件と権利帰属を確認した資料の所在",
    "reviewed_by": "確認者と確認範囲",
    "approved_uses": ["training", "weight_distribution"],
    "approved_weight_license": "Apache-2.0",
    "weight_license_review": "Apache-2.0で重みを配布できると判断した具体的根拠",
    "teacher": null
  }
}
```

外部教師を使うsourceは`teacher`に`model`、`revision`、`license`、`output_terms_evidence`も記録する。
承認フラグは利用条件の自動判定ではない。根拠のない承認を記入しない。
資料単位の条件、必要な表示、個人情報、元プロンプトの由来も確認する。
[Apache公式本文](https://www.apache.org/licenses/LICENSE-2.0)と依存のLICENSE/NOTICEを保持する。
自作ファイルのライセンスはリポジトリの他の部分を一括変更しない。

同一document・正規化した同一元文・同じタスク入力と正解がtrain/valをまたぐと停止する。
既存5評価ファイルの予約キーとの完全一致も学習前に検査する。
大規模コーパスでは近重複の近似検索と元文書の事前取り分けも行う。
テンプレート共有・意味的類似の完全除去、独立した封印正解付けは未完了で、出荷用の隔離完成とは扱わない。

## 制御タグと文字保持

タグはSentencePieceのcontrol symbol。`OriginCodec.prompt()`が制御IDを直接挿入し、
データ部分は通常のSentencePiece符号化を通す。タグと同じ文字列を入力しても制御IDにはならない。
NFKCをモデル入力に適用せず、byte fallback、正規化identity、空白非圧縮、dummy prefixなしを使う。
内部の空白記号U+2581のリテラルはUTF-8 byte piecesへ符号化する。
極小コーパスでもASCII空白のpieceが存在するよう、自作の空白・ASCII補助文を語彙学習へ加える。
語彙学習へ入れる元文・入力・正解はtrain行のみ。validationは使わない。

**現在のCPUランタイムb4846では、`/tokenize`の`parse_special=false`が効かない。**
入力とタグを文字列連結して`/completion`へ送ってはいけない。
`OriginCodec`で作成した整数ID列を`prompt`へ渡す。
互換ベンチはリテラルタグをこの方法で渡し、`/detokenize`による文字保持も確認する。
ネイティブな生文字列tokenizerの互換が成立したとは扱わない。

## 再現コマンド

次は完了した試作のコマンド。再実行時は既存フォルダを保護するため、新しい出力名を指定する。

```powershell
.\.venv_origin\Scripts\python.exe scripts/run.py training/test_origin.py
.\.venv_origin\Scripts\python.exe scripts/run.py data/build_origin_fixture.py --out artifacts/datasets/origin-fixture-20261029-r2
.\.venv_origin\Scripts\python.exe scripts/run.py training/train_origin.py --dataset artifacts/datasets/origin-fixture-20261029-r2 --out artifacts/checkpoints/origin-t0-a0-20261029-r3 --steps 500 --save-every 250 --lr 0.001 --generate-validation --generate-train
.\.venv_origin\Scripts\python.exe scripts/run.py training/export_origin.py --run artifacts/checkpoints/origin-t0-a0-20261029-r3 --out artifacts/models/origin-t0-a0-20261029-r3-export
.\.venv_origin\Scripts\python.exe scripts/run.py benchmarks/research/benchmark_origin_parity.py --run artifacts/checkpoints/origin-t0-a0-20261029-r3 --gguf artifacts/models/origin-t0-a0-20261029-r3-export/origin-f16.gguf --dataset artifacts/datasets/origin-fixture-20261029-r2 --out artifacts/results/origin-t0-a0-20261029-r3-parity.json
```

再開は`--resume <元run>/training-state-250.pt --tokenizer <元run>`を指定する。
新しい`--out`を使い、元runと同じ総steps、batch、LR、seed、max-length、sizeを指定する。
データ・語彙・コードhashの違いは拒否する。optimizer、scheduler、CPU/CUDA乱数状態も保存する。
pickle形式の再開ファイルは、この学習系で自身が作ったものだけを使う。

`--size n28/n33/s68/s88`は計画書の構造設定。実際に8,192/16,384 piecesの語彙が必要で、
小さなfixtureから不足した語彙を作って同じ規模名で報告することは拒否する。
T0のみ、少量コーパスによる語彙数の縮小を許す。
損失はprompt/padをmaskし、EOSを必ず含め、各例のtarget長で正規化する。
暗黙のtruncation・系列packingは行わない。現時点では自然文LM task、直接の教師生成器、
task比率の探索、候補順位学習、Rust adapterは未実装。Q8は`quantize_origin_q8.py`で研究用に生成できる。

## 量を増やしたpilotと処理token予算

`data/fetch_origin_corpus.py`は公開された合成日本語データを固定revisionで取得し、データ・LICENSE・帰属・
上流と生成元のモデルカード・hashを保存する。外部コードや重みは実行・取得しない。
`data/build_origin_corpus.py`は元文書の10%を先に取り分け、その全文書と同じ文が現れた場合も学習から外す。
別の5%をvalidationへ置く。全派生例は同じ元文書に従い、同じタスク入力/正解は全体で重複除去する。
近重複は文字5-gramのbottom-8候補検索とJaccard .85で除去する。検索の完全性や意味的な隔離は保証しない。

UniDic 2.1.2由来の`kana`を読みとし、`pron`を用いない。書字形のかな、助詞、数字・幅・空白・ASCIIを保ち、
未知漢字や解析不整合はレビューキューへ隔離する。読みは自動付与で、校正済みの正解とは区別する。
ノイズは合成であり、実IMEが表示する誤入力表記を捏造しない。

```powershell
.\.venv_origin\Scripts\python.exe scripts/run.py data/fetch_origin_corpus.py --out artifacts/datasets/origin-magpie-source-20261029 --shards 12
.\.venv_origin\Scripts\python.exe scripts/run.py data/build_origin_corpus.py --source artifacts/datasets/origin-magpie-source-20261029 --out artifacts/datasets/origin-magpie-pilot-20261029-r2 --sealed-out artifacts/datasets/origin-magpie-withheld-20261029-r2 --max-sentences 100000
.\.venv_origin\Scripts\python.exe scripts/run.py data/check_origin_corpus.py --dataset artifacts/datasets/origin-magpie-pilot-20261029-r2 --withheld artifacts/datasets/origin-magpie-withheld-20261029-r2 --out artifacts/results/origin-magpie-pilot-20261029-r2-audit.json
.\.venv_origin\Scripts\python.exe scripts/run.py training/train_origin.py --dataset artifacts/datasets/origin-magpie-pilot-20261029-r2 --out artifacts/checkpoints/origin-n33-t1-50m-20261029-r1 --size n33 --token-budget 50000000 --batch-size 16 --grad-accum 4 --max-length 256 --lr 0.0003 --save-every 1000 --validation-every 500 --max-validation-examples 512 --task-weights '{"convert":0.45,"repair_qwerty":0.30,"predict":0.20}'
```

既存出力名は再利用しない。取得中の失敗は`fetch_origin_corpus.py --resume`で固定済み版を継続できる。
中断した`.partial`ファイルがある場合は、正しい取得済みファイルとして扱わず停止する。

`--token-budget`は固定seedのサンプリングを先に再生し、指定の処理token量に達するstepsを決める。
target token量やunique元文量とは異なる。`--grad-accum`で実効batchを増やせる。
`--task-weights`の値は全体を正規化して使う。上の45:30:20には自然文LMが含まれないため、
実際の確率は約47.4%:31.6%:21.1%。`progress.json`、`validation-history.json`、中間checkpointを保存する。
validationを512例に絞る場合は、母数と固定seedの標本数をrun manifestへ別々に記録する。
resumeは元と同じtoken予算、task比、gradient accumulation、validation標本上限も要求する。
ランダム初期化でも、外部生成データを使う条件は`Origin-B/sequence-CE`として記録する。

`benchmarks/research/freeze_origin_baseline.py`は過去のCPU結果を現在の重み・実行器・評価データのhashと照合し、
480問のStandard 468以上・Nano 466以上等の比較条件を固定する。新しい速度測定の代用ではない。
GGUF化した候補は`benchmark_origin_regression.py --run <run> --gguf <gguf> --out <新しいJSON>`で、
既存480問と同じ採点関数・CPU実行条件へ接続できる。これだけでv4超えとは判定しない。
封印データ、誤補正、先読み妥当性、Windowsの順序付き操作・実IME確認は別途必要。

## raw状態の修正・大規模キャッシュ

初期pilotの補正例には、正解actionと入力欄の有無が相関する偏りがあった。
`rebalance_origin_repair.py`はすべての補正例を同じ`ctx/raw/pending`へ揃える。
正解読みは`reference_reading`に保持し、モデル入力へ渡さない。
`adapt_origin.py`は親の自前Origin重みと、全行を検証した元文を変えない変換だけを許す。
詳細と未達結果は[N33 50M検証](../reports/models/zerocon-origin-n33-50m-20261029.md)を参照する。

`curate_origin_repair.py`は固定ローマ字表で同じ読みに到達する別綴りをkeepとし、末尾状態が未解決の例を隔離する。
表のMIT表示・commit pin・内容hashとオフラインデコーダを`source-evidence/`へ保持する。
この処理はTSF状態を再現するものではない。validationも変わる新しいデータ版として扱い、旧版の診断と混ぜない。
処理後に`check_origin_corpus.py`を実行し、予約文書・評価キー・分割の監査がPASSしたものだけをキャッシュ化する。

```powershell
.\.venv_origin\Scripts\python.exe scripts/run.py training/prepare_origin_token_cache.py --dataset <監査済みデータ> --audit <監査JSON> --out <新しいキャッシュ> --vocab-size 16384 --tokenizer-max-rows 100000 --tokenizer-sample-seed 42
.\.venv_origin\Scripts\python.exe scripts/run.py training/train_origin_cached.py --cache <キャッシュ> --out <新しいrun> --size s68 --token-budget 50000000
```

SentencePiece学習はtrain行だけの固定seed reservoirに上限を設け、母数・抽出行数・標本hashを記録する。
validationを語彙学習に使わない。uint16 tokenと16-byte indexの読み取り専用mmapを使い、
prompt loss mask・EOSを再構成する。大規模な生コーパスとPythonのtokenリストを同時常駐させない。
系列長の超過は切り捨てずエラーにする。

`train_origin_cached.py --base <自前run>`は同じデータ・語彙・構造での段階追加学習、
`--resume <training-state.pt>`はoptimizer・scheduler・乱数まで含めた同一学習の再開。
第三者checkpointのpickleは読まない。S68/N33の規模比較には同じキャッシュ・処理token予算を使う。

## 短い変換範囲の追加実験と精度診断

`data/augment_origin_spans.py`は、監査済みの学習用full変換から短い連続範囲の例だけを追加する。
元の学習行・validationは保持し、前方文脈だけを使う。`data/verify_origin_spans.py`で元のbytesと全追加例の範囲・読み・文脈を照合し、
別途`check_origin_corpus.py`を実行する。両監査がPASSしたものは`training/append_origin_span_cache.py`で
元キャッシュを別フォルダへ複製し、同じ語彙で末尾を追加できる。
これは実行中の学習データを更新するコマンドではない。元の`train_origin_cached.py`のwarm startにはデータ完全一致の制約がある。

F16の出力差を切り分けるときは`export_origin.py --outtype f32`で元精度のGGUFを新しいフォルダへ生成する。
`check_origin_export_rounding.py`はF16の2次元tensorがF32から期待どおりに丸められ、1次元が保持されたかbit比較する。
`diagnose_origin_precision.py`は最初の分岐のlogit差をメモリ上で調べる。
FP32での整合、F16での厳密一致、量子化後の品質は別の結果として記録し、失敗を合格へ置き換えない。
実測は[S68途中診断・短語句実験](../reports/models/zerocon-origin-s68-spans-20261029.md)を参照する。

## 大量例の診断と、段階追加学習v2

`evaluate_origin_tasks.py --batch-size N --record-scores`は生成上限ごとにまとめて左paddingし、
実生成系列から未校正の特徴を保存する。batch1が既定。batchの壁時間を入力1件の応答時間として扱わない。
`origin_operating_cohort.py`は既存validationのQWERTY例を文書単位でcalibration / validation-checkへ固定する。
評価では`--operating-cohort <JSON> --cohort-partition calibration`等を両方指定し、全例なら`--max-cases 0`。
校正のpoint候補を凍結してから`analyze_origin_operating_points.py --selection <校正結果>`で確認側を評価する。
候補がなければ確認側からしきい値を選ばない。両方とも開発用で、封印評価や実IMEの自動採用承認の代用ではない。

`training/train_origin_cached_v2.py`は既存trainerを残した別entry point。
同じデータの`--base`に加え、元行・validation・利用根拠を保持した短語句追加だけを、
`--span-lineage-audit`と`--isolation-audit`のhash検証を経て許す。
任意のデータ変更や語彙交換を許すflagではない。

`--action-weights '{"repair_qwerty":{"keep":65,"emit":25,"abstain":10}}'`のように、
task内のkeep/emit/abstain比を明示できる。未指定なら従来task samplerと同じCDF。
source追加と比率変更を独立した比較にし、正常keepとpending abstainを同じ「正常率」と数えない。
`--device cpu`でGPU学習を妨げず配線テストもできる。
`--balance-repair-context`はrepairのkeep/emitそれぞれの中で、入力のctx空/非空を50:50にする。
action全体の配合は保持し、abstainは変更しない。両状態の実データがなければ停止する。
文脈有無と補正正解ラベルの相関を調べる独立条件として使い、短語句追加の効果と混同しない。
resumeには同じ配合・予算・deviceを要求し、`check_origin_resume.py`で値の完全一致を検査できる。
pickleのoptimizer checkpointは、このprojectで生成した信頼できるものだけを読む。

実測・最終S68の未達結果は[補正スコア・継続経路の記録](../reports/models/zerocon-origin-operating-curriculum-20261029.md)を参照する。

## 別系統のローカル教師の準備

`train_origin_cached_v3.py`はv2を残した別entry pointで、`origin_target_loss.py`によるtarget位置だけの
出力投影/CE計算を使う。全入力のdecoder処理・例ごとの正規化・EOS・入力への間接勾配は維持する。
数学的に同じ目的でも丸め差があり、v2とのbit一致や学習品質の同一性は仮定しない。
`profile_origin_target_loss.py`で所有するHF/cacheの同じ標本を使った計算・勾配比較ができる。
既存方式との単独要因比較中には途中で切り替えない。

`profile_origin_prediction_targets.py`は先読み正解の長さ・句読点/空白・自動品詞proxyと、
同じ診断出力の層別一致数を集計する。正解・点数は変更せず、有用性の人手評価の代用にしない。

`data/fetch_origin_teacher.py --out <新しいartifactsフォルダ>`は、レビューした公式
`Qwen/Qwen3-4B-Instruct-2507`のrevision `cdbee75f17c01a7cc42f958dc650907174af0554`だけを取得する。
LICENSE・モデルカード・設定・tokenizer・safetensorsを保存し、分割重みは公開LFS SHA-256とも照合する。
remote codeは取得・実行せず、HF cacheも指定した出力先内に置く。同じ取得の継続だけ`--resume`で許す。
自前studentの初期重みや語彙に使ったとは扱わない。

`benchmarks/research/probe_origin_teacher.py --source <取得フォルダ> --gguf <教師GGUF> --server <隔離したserver> --out <新しい出力先>`
は独立したローカルサーバーで、評価文を含めない自作の生成指示4件を試す。
元のHTTP応答・生成条件・hash・形式検査を保存し、プロセスを終了する。
出力はレビュー候補で、学生の学習へ自動投入しない。形式・文字数の合格は読みや日本語の正確さを保証しない。
現行IME・固定比較用b4846の実行器は変更しない。教師側の実行器変更をv4 CPU比較へ流用しない。
