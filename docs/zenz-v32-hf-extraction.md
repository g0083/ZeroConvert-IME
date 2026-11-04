# zenz-v3.2 GGUF からの Hugging Face / PyTorch 重み逆展開手順

2026-10-28作成。`zenz-v3.2-xsmall` および `zenz-v3.2-small` は上流で `.gguf`（`Q5_K_M`）のみ公開されており、Hugging Face 上に `model.safetensors` リポジトリが存在しない。本手順では、`v3.1`（CC BY-SA 4.0）のファイルを一切参照せず、ローカルの `zenz-v3.2` GGUF（Apache-2.0）単体から PyTorch 学習可能な Hugging Face 形式（`safetensors` + tokenizer + `config.json`）を100%自己完結で復元する。

## 1. ライセンスと来歴の扱い

- **入力元**:
  - `artifacts/models/zenz-v3.2-xsmall-q5km.gguf`（SHA-256: `00c64b3d318045a708d0cad5434faccab10f5481a49e6362864551fd0995fa58`、ライセンス: **Apache-2.0**）
  - （Small版を使う場合）`artifacts/models/zenz-v3.2-small-q5km.gguf`（SHA-256: `29c223d4c23327b80fd13ebb5ab2555057a46317997d5da391584ffbef0db673`、ライセンス: **Apache-2.0**）
- **自己完結性**:
  - GGUF ヘッダ内部に語彙（`tokenizer.ggml.tokens`: 6,000語）、BPEマージ規則（`tokenizer.ggml.merges`: 5,764件）、特殊トークンID（`bos=1, eos=2, pad=1`）、モデル構造定義（`gpt2.*`）がすべて含まれている。
  - `Miwa-Keita/zenz-v3.1-xsmall` 等の `CC BY-SA 4.0` 由来ファイルを1バイトもコピーしないため、展開後の重みおよびそこから学習した派生モデル（Corrector v3.2 等）は **Apache-2.0** として扱える。

## 2. テンソル逆展開の仕組みと注意点

1. **量子化テンソルの逆変換**:
   - `zenz-v3.2-xsmall-q5km.gguf` 内の全77テンソルのうち、LayerNorm・Bias・位置埋め込み（`position_embd.weight`）は最初から無圧縮の `F32` で格納されている。
   - 重み行列（`Q5_K` / `Q6_K`）は `gguf.quants.dequantize()` で `float32` 配列へ逆変換する。
2. **GPT-2 `Conv1D` 重みの転置**:
   - Hugging Face の `GPT2LMHeadModel` は線形層に `Conv1D`（形状 `[in_features, out_features]`）を使うが、`convert_hf_to_gguf.py` は GGUF 出力時に2次元重みを転置（`[out_features, in_features]`）している。
   - したがって、`blk.{i}.attn_qkv.weight`、`blk.{i}.attn_output.weight`、`blk.{i}.ffn_up.weight`、`blk.{i}.ffn_down.weight` の4種は **`.t().contiguous()` で転置**して `state_dict` に格納する（`wte` と `wpe` は転置しない）。
3. **入力埋め込み `wte.weight` への `output.weight`（`Q6_K`）の採用**:
   - GPT-2 では本来 `transformer.wte.weight` と `lm_head.weight` が重み共有（Tied Embeddings）されているが、`Q5_K_M` 量子化時に `token_embd.weight` は `Q5_K`（5.5bit）、`output.weight` はより高精度な `Q6_K`（6.5bit）で量子化されている。
   - 逆展開時に精度の高い **`output.weight`（`Q6_K`）側を `transformer.wte.weight` に採用**することで、入力埋め込みの量子化誤差が軽減される（かな漢字変換230問ベンチマークで元 `Q5_K_M` の 183/230問 に対し、展開後 `F16` はリグレッション0件で 185/230問 へ改善することを確認済み）。

## 3. 展開済みディレクトリと再生成コマンド

このワークスペースではすでに **`artifacts/models/hf/zenz-v3.2-xsmall`** に展開済みであり、そのまま `--base_model artifacts/models/hf/zenz-v3.2-xsmall` として指定できる。

