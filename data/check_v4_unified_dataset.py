# SPDX-License-Identifier: Apache-2.0
"""Independent read-only audit of source families, development keys and codec."""
import argparse
from collections import Counter
import json
from pathlib import Path

from build_v4_unified_dataset import rows, isolation_keys, benchmark_collision
from dataset_isolation import evaluation_keys
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec


def audit(dataset, codec_path):
    manifest_path = dataset / 'manifest.json'
    meta = json.loads(manifest_path.read_text(encoding='utf-8'))
    if meta['codec_sha256'] != digest(codec_path / 'v4-unified-codec.json'):
        raise ValueError('Codec changed')
    for name, expected in meta['output_hashes'].items():
        if digest(dataset / name) != expected:
            raise ValueError('Dataset changed')
    codec, reserved = V4UnifiedCodec(codec_path), evaluation_keys()
    previous, counts = set(), Counter()
    for split in ('val', 'train'):
        current, observations = set(), {}
        for row in rows(dataset / f'{split}.jsonl'):
            if benchmark_collision(row, reserved):
                raise ValueError('Benchmark collision')
            keys = isolation_keys(row)
            if keys & previous:
                raise ValueError('Train/validation source or observation overlap')
            current |= keys
            example = codec.example(row, meta['arguments']['max_length'])
            boundary = next(i for i, label in enumerate(example['labels']) if label != -100)
            prompt = tuple(example['input_ids'][:boundary])
            target = json.dumps(row['target'], ensure_ascii=False, sort_keys=True)
            if prompt in observations and observations[prompt] != target:
                raise ValueError('Conflicting targets for identical observed input')
            observations[prompt] = target
            decoded = codec.decode_answer(codec.answer(row), row['task'])
            if decoded != row['target']:
                raise ValueError('Answer round trip failed')
            counts[f'{split}_{row["task"]}_{row["target"]["action"]}'] += 1
        previous |= current
    return {'schema': 'zerocon-v4-unified-audit-v1', 'status': 'PASS',
            'manifest_sha256': digest(manifest_path), 'codec_sha256': digest(codec_path / 'v4-unified-codec.json'),
            'output_hashes': meta['output_hashes'], 'counts': dict(counts), 'auditor_sha256': digest(__file__),
            'checks': ['inherited audited source partitions', 'source family and observation separation',
                       'existing development keys', 'uniform repair fields', 'exact codec/target round trip',
                       'no truncation', 'observation-label conflicts'],
            'limits': ['No proof of semantic disjointness or manual reading quality', 'No real-user or sealed evaluation']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--codec', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    result = audit(args.dataset, args.codec)
    write_json(args.out, result)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
