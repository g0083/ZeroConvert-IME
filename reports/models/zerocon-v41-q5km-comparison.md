# ZeroCon v4.1 Q5_K_M 作成と Nano / Standard ベンチマーク比較（2026-11-21）

## 1. 結果

v4.1 の F16（`nano-v4-cap-nam-r3-f16.gguf` / `standard-v4-cap-nam-r3-f16.gguf`、既存の `imatrix` 校正）から Q5_K_M を作成し、CPU ベンチマークで比較した。**Q5_K_M は両サイズとも明確に劣るため選択肢に採用せず、選択肢は IQ4_emb8 / Q8 の2種ずつで確定した。** この後 Q5_K_S / Q6_K も作成・測定した（§4）。

| モデル | サイズ (MiB) | 230問 | 高難度250問 | 480問合計 |
|---|---:|---:|---:|---:|
| Nano IQ4 emb8 | 18.7 | 222 (96.5%) | 247 (98.8%) | **469 (97.7%)** |
| Nano Q5_K_M | 19.6 | 219 (95.2%) | 234 (93.6%) | **453 (94.4%)** |
| Nano Q8 | 27.7 | 224 (97.4%) | 247 (98.8%) | **471 (98.1%)** |
| Standard IQ4 emb8 | 58.5 | 225 (97.8%) | 247 (98.8%) | **472 (98.3%)** |
| Standard Q5_K_M | 69.1 | 215 (93.5%) | 228 (91.2%) | **443 (92.3%)** |
| Standard Q8 | 99.0 | 225 (97.8%) | 249 (99.6%) | **474 (98.8%)** |

- 埋め込み・出力テンソルを Q8 保護した Q5_K_M emb8 でも同傾向（Nano 448/480、Standard 442/480）だった。xsmall / small では K/V の量子化誤差が支配的とみてよい。
- 全モデルとも `truncated = 0`。

## 2. Q5_K_S / Q6_K の追加測定（2026-11-21）

Q5_K_M（削除対象）とは別に、既存 imatrix で Q5_K_S / Q6_K を作成し同じ480問で測定した。

| モデル | サイズ (MiB) | 230問 | 高難度250問 | 480問合計 |
|---|---:|---:|---:|---:|
| Nano IQ4 emb8 | 18.7 | 222 (96.5%) | 247 (98.8%) | **469 (97.7%)** |
| Nano Q5_K_S | 19.1 | 220 (95.7%) | 233 (93.2%) | **453 (94.4%)** |
| Nano Q6_K | 21.9 | 224 (97.4%) | 246 (98.4%) | **470 (97.9%)** |
| Nano Q8 | 27.7 | 224 (97.4%) | 247 (98.8%) | **471 (98.1%)** |
| Standard IQ4 emb8 | 58.5 | 225 (97.8%) | 247 (98.8%) | **472 (98.3%)** |
| Standard Q5_K_S | 66.0 | 218 (94.8%) | 230 (92.0%) | **448 (93.3%)** |
| Standard Q6_K | 77.3 | 224 (97.4%) | 248 (99.2%) | **472 (98.3%)** |
| Standard Q8 | 99.0 | 225 (97.8%) | 249 (99.6%) | **474 (98.8%)** |

- **Q5_K_S は高難度で大きく劣るため不採用**（Nano 453/480、Standard 448/480）。
- **Q6_K は IQ4 emb8 と互角**（Nano 470/480、Standard 472/480）で、IQ4 より約17〜19 MiB大きくなり遅延・メモリにも利点が見えない。現行の IQ4 / Q8 2種選択を変える必要は無い。
- Q5_K_M・Q5_K_S・Q6_K の実験ファイルは全て削除済み（F16 / IQ4 / Q8 / imatrix は保持）。結果JSONは `artifacts/results/v41_q5q6_cpu_230.json` / `v41_q5q6_cpu_250hard.json` を参照。削除前のSHA-256:
  - `nano-v4-cap-nam-r3-q5ks.gguf`: `2161a5a52867…`（20,065,344 bytes）
  - `nano-v4-cap-nam-r3-q6k.gguf`: `88bd3eaac5f7…`（22,980,096 bytes）
  - `standard-v4-cap-nam-r3-q5ks.gguf`: `7ecbe7f7a445…`（69,171,904 bytes）
  - `standard-v4-cap-nam-r3-q6k.gguf`: `9e6b4e124091…`（81,064,288 bytes）

## 3. Standard v4.1 の扱い

`standard-v4-cap-nam-r3-f16.gguf` は Nano v4.1 と同じ v4.1 調整譜面（日常会話・過剰漢字抑制・数値頑健性）の Standard 中規模版であり、そのまま Standard v4.1 として IQ4_emb8 / Q8 量子化（既存 `imatrix`）で採用した。4種のSHAとサイズは [docs/model-assets.md](../../docs/model-assets.md) / `windows/deployment/model-catalog.json` に記録した。

- Nano v4.1 IQ4_emb8: `6a99ca4e1c…`（19,602,048 bytes）
- Nano v4.1 Q8: `001d2cf3ac…`（29,039,232 bytes）
- Standard v4.1 IQ4_emb8: `61af7b70aa…`（61,399,072 bytes）
- Standard v4.1 Q8: `5065b64d9d…`（103,866,400 bytes）

## 4. 再現

```powershell
ZeroConvertIME-windows/llama_vulkan/llama-quantize.exe --imatrix artifacts/models/nano-v4-cap-nam-r3.imatrix artifacts/models/nano-v4-cap-nam-r3-f16.gguf artifacts/models/nano-v4-cap-nam-r3-q5km.gguf Q5_K_M
ZeroConvertIME-windows/llama_vulkan/llama-quantize.exe --imatrix artifacts/models/standard-v4-cap-nam-r3.imatrix artifacts/models/standard-v4-cap-nam-r3-f16.gguf artifacts/models/standard-v4-cap-nam-r3-q5km.gguf Q5_K_M
# 埋め込み保護版は --token-embedding-type q8_0 --output-tensor-type q8_0 を追加（結果は付録）
./.venv_train/Scripts/python.exe benchmarks/research/benchmark_kana_kanji_models.py --backend cpu --n-gpu-layers 0 --base-port 8480 --models "Nano IQ4=…" "Nano Q5_K_M=…" … --out artifacts/results/v41_q5km_cpu_230.json
# 高難度250問は --testset data/kana_kanji_hard_context_testset.jsonl と --base-port 8490
```

結果JSON: `artifacts/results/v41_q5km_cpu_230.json` / `v41_q5km_cpu_250hard.json` / `v41_q5km_emb8_cpu_230.json` / `v41_q5km_emb8_cpu_250hard.json`。

本流の配布は上記4種の同梱方式とは切り離し、インストーラー・APKへ同梱せず設定画面からダウンロードする方式へ変更した（[docs/model-assets.md](../../docs/model-assets.md) 参照）。

## 付録: Q5_K_M emb8（埋め込み・出力 Q8 保護）

| モデル | 230問 | 高難度250問 | 480問合計 |
|---|---:|---:|---:|
| Nano Q5_K_M emb8 (22.4 MiB) | 220 (95.7%) | 228 (91.2%) | 448 (93.3%) |
| Standard Q5_K_M emb8 (75.3 MiB) | 214 (93.0%) | 228 (91.2%) | 442 (92.1%) |

サイズは標準Q5_K_Mより大きい一方、精度もIQ4/Q8には届かなかった。
