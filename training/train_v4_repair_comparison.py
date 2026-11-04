# SPDX-License-Identifier: Apache-2.0
"""Repair-only diagnostic baselines with matched observations and generated validation."""
import argparse
import importlib.metadata
import json
import math
from pathlib import Path
import random
import shutil
import time

import torch
from peft import LoraConfig, get_peft_model, get_peft_model_state_dict, set_peft_model_state_dict
from safetensors.torch import load_file, save_file
from transformers import AutoModelForCausalLM, set_seed

from build_v4_unified_dataset import rows
from evaluate_v4_unified import hf_generate
from origin_contract import digest, write_json
from train_v4_unified import export_hf, loss_for, micro_batches, telemetry
from v4_unified_contract import BASE_VOCAB, V4UnifiedCodec


@torch.no_grad()
def generated_validation(model, codec, cases, device, batch_size=16):
    model.eval()
    details = []
    for start in range(0, len(cases), batch_size):
        chunk = cases[start:start + batch_size]
        tokens = hf_generate(model, [codec.prompt(r) for r in chunk], 96, device)
        for row, output in zip(chunk, tokens):
            answer = codec.decode_answer(output, 'repair')
            normal = row['target']['action'] == 'keep'
            unchanged = answer['action'] == 'keep' or (answer['action'] == 'emit' and
                answer['text'] == (row['reference_surface'] if codec.metadata['repair_output'] == 'surface'
                                   else row['input']['reading']))
            details.append({'id': row['id'], 'input': row['input'], 'target': row['target'],
                            'answer': answer, 'normal': normal, 'unchanged': unchanged,
                            'correct': answer == row['target'], 'tokens': output})
    summary = {'emit_total': sum(not d['normal'] for d in details),
               'emit_correct': sum(not d['normal'] and d['correct'] for d in details),
               'normal_total': sum(d['normal'] for d in details),
               'normal_changed': sum(d['normal'] and not d['unchanged'] for d in details),
               'invalid': sum(d['answer']['action'] == 'invalid' for d in details)}
    model.train()
    return {'summary': summary, 'details': details}


