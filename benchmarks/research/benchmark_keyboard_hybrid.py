"""Reproducible development/held-out evaluation of keyboard hybrid candidates.

External CSV is used ONLY for clean romaji snippets: its alleged noisy columns
are audited, not trusted. Injected errors are synthetic, not user frequency data.
"""
import argparse
import csv
import hashlib
import json
import random
import re
import time
from collections import Counter
from pathlib import Path

from dataset_isolation import load_jsonl, normalize, sha256, source_keys
from keyboard_hybrid import DICTIONARY, TABLE, NEIGHBORS, Lexicon, NeuralScorer, RomanDecoder, generate, is_kana


def write_rows(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf-8') as out:
        for row in rows:
            out.write(json.dumps(row, ensure_ascii=False) + '\n')


def build(args):
    if args.output.exists():
        raise FileExistsError(args.output)
    decoder = RomanDecoder()
    training = [Path('artifacts/datasets/corrector-v2-20261027') / f'{s}.jsonl' for s in ('train', 'val')]
    training += sorted(Path('artifacts/datasets/corrector-ablation-20261027').glob('*/*.jsonl'))
    reserved = set()
    for path in training:
        for row in load_jsonl(path):
            reserved.update(source_keys(row))
    with args.csv.open(encoding='utf-8-sig', newline='') as source:
        external = list(csv.DictReader(source))
    unique = {}
    for row in external:
        # Collapse duplicate source sentences before taking any window.
        key = normalize(row['org_sentences'])
        unique.setdefault(key, row)
    audit = Counter()
    chosen = []
    for identity, row in sorted(unique.items()):
        group = hashlib.sha256(identity.encode()).hexdigest()
        rng = random.Random(group)
        words = row['post_romanji'].split()
        windows = []
        for i in range(len(words)):
            keys = ''
            for word in words[i:i+5]:
                if not re.fullmatch('[a-z]+', word):
                    break
                keys += word
                if len(keys) > 28:
                    break
                if 10 <= len(keys) <= 28 and ('a' in keys or 's' in keys):
                    reading = decoder.decode(keys)
                    if is_kana(reading) and normalize(reading) not in reserved:
                        windows.append((keys, reading))
        if not windows:
            audit['no_eligible_window'] += 1
            continue
        keys, reading = rng.choice(sorted(set(windows)))
        chosen.append({'group': group, 'keys': keys, 'expected_reading': reading,
                       'source_sentence': row['org_sentences']})
    # Shared clean/noisy readings connect sources into one split; ambiguous
    # observed strings stay together even if two sentences intend different words.
    groups = []
    owners, parents = {}, list(range(len(chosen)))
    def find(i):
        while parents[i] != i:
            parents[i] = parents[parents[i]]
            i = parents[i]
        return i
    for index, source in enumerate(chosen):
        rng = random.Random(source['group'] + ':errors')
        keys = source['keys']
        edits = {}
        for i, old in enumerate(keys):
            for new in NEIGHBORS[old]:
                noisy = keys[:i]+new+keys[i+1:]
                if decoder.decode(noisy) != source['expected_reading']:
                    kind = 'a_s' if {old, new} == {'a', 's'} else 'other_adjacent'
                    edits.setdefault(kind, []).append((noisy, i, old, new))
        if not all(k in edits for k in ('a_s', 'other_adjacent')):
            audit['no_a_s_change'] += 1
            groups.append([])
            continue
        examples = [{**source, 'kind': 'clean', 'observed': keys, 'edit': None}]
        for kind in ('a_s', 'other_adjacent'):
            noisy, i, old, new = rng.choice(edits[kind])
            examples.append({**source, 'kind': kind, 'observed': noisy, 'edit': [i, old, new]})
        if any(normalize(decoder.decode(r['observed'])) in reserved for r in examples):
            audit['noisy_reading_overlaps_training'] += 1
            groups.append([])
            continue
        groups.append(examples)
        for key in {decoder.decode(r['observed']) for r in examples} | {source['expected_reading']}:
            if key in owners:
                parents[find(index)] = find(owners[key])
            else:
                owners[key] = index
    components = {}
    for i, group in enumerate(groups):
        if group:
            components.setdefault(find(i), []).extend(group)
    split_rows = {s: [] for s in ('dev', 'test', 'reserve')}
    for rows in components.values():
        component = hashlib.sha256('\n'.join(sorted({r['group'] for r in rows})).encode()).hexdigest()
        bucket = int(component[:8], 16) / 2**32
        split = 'dev' if bucket < .25 else 'test' if bucket < .65 else 'reserve'
        for row in rows:
            split_rows[split].append({**row, 'split_group': component,
                                     'id': row['group'][:16] + ':' + row['kind']})
    # Strictly assert connected identities and observed/target readings disjoint.
    keys_by_split = {s: {normalize(v) for r in rs for v in (r['expected_reading'], decoder.decode(r['observed']))}
                     for s, rs in split_rows.items()}
    overlaps = {a+'_'+b: len(keys_by_split[a] & keys_by_split[b])
                for a, b in [('dev', 'test'), ('dev', 'reserve'), ('test', 'reserve')]}
    assert not any(overlaps.values())
    args.output.mkdir(parents=True)
    for split, rows in split_rows.items():
        write_rows(args.output / f'{split}.jsonl', sorted(rows, key=lambda r: r['id']))
    manifest = {'source': {'csv': str(args.csv), 'sha256': sha256(args.csv)},
                'table_sha256': sha256(TABLE), 'dictionary_sha256': sha256(DICTIONARY),
                'training_exclusions': {str(p): sha256(p) for p in training},
                'external_audit': {'rows': len(external), 'pseudo_equals_pre': sum(r['pesedo_romanji'] == r['pre_romanji'] for r in external)},
                'notes': ['Synthetic single adjacent substitution; no empirical error frequency claimed.',
                          'Targets are decoded source romaji, not manually verified original-surface conversions.',
                          'No context, one natural romaji word-window per source sentence.',
                          'Base-model pretraining overlap is unknown; local fine-tuning target overlaps excluded.'],
                'exclusions': dict(audit), 'split_reading_overlaps': overlaps,
                'splits': {s: {'rows': len(rs), 'groups': len({r['split_group'] for r in rs}),
                               'kinds': dict(Counter(r['kind'] for r in rs)), 'sha256': sha256(args.output / f'{s}.jsonl')}
                           for s, rs in split_rows.items()}}
    (args.output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(manifest['splits']), flush=True)


def prepare(args):
    decoder, lexicon = RomanDecoder(), Lexicon()
    rows = load_jsonl(args.cases)
    if args.limit:
        rows = rows[:args.limit]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as out:
        for i, row in enumerate(rows):
            started = time.perf_counter()
            candidates, readings = generate(row['observed'], decoder, lexicon, args.readings, args.beam)
            elapsed = (time.perf_counter()-started)*1000
            record = {**row, 'observed_reading': decoder.decode(row['observed']), 'candidates': candidates,
                      'generation_ms': elapsed, 'unpruned_readings': len(readings),
                      'oracle_dictionary_reading_rank': readings.index(row['expected_reading'])+1 if row['expected_reading'] in readings else None}
            out.write(json.dumps(record, ensure_ascii=False)+'\n')
            if i % 50 == 0:
                print(f'candidates {i}/{len(rows)}', flush=True)
    print('written', args.output, flush=True)
    signature = {'dictionary_sha256': sha256(DICTIONARY), 'table_sha256': sha256(TABLE),
                 'reading_limit': args.readings, 'surface_beam': args.beam,
                 'generator': 'one-adjacent-unigram-v1', 'decoder': 'pinned-table-simplified-v1'}
    metadata = {'generation': signature, 'cases_sha256': sha256(args.cases),
                'limit': args.limit, 'candidate_sha256': sha256(args.output)}
    args.output.with_suffix('.metadata.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')


def score(args):
    candidate_meta = json.loads(args.candidates.with_suffix('.metadata.json').read_text(encoding='utf-8'))
    if candidate_meta['candidate_sha256'] != sha256(args.candidates):
        raise ValueError('Candidate file hash mismatch')
    scorer = NeuralScorer(str(args.model), device=args.device, batch_size=args.batch)
    rows = load_jsonl(args.candidates)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open('x', encoding='utf-8') as out:
        for i, row in enumerate(rows):
            started = time.perf_counter()
            scores = scorer.score(row['observed_reading'], row['candidates'], args.mode)
            elapsed = (time.perf_counter()-started)*1000
            row['candidates'] = [c | s for c, s in zip(row['candidates'], scores)]
            row['score_ms'] = elapsed
            out.write(json.dumps(row, ensure_ascii=False)+'\n')
            if i % 25 == 0:
                out.flush()
                print(f'scored {i}/{len(rows)}: {elapsed:.1f}ms', flush=True)
    meta = {'model': str(args.model), 'device': args.device, 'batch': args.batch, 'mode': args.mode,
            'candidate_file': str(args.candidates), 'candidate_sha256': sha256(args.candidates),
            'generation': candidate_meta['generation'],
            'model_files': {p.name: sha256(p) for p in args.model.iterdir() if p.suffix in ('.safetensors', '.json', '.txt')},
            'result_sha256': sha256(args.output)}
    args.output.with_suffix('.metadata.json').write_text(json.dumps(meta, indent=2), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('build')
    p.add_argument('--csv', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.set_defaults(run=build)
    p = sub.add_parser('prepare')
    p.add_argument('--cases', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--limit', type=int)
    p.add_argument('--readings', type=int, default=24)
    p.add_argument('--beam', type=int, default=3)
    p.set_defaults(run=prepare)
    p = sub.add_parser('score')
    p.add_argument('--candidates', type=Path, required=True)
    p.add_argument('--model', type=Path, required=True)
    p.add_argument('--device', default='cuda')
    p.add_argument('--batch', type=int, default=48)
    p.add_argument('--mode', choices=('conditional', 'reading-prior'), default='conditional')
    p.add_argument('--output', type=Path, required=True)
    p.set_defaults(run=score)
    args = parser.parse_args()
    args.run(args)


if __name__ == '__main__':
    main()
