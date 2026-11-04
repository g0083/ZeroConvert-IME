# SPDX-License-Identifier: Apache-2.0
"""Train a random-initialized, tied-embedding Llama IME student (Origin-A A0).

CE baseline only. Old v4 weights/IDs, DoRA and logit KL are not transplanted.
Requires sentencepiece plus the existing torch/transformers training runtime.
"""
import argparse
from collections import Counter, defaultdict
import importlib.metadata
import json
import math
from pathlib import Path
import random
import shutil
import time

from origin_contract import (
    CONTROLS, FIELDS, OriginCodec, SIZES, digest, parameter_count, read_rows,
    validate_partitions, validate_sources, write_json,
)


def check_inputs(dataset):
    from dataset_isolation import evaluation_keys, normalize
    train, val = (read_rows(dataset / f'{name}.jsonl') for name in ('train', 'val'))
    sources = json.loads((dataset / 'sources.json').read_text(encoding='utf-8'))
    validate_sources(train + val, sources)
    validate_partitions(train, val)
    reserved = evaluation_keys()
    for row in train + val:
        texts = [row['source_text'], row['target'].get('text', '')]
        texts += [v for v in row['input'].values() if isinstance(v, str)]
        if any(normalize(s) in reserved for s in texts if s):
            raise ValueError(f'Existing development evaluation collision: {row["id"]}')
    return train, val


def train_tokenizer(rows, out, vocab_size):
    import sentencepiece as spm
    def corpus():
        # Authored tokenizer support text; no held-out or user input is used.
        yield '  ASCII 0123456789\t\n'
        for row in rows:
            yield row['source_text']
            for field in FIELDS:
                value = row['input'].get(field)
                if isinstance(value, str) and value:
                    yield value
            if row['target'].get('text'):
                yield row['target']['text']
    spm.SentencePieceTrainer.train(
        sentence_iterator=corpus(), model_prefix=str(out / 'tokenizer'),
        vocab_size=vocab_size, model_type='bpe', byte_fallback=True,
        normalization_rule_name='identity', remove_extra_whitespaces=False,
        add_dummy_prefix=False, character_coverage=1.0, hard_vocab_limit=False,
        required_chars='▁',  # ASCII space must have its normal piece even in tiny corpora.
        bos_id=1, eos_id=2, pad_id=3, unk_id=0, bos_piece='<bos>', eos_piece='<eot>',
        pad_piece='<pad>', unk_piece='<unk>', control_symbols=CONTROLS,
        shuffle_input_sentence=False, num_threads=1, minloglevel=2,
    )
    codec = OriginCodec(out / 'tokenizer.model')
    probes = ['  ABC  ＡＢＣ　１２３\n\t', 'か\u3099', '𠮷󠄀👩🏽\u200d💻', '▁',
              '<task_convert><out><eot><keep>', 'URL: https://example.test/a?b=１']
    for probe in probes:
        codec.text(probe)
    write_json(out / 'origin-tokenizer.json', {
        'schema': 'zerocon-origin-v1', 'vocab_size': codec.sp.get_piece_size(),
        'requested_vocab_size': vocab_size, 'control_ids': codec.ids,
        'bos_id': codec.bos_id, 'eos_id': codec.eos_id, 'pad_id': codec.pad_id,
        'sha256': digest(out / 'tokenizer.model'),
        'literal_u2581': 'encode as UTF-8 byte fallback pieces in OriginCodec.text',
        'data_encoding': 'SentencePiece.encode, never parse special tokens in data fields',
        'runtime_compatibility': 'HF/GGUF/llama.cpp parity not yet certified',
    })
    return codec


def batch_tensors(examples, codec, device):
    import torch
    length = max(len(e['input_ids']) for e in examples)
    ids = [e['input_ids'] + [codec.pad_id] * (length - len(e['input_ids'])) for e in examples]
    labels = [e['labels'] + [-100] * (length - len(e['labels'])) for e in examples]
    input_ids = torch.tensor(ids, device=device)
    return input_ids, torch.tensor(labels, device=device), input_ids.ne(codec.pad_id)


