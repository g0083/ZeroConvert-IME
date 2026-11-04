# SPDX-License-Identifier: Apache-2.0
"""Standard v4 -> QWERTY three-task research model; immutable parent and audits.

FP32 master weights, BF16 autocast/SDPA, rank-32 DoRA. Old embedding rows are
frozen by a gradient mask and zero decay. A separate frozen parent supplies KL
on aligned convert targets only. New token rows are saved independently of PEFT.
"""
import argparse
from collections import Counter, defaultdict
from contextlib import nullcontext
import copy
import importlib.metadata
import json
import math
import os
from pathlib import Path
import random
import shutil
import subprocess
import time

import numpy as np
import torch
import torch.nn.functional as F
from peft import LoraConfig, get_peft_model, get_peft_model_state_dict, set_peft_model_state_dict
from safetensors.torch import save_file, load_file
from transformers import AutoModelForCausalLM, AutoTokenizer, set_seed

from build_v4_unified_dataset import rows
from origin_contract import digest, write_json
from v4_unified_contract import BASE_VOCAB, V4UnifiedCodec, legacy_example
from v4_repair_edits import repair_edit_annotations


def autocast(device):
    return torch.autocast('cuda', dtype=torch.bfloat16) if device == 'cuda' else nullcontext()


def collate(examples, device):
    width = (max(len(e['input_ids']) for e in examples) + 7) // 8 * 8
    ids = torch.full((len(examples), width), 1, dtype=torch.long)
    labels = torch.full_like(ids, -100)
    mask = torch.zeros_like(ids)
    for i, e in enumerate(examples):
        n = len(e['input_ids'])
        ids[i, :n] = torch.tensor(e['input_ids'])
        labels[i, :n] = torch.tensor(e['labels'])
        mask[i, :n] = 1
    values = {'input_ids': ids, 'attention_mask': mask, 'labels': labels}
    return {k: (v.pin_memory() if device == 'cuda' else v).to(device, non_blocking=True) for k, v in values.items()}


def token_logits(model, batch):
    # Project only supervised positions, avoiding prompt*6000 output activation.
    hidden = model.base_model.model.transformer(input_ids=batch['input_ids'],
        attention_mask=batch['attention_mask'], use_cache=False).last_hidden_state
    valid = batch['labels'][:, 1:] != -100
    return model.base_model.model.lm_head(hidden[:, :-1][valid]).float(), valid


def loss_for(model, teacher, examples, device, kl_weight, repair_first_weight=0., repair_edit_weight=0.):
    batch = collate(examples, device)
    with autocast(device):
        logits, valid = token_logits(model, batch)
        y = batch['labels'][:, 1:][valid]
        per_token = F.cross_entropy(logits, y, reduction='none')
        owners = torch.arange(len(examples), device=device).unsqueeze(1).expand_as(valid)[valid]
        counts = torch.bincount(owners, minlength=len(examples)).clamp_min(1)
        ce = torch.zeros(len(examples), device=device).scatter_add(0, owners, per_token) / counts
        # Long emitted readings previously diluted their first decision over tens
        # of tokens, while keep's decision had half the entire example weight.
        if repair_first_weight:
            first = counts.cumsum(0) - counts
            repair = torch.tensor([e.get('group', '').startswith('repair_') for e in examples], device=device)
            ce = torch.where(repair, (1-repair_first_weight)*ce + repair_first_weight*per_token[first], ce)
        loss = ce.mean()
        edit_loss = logits.sum()*0
        if repair_edit_weight:
            target_sizes = [sum(v != -100 for v in e['labels'][1:]) for e in examples]
            target_starts, total = [], 0
            for size in target_sizes:
                target_starts.append(total)
                total += size
            edit_positions, edit_owners, negatives = [], [], []
            for i, example in enumerate(examples):
                if example.get('repair_edits') and not example.get('group', '').startswith('repair_emit'):
                    raise ValueError('Edit loss is restricted to erroneous repair emits')
                for relative, negative in example.get('repair_edits', []):
                    if not 0 <= relative < target_sizes[i]:
                        raise ValueError('Edit annotation outside supervised target')
                    edit_positions.append(target_starts[i]+relative)
                    edit_owners.append(i)
                    negatives.append(negative)
            if edit_positions:
                positions = torch.tensor(edit_positions, device=device)
                eowners = torch.tensor(edit_owners, device=device)
                negative = torch.tensor(negatives, device=device)
                positive_logits = logits[positions].gather(1, y[positions, None]).squeeze(1)
                wrong_logits = logits[positions].gather(1, negative.clamp_min(0)[:, None]).squeeze(1)
                margin = torch.where((negative >= 0) & (negative != y[positions]), F.relu(2. - (positive_logits-wrong_logits)), 0.)
                edit_tokens = F.cross_entropy(logits[positions], y[positions], reduction='none') + .25*margin
                esum = torch.zeros(len(examples), device=device).scatter_add(0, eowners, edit_tokens)
                ecnt = torch.bincount(eowners, minlength=len(examples)).clamp_min(1)
                edit_loss = (esum/ecnt).mean()
                loss = loss + repair_edit_weight*edit_loss
        anchored = [i for i, e in enumerate(examples) if e.get('teacher') is not None]
        kl = logits.sum() * 0
        if anchored and kl_weight:
            teacher_examples = [examples[i]['teacher'] for i in anchored]
            tb = collate(teacher_examples, device)
            with torch.no_grad():
                th = teacher.transformer(input_ids=tb['input_ids'], attention_mask=tb['attention_mask'],
                                         use_cache=False).last_hidden_state
                tv = tb['labels'][:, 1:] != -100
                tl = teacher.lm_head(th[:, :-1][tv]).float()
            anchored_mask = torch.isin(owners, torch.tensor(anchored, device=device))
            sl = logits[anchored_mask]
            sy = y[anchored_mask]
            if not torch.equal(sy, tb['labels'][:, 1:][tv]):
                raise ValueError('Teacher target-relative alignment mismatch')
            temperature = 2.0
            distribution = F.softmax(tl / temperature, dim=-1)
            token_kl = F.kl_div(F.log_softmax(sl[:, :BASE_VOCAB] / temperature, dim=-1),
                               distribution, reduction='none').sum(-1) * temperature**2
            # Probability escape into appended IDs is measured separately from old-ID KL.
            escape = torch.logsumexp(sl, -1) - torch.logsumexp(sl[:, :BASE_VOCAB], -1)
            local_owners = owners[anchored_mask]
            sums = torch.zeros(len(examples), device=device).scatter_add(0, local_owners, token_kl + escape)
            kl = (sums[anchored] / counts[anchored]).sum() / len(examples)
            loss = loss + kl_weight * kl
    return loss, {'ce': float(ce.mean().detach()), 'kl': float(kl.detach()), 'edit': float(edit_loss.detach()),
                  'tokens': sum(len(e['input_ids']) for e in examples), 'target_tokens': int(valid.sum())}


