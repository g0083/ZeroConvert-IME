# SPDX-License-Identifier: Apache-2.0
"""Add short source-aligned conversion spans to an audited Origin corpus.

All original rows and validation bytes are preserved. New training examples
use only a parent training sentence and its preceding text, never future text,
validation/sealed sentences, model outputs, or hand-picked benchmark answers.
"""
import argparse
from collections import Counter
import hashlib
import importlib.metadata
import json
from pathlib import Path
import random
import shutil

from build_origin_corpus import KANJI, orthographic_tokens
from dataset_isolation import evaluation_keys, normalize
from origin_contract import digest, validate_row, write_json
from rebalance_origin_repair import iter_rows, prompt_key, target_key


def spans(row, tokens, seed, maximum=2):
    if row['task'] != 'convert' or row.get('category') != 'full':
        return []
    sentence = row['source_text']
    if set(row['input']) - {'ctx', 'reading'}:
        raise ValueError('Span augmentation does not discard protected or other input state')
    if ''.join(s for s, _ in tokens) != sentence or row['target'] != {'action': 'emit', 'text': sentence}:
        raise ValueError('Span parent must be a full, exactly aligned conversion')
    if ''.join(r for _, r in tokens) != row['input']['reading']:
        raise ValueError('Parent orthographic reading differs from current labeler')
    offsets, cursor = [], 0
    for surface, _ in tokens:
        offsets.append(cursor)
        cursor += len(surface)
    groups = [[], []]
    for start in range(len(tokens)):
        for stop in range(start + 1, min(len(tokens), start + 3) + 1):
            surface = ''.join(s for s, _ in tokens[start:stop])
            reading = ''.join(r for _, r in tokens[start:stop])
            if not (KANJI.search(surface) and 1 <= len(surface) <= 16 and 2 <= len(reading) <= 32):
                continue
            if not surface[0].isalnum() or not surface[-1].isalnum() or surface == sentence:
                continue
            groups[stop - start > 1].append((start, stop, surface, reading))
    rng = random.Random(hashlib.sha256(f'{seed}:{row["id"]}'.encode()).digest())
    for group in groups:
        rng.shuffle(group)
    selected = []
    while len(selected) < maximum and any(groups):
        for group in groups:
            if group and len(selected) < maximum:
                selected.append(group.pop())
    result = []
    for start, stop, surface, reading in selected:
        begin, end = offsets[start], offsets[stop - 1] + len(tokens[stop - 1][0])
        result.append({**row, 'id': f'{row["id"]}-span-{start}-{stop}',
                       'input': {'ctx': (row['input'].get('ctx', '') + sentence[:begin])[-48:], 'reading': reading},
                       'target': {'action': 'emit', 'text': surface}, 'category': 'morph-span',
                       'span_parent_id': row['id'], 'source_span': {'start': begin, 'end': end,
                            'token_start': start, 'token_end': stop},
                       'span_policy': 'one single-word and one short multiword span where available; preceding context only'})
    return result


def signature(row):
    return hashlib.sha256(prompt_key(row) + target_key(row).encode()).digest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--seed', type=int, default=20261029)
    parser.add_argument('--max-spans', type=int, choices=(1, 2), default=2)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    parent = json.loads((args.source / 'manifest.json').read_text(encoding='utf-8'))
    audit = json.loads(args.audit.read_text(encoding='utf-8'))
    if audit.get('status') != 'PASS' or audit['manifest_sha256'] != digest(args.source / 'manifest.json'):
        raise ValueError('Matching parent isolation audit required')
    for name, expected in parent['output_hashes'].items():
        if digest(args.source / name) != expected:
            raise ValueError('Audited parent changed')
    with (args.source / 'train.jsonl').open('rb') as stream:
        stream.seek(-1, 2)
        if stream.read(1) != b'\n':
            raise ValueError('Appending spans requires a newline-terminated parent JSONL')
    reserved = evaluation_keys()
    validation_prompts = {prompt_key(row) for row in iter_rows(args.source / 'val.jsonl') if row['task'] == 'convert'}
    seen, counts = set(), Counter()
    for row in iter_rows(args.source / 'train.jsonl'):
        counts[f'parent_train_{row["task"]}'] += 1
        if row['task'] == 'convert':
            seen.add(signature(row))
    import fugashi
    import unidic_lite
    tagger = fugashi.Tagger()
    args.out.mkdir(parents=True)
    for name in ('train.jsonl', 'val.jsonl', 'sources.json'):
        shutil.copy2(args.source / name, args.out / name)
    shutil.copytree(args.source / 'source-evidence', args.out / 'source-evidence')
    shutil.copy2(Path(__file__), args.out / 'source-evidence/span-builder.py')
    with (args.out / 'train.jsonl').open('a', encoding='utf-8') as output:
        for row in iter_rows(args.source / 'train.jsonl'):
            if row['task'] != 'convert' or row.get('category') != 'full':
                continue
            counts['parent_full_sentences_visited'] += 1
            tokens = orthographic_tokens(row['source_text'], tagger)
            for extra in spans(row, tokens, args.seed, args.max_spans):
                if prompt_key(extra) in validation_prompts:
                    counts['new_span_validation_prompt_excluded'] += 1
                    continue
                texts = [extra['target']['text'], *extra['input'].values()]
                if any(normalize(s) in reserved for s in texts if s):
                    counts['new_span_development_key_excluded'] += 1
                    continue
                key = signature(extra)
                if key in seen:
                    counts['new_span_exact_duplicate_excluded'] += 1
                    continue
                seen.add(key)
                validate_row(extra)
                output.write(json.dumps(extra, ensure_ascii=False) + '\n')
                counts['new_training_spans'] += 1
            if counts['parent_full_sentences_visited'] % 10000 == 0:
                print(json.dumps(dict(counts)), flush=True)
    if digest(args.out / 'val.jsonl') != parent['output_hashes']['val.jsonl']:
        raise ValueError('Validation must remain byte-identical')
    write_json(args.out / 'manifest.json', {
        'schema': 'zerocon-origin-v1', 'operation': 'source-aligned training conversion span augmentation',
        'parent': str(args.source), 'parent_manifest_sha256': digest(args.source / 'manifest.json'),
        'parent_output_hashes': parent['output_hashes'], 'parent_isolation_audit_sha256': digest(args.audit),
        'arguments': {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
        'counts': dict(counts), 'builder_sha256': digest(Path(__file__)),
        'labeler': {'fugashi': importlib.metadata.version('fugashi'), 'unidic-lite': importlib.metadata.version('unidic-lite'),
                    'dictionary_sha256': digest(Path(unidic_lite.DICDIR) / 'sys.dic'), 'license': 'BSD-3-Clause'},
        'output_hashes': {name: digest(args.out / name) for name in ('train.jsonl', 'val.jsonl', 'sources.json')},
        'partition_policy': 'Original training rows and validation preserved byte-for-byte; new spans from training only',
        'limits': ['Automatic orthographic readings, not human certification',
                   'No new source documents, teacher output or sealed text',
                   'Shared lexical words across source documents permitted; exact development keys excluded',
                   'Additional data is an experiment, not evidence of a quality improvement']})
    print(json.dumps(dict(counts), indent=2), flush=True)


if __name__ == '__main__':
    main()
