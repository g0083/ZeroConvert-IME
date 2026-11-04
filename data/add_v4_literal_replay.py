# SPDX-License-Identifier: Apache-2.0
"""Add deterministic, tagged-only literal copy training; leave validation unchanged."""
import argparse
import json
from pathlib import Path
import random
import shutil

from build_v4_unified_dataset import rows, benchmark_collision, isolation_keys
from dataset_isolation import evaluation_keys
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec


def build(parent, codec_path, out, count=1200):
    if out.exists():
        raise FileExistsError(out)
    codec = V4UnifiedCodec(codec_path)
    pm = json.loads((parent / 'manifest.json').read_text(encoding='utf-8'))
    for name, value in pm['output_hashes'].items():
        if digest(parent / name) != value:
            raise ValueError('Parent changed')
    reserved = evaluation_keys()
    val_keys = set().union(*(isolation_keys(r) for r in rows(parent / 'val.jsonl')))
    rng, added = random.Random(87153), []
    def word():
        return ''.join(rng.choice('abcdefghijknpqrstuvwxyz') for _ in range(rng.randint(3, 9)))
    while len(added) < count:
        index = len(added)
        a, b = word(), word()
        kind = index % 4
        text = (rng.choice('ACDEFGH') + ':\\' + a + '\\' + b if kind == 0 else
                f'https://copy.invalid/{a}?{b}={rng.randrange(1000)}' if kind == 1 else
                a + rng.choice([' ', '  ', '\t', '　']) + b if kind == 2 else
                f'{a}_{rng.randrange(1000)}.{b}')
        row = {'id': f'literal-train-{index}', 'source_id': 'synthetic-literal-copy',
               'document_id': f'literal-copy-{index}', 'source_text': text,
               'task': 'convert', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
               'input': {'ctx': '', 'reading': text}, 'target': {'action': 'emit', 'text': text},
               'category': 'v4-anchor', 'legacy_replay': False,
               'quality': 'Deterministic literal identity label; train only; no teacher/evaluation output used'}
        if benchmark_collision(row, reserved) or isolation_keys(row) & val_keys:
            continue
        codec.example(row, pm['arguments']['max_length'])
        added.append(row)
    out.mkdir(parents=True)
    with (out / 'train.jsonl').open('w', encoding='utf-8') as stream:
        for row in rows(parent / 'train.jsonl'):
            stream.write(json.dumps(row, ensure_ascii=False) + '\n')
        for row in added:
            stream.write(json.dumps(row, ensure_ascii=False) + '\n')
    for name in ('val.jsonl', 'sources.json'):
        shutil.copy2(parent / name, out / name)
    shutil.copytree(parent / 'source-evidence', out / 'source-evidence')
    shutil.copy2(__file__, out / 'source-evidence/add_v4_literal_replay.py')
    counts = dict(pm['counts'])
    counts['train_convert_emit'] += len(added)
    counts['train_tagged_only_literal_copy'] = len(added)
    manifest = {**pm, 'operation': 'append train-only literal identity replay; validation unchanged',
                'parent': str(parent), 'parent_manifest_sha256': digest(parent / 'manifest.json'),
                'counts': counts, 'literal_seed': 87153,
                'code_sha256': digest(__file__),
                'output_hashes': {n: digest(out / n) for n in ('train.jsonl', 'val.jsonl', 'sources.json')}}
    write_json(out / 'manifest.json', manifest)
    print(f'Added {len(added)} tagged-only training copies; validation preserved', flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--parent', type=Path, required=True)
    p.add_argument('--codec', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    build(a.parent, a.codec, a.out)
