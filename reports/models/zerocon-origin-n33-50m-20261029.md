# ZeroCon-Origin N33 50M学習と補正状態の検証

2026-10-29。**実モデル・GGUF・Q8を作成したが、v4超えは未達。配布モデルは変更していない。**
[構築計画](zerocon-origin-build-plan-20261029.md)のタグ制御と独自語彙・重みを実装した研究段階。
[pilot開始記録](zerocon-origin-pilot-20261029.md)の後続結果を記す。

## 学習と比較結果

N33は32,514,560パラメータ、8層、幅512、GQA 8/2、FFN1536、共有埋め込み。
16,384 piecesのSentencePieceはtrainだけから学習し、ランダム初期化した。
外部合成日本語の利用条件・生成元を保存したOrigin-B/sequence-CE条件で、v4の重み・語彙を継承していない。

| 条件 | 処理tokens | 学習時間 | 開発回帰480問 |
|---|---:|---:|---:|
| N33 T1、F16 | 50,002,230 | 3,514.08秒 | 136/480 |
| 同じ自前N33へraw状態修正の追加学習、Q8 | 追加10,002,340 | 757.02秒 | 140/480 |
| 既存Standard v4 Q8 | 既存結果 | — | 459/480 |

初段は通常106/230・難問30/250、追加学習Q8は110/230・30/250。
追加学習と量子化が同時に変わる表なので、4問の差をどちらか単独の効果と断定しない。
速度はデータ準備等の同時作業があり比較対象外。480問は開発回帰で、独立した封印評価ではない。
v4の5モデル×480問について保存済み出力を現在の同じ採点関数で再採点し、2,400件すべて既存scoreと一致した。

主な成果物（リポジトリ相対、Git対象外）：

- 初段run：`artifacts/checkpoints/origin-n33-t1-50m-20261029-r1/`
- 追加学習run：`artifacts/checkpoints/origin-n33-rawstate-adapt-10m-20261029-r1/`
- 初段回帰：`artifacts/results/origin-n33-t1-50m-regression-20261029.json`
- 追加学習Q8回帰：`artifacts/results/origin-n33-rawstate-adapt-10m-q8-regression-20261029.json`
- Q8：`artifacts/models/origin-n33-rawstate-adapt-10m-20261029-r1-q8/origin-q8_0.gguf`（34,981,120 bytes）
- 比較基準再採点：`artifacts/results/origin-v4-baseline-rescore-20261029.json`

量子化は固定された既存CPU版llama-quantizeを用い、2次元tensorをQ8_0、1次元をF32とした。
変換元・実行器・依存DLL・tensor・hashと利用条件はquantization manifestに保存した。
校正用データや封印文書は使っていない。
同じ256例のQ8/F16比較では出力・token列が255/256一致し、task別の正解件数と誤補正件数は同じだった。
これは完全な数値互換の合格ではない。`artifacts/results/origin-n33-rawstate-adapt-10m-q8-tasks-20261029.json`へ
量子化品質の比較モードとして記録し、厳密なHF/GGUF parity検査と区別した。

## 入力欄から正解が分かる偏りを発見

初期pilotでは正常例だけに正しい`surface`と`cursor`を入れ、誤打例には入れていなかった。
step2000の同じ96件で入力欄だけを揃える対照実験を行うと、正常26件がすべて誤変更された。
欄の有無を手掛かりにする学習であり、正常保持能力の証拠として扱えない。

全補正例を`ctx/raw/pending`に統一し、正解読みはモデルへ符号化しない`reference_reading`へ移した。
変換・先読み・元文・文書分割を維持し、矛盾する学習promptはabstain、validationと重なる学習promptは除外した。
対応コードは`data/rebalance_origin_repair.py`と`training/adapt_origin.py`。
転用検証はhashだけでなく親と派生の全行を検査する。親の初段モデルは保持した。

同じ修正版validationの層化256件における診断：

| 指標 | 初段50M | 修正後追加10M |
|---|---:|---:|
| 変換の正解表記完全一致 | 31/48 | 30/48 |
| 先読みsuffix完全一致 | 9/23 | 10/23 |
| 完了済み文に先読みを提案 | 0/24 | 0/24 |
| 合成誤打の読み救済 | 51/92 | 29/92 |
| 正常入力の読みを変更する提案 | 14/46 | 1/46 |
| 未確定子音がある例へのemit | 22/23 | 0/23 |

誤補正を減らした一方で救済率は下がった。全体改善・自動補正の安全性・実IME成功を示すものではない。
この標本はtask/action/category別に均した抽出で、利用時の頻度分布ではない。
先読みの別解妥当性や人による有用性評価、自動採用しきい値の校正も未実施。
ASCII/数字を含む変換16件の系列保持は両条件で一致したが、全保護条件の保証にはしない。

