# SPDX-License-Identifier: Apache-2.0
"""Select existing approved source documents, preserving their partitions.

No vocabulary training, external generation, evaluation examples or sealed text.
Automatic readings and synthetic repairs remain explicitly research labels.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import shutil

from dataset_isolation import EVALUATION_FILES, evaluation_keys, normalize
from origin_contract import digest, write_json
from v4_unified_contract import hira, V4UnifiedCodec, legacy_example
from transformers import AutoTokenizer


def rows(path):
    with Path(path).open(encoding='utf-8-sig') as stream:
        for line in stream:
            if line.strip():
                yield json.loads(line)


def isolation_keys(row):
    inp = row['input']
    keys = {'doc:' + row['document_id'], 'source:' + normalize(row['source_text'])}
    task = row['task']
    if task == 'convert':
        keys |= {'convert:' + normalize(inp['reading']), 'surface:' + normalize(row['target']['text'])}
    if task == 'repair':
        if 'raw' in inp:
            keys.add('repair:' + inp['raw'] + '\0' + inp['pending'] + '\0' + inp['ctx'])
        else:
            keys.add('repair-observed:' + normalize(inp['reading']) + '\0' + inp['pending'] + '\0' + inp['ctx'])
    if task == 'predict':
        keys.add('predict:' + inp['ctx'] + '\0' + inp['surface'] + '\0' + inp['pending'])
    return keys


def benchmark_collision(row, reserved):
    texts = [row['source_text'], row.get('reference_reading', '')]
    texts += [v for v in row['input'].values() if isinstance(v, str)]
    if row['task'] != 'predict':
        texts.append(row['target']['text'])
    return any(normalize(t) in reserved for t in texts if t)


def adapt(row):
    if row['task'] not in ('convert', 'repair_qwerty', 'predict'):
        raise ValueError('Unexpected task')
    inputs = dict(row['input'])
    if row['task'] == 'predict':
        inputs['pending'] = ''
    return {**row, 'task': 'repair' if row['task'] == 'repair_qwerty' else row['task'],
            'input_method': 'romaji', 'layout': 'JP-QWERTY', 'input': inputs,
            'quality': 'automatic research labels; not real-user typing'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=Path('artifacts/datasets/origin-magpie-full-curated-20261029-r1'))
    parser.add_argument('--source-audit', type=Path, default=Path('artifacts/results/origin-magpie-full-curated-20261029-r1-audit.json'))
    parser.add_argument('--parent', type=Path, default=Path('artifacts/checkpoints/standard-v4-nano-transfer-merged'))
    parser.add_argument('--anchors', type=Path, default=Path('artifacts/datasets/standard-v3-knowledge'))
    parser.add_argument('--codec', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--train-sources', type=int, default=20000)
    parser.add_argument('--val-sources', type=int, default=1200)
    parser.add_argument('--max-length', type=int, default=256)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    manifest_path = args.source / 'manifest.json'
    source_manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    source_audit = json.loads(args.source_audit.read_text(encoding='utf-8'))
    if source_audit['status'] != 'PASS' or source_audit['manifest_sha256'] != digest(manifest_path):
        raise ValueError('Source audit is not bound to selected source')
    for name, expected in source_manifest['output_hashes'].items():
        if digest(args.source / name) != expected:
            raise ValueError('Source changed: ' + name)
    codec = V4UnifiedCodec(args.codec)
    old_tokenizer = AutoTokenizer.from_pretrained(args.parent, local_files_only=True)
    reserved = evaluation_keys()
    splits, counts = {}, Counter()
    for split, limit in (('train', args.train_sources), ('val', args.val_sources)):
        chosen, selected = set(), []
        for original in rows(args.source / f'{split}.jsonl'):
            identity = (original['document_id'], original['source_text'])
            if identity not in chosen:
                if len(chosen) >= limit:
                    break
                if not 8 <= len(original['source_text']) <= 70:
                    continue
                chosen.add(identity)
            row = adapt(original)
            if benchmark_collision(row, reserved):
                counts[f'{split}_benchmark_excluded'] += 1
                continue
            try:
                codec.example(row, args.max_length)
            except ValueError as error:
                if str(error) != 'Length exceeds limit; truncation forbidden':
                    raise
                counts[f'{split}_length_excluded'] += 1
                continue
            selected.append(row)
        # Retain v4's tiny, approved source-separated knowledge anchors.
        for i, anchor in enumerate(rows(args.anchors / f'{split}.jsonl')):
            row = {'id': f'anchor-{split}-{i}', 'source_id': 'v4-authored-knowledge',
                   'document_id': 'anchor-' + anchor['source_group'],
                   'source_text': anchor['completion'], 'task': 'convert',
                   'input_method': 'romaji', 'layout': 'JP-QWERTY',
                   'input': {'ctx': anchor['left_context'], 'reading': hira(anchor['reading'])},
                   'target': {'action': 'emit', 'text': anchor['completion']},
                   'category': 'v4-anchor', 'quality': 'existing approved dictionary/collocation data'}
            if not benchmark_collision(row, reserved):
                codec.example(row, args.max_length)
                selected.append(row)
        splits[split] = selected
    # A source sentence is the exclusion unit, so all its task siblings move together.
    val_keys = set().union(*(isolation_keys(r) for r in splits['val']))
    blocked = {r['source_text'] for r in splits['train'] if isolation_keys(r) & val_keys}
    splits['train'] = [r for r in splits['train'] if r['source_text'] not in blocked]
    counts['training_source_sentences_excluded_for_validation'] = len(blocked)
    for split, selected in splits.items():
        observations, conflicts = {}, set()
        for row in selected:
            key = json.dumps([row['task'], row['input']], ensure_ascii=False, sort_keys=True)
            target = json.dumps(row['target'], ensure_ascii=False, sort_keys=True)
            if key in observations and observations[key][0] != target:
                conflicts.add(row['source_text'])
                conflicts.add(observations[key][1])
            observations[key] = (target, row['source_text'])
        splits[split] = [r for r in selected if r['source_text'] not in conflicts]
        counts[f'{split}_conflicting_sources_excluded'] = len(conflicts)
    args.out.mkdir(parents=True)
    for split, selected in splits.items():
        with (args.out / f'{split}.jsonl').open('x', encoding='utf-8') as stream:
            for row in selected:
                stream.write(json.dumps(row, ensure_ascii=False) + '\n')
                counts[f'{split}_{row["task"]}_{row["target"]["action"]}'] += 1
        counts[f'{split}_unique_sources'] = len({r['source_text'] for r in selected})
        counts[f'{split}_unique_documents'] = len({r['document_id'] for r in selected})
        # Measure legacy representability separately; don't silently drop new codec rows.
        counts[f'{split}_legacy_convert_rows'] = sum(
            len(legacy_example(r, old_tokenizer)['input_ids']) <= args.max_length
            for r in selected if r['task'] == 'convert')
    shutil.copy2(args.source / 'sources.json', args.out / 'sources.json')
    sources = json.loads((args.out / 'sources.json').read_text(encoding='utf-8'))
    sources['v4-authored-knowledge'] = {'license': 'Apache-2.0',
        'evidence': 'artifacts/results/origin-legacy817-provenance-review-20261029.json',
        'uses': 'existing approved anchors; this run does not reassess legal conclusions'}
    write_json(args.out / 'sources.json', sources)
    shutil.copytree(args.source / 'source-evidence', args.out / 'source-evidence')
    shutil.copy2('artifacts/results/origin-legacy817-provenance-review-20261029.json', args.out / 'source-evidence/anchor-review.json')
    write_json(args.out / 'manifest.json', {
        'schema': 'zerocon-v4-unified-dataset-v1', 'arguments': {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
        'source_manifest_sha256': digest(manifest_path), 'source_audit_sha256': digest(args.source_audit),
        'source_output_hashes': source_manifest['output_hashes'],
        'anchor_hashes': {name: digest(args.anchors / name) for name in ('train.jsonl', 'val.jsonl')},
        'codec_sha256': digest(args.codec / 'v4-unified-codec.json'), 'builder_sha256': digest(__file__),
        'evaluation_hashes': {str(p): digest(p) for p in EVALUATION_FILES},
        'counts': dict(counts), 'partition_policy': 'retain existing document partitions; exclude train source families touching validation',
        'output_hashes': {name: digest(args.out / name) for name in ('train.jsonl', 'val.jsonl', 'sources.json')},
        'limitations': ['Automatic readings, synthetic noise; no real-IME success claim',
                       'Validation is development data; no sealed examples selected',
                       'Source order subset; no claim of uniform corpus sampling']})
    print(json.dumps(dict(counts), indent=2), flush=True)


if __name__ == '__main__':
    main()
