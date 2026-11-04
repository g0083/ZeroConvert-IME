# QWERTY専用Corrector v4の追加改善（2026-10-30）

## 結果と採用範囲

Nano v4由来のQWERTY用研究候補を作成した。実際のWindowsローマ字変換DLLに新しい合成打鍵を通した
固定テストでは補正成功が79→92/192件、保持対象への誤提案が15→10/128件になった。
一方、従来のQWERTYラベル付き回帰セットでは誤提案が増えたため、汎用correctorの置き換えは保留する。
フリック用は今回作成していない。フリック合成誤りをQWERTY追加学習へ混ぜていない。

- 候補: `artifacts/models/zerocon-corrector-qwerty-v4-q4km.gguf`
- Q4_K_M、18,452,608 bytes（17.60 MiB）
- SHA256: `03657230977398d64d9a40d2826e98fe62d5660743cfe29c74c297a8fb32d113`
- 付属カード: `artifacts/models/zerocon-corrector-qwerty-v4-q4km.json`
- 中身は `corrector-qwerty-v4-20261030-wise-r2-a50-q4km.gguf` と同一。

現行のモデルストア、カタログ、設定、登録済みIME、配布物は更新していない。研究用候補の作成と、
実際のモデル推論＋Windows補正ポリシーの評価までであり、実IMEの改善を確認済みとは扱わない。

## データをQWERTYに合わせた

`data/build_qwerty_corrector.py` と `data/native_qwerty.py` を追加した。
従来の壊れたかなを逆ローマ字化して「実打鍵」と呼ぶ方法は使わない。
既存データの正規読み・表記・文脈だけから新しい正規キー列を作り、各ASCIIキーを
`AppendText`へ順番に渡して未確定読みを得る。正規キー列が元の読みに戻ることも確認する。

DLLは既存の `ZeroConvertIME-windows/azookey-server.dll`。
SHA256は `a3919de88b85a78cbc55a692838c363c150e411dc018de7218c257828c8b4181`。
独立Pythonプロセス内のComposingTextを使い、登録済みIMEへ接続・適用・学習通知は行わない。
resetは`RemoveText`を呼んでraw入力が空になったことを確認する。

| 分類 | 扱い |
|---|---|
| QWERTY隣接キー、キー脱落、母音脱落、重複、隣接転置 | 1回の物理キー編集と位置・前後キーを保存 |
| 正常なキー列、同じ読みに戻る別綴り | 保持 |
| 削除後の完成かなprefix | 全文へ補完せず保持 |
| 末尾の未確定子音・n | 入力途中として待機 |
| フリック方向・濁点等のモバイル専用破損 | 追加学習から除外 |

`canonical_keys` / `observed_keys` / `key_edit` / `native_observed` / 文脈 /
正規読み・表記 / `expected_action` を保存する。追加の推論入力としてrawキーを渡す契約には変更していない。
promptは従来どおり`文脈 + PUA区切り + 観測読み + PUA区切り`、出力は表記。

元グループを先に分割し、その後に新しい誤りを生成する。同一観測から正解が矛盾する103観測と、
分割をまたぐ衝突162グループを除外した。正規キーがnativeで戻らない90例と、表記・読みを
逆辞書で検証できない10例も除外した。借用した元グループは過去の開発で利用されているため、
歴史的に未使用の外部テストとは主張しない。

確定したデータ `artifacts/datasets/corrector-qwerty-native-20261030-r2`:

| 分割 | 行 | 元グループ | 修復 | 保持 | 待機 |
|---|---:|---:|---:|---:|---:|
| train | 22,151 | 1,136 | 14,434 | 2,829 | 4,888 |
| validation | 1,220 | 64 | 795 | 159 | 266 |
| test | 1,303 | 67 | 843 | 166 | 294 |

表記の許容別解は、モデル評価前に固定した送り仮名の組だけを読み検証して付けた。
例は`貸出し/貸し出し`、`申込み/申し込み`。同音語全般を正解にしていない。

正常保持を強める追試には、`data/add_qwerty_preservation.py`で元グループの一致する既存正常行だけを加えた。
古い合成誤りは再利用していない。追加行にはraw不明を`null`で記録する。
`corrector-qwerty-native-20261030-r3`はtrain24,410行/1,134グループ、val1,354行/64グループ。
testはr2と同じ1,303行・同じSHA256のまま。validationには追加正常canary96件を固定した。

主要ハッシュ:

