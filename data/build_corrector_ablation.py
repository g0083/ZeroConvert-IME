"""Matched-budget vocabulary/task ablation; benchmark text is used only to exclude.

Reads verified dictionary reading/surface pairs, never invents a kanji reading.
Both data conditions use the same synthetic error generator and shared held-out
source groups. This is a development experiment, not a replacement IME corpus.
"""
import argparse
import hashlib
import json
import random
import struct
from collections import Counter
from pathlib import Path

from build_corrector_dataset import augment
from dataset_isolation import evaluation_keys, load_jsonl, normalize, separated_rows, sha256, validate_rows
from generate_typo_dataset import hira_to_kata, mutate_geminate, mutate_syllabic_nasal, mutate_flick_proximity, mutate_prolonged


def kana(text):
    return bool(text) and all('ぁ' <= c <= 'ゖ' or c == 'ー' for c in normalize(text))


def seed_rows(paths):
    seeds = {}
    for path in paths:
        for r in load_jsonl(path):
            reading = normalize(r.get('target_reading', ''))
            if not kana(reading) or not 3 <= len(reading) <= 100:
                continue
            context = r['prompt'].split('\uee00', 1)[0].replace('\uee02', '')
            key = context, reading, r['completion']
            seeds[key] = {'prompt': f'\uee02{context}\uee00{hira_to_kata(reading)}\uee01',
                          'target_reading': reading, 'completion': r['completion'], 'pool': 'base'}
    return list(seeds.values())


def dictionary_seeds(path, reserved, limit):
    data = path.read_bytes()
    if data[:4] != b'AZR2':
        raise ValueError('Expected AZR2 dictionary')
    count = struct.unpack_from('<I', data, 4)[0]
    offsets = struct.unpack_from(f'<{count+1}I', data, 8)
    best = {}
    for start, end in zip(offsets, offsets[1:]):
        sl, rl, score = struct.unpack_from('<HHf', data, start)
        if start + 8 + sl + rl != end:
            raise ValueError('Invalid dictionary record')
        surface = data[start+8:start+8+sl].decode('utf-8')
        reading = normalize(data[start+8+sl:end].decode('utf-8'))
        if not (3 <= len(reading) <= 16 and kana(reading) and 2 <= len(surface) <= 16):
            continue
        if not any('一' <= c <= '鿿' for c in surface) or not all('ぁ' <= c <= 'ゖ' or 'ァ' <= c <= 'ヶ' or '一' <= c <= '鿿' or c == 'ー' for c in surface):
            continue
        if {reading, normalize(surface)} & reserved:
            continue
        # One high-scoring dictionary spelling per reading limits ambiguity.
        if reading not in best or score > best[reading][0]:
            best[reading] = score, surface
    choices = sorted(best.items(), key=lambda v: (-v[1][0], hashlib.sha256(v[0].encode()).hexdigest()))[:limit]
    return [{'prompt': f'\uee00{hira_to_kata(rd)}\uee01', 'target_reading': rd,
             'completion': surface, 'pool': 'dictionary'} for rd, (_, surface) in choices]


def generate(seeds):
    rows = augment(seeds)
    random.seed(20261027)
    for seed in seeds:
        reading = seed['target_reading']
        for name, fn in [('geminate', mutate_geminate), ('nasal', mutate_syllabic_nasal),
                         ('flick', mutate_flick_proximity), ('prolonged', mutate_prolonged)]:
            for _ in range(2):
                noisy = fn(reading)
                if noisy != reading:
                    prefix = seed['prompt'].split('\uee00')[0]
                    rows.append({**seed, 'prompt': f'{prefix}\uee00{hira_to_kata(noisy)}\uee01',
                                 'input_reading': noisy, 'augmentation': name})
    seen, result = set(), []
    for row in rows:
        key = row['prompt'], row['completion']
        if key not in seen:
            seen.add(key)
            result.append(row)
    return result


def build(source, dictionary, output, words=6000):
    if output.exists():
        raise FileExistsError(output)
    paths = [source / 'train.jsonl', source / 'val.jsonl']
    base = seed_rows(paths)
    reserved = evaluation_keys()
    base_keys = {normalize(r[k]) for r in base for k in ('target_reading', 'completion')}
    extra = dictionary_seeds(dictionary, reserved | base_keys, words)
    train, val, excluded = separated_rows(generate(base + extra), reserved)
    rng = random.Random(20261027)
    base_train = [r for r in train if r['pool'] == 'base']
    dict_train = [r for r in train if r['pool'] == 'dictionary']
    rng.shuffle(base_train)
    rng.shuffle(dict_train)
    budget = min(40000, len(base_train))
    narrow = base_train[:budget]
    # Match the augmentation mix as well as total examples, replacing half in
    # each stratum with dictionary vocabulary wherever enough examples exist.
    broad = []
    for kind, count in Counter(r.get('augmentation', 'clean') for r in narrow).items():
        old = [r for r in base_train if r.get('augmentation', 'clean') == kind]
        new = [r for r in dict_train if r.get('augmentation', 'clean') == kind]
        take = min(count // 2, len(new))
        broad.extend(old[:count-take] + new[:take])
    shared_val = []
    for pool in ('base', 'dictionary'):
        rows = [r for r in val if r['pool'] == pool]
        rng.shuffle(rows)
        shared_val.extend(rows[:1540])
    output.mkdir(parents=True)
    manifest = {'sources': {str(p): sha256(p) for p in paths + [dictionary]},
                'seed': 20261027, 'base_seeds': len(base), 'dictionary_seeds': len(extra),
                'excluded_rows': len(excluded), 'conditions': {}}
    for name, rows in [('narrow', narrow), ('diverse', broad)]:
        checks = validate_rows(rows, shared_val, reserved)
        for task in ('surface', 'reading'):
            folder = output / f'{name}-{task}'
            folder.mkdir()
            info = {'checks': checks, 'files': {}}
            paired = {split: [{**r, 'surface_target': r['completion'],
                              'completion': normalize(r['target_reading']) if task == 'reading' else r['completion']}
                             for r in items] for split, items in [('train', rows), ('val', shared_val)]}
            validate_rows(paired['train'], paired['val'], reserved)
            for split, items in [('train', rows), ('val', shared_val)]:
                items = paired[split]
                path = folder / f'{split}.jsonl'
                path.write_text(''.join(json.dumps(r, ensure_ascii=False)+'\n' for r in items), encoding='utf-8')
                info['files'][split] = {'sha256': sha256(path), 'rows': len(items),
                    'unique_surfaces': len({r['surface_target'] for r in items}),
                    'source_groups': len({r['source_group'] for r in items}),
                    'augmentation': dict(Counter(r.get('augmentation', 'clean') for r in items)),
                    'pools': dict(Counter(r['pool'] for r in items))}
            manifest['conditions'][f'{name}-{task}'] = info
    (output/'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    return manifest


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, default=Path('artifacts/datasets/corrector-v2-20261027'))
    p.add_argument('--dictionary', type=Path, default=Path('ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin'))
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    print(json.dumps(build(args.source, args.dictionary, args.out), ensure_ascii=False, indent=2))
