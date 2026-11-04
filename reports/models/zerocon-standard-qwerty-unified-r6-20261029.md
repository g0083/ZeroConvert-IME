# Standard-QWERTY統合モデルR6の改善検証

2026-10-29。[改善案](zerocon-standard-qwerty-unified-improvement-plan-20261029.md)の継続実装。
**R6dの学習・F32/F16/Q8書き出し・開発評価を完了。固定Rustポリシーでは補正が改善したが、通常変換の退行が残るため研究版として保留。IME未適用。**
本書の成績は開発評価であり、封印テスト・実IMEの成功率ではない。

## 1. 入力形式とデータ

`training/v4_unified_contract.py`にversion 2のcompact形式を追加した。version 1の保存済みcodecは従来どおり読み込む。
文字列をJSON引用せず、control ID認識を無効にしたデータtoken列として渡す。欠損は`<missing>`、空文字は空の列で区別する。
タスクごとに必要なフィールドだけを使う。旧6000 IDと追加済み6042 IDまでを保持し、ID6043に`<missing>`を追加した6044語彙。
pathの単一backslash、空白、引用符、タグに見える文字列の往復と、旧codecの回帰を確認した。
これはモデルが任意のpathを正しく生成することとは別の確認である。

補正入力は、現行correctorの混在かな・英字の観測読みをそのまま使う比較条件を追加した。
旧データに元打鍵がないため、ローマ字を逆算して入力事実として補わない。
新しい合成rawがある場合は固定decoderから観測読みを作り、rawと来歴はsidecarへ保持する。
正解読み・正解表記は入力へ入れない。

同じ観測入力・同じ分割・同じ例を使い、repairの教師だけをひらがな読み／補正表記へ切り替えるデータを作成した。
元データは現行v3.2 correctorと同じ`corrector-v2-20261027`だが、かなの読みを検証できる共通部分に絞り、
正常なかな入力を保持する例を追加している。元の40,111行全件をそのまま学習したとは扱わない。

最初のr1では観測の矛盾を元グループごと除いた結果、正常例が455件に偏ったため学習に使わなかった。
r2では、評価に触れる元グループを除外した上で、実際に教師が矛盾する観測を除外し、正常例を補った。

| 共通データr2 | train | val |
|---|---:|---:|
| keep | 3,633 | 300 |
| emit | 30,677 | 2,435 |
| 合計 | 34,310 | 2,735 |

独立監査で、両条件のID・入力token列・分割の一致、教師の往復、元グループ／読み／表記の分離、既存評価キーとの分離を確認した。
後の統合データでは文脈も照合する追加監査で一致を検出し、対象文書を除く処理を追加した。失敗した中間データは保全し、監査合格前に統合学習へ使わない。

## 2. 補正専用の対照学習

両方とも元のStandard v4から開始し、R5からの継続にはしていない。
LoRA r16・alpha32・dropout0.05・c_attn/c_proj、LR2e-4、実効batch32、micro8、3epoch、max256。
FP32 master／BF16 autocast、旧6000 embedding/head行は固定し、新規行だけ別途学習する。
データ順は各epochで同じseedからshuffleし、同じ行を各1回学習した。

現行correctorに近いrecipeだが、compactタグ、動作ラベル、Standard基盤、旧行固定、per-example CE、
今回の共通部分抽出は異なる。旧Trainerのtoken平均CEを含めた完全再現や、モデルサイズだけの対照ではない。

checkpointは固定valの正常100件・修復100件を実生成し、形式違反、正常誤変更、修復正答の順で選択した。
既知の300問や480問をcheckpoint選択に使っていない。

| 条件 | 選択epoch／step | 選択時の修復完全一致 | 正常誤変更 | 形式違反 |
|---|---|---:|---:|---:|
| 読み出力 | 2／2,146 | 読み45/100 | 2/100 | 0 |
| 表記出力 | 3／3,219 | 表記38/100 | 2/100 | 0 |

