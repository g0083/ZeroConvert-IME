# Corrector v4 — Nano v4を基盤とする補正専用モデル（2026-10-30）

## 作成モデルと選択結果

最終選択は **r1 IQ4_NL（埋め込み・出力Q8_0、18.69 MiB）**。
形式整理後の同じ検証256件で旧版175件から180件へ改善した。
仕上げ学習r4のQ6/Q8も180件で同率だったため、事前の「同点なら小さいモデル」の規則でr1 IQ4を選んだ。
この選択は固定300/35問の測定を始める前に`final-validation.json`へ保存した。

- 最終モデル: `artifacts/models/zerocon-corrector-v4-iq4nl-emb8.gguf`。
- 元ファイル: `artifacts/models/corrector-v4-nano-20261030-r1-iq4nl-emb8.gguf`。
- サイズ: **19,602,080 bytes**。
- SHA-256: **`6f1a0496440a3754b9360caee52ecafbf93131464a4f3637aef15b49c19c0776`**。
- 初期化から2epochの選択済みLoRAをFP32のNano v4へマージした重み。r4の追加LoRAは最終選択へ含めない。

| 同じ検証集合でのCPU生成 | 正常 / 128 | タイポ / 128 | 合計 / 256 | サイズ MiB |
|---|---:|---:|---:|---:|
| 旧zenz v3.2 xsmall corrector Q4 | 118 | 57 | 175 | 17.60 |
| **r1 IQ4_emb8（最終選択）** | **120** | **60** | **180** | **18.69** |
| r3 Q8（形式整理データで初期から学習） | 118 | 58 | 176 | 27.69 |
| r4 F16（仕上げ学習） | 121 | 59 | 180 | 50.06 |
| r4 Q4 | 122 | 55 | 177 | 17.60 |
| r4 Q6_emb8 | 121 | 59 | 180 | 23.33 |
| r4 Q8 | 121 | 59 | 180 | 27.69 |
| r4 IQ4_emb8 | 121 | 57 | 178 | 18.69 |

これは正解表記への厳密一致。正常入力の同義の表記違いも不正解となり、実補正経路での誤提案率とは異なる。
仕上げ学習は正常側の一致を増やしたが、合計を上回らなかった。
形式混在行の除外はデータ形式の改善であり、今回の学習条件では精度向上につながるとは確認できなかった。
最終選択r1には除外前の形式混在行が含まれる点を残る制約として記録する。
seed反復と未使用の最終評価は実施しておらず、小さな差を一般的な優劣とは扱わない。

## Windows補正ポリシーを通す固定300問の結果

モデル選択後に、現行Windows補正コードを直接コンパイルした同じ評価器で再測定した。
固定300問はタイポ255件・正常45件。正しい補正は既存のexpectedまたはacceptableへの一致。

| モデル | 正しい補正 / 255 | 誤提案 / 255 | 提案なし / 255 | 正常への誤提案 / 45 | p50 ms | p95 ms |
|---|---:|---:|---:|---:|---:|---:|
| 旧v3.2 xsmall Q4 | 71 | 49 | 135 | 5 | 25.88 | 36.17 |
| **v4 r1 IQ4_emb8（選択済み）** | **71** | **42** | **142** | **1** | **25.39** | **34.88** |
| v4 r4 Q4（仕上げ版） | 75 | 47 | 133 | 4 | 24.97 | 35.32 |
| v4 r4 Q6_emb8（仕上げ版） | 77 | 45 | 133 | 4 | 29.72 | 40.05 |

選択済みIQ4は正しい補正の件数を維持し、タイポへの誤提案7件と正常への誤提案4件を減らした。
取りこぼしを解消したとは言えず、通常候補との合算で正解を得られるタイポは旧版76件、IQ4は75件、Q4は79件、Q6は81件。
Q6は正しい補正が旧版から6件増える一方、IQ4より大きく遅く、正常への誤提案も多い。主モデルの事前選択は変更していない。