def prepare(dataset, codec, old, max_length, repair_edit_weight=0.):
    all_examples, pools, counts = {}, {}, Counter()
    for split in ('train', 'val'):
        examples, groups = [], defaultdict(list)
        for row in rows(dataset / f'{split}.jsonl'):
            new = codec.example(row, max_length)
            if repair_edit_weight:
                new['repair_edits'] = repair_edit_annotations(codec, row)
                if new['repair_edits']:
                    counts[f'{split}_edit_supervised_examples'] += 1
                    counts[f'{split}_edit_supervised_positions'] += len(new['repair_edits'])
            group = row['task'] + ('_' + row['target']['action'] if row['task'] == 'repair' else '')
            if row['task'] == 'repair' and row['source_id'] == 'corrector-qwerty-replay':
                group += '_corrector'
            anchor = row['category'] == 'v4-anchor'
            if row['task'] == 'convert' and row.get('legacy_replay', True):
                legacy = legacy_example(row, old)
                ny = [x for x in new['labels'] if x != -100]
                oy = [x for x in legacy['labels'] if x != -100]
                if ny == oy and len(legacy['input_ids']) <= max_length:
                    new['teacher'] = legacy
                    counts[f'{split}_aligned_convert'] += 1
                else:
                    counts[f'{split}_unaligned_convert'] += 1
                if len(legacy['input_ids']) <= max_length:
                    legacy['teacher'] = copy.deepcopy(legacy)
                    name = 'anchor_legacy' if anchor else 'convert_legacy'
                    legacy['group'] = name
                    groups[name].append(len(examples))
                    examples.append(legacy)
                group = 'anchor_tagged' if anchor else 'convert_tagged'
            elif row['task'] == 'convert':
                group = 'anchor_tagged' if anchor else 'convert_tagged'
                counts[f'{split}_tagged_only_literal_copy'] += 1
            new['group'] = group
            groups[group].append(len(examples))
            examples.append(new)
        all_examples[split], pools[split] = examples, dict(groups)
    return all_examples, pools, dict(counts)


WEIGHTS = {'anchor_legacy': .15, 'anchor_tagged': .15, 'convert_legacy': .15,
           'convert_tagged': .15, 'repair_keep': .25*.65, 'repair_emit': .25*.25,
           'repair_abstain': .25*.10, 'predict': .15}


def sample_batch(examples, groups, rng, size, weights=None):
    weights = WEIGHTS if weights is None else weights
    keys = list(weights)
    if any(not groups.get(k) for k in keys):
        raise ValueError('Required task/action group missing')
    return [examples[rng.choice(groups[k])] for k in rng.choices(keys, list(weights.values()), k=size)]


