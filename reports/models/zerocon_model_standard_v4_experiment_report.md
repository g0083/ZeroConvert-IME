# ZeroCon Model Standard v4 — Nano v4からの知見反映と追加学習（2026-10-29）

## 1. 結果

Standard v3を初期重みとし、Nano v4の凍結教師・高ランクDoRA・自己コピー抑止を使って追加学習した。今回の比較で最も正解数が多かったのは **Standard v4 Q8_0の458/480問（95.4%）**。同じ実行器・評価条件で再測定したStandard v3 Q8_0の451/480問から7問改善した。

| モデル | サイズ（MiB） | 230問 | 高難度250問 | 合計480問 | 総合正解率 | 打ち切り |
|---|---:|---:|---:|---:|---:|---:|
| Standard v3 F16 | 183.23 | 207 | 245 | 452 | 94.2% | 0 |
| **Standard v4 F16** | **183.23** | **211** | **246** | **457** | **95.2%** | **0** |
| Standard v3 Q8_0 | 99.05 | 206 | 245 | 451 | 94.0% | 0 |
| **Standard v4 Q8_0** | **99.05** | **211** | **247** | **458** | **95.4%** | **0** |
| Standard v3 IQ4_NL_emb8 | 58.55 | 206 | 236 | 442 | 92.1% | 0 |
| **Standard v4 IQ4_NL_emb8** | **58.55** | **209** | **242** | **451** | **94.0%** | **0** |

旧レポートのIQ4高難度237問と今回の236問には1問差がある。上表では過去の値を流用せず、今回の再測定値を基準にした。単回測定のため、この差の原因や再実行時の一致は未確認。

## 2. 反映した知見と実装

- 初期重み：`artifacts/checkpoints/standard-v3-argon4-r2-merged`。元のStandard v3を保持し、追加adapterを無効化したforwardを自己アンカーにする。
- 補助教師：`artifacts/checkpoints/nano-v4-argon4-merged`。Nano v4のF16相当HF重みを凍結し、教師が誤る位置も正解トークンとのlogit交換で補正する。Nanoの方が大きい・全面的に優秀だと仮定する方式ではない。
- Nano v4の`SuperTeacherDualAnchorTrainer.compute_loss`を再利用。正解ラベルCE、補正教師KL、自己アンカーKL、自己コピー検出時のマージン3.0（通常2.2）、EOS幻覚抑止を使用する。
- DoRA：`r=64, alpha=128, dropout=0`。12層用optimizerを実装し、下位4層0.40倍・中間4層1.00倍・上位4層1.30倍。DoRA magnitudeをweight decayから除外する。
- ASCII空白の入力0/567の二重符号化と出力567、EOS=3を継承。教師・生徒の語彙ID、特殊トークン、代表入力の符号化一致を検査する。
- seed=42をadapter初期化前に設定。学習コード・元重み・tokenizer・データのハッシュを保存する。EOSが切れる長さの系列は学習前に拒否する。

これらを組み合わせた1条件の実験であり、各施策単独の改善寄与は分離測定していない。

## 3. データと学習条件

入力はNano v4でも使った`artifacts/datasets/standard-v3-knowledge/`のtrain 817行・val 85行。空白の二重符号化後はtrain 861系列。評価5ファイルとの予約キー・学習/検証間の重複検査はすべて0件。

- train SHA-256：`c15dc9e1a305652b7d98c1de5e866cdb9f7e600ded5431e1ad2ab3bd9208ac75`
- val SHA-256：`70cebd4a16a709f67fef2acc2ea1c239ef7b4184f78ba8d51bc9fac67b654a5b`
- RTX 4070、BF16、batch 8、5 epochs、学習率8e-5、cosine、warmup 20 steps、weight decay 0.01。
- 学習時間53.94秒（重みロード・マージ・GGUF化は含まない）。最終検証損失0.29895088、検証損失最小のepoch 5 / step 540を採用。評価480問の点数でepochやblendを選んでいない。
- 100%マージを1本生成。校正は学習行だけを2周し、ASCII空白/全角空白の両系列を含める。Vulkan版`llama-imatrix --chunks 64`、IQ4は埋め込み・出力をQ8_0に保護。

データ分離は既存の正規化キーによる検査範囲であり、意味的な類似をすべて除外する保証ではない。この480問は過去から繰り返し使っている開発評価であり、未知データへの汎化性能とは区別する。

## 4. 改善と残る退行

F16では230問が4問改善・退行0、高難度250問が3問改善・2問退行。Q8では合計9問改善・2問退行、IQ4では13問改善・4問退行。個別出力は`artifacts/results/standard_v4_transfer_summary.json`に全件保存した。

F16の改善例：