別環境や別モデル（`small` 等）で再生成する場合は、リポジトリルートから以下を実行する。

```powershell
.\.venv\Scripts\python.exe -c "import os, json, hashlib, gguf, torch
from safetensors.torch import save_file

gguf_path = 'artifacts/models/zenz-v3.2-xsmall-q5km.gguf'
out_dir = 'artifacts/models/hf/zenz-v3.2-xsmall'
os.makedirs(out_dir, exist_ok=True)

r = gguf.GGUFReader(gguf_path)
def scalar(key):
    return r.fields[key].parts[-1][0].item()

n_layer = int(scalar('gpt2.block_count'))
n_ctx = int(scalar('gpt2.context_length'))
n_embd = int(scalar('gpt2.embedding_length'))
n_head = int(scalar('gpt2.attention.head_count'))
ln_eps = float(scalar('gpt2.attention.layer_norm_epsilon'))

tokens = [bytes(r.fields['tokenizer.ggml.tokens'].parts[i]).decode('utf-8') for i in r.fields['tokenizer.ggml.tokens'].data]
merges = [bytes(r.fields['tokenizer.ggml.merges'].parts[i]).decode('utf-8') for i in r.fields['tokenizer.ggml.merges'].data]
bos_id = tokens.index('<s>') if '<s>' in tokens else int(scalar('tokenizer.ggml.bos_token_id'))
eos_id = tokens.index('</s>') if '</s>' in tokens else int(scalar('tokenizer.ggml.eos_token_id'))
pad_id = tokens.index('[PAD]') if '[PAD]' in tokens else int(scalar('tokenizer.ggml.padding_token_id'))
unk_id = tokens.index('[UNK]') if '[UNK]' in tokens else 0
raw = {t.name: torch.from_numpy(gguf.quants.dequantize(t.data, t.tensor_type).copy()).to(torch.float32) for t in r.tensors}

state_dict = {
    'transformer.wte.weight': raw['output.weight'],
    'transformer.wpe.weight': raw['position_embd.weight'],
    'transformer.ln_f.weight': raw['output_norm.weight'],
    'transformer.ln_f.bias': raw['output_norm.bias'],
}
for i in range(n_layer):
    p, h = f'blk.{i}', f'transformer.h.{i}'
    state_dict[f'{h}.ln_1.weight'] = raw[f'{p}.attn_norm.weight']
    state_dict[f'{h}.ln_1.bias'] = raw[f'{p}.attn_norm.bias']
    state_dict[f'{h}.attn.c_attn.weight'] = raw[f'{p}.attn_qkv.weight'].t().contiguous()
    state_dict[f'{h}.attn.c_attn.bias'] = raw[f'{p}.attn_qkv.bias']
    state_dict[f'{h}.attn.c_proj.weight'] = raw[f'{p}.attn_output.weight'].t().contiguous()
    state_dict[f'{h}.attn.c_proj.bias'] = raw[f'{p}.attn_output.bias']
    state_dict[f'{h}.ln_2.weight'] = raw[f'{p}.ffn_norm.weight']
    state_dict[f'{h}.ln_2.bias'] = raw[f'{p}.ffn_norm.bias']
    state_dict[f'{h}.mlp.c_fc.weight'] = raw[f'{p}.ffn_up.weight'].t().contiguous()
    state_dict[f'{h}.mlp.c_fc.bias'] = raw[f'{p}.ffn_up.bias']
    state_dict[f'{h}.mlp.c_proj.weight'] = raw[f'{p}.ffn_down.weight'].t().contiguous()
    state_dict[f'{h}.mlp.c_proj.bias'] = raw[f'{p}.ffn_down.bias']

save_file(state_dict, os.path.join(out_dir, 'model.safetensors'), metadata={'format': 'pt'})

config = {
    'activation_function': 'gelu_new',
    'architectures': ['GPT2LMHeadModel'],
    'attn_pdrop': 0.1,
    'bos_token_id': bos_id,
    'embd_pdrop': 0.1,
    'eos_token_id': eos_id,
    'initializer_range': 0.02,
    'layer_norm_epsilon': ln_eps,
    'model_type': 'gpt2',
    'n_ctx': n_ctx,
    'n_embd': n_embd,
    'n_head': n_head,
    'n_inner': None,
    'n_layer': n_layer,
    'n_positions': n_ctx,
    'pad_token_id': pad_id,
    'reorder_and_upcast_attn': False,
    'resid_pdrop': 0.1,
    'scale_attn_by_inverse_layer_idx': False,
    'scale_attn_weights': True,
    'torch_dtype': 'float32',
    'use_cache': True,
    'vocab_size': len(tokens),
}
with open(os.path.join(out_dir, 'config.json'), 'w', encoding='utf-8') as f:
    json.dump(config, f, indent=2, ensure_ascii=False)

with open(os.path.join(out_dir, 'vocab.json'), 'w', encoding='utf-8') as f:
    json.dump({tok: idx for idx, tok in enumerate(tokens)}, f, ensure_ascii=False, indent=2)

with open(os.path.join(out_dir, 'merges.txt'), 'w', encoding='utf-8') as f:
    f.write('#version: 0.2\n' + '\n'.join(merges) + '\n')

tokenizer_config = {
    'add_bos_token': False,
    'add_prefix_space': False,
    'bos_token': tokens[bos_id],
    'clean_up_tokenization_spaces': True,
    'eos_token': tokens[eos_id],
    'errors': 'replace',
    'pad_token': tokens[pad_id],
    'tokenizer_class': 'GPT2Tokenizer',
    'unk_token': tokens[unk_id],
}
with open(os.path.join(out_dir, 'tokenizer_config.json'), 'w', encoding='utf-8') as f:
    json.dump(tokenizer_config, f, indent=2, ensure_ascii=False)

with open(os.path.join(out_dir, 'special_tokens_map.json'), 'w', encoding='utf-8') as f:
    json.dump({'bos_token': tokens[bos_id], 'eos_token': tokens[eos_id], 'pad_token': tokens[pad_id], 'unk_token': tokens[unk_id]}, f, indent=2, ensure_ascii=False)

with open(gguf_path, 'rb') as f:
    sha = hashlib.file_digest(f, 'sha256').hexdigest()
with open(os.path.join(out_dir, 'provenance.json'), 'w', encoding='utf-8') as f:
    json.dump({'source_gguf': gguf_path, 'source_sha256': sha, 'license': 'Apache-2.0', 'used_v3_1_files': False}, f, indent=2)
"
```