上記の読み一致と表記一致は採点対象が違うため、45対38だけで優劣を決めない。
両runとも3epoch・3,219stepを完走したが、読み版の配布用診断重みは2epoch時点である。

## 3. 同じrawによる共通評価

R5のcorrector由来コホートと同じIDの100タイポ・100正常・100pendingを固定した。
rawは過去に生成した合成打鍵であり、実利用者の打鍵ログではない。既に見た開発例で、封印評価ではない。
各モデルへ渡す観測読みは同じ固定decoderで作る。現行correctorへはその観測読みを従来のPUA形式で渡す。

| 生モデル条件 | 正解の読みを確認 /100タイポ | 観測の読みを保持確認 /100正常 |
|---|---:|---:|
| 現行v3.2 XSmall Q4・CPU | 41 | 83 |
| R5 Q8・既存の同一入力生成記録 | 29 | 98 |
| 新しい読み出力・HF FP32 | 42 | 85 |
| 新しい読み出力・Q8 CPU | 42 | 85 |
| 新しい表記出力・HF FP32 | 52 | 85 |
| 新しい表記出力・Q8 CPU | 52 | 85 |
| R6c統合・HF FP32 | 46 | 98 |
| R6d統合・HF FP32 | 47 | 98 |
| R6d統合・Q8 CPU | 47 | 98 |

表記出力の読みは、参照表記との一致またはAZR2辞書で検証できる読み経路で自動照合する。
同じ読みの別表記も認める緩い採点であり、意味・文脈の妥当性を保証しない。
確認できなかった正常例をすべて実IMEの誤補正と数えない。現行の既知ルール・辞書採用制限・通常候補保護・個人学習は通していない。
HF FP32とGGUF Q4/Q8も混在しているため、学習方式だけの因果効果とは扱わない。

pending100件は共通ホストガードで推論を省略した。モデルが100件保留を学習した成績として報告しない。
学習との並行CPU測定もあり、速度比較は行わない。

## 4. 候補制約の初期比較

新規の単語自由生成に加え、隣接キー1置換・母音1挿入・隣接転置・1削除から候補を作る。
小文字英字1〜64キー、最大8読み、辞書順位、入力無変更を必ず保持する固定条件。
候補生成は正解を参照しない。読み出力モデルの生成を、keep／abstain／候補の読みからなるtoken trieへ制限した。

共通200入力中15件はhyphen等で未対応（タイポ7・正常8）。対応93タイポ中、
全編集候補に正解があるもの91、上位8読みへ残るもの80だった。
読み出力HFの正解は42→46/100、正常保持確認は85/100のままだった。
未対応ではkeep／abstainだけを選べる。候補被覆80件は最終正答80件を意味しない。

この小さな改善だけでは採用しない。候補生成の被覆と選択精度を分け、
CPU nativeでの制約付き推論・総遅延は未実装／未検証とする。

## 5. 統合試作の方針

共通読み照合では表記出力が有望だったため、表記出力版から段階統合する。
**repairが漢字交じり表記を返す研究契約**であり、従来計画のひらがな修復契約とは異なる。
実IMEへの採用には読み検証と候補・読みの受け渡しを合わせて変更する必要がある。
研究CLIは出力種別と検証要否を明示し、自動採用はfalseを維持する。

通常変換・先読みのreplay、正常入力・削除prefix・pending、元のcorrector観測例と新しいraw変異を合わせる。
表記を確実に対応付けられないMagpie修復emitはsurface条件では除外し、keep・abstainと他taskは残す。
source groupと新しい観測入力で合流を監査し、valへ触れるtrain文書は除く。

まず変換40%・補正60%・先読み0のbridgeを作り、次に変換35%・補正50%・先読み15%を試した。
bridgeの補正内はkeep55%・emit40%・abstain5%、次段階はkeep40%・emit55%・abstain5%。
これは学習カリキュラムであり、実タイポ発生率ではない。
独立した元Standard v4を変換教師にし、補正や先読みへ旧教師を強制しない。
生成validationには独立したpath等のliteral canaryも加え、正常保持・変換・補正を合わせて選択する。