| ファイル | SHA256 |
|---|---|
| r2 train | `1123de7b7d26b9f135cd137d6014ee3c1c34a99ada9b6283ace7eb2b2401a0b4` |
| r2 val | `56424910b1dcbb0af0284f7abb96b0e5c8d44c703724a1cc08d41d2d025c412c` |
| r2/r3 test | `ed734b412916073087949eb383eac11db6576597f3a08565868f0247adad2cd9` |
| r3 train | `2e4e8b119b5dec49af1f3e33809031719946891e20f6c7d768bc30f4f14c6e93` |
| r3 val | `d8d5230753a6579c64da33f354dc9b34a47568f741538a92d29840e1406be1ec` |

## 学習・選択

`training/train_qwerty_corrector.py`を追加した。通常v4の知見から、Attention/MLPのrank32 DoRA、
FP32凍結親＋BF16 autocast、層別学習率、EOS margin、正常例3倍の再生、nativeポリシーによるcheckpoint選択を使う。
表記正解を通常候補へ注入せず、固定Standardモデルで独立生成した通常候補を用いる。
追加の大きい教師は使わない。損失は教師ありCEを例単位で平均し、補正以外の生成を増やさない。

### 追試と不採用候補

1. **QWERTY r1**: 原Nano v4 `nano-v4-argon4-merged`から開始、LR1.2e-4、3epoch。
   validationで最終epoch/step2610を選択。Q4で補正106/192、保持誤提案6/128、待機0/64。
   native testは92/192・保持9/128だが、旧回帰で補正25/150、正常誤提案10/45へ悪化し、不採用。
2. **QWERTY r2**: 既存corrector v4 `corrector-v4-nano-20261030-r1-merged`から開始。
   正常再生を加えたr3データ、LR4e-5、2epoch、adapterを無効化した親が正解に同意する正常/待機tokenだけのKLアンカーを使用。
   validation選択はepoch2。最良IQ4のF0.5は0.5615で、改善が小さい。
3. **重み補間**: 既存corrector v4とQWERTY r1のFP32重みを25/50/75%で補間。
   同じtokenizer・config・tensor形状を検証してから保存する。語彙/特殊tokenは変えない。
   trainだけのimatrixを使って各候補をQ4化。拡張validation480件だけで50%を選択した。

| 拡張validation | 補正成功/192 | 保持誤提案/224 | canary誤提案/96（保持の内数） | F0.5 | 判定 |
|---|---:|---:|---:|---:|---|
| 既存corrector v4 | 85 | 12 | 1 | 0.5505 | 基準 |
| QWERTY r2 IQ4 | 84 | 9 | 0 | 0.5615 | 適格 |
| QWERTY r1を25%補間 | 90 | 10 | 0 | 0.6081 | 適格 |
| QWERTY r1を50%補間 | 98 | 11 | 1 | **0.6380** | 選択 |
| QWERTY r1を75%補間 | 100 | 16 | 4 | 0.6250 | 保持退行で不適格 |

待機への提案は全候補0/64。誤自動適用は基準と全候補で2件（自動適用12件の内数）であり、解消していない。
選択条件は正常・待機・誤自動適用・canaryが基準を上回らない候補の最大ケース単位F0.5。
テストの採点を使って補間係数や量子化を選んでいない。ただし先行候補r1のテストは既に観察しているため、
この一連の開発に対する一度限りの封印テストとは呼ばない。旧回帰は継続的に参照する開発用回帰セットである。

最初の補間実行`wise-r1`は専用ポートの競合で推論開始前に停止。結果を混用せず、
`wise-r2`でポート8560に分けて全グリッドを再実行した。

## 固定テストの実測

`benchmark_qwerty_corrector.py`で実際のCPU llama HTTP推論とWindowsと同じRust補正ポリシーを使用した。
通常モデルは `zerocon-model-standard.gguf`（SHA256 `94c290aa772ae9f66bca45842dd506dbedeba5ef6f67368189261ff7364f25f2`）、
逆辞書は`reverse-v2.bin`（`0dc52727c4e403184dcdd365c2817ddd246369a1ea9b3025ae13c5b5bca40621`）に固定した。
比較対象は既存corrector v4 IQ4（`6f1a0496440a3754b9360caee52ecafbf93131464a4f3637aef15b49c19c0776`）。
実行時の全prompt・request・応答・モデル/実行器/ソースの識別情報を結果に保存した。
実際の応答だけを再生したポリシーとlive実行の提案・自動適用判定が全件一致することも確認した。

### Native QWERTY、384件/67元グループ

