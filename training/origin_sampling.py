# SPDX-License-Identifier: Apache-2.0
"""Explicit task/action sampling without label-correlated model input slots."""
from array import array
from collections import Counter
import math
import struct

from origin_token_cache import INDEX

ACTION_NAMES = ('emit', 'keep', 'abstain')


def cache_actions(cache, codec):
    actions = array('B')
    mapping = {codec.ids['<keep>']: 1, codec.ids['<abstain>']: 2}
    for offset, length, prompt, task in INDEX.iter_unpack(cache.index):
        if not 0 < prompt < length or offset + length * 2 > len(cache.tokens):
            raise ValueError('Invalid cached target boundary')
        first = struct.unpack_from('<H', cache.tokens, offset + prompt * 2)[0]
        action = mapping.get(first, 0)
        if action == 0 and (codec.sp.is_control(first) or first == codec.sp.unk_id()):
            raise ValueError('Unexpected control/unknown token at target start')
        actions.append(action)
    return actions


def cache_context_states(cache, codec):
    """Read observed ctx emptiness from fixed Origin field markers, not labels."""
    states = array('b')
    for offset, length, prompt, task in INDEX.iter_unpack(cache.index):
        if prompt < 4 or offset + length * 2 > len(cache.tokens):
            raise ValueError('Invalid cached prompt boundary')
        prefix = struct.unpack_from('<4H', cache.tokens, offset)
        expected = (codec.bos_id, codec.ids[f'<task_{cache.manifest["task_names"][task]}>'], codec.ids['<ctx>'])
        if prefix[:3] != expected:
            raise ValueError('Context sampling requires the recorded fixed Origin field order')
        if prefix[3] == codec.ids['<reading>']:
            states.append(0)
        elif not codec.sp.is_control(prefix[3]):
            states.append(1)
        else:
            raise ValueError('Unexpected control token inside observed context')
    return states


def sampling_weights(tasks, task_names, task_weights, actions, action_weights=None,
                     context_states=None, balance_repair_context=False):
    if len(tasks) != len(actions):
        raise ValueError('Task/action arrays differ')
    action_weights = action_weights or {}
    counts = Counter(tasks)
    actual = {task_names[t] for t in counts}
    valid = lambda values: (bool(values) and all(isinstance(v, (int, float)) and math.isfinite(v) and v >= 0 for v in values.values())
                            and sum(values.values()) > 0)
    if set(task_weights) - actual or not valid(task_weights) or set(action_weights) - actual:
        raise ValueError('Invalid task sampling weights')
    groups = Counter(zip(tasks, actions))
    if balance_repair_context and (context_states is None or len(context_states) != len(tasks)):
        raise ValueError('Context balancing requires one observed state per cached row')
    state_counts = Counter(zip(tasks, actions, context_states)) if balance_repair_context else {}
    repair_id = task_names.index('repair_qwerty') if 'repair_qwerty' in task_names else None
    if balance_repair_context:
        for action in (0, 1):
            if groups[(repair_id, action)] and any(not state_counts[(repair_id, action, state)] for state in (0, 1)):
                raise ValueError('Context balancing cannot invent missing keep/emit states')
    normalized = {}
    for task, weights in action_weights.items():
        if set(weights) - set(ACTION_NAMES) or not valid(weights):
            raise ValueError('Invalid within-task action weights')
        task_id = task_names.index(task)
        for name, weight in weights.items():
            action_id = ACTION_NAMES.index(name)
            if weight > 0 and not groups[(task_id, action_id)]:
                raise ValueError('Requested action has no training examples')
        normalized[task] = {name: weight / sum(weights.values()) for name, weight in weights.items()}
    cumulative, total = array('d'), 0.0
    for index, (task_id, action_id) in enumerate(zip(tasks, actions)):
        task = task_names[task_id]
        if balance_repair_context and task_id == repair_id and action_id in (0, 1):
            mass = (normalized[task].get(ACTION_NAMES[action_id], 0.0) if task in normalized else
                    groups[(task_id, action_id)] / counts[task_id])
            increment = task_weights.get(task, 0.0) * mass * .5 / state_counts[(task_id, action_id, context_states[index])]
        elif task in normalized:
            increment = task_weights.get(task, 0.0) * normalized[task].get(ACTION_NAMES[action_id], 0.0) / groups[(task_id, action_id)]
        else:
            # Preserve the baseline arithmetic/order exactly when no action weights are specified.
            increment = task_weights.get(task, 0.0) / counts[task_id]
        total += increment
        cumulative.append(total)
    populations = {task_names[t]: {ACTION_NAMES[a]: n for (task, a), n in groups.items() if task == t} for t in counts}
    return cumulative, populations


def verify_augmentation_binding(base, cache_meta, lineage, isolation, dataset_manifest, parent_cache_meta):
    """Verify immutable audit identities; caller also hashes all source/cache files."""
    if base['tokenizer_sha256'] != cache_meta['tokenizer_sha256']:
        raise ValueError('Warm-start vocabulary differs')
    if base['dataset_hashes'] == cache_meta['dataset_hashes']:
        return {'kind': 'exact-same-dataset'}
    if not lineage or not isolation or not parent_cache_meta:
        raise ValueError('A changed dataset requires completed span lineage and isolation audits')
    transfer = cache_meta.get('tokenizer_transfer') or {}
    if (lineage.get('status') != 'PASS' or isolation.get('status') != 'PASS' or
            dataset_manifest.get('operation') != 'source-aligned training conversion span augmentation' or
            cache_meta['dataset_manifest_sha256'] != lineage['dataset_manifest_sha256'] or
            cache_meta['dataset_manifest_sha256'] != isolation['manifest_sha256'] or
            lineage['output_hashes'] != cache_meta['dataset_hashes'] or
            lineage['parent_output_hashes'] != base['dataset_hashes'] or
            parent_cache_meta['dataset_hashes'] != base['dataset_hashes'] or
            parent_cache_meta['dataset_manifest_sha256'] != lineage['parent_manifest_sha256'] or
            base.get('token_cache_manifest_sha256') != transfer.get('parent_cache_manifest_sha256')):
        raise ValueError('Augmentation is not bound to the selected parent weights/cache')
    for name in ('val.jsonl', 'sources.json'):
        if base['dataset_hashes'][name] != cache_meta['dataset_hashes'][name]:
            raise ValueError('Augmentation changed validation or license records')
    return {'kind': 'verified-training-span-augmentation', 'verified_spans': lineage['verified_spans'],
            'parent_manifest_sha256': lineage['parent_manifest_sha256']}
