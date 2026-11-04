"""Append rule-labelled mixed literal/Japanese conversion; independent templates per split."""
import argparse
import json
from pathlib import Path
import random
import shutil
from build_v4_unified_dataset import rows, isolation_keys, benchmark_collision
from dataset_isolation import evaluation_keys
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec


def build(parent, codec_path, out):
    if out.exists():
        raise FileExistsError(out)
    meta = json.loads((parent / 'manifest.json').read_text(encoding='utf-8'))
    for name, expected in meta['output_hashes'].items():
        if digest(parent / name) != expected:
            raise ValueError('Parent changed')
    codec = V4UnifiedCodec(codec_path)
    reserved = evaluation_keys()
    existing_val = set().union(*(isolation_keys(r) for r in rows(parent / 'val.jsonl')))
    existing_train = set().union(*(isolation_keys(r) for r in rows(parent / 'train.jsonl')))
    templates = {'train': [('をかくにんする', 'を確認する'), ('をきろくする', 'を記録する'), ('をほぞんする', 'を保存する')],
                 'val': [('をひょうじする', 'を表示する'), ('をせんたくする', 'を選択する')]}
    added = {}
    for split, count, seed in [('val', 1000, 93071), ('train', 6000, 76139)]:
        rng = random.Random(seed)
        records, seen = [], set()
        other = existing_train if split == 'val' else existing_val | set().union(*(isolation_keys(r) for r in added['val']))
        while len(records) < count:
            i = len(records)
            word = ''.join(rng.choice('abcdefghijklmnprstuvwxyz') for _ in range(8))
            n = rng.randint(100, 99999)
            kind = i % 6
            literal = (f'{n}' if kind == 0 else f'{rng.randint(2031, 2097)}-{rng.randint(1,12):02}-{rng.randint(1,28):02}' if kind == 1 else
                       f'{n}{rng.choice(["kg", "mm", "ms", "MB"])}' if kind == 2 else
                       'G:\\' + word + '\\' + str(n) if kind == 3 else
                       f'https://preserve.invalid/{word}?id={n}' if kind == 4 else
                       f'probe-{word} --index {n} --dry-run')
            reading, surface = rng.choice(templates[split])
            row = {'id': f'mixed-{split}-{i}', 'source_id': 'synthetic-mixed-preservation',
                   'document_id': f'mixed-{split}-{i}', 'source_text': literal + surface,
                   'task': 'convert', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
                   'input': {'ctx': '', 'reading': literal + reading},
                   'target': {'action': 'emit', 'text': literal + surface},
                   'category': 'v4-anchor', 'legacy_replay': False,
                   'quality': 'Rule-labelled literal preserved verbatim; fixed Japanese suffix; template-disjoint validation'}
            keys = isolation_keys(row)
            if keys & (other | seen) or benchmark_collision(row, reserved):
                continue
            codec.example(row, meta['arguments']['max_length'])
            records.append(row)
            seen |= keys
        added[split] = records
    out.mkdir(parents=True)
    for split in ('train', 'val'):
        with (out / f'{split}.jsonl').open('w', encoding='utf-8') as f:
            for row in list(rows(parent / f'{split}.jsonl')) + added[split]:
                f.write(json.dumps(row, ensure_ascii=False) + '\n')
    shutil.copy2(parent / 'sources.json', out / 'sources.json')
    shutil.copytree(parent / 'source-evidence', out / 'source-evidence')
    shutil.copy2(__file__, out / 'source-evidence' / Path(__file__).name)
    counts = dict(meta['counts'])
    for split in ('train', 'val'):
        counts[f'{split}_convert_emit'] += len(added[split])
    write_json(out / 'manifest.json', {**meta, 'counts': counts, 'parent': str(parent),
        'parent_manifest_sha256': digest(parent / 'manifest.json'), 'operation': 'append mixed preservation, separate split templates',
        'mixed_counts': {k: len(v) for k,v in added.items()}, 'mixed_templates': templates,
        'code_sha256': digest(__file__), 'output_hashes': {n: digest(out/n) for n in ('train.jsonl', 'val.jsonl', 'sources.json')}})
    print(json.dumps({k: len(v) for k,v in added.items()}), flush=True)


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--parent', type=Path, required=True)
    p.add_argument('--codec', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    build(a.parent, a.codec, a.out)
