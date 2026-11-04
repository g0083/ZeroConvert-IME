# Standard-QWERTYの3タスク統合モデル — 2026-10-29

状態：**Standard-QWERTYの学習済み研究用初版R5を、HF・F32/F16 GGUF・Q8で作成した。品質基準は未達で、実IMEには未適用。**
correctorのデータ・失敗知見を継承し、同じ合成誤入力100件の読み救済は初回1件からHF42件／Q8 41件になった。
別のcorrectorコホートには正常誤変更2/100があり、通常変換の退行と保護pathの変形も残る。
本報告は研究結果であり、製品品質・公開配布条件の達成を意味しない。

成果物：[モデルカード・バンドル](../../artifacts/models/zerocon-standard-qwerty-unified-20261029-r5/README.md)、
[Q8 GGUF](../../artifacts/models/zerocon-standard-qwerty-unified-20261029-r5/f16/standard-qwerty-unified-q8_0.gguf)。

## 親と互換性

- 親は`artifacts/checkpoints/standard-v4-nano-transfer-merged/`。12層GPT-2、幅768、学習位置上限1024。
- 149 tensorについてHF→既存F16の丸め一致を確認し、既存量子化manifestと固定CPU Q8の459/480へ対応付けた。
- 親の来歴には上流量子化GGUFからの抽出を含む。元の上流非量子化重みを復元したとは扱わない。
- 初回codecは元6000 IDを保持し、不足するByteLevel文字24個・制御18個を末尾へ追加した6042語彙。R3以降はさらに`<emit>`を追加した6043語彙。新しいtoken行をPEFTとは別に保存する。
- 初回P2のHF→F32/F16は149 tensorの精度cast後一致、6プローブのgreedy token一致を確認した。
- 固定b4846実行器の文字列tokenizeには追加空白とliteral特殊タグの扱いで差がある。新codecが作る整数ID配列で呼ぶ契約に限定する。

証跡：`artifacts/results/v4-unified-standard-parent-20261029-r1.json`、
`v4-unified-standard-p2-parity-{f32,f16}-20261029-r1.json`。
`PASS_INTEGER_ID_CONTRACT`と`FAIL_DO_NOT_USE_STRING_PROMPTS`は別フィールドで記録している。

## 初回P2

承認済みMagpie由来の既存分割からtrain20,813元文／3,566文書、val1,285元文／250文書を選び、v4の辞書的アンカーを保持した。
自動読み・合成誤打を含む。評価・封印文書は学習・語彙追加・校正へ入れていない。
分割・観測の矛盾を除外し、新codecでのlossless往復・長さを監査した。

run：`artifacts/checkpoints/zerocon-v4-unified-standard-romaji-p2-20261029-r1/`。
rank32 DoRA、BF16 autocast／FP32 master、LR5e-5、micro8×累積12、1,000step。
convert60／repair25／predict15、repair内keep65／emit25／abstain10。
通常変換には独立した凍結v4のtarget相対位置KLを使用し、repair/predictへ旧教師を強制しない。
旧embedding/headの6000行は更新しない。

処理9,767,602 tokens、target1,501,643 tokens、サンプル96,000系列、異なるtoken化例57,047。
これらを独立元文数と数えない。学習約1,155秒、検証macro CEで選択した1,000stepを固定した。

### HF FP32の実生成

480問は既存の許容解を含む採点器、他は固定seedの開発validation標本。

| 項目 | 結果 |
|---|---:|
| 旧形式の通常変換 | 458/480 |
| 新タグ形式の通常変換 | 456/480 |
| 新規変換標本の完全一致 | 83/100 |
| 正常入力のkeep | 100/100 |
| 合成誤入力の読み救済 | **1/100** |
| pendingのabstain | 100/100 |
| 先読みsuffix完全一致 | 61/100 |
| 完了文のabstain | 100/100 |
| 句読点等だけの先読み完全一致 | 35/36 |
| 英数字・かな漢字等4文字以上の先読み完全一致 | 18/43 |

修復は1件だけで、損失が下がっても機能品質は未成立だった。正常保持だけで成功としない。
480問も親Q8の459を上回らず、通常変換を維持できたという最終判定にはしない。
HFと親Q8の差は精度形式も異なり、量子化後の同条件比較が別途必要。

証跡：`artifacts/results/v4-unified-standard-p2-hf-20261029-r1.json`、
`v4-unified-standard-p2-sampling-20261029-r1.json`。

## correctorの継承