結果は`artifacts/results/origin-n33-t1-50m-rawstate-tasks-20261029.json`と
`artifacts/results/origin-n33-rawstate-adapt-10m-tasks-20261029.json`。
step2000と追加学習後F16のそれぞれで、HFとCPU GGUFの256件の出力・token列が一致した。
後者は`artifacts/results/origin-n33-rawstate-adapt-10m-f16-parity-20261029.json`。
量子化後の全タスク品質と実行器互換は区別する。

## データ拡張と次の比較

固定済みApache-2.0合成元文から419,305文を受理した。
全量raw-state版はtrain 2,660,049例、validation 158,104例、予約文書6,128件。
その後の矛盾prompt処理・ラベル整理で例数が減るため、この数を最終学習量として扱わない。

オフラインの固定ローマ字表との照合で、synthetic emitの中に同じ読みになる有効な別綴りを発見した。
また、`pending`が空なのに末尾状態が未解決と思われる例がある。
`data/curate_origin_repair.py`で前者をkeepへ修正し、後者をレビュー用に隔離する。
このデコーダはTSFの実状態そのものではないため、曖昧な末尾に自動で新しい正解を付けない。
validationのラベル・集合も変わる新しいデータ版として保存し、旧スコアと混ぜない。
実際の整理ではtrainの6,323例、validationの385例をkeepにし、末尾状態のtrain 4,441例・validation 249例を隔離した。
整理後はtrain 2,655,581例（変換761,086・修復1,210,984・先読み683,511）、
validation 157,855例（45,183・72,145・40,527）。元文書の分割は変えていない。
整理後の全行再監査では、同じ読みになるemitと未申告の末尾残留の既知フラグは0件。
予約文書・元文キー・開発評価キー・文書分割の監査もPASSした。
結果は`artifacts/results/origin-magpie-full-curated-romaji-audit-20261029.json`と
`artifacts/results/origin-magpie-full-curated-20261029-r1-audit.json`。実入力状態や意味的重複まで検証した結果ではない。

大規模学習用にuint16 tokenと16-byte indexを読み取り専用mmapへ保存する仕組みを追加した。
promptのloss maskとEOSを再構成し、元データ・語彙・キャッシュhashを照合する。
SentencePiece用の元行はtrain限定の固定seed reservoirで上限を設け、標本hashと母数を記録する。
N33/S68を同じキャッシュと50M処理token予算で比較する。その後、validationと誤補正・先読みの結果から追加学習を選ぶ。

全量のキャッシュ化は完了した。trainは一巡105,468,919系列tokens、うちtarget 17,755,766 tokens。
これはuniqueな文章のtoken数でも50M予算で実際に処理した量でもない。
キャッシュは`artifacts/datasets/origin-magpie-full-curated-20261029-r1-tokens/`、
語彙sha256は`da88d26caae708586667067d5e74470eef7f51a7aa9ccb075837b5bc56ebeafe`。
train 2,655,581行から固定seedで抽出した100,000行だけを語彙学習へ渡した。

このキャッシュでS68のランダム初期化50M比較を開始した。
runは`artifacts/checkpoints/origin-s68-full-t1-50m-20261029-r1/`、
ログは`artifacts/logs/origin-s68-full-t1-50m-20261029-r1.log`、統合exec sessionは`17276`。
開始の記録であり、学習終了・精度向上の証拠ではない。
同じハンドルを確認してから追跡し、実行中の重複起動やコード変更を避ける。

## v4の知見と未実施範囲

参照したv4はCE、教師KL、自己アンカー、DoRA、コピー/EOS抑制による追加学習で、
報酬モデルや方策勾配を使う強化学習ではない。Originでは正常保持・EOS・隔離・seed・再開・来歴・量子化比較を引き継ぐ。
既存γ・token ID・KLを新語彙へそのまま移植しない。CE基準から損失を一つずつ比較する方針を維持する。

契約・loss・token予算・読み・隔離・再開・mmap等の回帰を実施した。
今回追加した補正ラベル整理4件、語彙標本1件、変更の影響を確認したmmap 3件のテストはすべて成功した。
旧trainer、追加学習、mmap trainerではそれぞれ中間checkpointから再開した重みとstateの一致を確認済み。
Windowsビルド・実IME適用・入力アプリ確認・インストーラー作成・commit/pushは未実施。
変更と研究成果物はローカルの未コミット状態であり、Gitに保存済みとは扱わない。
