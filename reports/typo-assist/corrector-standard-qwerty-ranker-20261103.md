# Standard v4によるQWERTY候補順位付け（2026-11-03）

## 実施範囲

Standard v4を基盤に、物理キー候補の順位付けと元入力の保持を学習する初回pilotを実施。
旧ASCII編集命令の生成学習とは異なり、推論では固定した読み候補の尤度を採点する。
評価の分離、ordered-eventの実DLL再生、実打鍵収集画面、学習、F16/Q8出力、CPU評価を用意した。
この記録は実行後に結果を追記する実験記録であり、製品適用の完了報告ではない。

## 先に固定した条件

- 親：`artifacts/checkpoints/standard-v4-nano-transfer-merged`。既存統合R6/R7の継続ではない。
- 学習データ：既存QWERTY r4から3,072例・626元グループ、validation384例・64グループを抽出。
- train内訳：修復1,040、保持992、待機1,040。正解読みを候補が含む修復は1,009。
- validation：修復137、保持124、待機123。修復の候補到達数134。
- rank32 DoRA、alpha64、Attention/MLP、LR3e-5、3epoch、seed42、FP32親＋BF16演算。
- 1更新8観測×4候補。正常保持は2回。正解を候補へ注入せず、未到達31例を学習から除外。
- 状態保護等で候補比較が成立しない1,395行も学習対象外。実効2,313観測反復/epoch、290更新/epoch。
- 読み＋EOSの平均対数尤度に対しlistwise CE、正解token CEを0.1加える。推論は同じ平均尤度。
- validationで保持誤提案0・待機0を満たすmarginからF0.5最大を選ぶ。CPU選択では同点時に誤提案が少ない方を優先する。
- checkpointはHF validationだけで選択。Q8を主評価とし、testを見てepoch・marginを変更しない。

## 新しい文章とイベント評価

自然な文章24元グループと英数記号8元グループを新たに作成。
devは69例・9グループ（修復30/保持33/待機6）、testは202例・23グループ（修復90/保持94/待機18）。
文章・固有名詞・ローマ字別綴り・正常prefix・削除・左右カーソル・未確定子音・隣接/脱落/重複/母音抜け/転置を含む。
保存したイベントをAppendText/RemoveText/MoveCursorへ順に渡し、raw・読み・カーソルの全snapshotを保存する。

これは**著者が作成した文章と合成イベント**。実ユーザーの打鍵でも独立人手判定でもなく、実用上の最終試験とはしない。
testのモデル応答を見る前に、生成監査で同じ読みに残る削除例2件を発見し、別の実際に読みが変わる削除へ修正した。
修正前r1は不採用、修正後`standard-qwerty-ranker-prospective-20261103-r2`を固定。
devは修正前後でhashが一致したため、親モデルの開発採点は検証の上再利用してlockを作り直した。
修正前testのモデル採点は行っていない。

監査PASS：train/val/dev/testの元グループ・観測読み・期待表記の衝突0、旧評価との一致0。
候補の変更キー経路はtrain34,443、val4,170、dev900、test2,785件をnativeで全検証した。
正常保持のうち複数候補が実際に競合するのはval78、dev18、test53例。
残りは入力状態や未対応文字による保護を含み、全てをモデルの正常保持能力へ加算しない。

## 学習・モデル

学習734.9秒、最大CUDA割当9,067,672,064 bytes。epoch3 / step870を選択。
HF選択値：正しい読み111/137、誤提案8、保持0/124、待機0/123、margin0.4。

| 成果物 | bytes | SHA256 |
|---|---:|---|
| `corrector-standard-qwerty-ranker-20261103-r1-f16.gguf` | 192,132,384 | `5e2e707f80615406fa345db0229d5b725e477886e8913c7207e8111704b35610` |
| `corrector-standard-qwerty-ranker-20261103-r1-q8.gguf` | 103,866,144 | `fd9df4c4d1fd9748ac93abfe650b0a81731a9534101f4046a7f68971d776a9d8` |

既存correctorの自由生成契約とは互換ではない。既存モデル名、配布カタログ、個人設定、稼働IMEを置き換えていない。

## CPU開発評価

CPU4スレッド、GPU offload0。256tokenまでの専用研究probeを固定ABI/headerから別ビルド。
productionソースは変更せず、protocol長さ上限だけを広げたartifact内ソースをコンパイルした。
モデル・tokenizer・実行器・入力契約・val/dev/testのhashをpolicy-lockへ保存した。

| Standard Q8 | 読み正解 | 誤提案 | 保持への提案 | 待機への提案 | F0.5 |
|---|---:|---:|---:|---:|---:|
| validation | 112/137 | 7 | 0/124 | 0/123 | 0.9135 |
| 新作dev | 24/30 | 4 | 0/33 | 0/6 | 0.8451 |

CPUでもmargin0.4を選択。正解率は**読み候補選択**の指標であり、漢字表記や実IME操作成功の数値ではない。
未学習Standard親を同じ観測条件で採点した場合、validationの保持条件を満たす設定では修復0件だった。
この親は補正用契約を学習していないため、既存製品correctorの代わりの性能基準とはしない。

採点が必要なケースのCPU中央値/p95はvalidation769/3,135ms、新作dev1,517/2,491ms。
候補探索・表記変換・起動・TSF・UIは除外。各条件一巡で、負荷・温度の反復分離もしていない。
最大24候補の全文尤度採点を毎打鍵で呼べる処理時間ではない。

## 固定後の評価

モデル・policyを固定した後にtestを実行。