**追記：以下で再利用したデータは現行v3.2 correctorも使った`corrector-v2-20261027`だが、比較文書の26/255は旧v3.1系XSmallの成績。現行配置モデルはv3.2 XSmall（SHA-256 `dd87dbdc1a04cb4391f8429e6a1addc3c65d824996dd465e96803a5c1411fe2e`）で、`artifacts/results/corrector-v32-xsmall-300.json`の補正パイプライン評価は70/255。通常モデル・実行backend等が異なるため、本試作との直接比較は未実施。最新条件と次の比較案は[改善案](zerocon-standard-qwerty-unified-improvement-plan-20261029.md)を参照。**

ユーザーの指示に従い、[corrector比較](../typo-assist/corrector-size-comparison-20261027.md)と
[語句多様化・読み補正分離の失敗分析](../typo-assist/corrector-ablation-20261027.md)を参照した。
読み出力へ変更するだけではコピーが残り、辞書語を半数混ぜる条件では悪化したという知見を使う。
旧correctorのモデル出力を無条件の教師にはしていない。

`corrector-v2-20261027`から、かなだけで確認できる正解読み・文脈・元グループを再利用する。
旧誤入力はかな化済みで元打鍵がないため、ローマ字へ逆変換して実打鍵とは扱わない。
固定表で確認した正規ローマ字に、correctorの隣接キー・母音脱落・転置の生成器を適用し、新しい合成rawを保持する。
正当な別綴り、完結した削除prefix、pendingも分け、FlickノイズをQWERTYタグへ流用しない。

現行の評価5集合へ接する旧correctorの4元グループを派生ごと除外した。
元のvalidationコホートを保持したまま、同一観測の矛盾とtrain／valの文書・元文・読み・観測の衝突を再除外し、監査PASSを得た。

| corrector由来の追加例 | train | val |
|---|---:|---:|
| keep | 5,366 | 647 |
| 修復読みemit | 19,393 | 2,374 |
| pending abstain | 2,325 | 285 |

合成例の派生数を独立した元文と数えない。統合後trainは22,113元文／4,795文書、valは1,450元文／397文書。
corrector由来データは今回の指示に基づくローカル研究用途として来歴を保存する。公開配布の条件を新たに承認したとは扱わない。

### 追加学習の変更

長いemitを平均CEだけで扱うと、先頭を読みへ切り替える判定が薄まり、2tokenのkeepが有利になる。
repairだけ`0.35 * target平均CE + 0.65 * 最初のtarget token CE`に変更した。
repair内の各actionの半分をcorrector由来にし、task比とkeep65/emit25/abstain10は保持する。
データと損失を同時に変更するパッケージであり、単独要因の因果比較とはしない。

run：`artifacts/checkpoints/zerocon-v4-unified-standard-romaji-corrector-20261029-r2/`。
初回P2のHFから追加学習し、凍結教師は元のStandard v4。rank32、LR3e-5、micro8×累積12、600step、KL係数1.0。
途中100stepの20件ずつの診断では、救済1/20、正常誤変更1/20、pending abstain20/20。
小さな途中診断だけでは改善・安全性の判定に使わない。

600step終了後も同じ開発コホートの救済は1/100、正常誤変更2/100だった。
corrector由来の別標本でも救済0/100、正常誤変更2/100で、データ追加と先頭文字の重み変更だけでは不十分だった。
旧形式変換457/480、新形式456/480、先読み61/100。いずれもこの候補を製品へ採用する根拠にはしない。

### 観測読みと明示actionによる再試作

R3ではraw/pendingを保持し、その入力だけから固定表で復号した観測読みを全repair例へ一律に添える。
正解読みは使わず、入力側から`reading`を注入する経路も許さない。pendingはrawの末尾と一致を要求し、
pendingを除いた部分を復号する。未解決の英字は観測中に残す。これはTSF全状態の再現ではない。

repairの内部出力を`<keep>`／`<abstain>`／`<emit>修復読み`へ分ける。
読みの最初の文字とkeepを直接競争させる形式から、明示actionを先に出す形式へ変更する。
外部の意味は引き続きkeep／abstain／ひらがなの修復読みであり、モデルは3タスクで同じ重みを使う。

旧6042 IDを保持し、`<emit>`をID6042へ追加した6043語彙。固定表のJSON・MIT表示もcodecに同梱する。
元文・分割・ラベルのJSONLはR2とバイト一致を保ち、新codecへ再束縛して独立監査した。
観測読みで系列が長くなるため最大512とし、microbatchのpadding後token数を1536以内へ分割する。
サンプルごとの勾配重みは実効batch96に対する件数比で保ち、サンプルを捨てない。

run：`artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-observed-20261029-r3/`。
R2からrank32 DoRA、LR5e-5、KL1.0、repair先頭action重み0.65、600step。
この再試作も入力契約と出力契約を同時に変える条件であり、単独効果とは主張しない。

