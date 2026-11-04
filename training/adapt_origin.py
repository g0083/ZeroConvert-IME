# SPDX-License-Identifier: Apache-2.0
"""Adapt only an owned Origin student to its source-preserving raw-state fix.

This is a new optimization stage, not a resume that silently changes data.
No v4 or third-party initial weights are loaded. Vocabulary transfer requires
the same source documents and original train/validation lineage.
"""
import argparse
from collections import Counter
import importlib.metadata
import hashlib
import json
import math
import os
from pathlib import Path
import random
import shutil
import time

from origin_contract import OriginCodec, digest, read_rows, write_json
from train_origin import balanced_ce, batch_tensors, budget_steps, check_inputs, evaluate


def rows_from(path):
    with path.open(encoding='utf-8') as stream:
        for line in stream:
            yield json.loads(line)


def transfer_signature(row, partition):
    value = dict(row)
    value.pop('category', None)  # Diagnostic stratum only; never model input.
    if 'original_target' in value:
        if partition != 'train' or value['target'] != {'action': 'abstain', 'text': ''}:
            raise ValueError('Only conservative training ambiguity relabeling is allowed')
        value['target'] = value.pop('original_target')
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).digest()


def verify_transfer(dataset, base_manifest):
    manifest = json.loads((dataset / 'manifest.json').read_text(encoding='utf-8'))
    if manifest.get('operation') != 'remove repair label-correlated surface/cursor slots':
        raise ValueError('This adapter accepts the reviewed source-preserving raw-state transformation only')
    parent = Path(manifest['parent'])
    if parent.resolve() != Path(base_manifest['arguments']['dataset']).resolve():
        raise ValueError('Vocabulary parent dataset mismatch')
    if digest(parent / 'manifest.json') != manifest['parent_manifest_sha256']:
        raise ValueError('Parent dataset manifest changed')
    if manifest['parent_output_hashes'] != base_manifest['dataset_hashes']:
        raise ValueError('Vocabulary was trained on a different partition')
    for name, expected in manifest['parent_output_hashes'].items():
        if digest(parent / name) != expected:
            raise ValueError('Parent source hashes changed')
    for name, expected in manifest['output_hashes'].items():
        if digest(dataset / name) != expected:
            raise ValueError('Adaptation dataset hashes changed')
    from rebalance_origin_repair import transform
    for partition in ('train', 'val'):
        originals = {}
        for row in rows_from(parent / f'{partition}.jsonl'):
            originals[row['id']] = transfer_signature(transform(row), partition)
        for row in rows_from(dataset / f'{partition}.jsonl'):
            if originals.get(row['id']) != transfer_signature(row, partition):
                raise ValueError('New text, changed validation labels, or cross-partition source in vocabulary transfer')
        del originals
    return manifest