| 評価 | ID | 旧出力 | 新出力 |
|---|---:|---|---|
| 230問 | 84 | 層移行風する | 創意工夫する |
| 230問 | 134 | Cloudではなく端末内で実行する | クラウドではなく端末内で実行する |
| 230問 | 211 | カフェルノアでまつ | カフェルノアで待つ |
| 高難度 | 245 | こんにちは。 | こんにちは |

F16の退行は高難度ID 229「１００えんのあめ」が`１００円の飴`→`1000円の飴`、ID 246「１０きろはしる」が`１０キロ走る`→`１0キロ走る`。全角数字の保持は未解決であり、総合点の上昇でこの退行を隠さない。Q8にも高難度2問の退行がある。タイポ補正・先読みの専用評価やWindows実IME操作は今回行っていない。

平均推論遅延（230問 / 高難度250問、ms）：

| 量子化 | v3 | v4 |
|---|---|---|
| F16 | 29.68 / 29.89 | 28.58 / 29.98 |
| Q8_0 | 27.77 / 28.34 | 29.53 / 27.73 |
| IQ4_NL_emb8 | 27.87 / 26.07 | 27.29 / 27.08 |

同じVulkan実行器（SHA-256 `b9d62c34ad6bc1dad8f5a9d790ed535f2df97565e2dbbda381e994dbbadf141b`）、GPU offload 99、context 2048、threads 4、temperature 0、seed 42、cache_prompt=false、最大96 tokens、warmup 3回。遅延は学習終了後の単回測定であり、速度向上を断定しない。

## 5. 成果物と再現

新しいモデル（すべてGit対象外）：

| パス | SHA-256 |
|---|---|
| `artifacts/models/standard-v4-nano-transfer-f16.gguf` | `1ea1f1ad5c9549d1c57e9f28ce626534bf5a2aa908ef1774d8822d3dc2925bed` |
| `artifacts/models/standard-v4-nano-transfer-q8_0.gguf` | `08946b336a75d414ac10f234b8f778624945c511d1cf7225ad0d24345fcfc644` |
| `artifacts/models/standard-v4-nano-transfer-iq4nl-emb8.gguf` | `d6d4ab261a1abe3f654cf634e21b338ec14ad0e54d9d9ac899c2877b46b1995b` |

checkpointは`artifacts/checkpoints/standard-v4-nano-transfer-{lora,merged}/`。`dataset-provenance.json`、`run-manifest.json`、`trainer_state.json`を保存。校正来歴は`artifacts/datasets/standard-v4-nano-transfer-imatrix/manifest.json`、学習・量子化ログは`artifacts/logs/standard-v4-{nano-transfer,quantization}.log`。

```powershell
# 学習・量子化。既存名では停止するため再実行時は両コマンドに同じ新しい--run-nameを渡す。
.\.venv_train\Scripts\python.exe scripts/run.py training/train_standard_v4.py
.\.venv_train\Scripts\python.exe scripts/run.py training/build_standard_v4_quants.py

# 例：Q8の230問。高難度は--testset data/kana_kanji_hard_context_testset.jsonlを追加。
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/benchmark_kana_kanji_models.py --models "Standard v3 Q8=artifacts/models/zerocon-standard-v3-q8_0.gguf" "Standard v4 Q8=artifacts/models/standard-v4-nano-transfer-q8_0.gguf" --base-port 8450 --out artifacts/results/standard_v4_transfer_quants_230_repeat.json

# 保存済みF16/Q8/IQ4比較の同一条件・モデルhash・ケース対応を検査して差分集計。
.\.venv_train\Scripts\python.exe scripts/run.py benchmarks/research/analyze_standard_v4.py
```

評価JSONは`standard_v4_transfer_{230,250hard}.json`と`standard_v4_transfer_quants_{230,250hard}.json`の4本。ベンチマークは専用ポートの所有サーバーを起動・終了し、稼働IMEへ接続しない。サンドボックス内ではlocalhost接続がWinError 10013で拒否されたため、正式な比較測定は許可された制限外コマンドで実行した。

検証：`python scripts/run.py data/test_dataset_isolation.py`の8件、`python scripts/run.py training/test_standard_v4.py`の2件が成功（実行Pythonは上記venv）。後者は12層のoptimizer分類、DoRA magnitudeの減衰除外、損失・adapter勾配、教師と凍結重みへの勾配遮断、全ラベル無効時を確認。ペア比較のhash・実行条件・ケース整合検査も成功。

ソース追加・学習・GGUF化・量子化・モデル単体評価まで実施済み。Windows/Androidの実IMEへの適用、入力アプリでの確認、配布カタログ更新、インストーラー生成、コミット・pushは未実施。既存差分・既存v3/nano重み・個人設定は保持している。
