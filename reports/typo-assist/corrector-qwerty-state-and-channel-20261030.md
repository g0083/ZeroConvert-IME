# QWERTYのキー列・編集状態・候補検査の比較（2026-10-30）

## 結論

同条件の読み入力／キー列＋編集状態入力の2モデルを学習し、既存2モデルを含めて比較した。
今回validationで選ばれたのは**前回のQWERTYモデル＋編集状態・キー編集の検査**。
新しいraw-stateモデルは正常保持canaryが悪化したため採用しない。

既存の固定QWERTY test384件では、前回モデルの正しい補正92/192件を保ち、
誤った修復提案45→32件、保持対象への誤提案10→0/128件、F0.5を0.5897→0.6686へ改善した。
追加の新作20文章・160ケースでも誤提案は減ったが、補正成功は11/100件と低い。
既存の短い元語句での47.9%を、未知の文章全般に対する実用精度とは扱えない。

キー列をpromptへ加えるだけでは汎化改善を確認できなかった。一方、観測キーで説明できない提案を
抑制する方法は、同じ重みのまま誤提案を減らせた。これは研究Pythonでの候補検査であり、
学習済みreranker、実IMEへの組み込み、リアルタイム遅延の改善は実装・確認済みではない。

## 実装

- `data/build_qwerty_state.py`: 前回r3データへ観測可能なappend/backspace/unknownとrawキー単位のカーソル位置を追加。
  削除例1,295件はDLLへ元キーを入れ、実際にRemoveTextし、観測読みの一致を確認した。
  raw不明の正常再生行はnull/unknownのまま。末尾編集の合成状態であり実ユーザー記録ではない。
- `training/qwerty_input_contract.py`: raw-state契約のpromptを一元化。
  PUA入力区切りの直後に`[QWERTY {"keys":...,"state":...,"cursor":...}]`を置き、続けて既存の観測読みを渡す。
  正解表記、正規キー、編集位置の正解、誤り種別、期待動作は参照しない。
- `training/train_qwerty_corrector.py`: kana/raw-state切り替え。学習と生成validationで同じformatterを使う。
  入力メタデータ・観測読み・正解全文・EOSを保持し、必要なら左文脈を切る。今回のtoken予算は384。
- `training/build_corrector_v4.py`: imatrixのtrain校正にも同じ入力契約を反映。表記・語彙・特殊token IDは変更しない。
- `benchmarks/research/qwerty_channel_filter.py`: 元入力を維持するか、モデル提案を通すかの保守的な検査。
  削除直後の提案を抑制し、キーがある場合は隣接置換・脱落・挿入・転置1回で提案表記の読みを説明できるか探索する。
  近似decoderで探索を絞り、採用パスはnative DLLと逆辞書で検証する。元の正規キーや正解読みは探索に使わない。
  raw不明ならキー検査を省略する。近似探索の見逃し、複数誤り、辞書外語は残る。
- `data/prepare_qwerty_state_fixtures.py`: 既存の独立Standard通常候補を維持し、同じケースIDに観測状態だけを追加。
- `benchmarks/research/compare_qwerty_state.py`: 4モデル×4集合×3検査条件を比較。実際のモデルprompt・budget・応答も保存。

Windows製品ソース・設定・モデルカタログは今回変更していない。
native DLLは独立Pythonプロセス内で使い、登録済みIMEの入力・学習状態へ接続していない。

## 同条件の追加学習

親は両モデルとも`artifacts/checkpoints/nano-v4-argon4-merged`。
親重みSHA256は`b99a1b6ed530fd5b8d85551c944c4bbc64a615d249ca8d4617a560aa33ae27d0`。
データは`artifacts/datasets/corrector-qwerty-state-20261030-r1`。
train24,410行、validation1,354行、test1,303行。元グループ分割は前回のまま。
観測状態の追加であり、正解や文章を変更していない。

両条件でrank32 DoRA、alpha64、Attention/MLP、LR1.2e-4、3epoch、seed42、正常例3倍、EOS margin、
層別学習率、FP32凍結親＋BF16 autocastを共通にした。追加教師とclean KLは使わない。
checkpointは既存のvalidation F0.5・保持/待機/誤自動適用/canary上限で選ぶ。
不適格checkpoint同士では超過件数の少ないもの、同点では先のepochを保持する既存実装を使用。
読み対照はepoch1、raw-stateはepoch2を保持したが、両方とも採用上限を満たしていない。

| 成果物 | 学習時間 | Q4_K_M SHA256 |
|---|---:|---|
| `corrector-qwerty-state-kana-20261030-r1-q4km.gguf` | 153.5秒 | `d3901c30214ca1e0da4ff137f53684a4ef15059808b811035c50e0f914158acd` |
| `corrector-qwerty-state-raw-20261030-r1-q4km.gguf` | 264.0秒 | `22e1334cae04ad75d016400cd8b00f38a2ea2a8b03323d1cfb8777e4eb7a6ccc` |

