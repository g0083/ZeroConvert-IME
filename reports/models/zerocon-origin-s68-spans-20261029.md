# ZeroCon-Origin S68途中診断と短語句データ実験

2026-10-29。[N33の結果](zerocon-origin-n33-50m-20261029.md)に続く作業記録。
**v4超えは未達。S68の50M学習は継続中で、以下の精度は途中checkpointの診断。**
既存IME・配布モデル・ユーザー設定は変更していない。

## S68の途中状態

runは`artifacts/checkpoints/origin-s68-full-t1-50m-20261029-r1/`、統合exec session `17276`。
67,649,280パラメータ、10層、幅768、GQA 12/2、FFN1792、共有埋め込み、16k自前語彙。
ランダム初期化から、監査済みの全量raw-stateデータを使う。v4の重みは読み込まない。
50M処理token予算から18,088 stepsを算出した。

step2000（5,531,080処理tokens、target 1,149,924 tokens）の保存完了を確認し、
学習を止めずに独立したHF診断用コピーへ復元した。
`artifacts/checkpoints/origin-s68-full-step2000-diagnostic-20261029/`にcheckpoint hash・親manifest・構造を記録。
語彙・データ・学習コードのhashは親から引き継ぎ、厳密なstate読み込みとパラメータ数を検査した。

同じ固定seedの層化validation 256件をCPUで生成した結果：

| 指標 | step2000 |
|---|---:|
| 変換の正解表記完全一致 | 3/44 |
| ASCII/数字を含む変換で系列を変更 | 2/11 |
| 合成誤打の正解読み救済 | 2/84 |
| 正常読みを変更する提案 | 0/63 |
| 未確定子音へのemit | 0/21 |
| 先読みsuffix完全一致 | 11/22 |
| 完了文への先読み提案 | 0/22 |

低い変換・救済率、保護失敗があり、出荷品質の証拠にはならない。
正常63件には整理済みの有効な別綴りも含む。task/action/category別の層化で、利用時頻度ではない。
N33 pilotの診断とはvalidationの集合・ラベルが異なるので、モデル規模による向上をこの表から主張しない。
結果：`artifacts/results/origin-s68-full-step2000-tasks-20261029.json`。

## GGUF精度の切り分け

| 出力 | HFとの出力・token一致 | task別完全一致の総数 |
|---|---:|---:|
| F32 GGUF | 256/256 | 122/256 |
| F16 GGUF | 255/256 | 122/256 |
| Q8 GGUF | 247/256 | 122/256 |

F16の厳密なparityコマンドは1件差分で**失敗**した。後から合格へ書き換えていない。
Q8は数値一致を要求しない品質比較モードで記録し、parity合格と区別した。
現時点の標本ではtask別の正解総数・正常誤変更・保護失敗の集計は同じだが、
個々の出力が同じこと、全体品質や量子化の安全性を保証しない。
Q8は72,332,800 bytes、92 tensor。校正データ・封印文書を使わず量子化した。

F16の最初の分岐は6番目の生成tokenで、HFの2候補のlogit差は約`6.96e-5`だった。
GGUFと同じ「2次元はF16、1次元はF32」に丸めたHFでも、その場のargmaxは元と同じだった。
重みの丸めだけで分岐を再現したとは報告しない。
一方、F16 GGUFの全92 tensorをF32 GGUFと比較し、2次元は期待するF16のbit列、1次元は元のF32のbit列に一致した。
F32では全256件が一致したので、元精度での符号化・構造・GGUF経路の整合は確認できた。
F16の演算差を含む詳細な数値原因は未確定で、品質ゲートは緩和しない。

対応コードは`training/export_origin.py --outtype f32`、
`benchmarks/research/diagnose_origin_precision.py`、`check_origin_export_rounding.py`。
元のHF重みを変更せず、丸め実験は別のCPUモデルのメモリ上で行った。
最初の全パラメータ丸め診断r1は、GGUFのnorm精度と一致しない探索条件。
GGUFの型を実際に検査した2次元限定のr2を採用し、r1を根拠にしない。

主な検査結果は`artifacts/results/`内の以下：

