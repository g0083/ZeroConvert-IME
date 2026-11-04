# ZeroCon-Origin 補正の出力スコア評価と継続学習経路

2026-10-29。[S68途中診断・短語句実験](zerocon-origin-s68-spans-20261029.md)の後続。
**v4超えは未達。以下は開発用の合成誤打診断と学習経路の検証であり、実IMEへ適用していない。**

## 逐次評価を保ったバッチ化

`benchmarks/research/origin_batch_inference.py`を追加し、評価器の既定batch sizeは1のまま維持した。
複数例では左paddingを使い、各例の生成上限`min(96, context - prompt長)`が同じ例だけをまとめる。
padding込みの物理長もcontext内へ収め、短い例の生成上限を長い例に合わせて縮めない。
EOS後のframework paddingだけを除き、EOSなしのPADや、途中の不正なcontrol tokenを有効な出力へ直さない。

T0の全validation 24件（batch1/4）、S68 step2000の固定256件（batch1/8）で、入力・正解・IDを照合し、
生成token列と出力action/textが全件一致した。
T0で両方記録したスコアの最大差は約`3.84e-7`。
これは検証した条件の一致であり、任意のモデル・batch・GPU精度で同一出力を保証するものではない。

結果は`artifacts/results/origin-t0-batch-equivalence-20261029.json`、
`origin-s68-step2000-batch-equivalence-20261029.json`。
検査コードは`compare_origin_diagnostics.py`。旧評価器のsourceもhash名で`origin-evaluator-source-snapshots/`へ保全した。

新しい評価ではHFの重み・config全ファイルを実行前後にhash比較し、変更されていれば停止する。
model dtype、batch size、コードhashを残す。batchの場合の`latency_ms`はnullにし、batch wall timeを別に記録する。
batch時間を例数で割ってIME応答速度として扱わない。

## 出力スコアは正解確率ではない

`evaluate_origin_tasks.py --record-scores`はモデルが実際に生成した系列のlog probabilityを記録する。
正解文・正解読みをteacher forcingで与えて信頼度に見せない。
最初のステップで通常の文字tokenへ置く確率質量、生成token/EOSの最小確率、平均log probability等を保存する。
実験用`emission_score`は最初のemit質量と最小token確率の小さい方。
NaNや選択tokenの非有限値は停止し、keep/abstainを高信頼の補正と数えない。
これらは未校正のモデル特徴で、補正が正しい確率を意味しない。

## 文書別の校正・確認セット

`origin_operating_cohort.py`が既存validationの補正例だけを、固定seedと文書IDのSHA256で分割する。
モデルの出力・スコアは分割に使わず、同じ文書の派生例を両方へ置かない。

| 区分 | 文書 | 補正例 | keep | noisy emit | abstain |
|---|---:|---:|---:|---:|---:|
| calibration | 1,529 | 36,495 | 17,556 | 9,979 | 8,960 |
| validation-check | 1,482 | 35,650 | 17,210 | 9,587 | 8,853 |

全72,145例のIDを`artifacts/results/origin-repair-operating-cohort-20261029.json`へ固定した。
既存validationはモデル開発にも使っているので、両方とも開発用であり、独立した封印評価とは呼ばない。
評価器は元validation hashと文書分割を再計算し、ID・件数の手編集を受け入れない。

`analyze_origin_operating_points.py`はcalibrationだけで固定gridを調べ、point estimateの候補を凍結する。
別の`--selection`実行でのみvalidation-checkを確認でき、モデル・HF重み・推論条件・cohort・policyコードを照合する。
確認側の成績を見てその場でしきい値を選ばない。
候補なしなら、確認側から都合のよいしきい値を拾う処理も行わない。

提案フィルターは観測input・生成結果・生成スコアだけを受け取る。
未確定子音、ひらがな以外、固定ローマ字表での観測読みと同じno-opを除外する。
正解や`reference_reading`をフィルターへ渡さない。正解は、その後の成績集計だけに使う。
この観測読みはオフライン近似で、TSFの実状態を再現したものではない。
表・コードはモデルに保存したMIT表示付きの固定証跡とhashを照合した。

## 同じ512例で見えた救済と誤補正の両方

calibrationから同じseedの層化512例（誤打256・正常192・abstain64）を抽出し、CPU batch8で評価した。
下表はしきい値0で、上記のno-op等の除外を適用した値。実採用率や実利用時precisionではない。

