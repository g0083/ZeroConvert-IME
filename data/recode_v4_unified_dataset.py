# SPDX-License-Identifier: Apache-2.0
"""Bind unchanged approved rows/partitions to a new append-only codec."""
import argparse
import json
from pathlib import Path
import shutil

from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--parent', type=Path, required=True)
    p.add_argument('--codec', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--max-length', type=int, default=512)
    args = p.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    codec = V4UnifiedCodec(args.codec)
    parent = json.loads((args.parent/'manifest.json').read_text(encoding='utf-8'))
    if not 1 < args.max_length <= 1024:
        raise ValueError('Invalid length')
    for name, expected in parent['output_hashes'].items():
        if digest(args.parent/name) != expected:
            raise ValueError('Parent changed')
    args.out.mkdir(parents=True)
    for name in ('train.jsonl', 'val.jsonl', 'sources.json'):
        shutil.copy2(args.parent/name, args.out/name)
    shutil.copytree(args.parent/'source-evidence', args.out/'source-evidence')
    write_json(args.out/'manifest.json', {'schema': 'zerocon-v4-unified-dataset-v1',
        'operation': 'unchanged source rows and partitions, new observed-reading/action codec',
        'arguments': {'max_length': args.max_length}, 'parent': str(args.parent),
        'parent_manifest_sha256': digest(args.parent/'manifest.json'),
        'codec_sha256': digest(args.codec/'v4-unified-codec.json'),
        'output_hashes': parent['output_hashes'], 'counts': parent['counts'],
        'code_sha256': digest(__file__), 'observations': codec.metadata.get('repair_observation'),
        'label_leak_policy': 'observation derived inside codec from raw/pending only, identical for every action',
        'publication_status': parent.get('publication_status', 'research')})
    print('Rebound unchanged data; independent audit is still required')


if __name__ == '__main__':
    main()