- `origin-s68-full-step2000-f16-parity-20261029.json`（厳密一致失敗）
- `origin-s68-full-step2000-f32-parity-20261029.json`（厳密一致成功）
- `origin-s68-full-step2000-q8-quality-20261029.json`（量子化品質比較）
- `origin-s68-step2000-f16-precision-diagnosis-20261029-r2.json`
- `origin-s68-step2000-export-rounding-20261029.json`（92 tensorのbit比較成功）

CPU速度はGPU学習・データ準備と並行しているため、v4との速度比較に使わない。

## 短い変換入力の不足

元文を含む全量変換例の読みは平均44.02文字だった。
既存の開発評価は通常230問が12.30文字、難問250問が14.36文字。
これは開発用の長さの集計であり、評価文や正解を学習例へ流用していない。
実利用時の分布も別に測る必要がある。

`data/augment_origin_spans.py`で、元の学習文だけから連続する単語1個と短い連続語句を選ぶ。
文脈は元の前文＋対象箇所より前の文内prefixを最大48文字。対象より後の文は入力に入れない。
表記は元文の連続範囲、読みは同じ固定UniDicの書字形かな。
文書ID・source ID・元文・来歴を維持し、評価の正解を参照して単語を選ばない。
外部の新しい元文・教師・重みは追加していない。

388,836個の学習用full例から762,298例を追加した。
開発予約キー2,688件、既存例との完全重複12,489件、validation promptとの一致47件は追加しなかった。
新しいtrainは3,417,879例（変換1,523,384・修復1,210,984・先読み683,511）。
変換の平均読み長は24.35文字になった。この分布が最適、または性能を改善したとはまだ判定しない。

元のtrain 2,240,359,803 bytesを先頭にそのまま保持し、追加例だけを末尾へ置く。
validation・sources.jsonもbyte単位で同じ。元の修復・先読み例は変更していない。
`data/verify_origin_spans.py`で全762,298件の親・token/文字境界・読み・表記・前方文脈・残りのmetadataを検査してPASS。
別の`check_origin_corpus.py`でも予約6,128文書・84,178文キー・既存評価・分割の検査がPASSした。
意味的な重複の完全除去や、読みの人手校正を保証するものではない。

成果物：

- データ：`artifacts/datasets/origin-magpie-full-spans-20261029-r1/`
- 来歴検証：`artifacts/results/origin-magpie-full-spans-lineage-20261029-r1.json`
- 隔離監査：`artifacts/results/origin-magpie-full-spans-isolation-20261029-r1.json`
- 長さ集計：`artifacts/results/origin-conversion-length-profile-20261029.json`と`origin-conversion-span-length-profile-20261029.json`

## 同じ語彙でのキャッシュ追加

`training/append_origin_span_cache.py`は両監査のPASS・全出力hash・元データとの関係を確認し、
元のtoken/index/validation/vocabを別フォルダへ複製して、証明済みの追加例だけを符号化する。
元キャッシュ・実行中のS68・語彙を変更しない。元のtrain限定100,000行で作った語彙を継続使用し、validationを追加しない。
独立したmmap readerで新旧の境界・EOS・hashを検査した。

新キャッシュは`artifacts/datasets/origin-magpie-full-spans-20261029-r1-tokens/`。
一巡131,098,386系列tokens、うちtarget 19,790,224 tokens。
unique元文のtoken数や実際に追加学習を終えた量と混同しない。

## 検証と次段

元文範囲・未来文脈混入・別の読み・文書変更・元trainの改変/欠落を検出する5テストと、
旧/新キャッシュ境界を含む全例の入力・loss mask・EOS比較1テストが成功した。
F32の実GGUF生成・256件のCPU往復、F16の92 tensor比較も実施した。

現在のS68は元の全量キャッシュで50M学習を続ける。今回の追加例を学習中に混ぜない。
終了後に最終checkpointを同じ採点器・3タスク・量子化条件で評価し、同じ元キャッシュと予算のN33を比較する。
短語句追加は次段の独立条件。現行cached trainerの`--base`はデータ完全一致のみ許すため、
この追加データで継続学習するには、学習終了後に来歴検証付きの転用経路を実装・テストする。
実行中のtrainerは編集していない。

Windowsビルド・実IME適用・入力アプリ確認・インストーラー作成・commit/pushは未実施。
新しい研究コード・記録はローカルの未コミット状態。
