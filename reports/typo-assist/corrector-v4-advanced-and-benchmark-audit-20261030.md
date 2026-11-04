# Corrector v4の追加学習と評価監査（2026-10-30）

## 現時点の判断

**今回のDoRA・蒸留候補は採用しない。** 検証生成は180/256から197/256へ増えたが、
固定300問での正しい補正は71/255から68/255へ減り、正常への誤提案も1件から2件へ増えた。
新手法を多数組み合わせるだけでは、実用側での改善を確認できなかった。前回の選定重みを保持する。

従来corrector v4の補正再現率はIQ4で71/255 = **27.8%**、Q6で77/255 = **30.2%**。
提案されたタイポ候補だけの正答割合も62.8% / 63.1%であり、取りこぼし・誤提案の両方が多い。
誤提案を減らした前回の成果と、十分な補正性能に達したことを区別する。

現行300問は合成回帰用には使えるが、Windows IMEの補正品質を代表する単独指標としては不十分。
低得点の全てをベンチマークの問題とすることもできない。以下の監査と、通常モデルv4の知見を移した追加学習を行う。

## ベンチマークの実体

- 300問は、文脈＋正解読みが**80組**。元の読みも80種類で、独立な300種類の問題ではない。
- タイポ255件・正常45件。正常の異なる読みは**36種類**。
- フリック由来40件をWindows QWERTYと同じ合計に含めている。
- 全300件で生の打鍵列・編集位置の来歴がなく、実IMEの入力途中状態を再生できない。
- 許容表記`acceptable`は全件なし。「お振り込みください」対「お振込みください」、
  「おめでとう！よかったね」対「おめでとう！良かったね」も完全一致では誤答になる。
  これらはレビュー対象として挙げるが、今回の旧スコアを有利に再採点したり、正解ファイルを変更したりしない。
- 正解読みとしてかな以外を含む行が32件。元の80種の種データに漢字交じり表記や句読点があり、
  「読みの復元率」をそのまま採点できるラベルではない。32件を全て誤ラベルと断定するものではない。
- `generate_benchmark_300.py`は指定変異が変化を起こさない場合にフリック変異へフォールバックするが、
  `typo_type`を変更しない。実データでは`prolonged`15件のうち13件に元の長音符がなく、このラベルで長音性能を測れない。
- 元読みとのOSA距離は、1文字178件、2文字70件、3文字6件、4文字1件。

通常のかな漢字変換は正しい読みから表記を返す問題であり、誤入力の検出・復元・変換・辞書検査を含む補正と
単純な正答率の大小では比較できない。ただし、補正モデルの低い再現率を容認する理由にはしない。

## 正解を返すoracleでの診断

モデル応答を正解表記へ置き換え、通常候補は独立した通常モデルの生成を維持し、製品の補正ポリシーをそのまま通した。
製品コード・読み距離の閾値・自動適用条件は変更していない。これはモデル精度や遅延の測定ではない。

- 正しい補正として通る: **222/255件**。通常候補との合算正解は223件。
- 正解表記でも補正候補にならない: **33件**。
- うち辞書が推定した表記読みとの距離が3以上: 28件。
- その28件中、データに記録された正解読みとの距離は2以内なのに、辞書読みとの距離が3以上になるもの: **21件**。
- 例: `たんっとうにかわります` → `担当に代わります`。記録された正解読みとの距離1に対し、
  現行辞書経路での距離は5。正解を生成するモデルでも現在の検査を通れない。
- 残る5件は未確定子音などのガード・既定経路が関わる。分類は拒否条件の手掛かりで、全経路の実行トレースではない。

一方、旧IQ4のモデル呼出しで正解表記が生出力として見られたのは74ケース、Q6は80ケース。
そのうち最終的に正解候補にならなかったものは各4件。現在の低成績の大部分は、
「正解を生成したのにフィルターで落ちる」だけでは説明できない。呼出し前にローマ字復元される場合もあり、
この74/80を独立した純粋モデル単体ベンチマークの正答率とは呼ばない。

