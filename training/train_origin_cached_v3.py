# SPDX-License-Identifier: Apache-2.0
"""Owned Origin trainer with target-only projection and audited stage sampling.

The original and v2 cached trainers remain unchanged for existing comparisons.
Changed-data warm starts require verified training-only span augmentation.
"""
import argparse
from collections import Counter
import importlib.metadata
import json
import math
import os
from pathlib import Path
import random
import shutil
import time

from origin_contract import OriginCodec, SIZES, digest, parameter_count, write_json
from origin_token_cache import TokenCache
from origin_target_loss import target_balanced_ce
from origin_sampling import ACTION_NAMES, cache_actions, cache_context_states, sampling_weights, verify_augmentation_binding
from train_origin import batch_tensors, budget_steps, evaluate


def train(args, train_cache, val_cache):
    meta = train_cache.manifest
    dataset = Path(meta['dataset'])
    for name, expected in meta['dataset_hashes'].items():
        if digest(dataset / name) != expected:
            raise ValueError('Cached source dataset changed')
    if digest(dataset / 'manifest.json') != meta['dataset_manifest_sha256']:
        raise ValueError('Cached source manifest changed')
    if digest(args.cache / 'tokenizer.model') != meta['tokenizer_sha256']:
        raise ValueError('Cached vocabulary changed')
    codec = OriginCodec(args.cache / 'tokenizer.model')
    expected_vocab = 8192 if args.size == 'n28' else 16384
    if args.size != 't0' and codec.sp.get_piece_size() != expected_vocab:
        raise ValueError('Model profile needs its actual planned vocabulary size')
    lengths, tasks = train_cache.lengths_and_tasks()
    weights = json.loads(args.task_weights)
    action_weights = json.loads(args.action_weights)
    actions = cache_actions(train_cache, codec)
    contexts = cache_context_states(train_cache, codec) if args.balance_repair_context else None
    cumulative, action_populations = sampling_weights(tasks, meta['task_names'], weights, actions, action_weights,
                                                     contexts, args.balance_repair_context)
    context_populations = (Counter((ACTION_NAMES[a], bool(c)) for t, a, c in zip(tasks, actions, contexts)
                                  if meta['task_names'][t] == 'repair_qwerty') if contexts is not None else None)
    args.task_weights = json.dumps(weights, sort_keys=True)
    args.action_weights = json.dumps(action_weights, sort_keys=True)
    steps = budget_steps(lengths, args.token_budget, args.batch_size, args.grad_accum, args.seed, cumulative)
    import torch
    from transformers import AutoModelForCausalLM, LlamaConfig, LlamaForCausalLM
    torch.manual_seed(args.seed)
    torch.set_num_threads(4)
    if args.device == 'cuda' and not torch.cuda.is_available():
        raise ValueError('CUDA was explicitly requested but is unavailable')
    device = ('cuda' if torch.cuda.is_available() else 'cpu') if args.device == 'auto' else args.device
    if device == 'cuda':
        torch.cuda.manual_seed_all(args.seed)
    layers, width, heads, kv_heads, ffn = SIZES[args.size]
    config = LlamaConfig(vocab_size=codec.sp.get_piece_size(), hidden_size=width, intermediate_size=ffn,
        num_hidden_layers=layers, num_attention_heads=heads, num_key_value_heads=kv_heads,
        max_position_embeddings=meta['max_length'], tie_word_embeddings=True,
        attention_bias=False, mlp_bias=False, rms_norm_eps=1e-5, rope_theta=10000.0,
        attention_dropout=0.0, bos_token_id=codec.bos_id, eos_token_id=codec.eos_id, pad_token_id=codec.pad_id)
    parent = None
    if args.base:
        base = json.loads((args.base / 'run-manifest.json').read_text(encoding='utf-8'))
        if base.get('schema') != 'zerocon-origin-run-v1':
            raise ValueError('Warm start requires an owned Origin parent')
        if not (args.base / 'metrics.json').is_file() and not base.get('checkpoint_materialization'):
            raise ValueError('Use a completed run or a frozen materialized checkpoint')
        lineage = json.loads(args.span_lineage_audit.read_text(encoding='utf-8')) if args.span_lineage_audit else None
        isolation = json.loads(args.isolation_audit.read_text(encoding='utf-8')) if args.isolation_audit else None
        parent_meta = json.loads((args.cache / 'parent-cache-provenance.json').read_text(encoding='utf-8')) if (args.cache / 'parent-cache-provenance.json').is_file() else None
        transfer = verify_augmentation_binding(base, meta, lineage, isolation,
                      json.loads((dataset / 'manifest.json').read_text(encoding='utf-8')), parent_meta)
        if transfer['kind'] != 'exact-same-dataset':
            if (digest(args.span_lineage_audit) != meta['tokenizer_transfer']['span_lineage_audit_sha256'] or
                    digest(args.isolation_audit) != meta['isolation_audit_sha256'] or
                    lineage['code_sha256'] != digest(Path(__file__).resolve().parents[1] / 'data/verify_origin_spans.py')):
                raise ValueError('Augmentation audit identities or verifier changed')
            transfer.update(lineage_audit_sha256=digest(args.span_lineage_audit), isolation_audit_sha256=digest(args.isolation_audit))
        hf_hashes = {p.name: digest(p) for p in sorted((args.base / 'hf').iterdir()) if p.is_file()}
        model = AutoModelForCausalLM.from_pretrained(args.base / 'hf', local_files_only=True,
                                                    use_safetensors=True, trust_remote_code=False).float().to(device)
        if {p.name: digest(p) for p in sorted((args.base / 'hf').iterdir()) if p.is_file()} != hf_hashes:
            raise ValueError('Parent HF files changed during loading')
        expected_config, actual_config = config.to_dict(), model.config.to_dict()
        for key in ('hidden_size', 'intermediate_size', 'num_hidden_layers', 'num_attention_heads', 'num_key_value_heads',
                    'max_position_embeddings', 'vocab_size', 'tie_word_embeddings', 'rope_parameters', 'rope_scaling',
                    'rms_norm_eps', 'hidden_act', 'attention_bias', 'mlp_bias', 'attention_dropout', 'bos_token_id', 'eos_token_id', 'pad_token_id'):
            if actual_config.get(key) != expected_config.get(key):
                raise ValueError(f'Warm-start architecture mismatch: {key}')
        parent = {'run': str(args.base), 'manifest_sha256': digest(args.base / 'run-manifest.json'),
                  'hf_hashes': hf_hashes, 'data_transfer': transfer}
    else:
        model = LlamaForCausalLM(config).to(device)
    count = sum(p.numel() for p in model.parameters())
    if count != parameter_count(args.size, codec.sp.get_piece_size()) or model.lm_head.weight.data_ptr() != model.model.embed_tokens.weight.data_ptr():
        raise ValueError('Origin profile/tied embedding invariant failed')
    optimizer = torch.optim.AdamW([
        {'params': [p for p in model.parameters() if p.ndim >= 2], 'weight_decay': .1},
        {'params': [p for p in model.parameters() if p.ndim < 2], 'weight_decay': 0.0},
    ], lr=args.lr, betas=(.9, .95))
    warmup = max(1, int(steps * .03))
    def scale(step):
        return (step + 1) / warmup if step < warmup else .1 + .45 * (1 + math.cos(math.pi * (step - warmup) / max(1, steps - warmup)))
    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, scale)
    indices = sorted(random.Random(args.seed).sample(range(len(val_cache)), min(args.validation_cases, len(val_cache))))
    _, val_tasks = val_cache.lengths_and_tasks()
    val_rows = [{'task': meta['task_names'][val_tasks[i]]} for i in indices]
    val_examples = [val_cache[i] for i in indices]
    args.out.mkdir(parents=True)
    code_paths = [Path(__file__), Path(__file__).with_name('origin_token_cache.py'),
                  Path(__file__).with_name('origin_contract.py'), Path(__file__).with_name('train_origin.py'),
                  Path(__file__).with_name('origin_sampling.py'), Path(__file__).with_name('origin_target_loss.py'),
                  Path(__file__).resolve().parents[1] / 'data/verify_origin_spans.py']
    manifest = {
        'schema': 'zerocon-origin-run-v1', 'condition': 'Origin-B/sequence-CE/mmap-cache-v3-target-only',
        'loss_implementation': 'same per-example causal CE; lm_head/CE only at supervised positions; full decoder/prompt gradients retained',
        'initialization': 'owned-origin-warm-start' if args.base else 'random', 'parent': parent,
        'arguments': {**{k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
                      'dataset': str(dataset), 'max_length': meta['max_length'], 'steps': steps},
        'dataset_hashes': meta['dataset_hashes'], 'tokenizer_sha256': meta['tokenizer_sha256'],
        'token_cache_manifest_sha256': digest(args.cache / 'cache-manifest.json'),
        'model_config': model.config.to_dict(), 'parameters': count, 'device': device, 'pid': os.getpid(),
        'gpu': torch.cuda.get_device_name() if device == 'cuda' else None,
        'versions': {name: importlib.metadata.version(name) for name in ('torch', 'transformers', 'sentencepiece', 'safetensors')},
        'code_hashes': {p.name: digest(p) for p in code_paths},
        'validation_population': len(val_cache), 'validation_sample': len(indices),
        'validation_sample_indices': indices, 'partitions': meta['partitions'],
        'task_action_populations': action_populations,
        'repair_context_populations': ([{'action': action, 'has_context': context, 'rows': rows}
            for (action, context), rows in sorted(context_populations.items())] if context_populations is not None else None),
        'context_sampling_policy': ('keep/emit separately split 50:50 by observed context emptiness; abstain unchanged'
                                    if args.balance_repair_context else None),
        'source_evidence_hashes': {str(p.relative_to(dataset)): digest(p)
            for p in sorted((dataset / 'source-evidence').rglob('*')) if p.is_file()},
    }
    write_json(args.out / 'run-manifest.json', manifest)
    write_json(args.out / 'token-cache-provenance.json', meta)
    for name in ('tokenizer.model', 'tokenizer.vocab', 'origin-tokenizer.json'):
        shutil.copy2(args.cache / name, args.out / name)
    shutil.copy2(Path(__file__).with_name('origin_licenses') / 'Apache-2.0.txt', args.out / 'LICENSE')
    shutil.copy2(dataset / 'sources.json', args.out / 'sources.json')
    if (dataset / 'source-evidence').is_dir():
        shutil.copytree(dataset / 'source-evidence', args.out / 'source-evidence')
    license_dir = args.out / 'third-party-licenses'
    license_dir.mkdir()
    shutil.copy2(Path(__file__).with_name('origin_licenses') / 'sentencepiece-LICENSE.txt', license_dir / 'sentencepiece-LICENSE')
    for name in ('torch', 'transformers', 'safetensors'):
        dist = importlib.metadata.distribution(name)
        for file in dist.files or []:
            if Path(str(file)).name.lower() in ('license', 'license.txt', 'notice', 'notice.txt'):
                source = Path(dist.locate_file(file))
                if source.is_file():
                    shutil.copy2(source, license_dir / f'{name}-{source.name}')
    code_dir = args.out / 'source-code'
    code_dir.mkdir()
    for path in code_paths:
        shutil.copy2(path, code_dir / path.name)
    for label, path in (('span-lineage-audit.json', args.span_lineage_audit), ('isolation-audit.json', args.isolation_audit)):
        if path:
            shutil.copy2(path, args.out / label)
    state = {'step': 0, 'processed_tokens': 0, 'target_tokens': 0, 'losses': [], 'validation_history': []}
    if args.resume:
        saved = torch.load(args.resume, map_location=device, weights_only=False)
        for key in ('dataset_hashes', 'tokenizer_sha256', 'token_cache_manifest_sha256', 'model_config', 'code_hashes', 'parent', 'device'):
            if saved['manifest'][key] != manifest[key]:
                raise ValueError(f'Cache resume identity mismatch: {key}')
        for key in ('size', 'token_budget', 'steps', 'batch_size', 'grad_accum', 'lr', 'seed', 'task_weights', 'action_weights', 'balance_repair_context', 'validation_cases'):
            if saved['manifest']['arguments'][key] != manifest['arguments'][key]:
                raise ValueError(f'Cache resume settings mismatch: {key}')
        model.load_state_dict(saved['model'])
        optimizer.load_state_dict(saved['optimizer'])
        scheduler.load_state_dict(saved['scheduler'])
        torch.set_rng_state(saved['rng_cpu'].cpu())
        if device == 'cuda':
            torch.cuda.set_rng_state_all([s.cpu() for s in saved['rng_cuda']])
        state = saved['state']
    initial = evaluate(model, val_rows, val_examples, codec, device)
    initial_tokens = state['processed_tokens']
    start = time.perf_counter()
    bf16 = device == 'cuda' and torch.cuda.is_bf16_supported()
    model.train()
    def save(path):
        torch.save({'manifest': manifest, 'state': state, 'model': model.state_dict(),
            'optimizer': optimizer.state_dict(), 'scheduler': scheduler.state_dict(),
            'rng_cpu': torch.get_rng_state(), 'rng_cuda': torch.cuda.get_rng_state_all() if device == 'cuda' else []}, path)
    print(f'{args.size}: {count:,} parameters, {len(train_cache):,} mapped rows, {steps:,} steps', flush=True)
    for step in range(state['step'], steps):
        optimizer.zero_grad(set_to_none=True)
        step_loss = 0.0
        for micro in range(args.grad_accum):
            chosen = random.Random(args.seed + step * args.grad_accum + micro).choices(range(len(train_cache)), cum_weights=cumulative, k=args.batch_size)
            batch = [train_cache[i] for i in chosen]
            ids, labels, mask = batch_tensors(batch, codec, device)
            with torch.autocast(device_type=device, dtype=torch.bfloat16, enabled=bf16):
                loss = target_balanced_ce(model, ids, mask, labels)
            if not torch.isfinite(loss):
                raise ValueError('Non-finite cached training loss')
            (loss / args.grad_accum).backward()
            step_loss += float(loss.detach()) / args.grad_accum
            state['processed_tokens'] += sum(len(e['input_ids']) for e in batch)
            state['target_tokens'] += sum(sum(v != -100 for v in e['labels']) for e in batch)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0, error_if_nonfinite=True)
        optimizer.step()
        scheduler.step()
        state['step'] = step + 1
        state['losses'].append(step_loss)
        if step == 0 or (step + 1) % 50 == 0 or step + 1 == steps:
            progress = {'step': step + 1, 'steps': steps, 'processed_tokens': state['processed_tokens'],
                        'target_tokens': state['target_tokens'], 'loss': step_loss,
                        'elapsed_seconds': time.perf_counter() - start, 'pid': os.getpid()}
            write_json(args.out / 'progress.json', progress)
            print(json.dumps(progress), flush=True)
        if args.validation_every > 0 and (step + 1) % args.validation_every == 0:
            state['validation_history'].append({'step': step + 1, **evaluate(model, val_rows, val_examples, codec, device)})
            write_json(args.out / 'validation-history.json', state['validation_history'])
        if args.save_every > 0 and (step + 1) % args.save_every == 0:
            save(args.out / f'training-state-{step + 1}.pt')
    if device == 'cuda':
        torch.cuda.synchronize()
    seconds = time.perf_counter() - start
    save(args.out / 'training-state.pt')
    model.save_pretrained(args.out / 'hf')
    final = evaluate(model, val_rows, val_examples, codec, device)
    result = {'training_seconds': seconds, 'processed_tokens': state['processed_tokens'], 'target_tokens': state['target_tokens'],
              'tokens_per_second': (state['processed_tokens'] - initial_tokens) / max(seconds, 1e-9),
              'initial_validation': initial, 'final_validation': final,
              'training_loss_first': state['losses'][0], 'training_loss_last': state['losses'][-1],
              'cache_memory_design': 'readonly mmap tokens/index plus compact lengths/task/CDF arrays',
              'peak_gpu_allocated_bytes': torch.cuda.max_memory_allocated() if device == 'cuda' else None}
    write_json(args.out / 'metrics.json', result)
    print(json.dumps({k: result[k] for k in ('training_seconds', 'processed_tokens', 'training_loss_last')}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--size', choices=SIZES, default='n33')
    parser.add_argument('--token-budget', type=int, default=50000000)
    parser.add_argument('--batch-size', type=int, default=16)
    parser.add_argument('--grad-accum', type=int, default=4)
    parser.add_argument('--lr', type=float, default=3e-4)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--task-weights', default='{"convert":0.45,"repair_qwerty":0.30,"predict":0.20}')
    parser.add_argument('--action-weights', default='{}', help='Optional within-task keep/emit/abstain weights; no change by default')
    parser.add_argument('--balance-repair-context', action='store_true', help='Split keep/emit context-empty/present mass equally without changing action mass')
    parser.add_argument('--device', choices=('auto', 'cpu', 'cuda'), default='auto')
    parser.add_argument('--validation-cases', type=int, default=512)
    parser.add_argument('--validation-every', type=int, default=500)
    parser.add_argument('--save-every', type=int, default=1000)
    parser.add_argument('--base', type=Path)
    parser.add_argument('--span-lineage-audit', type=Path)
    parser.add_argument('--isolation-audit', type=Path)
    parser.add_argument('--resume', type=Path)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    if min(args.token_budget, args.batch_size, args.grad_accum, args.validation_cases) <= 0 or args.lr <= 0:
        parser.error('Positive training dimensions, validation sample and lr required')
    with TokenCache(args.cache, 'train') as train_cache, TokenCache(args.cache, 'val') as val_cache:
        train(args, train_cache, val_cache)


if __name__ == '__main__':
    main()
