# SPDX-License-Identifier: Apache-2.0
"""Reuse corrector's canonical seeds and keyboard mutators in raw-key space.

Old mixed-kana corruptions do not retain original keystrokes. They are never
reverse-romanized and represented as observed keys. New synthetic keys retain
their generator/seed provenance, and are checked with the pinned offline table.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import random
import shutil

from build_origin_corpus import romanize
from build_v4_unified_dataset import rows, isolation_keys, benchmark_collision
from dataset_isolation import evaluation_keys, validate_rows, source_keys
from generate_typo_dataset import mutate_adjacent_key, mutate_missing_vowel, mutate_transposition
from keyboard_hybrid import RomanDecoder
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec, hira


def is_reading(value):
    return bool(value) and all('ぁ' <= c <= 'ゖ' or c in 'ーゝゞ' for c in value)


def generate(source_rows, partition, decoder, counts):
    seeds = {}
    for old in source_rows:
        reading = hira(old.get('target_reading', ''))
        if not is_reading(reading) or not 3 <= len(reading) <= 32:
            counts[f'{partition}_unverified_or_long_reading_excluded'] += 1
            continue
        prompt = old['prompt']
        context = prompt.split('\uee00', 1)[0].removeprefix('\uee02')
        surface = old['completion']
        if not surface or len(surface) > 40:
            continue
        seeds[(old['source_group'], context, reading, surface)] = old
    random.seed(20261029)
    for (group, ctx, reading, surface), original in sorted(seeds.items()):
        raw = romanize(reading)
        if not raw or len(raw) > 96 or not raw.isascii() or decoder.decode(raw) != reading:
            counts[f'{partition}_canonical_raw_roundtrip_excluded'] += 1
            continue
        counts[f'{partition}_eligible_seed_pairs'] += 1
        common = {'source_id': 'corrector-qwerty-replay', 'document_id': 'corrector-' + group,
            'source_text': surface, 'task': 'repair', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
            'quality': 'existing kana-only corrector target; newly generated synthetic raw keys; not real-user traces',
            'origin_corrector_group': group, 'origin_corrector_context': ctx,
            'origin_corrector_reading': reading, 'reference_reading': reading,
            'original_source': {k: original.get(k) for k in ('origin', 'origin_row', 'family', 'category')}}
        variants = [(raw, '', 'keep', '', 'clean')]
        for first, second in (('shi', 'si'), ('chi', 'ti'), ('tsu', 'tu'), ('sha', 'sya'), ('cho', 'tyo')):
            alternative = raw.replace(first, second, 1)
            if alternative != raw and decoder.decode(alternative) == reading:
                variants.append((alternative, '', 'keep', '', 'valid-alternate-spelling'))
        # Corrector lessons: preserve deletion/prefix states and unresolved input.
        for cut in range(len(raw)//2, 1, -1):
            prefix = raw[:cut]
            decoded = decoder.decode(prefix)
            if is_reading(decoded) and not (prefix.endswith('n') and not prefix.endswith('nn')):
                variants.append((prefix, '', 'keep', '', 'complete-prefix-during-edit'))
                break
        variants.append((raw + 'k', 'k', 'abstain', '', 'pending'))
        for name, mutate in (('keyboard_adjacent', mutate_adjacent_key),
                             ('keyboard_missing_vowel', mutate_missing_vowel),
                             ('keyboard_transposition', mutate_transposition)):
            for _ in range(4):
                noisy = mutate(raw)
                if noisy == raw:
                    continue
                decoded = decoder.decode(noisy)
                if decoded == reading:
                    variants.append((noisy, '', 'keep', '', 'equivalent-after-noise'))
                elif noisy.endswith('n') and not noisy.endswith('nn'):
                    counts[f'{partition}_ambiguous_terminal_n_excluded'] += 1
                elif decoded and decoded[-1].isascii() and decoded[-1].isalpha():
                    counts[f'{partition}_unresolved_terminal_excluded'] += 1
                else:
                    variants.append((noisy, '', 'emit', reading, name))
        seen = set()
        for index, (observed, pending, action, target, kind) in enumerate(variants):
            if (observed, pending, action, target) in seen:
                continue
            seen.add((observed, pending, action, target))
            reference = decoder.decode(observed) if action == 'keep' and kind == 'complete-prefix-during-edit' else reading
            yield {**common, 'id': f'corrector-{partition}-{group[:12]}-{digest_text(ctx+reading+surface)[:10]}-{index}',
                'input': {'ctx': ctx, 'raw': observed, 'pending': pending},
                'target': {'action': action, 'text': target}, 'category': kind,
                'reference_reading': reference, 'raw_provenance': 'new synthetic raw; legacy observed raw was unavailable'}


def digest_text(text):
    import hashlib
    return hashlib.sha256(text.encode()).hexdigest()


def observed(row):
    return json.dumps([row['task'], row['input']], ensure_ascii=False, sort_keys=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--parent', type=Path, required=True)
    p.add_argument('--corrector', type=Path, default=Path('artifacts/datasets/corrector-v2-20261027'))
    p.add_argument('--codec', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    parent = json.loads((args.parent/'manifest.json').read_text(encoding='utf-8'))
    for name, expected in parent['output_hashes'].items():
        if digest(args.parent/name) != expected:
            raise ValueError('Parent data changed')
    corrector_meta = json.loads((args.corrector/'manifest.json').read_text(encoding='utf-8'))
    old = {part: list(rows(args.corrector/f'{part}.jsonl')) for part in ('train', 'val')}
    for part in old:
        if digest(args.corrector/f'{part}.jsonl') != corrector_meta['outputs'][part]['sha256']:
            raise ValueError('Corrector source changed')
    reserved = evaluation_keys()
    blocked_groups = {r['source_group'] for part in old.values() for r in part if source_keys(r) & reserved}
    old = {part: [r for r in records if r['source_group'] not in blocked_groups] for part, records in old.items()}
    validate_rows(old['train'], old['val'], reserved)
    codec, decoder, counts = V4UnifiedCodec(args.codec), RomanDecoder(), Counter()
    counts['legacy_corrector_groups_touching_current_evaluation_excluded'] = len(blocked_groups)
    original = {part: list(rows(args.parent/f'{part}.jsonl')) for part in ('train', 'val')}
    added = {}
    for part in original:
        selected = []
        for row in generate(old[part], part, decoder, counts):
            if benchmark_collision(row, reserved):
                counts[f'{part}_benchmark_excluded'] += 1
                continue
            try:
                codec.example(row, 256)
            except ValueError as error:
                if str(error) != 'Length exceeds limit; truncation forbidden':
                    raise
                counts[f'{part}_length_excluded'] += 1
                continue
            selected.append(row)
        added[part] = selected
    # Original development cohort remains byte-identical and never moves into train.
    known_val = {observed(r): r['target'] for r in original['val']}
    conflicting = {r['document_id'] for r in added['val'] if observed(r) in known_val and known_val[observed(r)] != r['target']}
    added['val'] = [r for r in added['val'] if r['document_id'] not in conflicting]
    parts = {part: original[part] + added[part] for part in original}
    for part in parts:
        targets, conflicts = {}, set()
        for row in parts[part]:
            k, target = observed(row), json.dumps(row['target'], sort_keys=True)
            if k in targets and targets[k][0] != target:
                conflicts.add(row['document_id'])
                conflicts.add(targets[k][1])
            targets[k] = (target, row['document_id'])
        if part == 'val':
            conflicts -= {r['document_id'] for r in original['val']}
        parts[part] = [r for r in parts[part] if r['document_id'] not in conflicts]
        counts[f'{part}_conflicting_documents_excluded'] = len(conflicts)
    val_keys = set().union(*(isolation_keys(r) for r in parts['val']))
    blocked_docs = {r['document_id'] for r in parts['train'] if isolation_keys(r) & val_keys}
    parts['train'] = [r for r in parts['train'] if r['document_id'] not in blocked_docs]
    counts['train_documents_touching_validation_excluded'] = len(blocked_docs)
    args.out.mkdir(parents=True)
    for part, selected in parts.items():
        with (args.out/f'{part}.jsonl').open('x', encoding='utf-8') as stream:
            for row in selected:
                stream.write(json.dumps(row, ensure_ascii=False)+'\n')
                domain = 'corrector' if row['source_id'] == 'corrector-qwerty-replay' else 'parent'
                counts[f'{part}_{domain}_{row["task"]}_{row["target"]["action"]}'] += 1
        counts[f'{part}_unique_sources'] = len({r['source_text'] for r in selected})
        counts[f'{part}_unique_documents'] = len({r['document_id'] for r in selected})
    if list(rows(args.out/'val.jsonl'))[:len(original['val'])] != original['val']:
        raise ValueError('Original development cohort changed')
    sources = json.loads((args.parent/'sources.json').read_text(encoding='utf-8'))
    sources['corrector-qwerty-replay'] = {
        'origin': str(args.corrector), 'approved_uses': ['local research training'],
        'approval': 'User explicitly requested reuse of corrector data and findings in this chat',
        'distribution_status': 'source terms review pending; local research artifact, not publicly cleared',
        'data': 'verified kana-only seed targets; original raw keystrokes unavailable; new synthetic raw retained',
        'negative_findings': 'dictionary-heavy diversification and direct reading output alone did not improve earlier ablation',
    }
    write_json(args.out/'sources.json', sources)
    shutil.copytree(args.parent/'source-evidence', args.out/'source-evidence')
    for path in (args.corrector/'manifest.json', Path('reports/typo-assist/corrector-ablation-20261027.md'),
                 Path('reports/typo-assist/corrector-size-comparison-20261027.md')):
        shutil.copy2(path, args.out/'source-evidence'/('corrector-'+path.name))
    write_json(args.out/'manifest.json', {
        'schema': 'zerocon-v4-unified-dataset-v1', 'arguments': {'max_length': 256},
        'parent': str(args.parent), 'parent_manifest_sha256': digest(args.parent/'manifest.json'),
        'original_validation_sha256': digest(args.parent/'val.jsonl'),
        'corrector_manifest_sha256': digest(args.corrector/'manifest.json'),
        'corrector_hashes': {part: digest(args.corrector/f'{part}.jsonl') for part in old},
        'codec_sha256': digest(args.codec/'v4-unified-codec.json'), 'counts': dict(counts),
        'code_hashes': {path: digest(path) for path in ('data/add_v4_corrector_replay.py', 'data/generate_typo_dataset.py',
            'data/build_origin_corpus.py', 'benchmarks/research/keyboard_hybrid.py')},
        'output_hashes': {name: digest(args.out/name) for name in ('train.jsonl', 'val.jsonl', 'sources.json')},
        'publication_status': 'local research; corrector upstream data terms not newly cleared by this operation',
        'limits': ['synthetic keys, no actual user keystrokes or flick events', 'original development cohort retained; not sealed data']})
    print(json.dumps(dict(counts), indent=2), flush=True)


if __name__ == '__main__':
    main()