def task_sampling_weights(rows, weights):
    counts = Counter(r['task'] for r in rows)
    if set(weights) - counts.keys() or any(not isinstance(v, (float, int)) or not math.isfinite(v) or v < 0 for v in weights.values()) or sum(weights.values()) <= 0:
        raise ValueError('Invalid task sampling weights')
    cumulative, total = [], 0.0
    for row in rows:
        total += weights.get(row['task'], 0.0) / counts[row['task']]
        cumulative.append(total)
    return cumulative


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--token-budget', type=int, default=10000000)
    parser.add_argument('--batch-size', type=int, default=16)
    parser.add_argument('--grad-accum', type=int, default=4)
    parser.add_argument('--lr', type=float, default=1e-4)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--task-weights', default='{"convert":0.45,"repair_qwerty":0.30,"predict":0.20}')
    parser.add_argument('--max-validation-examples', type=int, default=512)
    parser.add_argument('--validation-every', type=int, default=500)
    parser.add_argument('--save-every', type=int, default=1000)
    parser.add_argument('--resume', type=Path)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    if min(args.token_budget, args.batch_size, args.grad_accum) <= 0 or args.lr <= 0:
        parser.error('Positive budget/batch/accumulation/lr required')
    parent = json.loads((args.base / 'run-manifest.json').read_text(encoding='utf-8'))
    if parent.get('schema') != 'zerocon-origin-run-v1':
        raise ValueError('Only owned Origin weights are accepted')
    if not parent.get('checkpoint_materialization') and not (args.base / 'metrics.json').is_file():
        raise ValueError('Wait for the base run to finish, or materialize a completed intermediate checkpoint')
    lineage = verify_transfer(args.dataset, parent)
    train, validation = check_inputs(args.dataset)
    full_val_count = len(validation)
    if args.max_validation_examples > 0 and full_val_count > args.max_validation_examples:
        indices = sorted(random.Random(args.seed).sample(range(full_val_count), args.max_validation_examples))
        validation = [validation[i] for i in indices]
    weights = json.loads(args.task_weights)
    cumulative = task_sampling_weights(train, weights)
    args.task_weights = json.dumps(weights, sort_keys=True)
    codec = OriginCodec(args.base / 'tokenizer.model')
    if digest(args.base / 'tokenizer.model') != parent['tokenizer_sha256']:
        raise ValueError('Base vocabulary changed')
    import torch
    from transformers import AutoModelForCausalLM
    torch.manual_seed(args.seed)
    torch.set_num_threads(4)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    model = AutoModelForCausalLM.from_pretrained(args.base / 'hf', local_files_only=True,
                                               use_safetensors=True, trust_remote_code=False).to(device)
    if model.config.model_type != 'llama' or model.config.vocab_size != codec.sp.get_piece_size():
        raise ValueError('Unexpected base model/tokenizer')
    if model.lm_head.weight.data_ptr() != model.model.embed_tokens.weight.data_ptr():
        raise ValueError('Origin tied embedding invariant failed')
    max_length = model.config.max_position_embeddings
    print(f'Tokenizing adaptation: {len(train)} train / {len(validation)} sampled validation', flush=True)
    examples = [codec.example(row, max_length) for row in train]
    val_examples = [codec.example(row, max_length) for row in validation]
    steps = budget_steps([len(e['input_ids']) for e in examples], args.token_budget,
                         args.batch_size, args.grad_accum, args.seed, cumulative)
    optimizer = torch.optim.AdamW([
        {'params': [p for p in model.parameters() if p.ndim >= 2], 'weight_decay': .1},
        {'params': [p for p in model.parameters() if p.ndim < 2], 'weight_decay': 0.0},
    ], lr=args.lr, betas=(.9, .95))
    warmup = max(1, int(steps * .03))
    def scale(step):
        return (step + 1) / warmup if step < warmup else .1 + .45 * (1 + math.cos(math.pi * (step - warmup) / max(1, steps - warmup)))
    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, scale)
    args.out.mkdir(parents=True)
    source_files = [Path(__file__), Path(__file__).with_name('origin_contract.py'), Path(__file__).with_name('train_origin.py'),
                    Path(__file__).resolve().parents[1] / 'data/rebalance_origin_repair.py']
    manifest = {
        'schema': 'zerocon-origin-run-v1', 'initialization': 'adaptation-from-owned-random-origin-student',
        'condition': 'Origin-B/sequence-CE/raw-state-adaptation',
        'arguments': {**{k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
                      'max_length': max_length, 'steps': steps},
        'dataset_hashes': {name: digest(args.dataset / name) for name in ('train.jsonl', 'val.jsonl', 'sources.json')},
        'tokenizer_sha256': parent['tokenizer_sha256'],
        'tokenizer_training_data_hashes': parent.get('tokenizer_training_data_hashes', parent['dataset_hashes']),
        'tokenizer_transfer_lineage': {'manifest_sha256': digest(args.dataset / 'manifest.json'),
                                       'parent_manifest_sha256': lineage['parent_manifest_sha256']},
        'parent': {'run': str(args.base), 'manifest_sha256': digest(args.base / 'run-manifest.json'),
                   'hf_hashes': {p.name: digest(p) for p in sorted((args.base / 'hf').iterdir()) if p.is_file()},
                   'checkpoint_materialization': parent.get('checkpoint_materialization')},
        'model_config': model.config.to_dict(), 'parameters': sum(p.numel() for p in model.parameters()),
        'code_hashes': {p.name: digest(p) for p in source_files},
        'versions': {name: importlib.metadata.version(name) for name in ('torch', 'transformers', 'sentencepiece', 'safetensors')},
        'source_evidence_hashes': {str(p.relative_to(args.dataset)): digest(p)
            for p in sorted((args.dataset / 'source-evidence').rglob('*')) if p.is_file()},
        'device': device, 'gpu': torch.cuda.get_device_name() if device == 'cuda' else None, 'pid': os.getpid(),
        'validation_population': full_val_count, 'validation_sample': len(validation),
        'scope': 'new optimization stage with fresh optimizer; not silent resume across changed data',
    }
    write_json(args.out / 'run-manifest.json', manifest)
    for name in ('tokenizer.model', 'tokenizer.vocab', 'origin-tokenizer.json', 'LICENSE'):
        shutil.copy2(args.base / name, args.out / name)
    shutil.copy2(args.dataset / 'sources.json', args.out / 'sources.json')
    for name, source in (('source-evidence', args.dataset), ('third-party-licenses', args.base)):
        if (source / name).is_dir():
            shutil.copytree(source / name, args.out / name)
    code_dir = args.out / 'source-code'
    code_dir.mkdir()
    for path in source_files:
        shutil.copy2(path, code_dir / path.name)
    state = {'step': 0, 'processed_tokens': 0, 'target_tokens': 0, 'losses': [], 'validation_history': []}
    if args.resume:
        saved = torch.load(args.resume, map_location=device, weights_only=False)
        for key in ('dataset_hashes', 'tokenizer_sha256', 'code_hashes', 'parent', 'model_config'):
            if saved['manifest'][key] != manifest[key]:
                raise ValueError(f'Adaptation resume identity mismatch: {key}')
        for key in ('base', 'dataset', 'token_budget', 'steps', 'batch_size', 'grad_accum', 'lr', 'seed', 'task_weights', 'max_validation_examples'):
            if saved['manifest']['arguments'][key] != manifest['arguments'][key]:
                raise ValueError(f'Adaptation resume settings mismatch: {key}')
        model.load_state_dict(saved['model'])
        optimizer.load_state_dict(saved['optimizer'])
        scheduler.load_state_dict(saved['scheduler'])
        torch.set_rng_state(saved['rng_cpu'].cpu())
        if device == 'cuda':
            torch.cuda.set_rng_state_all([value.cpu() for value in saved['rng_cuda']])
        state = saved['state']
    initial = evaluate(model, validation, val_examples, codec, device)
    initial_tokens = state['processed_tokens']
    start = time.perf_counter()
    bf16 = device == 'cuda' and torch.cuda.is_bf16_supported()
    model.train()
    def save(path):
        torch.save({'manifest': manifest, 'state': state, 'model': model.state_dict(),
            'optimizer': optimizer.state_dict(), 'scheduler': scheduler.state_dict(),
            'rng_cpu': torch.get_rng_state(), 'rng_cuda': torch.cuda.get_rng_state_all() if device == 'cuda' else []}, path)
    print(f'Adapting {manifest["parameters"]:,} owned parameters; budget={args.token_budget:,}; steps={steps}', flush=True)
    for step in range(state['step'], steps):
        optimizer.zero_grad(set_to_none=True)
        step_loss = 0.0
        for micro in range(args.grad_accum):
            indices = random.Random(args.seed + step * args.grad_accum + micro).choices(range(len(examples)), cum_weights=cumulative, k=args.batch_size)
            selected = [examples[i] for i in indices]
            ids, labels, mask = batch_tensors(selected, codec, device)
            with torch.autocast(device_type=device, dtype=torch.bfloat16, enabled=bf16):
                loss = balanced_ce(model(input_ids=ids, attention_mask=mask, use_cache=False).logits, labels)
            if not torch.isfinite(loss):
                raise ValueError('Non-finite adaptation loss')
            (loss / args.grad_accum).backward()
            step_loss += float(loss.detach()) / args.grad_accum
            state['processed_tokens'] += sum(len(e['input_ids']) for e in selected)
            state['target_tokens'] += sum(sum(t != -100 for t in e['labels']) for e in selected)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0, error_if_nonfinite=True)
        optimizer.step()
        scheduler.step()
        state['step'] = step + 1
        state['losses'].append(step_loss)
        if step == 0 or (step + 1) % 50 == 0 or step + 1 == steps:
            progress = {'step': step + 1, 'steps': steps, 'processed_tokens': state['processed_tokens'],
                        'target_tokens': state['target_tokens'], 'loss': step_loss, 'elapsed_seconds': time.perf_counter() - start,
                        'pid': os.getpid()}
            write_json(args.out / 'progress.json', progress)
            print(json.dumps(progress), flush=True)
        if args.validation_every > 0 and (step + 1) % args.validation_every == 0:
            state['validation_history'].append({'step': step + 1, **evaluate(model, validation, val_examples, codec, device)})
            write_json(args.out / 'validation-history.json', state['validation_history'])
        if args.save_every > 0 and (step + 1) % args.save_every == 0:
            save(args.out / f'training-state-{step + 1}.pt')
    if device == 'cuda':
        torch.cuda.synchronize()
    seconds = time.perf_counter() - start
    save(args.out / 'training-state.pt')
    model.save_pretrained(args.out / 'hf')
    final = evaluate(model, validation, val_examples, codec, device)
    report = {'training_seconds': seconds, 'processed_tokens': state['processed_tokens'], 'target_tokens': state['target_tokens'],
              'tokens_per_second': (state['processed_tokens'] - initial_tokens) / max(seconds, 1e-9),
              'initial_validation': initial, 'final_validation': final,
              'training_loss_first': state['losses'][0], 'training_loss_last': state['losses'][-1],
              'parent': manifest['parent'], 'goal_status': 'unproven; evaluate complete task and deployment gates separately'}
    write_json(args.out / 'metrics.json', report)
    print(json.dumps({k: report[k] for k in ('training_seconds', 'processed_tokens', 'training_loss_last')}, indent=2))


if __name__ == '__main__':
    main()
