# QWERTY局所correctorの修復重み付け・コピー対照（2026-10-30）

## 判断

**今回の2条件は採用しない。** 前回の局所出力契約と推論経路を保ったまま、修復差分のCE重み付けと
誤コピーへの対照損失を実装し、同じNano v4から学習した。
重み付けだけでは固定testの修復数が増えたが、長文回帰と正常保持が悪化した。
対照損失は自己コピー指示を消せた一方、許可した編集条件に収まらない別の指示が増え、正しい修復にはつながらなかった。

| 局所corrector | 固定testの正しい表記 /192 | 誤修復 | 保持誤提案 /128 | 長文回帰の正しい表記 /100 | 誤修復 | 保持誤提案 /40 |
|---|---:|---:|---:|---:|---:|---:|
| 従来損失 | 18 | 54 | 5 | 6 | 23 | 5 |
| 修復差分CEを3倍 | 33 | 63 | 6 | 4 | 32 | 9 |
| 差分CE＋コピー対照 | 14 | 35 | 0 | 6 | 24 | 3 |

これはcorrector本体の損失を変えた比較であり、推論時に別の候補探索/採点モデルを追加したものではない。
前回不採用の局所形式を製品採用する意図ではなく、その主な失敗原因が損失で解消するかを切り分ける実験。
モデル重み2種を研究成果物として保存し、既存モデル・配布カタログ・設定・実IMEは置き換えていない。

## 比較条件

共通の親は`artifacts/checkpoints/nano-v4-argon4-merged`。
親重みSHA256は`b99a1b6ed530fd5b8d85551c944c4bbc64a615d249ca8d4617a560aa33ae27d0`。
既存データ`artifacts/datasets/corrector-qwerty-local-20261030-r4`をそのまま使用。
train33,200行/1,134グループ、validation1,634行/64グループで、新しい評価問題を学習へ入れていない。
観測入力が同じなのに異なる局所教師を持つ組もtrain/validationとも0件であることを確認した。

rank32 DoRA、alpha64、Attention/MLP、LR1.2e-4、3epoch、seed42、保持例3倍、層別学習率、
FP32凍結親＋BF16 autocast、384token、EOS marginを共通にした。
data/base/validationとtrainだけの量子化校正テキストのSHA256が3条件で一致することを監査した。
校正テキストSHA256は`1d49796c2782966ae455d22e0cd40b0913dd6995052dc1947fcb800abdc5678c`。

対照は前回の`corrector-qwerty-local-long-20261030-r3`。
参照として全文モデル`corrector-qwerty-full-long-20261030-r1`も再評価した。
今回の新しい2モデルも親Nano v4から開始し、前回の局所モデルへの継続学習とはしていない。

## 実装した損失

`training/qwerty_repair_loss.py`と`train_qwerty_local.py`のオプションで実装。
局所出力は従来と同じ`K`（保持）/`W`（待機）/`E:before:after`。

### 修復差分の重み付け

正しい教師`E:before:after`と、誤コピー指示`E:before:before`をtoken化し、
BPE上の差分に当たる正解tokenを重み3、その他を1でCEへ入れる。
差分が削除境界になる場合は次の正解token（EOSを含む）を重くする。
各例の重み和で正規化するため、修復例の損失全体を3倍にする手法ではない。
文字/言語学的な誤り位置を厳密に注釈したものではなく、BPE整列による教師の代理指標。

### 誤コピーへの対照

正解と誤コピーの最初に異なるtokenで、
`0.2 × max(0, 2 + logit(copy) - logit(gold))`を加える。
最初の相違までのtoken接頭辞は同じなので、同じ条件の次tokenとして比較できる。
正解teacher-forcingの1回のforwardから計算し、別の教師モデルや負例の全文生成は必要ない。
K/Wにはコピー負例を作らず、正常保持自体を罰しない。
対照損失は修復例の該当位置だけに適用し、例単位で平均する。

負例・差分重みは学習用の注釈。モデルへforwardするのは観測由来のinput_ids/attention_maskだけで、
正規キー・正解・負例を推論promptへ渡さない。
出力契約・model shape・tokenizer・推論側処理は変えていないが、出力分布が変わるため処理時間まで同じとは限らない。

## 学習・checkpoint選択

| 条件 | run | 学習時間 | 選択checkpoint |
|---|---|---:|---|
| 従来 | `corrector-qwerty-local-long-20261030-r3` | 前回420.9秒 | epoch1 / step1502 |
| 差分CE | `corrector-qwerty-edit-weight-20261030-r1` | 535.8秒 | epoch2 / step3004 |
| 差分CE＋対照 | `corrector-qwerty-edit-contrast-20261030-r1` | 554.8秒 | epoch1 / step1502 |

