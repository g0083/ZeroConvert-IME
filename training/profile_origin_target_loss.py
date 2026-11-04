# SPDX-License-Identifier: Apache-2.0
"""Compare original/target-only CE on an owned model and identical cached rows.

Only in-memory model copies are updated. No checkpoint or dataset is modified.
Uniform train-row sampling describes this probe, not production task weights.
"""
import argparse
import gc
import json
from pathlib import Path
import random
import time

from origin_contract import OriginCodec, digest, write_json
from origin_target_loss import target_balanced_ce
from origin_token_cache import TokenCache
from train_origin import balanced_ce, batch_tensors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--device', choices=('cpu', 'cuda'), default='cuda')
    parser.add_argument('--microbatch', type=int, default=16)
    parser.add_argument('--grad-accum', type=int, default=4)
    parser.add_argument('--warmup', type=int, default=3)
    parser.add_argument('--steps', type=int, default=10)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    if min(args.microbatch, args.grad_accum, args.warmup, args.steps) < 1:
        parser.error('Positive probe dimensions required')
    manifest = json.loads((args.run / 'run-manifest.json').read_text(encoding='utf-8'))
    if manifest.get('schema') != 'zerocon-origin-run-v1':
        raise ValueError('Owned Origin source required')
    if digest(args.cache / 'cache-manifest.json') != manifest['token_cache_manifest_sha256']:
        raise ValueError('Cache does not match this owned run')
    codec = OriginCodec(args.run / 'tokenizer.model')
    if digest(args.run / 'tokenizer.model') != manifest['tokenizer_sha256']:
        raise ValueError('Vocabulary changed')
    with TokenCache(args.cache, 'train') as cache:
        rng = random.Random(20261029)
        positions = rng.choices(range(len(cache)), k=(args.warmup + args.steps) * args.grad_accum * args.microbatch)
        examples = [cache[i] for i in positions]
    import torch
    from transformers import AutoModelForCausalLM
    torch.set_num_threads(4)
    hf = args.run / 'hf'
    hf_hashes = {p.name: digest(p) for p in sorted(hf.iterdir()) if p.is_file()}
    model = AutoModelForCausalLM.from_pretrained(hf, local_files_only=True, use_safetensors=True,
                                                trust_remote_code=False).float().to(args.device).train()
    original = {k: v.detach().cpu().clone() for k, v in model.state_dict().items()}
    bf16 = args.device == 'cuda' and torch.cuda.is_bf16_supported()
    sync = lambda: torch.cuda.synchronize() if args.device == 'cuda' else None
    phases, gradients = {}, {}
    for name in ('full_projection', 'target_only'):
        model.load_state_dict(original)
        torch.manual_seed(42)
        if args.device == 'cuda':
            torch.cuda.manual_seed_all(42)
        optimizer = torch.optim.AdamW(model.parameters(), lr=1e-4, betas=(.9, .95), weight_decay=.1)
        initial_loss, elapsed, tokens = None, 0.0, 0
        for step in range(args.warmup + args.steps):
            if step == args.warmup:
                sync()
                if args.device == 'cuda':
                    torch.cuda.reset_peak_memory_stats()
                started = time.perf_counter()
            optimizer.zero_grad(set_to_none=True)
            step_loss = 0.0
            for micro in range(args.grad_accum):
                start = (step * args.grad_accum + micro) * args.microbatch
                selected = examples[start:start + args.microbatch]
                ids, labels, mask = batch_tensors(selected, codec, args.device)
                with torch.autocast(device_type=args.device, dtype=torch.bfloat16, enabled=bf16):
                    loss = (balanced_ce(model(input_ids=ids, attention_mask=mask, use_cache=False).logits, labels)
                            if name == 'full_projection' else target_balanced_ce(model, ids, mask, labels))
                if not torch.isfinite(loss):
                    raise ValueError('Non-finite probe loss')
                (loss / args.grad_accum).backward()
                step_loss += float(loss.detach()) / args.grad_accum
                if step >= args.warmup:
                    tokens += sum(len(e['input_ids']) for e in selected)
            if step == 0:
                initial_loss = step_loss
                gradients[name] = {k: p.grad.detach().cpu().clone() for k, p in model.named_parameters()}
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0, error_if_nonfinite=True)
            optimizer.step()
        sync()
        elapsed = time.perf_counter() - started
        phases[name] = {'initial_loss': initial_loss, 'measured_processed_tokens': tokens,
                        'measured_steps': args.steps, 'seconds': elapsed, 'tokens_per_second': tokens / elapsed,
                        'peak_gpu_allocated_bytes': torch.cuda.max_memory_allocated() if args.device == 'cuda' else None}
        del optimizer
        model.zero_grad(set_to_none=True)
        gc.collect()
        if args.device == 'cuda':
            torch.cuda.empty_cache()
    squared_delta = squared_reference = max_absolute = 0.0
    for name, reference in gradients['full_projection'].items():
        delta = gradients['target_only'][name] - reference
        squared_delta += float(delta.double().square().sum())
        squared_reference += float(reference.double().square().sum())
        max_absolute = max(max_absolute, float(delta.abs().max()))
    if hf_hashes != {p.name: digest(p) for p in sorted(hf.iterdir()) if p.is_file()}:
        raise ValueError('Owned HF files changed')
    result = {'schema': 'origin-target-loss-profile-v1', 'arguments': {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
              'hf_source_hashes': hf_hashes, 'run_manifest_sha256': digest(args.run / 'run-manifest.json'),
              'cache_manifest_sha256': digest(args.cache / 'cache-manifest.json'),
              'uniform_train_row_indices': positions, 'autocast_bf16': bf16, 'phases': phases,
              'first_update_gradient_relative_l2': (squared_delta / squared_reference) ** .5,
              'first_update_gradient_max_absolute': max_absolute,
              'speed_ratio_target_over_full': phases['target_only']['tokens_per_second'] / phases['full_projection']['tokens_per_second'],
              'code_sha256': digest(Path(__file__)), 'loss_code_sha256': digest(Path(__file__).with_name('origin_target_loss.py')),
              'baseline_code_sha256': digest(Path(__file__).with_name('train_origin.py')),
              'status': 'compute/gradient probe only; not training accuracy, strict bit parity or product latency'}
    write_json(args.out, result)
    print(json.dumps({k: v for k, v in result.items() if k in ('phases', 'first_update_gradient_relative_l2',
                     'first_update_gradient_max_absolute', 'speed_ratio_target_over_full')}, indent=2))


if __name__ == '__main__':
    main()