| 固定test | 読み正解 | 誤提案（保持への提案を含む） | 保持への提案 | 待機への提案 | F0.5 |
|---|---:|---:|---:|---:|---:|
| 学習済みStandard v4 Q8 | 72/90 | 6 | 1/94 | 0/18 | 0.8955 |
| 既存Nano Q4読みprior（旧固定policy） | 79/90 | 8 | 1/94 | 0/18 | 0.9018 |

候補到達は83/90。未到達7件も分母に残した。誤提案6件のうち修復対象に対する誤り5件、保持への介入1件。
正常入力に対する0件条件はvalidationからtestへ一般化していない。
採点が必要な143例のCPU中央値/p95は1,499/2,298ms（全202例では1,105/2,272ms）。
この待ち時間に候補探索・表記変換等は含まれない。

Nano参照の同じ143例のCPU中央値/p95は293/528ms。Standardは誤提案が2件少ない一方、
正しい修復が7件少なく、中央値で約5.1倍遅い。F0.5差（Standard−Nano）の元グループpaired bootstrap
5,000回/seed42/23グループの95%percentile区間は`[-0.0703,+0.0542]`。
この比較からStandard方式の優越は確認できず、今回の重みの製品採用は見送る。

誤介入1件は、まだ入力が続く正常なprefixに対するもの。実際に候補が競合する正常53例中1件であり、
入力状態で抑制された41例を含む94例全体の保持と区別する。
Standardの修復内訳は隣接16/18、脱落12/18、重複17/18、母音抜け12/18、転置15/18。
待機0件は未確定子音保護を含むため、学習だけで達成した結果とはしない。

比較用Nano読みpriorは従来の別validationで固定したweight/marginをそのまま使い、同じ候補集合を比較する。
基盤・学習・量子化・採点条件が異なるため、StandardとNanoの容量だけの効果を証明する比較ではない。

## 実打鍵と実IMEへの判断

利用者から既存の実打鍵記録はないと回答があったため、専用記録画面を作成して起動・表示確認した。
通常アプリや他ウィンドウは収集せず、記録画面内のASCIIキー・Backspace・左右移動だけを保存する。
モデルの提案は表示しない。記録者が意図した表記/読み・修復/保持/待機を確認して保存する。
独立した判定者の確認は未実施として記録する。時刻は記録ハンドラの観測時刻で、TSFやハードウェア遅延計測ではない。

保存後はimportでイベント再生、教師整合、学習との衝突、元グループのdev/test分離を検証する。
実打鍵が揃うまで評価を捏造・代用しない。再現コマンドは[開発手順](../../docs/qwerty-ranking-corrector.md)に記載。

独立CLIで通常候補を保持した手動補正候補の表記確認まで用意した。
実行確認では、観測キー`siryouwookurimssu`・文脈「会議の」から、
候補キー`siryouwookurimasu`・読み「しりょうをおくります」・表記「資料を送ります」を返した。
表記は元Standard v4 Q8で生成し、逆辞書で読み整合を確認、`auto_apply=false`。
この1例は動作確認であり、benchmarkへ加算していない。探索12.5ms・採点278.3ms・表記要求85.2ms。
プロセス起動・モデル起動は除外し、常駐化したIMEの時間とも区別する。
最初のサンドボックス内実行では一時localhostサーバーを開始できず、許可された制限外実行で確認し、所有サーバーを終了した。
CPUの遅延が大きく、保持への介入と実打鍵の確認も残るため、実IME組込み・自動補正範囲の拡大は保留する。
次の最適化対象は候補の削減、採点するtoken範囲、prefix共有の拡大。いずれもvalidationで品質と時間を比較して決める。
製品側の非同期化・revision照合・古い結果の取消は今回変更しておらず、組込み時に別途回帰確認が必要。

## 確認・保存範囲

新規順位付けテスト4件、イベント再生テスト3件、既存候補探索テスト3件を対象にした。
F16 nativeとHF BF16採点の開発8例照合は最大平均対数尤度差0.097（許容0.1）。
同一算術での完全一致を意味せず、実際のCPU Q8を主評価とした。
データ監査PASS、順位付け4件＋イベント再生3件＋既存探索3件の計10件成功。
`scripts/check_repository.py`でsyntax/JSON・除外・サイズを確認し、対象Markdownの`git diff --check`も成功。
最終repository検査は1,707 source filesを確認した。

主要な保存先:

- `artifacts/datasets/standard-qwerty-ranker-20261103-r1/`: 学習・validation候補集合と来歴
- `artifacts/datasets/standard-qwerty-ranker-prospective-20261103-r2/`: 新作dev/testとイベント・snapshot
- `artifacts/checkpoints/corrector-standard-qwerty-ranker-20261103-r1-{lora,merged}/`: 学習manifest、選択・重み
- `artifacts/results/standard-qwerty-ranker-trained-20261103-r1/`: CPU validation/dev、policy-lock
- `artifacts/results/standard-qwerty-ranker-heldout-20261103-r1/`: 固定後のCPU test全採点
- `artifacts/results/standard-qwerty-ranker-nano-reference-20261103-r1/`: 同候補集合の旧Nano参照
- `artifacts/results/standard-qwerty-ranker-analysis-20261103-r1.json`: 種別分析・bootstrap
- `artifacts/datasets/qwerty-human-recordings-20261103/recordings.jsonl`: 記録者が保存した場合の実打鍵。存在しない間は0件として扱う

ブランチ`feature/windows-color-themes`と既存の無関係な差分を保持。
ソース・重み・結果はローカル作業ファイルとして存在するが、コミット・pushは未実施。
Windows製品ビルド、実IME適用、実アプリの入力確認、インストーラー作成も未実施。
