# タイポ補正モデル大型化の比較（2026-10-27）

## 結論

同じ追加学習データ・レシピで、約18.5MBのXSmall版から約64.3MBのSmall版へ変更すると、開発評価300問中のタイポ255問で正しい補正候補が26件から84件に増えた。誤提案も57件から41件に減少した。大型化は有効だったが、正答率32.9%であり、十分な補正品質に達したとは扱わない。

CPU補正処理のp95は初回30.3msから103.3msへ増加した。実IMEへの適用は行っていない。サイズ・遅延の増加を許容する場合の改善候補として保持し、採用前にWindows実IMEでの連続打鍵・古い結果の破棄・候補採用・取消を確認する必要がある。

## 比較条件

- 現行: `Miwa-Keita/zenz-v3.1-xsmall` snapshot `a728931c8b4867a53ca33d1ff3fe7360b0f15cd5`、6層・幅512。
- 大型: `Miwa-Keita/zenz-v3.1-small` snapshot `344612e331bf3ba752a9bfef6633c93c6b933ebb`、12層・幅768。ローカルキャッシュからオフラインで追加学習。
- 追加学習: LoRA r16/alpha32/dropout0.05、学習率0.0001、3epoch、batch32×累積2、最大256token、Trainer seed42。検証損失でcheckpointを選択。双方1881stepを選択し、検証損失は約1.3026から0.8291へ低下。
- ベースの事前学習済み重みも異なるため、パラメーター数だけを変えた因果実験ではない。同じLoRA設定でも学習対象パラメーター数は異なる。学習seedを振った複数モデルの比較は行っていない。
- tokenizerのvocab、merges、設定、special-tokenファイルのSHA-256は両ベースで一致。
- 追加学習40,111行・検証3,081行。現行版と同一ファイルを使用。学習↔検証・学習↔評価・検証↔評価の正規化キー重複、評価由来行、学習↔検証の元グループ重複は全て0。両checkpointの`dataset-provenance.json`に記録。
- 評価用の正解、採点規則、補正ルール、設定は変更していない。個人採用履歴を読み込まず、各問で補正キャッシュを消去。
- 同じ新規ビルドの`evaluate_corrector.exe`から、現行Windows補正関数を直接呼び出した。通常候補は固定のStandardモデルから独立に生成。Swift辞書・TSF・画面操作は含まない。
- 両モデルともQ4_K_M、同じCPU版llama-server、GPU層0・threads4・context2048。temperature0・seed42・prompt cache無効。補正呼出しの待ち時間設定450msを維持。配備中correctorのcontext512とは異なる。
- 別の専用モデルプロセスを所有し、終了時に停止。300問はXSmall→Small→Small→XSmallの順で2回ずつ測定し、補助35問は各1回測定。学習・変換・ビルド終了後に逐次実行。

## 開発評価300問の結果

`data/typo_benchmark_300.jsonl`のタイポ255問・正常45問を使用。正解または既存acceptableと一致した補正候補を正答とした。正しい通常候補だけでは「正しい補正候補」に加算しない。

| 指標 | XSmall（現行） | Small（大型） |
|---|---:|---:|
| モデルサイズ（bytes） | 18,452,288 | 64,282,688 |
| 正しい補正候補 / タイポ255問 | 26（10.2%） | 84（32.9%） |
| 誤った補正候補 / タイポ255問 | 57 | 41 |
| 提案した補正の正答割合 | 31.3% | 67.2% |
| 補正候補なし / タイポ255問 | 172 | 130 |
| 通常候補または補正候補に正解あり | 30 | 87 |
| 正常45問への誤提案 | 1 | 0 |
| 誤自動適用 | 0 | 0 |
| 通信失敗・クラッシュ・出力上限到達 | 全て0 | 全て0 |
| 補正処理 p50（初回、ms） | 20.9 | 73.8 |
| 補正処理 p95（初回、ms） | 30.3 | 103.3 |

2回目も通常候補・補正候補・自動適用判定・クラッシュ状態は両モデルで全300問一致した。2回目のp50/p95はXSmallが20.7/28.5ms、Smallが74.7/104.3ms。補正処理時間には通常候補生成・Swift・IPC・TSF・表示更新を含まないため、打鍵から表示までの総遅延ではない。

Smallで正答に改善した62件、逆に正答を失った4件。評価セットには同じ入力の重複があるため、独立した66種類の事象を意味しない。正答を失った例には「かきょうへんすうをへんこう」「すたっくとれーすをかくに」がある。