## 正常入力・自動適用の測定量

IQ4の正常への誤提案は1/45だが、単純二項モデルでさえWilson 95%区間は約0.4〜11.6%。
実データは元語句が重複しており、この区間で実使用の誤補正率を保証できない。
また誤自動適用0件の実際の適用分母は**2件**。300件すべてに自動適用して成功した結果ではない。

## 通常v4から移す手法

前回はr16 LoRAによるcorrector方式の移植が中心で、通常Nano/Standard v4の学習手法を十分に移していなかった。
今回の候補は次の構成とする。

| 手法 | 補正用への適用 |
|---|---|
| rank64 DoRA、Attention＋MLP | `c_attn`・`c_proj`・`c_fc`を更新し、表現能力を増やす。マージ後は通常の6層重みとなり、層数は増やさない |
| 凍結した大型教師 | 同じ原学習分割で訓練済みのSmall correctorを使用。通常変換に強いことだけを理由にタイポ教師へ流用しない |
| Target-Corrected KL | 教師が誤る位置は正解logitと最上位logitを交換してから蒸留し、教師の誤りをそのまま押し付けない |
| 自己アンカー | adapter無効時の既存Nano v4由来correctorを凍結基準にし、既存の正解能力を保護する |
| 誤り位置重視 | アンカーが正解を外すトークンを重くし、正解と異なる出力だけにmarginを課す。正常入力の正しいコピーを一律に罰しない |
| EOS抑制 | 正解EOS位置だけで後続のPUA・句点等とのmarginを課し、余計な追記を抑える |
| 層別学習率 | 下位2層0.45倍、中位2層1倍、上位2層1.35倍。DoRA magnitudeはweight decayから除く |
| 学習の整合 | dropoutを無効化、親をFP32で保持、BF16 autocast、全正解とEOSの保持、勾配累積の損失正規化を明示 |