def balanced_ce(logits, labels):
    import torch.nn.functional as F
    # Causal shift; prompt/pad ignored, EOS always supervised.
    labels = labels[:, 1:]
    losses = F.cross_entropy(logits[:, :-1].float().transpose(1, 2), labels, reduction='none', ignore_index=-100)
    counts = labels.ne(-100).sum(1)
    if (counts == 0).any():
        raise ValueError('An example has no supervised target')
    return (losses.sum(1) / counts).mean()


def budget_steps(lengths, budget, batch_size, grad_accum, seed, cum_weights=None):
    """Replay deterministic index sampling to fix the processed-token budget."""
    consumed, steps = 0, 0
    while consumed < budget:
        for micro in range(grad_accum):
            consumed += sum(random.Random(seed + steps * grad_accum + micro).choices(lengths, cum_weights=cum_weights, k=batch_size))
        steps += 1
    return steps


def evaluate(model, rows, examples, codec, device, generate=False, max_new=96, batch_size=16):
    import torch
    totals = defaultdict(list)
    outputs = []
    model.eval()
    with torch.no_grad():
        for start_index in range(0, len(examples), batch_size):
            selected = examples[start_index:start_index + batch_size]
            ids, labels, mask = batch_tensors(selected, codec, device)
            logits = model(input_ids=ids, attention_mask=mask, use_cache=False).logits
            for offset in range(len(selected)):
                loss = balanced_ce(logits[offset:offset + 1], labels[offset:offset + 1])
                totals[rows[start_index + offset]['task']].append(float(loss))
        for row, example in zip(rows, examples):
            if generate:
                prompt = codec.prompt(row)
                input_ids = torch.tensor([prompt], device=device)
                start = time.perf_counter()
                generated = model.generate(input_ids=input_ids, attention_mask=torch.ones_like(input_ids),
                    max_new_tokens=min(max_new, model.config.max_position_embeddings - len(prompt)),
                    do_sample=False, pad_token_id=codec.pad_id,
                    eos_token_id=codec.eos_id, use_cache=True)[0, len(prompt):].tolist()
                if device == 'cuda':
                    torch.cuda.synchronize()
                answer = codec.decode_answer(generated)
                outputs.append({'id': row['id'], 'task': row['task'], 'expected': row['target'],
                                'actual': answer, 'exact': answer == row['target'],
                                'latency_ms': (time.perf_counter() - start) * 1000})
    model.train()
    return {'ce_by_task': {k: sum(v) / len(v) for k, v in totals.items()}, 'outputs': outputs}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--size', choices=SIZES, default='t0')
    parser.add_argument('--vocab-size', type=int, help='T0:1024; N28:8192; other profiles:16384')
    parser.add_argument('--steps', type=int, default=400)
    parser.add_argument('--token-budget', type=int, default=0, help='Derive exact deterministic step count from processed tokens; overrides --steps')
    parser.add_argument('--batch-size', type=int, default=16)
    parser.add_argument('--grad-accum', type=int, default=1)
    parser.add_argument('--task-weights', help='JSON task sampling weights; default preserves empirical dataset mix')
    parser.add_argument('--validation-every', type=int, default=0)
    parser.add_argument('--max-validation-examples', type=int, default=512, help='Fixed sampled CE diagnostic; 0 means all')
    parser.add_argument('--max-length', type=int, default=256)
    parser.add_argument('--lr', type=float, default=3e-4)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--resume', type=Path, help='Origin training-state.pt only; total --steps must match')
    parser.add_argument('--save-every', type=int, default=0)
    parser.add_argument('--tokenizer', type=Path, help='Reuse a train-only Origin tokenizer directory')
    parser.add_argument('--generate-validation', action='store_true')
    parser.add_argument('--generate-train', action='store_true', help='Overfit diagnostic; never a held-out score')
    args = parser.parse_args()
    if args.vocab_size is None:
        args.vocab_size = 1024 if args.size == 't0' else 8192 if args.size == 'n28' else 16384
    if min(args.steps, args.batch_size, args.max_length, args.grad_accum) <= 0 or args.lr <= 0 or args.vocab_size < 300:
        parser.error('Positive training sizes/lr and vocab >= 300 required')
    if args.out.exists():
        raise FileExistsError(f'Existing artifacts preserved: {args.out}')
    train, val = check_inputs(args.dataset)  # Before allocating model/GPU.
    import torch
    import transformers
    from transformers import LlamaConfig, LlamaForCausalLM
    import sentencepiece
    torch.manual_seed(args.seed)
    torch.set_num_threads(4)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(args.seed)
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    args.out.mkdir(parents=True)
    dataset_hashes = {name: digest(args.dataset / name) for name in ('train.jsonl', 'val.jsonl', 'sources.json')}
    if args.resume and not args.tokenizer:
        parser.error('Resume requires --tokenizer pointing to its original run')
    if args.tokenizer:
        token_meta = json.loads((args.tokenizer / 'run-manifest.json').read_text(encoding='utf-8'))
        if token_meta['dataset_hashes'] != dataset_hashes:
            raise ValueError('Tokenizer provenance must match this dataset, including held-out split')
        for name in ('tokenizer.model', 'tokenizer.vocab', 'origin-tokenizer.json'):
            shutil.copy2(args.tokenizer / name, args.out / name)
        codec = OriginCodec(args.out / 'tokenizer.model')
        recorded = json.loads((args.out / 'origin-tokenizer.json').read_text(encoding='utf-8'))
        if digest(args.out / 'tokenizer.model') != recorded['sha256']:
            raise ValueError('Tokenizer hash mismatch')
    else:
        codec = train_tokenizer(train, args.out, args.vocab_size)
    expected_vocab = 8192 if args.size == 'n28' else 16384
    if args.size != 't0' and codec.sp.get_piece_size() != expected_vocab:
        raise ValueError(f'{args.size} requires an actual {expected_vocab}-piece vocabulary; enlarge the approved training corpus')
    full_val_count = len(val)
    full_val_task_counts = dict(Counter(r['task'] for r in val))
    if args.max_validation_examples > 0 and len(val) > args.max_validation_examples:
        indices = sorted(random.Random(args.seed).sample(range(len(val)), args.max_validation_examples))
        val = [val[i] for i in indices]
    print(f'Tokenizing {len(train)} training / {len(val)} validation examples (full validation={full_val_count})', flush=True)
    train_examples = [codec.example(r, args.max_length) for r in train]
    val_examples = [codec.example(r, args.max_length) for r in val]
    cum_weights = None
    if args.task_weights:
        weights = json.loads(args.task_weights)
        counts = Counter(r['task'] for r in train)
        if set(weights) - counts.keys() or any(not isinstance(v, (int, float)) or not math.isfinite(v) or v < 0 for v in weights.values()) or sum(weights.values()) <= 0:
            raise ValueError('Task weights must name present tasks with finite nonnegative weights and positive total')
        total, cum_weights = 0.0, []
        for row in train:
            total += weights.get(row['task'], 0.0) / counts[row['task']]
            cum_weights.append(total)
        args.task_weights = json.dumps(weights, sort_keys=True)
    if args.token_budget > 0:
        args.steps = budget_steps([len(e['input_ids']) for e in train_examples], args.token_budget,
                                  args.batch_size, args.grad_accum, args.seed, cum_weights)
        print(f'Processed-token budget {args.token_budget:,}: {args.steps:,} optimizer steps', flush=True)
    layers, width, heads, kv_heads, ffn = SIZES[args.size]
    config = LlamaConfig(vocab_size=codec.sp.get_piece_size(), hidden_size=width,
        intermediate_size=ffn, num_hidden_layers=layers, num_attention_heads=heads,
        num_key_value_heads=kv_heads, max_position_embeddings=args.max_length,
        tie_word_embeddings=True, attention_bias=False, mlp_bias=False,
        bos_token_id=codec.bos_id, eos_token_id=codec.eos_id, pad_token_id=codec.pad_id,
        rms_norm_eps=1e-5, rope_theta=10000.0, attention_dropout=0.0)
    model = LlamaForCausalLM(config).to(device)
    count = sum(p.numel() for p in model.parameters())
    if count != parameter_count(args.size, codec.sp.get_piece_size()):
        raise ValueError(f'Parameter formula mismatch: {count}')
    if model.lm_head.weight.data_ptr() != model.model.embed_tokens.weight.data_ptr():
        raise ValueError('Embedding weights are not tied')
    groups = [
        {'params': [p for p in model.parameters() if p.ndim >= 2], 'weight_decay': 0.1},
        {'params': [p for p in model.parameters() if p.ndim < 2], 'weight_decay': 0.0},
    ]
    optimizer = torch.optim.AdamW(groups, lr=args.lr, betas=(0.9, 0.95))
    warmup = max(1, int(args.steps * 0.03))
    def scale(step):
        if step < warmup:
            return (step + 1) / warmup
        return 0.1 + 0.45 * (1 + math.cos(math.pi * (step - warmup) / max(1, args.steps - warmup)))
    scheduler = torch.optim.lr_scheduler.LambdaLR(optimizer, scale)
    manifest = {
        'schema': 'zerocon-origin-run-v1', 'initialization': 'random' if not args.resume else 'origin-resume',
        'condition': ('Origin-B/sequence-CE' if any(s.get('teacher') for s in json.loads((args.dataset / 'sources.json').read_text(encoding='utf-8')).values())
                      else 'Origin-A/A0-CE'),
        'arguments': {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
        'dataset_hashes': dataset_hashes, 'tokenizer_sha256': digest(args.out / 'tokenizer.model'),
        'source_evidence_hashes': {str(p.relative_to(args.dataset)): digest(p)
            for p in sorted((args.dataset / 'source-evidence').rglob('*')) if p.is_file()},
        'parameters': count, 'device': device,
        'versions': {name: importlib.metadata.version(name) for name in ('torch', 'transformers', 'sentencepiece', 'safetensors')},
        'cuda': torch.version.cuda, 'gpu': torch.cuda.get_device_name(0) if device == 'cuda' else None,
        'code_hashes': {p.name: digest(p) for p in (Path(__file__), Path(__file__).with_name('origin_contract.py'))},
        'partition_counts': {'train': dict(Counter(r['task'] for r in train)), 'validation': full_val_task_counts,
                             'validation_sample': dict(Counter(r['task'] for r in val))},
        'limits': ['Synthetic wiring quality only unless corpus independently reviewed',
                   'Exact development-key exclusion only; semantic overlap not certified',
                   'Not GGUF certified or installed in IME'],
    }
    write_json(args.out / 'run-manifest.json', manifest)
    shutil.copy2(args.dataset / 'sources.json', args.out / 'sources.json')
    if (args.dataset / 'source-evidence').is_dir():
        shutil.copytree(args.dataset / 'source-evidence', args.out / 'source-evidence')
    license_root = Path(__file__).with_name('origin_licenses')
    shutil.copy2(license_root / 'Apache-2.0.txt', args.out / 'LICENSE')
    # Retain the actual implementation license in the artifact directory.
    licenses = args.out / 'third-party-licenses'
    licenses.mkdir()
    shutil.copy2(license_root / 'sentencepiece-LICENSE.txt', licenses / 'sentencepiece-LICENSE')
    shutil.copy2(license_root / 'README.md', licenses / 'README.md')
    for name in ('sentencepiece', 'transformers', 'torch', 'safetensors'):
        dist = importlib.metadata.distribution(name)
        for f in dist.files or []:
            if Path(str(f)).name.lower() in ('license', 'license.txt', 'notice', 'notice.txt'):
                source = Path(dist.locate_file(f))
                if source.is_file():
                    shutil.copy2(source, licenses / f'{name}-{source.name}')
    state = {'step': 0, 'processed_tokens': 0, 'target_tokens': 0, 'losses': []}
    if args.resume:
        saved = torch.load(args.resume, map_location=device, weights_only=False)
        prior = saved['manifest']
        for key in ('dataset_hashes', 'tokenizer_sha256', 'code_hashes', 'source_evidence_hashes'):
            if prior[key] != manifest[key]:
                raise ValueError(f'Resume identity mismatch: {key}')
        for key in ('size', 'steps', 'batch_size', 'max_length', 'lr', 'seed', 'grad_accum', 'max_validation_examples', 'token_budget', 'task_weights'):
            if prior['arguments'][key] != manifest['arguments'][key]:
                raise ValueError(f'Resume setting mismatch: {key}')
        model.load_state_dict(saved['model'])
        optimizer.load_state_dict(saved['optimizer'])
        scheduler.load_state_dict(saved['scheduler'])
        torch.set_rng_state(saved['rng_cpu'].cpu())
        if device == 'cuda':
            torch.cuda.set_rng_state_all([s.cpu() for s in saved['rng_cuda']])
        state = saved['state']
    initial_val = evaluate(model, val, val_examples, codec, device)
    bf16 = device == 'cuda' and torch.cuda.is_bf16_supported()
    start = time.perf_counter()
    initial_tokens = state['processed_tokens']
    validation_history = []
    print(f'{args.size}: {count:,} parameters; {device}; bf16 autocast={bf16}', flush=True)
    def save_state(path):
        torch.save({'manifest': manifest, 'state': state, 'model': model.state_dict(),
            'optimizer': optimizer.state_dict(), 'scheduler': scheduler.state_dict(),
            'rng_cpu': torch.get_rng_state(),
            'rng_cuda': torch.cuda.get_rng_state_all() if device == 'cuda' else []}, path)
    for step in range(state['step'], args.steps):
        optimizer.zero_grad(set_to_none=True)
        step_loss = 0.0
        for micro in range(args.grad_accum):
            indices = random.Random(args.seed + step * args.grad_accum + micro).choices(range(len(train_examples)), cum_weights=cum_weights, k=args.batch_size)
            examples = [train_examples[i] for i in indices]
            ids, labels, mask = batch_tensors(examples, codec, device)
            with torch.autocast(device_type=device, dtype=torch.bfloat16, enabled=bf16):
                loss = balanced_ce(model(input_ids=ids, attention_mask=mask, use_cache=False).logits, labels)
            if not torch.isfinite(loss):
                raise ValueError('Non-finite training loss')
            (loss / args.grad_accum).backward()
            step_loss += float(loss.detach()) / args.grad_accum
            state['processed_tokens'] += sum(len(e['input_ids']) for e in examples)
            state['target_tokens'] += sum(sum(t != -100 for t in e['labels']) for e in examples)
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0, error_if_nonfinite=True)
        optimizer.step()
        scheduler.step()
        state['step'] = step + 1
        state['losses'].append(step_loss)
        if step == 0 or (step + 1) % 50 == 0 or step + 1 == args.steps:
            print(f'step {step + 1}/{args.steps}: loss={state["losses"][-1]:.4f}; processed={state["processed_tokens"]}; '
                  f'tokens/s={(state["processed_tokens"] - initial_tokens) / max(time.perf_counter() - start, 1e-9):.0f}', flush=True)
            write_json(args.out / 'progress.json', {'step': step + 1, 'steps': args.steps,
                'processed_tokens': state['processed_tokens'], 'target_tokens': state['target_tokens'],
                'training_loss': step_loss, 'elapsed_seconds': time.perf_counter() - start})
        if args.validation_every > 0 and (step + 1) % args.validation_every == 0:
            validation_history.append({'step': step + 1, **evaluate(model, val, val_examples, codec, device)})
            write_json(args.out / 'validation-history.json', validation_history)
        if args.save_every > 0 and (step + 1) % args.save_every == 0:
            save_state(args.out / f'training-state-{step + 1}.pt')
    if device == 'cuda':
        torch.cuda.synchronize()
    elapsed = time.perf_counter() - start
    save_state(args.out / 'training-state.pt')
    model.save_pretrained(args.out / 'hf')
    final_val = evaluate(model, val, val_examples, codec, device, args.generate_validation)
    train_eval = evaluate(model, train, train_examples, codec, device, args.generate_train) if args.generate_train else None
    report = {'parameters': count, 'training_seconds': elapsed,
        'processed_tokens': state['processed_tokens'], 'target_tokens': state['target_tokens'],
        'tokens_per_second': (state['processed_tokens'] - initial_tokens) / max(elapsed, 1e-9),
        'initial_validation': initial_val, 'final_validation': final_val,
        'training_overfit_diagnostic': train_eval, 'training_loss_first': state['losses'][0],
        'training_loss_last': state['losses'][-1],
        'peak_gpu_allocated_bytes': torch.cuda.max_memory_allocated() if device == 'cuda' else None}
    write_json(args.out / 'metrics.json', report)
    print(json.dumps({k: report[k] for k in ('training_seconds', 'tokens_per_second', 'training_loss_first', 'training_loss_last')}, indent=2), flush=True)


if __name__ == '__main__':
    main()