## 6. 段階統合の失敗と固定教師の修正

| 条件 | 旧変換 /480 | 新変換 /480 | 共通タイポの読み確認 /100 | 正常保持確認 /100 |
|---|---:|---:|---:|---:|
| 補正専用surface HF | 369 | 1 | 52 | 85 |
| R6a bridge HF | 423 | 391 | 35 | 100 |
| R6b rebase＋3task HF | 449 | 432 | 18 | 99 |

R6aは800stepを完走し、生成validationで選択した600stepを使用した。
R6bでは元v4に対するR6aのtransformer差分を固定係数0.25へ縮め、学習済み追加embedding行を保持してから3taskを学習した。
1,000step・3,837,127処理tokensを完走したが、補正が落ちたため本線には採用しない。
係数0.25は480問で探索して選んだ値ではなく、回復のための固定初期化案だった。

調査で、追加したplain-LoRA条件は学生側のbase Dropout 0.1を残す一方、凍結教師はDropoutなしになっていた。
同じ元v4重み・LoRA更新0の対照で、学生Dropoutありのanchor lossは7.24〜7.70、
なしでは約1.05e-7だった。重みの差と無関係な確率的差を罰していたことを確認した。
これだけで各退行の全原因を特定したとはしないが、知識維持の目的に不適切な設定なので修正した。
既存R5のDoRA条件はもともとDropoutを全て無効化しており、この不整合を含まない。

現在の統合trainerはadapter形式にかかわらず全学生Dropoutを無効化し、非0の要求は拒否する。
同じ重みのanchorが不一致にならない回帰テストを追加した。補正専用の対照trainerは固定教師を使わず、保存した専用recipeのまま維持する。
旧runのsource-codeには当時の実行コードを保持した。修正後のソースで旧Dropout条件をそのまま再実行できるとは扱わない。

診断：`artifacts/results/v4-anchor-dropout-20261029-r2.json`。
旧診断の実行ソースも`artifacts/results/v4-anchor-dropout-20261029-source.py`へ保存した。

## 7. R6c：修正したanchorと補正正例の追加学習

R6bは親へ混ぜず、R6aの選択HFへ戻した。
既存R5で使ったrank32 DoRA・全Dropout0・変換KL6・repair先頭action重み0.10を起点に、
変換10%・補正85%・先読み5%、補正内keep30%・emit65%・abstain5%で学習した。
旧corrector観測例と新しい合成rawの修復正例をそれぞれ半分使い、正常保持・未確定もreplayする。
recipe・サンプル配分・学習量をまとめて変える段階実験であり、単独施策の因果比較ではない。

独立した乱数seedからpath・URL・空白を含む英字列・識別子のidentity教師1,200例も追加した。
検証文字列と既知評価を教師として流用していない。valは変更せず、通常変換のtagged anchor poolだけで学習する。
legacy空白置換や旧教師はこのlossless copy例には使わない。

GPU事前計測はmicro8/16が合格し、32は無制限paddingでOOM、48は予算超過予測でskipとなった。
選択micro16、実効96、padding後3,072token以内へ分割、2,000step、LR5e-5で開始した。
追加生成validationには現行valと独立に作ったliteral canaryを使い、480問でcheckpointを選ばない。

2,000step・8,426,731処理tokensを完走し、生成validationで400stepを選択した。
HFの旧変換445/480、新変換439/480、先読み完全一致61/100、共通タイポの読み確認46/100。
後半は正常誤変更が増えており、最終stepの重みを採用しなかった。

## 8. R6d：通常変換の再統合と最終成果物

R6cの選択400stepから、変換50%・補正35%・先読み15%、補正内keep50%・emit45%・abstain5%へ変更した。
DoRA r32・Dropout0、LR3e-5、KL1.2、repair先頭action重み0.25、実効64・micro16・token予算3,072・max512。
設定は`training/configs/v4_surface_consolidation.json`。corrector由来の観測例と合成raw例を継続して使った。