DoRAの構造は[原論文](https://arxiv.org/abs/2402.09353)を参照。上記の補正性能への効果は今回の比較で判断し、
個々の施策に差分を帰属するには別のアブレーションが必要。

教師とstudentの語彙IDは完全一致。教師の既知の学習行と検証のキー重複は0。
教師は過去に同系統validationでcheckpointを選んでおり、検証への一切の露出がないという意味ではない。
現行manifestの`teacher_training_validation_overlap`の0は、既知の**教師学習行との重複**を指す。
初回試作では同じ値を`teacher_validation_exposure`と記録しており、意味を曖昧にしないため名称を明確にした。

入力・データは形式整理済み38,435 / 3,010行。既存モデル・設定・評価正解・個人履歴を保持する。
新評価の基準は[評価契約](../../docs/corrector-evaluation.md)に整理した。

## 実行とモデル選択

初回の2epoch試作は検証生成198/256（正常121/128・タイポ77/128）まで改善した。
これは教師・アンカーを用いた複合損失の学習であり、CEだけの損失値と数値の大小を比較しない。
実装点検でcustom lossがmicrobatch平均を返す場合の勾配累積スケールを明示し、
修正後の`corrector-v4-advanced-20261030-dora64-r2`を同じ初期重みから2epoch再学習する。
初回の重み・ログ・実行コードは保全し、正式候補は修正後の実行から選ぶ。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/test_corrector_v4_advanced.py
.\.venv_train\Scripts\python.exe scripts/run.py training/train_corrector_v4_advanced.py --run-name corrector-v4-advanced-20261030-dora64-r2
.\.venv_train\Scripts\python.exe scripts/run.py training/build_corrector_v4.py --run-name corrector-v4-advanced-20261030-dora64-r2
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/evaluate_corrector_v4_validation.py --run-name corrector-v4-advanced-20261030-dora64-r2 --reference previous-v4=artifacts/models/zerocon-corrector-v4-iq4nl-emb8.gguf --out artifacts/results/corrector-v4-advanced-20261030-dora64-r2-validation.json
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/run_corrector_v4_comparison.py --run-name corrector-v4-advanced-20261030 --validation artifacts/results/corrector-v4-advanced-20261030-dora64-r2-validation.json --extra-models previous-v4 --runner artifacts/build/corrector-v4-advanced-20261030/debug/examples/evaluate_corrector.exe --out-dir artifacts/results/corrector-v4-advanced-20261030/pipeline
```

checkpointは正常・タイポ各128件の等重み生成一致率で選ぶ。量子化の比較も同じ検証集合で行い、
その後に固定300問を測る。生の生成と製品ポリシーでの成績が同じではないため、
このcheckpoint選択だけで最良の実用補正品質を保証するものではない。

正式再実行は377.8秒、2epoch目のcheckpoint-2404を選択。学習可能パラメータは3,173,376。
既存r16 LoRA版の540,672から適応容量を増やしたが、マージ後の層数・幅・語彙は同じ。
試作版も正式再実行も同じ元のcorrector v4重みから初期化し、試作の重みをさらに継続学習した結果ではない。

### 量子化後の同じ検証集合

| モデル | 正常 / 128 | タイポ / 128 | 合計 / 256 |
|---|---:|---:|---:|
| 旧zenz v3.2 corrector Q4 | 118 | 57 | 175 |
| 前回corrector v4 IQ4 | 120 | 60 | 180 |
| 新DoRA版 F16 | 120 | 76 | 196 |
| 新DoRA版 Q4 | 120 | 75 | 195 |
| 新DoRA版 Q6_emb8 | 120 | 75 | 195 |
| 新DoRA版 Q8 | 120 | 77 | 197 |
| **新DoRA版 IQ4_emb8** | **120** | **77** | **197** |

Q8とIQ4が同率のため、同点なら小さい重みを選ぶ規則でIQ4を選んだ。
新しいIQ4は19,602,112 bytes（18.69 MiB）、SHA-256
`a4b42af81a13bde66e54771e2031e8c64e359b91899b8983ac0522496c353367`。
選択結果を保存してから300/35問を測定する。モデルを合わせるために評価の正解を変更していない。

これは検証上の候補選択であり、製品への採用ではない。研究用の元ファイル
`artifacts/models/corrector-v4-advanced-20261030-dora64-r2-iq4nl-emb8.gguf`を保持する。
前回モデル・配布カタログは置換しない。

### 固定300問を同じポリシーで再測定

| モデル | 正しい補正 / 255 | 誤提案 / 255 | 正常への誤提案 / 45 | 通常候補との合算正解 / 255 | p50 / p95 ms |
|---|---:|---:|---:|---:|---:|
| 旧zenz v3.2 Q4 | 71 | 49 | 5 | 76 | 25.48 / 35.26 |
| 前回corrector v4 IQ4 | 71 | 42 | 1 | 75 | 25.41 / 35.18 |
| 新DoRA版 Q4 | 69 | 35 | 7 | 74 | 24.87 / 34.24 |
| 新DoRA版 IQ4 | 68 | 41 | 2 | 73 | 25.28 / 34.38 |

正常への誤提案も偽陽性に含めたケース単位F0.5は、前回IQ4 0.4993に対し新IQ4 0.4864、新Q4 0.4936。
新Q4はタイポへの誤提案を減らすが、正常への誤提案が7件へ増えており置換しない。
補助35問では前回17正答・7誤提案、新IQ4と新Q4は各18正答・8誤提案だった。
全8回でクラッシュ・通信失敗・生成上限到達・誤自動適用は0。
遅延はCPU・4threads、学習・校正後の逐次1回測定で、TSF表示時間でも反復された優劣でもない。

正解を新たに得た17件に対して、前回の正解を失ったものは20件。
`同いいたします`→`同意いたします`、`承認いたしました`→`承知いたしました`のような改善もあるが、
`来週の水曜日です`の代わりに`来週の予定日です`、`ギターを始める`の代わりに`コーヒーを食べる`を
生成する退行もあった。これらの大きな読み変更は現行ガードで拒否される。
単なる許容表記不足だけでは退行を説明できず、入力への忠実さと未学習の語句・誤りへの汎化が課題として残る。

新IQ4で正解が生出力として見られたのは69ケースで、正解出力から提案にならなかったものは2件。
前回の74ケース・拒否4件からの変化なので、今回の低下の主因を辞書ガードの強化へ帰属しない。
元読みで平均したmacro再現率は前回27.92%・新IQ4 28.02%とほぼ同じで、microの件数だけでなく元グループの偏りも見る必要がある。

## 次に優先する変更

1. **評価用の入力・正解を検証する。** 元の300問は保存し、Windows実変換器で再生できる打鍵列と、
   かなで検証した正解読み、事前レビューした許容表記を持つ別の固定集合を作る。フリック・保留・正常入力を別集計にする。
2. **validationの選択基準を実用経路へ揃える。** 生の生成一致率だけの改善では今回の退行を検出できなかった。
   未学習元グループの代表性、正常への誤提案、実ポリシーでの正しい候補を合わせて選ぶ。
3. **学習では局所修復と不変部分を強く扱う。** 語句全体をもっともらしい別文へ置き換える誤りを、
   同じ語句の正常／誤入力ペアと編集位置の来歴で抑える。今回の固定300問の正解を学習へ転記しない。
4. **辞書の読み根拠を別途改善する。** 正解を拒否する21件のようなケースを独立した回帰テストへ落とし、
   読みの選び方を検討する。誤提案を増やして点を上げる距離閾値の一括緩和は行っていない。

独立した実打鍵locked testの整備と実IME操作の測定は未実施。性能改善は今回の実験では未達であり、
候補のvalidation向上を実用精度の向上として報告しない。

## 成果物と検証の保存先

- 監査: `artifacts/results/corrector-benchmark-audit-20261030-r2.json`。
- 最終監査と新旧の対照: `artifacts/results/corrector-v4-advanced-20261030/audit-final.json`、`paired-errors.json`。
- 固定300/35問の全出力: `artifacts/results/corrector-v4-advanced-20261030/pipeline/`。
- 全量子化版の検証: `artifacts/results/corrector-v4-advanced-20261030-dora64-r2-validation.json`。
- 正解oracle: `artifacts/results/corrector-v4-advanced-oracle-300-20261030.json`。
- 初回の学習コード保存: `artifacts/results/corrector-v4-advanced-20261030/source-pilot/`。
- 学習・export・評価ログ: `artifacts/logs/corrector-v4-advanced-*`。
- gradient isolation・logit交換・optimizer・累積設定のテスト: `training/test_corrector_v4_advanced.py`、2成功。
- 監査集計・距離・区間・同音語の採点・oracle分離テスト: `benchmarks/research/test_corrector_benchmark_audit.py`、4成功。
- Rust評価器のoracleと読みadapterテスト: 2成功。
- `cargo test --offline --manifest-path engine/rust/Cargo.toml --target-dir artifacts/build/corrector-v4-advanced-20261030 --example evaluate_corrector`: 成功。
- `python scripts/run.py benchmarks/research/test_corrector_benchmark_audit.py`: 4成功。
- `python scripts/check_repository.py`: 1,641ソースの構文・JSON・生成物除外を検査、成功。
- 変更Rustのrustfmt、対象差分と新規ファイルの空白検査、旧重み・旧評価ファイルのSHA-256保全、全比較の通常候補・辞書・runtime・評価器の同一性確認: 成功。
- 採用しなかった研究モデルと選択理由: `artifacts/results/corrector-v4-advanced-20261030/research-model-card.json`。

実IMEへの適用、TSF画面操作の確認、インストーラー作成、commit・pushはこの作業に含めていない。