全test1,303件から、事前に固定した修復192・保持128・待機64件をグループ分散で抽出した。
以下はこの384件の結果であり、全1,303件を測定した値ではない。

| 指標 | 既存v4 | QWERTY v4 50%補間 |
|---|---:|---:|
| 正しい補正 / 修復192件 | 79（41.15%） | 92（47.92%） |
| 誤った補正提案 / 修復192件 | 52 | 45 |
| 修復への無提案 | 61 | 55 |
| 保持への誤提案 / 128件 | 15 | 10 |
| 上記のうち正常入力 / 76件 | 2 | 0 |
| 上記のうち削除後prefix / 52件 | 13 | 10 |
| 待機への提案 / 64件 | 0 | 0 |
| 正しい補正 / 全提案（適合率） | 79/146（54.11%） | 92/147（62.59%） |
| ケース単位micro F0.5 | 0.5090 | 0.5897 |
| 元グループmacro F0.5 | 0.4740 | 0.5432 |
| 自動適用 / 384件 | 9 | 9 |
| 誤自動適用 / 自動適用9件 | 0 | 0 |
| 補正処理 p50 / p95 ms | 15.11 / 29.25 | 14.77 / 29.53 |

遅延は1巡のlocalhost補正処理実測であり、TSF/候補表示までの遅延ではない。
p95はほぼ同等で高速化を主張しない。学習用の応答再生では遅延を測らない。
67元グループ単位のpaired bootstrap（seed42、5,000回）ではF0.5差の95%percentile区間は
`[+0.0242, +0.1381]`。合成固定集合の範囲の区間であり、実使用分布や安全性の保証ではない。

誤り別の正しい補正は隣接19→22/46、脱落19→21/39、重複16→18/28、母音脱落15→16/33、転置10→15/46。
表記採点とは別の逆辞書による意図読み一致は85→98/192件だった。
`連絡してほしい/連絡して欲しい`等を現行の許容表記が十分覆っていない一方、
読み一致だけでは同音異義語の意味の正しさを保証できない。採点後に許容解を追加して点数を上げていない。

### 旧QWERTYラベル付き回帰、195件

旧300問から`adjacent / missing_vowel / transposition`150件と正常45件を抽出。
rawキーが保存されておらず、実際のQWERTY誤りであることは未検証なので、native集合とは合算しない。

| 指標 | 既存v4 | QWERTY v4 |
|---|---:|---:|
| 正しい補正 / 150 | 38 | 40 |
| 誤った補正提案 / 150 | 27 | 37 |
| 正常への誤提案 / 45 | 1 | 2 |
| ケース単位F0.5 | 0.4589 | 0.4292 |
| 誤自動適用 / 自動適用2件 | 0 | 0 |
| 補正処理 p50 / p95 ms | 25.07 / 35.24 | 25.62 / 35.75 |

旧集合の退行を打ち消すように評価問題や閾値を書き換えていない。
今回の改善はnative QWERTY集合への適合改善であり、広い入力に対する全面的な改善ではない。

## 入力途中のnの扱い

独立したソース修正として、`windows/crates/server/src/ai_assist/corrector.rs`の末尾ASCII判定から
未確定`n`を例外扱いする箇所を除いた。nativeでは`n`が未確定、`nn`で`ん`になるため、
末尾`n`を含む途中入力でモデルを起動しないようにした。学習履歴の優先順等は変えていない。
回帰テスト`native_unresolved_terminal_n_waits_without_calling_a_model`を修正前に失敗、修正後に成功させた。

修正前の基準モデルで待機への提案が24/64件あった検証ケースは、修正後0件になる。
以降の旧/新モデル比較は両方に同じ修正を使用した。この効果を新しい重みの改善へ加算しない。
このソース修正も登録済みIMEには未適用。

## 再現手順・成果物

既存の出力がある場合は新しい名前を使う。Pythonはすべてルートの`python scripts/run.py`経由。
重み・データ・結果はGit対象外の`artifacts/`。