最終データは`artifacts/datasets/v4-unified-compact-surface-literals-20261029-r1/`。
manifest SHA256は`ce140be218c6805915ed821645e8e0686d502b41550bf2ea58db30fc81d85ab2`。
分離・矛盾・教師往復の独立監査に合格したデータへliteral replay 1,200例を追加したもので、元corrector全件の無加工再現ではない。

checkpoint選択はvalの429例。anchor85例と通常変換30例、独立literal canary4例、補正250例、先読み60例を使う。
親HFが正解した変換の取りこぼしを選択指標に追加し、親出力は採点側だけに保持した。
既知の480問・300問をcheckpoint選択へ使っていないが、反復実験後の開発評価であり封印評価ではない。

1,000step・4,247,675処理tokensを完走し、選択は800step。
その時点までの学習量は3,398,009処理tokens、707,153教師tokens、51,200系列、重複を除くtoken化例36,356件。
前段R6a/R6cの学習量はこの値に含めない。サンプラーの再生監査はPASS。

| 通常変換・先読み | 親Standard v4 Q8 | R6d HF | R6d Q8 |
|---|---:|---:|---:|
| 旧形式変換 /480 | 459 | 450 | 450 |
| compact形式変換 /480 | — | 450 | 449 |
| 先読み完全一致 /100 | — | 65 | 65 |

Q8旧形式は親に対し改善2件・悪化11件、新形式は改善5件・悪化15件。
許容表記差も含む旧採点器だが、`2026年→2028年`、`docker compose up -d`の`up`欠落、
「今日は医者に行く→今日歯医者に行く」など、実質的な退行が残る。変換性能を維持できたとは判断しない。
一方、R5で二重化していた`C:\Users\Tomoki\Models`のbackslashは、新形式Q8の当該例で単一のまま出力できた。

選択HFを親側へ0.95／0.85に縮める診断もvalだけで試した。
0.85は親正解の取りこぼしを1件減らす一方、補正正答も1件減ったため、事前の「他指標を悪化させない」条件により不採用。
派生重みは保存・採用せず、最終R6dは選択HFのまま。

最終バンドル：`artifacts/models/zerocon-standard-qwerty-unified-surface-20261029-r6d/`。
F32／F16／Q8、codec・固定decoder・来歴・評価証拠・研究カードを同梱する。
Q8は103,938,656 bytes、SHA256は`dcc83b90c8fcaf0c66a18a0ea20b5d2aafb053a2f0b9593692fd8185cf545dcb`。
HF SHA256は`3bbd3b39f55889ef83bbf42d0af06ee9bc818e3cda6b05b10c90bc543740c5c7`。

F32／F16とも149 tensorのcast後一致、文字列6例の整数ID往復、greedy生成6例でHFとのtoken一致を確認した。
native文字列tokenizerは不一致のため使用不可。**ホストcodecから整数ID配列を渡す契約が必須**である。
HFとF16経由Q8は1,645出力中13件が異なった。全入力の数値同一性や量子化だけの因果効果は主張しない。

## 9. 固定Rust補正ポリシーでの300問

現行v3.2 XSmall corrector Q4とR6d Q8を、同じ255タイポ・45正常、CPU4 thread、同じRust評価器で測定した。
評価器は現行correctorの既存記録と同じSHA256の保存済み実行物を使用した。
通常変換を親Standard v4 Q8に固定した比較では、ケース・評価器・通常モデルのhashと、全300件の通常出力の一致を確認した。

| 条件 | 正しい補正提案 /255 | 誤提案 /255 | 正常への誤提案 /45 | 誤表記の自動適用判定 |
|---|---:|---:|---:|---:|
| 現行v3.2・通常変換固定 | 71 | 49 | 5 | 0 |
| R6d・通常変換固定 | 83 | 11 | 0 | 0 |
| R6d・通常変換も同じ重み | 83 | 11 | 0 | 0 |