def selection_key(summary):
    return summary['invalid'], summary['normal_changed'], -summary['emit_correct']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--parent', type=Path, default=Path('artifacts/checkpoints/standard-v4-nano-transfer-merged'))
    p.add_argument('--dataset', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--epochs', type=int, default=3)
    p.add_argument('--effective-batch', type=int, default=32)
    p.add_argument('--micro-batch', type=int, default=8)
    p.add_argument('--micro-token-budget', type=int, default=1536)
    p.add_argument('--lr', type=float, default=2e-4)
    p.add_argument('--seed', type=int, default=42)
    p.add_argument('--resume', action='store_true')
    p.add_argument('--device', choices=('cuda', 'cpu'), default='cuda')
    a = p.parse_args()
    torch.set_num_threads(4)
    set_seed(a.seed)
    codec = V4UnifiedCodec(a.dataset / 'codec')
    manifest_path = a.dataset.parent / 'manifest.json'
    dm = json.loads(manifest_path.read_text(encoding='utf-8'))
    mode = codec.metadata['repair_output']
    contract = dm['conditions'][mode]
    for name, expected in contract['files'].items():
        if digest(a.dataset / name) != expected:
            raise ValueError('Matched dataset changed')
    if digest(a.dataset / 'codec/v4-unified-codec.json') != contract['codec_sha256']:
        raise ValueError('Matched codec changed')
    from audit_v4_repair_comparison import audit
    audit_result = audit(a.dataset.parent)
    examples = [codec.example(r, dm['max_length']) for r in rows(a.dataset / 'train.jsonl')]
    cases = list(rows(a.dataset / 'validation-generation.jsonl'))
    if not examples or not cases or a.epochs < 1:
        raise ValueError('Empty data or invalid epochs')
    arguments = {k: str(v) if isinstance(v, Path) else v for k, v in vars(a).items() if k != 'resume'}
    code_names = ['training/train_v4_repair_comparison.py', 'training/train_v4_unified.py',
                  'training/v4_unified_contract.py', 'data/build_v4_repair_comparison.py',
                  'data/audit_v4_repair_comparison.py', 'benchmarks/research/evaluate_v4_unified.py']
    manifest = {'schema': 'v4-repair-comparison-run-v1', 'arguments': arguments,
                'dataset_manifest_sha256': digest(manifest_path), 'audit': audit_result,
                'parent_hashes': {f.name: digest(f) for f in a.parent.iterdir() if f.is_file()},
                'code_hashes': {s: digest(s) for s in code_names},
                'adapter': {'rank': 16, 'alpha': 32, 'dropout': .05, 'targets': ['c_attn', 'c_proj'], 'dora': False},
                'loss': 'per-example target CE, no first-action weighting, no copy margin, repair only',
                'selection': 'lexicographic generated validation: invalid, normal_changed, negative emit_correct; not test cases',
                'data_order': 'each row once per epoch, Random(seed+epoch) shuffle, same paired IDs',
                'environment': {n: importlib.metadata.version(n) for n in ('torch', 'transformers', 'peft', 'tokenizers')}}
    a.out.mkdir(parents=True, exist_ok=a.resume)
    if a.resume:
        if json.loads((a.out / 'run-manifest.json').read_text(encoding='utf-8')) != manifest:
            raise ValueError('Resume manifest differs')
    else:
        write_json(a.out / 'run-manifest.json', manifest)
        write_json(a.out / 'sources.json', {'matched_dataset_manifest': str(manifest_path), 'sha256': digest(manifest_path)})
        (a.out / 'source-evidence').mkdir()
        shutil.copy2(manifest_path, a.out / 'source-evidence/dataset-manifest.json')
        shutil.copy2('training/origin_licenses/Apache-2.0.txt', a.out / 'LICENSE')
        for name in code_names:
            target = a.out / 'source-code' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(name, target)
    base = AutoModelForCausalLM.from_pretrained(a.parent, local_files_only=True, dtype=torch.float32,
                                              attn_implementation='sdpa')
    base.resize_token_embeddings(len(codec.tokenizer), mean_resizing=False)
    model = get_peft_model(base, LoraConfig(r=16, lora_alpha=32, lora_dropout=.05,
                                           target_modules=['c_attn', 'c_proj'], bias='none'))
    embedding = model.get_input_embeddings().weight
    embedding.requires_grad_(True)
    embedding.register_hook(lambda g: torch.cat((torch.zeros_like(g[:BASE_VOCAB]), g[BASE_VOCAB:])))
    model.to(a.device).train()
    adapter_params = [v for v in model.parameters() if v.requires_grad and v is not embedding]
    optimizer = torch.optim.AdamW([{'params': adapter_params, 'lr': a.lr, 'weight_decay': 0.},
                                   {'params': [embedding], 'lr': a.lr * 10, 'weight_decay': 0.}],
                                  fused=a.device == 'cuda')
    lrs = [g['lr'] for g in optimizer.param_groups]
    total_steps = a.epochs * math.ceil(len(examples) / a.effective_batch)
    step, processed, targets, next_epoch, next_offset = 0, 0, 0, 0, 0
    best, history = None, []
    state_path = a.out / 'training-state.pt'
    if a.resume:
        state = torch.load(state_path, map_location=a.device, weights_only=False)
        if state['manifest_sha256'] != digest(a.out / 'run-manifest.json'):
            raise ValueError('Resume state belongs to a different run')
        set_peft_model_state_dict(model, state['adapter'])
        with torch.no_grad():
            embedding[BASE_VOCAB:].copy_(state['new_rows'])
        optimizer.load_state_dict(state['optimizer'])
        step, processed, targets = state['step'], state['processed'], state['targets']
        next_epoch, next_offset, best, history = state['next_epoch'], state['next_offset'], state['best'], state['history']
        torch.set_rng_state(state['torch_rng'].cpu())
        if a.device == 'cuda':
            torch.cuda.set_rng_state_all([v.cpu() for v in state['cuda_rng']])
    started = time.perf_counter()

    def save_state(epoch, offset):
        state = {'adapter': get_peft_model_state_dict(model, save_embedding_layers=False),
                 'new_rows': embedding[BASE_VOCAB:].detach().cpu(), 'optimizer': optimizer.state_dict(),
                 'step': step, 'processed': processed, 'targets': targets, 'next_epoch': epoch, 'next_offset': offset,
                 'best': best, 'history': history, 'torch_rng': torch.get_rng_state(),
                 'cuda_rng': torch.cuda.get_rng_state_all() if a.device == 'cuda' else [],
                 'manifest_sha256': digest(a.out / 'run-manifest.json')}
        temporary = state_path.with_suffix('.partial')
        torch.save(state, temporary)
        temporary.replace(state_path)

    for epoch in range(next_epoch, a.epochs):
        order = list(range(len(examples)))
        random.Random(a.seed + epoch).shuffle(order)
        offset = next_offset if epoch == next_epoch else 0
        for start in range(offset, len(order), a.effective_batch):
            chunk = [examples[i] for i in order[start:start + a.effective_batch]]
            chunk.sort(key=lambda e: len(e['input_ids']))
            step += 1
            fraction = min(1., step/max(1, total_steps*.05)) * .5 * (1 + math.cos(math.pi * step / total_steps))
            for group, lr in zip(optimizer.param_groups, lrs):
                group['lr'] = lr * fraction
            optimizer.zero_grad(set_to_none=True)
            total_loss = 0.
            for batch in micro_batches(chunk, a.micro_batch, a.micro_token_budget):
                loss, metrics = loss_for(model, None, batch, a.device, 0.)
                if not torch.isfinite(loss):
                    raise FloatingPointError('Nonfinite loss')
                (loss * len(batch) / len(chunk)).backward()
                total_loss += float(loss.detach()) * len(batch)
                processed += metrics['tokens']
                targets += sum(sum(t != -100 for t in e['labels']) for e in batch)
            torch.nn.utils.clip_grad_norm_([v for v in model.parameters() if v.requires_grad], 1.)
            optimizer.step()
            if step == 1 or step % 25 == 0:
                progress = {'step': step, 'steps': total_steps, 'epoch': epoch + (start + len(chunk))/len(order),
                            'loss': total_loss / len(chunk), 'processed_tokens': processed, 'target_tokens': targets,
                            'seconds': time.perf_counter() - started, **telemetry()}
                write_json(a.out / 'progress.json', progress)
                print(json.dumps(progress), flush=True)
            if step % 200 == 0:
                save_state(epoch, start + len(chunk))
        validation = generated_validation(model, codec, cases, a.device)
        write_json(a.out / f'validation-generation-epoch{epoch + 1}.json', validation)
        record = {'epoch': epoch + 1, 'step': step, **validation['summary']}
        history.append(record)
        key = selection_key(validation['summary'])
        if best is None or key < tuple(best['key']):
            best = {'key': list(key), 'epoch': epoch + 1, 'step': step, 'summary': validation['summary']}
            save_file({k: v.detach().cpu().contiguous() for k, v in get_peft_model_state_dict(model, save_embedding_layers=False).items()},
                      str(a.out / 'best-adapter.safetensors'))
            save_file({'new_rows': embedding[BASE_VOCAB:].detach().cpu().contiguous()}, str(a.out / 'best-new-rows.safetensors'))
            write_json(a.out / 'best-selection.json', best)
        write_json(a.out / 'validation-history.json', history)
        save_state(epoch + 1, 0)
        print(json.dumps(record), flush=True)
    del optimizer
    selected = {'adapter': load_file(str(a.out / 'best-adapter.safetensors')),
                'new_rows': load_file(str(a.out / 'best-new-rows.safetensors'))['new_rows']}
    hf = export_hf(a.out, a.parent, a.dataset / 'codec', model, selected)
    write_json(a.out / 'metrics.json', {'status': 'training_completed', 'steps': step, 'epochs': a.epochs,
               'processed_tokens': processed, 'target_tokens': targets, 'elapsed_seconds': time.perf_counter() - started,
               'best': best, 'hf_hashes': {p.name: digest(p) for p in hf.iterdir() if p.is_file()},
               'product_status': 'Repair-only diagnostic; not a three-task model, not for IME installation'})


if __name__ == '__main__':
    main()