640件の固定validation（修復288・保持256・待機96）を使用。
意図した読みのF0.5と、保持誤提案1件以内・待機0件・正常canary1件以内の基準を維持した。
超過時は超過件数を大きく減点し、全checkpoint不適格なら最も減点の少ない重みを研究評価用に保存する。
その保存を採用可とは扱わない。3条件ともHFの保持基準を満たしていない。

HF生成で選んだcheckpointの指標は、従来が正しい読み30/288・保持誤提案3件、
差分CEが44/288・5件、対照ありは24/288・4件。
学習後半は読みの修復数が増えても正常保持が悪化し、後のepochへ無条件に切り替えていない。
異なる損失の数値自体をそのまま比較して優劣を決めていない。

F16とQ4_K_Mを生成し、Q4_K_Mを主比較に事前固定した。

| 新規Q4モデル（各18,452,608 bytes） | SHA256 |
|---|---|
| `artifacts/models/corrector-qwerty-edit-weight-20261030-r1-q4km.gguf` | `7ba94b23ea3aa839f8e1e7106c4c08383ae68c86fee7f254a987d2119cb9b648` |
| `artifacts/models/corrector-qwerty-edit-contrast-20261030-r1-q4km.gguf` | `9da3559ec4977ea9a31400d91c50fe393173f08936c7a3b5d279fd8b1091a850` |

## 表記までのQ4評価

`evaluate_qwerty_local.py`へ複数の局所runを比較するオプションを追加。
全文参照・従来局所・差分CE・対照ありを同じCPUサーバー設定で順番に評価した。
既存のStandard通常モデルで局所Eの読みを表記化し、読み整合を検証する。
共通の入力途中/削除保護とnativeの物理キー検証を適用。提案は全件手動限定で、自動適用は0件。
rawモデル指標、最終表記、実際のpromptと応答を別々に保存する。

### Validation

| 条件 | 表記正解 /288 | 誤修復 | 修復無提案 | 保持誤提案 /256 | F0.5 |
|---|---:|---:|---:|---:|---:|
| 従来局所 | 14 | 63 | 211 | 2 | 0.1159 |
| 差分CE | 25 | 103 | 160 | 9 | 0.1495 |
| 対照あり | 10 | 73 | 205 | 2 | 0.0796 |
| 全文参照 | 124 | 25 | 139 | 0 | 0.7014 |

全validationを評価してからtest/長文回帰を実行した。
3つの局所条件は保持誤提案上限1件を満たさず、研究比較の選択は全文参照となった。
testを見て損失係数・epoch・量子化・受理規則を変更していない。

### 固定test384件・67元グループ

| 条件 | 表記正解 /192 | 誤修復 | 修復無提案 | 保持誤提案 /128 | F0.5 |
|---|---:|---:|---:|---:|---:|
| 従来局所 | 18 | 54 | 120 | 5 | 0.1800 |
| 差分CE | 33 | 63 | 96 | 6 | 0.2750 |
| 対照あり | 14 | 35 | 143 | 0 | 0.1804 |
| 全文参照 | 78 | 12 | 102 | 0 | 0.7065 |

### 長文回帰160件・20元文章

| 条件 | 表記正解 /100 | 誤修復 | 修復無提案 | 保持誤提案 /40 | F0.5 |
|---|---:|---:|---:|---:|---:|
| 従来局所 | 6 | 23 | 71 | 5 | 0.1271 |
| 差分CE | 4 | 32 | 64 | 9 | 0.0714 |
| 対照あり | 6 | 24 | 70 | 3 | 0.1293 |
| 全文参照 | 15 | 4 | 81 | 1 | 0.4167 |

待機への最終提案は全条件・全集合で0件。入力途中保護の効果をモデル学習の成功へ加算しない。
raw不明の正常canaryは局所指示を適用できないため、その保持を一般の正常打鍵への耐性の証拠とはしない。
前回の全文/従来局所の正誤数が同一に再現され、評価器拡張による基準の変更がないことも確認した。

元グループ単位paired bootstrap（5,000回、seed42）の、従来局所に対するF0.5差95%percentile区間:

| 条件 | 固定test | 長文回帰 |
|---|---|---|
| 差分CE | `[+0.0157,+0.1778]` | `[-0.1701,+0.0356]` |
| 対照あり | `[-0.0831,+0.0849]` | `[-0.1572,+0.1458]` |

差分CEは固定testのF0.5を伸ばしたが、正常保持の退行と長文側の悪化があり、全面的改善とは判断しない。
対照ありはF0.5の一貫した改善を確認できていない。
いずれも繰り返し参照した合成開発評価の区間であり、独立した実打鍵の封印試験や実使用頻度の保証ではない。

## 誤コピーの減少と別の失敗への移行

以下の自己コピー指示は、beforeとafterが同じ`E:before:before`を指す。正常保持K/Wを数えていない。
アンカーが不在/非一意の場合も含む出力パターンの総数で、排他的なinvalid原因分類とは別指標。

