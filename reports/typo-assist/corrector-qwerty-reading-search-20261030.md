# QWERTYの読み候補探索とNano v4採点（2026-10-30）

## 結果

生のキー列から1回の物理編集で説明できる読みを作り、**既存Nano v4を読みの尤度採点に使用する追加経路**を実装した。
読みを全文生成するのではなく、元入力を含む最大24候補を比較する。
前回の補正候補を保持し、候補がないケースにだけ通常モデルで表記へ変換した手動候補を追加する。
新しいLoRA学習や重みの置き換えはしていない。

| 表記完全一致/事前固定の許容表記 | 前回の状態＋キー検査 | 今回の追加経路あり |
|---|---:|---:|
| 固定testの正しい補正 / 192 | 92（47.92%） | 123（64.06%） |
| 固定testの誤修復提案 | 32 | 40 |
| 固定testの保持への誤提案 / 128 | 0 | 0 |
| 固定testのケースF0.5 | 0.6686 | 0.7287 |
| 新作文章の正しい補正 / 100 | 11 | 69 |
| 新作文章の誤修復提案 | 12 | 18 |
| 新作文章の保持への誤提案 / 40 | 1 | 1 |
| 新作文章のケースF0.5 | 0.2806 | 0.7633 |

正しい補正数は増えた一方、誤った提案も増えた。新候補は全件手動採用とし、自動適用を拡大していない。
これは合成開発評価の結果。登録済みIMEへの適用、TSF/GUI操作、実ユーザーの頻度に基づく品質は未確認。

## 実装した処理

`benchmarks/research/qwerty_reading_search.py`:

1. 観測したASCIIキー列と編集状態だけを読む。正規キー、正解読み・表記、誤り位置、期待動作を候補生成へ渡さない。
2. 隣接キー置換・脱落・挿入・転置1回のキー列を列挙する。元入力を変更しない候補を必ず残す。
3. 近似ローマ字decoderと逆辞書のtrie/Viterbiスコアで候補を絞り、採用候補のキー列をWindows DLLで実変換して検証する。
4. 元入力を含む最大24読みを、既存のネイティブllama採点器でCPU一括採点する。
5. `辞書スコア + weight × log P(読みと終了境界 | EE00)`で比較し、元入力からの改善幅がmarginを超えた場合だけ候補を返す。

キー列は小文字英字96文字まで。raw不明、削除直後、末尾未確定子音では追加提案しない。
近似decoderは探索の前段だけで使うが、探索前の見逃しは残る。全物理編集の完全列挙からのnative一致を保証する方式ではない。
1回の編集という上限も、今回の合成誤りへ合わせた研究上の制約である。

`evaluate_qwerty_reading_surface.py`:

- 前回QWERTY＋状態/キー検査の提案があるケースは、その提案と自動適用フラグをそのまま保持。
- 空いているケースだけ、採点で選んだ読みを固定Standardモデルで表記へ変換する。
- 正解表記や正規読みを通常モデルのpromptへ渡さない。文脈＋候補読みだけを渡す。
- 生成が打ち切られず、制御文字を含まず、表記から候補の読みへの整合を逆辞書で確認できた場合だけ追加。
- 追加候補は必ず`auto_apply=false`。

新候補は既存Rust補正関数へ再投入していない。原入力とのかな文字距離の検査の代わりに、
物理キー編集の実変換による証拠と、表記・読み整合で受理する研究経路を構成した。
既存の浮いた子音復元や読み距離判定に最終候補を固定されずに評価する意図がある。
製品の補正ポリシーを更新・適用した結果とは区別する。

## 使用モデルと比較の固定

- 読み採点: `artifacts/models/zerocon-nano-v4-q4km.gguf`
  - SHA256 `473028f8f7cf5fd1acf48142124a9ce90044468ecc4939adea473614cfcdbc7e`
  - tokenizer: `artifacts/checkpoints/nano-v4-argon4-merged`
- 採点器: `artifacts/build/keyboard-hybrid-20261027/keyboard_prior_score_masked.exe`
  - SHA256 `48023f7be8230e6613179165c75eca346ba8939bc6243f2dd4306ef2b766b6d2`
  - prefixの全tokenを採点対象から除外する既存プロトコル。CPU4スレッド。