全条件でcrash・失敗した推論要求は0件。同じ重みの条件は、convertとrepairを物理的にも同じ推論プロセスで処理した。
通常変換または補正に正解が存在した件数は順に76／86／92件だった。
上記は固定した許容表記での主採点。読み一致も認める副採点では正しい提案が75／84／84件となるが、主採点を置き換えない。
過去の70件という現行記録はVulkan・別の通常モデルであり、この71件との違いをモデル更新の効果とは扱わない。

初期adapterには、上流の既知処理で浮遊英字から読みが既に修復された後、モデルがkeepを返すと、その読みをかなのまま最終表記にする不具合があった。
R6cの旧adapter記録には表記不一致の自動適用判定2件があり、両方とも読みは正しかった。
adapterを修正し、元の観測読みを保持するkeepはfallbackを抑止し、上流で修復済みの読みを維持するkeepは通常変換に渡すようにした。
修正を回帰テストに固定してからR6dを再評価した。旧R6cの値80件は接続不具合を含む診断記録として残し、最終比較に使わない。

この改善はモデルと表記repair用adapterを組み合わせた結果で、重み単独の効果の分離ではない。
正常45件の0件で誤補正率0.1%以下や自動適用precisionを証明できない。Swift・TSF・編集操作・個人学習・実アプリは含まない。
学習並行時の評価も含むため、旧記録との遅延比較を行わない。

## 10. 残る失敗と次の優先順位

単体CLIの`repair --raw argiatou`はQ8で`emit: アルバム`だった。
ポリシーを通した成績だけで自由生成が信頼できるとは判断しない。CLIは`automatic_adoption=false`、`reading_verification_required=true`を返す。

次は通常変換を保護する構造を優先する。共有backboneを維持しつつ、repair用adapter／task専用層の切替や、
変換アンカーの勾配制約を比較し、数字・英字・コマンドの保持を検証する。
候補制約は今回の読み出力対照では42→46件に留まったため、表記repairの読み検証と候補選択を別途測る。
変換退行の解消後に、独立した評価集合、正常入力を増やした校正、実IMEの入力・削除・移動・確定／取消・遅延確認へ進む。
現段階のR6dは研究成果物であり、既存モデルの差替え候補としては採用しない。

## 11. 保存先と確認範囲

- 対照データ：`artifacts/datasets/v4-repair-comparison-20261029-r2/`
- 読み版：`artifacts/checkpoints/v4-repair-reading-20261029-r1/`
- 表記版：`artifacts/checkpoints/v4-repair-surface-20261029-r1/`
- 共通入力：`artifacts/results/v4-repair-common-fixture-20261029.json`
- 比較結果：`artifacts/results/v4-repair-common-*.json`
- 候補集合：`artifacts/results/v4-repair-common-candidates-20261029.json`
- データ作成・独立監査：`data/build_v4_repair_comparison.py`、`data/audit_v4_repair_comparison.py`
- 学習・生成選択：`training/train_v4_repair_comparison.py`、`training/v4_generation_selection.py`

`python scripts/run.py`で以下の回帰を実行し、計33件が成功した（実行Pythonは`.venv_train/Scripts/python.exe`）。

- `training/test_v4_unified.py`：13件（統合・Dropout・literal teacher）
- `training/test_v4_compact_contract.py`：6件（codec・教師分離）
- `benchmarks/research/test_v4_repair_candidates.py`：5件（候補・trie・辞書）
- `benchmarks/research/test_v4_surface_pipeline_adapter.py`：3件（接続・keepの修復済み読み）
- `data/test_v4_corrector_replay.py`：3件
- `training/test_v4_repair_edits.py`：3件

ログは`artifacts/logs/`、個別JSONは`artifacts/results/`、主要証拠の複製は最終バンドルの`evidence/`。
今回の編集対象は学習・評価ソース、設定recipe、文書。Windows/Android製品ソース・設定・モデルカタログ・既存重みは今回の作業では変更していない。
製品ビルド、IME適用、入力アプリでの確認、インストーラー、commit/pushは未実施。共有作業ツリーの既存差分は保持した。