GGUFは`artifacts/models/`、HF/adapterは`artifacts/checkpoints/`に保存。
F16/Q4/Q6/Q8/IQ4を生成したが、今回の比較は**Q4を事前固定**し、testで量子化を選んでいない。
raw-state重みは専用promptが必要であり、従来correctorへそのまま置換できない。

## 比較条件とvalidation選択

通常モデル・辞書・Rust補正ポリシー・元ケースIDは前回と共通。
正解を通常候補へ渡さず、独立に生成した固定通常候補を使用した。
各モデルに「検査なし／削除状態のみ保護／削除状態＋1キー編集検査」の3条件を同じように適用した。
全validationの比較と選択を終えてから、test・旧回帰・新作集合のモデル生成を実行した。
閾値や編集数をtestの結果に合わせて変更していない。

| validation480件 | 正しい補正/192 | 誤修復提案 | 保持誤提案/224 | canary誤提案/96（内数） | 誤自動適用 | F0.5 |
|---|---:|---:|---:|---:|---:|---:|
| 前回QWERTY・検査なし | 98 | 35 | 11 | 1 | 2 | 0.6380 |
| 前回QWERTY・状態＋キー検査 | 98 | 20 | 1 | 1 | 2 | 0.7335 |
| 今回kana・検査なし | 91 | 44 | 6 | 1 | 4 | 0.6019 |
| 今回raw-state・検査なし | 99 | 37 | 5 | 2 | 2 | 0.6548 |
| 今回raw-state・状態＋キー検査 | 99 | 13 | 3 | 2 | 2 | 0.7592 |

raw-state＋検査のF0.5は高いが、canary誤提案が基準1→2件のため不適格。
最大スコアだけで採用せず、適格な前回QWERTY＋状態/キー検査を選んだ。
誤自動適用2件は今回もvalidationで残っている。

## 固定test384件

修復192・保持128・待機64件、67元グループ。

| 指標 | 前回QWERTY | 前回QWERTY＋選択した検査 |
|---|---:|---:|
| 正しい補正 / 192 | 92 | 92 |
| 誤った修復提案 | 45 | 32 |
| 無提案 / 修復192件 | 55 | 68 |
| 保持への誤提案 / 128 | 10 | 0 |
| 待機への提案 / 64 | 0 | 0 |
| 正しい補正 / 全提案 | 92/147（62.59%） | 92/124（74.19%） |
| ケース単位F0.5 | 0.5897 | 0.6686 |
| 誤自動適用 / 自動適用9件 | 0 | 0 |

状態保護だけでは保持誤提案10件が0になり、キー検査がさらに誤修復13件を除いた。
誤提案を無提案へ変えた改善であり、正しい修復数自体は増えていない。
元グループ単位paired bootstrap、5,000回・seed42のF0.5差95%percentile区間は`[+0.0508,+0.1088]`。
合成固定集合の区間であり、実使用分布の安全性を保証しない。

今回kanaは検査なし85/192、raw-stateは89/192。
raw-state＋検査は89/192・誤修復25・保持誤提案2・F0.5 0.6784だが、
testスコアを使ってvalidationの不適格判定を覆していない。

## 新作20文章・160ケース

`data/build_qwerty_fresh_eval.py`で今回新しく文章と読みを作成し、モデル比較の前に固定した。
修復100、正常20、削除後保持20、待機20。既存train/val/testとのテキストキーの非重複を確認した。
正規キーのnative roundtripと逆辞書の表記読み整合を確認しているが、独立した人による意味審査は未実施。
合成誤りであり、実ユーザーの頻度分布でも封印された外部試験でもない。

| 条件 | 正しい補正/100 | 誤修復提案 | 保持誤提案/40 | F0.5 |
|---|---:|---:|---:|---:|
| 前回QWERTY | 11 | 22 | 5 | 0.2183 |
| 前回QWERTY＋選択した検査 | 11 | 12 | 1 | 0.2806 |
| 今回kana | 9 | 16 | 13 | 0.1786 |
| 今回raw-state | 7 | 15 | 4 | 0.1716 |
| 今回raw-state＋検査 | 7 | 8 | 1 | 0.2134 |

未知の長めの文章に対して生成能力が不足している。raw情報の追加だけで解決していない。
表記別解は今回未付与であり、表記完全一致だけでは過小評価もあり得るが、低成績全体を採点の問題とは扱わない。
診断としてモデル応答に正解表記を注入して同じポリシーを通すと、正しい最終提案71/100、
無提案22、異なる提案7件だった。7件では浮いた子音の復元読み等が最終提案になっていた。
この診断はモデルの点数でも厳密な達成可能上限でもない。生成と、生成を拒否・置換する経路の両方に課題がある。

## 旧QWERTYラベル回帰195件