| 自前S68 checkpoint | 処理tokens | 正しい読み変化/提案 | 合成誤打救済 | 正常の誤変更 |
|---|---:|---:|---:|---:|
| step2000 | 5,531,080 | 5/49 | 5/256 | 7/192 |
| step10000 | 27,646,893 | 41/149 | 41/256 | 17/192 |

救済は増えたが、正常入力への誤変更も増えた。
固定gridでprecision 99.5%・救済90%等のpoint目標を同時に満たす候補は、どちらのcheckpointにもなかった。
何も提案しない場合はprecisionをnull、救済を0として扱い、合格にしない。

両方の集計結果は`artifacts/results/origin-s68-step2000-calibration-operating-points-20261029.json`と
`origin-s68-step10000-calibration-operating-points-20261029.json`。
同じsampleでも計算上の停止guard追加等でコードhashが異なる箇所は記録されている。
使った特徴式・固定しきい値grid・候補フィルターは同じ。途中診断から最終モデルの優越を主張しない。

例ごとのprecision point estimateと、文書単位「一つでも正常誤変更」「採用がすべて正しい」の片側Clopper-Pearson診断を分けた。
後者は独立文書を仮定した文書eventの統計で、打鍵ごとの誤補正率のCIへ置き換えない。
校正側の正常文書1,529件では、仮に誤り0でも文書eventの片側95%上限は約0.196%。
0.1%未満を示すには、独立事象なら最低2,995件の0誤りが必要になる。
実利用の相関・意図・自動採用・人手正解は別に検証が必要で、自動採用承認はすべてfalseで記録する。

## keep比率と、元データを保った継続学習

現行全量データのrepairはkeep 583,547・emit 329,123・abstain 298,314例。
keepは約48.2%、abstain込みの非emitは約72.8%。計画の「keep 60〜80%」と非emit比率を混同しない。
S68 T1は比較条件を変えず、50,002,572処理tokensで完走した。

別の`training/train_origin_cached_v2.py`と`origin_sampling.py`を追加した。
元の実行中`train_origin_cached.py`やその依存4ファイルは編集せず、run manifestのhashとの一致も確認した。

- action指定なしのCDFは、従来のtask別CDFとbit単位で同じ順序・計算になる。
- 任意指定の`{"repair_qwerty":{"keep":65,"emit":25,"abstain":10}}`は、repair内で各比率へ正規化する。
- target開始tokenからkeep/abstain/emitを区別し、正解欄の有無をモデル入力へ足さない。
- データが変わるwarm startは、全件の元文範囲検証・隔離監査・親cache identity・同一語彙・同一validation/LICENSE記録を要求する。
- 自前の完了run、または保存完了checkpointからの独立HFコピーだけを親にする。
- 構造・RoPE・特殊token・共有埋め込みを照合し、親HFの読み込み前後hashも確認する。
- source追加、action配合変更、厳密なresumeは別の操作として来歴を残す。

実在する全量cacheと追加762,298例のcacheを使い、CPUのT0（2,491,008 parameters）で接続を検査した。
元cacheで5,086処理tokensをランダム初期化学習し、その自前重みから追加cache＋65/25/10を5,277 tokens学習した。
さらにstep5から再開し、最終17 stepsで重み・loss履歴・optimizer・scheduler・CPU/CUDA RNGの値が完全一致した。
検査結果：`artifacts/results/origin-cached-v2-span-resume-20261029.json`。
これは配線・再開の検証で、S68の品質向上の結果ではない。

元cacheのS68/N33 T1比較後、追加短語句とkeep配合を別条件として検証できる。
実S68で両方を同時に変えて、どちらか一つの効果と報告しない。

## 既存817行の来歴確認