## 4. Corrector v3.2 学習・マージ・GGUF化への接続方法

既存の `training/train_typo_corrector.py` と `training/export_typo_corrector.py` は `--base_model` にローカルディレクトリをそのまま受け取れる。

```powershell
# 1. LoRA学習（出力先は未使用ディレクトリを指定）
python scripts/run.py training/train_typo_corrector.py `
  --base_model artifacts/models/hf/zenz-v3.2-xsmall `
  --train_file artifacts/datasets/corrector-v2-20261027/train.jsonl `
  --val_file artifacts/datasets/corrector-v2-20261027/val.jsonl `
  --output_dir artifacts/checkpoints/corrector-v32-xsmall-lora

# 2. LoRA重みのマージ
python scripts/run.py training/export_typo_corrector.py `
  --base_model artifacts/models/hf/zenz-v3.2-xsmall `
  --lora_dir artifacts/checkpoints/corrector-v32-xsmall-lora `
  --output_dir artifacts/checkpoints/corrector-v32-xsmall-merged

# 3. GGUF (F16) 変換と Q4_K_M 量子化
.\.venv\Scripts\python.exe android/app/src/main/cpp/llama.cpp/convert_hf_to_gguf.py `
  artifacts/checkpoints/corrector-v32-xsmall-merged `
  --outfile artifacts/models/corrector-v32-xsmall-f16.gguf `
  --outtype f16

.\ZeroConvertIME-windows\llama_vulkan\llama-quantize.exe `
  artifacts/models/corrector-v32-xsmall-f16.gguf `
  artifacts/models/corrector-v32-xsmall-q4km.gguf `
  Q4_K_M
```