比較用の仕上げQ6も`artifacts/models/zerocon-corrector-v4-refined-q6k.gguf`として保存した。
24,468,128 bytes（23.33 MiB）、SHA-256 `28067b15a01308c95729f671321bba2843ecc0d657596539ec2b02f650148bdb`。

### 補助35問

| モデル | 正しい補正 | 誤提案 | 通常候補との合算正解 | p95 ms |
|---|---:|---:|---:|---:|
| 旧v3.2 xsmall Q4 | 17 | 8 | 18 | 23.73 |
| v4 r1 IQ4_emb8 | 17 | 7 | 18 | 24.99 |
| v4 r4 Q4 | 18 | 4 | 19 | 25.00 |
| v4 r4 Q6_emb8 | 17 | 6 | 18 | 29.92 |

35問は全てタイポで、既存の明示補正ルールの影響も大きい。正常入力への誤提案率の判断には使わない。
全8回でクラッシュ・通信失敗・生成上限到達・誤自動適用は0。この集合での0件は普遍的な安全性の証明ではない。
遅延は1回の逐次測定で、誤差幅や打鍵から表示までの時間は測っていない。

各モデルでケース、評価器、推論runtime、通常モデル、逆引き辞書のSHA-256と、実際に生成した通常候補が同一であることを照合した。
通常モデルは`artifacts/models/zerocon/zerocon-model-standard.gguf`（SHA-256 `94c290aa772ae9f66bca45842dd506dbedeba5ef6f67368189261ff7364f25f2`）。
評価器SHA-256は`dc0afa4f791dabd0f3571914c9d8fe099b5ec69156bac1d03fd2ec58e46ed313`。

- 最終選択・同一検証集合の全生成: `artifacts/results/corrector-v4-20261030/final-validation.json`。
- 固定300/35問の全ケースと集計: `artifacts/results/corrector-v4-20261030/pipeline/`。
- モデルcard、コピー元、ハッシュ、結果、来歴: `artifacts/models/zerocon-corrector-v4-model-card.json`。

## 作成方法と来歴

最新版のzenz v3.2 xsmall correctorで用いた、誤入力から正しい漢字交じり表記を直接生成するLoRA学習を、
ZeroCon Model Nano v4へ移した。かな修復と漢字変換を2モデルに分ける方式や、v4統合モデルの独自codecは使わない。
入力契約は既存の`U+EE02 文脈 U+EE00 未確定入力 U+EE01`（文脈は省略可）、出力は正しい表記とEOS。

- 起点: `artifacts/checkpoints/nano-v4-argon4-merged/`、6層・幅512、語彙6,000。
- 親の`model.safetensors` SHA-256: `b99a1b6ed530fd5b8d85551c944c4bbc64a615d249ca8d4617a560aa33ae27d0`。
- 比較旧版: `artifacts/models/corrector-v32-xsmall-q4km.gguf`、18,452,384 bytes、SHA-256 `dd87dbdc1a04cb4391f8429e6a1addc3c65d824996dd465e96803a5c1411fe2e`。現行カタログのcorrectorと同じハッシュ。
- 旧版の実保存設定: LoRA r16 / alpha32 / dropout0.05、`c_attn`・`c_proj`、学習率0.0002、3epoch、batch16×累積2、最大256token、seed42。
- v4は同じLoRA構成・実効batch32を維持し、batch32×累積1へ変更。凍結親重みはFP32で保持し、RTX 4070上でBF16 autocastを使う。追加学習パラメータ540,672。
- 正解全文・未確定入力・EOSを必ず残し、長い左文脈から先に削る既存`corrector_encoding.py`を再利用。BOS/EOS/PAD = 2/3/1を検査する。
- CE損失だけでなく、正常入力128件・タイポ128件の固定検証生成を毎epoch採点。各種別につき元グループ最大1件とし、2種別の完全一致率を等重みで平均してcheckpointを選ぶ。EOS未到達は不正解。
- F16 GGUF化後、学習行だけをハッシュ順に並べた校正コーパスでimatrixを作成（64 chunks、context512）。検証・評価の文章は校正へ入れない。
- Q4_K_M、Q6_K、Q8_0、IQ4_NLを生成。Q6/IQ4は埋め込みと出力をQ8_0で保持。量子化と最終候補の選択には固定300/35問を使わない。

