# Nano v4の全文生成と局所修復の同条件比較（2026-10-30）

## 判断

**今回のキー文字列による局所修復は採用しない。** 同じNano v4から、同じ観測入力・学習行・重み付け・DoRA条件で
全文表記生成と`K/W/E:before:after`の局所指示を学習した。局所方式は修復指示の不正出力・取りこぼし・誤修復が多く、
実際の表記精度と遅延でも全文方式を上回らなかった。

| 物理キー整合を検証した最終表記 | 全文生成 | 局所修復 |
|---|---:|---:|
| 固定testの正しい補正 /192 | 78 | 18 |
| 固定testの誤修復提案 | 12 | 54 |
| 固定testの保持への誤提案 /128 | 0 | 5 |
| 固定testのF0.5 | 0.7065 | 0.1800 |
| 新作文章の正しい補正 /100 | 15 | 6 |
| 新作文章の誤修復提案 | 4 | 23 |
| 新作文章の保持への誤提案 /40 | 1 | 5 |
| 新作文章のF0.5 | 0.4167 | 0.1271 |

全文方式は、前回の単一QWERTYモデル＋状態/キー検査の92/192件から78/192件へ正解数が減る一方、
誤修復は32→12件、F0.5は0.6686→0.7065へ改善する保守的な候補になった。
新作文章では11→15/100件、誤修復12→4件、F0.5 0.2806→0.4167。
これらは入力契約・学習・処理経路を含む比較で、学習データ拡張だけの因果効果を単独に示すものではない。
別の読み採点モデルを使った探索追加経路の123/192・69/100件とは、推論構成も成績も区別する。

今回作ったのは**corrector本体の重み2種類**。推論時の候補比較用の別Nanoモデルは使わない。
局所方式は既存の通常モデルによる表記変換が必要であり、単一correctorだけで最終漢字表記を出す方式ではない。
両方とも研究成果物として保存し、実IME・設定・配布カタログは更新していない。

## 既存データの不足と共通の追加データ

従来の修復学習14,404行には、30キー以上が16行、40キー以上が0行しかなかった。
長い文章の比較のため、既存のtrain/validationに属する語句を、それぞれのsource groupを維持して
「ここでは…という表現を使います」等の合成テンプレートへ埋め、新しく1キー誤りを作った。
文章としての使用分布ではなく、長い入力で修復箇所を扱うための合成増補である。

train/validationで異なるテンプレートを使い、正規キーのnative roundtripに失敗する28例は除外。
元の分割と既存testを維持し、新作20文章も学習へ投入しない。
既存評価・新作文章・train/validation間のテキストキーとsource groupの衝突を検査した。

| 分割 | 行 | 元グループ | 修復 | 保持 | 待機 | 長い合成行 |
|---|---:|---:|---:|---:|---:|---:|
| train | 33,200 | 1,134 | 19,650 | 7,429 | 6,121 | 8,791 |
| validation | 1,634 | 64 | 959 | 369 | 306 | 280 |
| 既存test全体 | 1,303 | 67 | 843 | 166 | 294 | 0 |

checkpoint選択には既存validation480件＋長い合成validation160件の**640件**を固定した。
内訳は修復288・保持256・待機96件。最終testは従来の384件、新作文章は160件であり、
test全体1,303行をすべて測定した点数ではない。これらは過去に観察済みの合成開発評価であり、封印された実打鍵試験ではない。

## 局所契約とラベル修正

入力は両方式とも`qwerty_local_eval.input_prompt`で作る同じraw-state prompt。
文脈、観測キー、観測読み、append/backspace/unknown、カーソル位置を渡す。
正規キー、誤り位置、正解表記、期待動作、局所修復ラベルは入力へ渡さない。

- 全文方式の教師: 従来どおりの正解表記。保持/待機は元の観測を保持する教師を使う。
- 局所方式の教師: `K`（保持）、`W`（待機）、`E:before:after`（一意なキー部分の置換）。
  beforeは最大12文字、afterは最大14文字。指示は実際の入力へ適用し、1回の物理編集とnative読みを確認する。

初期検証で、Pythonの`count`が重なる出現を数えないため、一意性の判定に不備があることを発見した。
`find==rfind`で重なりも含めて判定するよう修正し、33行の局所ラベルを修復した。
途中の局所run r1/r2は完了前に停止し、最終評価へ混ぜていない。`training_completed=false`のまま保全している。

修正により以前除外された2行が復活したため、対照条件を揃える最終データではその2行を両方式とも含めない。
全文runは`corrector-qwerty-local-20261030-r2`、局所runは修正済み`…-r4`を使うが、
**33,200行のID・入力prompt・全文教師・順序・重み付けは完全一致**する。
違いは局所方式だけが使う33行の修復済み局所ラベルである。validation fixtureもバイト一致を確認した。
実際のformatterを使った同一性監査は比較結果の`analysis.json`へ保存した。

## 学習と出力モデル