R3終了後の同一100件では読み救済10/100、正常誤変更0/100、pending abstain100/100。
正常入力はkeep95件、入力と同じ読みのemit4件、形式違反1件だった。正常誤変更0を正常keep100と同一視しない。
変換は旧457/480・新456/480、先読み61/100。修復のemitは77件あり、修復開始より本文の誤コピーが残ると判断した。

### R4：変更箇所への追加損失

correctorの過去分析にある「読み出力化だけでは誤入力コピーが残る」を、教師あり損失へ反映する。
観測読みと学習正解のtoken列を比較し、相違位置だけに追加CEと誤コピー対照marginを計算する。
削除では次の正しいtoken、末尾削除ではEOSを対象にする。正しいコピー・keep・abstain・convert・predictは対象外。
goldを使うのは学習／検証のラベル計算だけであり、prompt・推論・採用判定へ渡さない。

追加損失は、各誤修復例の変更位置における`CE + 0.25 * relu(2 - (gold logit - wrong-copy logit))`。
例内で平均し、batchの全件数で正規化して係数1.0で加える。元のtask/actionサンプリングは保持するが、勾配寄与は変わる。
SequenceMatcherのBPE整列は操作上のproxyであり、唯一の言語的誤り位置を保証するものではない。

run：`artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-editfocus-20261029-r4/`。
R3と同じcodec・同じデータで、R3の選択済みHFから600step追加学習する。
旧形式の回帰、正常誤変更、先読みを引き続き別採点し、変更箇所のloss改善だけで成功としない。

R4の実生成では救済4/100・正常誤変更3/100へ悪化したため不採用。
旧形式変換459/480、新形式457/480、先読み60/100だった。変更箇所のloss低下を機能改善へ読み替えない。

### R5：修復本文を学習する段階カリキュラム

R3を親へ戻し、修復正例を増やした段階学習を行う。
従来の600stepではcorrector由来のemit実サンプルは約1,800件であり、用意した19,393例全体を学習したとは扱えない。
サンプリングをconvert10／repair85／predict5、repair内keep30／emit65／abstain5へ変更する。
これは実誤入力の出現頻度ではなく、研究用の学習カリキュラム。

全体の修復emitは55.25%、正常keepは25.5%。単に正常例を減らす変更にはしない。
repair先頭action重みを0.1へ下げ、本文CEの比重を0.9へ戻す。追加edit損失は使用しない。
通常変換は独立v4教師KL係数6.0で維持する。単純なサンプリング確率との積は従来の60%×1.0と同じだが、
勾配分散や学習の同一性を保証するものではない。

run：`artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-repair-bootstrap-20261029-r5/`。
rank32、LR5e-5、1,000step、実効96、同じ6043 codec・同じデータ。
配合は`training/configs/v4_qwerty_repair_bootstrap.json`とrun内の`mixture.json`に保存する。
失敗したR4は親へ混ぜず、全候補と結果は保全する。

## 最終R5の結果と成果物

runは1,000step、9,814,509処理tokens、1,026,281 target tokensまで完走した。
ただし出力に選んだ重みはvalidationで選択した**600step**。
選択重みは57,600系列・5,890,397処理tokensの時点であり、後半400stepの更新を含めた重みとは報告しない。
run全体ではcorrector由来のemitを26,530回サンプルした。この回数も選択checkpointまでの回数とは区別する。
最後のoptimizer状態と選択済みHF・adapter・新token行は別々に保持した。

### 同じ固定コホート

| 指標 | R1 HF | R3 HF | R5 HF | R5 Q8 |
|---|---:|---:|---:|---:|
| 旧形式の変換 /480 | 458 | 457 | 457 | 457 |
| 新形式の変換 /480 | 456 | 456 | 457 | 456 |
| 合成誤入力の読み救済 /100 | 1 | 10 | 42 | 41 |
| 正常誤変更 /100 | 0 | 0 | 0 | 0 |
| pending abstain /100 | 100 | 100 | 100 | 100 |
| 先読みsuffix完全一致 /100 | 61 | 61 | 62 | 61 |
| 完了文abstain /100 | 100 | 100 | 100 | 100 |

R5の正常100件はkeep97・同じ読みのemit3だった。keep100という意味ではない。
別のcorrector由来コホートは、HFで救済31/100・正常誤変更2/100、Q8で救済29/100・正常誤変更2/100。
両方のコホートでpending abstainは100/100。異なる元分割の合成例を実誤入力として数えない。
旧correctorの300問ベンチマークとは入力状態・コホート・採点対象が異なり、直接の優越比較をしない。

親Q8の459/480に対し、新Q8の旧形式は改善0・喪失2、新形式は改善4・喪失7。
親が正答していたpathで、**新形式はbackslashを重ねる退行**が確認された。
例：`C:\Users\Tomoki\Models`が`C:\\Users\\Tomoki\\Models`になる。
したがって「通常変換を維持して機能追加できた」「保護文字の重大破壊0」「v4全面超越」はいずれも未達。
読み救済90%、自動補正precision99.5%、正常誤変更率の上限0.1%、封印・実IMEの条件も達成したとは扱わない。

