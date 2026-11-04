# SPDX-License-Identifier: Apache-2.0
"""Left-padded evaluation with unchanged per-example budgets and optional scores.

Scores use generated tokens only. No reference answer enters model input or
score extraction. These are model features, not calibrated correctness odds.
"""
from collections import defaultdict
import math
import time


def generation_batches(prompts, max_context, batch_size, token_cap=96):
    if batch_size < 1 or token_cap < 1:
        raise ValueError('Positive batch size and generation cap required')
    allowances = [min(token_cap, max_context - len(prompt)) for prompt in prompts]
    if any(n < 1 for n in allowances):
        raise ValueError('No generation capacity left; truncation is forbidden')
    if batch_size == 1:
        return [(allowances[i], [i]) for i in range(len(prompts))]
    groups = defaultdict(list)
    for i, allowance in enumerate(allowances):
        groups[allowance].append(i)
    result = []
    for allowance in sorted(groups, reverse=True):
        ordered = sorted(groups[allowance], key=lambda i: (len(prompts[i]), i))
        for offset in range(0, len(ordered), batch_size):
            indices = ordered[offset:offset + batch_size]
            if max(len(prompts[i]) for i in indices) + allowance > max_context:
                raise ValueError('Padded physical context exceeds the original generation limit')
            result.append((allowance, indices))
    return result


def unpad_generated(tokens, pad_id, eos_id):
    if eos_id not in tokens:
        return tokens  # A model-generated PAD without EOS remains an invalid real token.
    end = len(tokens)
    while end and tokens[end - 1] == pad_id:
        end -= 1
    return tokens[:end]


def non_emission_ids(codec):
    controls = {i for i in range(codec.sp.get_piece_size()) if codec.sp.is_control(i)}
    controls.update((codec.sp.unk_id(), codec.pad_id, codec.bos_id, codec.eos_id))
    controls.update(codec.ids.values())
    return sorted(controls)


def generated_score_features(scores, generated_tensor, trimmed_tokens, actuals, codec, controls=None):
    import torch
    controls = non_emission_ids(codec) if controls is None else controls
    selected, emit_mass = [], None
    for step, score in enumerate(scores):
        log_probs = torch.log_softmax(score.float(), dim=-1)
        if torch.isnan(log_probs).any():
            raise ValueError('Non-finite generation score distribution')
        if step == 0:
            emit_mass = (1 - log_probs[:, controls].exp().sum(dim=-1)).clamp(0, 1).cpu().tolist()
        selected.append(log_probs.gather(1, generated_tensor[:, step:step + 1]).squeeze(1))
    token_logs = torch.stack(selected, dim=1).cpu().tolist() if selected else [[] for _ in actuals]
    features = []
    for i, tokens in enumerate(trimmed_tokens):
        logs = token_logs[i][:len(tokens)]
        if any(not math.isfinite(value) for value in logs):
            raise ValueError('Non-finite selected generated-token score')
        minimum = math.exp(min(logs)) if logs else 0.0
        mass = emit_mass[i] if emit_mass is not None else 0.0
        features.append({'first_step_emit_mass': mass,
                         'minimum_generated_token_probability': minimum,
                         'mean_generated_log_probability': sum(logs) / len(logs) if logs else None,
                         'generated_sequence_log_probability': sum(logs),
                         'scored_tokens_including_eos': len(logs),
                         'emission_score': min(mass, minimum) if actuals[i]['action'] == 'emit' else 0.0,
                         'calibrated_correctness_probability': False})
    return features


def generate_cases(model, codec, rows, device='cpu', batch_size=1, record_scores=False):
    import torch
    prompts = [codec.prompt(row) for row in rows]
    planned = generation_batches(prompts, model.config.max_position_embeddings, batch_size)
    controls = non_emission_ids(codec) if record_scores else None
    cases, done = [None] * len(rows), 0
    with torch.inference_mode():
        for allowance, indices in planned:
            width = max(len(prompts[i]) for i in indices)
            ids = torch.tensor([[codec.pad_id] * (width - len(prompts[i])) + prompts[i] for i in indices], device=device)
            mask = torch.tensor([[0] * (width - len(prompts[i])) + [1] * len(prompts[i]) for i in indices], device=device)
            if device == 'cuda':
                torch.cuda.synchronize()
            start = time.perf_counter()
            output = model.generate(input_ids=ids, attention_mask=mask, max_new_tokens=allowance,
                do_sample=False, use_cache=True, eos_token_id=codec.eos_id, pad_token_id=codec.pad_id,
                return_dict_in_generate=True, output_scores=record_scores)
            if device == 'cuda':
                torch.cuda.synchronize()
            elapsed = (time.perf_counter() - start) * 1000
            raw_generated = output.sequences[:, width:]
            tokens = [unpad_generated(row, codec.pad_id, codec.eos_id) for row in raw_generated.tolist()]
            actuals = [codec.decode_answer(row) for row in tokens]
            features = generated_score_features(output.scores, raw_generated, tokens, actuals, codec, controls) if record_scores else None
            for local, index in enumerate(indices):
                row, actual = rows[index], actuals[local]
                case = {'id': row['id'], 'document_id': row['document_id'], 'task': row['task'],
                        'category': row.get('category', 'unspecified'), 'input': row['input'],
                        'reference_reading': row.get('reference_reading'),
                        'expected': row['target'], 'actual': actual, 'exact': actual == row['target'],
                        'tokens': tokens[local], 'generation_allowance': allowance,
                        'generation_batch_size': len(indices), 'batch_wall_time_ms': elapsed,
                        'latency_ms': elapsed if batch_size == 1 else None}
                if features is not None:
                    case['generation_score_features'] = features[local]
                cases[index] = case
            previous, done = done, done + len(indices)
            if done // 32 != previous // 32 or done == len(rows):
                print(f'{done}/{len(rows)} complete', flush=True)
    return cases