共通の親: `artifacts/checkpoints/nano-v4-argon4-merged`。
親重みSHA256: `b99a1b6ed530fd5b8d85551c944c4bbc64a615d249ca8d4617a560aa33ae27d0`。

共通条件はrank32 DoRA、alpha64、Attention/MLP、LR1.2e-4、3epoch、seed42、正常保持例3倍、
層別学習率、per-example CE＋EOS margin、FP32凍結親＋BF16 autocast、最大384token。
追加教師モデル・推論時の読み候補列挙は学習モデルに必須ではない。

checkpointの比較は意図した読みへのF0.5で揃えた。
全文出力は逆辞書による読み整合、局所出力は指示適用後のnative読みで採点する。
保持への誤提案1件以内、待機0件、正常canary1件以内を上限とし、超過件数を大きく減点する。
不正指示は無提案だが、修復対象の分母から除外しない。

| 条件 | run | 選択checkpoint | 学習時間 |
|---|---|---|---:|
| 全文 | `corrector-qwerty-full-long-20261030-r1` | epoch1 / step1502 | 481.6秒 |
| 局所 | `corrector-qwerty-local-long-20261030-r3` | epoch1 / step1502 | 420.9秒 |

両者ともHF段階の厳しい保持上限を満たさなかった。局所方式はepochを進めると読みの修復数が増える一方、
保持への誤提案も3→9→11件と増えたため、保持を重視した選択はepoch1になった。
学習時間は同一負荷での速度試験ではなく、並行したexport等の負荷もあり、速度差を主張しない。

`build_qwerty_local.py`で、各モードの実際の入力・教師を使ったtrainだけのimatrixを作成し、F16とQ4_K_Mを生成。
主比較の量子化はQ4_K_Mに事前固定し、testで選び直していない。

| Q4モデル（各18,452,608 bytes） | SHA256 |
|---|---|
| `artifacts/models/corrector-qwerty-full-long-20261030-r1-q4km.gguf` | `403b2575789742343ad8bae1ee8a11d0a5c5a0410307d8e86a8dfad20626c451` |
| `artifacts/models/corrector-qwerty-local-long-20261030-r3-q4km.gguf` | `7ab7a5db3ec662d60a5f87a0e303897bb33eb04bd5cda7cce25bb8385e7bfa05` |

専用raw-state promptが必要。局所モデルは出力の適用処理も必要で、現行correctorへGGUFだけを置換できない。

## 最終評価の三つの層

`evaluate_qwerty_local.py`はモデル・通常モデルを専用CPUサーバーで起動し、全prompt・応答・適用結果を保存する。
通常モデルは既存Standard、逆辞書・native DLLも既存の固定物を使用。正常候補へ正解を注入しない。

1. **rawモデル指標**: 入力途中保護なし。全文の読み整合／局所指示の適用後読みを採点し、不正出力も記録。
2. **basic表記指標**: 両方式共通の末尾未確定子音・削除直後の保護。局所Eは通常モデルで表記化し、読み整合を検証。
3. **physical表記指標**: 全文提案にも1キー編集のnative証拠を要求する。局所Eは適用済みキー列自体が証拠になる。
   raw不明の全文提案には証拠を出せないため、この層では提案しない。

physical層を含む評価コードと選択規則はGGUF評価前に固定した。全validationを終えてからtest/freshを評価。
validationのphysical表記F0.5と保持/待機/canary上限で2方式から選ぶと全文方式になった。
これは2方式間の研究上の選択であり、そのまま現行IMEへ採用する判断ではない。
新モデルの提案はすべて手動限定で、自動適用は全ケース0件。

### 表記の結果（physical層）

| 集合・方式 | 正解 | 誤修復 | 修復無提案 | 保持誤提案 | F0.5 |
|---|---:|---:|---:|---:|---:|
| validation 全文 | 124/288 | 25 | 139 | 0/256 | 0.7014 |
| validation 局所 | 14/288 | 63 | 211 | 2/256 | 0.1159 |
| test 全文 | 78/192 | 12 | 102 | 0/128 | 0.7065 |
| test 局所 | 18/192 | 54 | 120 | 5/128 | 0.1800 |
| 新作文章 全文 | 15/100 | 4 | 81 | 1/40 | 0.4167 |
| 新作文章 局所 | 6/100 | 23 | 71 | 5/40 | 0.1271 |

待機への最終提案はすべて0件。共通の入力途中保護の効果を、モデル本体のWAIT学習の成功へ加算しない。
raw不明の正常canary96件は局所指示を適用できないため、その保持を通常打鍵への一般的耐性とは扱わない。

物理検査なしのbasic層では、全文のtestは78正解・103誤修復・保持誤提案14件、
新作文章は15正解・83誤修復・保持誤提案12件。全文モデル自体の誤生成も依然多い。
physical層の誤提案減少を、重みだけの改善と混同しない。

