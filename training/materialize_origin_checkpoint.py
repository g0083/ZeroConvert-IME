# SPDX-License-Identifier: Apache-2.0
"""Materialize a completed owned Origin checkpoint for offline diagnostics.

Does not modify/interrupt its parent training job or copy optimizer state into
the inference artifact. The original initialization and source identities stay
in the manifest; checkpoint provenance is added explicitly.
"""
import argparse
import copy
import json
from pathlib import Path
import shutil

from origin_contract import OriginCodec, SIZES, digest, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--checkpoint', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    if args.checkpoint.resolve().parent != args.run.resolve():
        raise ValueError('Use an owned checkpoint directly inside the selected Origin run')
    original = json.loads((args.run / 'run-manifest.json').read_text(encoding='utf-8'))
    if original.get('schema') != 'zerocon-origin-run-v1':
        raise ValueError('Not an Origin run')
    if not original.get('model_config'):
        for name in ('train_origin.py', 'origin_contract.py'):
            if digest(Path(__file__).with_name(name)) != original['code_hashes'][name]:
                raise ValueError('Training/config source differs; preserve the exact config before materializing')
    import torch
    from transformers import LlamaConfig, LlamaForCausalLM
    torch.set_num_threads(4)
    saved = torch.load(args.checkpoint, map_location='cpu', weights_only=False)
    for key in ('dataset_hashes', 'tokenizer_sha256', 'code_hashes', 'arguments'):
        if saved['manifest'][key] != original[key]:
            raise ValueError(f'Checkpoint identity mismatch: {key}')
    codec = OriginCodec(args.run / 'tokenizer.model')
    if digest(args.run / 'tokenizer.model') != original['tokenizer_sha256']:
        raise ValueError('Tokenizer changed')
    if original.get('model_config'):
        if original['model_config'].get('model_type') != 'llama':
            raise ValueError('Only Llama Origin configuration is supported')
        config = LlamaConfig.from_dict(original['model_config'])
    else:
        layers, width, heads, kv_heads, ffn = SIZES[original['arguments']['size']]
        config = LlamaConfig(vocab_size=codec.sp.get_piece_size(), hidden_size=width,
            intermediate_size=ffn, num_hidden_layers=layers, num_attention_heads=heads,
            num_key_value_heads=kv_heads, max_position_embeddings=original['arguments']['max_length'],
            tie_word_embeddings=True, attention_bias=False, mlp_bias=False,
            bos_token_id=codec.bos_id, eos_token_id=codec.eos_id, pad_token_id=codec.pad_id,
            rms_norm_eps=1e-5, rope_theta=10000.0, attention_dropout=0.0)
    model = LlamaForCausalLM(config)
    model.load_state_dict(saved['model'], strict=True)
    count = sum(p.numel() for p in model.parameters())
    if count != original['parameters']:
        raise ValueError('Architecture parameter count mismatch')
    args.out.mkdir(parents=True)
    model.save_pretrained(args.out / 'hf')
    for name in ('tokenizer.model', 'tokenizer.vocab', 'origin-tokenizer.json', 'LICENSE', 'sources.json'):
        shutil.copy2(args.run / name, args.out / name)
    for name in ('third-party-licenses', 'source-evidence', 'source-code'):
        if (args.run / name).is_dir():
            shutil.copytree(args.run / name, args.out / name)
    manifest = copy.deepcopy(original)
    manifest['checkpoint_materialization'] = {
        'parent_run': str(args.run), 'parent_manifest_sha256': digest(args.run / 'run-manifest.json'),
        'checkpoint': str(args.checkpoint), 'checkpoint_sha256': digest(args.checkpoint),
        'step': saved['state']['step'], 'processed_tokens': saved['state']['processed_tokens'],
        'target_tokens': saved['state']['target_tokens'], 'code_sha256': digest(Path(__file__)),
        'purpose': 'intermediate checkpoint diagnostics; no new training occurred',
    }
    write_json(args.out / 'run-manifest.json', manifest)
    print(json.dumps(manifest['checkpoint_materialization'], indent=2))


if __name__ == '__main__':
    main()