ユーザーから「AIが作った」と説明があり、AI生成の例文として来歴を記録した。
元train 817・val 85行の実ファイルhashと、Git commit・生成器コードhashも照合した。
続いてユーザーからGemini / Google AIによる生成だと確認できた。正確な利用経路・生成時の契約・別許諾は未確認。
確認した[Google日本向け規約](https://policies.google.com/terms?hl=ja&gl=jp)にはAI生成内容によるモデル開発の制限がある。
[Gemini API規約](https://ai.google.dev/gemini-api/terms)にも競合モデル開発の制限があり、
[Antigravity規約](https://antigravity.google/terms)は個人利用と企業/API経路の適用契約を分けている。
これは確認時点の公開条件で、既存生成時の契約違反を断定するものではない。
一律にApache配布モデルへの学習利用可とは判断せず、この817行は採用保留として保全する。
現在の確認済み素材による実験を続け、既存v4の成果物・設定は変更しない。
ルートに包括LICENSEがあるとは確認できていないため、依存のMIT表示をこの例文へ流用しない。
レビューは`artifacts/results/origin-legacy817-provenance-review-20261029.json`。
現在のS68の比較用データ・語彙を途中で置き換えていない。

## S68 T1の最終結果と、同条件N33の開始

S68の最終保存後、session `17276`がexit 0で終了したことを確認した。
処理50,002,572 tokens、うちtarget 10,398,124。16,267.4秒の記録経過時間には、
step13450→13500の11,836.5秒という長い進捗記録間隔が含まれる。GPU純計算の速度比較に使わない。

Q8は72,332,800 bytes。同じ480問・採点器・固定CPU実行器で**142/480（通常110/230、難問32/250）**。
現行v4最高459/480には遠く、N33 pilot等との数問差を規模の優位とは報告しない。
制御形式は各モデルの契約に従う。CPU実行器のhelpで既定repeat penaltyが1.0であることも確認した。
時間は次のN33学習と並行した測定なので比較対象外。
結果：`artifacts/results/origin-s68-full-50m-q8-regression-20261029.json`。

GPUでの層化1,024件の最終診断：

| 指標 | 成績 |
|---|---:|
| 変換の正解表記完全一致 | 105/172 |
| ASCII/数字を含む期待出力の系列不一致 | 2/47 |
| 合成誤打の正解読み救済 | 116/340 |
| 正常読みを変更する提案 | 10/255 |
| 未確定子音へのemit | 0/85 |
| 先読みsuffix完全一致 | 50/86 |
| 完了文への先読み提案 | 0/86 |

先読み別解の妥当性・打鍵削減は未評価。正常例やtaskの割合は層化で均しており、実運用頻度ではない。
`artifacts/results/origin-s68-full-50m-tasks1024-20261029.json`へ保存した。

calibration全36,495例の別測定では、しきい値0＋観測側フィルター後の読み変化6,080提案のうち正解3,527。
誤打救済3,527/9,979、正常誤変更120/17,556、未確定へのモデルemit 0/8,960。
固定gridで目標を同時に満たすpoint候補はなかった。
全量は正常な別綴りの頻度が層化512件と違うため、全量の率の低下をそのまま学習改善と比較しない。
結果は`origin-s68-full-50m-calibration-scores-20261029.json`と`origin-s68-full-50m-calibration-operating-points-20261029.json`。

最終checkpointでもF32 GGUFはCPU HF参照256/256件の出力・token列が一致した。
F16は全92 tensorが期待する丸め値と一致した。
Q8は255/256一致で、隣接誤打1件が正解emitからkeepになり、救済が27/84→26/84へ低下した。
正常誤変更2/63・pending emit 0/21・変換29/44・先読み37/44は同じ。この退行をparity合格や無損失量子化とは扱わない。
結果は`origin-s68-full-50m-f32-parity-20261029.json`、`origin-s68-full-50m-q8-quality-20261029.json`、
`origin-s68-full-50m-export-rounding-20261029.json`。

480問の338失敗を自動解析すると、入力読みの完全コピーは5件、異なる推定読み168件、同じ推定読みで表記違い162件、
解析不能2件、空/無効出力1件。UniDicの推定自体が誤る可能性があり、人手裁定ではない。
期待scoreは書き換えていない。単純なコピー罰則だけを主軸にせず、語彙・短い変換範囲・文脈選択を次段で強化する根拠にする。
`analyze_origin_conversion_errors.py`と`origin-s68-full-50m-conversion-error-analysis-20261029.json`へ記録した。

同じ元cache・16k語彙・50M予算・batch16×accum4・LR3e-4・seed42のN33を次に開始した。
runは`artifacts/checkpoints/origin-n33-full-t1-50m-20261029-r1/`、session `96650`。
共有step8050まで162個の進捗点で、処理/target tokenの推移も同一だった。
`origin-n33-s68-sampling-schedule-check-20261029.json`に照合結果を保存した。
N33終了後に同じ品質評価で規模を比較し、その後の追加学習・正常な別綴り・許諾済み教師の直接活用を個別に検証する。
本節の変更後も、変換468/480等の元の合格条件は維持する。

今回の関連unit testはbatch3・診断集計4・文書分割2・operating point4・sampling4の計17件が成功した。
v2の実CPU追加学習・中間再開の完全一致、F32実GGUF往復、92 tensor比較も別に実施した。

## 文脈と補正ラベルの相関・独立したsampler条件

元trainのpendingが空の補正入力では、ctx空のemit正解割合が19.62%、ctx非空が47.42%だった。
正常valで同じ文書・raw・正解読みを持つctx有無6,865組を対応付けると、両方無変更6,792組、
ctx有りだけ誤変更54組、両方誤変更17組、ctx無しだけ誤変更2組だった。
相関であり原因の確定ではない。`artifacts/results/origin-s68-full-50m-context-bias-20261029.json`に記録した。

v2へ`--balance-repair-context`を追加した。観測入力のctx markerから空/非空を読み、
keepとemitの中で各状態の質量を半分ずつにする。action全体の重みとabstainは保持する。
元データの全pending例はctx空なので、ctx非空pendingの能力を確認したとは扱わない。
実cacheで読んだ各状態の件数は、生データ監査の件数と一致した。
追加cache・自前T0親・65/25/10とこのflagを使ったCPU 5,027 tokensの配線検査に続き、
step5再開の最終16 stepsで重み・履歴・optimizer・scheduler・乱数が完全一致した。
結果は`artifacts/results/origin-cached-v2-context-balanced-resume-20261029.json`。
実モデルの改善は未評価で、次段では同じ元cacheと同じ親からflag有無を別条件にする。

同条件N33の50,002,572 tokens学習も完了し、session96650 exit 0を確認した。
最終validation CEはconvert 0.39426、repair 0.32840、predict 0.35598。
これは正解率ではない。

同じ評価条件での最終品質比較（自動ラベルによる開発用診断）：

| 指標 | N33 | S68 |
|---|---:|---:|
| Q8固定CPU変換480問 | 131/480 | 142/480 |
| GPU層化診断・変換 | 93/172 | 105/172 |
| 同・先読みsuffix完全一致 | 52/86 | 50/86 |
| 同・誤打救済 | 108/340 | 116/340 |
| 同・正常誤変更 | 14/255 | 10/255 |
| 全calibration・誤打救済 | 3,303/9,979 | 3,527/9,979 |
| 全calibration・正常誤変更 | 160/17,556 | 120/17,556 |
| 全calibration・pendingモデルemit | 0/8,960 | 0/8,960 |

全calibrationの数値はしきい値0＋同じ観測側フィルター後。どちらも目標を同時に満たす候補はない。
N33のQ8は34,979,296 bytes、F16の74 tensorsはF32から期待する丸め値と一致した。
F32 GGUFは固定CPU HF参照256件の出力とtoken列が256/256一致し、session20536 exit 0を確認した。
Q8は同じ参照と254/256一致。正常例1件が誤emitから正解keepへ変わり、別の変換失敗例の表記も変わった。
変換25/44・先読み36/44・誤打救済29/84は維持、正常誤変更3/63→2/63。
厳密parityや無損失とは扱わない。session83305 exit 0はquality比較モードの終了。
CPU時間は他の診断と並行したため比較しない。結果は`origin-n33-full-50m-`で始まる各JSONへ保存した。
S68が全指標で優位とはいわず、先読み一致はN33が2件多い。v4優越は両方とも未達。

次のA1は同じ元cache・S68 T1最終重みから、keep/emit/abstainを65/25/10にして10M tokens追加学習する。
LRは1e-4、task配合・seed42・batch16×accum4は共通。新optimizerとstage schedulerを使う。
A2も同じ親から同じ条件で始め、context balancingだけを有効にする。A1の子からA2を作らない。
この比較で見るのはcontext balancingの影響で、A1とT1の差には追加学習・配合・LR変更も含まれる。
短語句cacheをここに混ぜない。A1 runは`artifacts/checkpoints/origin-s68-a1-action65-10m-20261029-r1/`。
両条件とも全calibrationと固定層化診断で評価し、正常誤変更だけ減って誤打救済が落ちた状態を成功と扱わない。

### A1の完了とA2の開始

A1はsession93598 exit 0。10,000,316処理tokens、2,062,176 target tokens、3,613 steps。
検証CEはrepair 0.27147、predict 0.34618、convert 0.31445へ下がったが、以下の品質を別に測った。

| 指標 | S68 T1 | A1 |
|---|---:|---:|
| Q8固定CPU480問 | 142/480 | 158/480（通常124/230、難問34/250） |
| GPU層化変換 | 105/172 | 112/172 |
| 同・先読みsuffix完全一致 | 50/86 | 51/86 |
| 同・誤打救済 | 116/340 | 133/340 |
| 同・正常誤変更 | 10/255 | 9/255 |
| 全calibration・誤打救済 | 3,527/9,979 | 3,815/9,979 |
| 全calibration・正常誤変更 | 120/17,556 | 136/17,556 |
| 全calibration・pendingモデルemit | 0/8,960 | 0/8,960 |

A1全量の変化提案精度pointは3,815/6,281=60.74%、誤打救済38.23%、正常誤変更0.7747%。
しきい値gridの目標同時達成候補はない。層化1,024例の正常誤変更だけを見ると1件減ったが、
同じ全calibrationでは16件増えており、正常保持を改善したと報告しない。
正常を多めに学習させただけで自動採用precisionを保証できるわけではない。
480問はv4の459/480を大幅に下回る。CPU時間は並行評価があったため比較対象外。
結果は`origin-s68-a1-action65-10m-`で始まるJSONとQ8モデルへ保存した。

A1の評価GPU処理が終了した後、A2をsession71967で開始した。
runは`artifacts/checkpoints/origin-s68-a2-action65-context50-10m-20261029-r1/`。
同じT1親・元cache・65/25/10・LR1e-4・seed42・batch16×accum4・10M処理token予算で、
`--balance-repair-context`だけを変更した。系列長分布も変わるため予定stepsは3,634で、A1の3,613とは異なる。
target token量と抽出例数も別に記録し、同じuniqueデータ露出数とは主張しない。

## ローカル教師の取得・隔離ランタイム

既存817行の採否と独立して、計画の外部教師活用を試す準備を進めた。
公式[Qwen3-4B-Instruct-2507のカード](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507/blob/cdbee75f17c01a7cc42f958dc650907174af0554/README.md)と
[LICENSE](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507/blob/cdbee75f17c01a7cc42f958dc650907174af0554/LICENSE)を確認し、
revision `cdbee75f17c01a7cc42f958dc650907174af0554`を固定した。Apache-2.0のモデル重みを別の教師候補として使い、
既存v4のGemini例文を教師経由で再利用しない。新しい世代全体に同じライセンスが適用されるとは推定しない。

`data/fetch_origin_teacher.py`で12ファイルを取得し、3分割のsafetensorsはすべて公式LFS SHA-256と一致した。
総パラメータは公式metadataで4,022,468,096。sourceとLICENSEは
`artifacts/models/origin-teacher-qwen3-4b-2507-cdbee75/`、取得session4021 exit 0。
元のstudentをこの重みから初期化したり、現在の学習データへ混ぜたりしていない。

既存b4846を保持し、現在のllama.cppソースからCPU教師用server/quantizerだけを
`artifacts/runtime/origin-teacher-llama-cpu-20261029-build/`へビルドした。
最初の通常sandbox実行はZERO_CHECKから進まなかったため、確認した自分のプロセスツリーだけを停止した。
同じ出力先でsandbox外実行を再試行しsession10217 exit 0。ソースの権限・パス・既存実行器は変更していない。
1342個のC/C++/header/CMake/LICENSEのhashは取得開始時と成功後で一致した。
初回取得は開始直後のビルドと一部重なったため、ビルド前に全件取得完了とは記録しない。
実行器の表示は0.7.0-dev / build0 / commit unknown。親Gitのgitlinkは作業treeの未変更証明には使わない。
実バイナリhashと条件は`artifacts/results/origin-teacher-runtime-build-manifest-20261029.json`に保存した。

公式重みのF16 GGUF変換はsession30875 exit 0で終了。量子化・生成probeは別工程とし、
生成形式だけ通っても読み・意味・学習効果を確認したとは扱わない。
元のv4 CPU比較へこの新しい実行器を流用しない。

Q4_K_M生成はsession92409 exit 0、2,497,279,072 bytes（F16は8,051,283,552 bytes）。
source・変換器Python・GGUF・実行器へのhashの結び付けを`teacher-export-manifest.json`へ保存した。
自作の生成指示4件だけをCPUで試す`probe_origin_teacher.py`を作り、Q4 probeはsession52169 exit 0。
すべてJSONを返したが、指定の配列/文字数を満たしたのは3/4件。自然さや一文の指示までこのvalidatorで保証しない。
生成はレビュー候補として保全し、学習へ投入していない。同一指示のF16 probeは別session62102で測る。
この16候補文の生成だけで、日本語教師としての優位や学生の改善を判断しない。
F16 probeもsession62102 exit 0で完了し、4応答すべてJSON、配列/文字数検査は2/4合格だった。
短すぎる文や長いパス表記があり、「庭の花を水やりした」のように日本語の修正が必要な候補もあった。
Q4の3/4との差で量子化の優位を判断しない。32候補文はすべて未採用のprobe出力として保全した。
新しい2本のPythonはAST/CLIを確認し、取得・変換・量子化・両精度の実HTTP生成まで実行した。

## 先読み正解の内訳と、計算量を減らす別trainer

`profile_origin_prediction_targets.py`で既存train/valの先読み正解を変更せず集計した。
trainのemit 294,671例中、句読点/空白だけが97,675例（約33%）。valは17,468例中5,770例。
UniDicの名詞・動詞等で非自立可能を除く自動proxy付きtargetはtrain74,286、val4,541例。
層化診断のsuffix86例中、句読点等だけ30例、内容語proxy付き25例。
前者の一致はT1/A1とも27/30、後者は3/25→6/25、全体は50/86→51/86。
短い補完にも打鍵削減の価値があり、自動品詞や文字数を人による有用性評価とは扱わない。
結果は`artifacts/results/origin-prediction-target-profile-20261029-r2.json`。
初版では英数字/かな漢字なしを句読点とまとめていたため、r2ではUnicodeの句読点/空白と記号等を分けた。

`origin_target_loss.py`はdecoderに全入力を通したまま、正解ラベルがある因果位置だけをlm_head/CEへ渡す。
元の例ごとに正規化したCEとEOS supervision、入力位置への間接勾配を保持する。
CPUの3テストで可変target長・padding・EOS・全パラメータ勾配・入力埋め込み勾配を検査した。
別entry point `train_origin_cached_v3.py`を作り、元trainerとv2は変更していない。
実span cache/T0/CPUで5,027 tokens学習し、step5再開後の重み・optimizer・scheduler・乱数・履歴が完全一致した。
`origin-cached-v3-target-only-resume-20261029.json`はPASS。
v2とv3を比べるとCPUでも最終重み最大差1.32e-6があり、方式間のbit一致とは主張しない。

S68の同じ学習行・同じ初期重みのGPU計算probeでは、元方式11,163→target-only13,672 tokens/s（1.225倍）。
peak割当2,349,113,856→2,016,310,272 bytes。BF16の初回勾配relative L2差0.00446、最大絶対差0.000362。
短い固定一様train標本による計算比較で、実運用task比の長期速度・学習精度・IME速度の証明ではない。
結果は`origin-s68-target-loss-gpu-profile-20261029.json`。既存のA1/A2/S1比較へ途中導入しない。

短語句追加S1はA1と同じT1親・65/25/10・LR1e-4・seed42・10M予算・v2を使い、
source-aligned span追加だけを変える条件を`origin-s68-s1-spans10m-prespec-20261029.json`へ事前記録した。
同じ処理token予算でもtarget数やunique露出数が一致するとは扱わない。

A2はsession71967 exit 0、10,000,376処理tokens、2,071,273 target tokens。
calibration初回session51198は9,056件の進捗後にhandleが失われ、最終JSONなし。
対象コマンドのプロセスが存在しないことを確認して同条件で再評価した。原因は未確定。

## A2の最終補正・変換評価

同じ全calibration36,495例、しきい値0＋観測側フィルターで、A2の誤打救済は3,695/9,979、
正常誤変更105/17,556、pendingモデルemit 0/8,960。変化提案精度3,695/6,001=61.57%、
救済37.03%、正常誤変更0.5981%。目標を同時に満たすgrid候補はない。
A1より正常誤変更が31件減る一方で救済も120件減った。T1との比較では正常誤変更120→105、救済3,527→3,695。
context balancingの単独比較対象は同じ親から学習したA1で、T1との差をその単独効果とは扱わない。

Q8の同じCPU480問は151/480（通常117/230、難問34/250）。A1の158/480より7件少なく、
T1の142/480より9件多い。v4の459/480には未達。session61196 exit 0。
calibration再評価session96564もexit 0で全件完了した。初回途中結果を足したり、二重計数したりしていない。
S1はA1を対照とする事前記録を維持し、良い指標だけで親・配合・方式を途中変更しない。
層化1,024例のA2は変換110/172、先読みsuffix50/86、誤打救済134/340、正常誤変更12/255、pending emit0/85。
全calibrationの率と、層化標本の少数差を混同しない。session66560 exit 0。

S1は事前記録の全bindingsを起動直前に再照合し、保存された引数配列をそのままsession49541で開始した。
runは`artifacts/checkpoints/origin-s68-s1-spans-action65-10m-20261029-r1/`。
GPUでのA2診断終了を確認してから開始し、target-only v3へ切り替えていない。

## S1の評価の自動継続

`training/run_origin_evaluation.ps1`で所有するOrigin runの評価を順次実行する経路を追加した。
指定PIDをmanifestと実コマンドで確認し、終了コード0・最終step・token予算・metrics・不変manifestを確認してから、
全calibration→operating point→1,024例→F16 export→Q8→固定CPU480問へ進む。
失敗した工程では停止し、引数・exit code・logをpipeline JSONへ記録する。自動採用承認は常にfalse。
PowerShell parseを確認し、session85783をS1のPID17572待機として開始した。
待機後の全工程を実行済みとは扱わない。これが評価の所有者なので同じ評価を別途起動しない。
状態は`artifacts/results/origin-s68-s1-spans-action65-10m-20261029-r1-eval-pipeline.json`。
既存の学習・実IME・固定比較用実行器・Git履歴は変更していない。

S1学習はsession49541 exit 0で完了し、pipelineも所有して待機したプロセスのexit 0を取得した。
4,008 steps、10,002,408処理tokens、1,513,500 target tokens。
A1は10,000,316/2,062,176だったので、処理token予算が近くてもtarget量は約27%少ない。
source追加に伴う系列分布の変化として記録し、同じtarget露出量とは報告しない。
pipelineは正常終了確認後にcalibrationへ自動遷移した。品質判定は全評価後に行う。

## S1の全評価と、T2の学習量拡大

pipeline session85783は全6工程exit 0で終了。S1のQ8は150/480（118/230、32/250）で、A1の158を下回った。
全calibrationは救済3,803/9,979、正常誤変更141/17,556、pending emit0/8,960。
A1の3,815/136に対して両方わずかに悪化し、目標同時達成point候補なし。
層化診断は変換100/172、先読みsuffix53/86、救済131/340、正常誤変更12/255、pending0/85。
先読みの数問差を全体優位とは扱わない。元の長いvalのCE悪化と合わせ、短語句追加を現時点の採用条件としない。
全結果は`origin-s68-s1-spans-action65-10m-20261029-r1-eval-`で始まる成果物へ保存した。

次のT2は正常誤変更が少なかったA2を親とし、元の全量cache・65/25/10・context50・LR1e-4・seed42・
batch16×accum4を保持して100M処理tokensを追加する。小さな配合変更の繰り返しから、言語・読みの学習量を増やす段階へ進む。
target-onlyのv3を使うため、追加学習・stage scheduler・計算kernelの変更を含み、単独施策の寄与とは主張しない。
数学的なCE・CPU全勾配・resumeの検査とBF16の差は前節の記録どおり。品質は改めて同じ評価で測る。
runは`artifacts/checkpoints/origin-s68-t2-full-context50-100m-20261029-r1/`、training session41767。
validationは1,000 stepsごと、optimizer checkpointは5,000 stepsごと。既存の比較結果・封印評価・合格条件は維持する。

## 未完了範囲

v4最高459/480超え、校正後の実誤補正率、先読みの人による妥当性、速度・容量、封印評価、
Windowsの打鍵・削除・カーソル・確定/取消は未達または未検証。
Windowsビルド・実IME適用・入力アプリ確認・インストーラー作成・commit/pushは未実施。
コード・記録はローカルの未コミット状態。