モデルが実際に呼ばれたタイポ254問で、ルールによる絞り込み前の生出力の正答は27件から89件へ増えた。生出力が正答でも最終補正にならなかった例は2件から5件。改善の主因は生成側にあり、今回の結果から単に絞り込みを緩めることは勧めない。この数値はモデルが呼ばれた問だけの条件付き集計であり、全問をモデル単独で評価した値ではない。

改善例:

- `さしかえをあねがいします` → `差し替えをお願いします`
- `ごったいおうおねがいします` → `ご対応お願いします`
- `どういいたさlます` → `同意いたします`

残る誤提案例:

- `しょうんちいたしました` → `承認いたしました`（期待: `承知いたしました`）
- `じゅしゅしてください` → `実施してください`（期待: `遵守してください`）
- `ごつごんうはいかがでしょうか` → `ご都合うはいかがでしょうか`

正答を得られなかった171/255問の内訳は、誤提案41問・提案なし130問。正常入力・誤自動適用の0件は、この小さなケース集合での観測に限る。固定セットは既に開発に使用しており、未使用の最終テストや、事前学習との非重複保証ではない。

## 補助35問

`benchmarks/research/typo_testset.jsonl`は全てタイポで正常入力を含まない。

| 指標 | XSmall | Small |
|---|---:|---:|
| 正しい補正候補 / 35問 | 16 | 18 |
| 誤った補正候補 | 3 | 4 |
| 通常候補または補正候補に正解あり | 17 | 19 |
| 補正処理 p95（ms） | 17.9 | 74.0 |
| モデルを呼んだ17問だけの補正処理p50（ms） | 11.0 | 39.4 |

改善した2問は`おきょくさん`→`お客さん`、`でわ`→`電話`。この集合は既存明示ルール等の影響が大きく、モデルが呼ばれたのは17/35問。300問ほどの伸びはなく、誤提案も1件増えた。両モデルとも誤自動適用・通信失敗・クラッシュ・出力上限到達は0。

## モデル・実験記録

- 現行: `artifacts/models/corrector-v2-20261027-q4km.gguf`
  - SHA-256 `d817a1403c9e55e16c57b462fc53db2c7398881e1ce607408e004ea6fea33d39`
- 大型: `artifacts/models/corrector-size-small-20261027-q4km.gguf`
  - SHA-256 `a9f259bb7d0e10a4390b98f4cd9ba813c3509b7b5c53b853e0ec82ff9ab4903a`
- LoRA: `artifacts/checkpoints/corrector-size-small-20261027-lora/`
- マージ済み: `artifacts/checkpoints/corrector-size-small-20261027-merged/`
- 学習・exportログ: `artifacts/logs/corrector-size-small-20261027-{training,export}.log`
- 個別結果・ハッシュ・比較集計: `artifacts/results/corrector-size-20261027/`の`experiment.json`、`{xsmall,small}-{300,35}-run*.json`、`comparison.json`。比較集計用`analyze.py`も同フォルダに保存。

評価器のビルド:

```powershell
cargo build --manifest-path engine/rust/Cargo.toml --target-dir artifacts/build/corrector-size-20261027 --example evaluate_corrector
```

各モデルの評価（出力先は未使用のパスを指定）:

```powershell
python scripts/run.py benchmarks/research/benchmark_corrector_pipeline.py --corrector artifacts/models/corrector-size-small-20261027-q4km.gguf --normal artifacts/models/zerocon/zerocon-model-standard.gguf --runner artifacts/build/corrector-size-20261027/debug/examples/evaluate_corrector.exe --cases data/typo_benchmark_300.jsonl --out artifacts/results/corrector-size-20261027/small-300-run1.json
```

## 作業範囲

既存の学習・export・GGUF変換・量子化・評価コードで実験した。製品のソース・補正閾値・設定・モデルカタログは変更していない。Windows試験IMEへの適用、入力アプリでの大型モデル確認、インストーラー作成、commit・pushは未実施。モデル・checkpoint・評価JSONはGit対象外。既存の無関係な変更を保持し、ブランチは`feature/windows-color-themes`のまま。

実行確認: 評価器のcargo build成功、追加学習完了・来歴のtraining_completed=true、export・GGUF変換・量子化成功、6回の評価完走、300問の全出力再現性検査成功、dataset_isolation.validate_rowsによる分離再確認成功。汎用check_dataset_isolation.pyはseparated-v1用manifestスキーマを想定し、この補正用manifestではKeyErrorになったため、学習前ガードと同じvalidate_rowsで直接検証した。