## データの監査と改善

元データは最新版correctorと同じ`artifacts/datasets/corrector-v2-20261027/{train,val}.jsonl`（40,111 / 3,081行）。
既存のtrain/val分割と元グループを保持し、学習データを作り直すための評価正解の転記は行っていない。

1. 後から予約対象に加わった難文脈評価との重複キー7件を検出。関連する4グループ・学習874行を除外した。
2. Nano v4の既知の追加学習・検証データに露出した検証1グループ・15行も除外した。
3. 合成行の`is_clean`は元行から継承されていて信頼できないため、入力と正解読み／正解表記の正規化比較から正常入力を判定する。
4. 他タスクの`U+EE08`等を含む教師を発見。正解を推測して記号だけ除く方法は採らず、その形式の行を除外する版も作成した。

| データ | 学習行 | 検証行 | 学習グループ | 検証グループ |
|---|---:|---:|---:|---:|
| 重複除外版 `corrector-v4-20261030` | 39,237 | 3,066 | 1,515 | 161 |
| 表記形式整理版 `corrector-v4-surface-20261030` | 38,435 | 3,010 | 1,504 | 159 |

形式整理版では学習802行・検証56行を除外。正常行は学習4,525・検証373行。
両版とも学習↔検証、学習↔評価、検証↔評価の正規化キー共有、元グループ共有、評価由来行が0。
形式整理版の教師出力にトークナイザーのUNKトークンが現れる行も0。
Nano v4の既知の追加学習データと検証のキー共有は0だが、zenzの元の事前学習データの露出は不明。
固定ベンチマークは過去にも参照した開発評価であり、完全な未使用最終テストとは扱わない。

## 比較した学習条件

| run | 初期重み | データ | 学習率 | epoch上限 | 選択epoch |
|---|---|---|---:|---:|---:|
| r1 | Nano v4 | 重複除外版 | 0.0002 | 3 | 2 |
| r2 | Nano v4 | 重複除外版 | 0.0001 | 4 | 4 |
| r3 | Nano v4 | 表記形式整理版 | 0.0002 | 3 | 3 |
| r4 | r1の選択済みマージ重み | 表記形式整理版 | 0.00005 | 2 | 1 |

r1の学習時間134.3秒、r2は173.4秒、r3は171.4秒、r4は83.43秒（学習とepoch検証を含むTrainer値）。
r1/r2の同じ旧検証集合では、最良の等重み生成完全一致率はそれぞれ68.36% / 67.19%。
学習率を半分にしたr2は改善しなかったためGGUFへ書き出していない。
形式整理後の検証集合は異なるため、この率をr3/r4の率と直接比較しない。
最終比較は形式整理後の同一256件へ旧版と各候補を通し直す。

## 実行手順と保存先

`training/README.md`のCorrector v4節に新しいrun名で実行する一般手順を記載。
実施した初期学習と仕上げ学習は次のコマンド。環境は`.venv_train`、モデル取得はoffline。
最終選択r1のデータを再生成する場合は、形式除外を追加する前の`source-v1/build_corrector_v4_dataset.py`を
runner経由で実行して新しい出力先を指定する。現行生成器の出力はr3/r4の形式整理版となる。