```powershell
python scripts/run.py data/build_qwerty_corrector.py --out artifacts/datasets/<新しいnativeデータ名>
python scripts/run.py data/add_qwerty_preservation.py --parent artifacts/datasets/<nativeデータ名> --out artifacts/datasets/<保持追加データ名>
cargo build --offline --manifest-path engine/rust/Cargo.toml --target-dir artifacts/build/corrector-qwerty-20261030 --example evaluate_corrector
python scripts/run.py benchmarks/research/qwerty_corrector_policy.py --prepare artifacts/datasets/<保持追加データ名> --split val --out artifacts/results/<val-fixture>.json
python scripts/run.py benchmarks/research/qwerty_corrector_policy.py --fixture artifacts/results/<val-fixture>.json --model artifacts/models/zerocon-corrector-v4-iq4nl-emb8.gguf --out artifacts/results/<baseline>.json
python scripts/run.py training/train_qwerty_corrector.py --run-name <新しいrun名> --dataset artifacts/datasets/<nativeデータ名> --fixture <そのnativeデータのval-fixture> --baseline <そのbaseline>
python scripts/run.py training/build_corrector_v4.py --run-name <同じrun名>
python scripts/run.py training/blend_qwerty_corrector.py --run-name <新しい補間名> --parent artifacts/checkpoints/corrector-v4-nano-20261030-r1-merged --adapted artifacts/checkpoints/<QWERTY学習run名>-merged --imatrix artifacts/models/<QWERTY学習run名>.imatrix --fixture <保持追加データのval-fixture> --baseline <そのbaseline>
python scripts/run.py benchmarks/research/qwerty_corrector_policy.py --prepare artifacts/datasets/<保持追加データ名> --split test --out artifacts/results/<test-fixture>.json
python scripts/run.py benchmarks/research/benchmark_qwerty_corrector.py --selection artifacts/results/<補間名>/selection.json --test-fixture <test-fixture> --out-dir artifacts/results/<新しいtest結果名>
python scripts/run.py benchmarks/research/analyze_qwerty_corrector.py --comparison-dir artifacts/results/<test結果名>
python scripts/run.py training/package_qwerty_corrector.py --selection <selection.json> --comparison <comparison.json> --out-name <新しい候補名>
```

各追試の実引数はcheckpointの`run-manifest.json`、親/データとコードのSHA256はprovenanceに記録している。
QWERTY r1の当時の学習ソースは`artifacts/results/corrector-qwerty-v4-20261030-r1-source/`にも保管した。
主要結果は以下。

- `artifacts/results/corrector-qwerty-v4-20261030-wise-r2/selection.json`
- `artifacts/results/corrector-qwerty-v4-20261030-wise-r2-test/comparison.json`
- 同ディレクトリの`native-test-*.json`、`legacy-qwerty-labelled-*.json`、`group-analysis-with-reading.json`
- `artifacts/results/corrector-qwerty-v4-20261030-r1-test/comparison.json`（不採用候補）
- `artifacts/results/corrector-qwerty-v4-20261030-r2-selection/selection.json`

## 検証と反映状況

- `python scripts/run.py training/test_qwerty_corrector.py`: 5成功。物理編集、同音語を別解にしないこと、native n/nn/reset、凍結親と損失勾配、正常保持KLを確認。
- `cargo test --offline --manifest-path engine/rust/Cargo.toml --target-dir artifacts/build/corrector-qwerty-20261030 --lib -- --test-threads=1`: 59成功、1ignored。
- 同manifest/target-dirで`--example evaluate_corrector -- --test-threads=1`: 3成功。oracle/replay分離、実応答の再生を確認。
- `rustfmt --edition 2021 --check windows/crates/server/src/ai_assist/corrector.rs engine/rust/examples/evaluate_corrector.rs`: 成功。
- `python scripts/run.py scripts/check_repository.py`: 成功。
- `git diff --check --ignore-submodules=all`: 成功。通常の`git diff --check`は既存submodule管理領域のPermission deniedで停止するため、このオプションを使用。
- `node windows/scripts/sync-version.mjs --check-release`: 既存`frontend/src-tauri/tauri.conf.json`のversion metadata不整合で失敗。無関係なrelease番号は変更しない。
- `python windows/scripts/vm.py --help`: `ModuleNotFoundError: No module named 'fcntl'`で停止。このWindowsホストで指定VM経路は使えず、GUI/TSF操作確認は未実施。

ソース・学習・GGUF作成・単体/ポリシーテストまで実施。Windows全体ビルド、実IME適用、入力アプリでの確認、
インストーラー作成、コミット・pushは未実施。既存の無関係な作業差分を維持した。
ブランチは`feature/windows-color-themes`のまま。未コミットのソースをGit保存済みとは扱わない。

次に必要なのは、固定した実打鍵評価と正常混在入力の拡充、意味を含めた表記別解の事前審査、
削除・再入力・カーソル移動・採用/取消を通した実IME検証である。
今回の合成384件だけで自動補正を広く有効化できる品質へ到達したとは判断しない。