| 指標 | 従来局所 | 差分CE | 対照あり |
|---|---:|---:|---:|
| 固定test384件の自己コピー指示 | 49 | 15 | 0 |
| 長文160件の自己コピー指示 | 50 | 9 | 0 |
| 固定testの不正指示全体 | 104 | 89 | 112 |
| 長文の不正指示全体 | 79 | 69 | 76 |
| 固定testの許可した1編集に収まらない指示 | 18 | 38 | 84 |
| 長文の許可した1編集に収まらない指示 | 8 | 24 | 46 |

今回の評価では自己コピー指示は0件になったが、正しい編集を選ぶ能力には結び付かなかった。
「許可した1編集に収まらない」には複数変更と許可外の置換等を含み、全てを複数キー編集と断定しない。
不正出力が少なくなった件数だけを成果として採用しない。
読みが変わらない別綴りへの編集も無提案として処理し、修復対象の分母には残した。

この結果は、今回の係数・データ・1seed・局所ASCII形式での結果。
「対照学習一般が無効」とは結論しないが、自己コピーだけを負例にしても、許される正しい修復を選べるようにはならなかった。

## CPU時間と推論構成

CPUのcorrector要求、局所適用/native検証、必要な通常モデルの表記変換を計測。
起動、通常の基準候補生成、TSF/GUI表示は除外。一巡の比較で、実IMEの反復した体感遅延ではない。

| 条件 | 固定test p50/p95 | 長文 p50/p95 |
|---|---:|---:|
| 従来局所 | 88.6 / 238.3ms | 123.8 / 331.3ms |
| 差分CE | 89.7 / 242.5ms | 133.3 / 363.9ms |
| 対照あり | 85.0 / 203.9ms | 120.1 / 345.6ms |

学習用の対照計算を推論時に追加する必要はない。ただし出力の種類・長さ・表記変換の回数が変わるため、
総時間が一定になるわけではない。この1巡から安定した高速化を主張しない。

## 成果物・確認範囲

- `artifacts/results/corrector-qwerty-repair-loss-20261030-r1/`: plan、validation選択、全モデルのprompt/response/最終表記、比較結果、`loss-analysis.json`。
- 新規checkpoint: `artifacts/checkpoints/corrector-qwerty-edit-{weight,contrast}-20261030-r1-lora`、各merged HF。
- 新規GGUF: `artifacts/models/corrector-qwerty-edit-{weight,contrast}-20261030-r1-{f16,q4km}.gguf`。
- ログ: `artifacts/logs/corrector-qwerty-edit-*.log`、`corrector-qwerty-repair-*.log`。

再現はルートで`.venv_train/Scripts/python.exe scripts/run.py ...`を使用し、新しいrun/output名を指定する。

```powershell
python scripts/run.py training/test_qwerty_repair_loss.py
python scripts/run.py training/train_qwerty_local.py --run-name <重み付けrun> --mode local --dataset artifacts/datasets/corrector-qwerty-local-20261030-r4 --repair-weight 3
python scripts/run.py training/train_qwerty_local.py --run-name <対照run> --mode local --dataset artifacts/datasets/corrector-qwerty-local-20261030-r4 --repair-weight 3 --copy-margin-weight 0.2 --copy-margin 2
python scripts/run.py training/build_qwerty_local.py --run-name <重み付けrun>
python scripts/run.py training/build_qwerty_local.py --run-name <対照run>
python scripts/run.py benchmarks/research/evaluate_qwerty_local.py --full-run corrector-qwerty-full-long-20261030-r1 --local-run corrector-qwerty-local-long-20261030-r3 --additional-local-run <重み付けrun> --additional-local-run <対照run> --out artifacts/results/<新しい比較名>
python scripts/run.py benchmarks/research/analyze_qwerty_repair_loss.py --out-dir artifacts/results/<比較名>
```

新規の損失テスト3成功、局所契約の回帰3成功、評価指標の回帰3成功。
コピー側logitを下げ正解側を上げる勾配、教師token接頭辞の一致、K/Wへ負例を作らないこと、
無監督位置のマスク、空ラベル、例単位の正規化を確認した。
`scripts/check_repository.py`、対象の`git diff --check --ignore-submodules=all`も成功。

実施したのはソース編集・2条件の学習・GGUF作成・モデル比較と単体確認まで。
Windows製品コード、設定、既存重み、登録済みIMEは変更していない。
実IME適用・GUI確認・インストーラー作成・コミット・pushは未実施。
ブランチ`feature/windows-color-themes`と無関係な既存差分を保持している。

局所ASCII形式で自己コピーを禁止する係数探索を続けるより、正常保持と「有効だが誤った編集」を含めた教師設計を見直す必要がある。
全文表記出力の参照モデルが依然大幅に上回るため、局所形式へ全面移行する方針は維持しない。