```powershell
.\.venv_train\Scripts\python.exe scripts/run.py training/train_corrector_v4.py --run-name corrector-v4-nano-20261030-r1 --dataset artifacts/datasets/corrector-v4-20261030
.\.venv_train\Scripts\python.exe scripts/run.py training/build_corrector_v4.py --run-name corrector-v4-nano-20261030-r1
.\.venv_train\Scripts\python.exe scripts/run.py training/train_corrector_v4.py --run-name corrector-v4-nano-20261030-r4 --base-model artifacts/checkpoints/corrector-v4-nano-20261030-r1-merged --dataset artifacts/datasets/corrector-v4-surface-20261030 --lr 0.00005 --epochs 2
.\.venv_train\Scripts\python.exe scripts/run.py training/build_corrector_v4.py --run-name corrector-v4-nano-20261030-r4
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/evaluate_corrector_v4_validation.py --run-name corrector-v4-nano-20261030-r4 --reference r1-iq4nl-emb8=artifacts/models/corrector-v4-nano-20261030-r1-iq4nl-emb8.gguf --reference r3-q8_0=artifacts/models/corrector-v4-nano-20261030-r3-q8_0.gguf --out artifacts/results/corrector-v4-nano-20261030-r4-validation.json
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/run_corrector_v4_comparison.py --run-name corrector-v4-final-20261030 --validation artifacts/results/corrector-v4-20261030/final-validation.json --extra-models q6k --out-dir artifacts/results/corrector-v4-20261030/pipeline
```

- データと分離・除外manifest: `artifacts/datasets/corrector-v4{,-surface}-20261030/`。
- LoRA・checkpoint・選択状態・親とコードのハッシュ: `artifacts/checkpoints/corrector-v4-nano-20261030-r{1,2,3,4}-lora/`。
- 学習・量子化・推論ログ: `artifacts/logs/corrector-v4-*`。
- モデルとimatrix: `artifacts/models/corrector-v4-nano-20261030-r*-*`。
- 校正行とexport manifest: `artifacts/datasets/corrector-v4-nano-20261030-r*-imatrix/`。
- r1/r2時点のデータ生成コード等の保全: `artifacts/results/corrector-v4-20261030/source-v1/`。記号除外を追加した現コードと区別する。

モデル・データ・評価JSON・ローカル保全コードはGit対象外。Gitへ保存されるソースや通常のバックアップの代わりにはならない。

## 検証と適用範囲

- `python scripts/run.py training/test_corrector_v4.py`: 4成功。継承ラベル、評価グループ全体の除外、固定検証抽出、他タスク記号の拒否を検証。
- `python scripts/run.py training/test_corrector_encoding.py`: 2成功。正解全文とEOSの保全。
- `python scripts/run.py data/test_corrector_dataset.py`: 2成功。
- `python scripts/run.py data/test_dataset_isolation.py`: 8成功。
- `cargo build --offline --manifest-path engine/rust/Cargo.toml --target-dir artifacts/build/corrector-v4-20261030 --example evaluate_corrector`: 成功。
- 同ターゲットの`cargo test --example evaluate_corrector`: 1成功。
- 同ターゲットの`cargo test --lib -- --test-threads=1`: 58成功・1除外。初回はサンドボックスのlocalhost通信制限で3件失敗したが、通信を許可した再実行で全成功。除外1件は明示したモデルendpointが必要な先読み実測テスト。
- `python scripts/check_repository.py`、変更対象の差分・新規ファイルの空白検査: 成功。

Windows補正ポリシーを直接コンパイルする評価器でモデル差を調べる。通常候補は固定の通常モデルの推論で生成し、
正解表記を通常候補として注入しない。個人の採用履歴は無効、ケースごとに補正キャッシュを消す。
CPU・4threads・GPU層0・temperature0。学習と校正を終えてから遅延比較を逐次実行する。
測るのは補正処理の時間であり、TSF・Swift辞書・入力アプリの描画を含む打鍵から表示までの時間ではない。

学習コード・データ監査・モデル生成・専用推論サーバーでの評価を実施。
製品のRust/Swift/Androidコード、モデルカタログ、設定、登録済みIME、既存の配布用重みは変更していない。
実IMEへの適用、入力アプリでの新モデルの操作確認、インストーラー作成、commit・pushは未実施。
作業ブランチは`feature/windows-color-themes`を維持し、既存の無関係な差分も保持した。