前回QWERTYは40/150・誤修復37・正常誤提案2/45のまま。rawキー/編集状態が保存されていないため、
新しい検査はここでは判断を変えない。元の汎用v4の38/150・誤修復27・正常誤提案1/45への退行は解消していない。
今回kanaは22/150・正常誤提案4、raw-stateは21/150・正常誤提案4で、重みの置換は見送る。

## 限界と次の実装範囲

削除ケースは正常prefixの保持として合成されており、backspaceと保持ラベルの相関が強い。
削除後に残る誤り、再入力、途中カーソル、停止時間、取消・確定を含む実操作の検証が必要。
近似decoderの探索はnative検証で誤採用を避けるが、正しい候補を探索前に落とす可能性がある。
1編集の制約は今回の合成誤りには合うが、複数タイポへの修復能力を示さない。

今回は学習済み候補rerankerを実装したものではない。次は長い文の誤り位置を局所化し、
元入力・物理キー候補・生成候補を比較する学習と、浮いた子音の復元結果に固定されない候補検証を優先する。
今回の新作評価は今後の開発回帰用とし、そこへ合わせて学習した後は別の未参照評価を追加する。
Python探索をそのまま入力コールバックへ追加しない。製品へ組み込む前に処理予算・背景実行・古い結果の破棄と、
TSFから表示までの遅延を測る必要がある。今回の応答再生・候補検査時間は実IMEの遅延として報告しない。

## 再現・検証・保存範囲

Pythonはルートで`python scripts/run.py <対象.py>`を使う。今回の実行環境は`.venv_train/Scripts/python.exe`。
新規出力名を指定し、既存の出力を上書きしない。

```powershell
python scripts/run.py data/build_qwerty_state.py --parent artifacts/datasets/corrector-qwerty-native-20261030-r3 --out artifacts/datasets/corrector-qwerty-state-20261030-r1
python scripts/run.py data/prepare_qwerty_state_fixtures.py --dataset artifacts/datasets/corrector-qwerty-state-20261030-r1 --prefix corrector-qwerty-state-20261030-r1
python scripts/run.py training/train_qwerty_corrector.py --run-name <kana/rawそれぞれ新しい名前> --dataset artifacts/datasets/corrector-qwerty-state-20261030-r1 --fixture artifacts/results/corrector-qwerty-state-20261030-r1-val-fixture.json --baseline artifacts/results/corrector-qwerty-state-20261030-r1-val-baseline.json --epochs 3 --input-contract <kanaまたはraw-state>
python scripts/run.py training/build_corrector_v4.py --run-name <同じ名前>
python scripts/run.py data/build_qwerty_fresh_eval.py --out artifacts/datasets/corrector-qwerty-fresh-20261030-r1
python scripts/run.py benchmarks/research/qwerty_corrector_policy.py --prepare artifacts/datasets/corrector-qwerty-fresh-20261030-r1 --split test --all --out artifacts/results/corrector-qwerty-fresh-20261030-r1-fixture.json
python scripts/run.py benchmarks/research/compare_qwerty_state.py --out artifacts/results/<新しい比較名>
python scripts/run.py benchmarks/research/analyze_qwerty_state.py --comparison-dir artifacts/results/<比較名>
```

比較スクリプト内の今回のモデル/fixture名を別runへ変更する場合は、評価開始前に固定する。
`artifacts/results/corrector-qwerty-state-20261030-r1-comparison/`の`plan.json`、`validation-selection.json`、
`comparison.json`、`selected-analysis-v2.json`、`selected-analysis-v2-fresh-oracle.json`が主要成果物。
実際の全モデル要求と応答、各モデル/ソース/fixtureのSHA256、保持した/抑制した提案、native検証済みキー列を保存した。
旧`selected-analysis.json`と`fresh-oracle-diagnostic.json`は初回診断を保全したもので、
oracle識別を明示した上記v2を参照する。

- `python scripts/run.py training/test_qwerty_input_contract.py`: 2成功。正解フィールドを変えてもpromptが変わらないこと、状態・cursor検証。
- `python scripts/run.py training/test_qwerty_corrector.py`: 5成功。既存の損失・凍結親・native変換の回帰。
- `python scripts/run.py benchmarks/research/test_qwerty_channel_filter.py`: 3成功。編集復元、native witness、到達不能候補、削除保護、raw不明のfallback。
- `python scripts/run.py scripts/check_repository.py`: 成功。構文・JSON・成果物のGit除外等。
- 対象ファイルの`git diff --check --ignore-submodules=all`: 成功。

ソース編集、2条件の学習、GGUF作成、固定評価、候補検査、Pythonテストを実施。
今回はWindows製品コードを変更しておらず、Windowsビルド・実IME適用・GUI/TSF確認・インストーラー作成は未実施。
ブランチ`feature/windows-color-themes`を維持し、既存の無関係な変更を保持。コミット・pushはしていない。
