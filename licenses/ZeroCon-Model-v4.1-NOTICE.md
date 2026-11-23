# ZeroCon Model v4.2 — License notice

ZeroCon Model Nano v4.2 and Standard v4.2 (including prior v4.1 releases) are modified versions of the following models by Miwa Keita (ensan-hcl). The revisions used for training identify their license as Apache License 2.0. The SHA-256 values refer to the upstream `ggml-model-Q5_K_M.gguf` files.

| Upstream model | Revision | SHA-256 |
| --- | --- | --- |
| `Miwa-Keita/zenz-v3.2-xsmall-gguf` | [`4f5423f0fad41a73b1242eb96fe5c12ae4fdca83`](https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf/raw/4f5423f0fad41a73b1242eb96fe5c12ae4fdca83/README.md) | `00c64b3d318045a708d0cad5434faccab10f5481a49e6362864551fd0995fa58` |
| `Miwa-Keita/zenz-v3.2-small-gguf` | [`c67e03e07d215c869f591b274c1631170d3e11fe`](https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf/raw/c67e03e07d215c869f591b274c1631170d3e11fe/README.md) | `29c223d4c23327b80fd13ebb5ab2555057a46317997d5da391584ffbef0db673` |

The upstream weights were reconstructed for training, fine-tuned using rank-64 DoRA and distillation, adapted for conversational kana-kanji conversion, additional-dictionary vocabulary, and numeral handling, and exported as modified GGUF files quantized to IQ4_NL with Q8_0 embeddings and output, or Q8_0.

- `zerocon-nano-v4.2-iq4nl-emb8.gguf`
- `zerocon-nano-v4.2-q8_0.gguf`
- `zerocon-standard-v4.2-iq4nl-emb8.gguf`
- `zerocon-standard-v4.2-q8_0.gguf`

Copyright (c) g0083 for the ZeroCon modifications. Rights in those modifications are reserved where the project has granted no separate permission. This notice preserves rights granted under the upstream Apache License 2.0 and any other applicable license. The Apache License 2.0 text accompanies these models in `LICENSE-Apache-2.0.txt`. [ZeroConvert IME terms](https://github.com/g0083/ZeroConvert-IME/blob/main/LICENSE)