先読みのQ8完全一致61/100には、句読点等だけ33/36、英数字・かな漢字等4文字以上20/43を含む。
人による妥当性・打鍵削減・複数許容解の評価ではない。

### 精度形式・CPU・API

最終HFは`7f22d90a1b5d720a6dae175ca83e5deb704d03bc782f5eafb0a8344245b58413`。
Q8は103,937,056 bytes（約99.1 MiB）、SHA-256
`002f006118d01c1a77a61ec74cd7bc0c48556fb3f4baeb0014fb098cd29504c3`。
F32/F16とも149 tensorのcast一致、5文字列の整数ID往復、6プローブのgreedy token一致を確認した。
全入力での数値同一性を証明したとは扱わない。
HF→F16→Q8の主1,645例では出力差14件。F16丸めとQ8量子化を単独に分離した差ではない。

固定b4846・CPU4threads・GPU層0・context2048・temperature0・seed42・cache無効・最大96tokensで生成した。
ただし新評価器は整数prompt/return_tokens、従来の固定比較器は文字列prompt/stop文字列を使うため、CPU時間を直接同一視しない。
raw結果の`timing_comparable_to_v4=true`は学習非並行だけを検査した不十分なflagであり、
`v4-unified-r5-evidence/verification.json`と最終比較JSONで**速度比較不可**と補正した。
元の結果と評価ソースは保全し、品質の件数は変えていない。

同じ従来API・同じ32問の現環境診断では、親Q8と新Q8はともに30/32。
平均96.58→88.68ms、p95 124.77→112.02msだった。小標本の診断であり、25%短縮の合格・モデル単体の因果的速度差とは主張しない。
新API全480の観測p95、補正単体p95、修復→変換の総遅延、実IME総応答は分けて扱う。

### 保存先と確認範囲

- HF：`artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-repair-bootstrap-20261029-r5/hf/`
- 研究用バンドル：`artifacts/models/zerocon-standard-qwerty-unified-20261029-r5/`
- 主評価：`artifacts/results/v4-unified-standard-bootstrap-{hf,q8}-20261029-r5.json`
- corrector評価：同prefixの`-corrector-{hf,q8}-20261029-r5.json`
- 最終比較：`v4-unified-standard-bootstrap-comparison-20261029-r5-final.json`
- 元評価ソース・評価hash・速度比較の補正：`artifacts/results/v4-unified-r5-evidence/`
- モデル内：`MODEL_MANIFEST.json`、各precisionのexport/quantization manifest、codec・固定表・MIT表示・来歴・source snapshot。

17件の対象回帰テスト（統合11、corrector継承3、edit整列3）が成功。
実行コマンドと結果：

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/test_v4_unified.py          # 11 tests OK
.\.venv_train\Scripts\python.exe scripts/run.py data/test_v4_corrector_replay.py       # 3 tests OK
.\.venv_train\Scripts\python.exe scripts/run.py training/test_v4_repair_edits.py        # 3 tests OK
```

native Q8で同梱codecを使う単発CLIの実行も確認した。ただし`argiatou`は`あるげあとう`と誤修復し、
`automatic_adoption=false`だった。このprobeも未解決例として保持する。
ソース編集・学習・GGUF/Q8作成・開発評価までで、実IME反映・入力アプリ確認・Android・installer・commit/pushは行っていない。
旧重み・旧実行環境・無関係な変更は保持した。

## 実測設定と作業範囲

RTX 4070 12GBで、同じ実効batch192の100 microstepを比較した。
batch4は約4,088 tokens/s、8は約6,326、16は約2,578。16ではVRAM使用量が上限近くまで増え、遅くなった。
32/48/64は前の実測からメモリ予算超過を予測してスキップし、実測値とは報告しない。
実学習は別途固定した実効batch96、micro8。95%／170WというP3/P4の目標を達成済みとは扱わず、P2候補とする。

回帰テスト8件とcorrector継承テスト3件が成功。旧ID・literal・空白・絵文字、target投影の損失／勾配、
独立教師の凍結、旧embedding行の保持、保存再開の次step一致、先頭判定の重み、rawの由来を確認した。

実IMEへの適用、入力アプリでの打鍵確認、Android反映、インストーラー、commit／pushは未実施。
モデル・重み・生成データ・ログは既存どおりGit対象外。通常変換・修復→変換の合計遅延、
実誤入力、正常10,000件、校正・封印評価を含む最終条件は未達／未検証のまま残す。

再現手順：[v4統合モデル学習手順](../../docs/v4-unified-training.md)。