- 既存補正: `artifacts/models/zerocon-corrector-qwerty-v4-q4km.gguf`
  - SHA256 `03657230977398d64d9a40d2826e98fe62d5660743cfe29c74c297a8fb32d113`
- 表記生成: `artifacts/models/zerocon/zerocon-model-standard.gguf`
  - SHA256 `94c290aa772ae9f66bca45842dd506dbedeba5ef6f67368189261ff7364f25f2`
- 辞書: `ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin`
  - SHA256 `0dc52727c4e403184dcdd365c2817ddd246369a1ea9b3025ae13c5b5bca40621`

モデル・辞書・DLL・tokenizer・fixture・ソースの識別情報を結果とpost-run auditへ保存した。
実際の候補キー列・native読み・スコア、表記生成promptと応答も保存。
新たな単一GGUFを学習したものではなく、既存モデルと新しい候補探索/校正パラメータを組み合わせる方式。
Nano読み採点モデルは既存補正モデルとは別に必要となる。

候補上限24、weight `[0,1,2,4,8]`、margin `[0,2,5,10,20,30,40,60,80]`を比較前に固定。
読みのみの診断はvalidationの読みF0.5、保持誤提案1件以内・待機0件で選択。
表記を含む追加経路は、別途同じvalidationで表記F0.5と既存の保持/待機/誤自動適用/canary上限を使い選んだ。
追加経路なしも候補に含め、改善しなければそのまま維持できるようにした。
各段の選択をtest/freshの採点前に保存し、testを見て閾値を変更していない。

使用したtest/freshは過去の開発で観察済みであり、今回も独立した一度限りの封印試験とは呼ばない。
既存の学習データ・重み・評価問題を変更せず、新しい学習にも投入していない。

## 読み候補の診断

表記生成を含めない、候補読みの完全一致での結果。表記の正答率と混同しない。
読み段の選択はweight8、margin40。

| 集合 | 修復件数 | 24候補内に正解読みあり | 実際に正解読みを選択 | 誤提案（正常含む） |
|---|---:|---:|---:|---:|
| validation | 192 | 188 | 155 | 20 |
| 固定test | 192 | 186 | 143 | 31 |
| 新作文章 | 100 | 88 | 85 | 6 |

validationの読み段は保持への誤提案1件、test/freshは0件、待機は全集合0件。
新作文章で残る12件は候補内に正解読みがなく、探索・枝刈りの見逃しをさらに分ける必要がある。

## 表記を含む追加経路の選択と結果

表記段の選択はweight4、margin40。読みだけの最良条件をそのまま採用せず、実際の表記出力で校正した。

| 指標 | validation 前→後 | 固定test 前→後 | 新作文章 前→後 |
|---|---:|---:|---:|
| 正しい補正 | 98→131 /192 | 92→123 /192 | 11→69 /100 |
| 誤修復提案 | 20→22 | 32→40 | 12→18 |
| 修復への無提案 | 74→39 | 68→29 | 77→13 |
| 保持への誤提案 | 1→1 /224 | 0→0 /128 | 1→1 /40 |
| 待機への提案 | 0→0 /64 | 0→0 /64 | 0→0 /20 |
| ケースF0.5 | 0.7335→0.8106 | 0.6686→0.7287 | 0.2806→0.7633 |
| 誤自動適用 / 自動適用件数 | 2/12→2/12 | 0/9→0/9 | 0/0→0/0 |

固定testの全提案適合率は92/124=74.19%→123/163=75.46%。
新作文章は11/24=45.83%→69/88=78.41%。
正常保持を変えずに取りこぼしを減らしたが、既存の誤提案を維持する構成でもあり、誤修復数自体は増えた。

追加分の内訳:

- 固定test: 39提案を追加。表記正解31、正解読みだが表記不一致4、別読み等4。
- 新作文章: 64提案を追加。表記正解58、正解読みだが表記不一致1、別読み等5。

読み一致を表記正解へ加算していない。表記不一致には許容別解の不足と同音異義語があり得るため、
自動で正解へ読み替えず、将来の別評価版で意味を含めて審査する。

元グループ単位paired bootstrap（seed42、5,000回）のF0.5差95%percentile区間:

- 固定test67グループ: `[+0.0246,+0.0988]`。macro F0.5 0.5890→0.6918。
- 新作文章20グループ: `[+0.3614,+0.6193]`。macro F0.5 0.2412→0.7599。

20文章から5誤りずつ生成した100タイポを独立な100文章と扱わない。
これらの区間は合成開発集合の範囲であり、実ユーザーの安全性や頻度分布を保証しない。
旧QWERTYラベル回帰はraw不明のため追加経路の対象外で、前回の40/150・正常誤提案2/45という課題は残る。

## 実行確認と遅延

正解を受け取らない研究CLI `run_qwerty_reading_assist.py`を追加した。
`siryouwookurimssu`、文脈`会議の`から、実推論で`資料を送ります`を得た。
候補キー`siryouwookurimasu`、読み`しりょうをおくります`、読み整合あり、`auto_apply=false`。
これは登録済みIMEでの表示・採用確認ではない。

追加経路の時間を、固定testからラベルを使わず選んだ24ケースで3巡測定した。
対象は「元の補正候補なし・rawあり・append状態」。起動・初期化、元の補正推論、TSF/表示時間は含めない。
Python探索＋CPUネイティブNano採点＋必要なStandard表記生成の実測である。

| 巡 | 追加処理p50 | 追加処理p95 |
|---|---:|---:|
| 1 | 80.7ms | 193.3ms |
| 2 | 84.8ms | 185.8ms |
| 3 | 82.8ms | 186.5ms |

高速化を達成したという結果ではない。モデル起動・ベース経路・画面表示を足した実IME遅延は未測定。
製品へ入れる際は背景実行、入力世代・取消の照合、候補上限・処理期限・prefix再利用を実装し、
通常候補とTab対象の安定性を含めて検証する必要がある。入力コールバックでこのPython処理を同期実行しない。

## 再現・確認・保存

新規出力名を指定する。既存の結果やモデルは上書きしない。

```powershell
python scripts/run.py benchmarks/research/test_qwerty_reading_search.py
python scripts/run.py benchmarks/research/test_qwerty_reading_surface.py
python scripts/run.py benchmarks/research/benchmark_qwerty_reading_search.py --out artifacts/results/<新しい読み探索名>
python scripts/run.py benchmarks/research/evaluate_qwerty_reading_surface.py --search-dir artifacts/results/<読み探索名> --out artifacts/results/<新しい表記評価名>
python scripts/run.py benchmarks/research/analyze_qwerty_reading_surface.py --out-dir artifacts/results/<表記評価名>
python scripts/run.py benchmarks/research/profile_qwerty_reading_surface.py --selection-dir artifacts/results/<表記評価名> --out artifacts/results/<表記評価名>/profile.json
python scripts/run.py benchmarks/research/run_qwerty_reading_assist.py --keys siryouwookurimssu --context 会議の --selection-dir artifacts/results/<表記評価名> --search-dir artifacts/results/<読み探索名> --log-prefix artifacts/logs/<新しいデモ名>
```

今回の主要成果物:

- `artifacts/results/corrector-qwerty-reading-search-20261030-r1/`: plan、validation選択、各ケースの候補・nativeキー・採点値、比較結果。
- `artifacts/results/corrector-qwerty-reading-surface-20261030-r1/`: 表記validation選択、生成prompt/応答、最終候補、比較結果、グループ解析、依存hash監査、profile、CLIデモ。
- ログ: `artifacts/logs/corrector-qwerty-reading-*.log`。

確認は`.venv_train/Scripts/python.exe scripts/run.py ...`で実施。
探索テスト3成功、表記追加テスト3成功。観測だけから候補を作ること、全候補のnative一致、
元入力との点差、既存候補の非破壊性、追加候補の手動限定、正解フィールドの変更で提案が変わらないことを確認した。
`scripts/check_repository.py`と対象ファイルの`git diff --check --ignore-submodules=all`も成功。

今回は研究用ソース・評価・CLIまで。Windows製品コード、設定、モデル重みは変更していない。
Windowsビルド・実IME適用・GUI確認・インストーラー作成・コミット・pushは未実施。
ブランチは`feature/windows-color-themes`のまま、既存の他作業の差分を保持している。
