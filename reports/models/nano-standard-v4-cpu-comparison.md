# Nano v4 / Standard v4 CPU比較（2026-10-29）

Intel Core i5-14500HX、CPU実行器、GPUオフロード0、4スレッドで同じ480問を順次測定した。学習やモデル差し替えは行っていない。

## 結果

| モデル | サイズ（MiB） | 230問 | 高難度250問 | 総合 | 正解率 | 平均遅延 | 中央値 | p95 | 打ち切り |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Nano v4 IQ4 emb8 | 18.69 | 206/230 | 239/250 | 445/480 | 92.71% | 38.71 ms | 39.27 ms | 55.75 ms | 0 |
| Nano v4 Q6 | 23.33 | 207/230 | 245/250 | 452/480 | 94.17% | 42.39 ms | 43.36 ms | 63.79 ms | 0 |
| Nano v4 Q8 | 27.69 | 207/230 | 247/250 | 454/480 | 94.58% | 40.32 ms | 41.12 ms | 59.86 ms | 0 |
| Standard v4 IQ4 emb8 | 58.55 | 210/230 | 241/250 | 451/480 | 93.96% | 109.01 ms | 108.52 ms | 161.18 ms | 0 |
| Standard v4 Q8 | 99.05 | 212/230 | 247/250 | 459/480 | 95.62% | 122.92 ms | 119.87 ms | 184.62 ms | 0 |

Nano v4 Q8は454/480問（94.58%）、Standard v4 Q8は459/480問（95.62%）。Standardは5問上回る一方、平均遅延は約3倍、モデルサイズは約3.6倍。今回のCPU測定では、NanoのQ8がQ6より2問正解が多く、平均遅延も短かった。

## IQ4の意味

比較対象のIQ4は両方ともIQ4_NL_emb8。内部重みをIQ4_NL、入力埋め込みと出力テンソルをQ8_0にしている。NanoはBlend85版で、`zerocon-nano-v4-iq4nl-emb8.gguf`と`zerocon-nano-v4-blend85-iq4nl-emb8.gguf`のSHA-256一致を確認した。

## 測定条件と限界

平均・中央値・p95は両評価セットの全480要求をまとめて算出した。測定対象はHTTP変換要求から全文出力完了までの経過時間であり、最初のトークンまでの時間や実IMEの打鍵遅延ではない。入力長・出力長が異なるため、1トークンあたりの処理速度とも区別する。

実行条件：CPU backend、n_gpu_layers=0、threads=4、context=2048、temperature=0、seed=42、cache_prompt=false、max_tokens=96、各モデル・セットの開始時にwarmup 3回。各条件1回、モデルを順次測定。電力・温度やスケジューリングの変動を分離した反復試験は未実施。Vulkan時と正解数に差があるため、CPUで実測した精度を併記した。

CPU実行器SHA-256：`f5be4f24df220630af9426e67577669d00d4e04c95c67ca113287fa051b54fa4`。両セットのモデル・評価データ・実行器のハッシュ、設定、全ケース数を照合済み。

## 保存先・再現

- `artifacts/results/nano_standard_v4_cpu_230.json`
- `artifacts/results/nano_standard_v4_cpu_250hard.json`
- `artifacts/results/nano_standard_v4_cpu_summary.json`

`python scripts/run.py benchmarks/research/benchmark_kana_kanji_models.py --backend cpu --n-gpu-layers 0 --models "名前=GGUFパス" ... --base-port 8470 --out <新しいJSON>`で実行する。高難度側は`--testset data/kana_kanji_hard_context_testset.jsonl`を追加。Pythonは`.venv_train/Scripts/python.exe`を使用した。モデルの絶対パスとハッシュは各結果JSONに記録。localhost接続が必要なため、許可された制限外コマンドで測定した。

実IME適用・入力アプリでの確認・インストーラー更新・コミット/pushは未実施。既存ソース・モデル・個人設定は保持した。
