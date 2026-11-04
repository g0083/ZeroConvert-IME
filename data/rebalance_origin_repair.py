# SPDX-License-Identifier: Apache-2.0
"""Remove label-correlated input slots from an owned Origin pilot.

Complete QWERTY examples all receive ctx/raw/pending, independent of the answer.
Correct readings stay outside model input. Source document partitions and all
conversion/prediction examples are preserved. Cross-split raw prompts are
removed from training; contradictory training targets become abstain.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil

from origin_contract import digest, validate_row, write_json


def transform(row):
    if row['task'] != 'repair_qwerty':
        return row
    inputs = row['input']
    reference = row.get('reference_reading') or (row['target']['text'] if row['target']['action'] == 'emit' else inputs.get('surface'))
    return {**row, 'input': {'ctx': inputs.get('ctx', ''), 'raw': inputs['raw'], 'pending': inputs.get('pending', '')},
            'reference_reading': reference,
            'input_state_scope': 'raw-only observed keys; derived surface and cursor intentionally omitted for every label'}


def prompt_key(row):
    return hashlib.sha256(json.dumps([row['task'], row['input']], sort_keys=True, ensure_ascii=False).encode()).digest()


def target_key(row):
    return json.dumps(row['target'], sort_keys=True, ensure_ascii=False)


def iter_rows(path):
    with path.open(encoding='utf-8') as stream:
        for line in stream:
            yield json.loads(line)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    source_manifest = json.loads((args.source / 'manifest.json').read_text(encoding='utf-8'))
    for name, expected in source_manifest['output_hashes'].items():
        if digest(args.source / name) != expected:
            raise ValueError(f'Parent dataset changed: {name}')
    validation_prompts = set()
    for row in iter_rows(args.source / 'val.jsonl'):
        if row['task'] == 'repair_qwerty':
            validation_prompts.add(prompt_key(transform(row)))
    targets, conflicting = {}, set()
    for row in iter_rows(args.source / 'train.jsonl'):
        if row['task'] != 'repair_qwerty':
            continue
        row = transform(row)
        key = prompt_key(row)
        target = target_key(row)
        if key in targets and targets[key] != target:
            conflicting.add(key)
        targets[key] = target
    args.out.mkdir(parents=True)
    counts, signatures = Counter(), set()
    label_slots = Counter()
    for partition in ('train', 'val'):
        with (args.out / f'{partition}.jsonl').open('x', encoding='utf-8') as output:
            for original in iter_rows(args.source / f'{partition}.jsonl'):
                row = transform(original)
                if row['task'] == 'repair_qwerty':
                    key = prompt_key(row)
                    if partition == 'train' and key in validation_prompts:
                        counts['train_prompts_excluded_for_validation'] += 1
                        continue
                    if partition == 'train' and key in conflicting:
                        row = {**row, 'target': {'action': 'abstain', 'text': ''},
                               'category': 'ambiguous-observed-raw', 'original_target': row['target']}
                        counts['ambiguous_training_targets_to_abstain'] += 1
                signature = hashlib.sha256(json.dumps([row['task'], row['input'], row['target']], sort_keys=True,
                                                     ensure_ascii=False).encode()).digest()
                if signature in signatures:
                    counts[f'{partition}_exact_duplicates_removed'] += 1
                    continue
                signatures.add(signature)
                validate_row(row)
                if row['task'] == 'repair_qwerty':
                    label_slots[(partition, row['target']['action'], tuple(sorted(row['input'])))] += 1
                output.write(json.dumps(row, ensure_ascii=False) + '\n')
                counts[f'{partition}_{row["task"]}'] += 1
    shutil.copy2(args.source / 'sources.json', args.out / 'sources.json')
    shutil.copytree(args.source / 'source-evidence', args.out / 'source-evidence')
    write_json(args.out / 'input-slot-audit.json', [
        {'partition': partition, 'action': action, 'keys': list(keys), 'rows': count}
        for (partition, action, keys), count in sorted(label_slots.items())])
    write_json(args.out / 'manifest.json', {
        'schema': 'zerocon-origin-v1', 'operation': 'remove repair label-correlated surface/cursor slots',
        'parent': str(args.source), 'parent_manifest_sha256': digest(args.source / 'manifest.json'),
        'parent_output_hashes': source_manifest['output_hashes'], 'builder_sha256': digest(Path(__file__)),
        'counts': dict(counts), 'conflicting_training_prompts': len(conflicting),
        'output_hashes': {name: digest(args.out / name) for name in ('train.jsonl', 'val.jsonl', 'sources.json')},
        'partition_policy': 'same source documents as parent; only exclude training/duplicate rows; never move source groups',
        'limitations': ['Automatic labels and synthetic errors retained from parent',
                       'Raw-only input state baseline, not a real TSF surface simulation',
                       'Validation labels are preserved; ambiguous intended repairs need adjudication',
                       'No new source documents or sealed data imported'],
    })
    print(json.dumps(dict(counts), indent=2))


if __name__ == '__main__':
    main()
