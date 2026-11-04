# SPDX-License-Identifier: Apache-2.0
"""Prepare a train-only tokenizer and bounded-memory Origin sequence cache."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import shutil

from origin_contract import OriginCodec, TASKS, digest, validate_sources, write_json
from origin_token_cache import write_partition
from train_origin import train_tokenizer


def rows_from(path):
    with path.open(encoding='utf-8') as stream:
        for line in stream:
            yield json.loads(line)


def tokenizer_sample(path, limit, seed):
    """Fixed-seed reservoir over training rows, bounded before SentencePiece."""
    rng, reservoir = random.Random(seed), []
    count = 0
    with path.open(encoding='utf-8') as stream:
        for count, line in enumerate(stream, 1):
            if count <= limit:
                reservoir.append(line)
            else:
                slot = rng.randrange(count)
                if slot < limit:
                    reservoir[slot] = line
    fingerprint = hashlib.sha256(''.join(reservoir).encode()).hexdigest()
    return reservoir, {'algorithm': 'uniform reservoir over training rows only',
                       'seed': seed, 'population_rows': count, 'sampled_rows': len(reservoir),
                       'ordered_sample_sha256': fingerprint, 'max_rows': limit}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--vocab-size', type=int, choices=(8192, 16384), default=16384)
    parser.add_argument('--max-length', type=int, default=256)
    parser.add_argument('--tokenizer-run', type=Path)
    parser.add_argument('--source-preserving-transform', action='store_true')
    parser.add_argument('--tokenizer-max-rows', type=int, default=100000)
    parser.add_argument('--tokenizer-sample-seed', type=int, default=42)
    args = parser.parse_args()
    if args.tokenizer_max_rows <= 0:
        parser.error('A positive bounded tokenizer sample is required')
    if args.out.exists():
        raise FileExistsError(args.out)
    audit = json.loads(args.audit.read_text(encoding='utf-8'))
    if audit.get('status') != 'PASS' or audit['manifest_sha256'] != digest(args.dataset / 'manifest.json'):
        raise ValueError('A matching source/withheld isolation audit is required')
    data_manifest = json.loads((args.dataset / 'manifest.json').read_text(encoding='utf-8'))
    for name, expected in data_manifest['output_hashes'].items():
        if digest(args.dataset / name) != expected:
            raise ValueError('Audited dataset changed')
    sources = json.loads((args.dataset / 'sources.json').read_text(encoding='utf-8'))
    # License and row-schema checks are streamed; no entire raw corpus list.
    for partition in ('train', 'val'):
        for row in rows_from(args.dataset / f'{partition}.jsonl'):
            validate_sources([row], sources)
    lineage = None
    if args.tokenizer_run:
        run = json.loads((args.tokenizer_run / 'run-manifest.json').read_text(encoding='utf-8'))
        if data_manifest['output_hashes'] != run['dataset_hashes']:
            if not args.source_preserving_transform:
                raise ValueError('Tokenizer training partition differs')
            from adapt_origin import verify_transfer
            verified = verify_transfer(args.dataset, run)
            lineage = {'parent_manifest_sha256': verified['parent_manifest_sha256'], 'verified_transform': True}
        if digest(args.tokenizer_run / 'tokenizer.model') != run['tokenizer_sha256']:
            raise ValueError('Tokenizer hash mismatch')
    args.out.mkdir(parents=True)
    sample_provenance = None
    if args.tokenizer_run:
        for name in ('tokenizer.model', 'tokenizer.vocab', 'origin-tokenizer.json'):
            shutil.copy2(args.tokenizer_run / name, args.out / name)
        codec = OriginCodec(args.out / 'tokenizer.model')
    else:
        sample, sample_provenance = tokenizer_sample(args.dataset / 'train.jsonl', args.tokenizer_max_rows,
                                                     args.tokenizer_sample_seed)
        codec = train_tokenizer((json.loads(line) for line in sample), args.out, args.vocab_size)
        del sample
    if codec.sp.get_piece_size() != args.vocab_size:
        raise ValueError('Actual vocabulary differs from requested model profile')
    partitions = {}
    for partition in ('train', 'val'):
        partitions[partition] = write_partition(rows_from(args.dataset / f'{partition}.jsonl'), codec,
                                                args.out, partition, args.max_length, list(TASKS))
    write_json(args.out / 'cache-manifest.json', {
        'schema': 'origin-token-cache-v1', 'dataset': str(args.dataset), 'dataset_hashes': data_manifest['output_hashes'],
        'isolation_audit_sha256': digest(args.audit), 'dataset_manifest_sha256': digest(args.dataset / 'manifest.json'),
        'tokenizer_sha256': digest(args.out / 'tokenizer.model'), 'tokenizer_transfer': lineage,
        'tokenizer_training_sample': sample_provenance,
        'vocab_size': codec.sp.get_piece_size(), 'eos_id': codec.eos_id, 'pad_id': codec.pad_id,
        'max_length': args.max_length, 'task_names': list(TASKS), 'partitions': partitions,
        'code_hashes': {p.name: digest(p) for p in (Path(__file__), Path(__file__).with_name('origin_token_cache.py'),
                        Path(__file__).with_name('origin_contract.py'), Path(__file__).with_name('train_origin.py'))},
        'normalization': 'No truncation; labels reconstructed exactly from prompt boundary and EOS',
    })
    print(json.dumps(partitions, indent=2))


if __name__ == '__main__':
    main()
