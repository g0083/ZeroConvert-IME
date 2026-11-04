# SPDX-License-Identifier: Apache-2.0
"""Create only a tiny source-preserving adaptation wiring fixture from T0."""
import argparse
import json
from pathlib import Path
import shutil

from origin_contract import digest, write_json
from rebalance_origin_repair import transform


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    base = json.loads((args.base / 'run-manifest.json').read_text(encoding='utf-8'))
    parent = Path(base['arguments']['dataset'])
    if base['parameters'] > 1000000:
        raise ValueError('This fixture command is for tiny T0 wiring tests only')
    args.out.mkdir(parents=True)
    for partition in ('train', 'val'):
        with (parent / f'{partition}.jsonl').open(encoding='utf-8') as source, (args.out / f'{partition}.jsonl').open('x', encoding='utf-8') as output:
            for line in source:
                output.write(json.dumps(transform(json.loads(line)), ensure_ascii=False) + '\n')
    shutil.copy2(parent / 'sources.json', args.out / 'sources.json')
    write_json(args.out / 'manifest.json', {
        'schema': 'zerocon-origin-v1', 'operation': 'remove repair label-correlated surface/cursor slots',
        'purpose': 'adaptation wiring fixture only', 'parent': str(parent),
        'parent_manifest_sha256': digest(parent / 'manifest.json'), 'parent_output_hashes': base['dataset_hashes'],
        'output_hashes': {name: digest(args.out / name) for name in ('train.jsonl', 'val.jsonl', 'sources.json')},
        'builder_sha256': digest(Path(__file__)),
    })
    print(f'Adaptation wiring fixture created: {args.out}')


if __name__ == '__main__':
    main()