def micro_batches(examples, micro, token_budget=0):
    offset = 0
    while offset < len(examples):
        size = min(micro, len(examples)-offset)
        while token_budget and size > 1:
            width = (max(len(e['input_ids']) for e in examples[offset:offset+size])+7)//8*8
            if width*size <= token_budget:
                break
            size = max(1, size//2)
        yield examples[offset:offset+size]
        offset += size


def resolve_weights(corrector_fraction, task_weights=None, action_weights=None, allow_zero_tasks=False):
    if not 0 <= corrector_fraction < 1:
        raise ValueError('Corrector replay fraction must be in [0,1)')
    if task_weights is None and action_weights is None:
        weights = dict(WEIGHTS)
    else:
        task_weights = task_weights or {'convert': .6, 'repair': .25, 'predict': .15}
        action_weights = action_weights or {'keep': .65, 'emit': .25, 'abstain': .10}
        def normalized(values, keys, allow_zero=False):
            if set(values) != set(keys) or any(type(v) not in (int,float) or not math.isfinite(v) or v < 0 or (v == 0 and not allow_zero) for v in values.values()):
                raise ValueError('All declared task/action weights must be positive and finite')
            if sum(values.values()) <= 0:
                raise ValueError('At least one task weight must be positive')
            return {k: values[k]/sum(values.values()) for k in keys}
        tasks = normalized(task_weights, ('convert', 'repair', 'predict'), allow_zero_tasks)
        actions = normalized(action_weights, ('keep', 'emit', 'abstain'))
        weights = {k: tasks['convert']/4 for k in ('anchor_legacy', 'anchor_tagged', 'convert_legacy', 'convert_tagged')}
        weights.update({f'repair_{k}': tasks['repair']*actions[k] for k in ('keep', 'emit', 'abstain')})
        weights['predict'] = tasks['predict']
    if corrector_fraction:
        for name in ('repair_keep', 'repair_emit', 'repair_abstain'):
            weights[name + '_corrector'] = weights[name] * corrector_fraction
            weights[name] *= 1-corrector_fraction
    return {k: v for k, v in weights.items() if v > 0}


def disable_dropout(model):
    # Anchor distributions must differ because of learned weights, not random
    # dropout masks present only in the student. Dedicated training is separate.
    for module in model.modules():
        if isinstance(module, torch.nn.Dropout):
            module.p = 0.


def create_models(parent, codec, rank, device, student_model=None, adapter_kind='dora', adapter_dropout=0.):
    # FP32 master keeps old input/head rows exactly unchanged across optimizer steps.
    base = AutoModelForCausalLM.from_pretrained(student_model or parent, local_files_only=True, dtype=torch.float32,
                                              attn_implementation='sdpa')
    if base.config.n_layer != 12 or base.config.n_positions != 1024:
        raise ValueError('Expected Standard v4 GPT-2 12-layer parent')
    original = base.get_input_embeddings().weight.detach().clone()
    base.resize_token_embeddings(len(codec.tokenizer), mean_resizing=False)
    with torch.no_grad():
        base.get_input_embeddings().weight[:len(original)].copy_(original)
        if not torch.equal(base.get_input_embeddings().weight[:len(original)], original):
            raise ValueError('Embedding expansion changed existing rows')
    model = get_peft_model(base, LoraConfig(r=rank, lora_alpha=rank*2, lora_dropout=adapter_dropout,
        target_modules=['c_attn', 'c_proj', 'c_fc'] if adapter_kind == 'dora' else ['c_attn', 'c_proj'],
        use_dora=adapter_kind == 'dora', bias='none'))
    embedding = model.get_input_embeddings().weight
    embedding.requires_grad_(True)
    embedding.register_hook(lambda gradient: torch.cat((torch.zeros_like(gradient[:BASE_VOCAB]), gradient[BASE_VOCAB:])))
    disable_dropout(model)
    teacher = AutoModelForCausalLM.from_pretrained(parent, local_files_only=True, dtype=torch.float32,
                                                 attn_implementation='sdpa').eval().requires_grad_(False)
    for module in teacher.modules():
        if isinstance(module, torch.nn.Dropout):
            module.p = 0
    model.to(device)
    teacher.to(device)
    model.train()
    return model, teacher


def optimizer_for(model, lr, uniform=False):
    groups = defaultdict(list)
    embedding = model.get_input_embeddings().weight
    for name, parameter in model.named_parameters():
        if not parameter.requires_grad:
            continue
        if parameter is embedding:
            key = (lr * 10, 0.)
        else:
            tier = next((int(part) for i, part in enumerate(name.split('.')) if i and name.split('.')[i-1] == 'h'), 5)
            multiplier = .4 if tier < 4 else 1 if tier < 8 else 1.3
            decay = 0. if parameter.ndim < 2 or 'lora_magnitude' in name else .01
            key = (lr, 0.) if uniform else (lr*multiplier, decay)
        groups[key].append(parameter)
    return torch.optim.AdamW([{'params': ps, 'lr': key[0], 'weight_decay': key[1]}
                             for key, ps in groups.items()], fused=torch.cuda.is_available())


def telemetry():
    try:
        result = subprocess.run(['nvidia-smi', '--query-gpu=utilization.gpu,power.draw,memory.used',
                                 '--format=csv,noheader,nounits'], capture_output=True, text=True, check=True,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        util, power, memory = map(float, result.stdout.strip().split(',')[:3])
        return {'gpu_util_percent': util, 'power_watts': power, 'memory_mib': memory}
    except (OSError, ValueError, subprocess.CalledProcessError):
        return {}


def profile(parent, codec, examples, groups, args, device, weights):
    records = []
    for micro in (4, 8, 16, 32, 48, 64):
        if args.effective_batch % micro:
            continue
        passed = [r for r in records if r['status'] == 'PASS']
        if device == 'cuda' and passed:
            previous = passed[-1]
            projected = 1024 + max(0, previous['peak_allocated_mib'] - 1024) * micro / previous['micro_batch']
            if projected > args.profile_memory_mib:
                records.append({'micro_batch': micro, 'status': 'SKIPPED_MEMORY_BUDGET',
                                'projected_peak_allocated_mib': projected, 'budget_mib': args.profile_memory_mib})
                print(json.dumps(records[-1]), flush=True)
                continue
        set_seed(args.seed)
        model, teacher = create_models(parent, codec, args.rank, device, args.student_model, args.adapter_kind, args.adapter_dropout)
        optimizer = optimizer_for(model, args.lr, args.uniform_lr)
        rng = random.Random(args.seed)
        optimizer.zero_grad(set_to_none=True)
        if device == 'cuda':
            torch.cuda.reset_peak_memory_stats()
        started, tokens, targets = time.perf_counter(), 0, 0
        measures = []
        try:
            for i in range(args.profile_steps + 5):
                batch = sample_batch(examples, groups, rng, micro, weights)
                loss, metrics = loss_for(model, teacher, batch, device, args.kl_weight, args.repair_first_token_weight, args.repair_edit_weight)
                (loss / (args.effective_batch // micro)).backward()
                if (i+1) % (args.effective_batch // micro) == 0:
                    torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad], 1.)
                    optimizer.step()
                    optimizer.zero_grad(set_to_none=True)
                if i == 4:
                    if device == 'cuda':
                        torch.cuda.synchronize()
                    started = time.perf_counter()
                elif i > 4:
                    tokens += metrics['tokens']
                    targets += metrics['target_tokens']
                if i > 4 and (i+1) % 20 == 0:
                    measures.append(telemetry())
                if device == 'cuda' and torch.cuda.max_memory_allocated()/2**20 > args.profile_memory_mib:
                    raise torch.cuda.OutOfMemoryError('Profile allocation safety budget exceeded')
            if device == 'cuda':
                torch.cuda.synchronize()
            elapsed = time.perf_counter() - started
            records.append({'micro_batch': micro, 'gradient_accumulation': args.effective_batch // micro,
                'measured_micro_steps': args.profile_steps, 'status': 'PASS', 'seconds': elapsed,
                'tokens_per_second': tokens/elapsed, 'target_tokens_per_second': targets/elapsed,
                'peak_allocated_mib': torch.cuda.max_memory_allocated()/2**20 if device == 'cuda' else None,
                'telemetry': measures})
        except torch.cuda.OutOfMemoryError:
            records.append({'micro_batch': micro, 'status': 'OOM'})
        print(json.dumps(records[-1]), flush=True)
        loss, batch = None, None
        del model, teacher, optimizer
        import gc
        gc.collect()
        if device == 'cuda':
            torch.cuda.empty_cache()
    passing = [r for r in records if r['status'] == 'PASS']
    if not passing:
        raise RuntimeError('No batch profile fits')
    return {'schema': 'v4-unified-gpu-profile-v1', 'effective_batch': args.effective_batch,
            'sdpa': True, 'pin_memory': device == 'cuda', 'num_workers': 0,
            'selection_metric': 'measured processed tokens/sec; wattage is observational',
            'records': records, 'selected_micro_batch': max(passing, key=lambda r: r['tokens_per_second'])['micro_batch']}


@torch.no_grad()
def validation(model, examples, groups, device, count=48, repair_first_weight=0., repair_edit_weight=0.):
    model.eval()
    result = {}
    for name, indices in groups.items():
        chosen = random.Random(731).sample(indices, min(count, len(indices)))
        losses = []
        for start in range(0, len(chosen), 8):
            subset = [examples[i] for i in chosen[start:start+8]]
            loss, _ = loss_for(model, None, subset, device, 0, repair_first_weight, repair_edit_weight)
            losses.extend([float(loss)] * len(subset))
        result[name] = sum(losses)/len(losses)
    model.train()
    return result


def save_checkpoint(path, model, optimizer, step, rng, manifest_hash, best, history, tokens):
    state = {'adapter': get_peft_model_state_dict(model, save_embedding_layers=False),
             'new_rows': model.get_input_embeddings().weight[BASE_VOCAB:].detach().cpu(),
             'optimizer': optimizer.state_dict(), 'step': step, 'sample_rng': rng.getstate(),
             'torch_rng': torch.get_rng_state(), 'cuda_rng': torch.cuda.get_rng_state_all() if torch.cuda.is_available() else [],
             'python_rng': random.getstate(), 'numpy_rng': np.random.get_state(),
             'manifest_sha256': manifest_hash, 'best': best, 'history': history, 'tokens': tokens}
    temporary = path.with_suffix('.partial')
    torch.save(state, temporary)
    temporary.replace(path)


def export_hf(out, parent, codec_path, model, selected):
    hf = out / 'hf'
    if hf.exists():
        raise FileExistsError(hf)
    set_peft_model_state_dict(model, selected['adapter'])
    with torch.no_grad():
        model.get_input_embeddings().weight[BASE_VOCAB:].copy_(selected['new_rows'].to(model.device))
    model.cpu()
    merged = model.merge_and_unload().float()
    parent_model = AutoModelForCausalLM.from_pretrained(parent, local_files_only=True, dtype=torch.float32)
    if not torch.equal(merged.get_input_embeddings().weight[:BASE_VOCAB], parent_model.get_input_embeddings().weight):
        raise ValueError('Frozen original embedding/head rows changed')
    del parent_model
    merged.config.use_cache = True
    merged.save_pretrained(hf, safe_serialization=True)
    for p in codec_path.iterdir():
        if p.is_file():
            shutil.copy2(p, hf / p.name)
    return hf


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--parent', type=Path, default=Path('artifacts/checkpoints/standard-v4-nano-transfer-merged'))
    p.add_argument('--student-model', type=Path)
    p.add_argument('--codec', type=Path, required=True)
    p.add_argument('--dataset', type=Path, required=True)
    p.add_argument('--audit', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--steps', type=int, default=1000)
    p.add_argument('--max-processed-tokens', type=int, default=0)
    p.add_argument('--task-gradients', choices=('off', 'measure', 'project'), default='off')
    p.add_argument('--batch-size', type=int, default=32)
    p.add_argument('--effective-batch', type=int, default=192)
    p.add_argument('--micro-token-budget', type=int, default=0)
    p.add_argument('--profile-steps', type=int, default=0)
    p.add_argument('--profile-only', action='store_true')
    p.add_argument('--profile-memory-mib', type=float, default=8500)
    p.add_argument('--lr', type=float, default=5e-5)
    p.add_argument('--rank', type=int, default=32)
    p.add_argument('--adapter-kind', choices=('dora', 'lora'), default='dora')
    p.add_argument('--adapter-dropout', type=float, default=0.)
    p.add_argument('--uniform-lr', action='store_true')
    p.add_argument('--allow-zero-tasks', action='store_true', help='Explicit diagnostic/staged curriculum only')
    p.add_argument('--generation-validation', type=Path, help='Audited generated-selection JSONL, never fixed evaluation cases')
    p.add_argument('--kl-weight', type=float, default=.5)
    p.add_argument('--repair-first-token-weight', type=float, default=0.)
    p.add_argument('--repair-edit-weight', type=float, default=0.)
    p.add_argument('--corrector-replay-fraction', type=float, default=0.)
    p.add_argument('--mixture', type=Path, help='Frozen JSON task_weights and repair_action_weights')
    p.add_argument('--max-length', type=int, default=256)
    p.add_argument('--seed', type=int, default=42)
    p.add_argument('--save-every', type=int, default=100)
    p.add_argument('--resume', type=Path)
    p.add_argument('--device', choices=('cpu', 'cuda'), default='cuda')
    args = p.parse_args()
    if args.max_processed_tokens < 0:
        p.error('Token budget must be nonnegative')
    if not 0 <= args.repair_first_token_weight <= 1:
        p.error('Repair first-token weight must be in [0,1]')
    if args.repair_edit_weight < 0:
        p.error('Repair edit weight must be nonnegative')
    mixture = json.loads(args.mixture.read_text(encoding='utf-8')) if args.mixture else {}
    if set(mixture) - {'task_weights', 'repair_action_weights', 'purpose'}:
        p.error('Unknown mixture configuration fields')
    weights = resolve_weights(args.corrector_replay_fraction, mixture.get('task_weights'), mixture.get('repair_action_weights'), args.allow_zero_tasks)
    if not 0 <= args.adapter_dropout < 1:
        p.error('Invalid adapter dropout')
    if args.adapter_dropout:
        p.error('Anchored joint training requires zero dropout; dedicated recipe uses train_v4_repair_comparison.py')
    if args.steps < 1 or args.batch_size < 1 or args.effective_batch % args.batch_size or args.max_length > 1024:
        p.error('Invalid budget/batch/context')
    if args.out.exists() and not args.resume:
        raise FileExistsError(args.out)
    device = args.device
    if device == 'cuda' and not (torch.cuda.is_available() and torch.cuda.is_bf16_supported()):
        raise RuntimeError('CUDA BF16 required for requested device')
    torch.set_num_threads(4)
    set_seed(args.seed)
    codec = V4UnifiedCodec(args.codec)
    generation_cases = None
    if args.generation_validation:
        gm = json.loads(args.generation_validation.with_suffix('.manifest.json').read_text(encoding='utf-8'))
        if (gm['codec_sha256'] != digest(args.codec/'v4-unified-codec.json') or
                gm['dataset_manifest_sha256'] != digest(args.dataset/'manifest.json') or
                gm['probe_sha256'] != digest(args.generation_validation)):
            raise ValueError('Generated-selection validation differs from audited data/codec')
        active_tasks = {('repair' if k.startswith('repair') else 'predict' if k == 'predict' else 'convert') for k in weights}
        generation_cases = [r for r in rows(args.generation_validation) if r['task'] in active_tasks]
        if not generation_cases:
            raise ValueError('Empty generated selection')
    audit = json.loads(args.audit.read_text(encoding='utf-8'))
    dm = json.loads((args.dataset / 'manifest.json').read_text(encoding='utf-8'))
    if audit['status'] != 'PASS' or audit['manifest_sha256'] != digest(args.dataset / 'manifest.json'):
        raise ValueError('Dataset audit mismatch')
    for name, expected in audit['output_hashes'].items():
        if digest(args.dataset / name) != expected:
            raise ValueError('Audited data changed')
    if audit['codec_sha256'] != digest(args.codec / 'v4-unified-codec.json'):
        raise ValueError('Audited codec changed')
    if args.max_length != dm['arguments']['max_length']:
        raise ValueError('Dataset length contract differs')
    old = AutoTokenizer.from_pretrained(args.parent, local_files_only=True)
    examples, pools, alignment = prepare(args.dataset, codec, old, args.max_length, args.repair_edit_weight)
    args.out.mkdir(parents=True, exist_ok=bool(args.resume))
    arguments = {k: str(v) if isinstance(v, Path) else v for k,v in vars(args).items() if k != 'resume'}
    manifest = {'schema': 'zerocon-v4-unified-run-v1', 'stage': 'P2/P3 research candidate; product gates unproven',
        'arguments': arguments, 'task_weights': weights,
        'mixture_sha256': digest(args.mixture) if args.mixture else None,
        'parent_hashes': {p.name: digest(p) for p in args.parent.iterdir() if p.is_file()},
        'student_hashes': {p.name: digest(p) for p in args.student_model.iterdir() if p.is_file()} if args.student_model else None,
        'codec_hashes': {p.name: digest(p) for p in args.codec.iterdir() if p.is_file()},
        'dataset_manifest_sha256': digest(args.dataset / 'manifest.json'), 'audit_sha256': digest(args.audit),
        'alignment': alignment, 'groups': {split: {k: len(v) for k,v in groups.items()} for split,groups in pools.items()},
        'code_hashes': {name: digest(name) for name in (
            'training/v4_unified_contract.py', 'training/train_v4_unified.py', 'training/train_nano_v3.py',
            'training/origin_contract.py', 'training/v4_repair_edits.py', 'data/build_v4_unified_dataset.py', 'data/dataset_isolation.py')},
        'environment': {n: importlib.metadata.version(n) for n in ('torch', 'transformers', 'peft', 'tokenizers', 'safetensors')},
        'gpu': torch.cuda.get_device_name() if device == 'cuda' else None,
        'cuda_allocator': os.environ.get('PYTORCH_CUDA_ALLOC_CONF'),
        'loss': 'per-example target CE, optional repair first-token CE and training-only edit CE + wrong-copy margin2*0.25; frozen v4 aligned convert KL and new-vocab probability escape',
        'adapter_kind': args.adapter_kind, 'adapter_dropout': args.adapter_dropout, 'effective_student_dropout': 0.,
        'generation_validation_sha256': digest(args.generation_validation) if args.generation_validation else None,
        'generation_reading_dictionary_sha256': digest('ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin')
            if generation_cases is not None and codec.metadata.get('repair_output') == 'surface' else None,
        'new_rows': 'FP32 tied embedding/head; 10x LR; original rows gradient masked; zero embedding decay',
        'licenses': 'retained upstream evidence; research candidate, publication review separate'}
    if generation_cases is not None:
        manifest['code_hashes']['training/v4_generation_selection.py'] = digest('training/v4_generation_selection.py')
    if args.task_gradients != 'off':
        manifest['code_hashes']['training/v4_task_gradients.py'] = digest('training/v4_task_gradients.py')
    for name in ('data/build_v4_observed_unified.py', 'data/build_v4_repair_comparison.py',
                 'data/audit_v4_repair_comparison.py', 'data/check_v4_unified_dataset.py',
                 'data/add_v4_literal_replay.py', 'benchmarks/research/evaluate_v4_unified.py',
                 'benchmarks/research/evaluate_v4_repair_comparison.py', 'training/rebase_v4_unified.py',
                 'training/infer_v4_unified.py'):
        if Path(name).is_file():
            manifest['code_hashes'][name] = digest(name)
    manifest['code_hashes']['data/add_v4_mixed_preservation.py'] = digest('data/add_v4_mixed_preservation.py')
    manifest_path = args.out / 'run-manifest.json'
    if args.resume:
        if json.loads(manifest_path.read_text(encoding='utf-8')) != manifest:
            raise ValueError('Resume manifest differs: code, inputs, settings must be unchanged')
    else:
        write_json(manifest_path, manifest)
        shutil.copy2(args.dataset / 'sources.json', args.out / 'sources.json')
        shutil.copytree(args.dataset / 'source-evidence', args.out / 'source-evidence')
        shutil.copy2('training/origin_licenses/Apache-2.0.txt', args.out / 'LICENSE')
        if args.mixture:
            shutil.copy2(args.mixture, args.out/'mixture.json')
        if args.generation_validation:
            shutil.copy2(args.generation_validation, args.out/'generation-validation.jsonl')
            shutil.copy2(args.generation_validation.with_suffix('.manifest.json'), args.out/'generation-validation.manifest.json')
        for name in manifest['code_hashes']:
            target = args.out / 'source-code' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(name, target)
    micro = args.batch_size
    if args.profile_steps:
        profile_result = profile(args.parent, codec, examples['train'], pools['train'], args, device, weights)
        write_json(args.out / 'gpu-profile.json', profile_result)
        micro = profile_result['selected_micro_batch']
        if args.profile_only:
            return
    model, teacher = create_models(args.parent, codec, args.rank, device, args.student_model, args.adapter_kind, args.adapter_dropout)
    optimizer = optimizer_for(model, args.lr, args.uniform_lr)
    base_lrs = [g['lr'] for g in optimizer.param_groups]
    rng = random.Random(args.seed)
    start, best, history, total_tokens = 0, float('inf'), [], 0
    if generation_cases is not None:
        best = None
    if args.resume:
        # Only local checkpoints generated by this program are accepted.
        if args.resume.resolve().parent != args.out.resolve():
            raise ValueError('Resume state must be inside this owned run')
        state = torch.load(args.resume, map_location=device, weights_only=False)
        if state['manifest_sha256'] != digest(manifest_path):
            raise ValueError('Checkpoint manifest mismatch')
        set_peft_model_state_dict(model, state['adapter'])
        with torch.no_grad():
            model.get_input_embeddings().weight[BASE_VOCAB:].copy_(state['new_rows'])
        optimizer.load_state_dict(state['optimizer'])
        start, best, history, total_tokens = state['step'], state['best'], state['history'], state['tokens']
        rng.setstate(state['sample_rng'])
        torch.set_rng_state(state['torch_rng'].cpu())
        if device == 'cuda':
            torch.cuda.set_rng_state_all([s.cpu() for s in state['cuda_rng']])
        random.setstate(state['python_rng'])
        np.random.set_state(state['numpy_rng'])
    else:
        initial = validation(model, examples['val'], pools['val'], device, repair_first_weight=args.repair_first_token_weight, repair_edit_weight=args.repair_edit_weight)
        write_json(args.out / 'initial-validation.json', initial)
        if generation_cases is not None:
            from v4_generation_selection import assess
            write_json(args.out / 'initial-generated-validation.json', assess(model, codec, generation_cases, device))
    optimizer.zero_grad(set_to_none=True)
    started = time.perf_counter()
    for step in range(start+1, args.steps+1):
        fraction = min(1., step/max(1, args.steps*.05)) * .5 * (1 + math.cos(math.pi*step/args.steps))
        for group, lr in zip(optimizer.param_groups, base_lrs):
            group['lr'] = lr * fraction
        sampled = sample_batch(examples['train'], pools['train'], rng, args.effective_batch, weights)
        # Sorting each effective batch reduces padding without changing its samples/weights.
        sampled.sort(key=lambda e: len(e['input_ids']))
        losses, ces, kls, edits = [], [], [], []
        task_stats = {}
        if args.task_gradients != 'off':
            from v4_task_gradients import backward_tasks
            totals, task_stats = backward_tasks(model, teacher, sampled, micro, args.micro_token_budget,
                args.effective_batch, args.kl_weight, args.repair_first_token_weight, args.repair_edit_weight,
                loss_for, micro_batches, args.task_gradients == 'project')
            losses, ces, kls, edits = [totals['loss']], [totals['ce']], [totals['kl']], [totals['edit']]
            total_tokens += totals['tokens']
        for subset in ([] if args.task_gradients != 'off' else micro_batches(sampled, micro, args.micro_token_budget)):
            loss, metrics = loss_for(model, teacher, subset, device, args.kl_weight, args.repair_first_token_weight, args.repair_edit_weight)
            if not torch.isfinite(loss):
                raise FloatingPointError('Nonfinite loss')
            (loss * (len(subset)/args.effective_batch)).backward()
            losses.append(float(loss.detach())*len(subset))
            ces.append(metrics['ce']*len(subset))
            kls.append(metrics['kl']*len(subset))
            edits.append(metrics['edit']*len(subset))
            total_tokens += metrics['tokens']
        norm = torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad], 1.)
        if task_stats:
            with (args.out / 'task-gradient-history.jsonl').open('a', encoding='utf-8') as stream:
                stream.write(json.dumps({'step': step, **task_stats}) + '\n')
        optimizer.step()
        optimizer.zero_grad(set_to_none=True)
        if step % 10 == 0 or step == 1:
            progress = {'step': step, 'steps': args.steps, 'loss': sum(losses)/args.effective_batch,
                'ce': sum(ces)/args.effective_batch, 'kl': sum(kls)/args.effective_batch,
                'edit': sum(edits)/args.effective_batch, 'grad_norm': float(norm),
                'processed_tokens': total_tokens, 'seconds': time.perf_counter()-started,
                'micro_batch': micro, **task_stats, **telemetry()}
            write_json(args.out / 'progress.json', progress)
            print(json.dumps(progress), flush=True)
        budget_reached = bool(args.max_processed_tokens and total_tokens >= args.max_processed_tokens)
        if step % args.save_every == 0 or step == args.steps or budget_reached:
            result = validation(model, examples['val'], pools['val'], device, repair_first_weight=args.repair_first_token_weight, repair_edit_weight=args.repair_edit_weight)
            score = sum(result.values())/len(result)
            history.append({'step': step, 'macro_objective': score, 'groups': result,
                            **({'macro_ce': score} if not args.repair_edit_weight else {})})
            if generation_cases is not None:
                from v4_generation_selection import assess
                if manifest['generation_reading_dictionary_sha256'] and digest('ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin') != manifest['generation_reading_dictionary_sha256']:
                    raise ValueError('Generated-validation reading dictionary changed')
                generated = assess(model, codec, generation_cases, device)
                history[-1]['generated'] = generated['summary']
                history[-1]['selection_key'] = generated['key'] + [score]
                write_json(args.out/f'generated-validation-step{step}.json', generated)
                score = history[-1]['selection_key']
            if best is None or score < best:
                best = score
                save_file({k: v.detach().cpu().contiguous() for k,v in get_peft_model_state_dict(model, save_embedding_layers=False).items()}, str(args.out / 'best-adapter.safetensors'))
                save_file({'new_rows': model.get_input_embeddings().weight[BASE_VOCAB:].detach().cpu().contiguous()}, str(args.out / 'best-new-rows.safetensors'))
                write_json(args.out / 'best-selection.json', history[-1])
            write_json(args.out / 'validation-history.json', history)
            save_checkpoint(args.out / 'training-state.pt', model, optimizer, step, rng, digest(manifest_path), best, history, total_tokens)
        if budget_reached:
            break
    selected = {'adapter': load_file(str(args.out / 'best-adapter.safetensors')),
                'new_rows': load_file(str(args.out / 'best-new-rows.safetensors'))['new_rows']}
    del teacher, optimizer
    hf = export_hf(args.out, args.parent, args.codec, model, selected)
    write_json(args.out / 'metrics.json', {'status': 'training_completed', 'steps': step,
        'processed_tokens': total_tokens, 'elapsed_seconds': time.perf_counter()-started,
        'best': json.loads((args.out / 'best-selection.json').read_text(encoding='utf-8')),
        'hf_hashes': {p.name: digest(p) for p in hf.iterdir() if p.is_file()},
        'product_status': 'unproven; independent generation, GGUF, quantization and real-IME tests required'})


if __name__ == '__main__':
    main()