元グループ単位paired bootstrap（5,000回、seed42）の局所−全文のF0.5差95%percentile区間は、
test67グループで`[-0.6150,-0.4303]`、新作20グループで`[-0.4893,-0.0285]`。
合成開発集合の範囲での結果であり、実使用頻度の安全性・将来の最良局所方式を保証/否定するものではない。

## 局所方式の失敗の内訳

rawモデル指標で不正出力はvalidation185/640、test104/384、新作79/160件。
修復対象に限るとtest101/192、新作69/100件で指示が不正だった。

| 不正指示の主因 | test | 新作文章 |
|---|---:|---:|
| beforeとafterが同じで修復にならない | 45 | 31 |
| 入力にbeforeが存在しない | 10 | 19 |
| beforeが一意でない | 3 | 8 |
| 1回の物理編集でない | 18 | 8 |
| nativeで未完成/範囲外等 | 23 | 13 |
| 形式不正/生成打切り | 5 | 0 |

「短い出力なら簡単に学習できる」という仮説は今回支持されなかった。
afterへ入力をそのままコピーする傾向と、アンカーの選択・適用の失敗が多い。
有効な指示でもtestでは意図読みが正しい20件に対し誤り54件あり、形式制約だけを直せば解決する状況でもない。

選択後の検証128件（修復73件）だけを使い、量子化の影響も診断した。
意図読みが正しい出力は、マージ済みFP32で6/73、GGUF F16で5/73、Q4で2/73。
不正出力は各48/128、45/128、46/128だった。Q4で悪化するが、マージ済みFP32でも成功率が低い。
この診断を使って量子化・checkpointを選び直していない。局所adapter rootと選択checkpointの重みハッシュ一致も確認した。

## 処理時間

CPUモデル要求＋指示適用/native検証＋必要な通常モデルの表記変換を測定。
モデル起動・通常の基準候補生成・TSF/GUI表示は含めない。入力コールバックへ同期処理を追加したものではない。
一巡の実測であり、反復したWindows体感遅延の測定ではない。

| physical層 | p50 | p95 |
|---|---:|---:|
| test 全文 | 59.2ms | 80.0ms |
| test 局所 | 62.1ms | 158.1ms |
| 新作文章 全文 | 115.4ms | 150.7ms |
| 新作文章 局所 | 87.0ms | 226.1ms |

局所方式は出力文字列が短くても、追加の表記変換を含む処理時間は改善しなかった。
別の読み採点モデルを使わないことと、最終表示まで高速になることを区別する。

## 成果物・再現・実施範囲

主比較: `artifacts/results/corrector-qwerty-full-vs-local-20261030-r1/`
`plan.json`、`selection.json`、`comparison.json`、各層のケース、prompt/response、通常モデル要求、
`analysis.json`（実入力の同一性と不正指示の内訳）、`export-probe.json`を保存した。
全モデルのrun-manifest、dataset-provenance、validation生成は各checkpointフォルダーに保存。
不完全な局所run r1/r2は採用していない。最終局所runはr3。

```powershell
python scripts/run.py training/test_qwerty_local.py
python scripts/run.py benchmarks/research/test_qwerty_local_eval.py
python scripts/run.py data/build_qwerty_local_dataset.py --out artifacts/datasets/<共有データ名>
python scripts/run.py training/train_qwerty_local.py --run-name <全文名> --mode full --dataset artifacts/datasets/<共有データ名>
python scripts/run.py training/train_qwerty_local.py --run-name <局所名> --mode local --dataset artifacts/datasets/<共有データ名>
python scripts/run.py training/build_qwerty_local.py --run-name <全文名>
python scripts/run.py training/build_qwerty_local.py --run-name <局所名>
python scripts/run.py benchmarks/research/evaluate_qwerty_local.py --full-run <全文名> --local-run <局所名> --out artifacts/results/<比較名>
```

`analyze_qwerty_local.py`と`probe_qwerty_local_export.py`は今回のrun/dataパスを使う診断スクリプト。
別runへ変更する場合は比較開始前に固定する。既存成果物は上書きしない。

Pythonはルートで`.venv_train/Scripts/python.exe scripts/run.py ...`経由。
局所契約テスト3成功、共通評価テスト3成功。重なるアンカー、不正編集、ラベル非依存、
native roundtrip、通常のかな漢字変換を誤補正に数えないこと、無提案を分母から除かないことを確認。
`scripts/check_repository.py`と対象の`git diff --check --ignore-submodules=all`も成功。

今回はソース、学習、GGUF生成、単体/モデル比較まで実施。
Windows製品コード・設定・配布カタログ・登録済みIMEは更新していない。
実IMEへの適用・GUI確認・インストーラー・コミット・pushは未実施。
`feature/windows-color-themes`を維持し、無関係な作業差分と既存モデルを保持した。

次はキー局所形式への全面移行を保留し、変更不要時のコピーと修復時の誤コピーを分ける教師・損失の設計、
探索で得られた正しい候補と紛らわしい候補の対照学習を優先する。
今回の結果だけで「単一correctorでは不可能」「局所修復は常に不利」とは結論しない。
