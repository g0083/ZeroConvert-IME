# SPDX-License-Identifier: Apache-2.0
"""Append verified training spans without rebuilding or changing a parent cache."""
import argparse
from array import array
import copy
import json
from pathlib import Path
import shutil
import sys

from origin_contract import OriginCodec, digest, validate_sources, write_json
from origin_token_cache import INDEX, TokenCache


def append_sequences(rows, codec, out, parent_meta, max_length, task_names):
    if sys.byteorder != 'little' or codec.sp.get_piece_size() > 65535:
        raise ValueError('Cache requires little endian uint16 vocabulary')
    meta = copy.deepcopy(parent_meta)
    with (out / 'train.tokens').open('ab') as tokens, (out / 'train.index').open('ab') as index:
        for count, row in enumerate(rows, 1):
            example = codec.example(row, max_length)
            ids, labels = example['input_ids'], example['labels']
            prompt = next(i for i, label in enumerate(labels) if label != -100)
            index.write(INDEX.pack(tokens.tell(), len(ids), prompt, task_names.index(row['task'])))
            tokens.write(array('H', ids).tobytes())
            meta['rows'] += 1
            meta['processed_tokens_one_pass'] += len(ids)
            meta['target_tokens_one_pass'] += len(ids) - prompt
            meta['by_task'][row['task']] = meta['by_task'].get(row['task'], 0) + 1
            if count % 100000 == 0:
                print(f'Appended {count:,} source-aligned spans', flush=True)
    meta.update(tokens_sha256=digest(out / 'train.tokens'), index_sha256=digest(out / 'train.index'),
                token_bytes=(out / 'train.tokens').stat().st_size, index_bytes=(out / 'train.index').stat().st_size)
    return meta


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent-cache', type=Path, required=True)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--lineage-audit', type=Path, required=True)
    parser.add_argument('--isolation-audit', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    meta = json.loads((args.parent_cache / 'cache-manifest.json').read_text(encoding='utf-8'))
    manifest = json.loads((args.dataset / 'manifest.json').read_text(encoding='utf-8'))
    lineage = json.loads(args.lineage_audit.read_text(encoding='utf-8'))
    isolation = json.loads(args.isolation_audit.read_text(encoding='utf-8'))
    manifest_hash = digest(args.dataset / 'manifest.json')
    if (lineage.get('status') != 'PASS' or isolation.get('status') != 'PASS' or
            lineage['dataset_manifest_sha256'] != manifest_hash or isolation['manifest_sha256'] != manifest_hash):
        raise ValueError('Matching completed lineage and isolation audits are required')
    verifier = Path(__file__).resolve().parents[1] / 'data/verify_origin_spans.py'
    if lineage['code_sha256'] != digest(verifier):
        raise ValueError('Lineage verifier changed; rerun the verification')
    if (manifest['operation'] != 'source-aligned training conversion span augmentation' or
            meta['dataset_hashes'] != lineage['parent_output_hashes'] or
            meta['dataset_manifest_sha256'] != lineage['parent_manifest_sha256'] or
            lineage['output_hashes'] != manifest['output_hashes']):
        raise ValueError('Span data is not a verified extension of this cache')
    parent_dataset = Path(meta['dataset'])
    if digest(parent_dataset / 'manifest.json') != meta['dataset_manifest_sha256']:
        raise ValueError('Parent dataset manifest changed')
    for name, value in meta['dataset_hashes'].items():
        if digest(parent_dataset / name) != value:
            raise ValueError('Parent source outputs changed')
    for name, value in manifest['output_hashes'].items():
        if digest(args.dataset / name) != value:
            raise ValueError('Verified augmented outputs changed')
    if (manifest['output_hashes']['val.jsonl'] != meta['dataset_hashes']['val.jsonl'] or
            manifest['output_hashes']['sources.json'] != meta['dataset_hashes']['sources.json']):
        raise ValueError('Validation or license records changed')
    if digest(args.parent_cache / 'tokenizer.model') != meta['tokenizer_sha256']:
        raise ValueError('Parent vocabulary changed')
    with TokenCache(args.parent_cache, 'train') as train, TokenCache(args.parent_cache, 'val') as val:
        for cache in (train, val):
            for position in (0, len(cache) - 1):
                cache[position]  # shape, boundary, task and EOS checks
    args.out.mkdir(parents=True)
    for name in ('train.tokens', 'train.index', 'val.tokens', 'val.index', 'tokenizer.model', 'tokenizer.vocab', 'origin-tokenizer.json'):
        shutil.copy2(args.parent_cache / name, args.out / name)
    codec = OriginCodec(args.out / 'tokenizer.model')
    sources = json.loads((args.dataset / 'sources.json').read_text(encoding='utf-8'))
    def new_rows():
        with (args.dataset / 'train.jsonl').open('rb') as stream:
            stream.seek(lineage['original_training_prefix_bytes'])
            for line in stream:
                row = json.loads(line)
                if row['task'] != 'convert' or row.get('category') != 'morph-span':
                    raise ValueError('Unexpected task in the appended tail')
                validate_sources([row], sources)
                yield row
    updated = append_sequences(new_rows(), codec, args.out, meta['partitions']['train'], meta['max_length'], meta['task_names'])
    if updated['rows'] - meta['partitions']['train']['rows'] != lineage['verified_spans']:
        raise ValueError('Appended cache count differs from verified source spans')
    result = copy.deepcopy(meta)
    result.update(dataset=str(args.dataset), dataset_hashes=manifest['output_hashes'], dataset_manifest_sha256=manifest_hash,
                  isolation_audit_sha256=digest(args.isolation_audit), tokenizer_transfer={
                      'kind': 'vocabulary trained on the preserved parent training partition only',
                      'parent_cache_manifest_sha256': digest(args.parent_cache / 'cache-manifest.json'),
                      'span_lineage_audit_sha256': digest(args.lineage_audit), 'verified_spans': lineage['verified_spans']})
    result['partitions']['train'] = updated
    result['code_hashes'] = {p.name: digest(p) for p in (Path(__file__), Path(__file__).with_name('origin_token_cache.py'),
                                                       Path(__file__).with_name('origin_contract.py'), verifier)}
    write_json(args.out / 'parent-cache-provenance.json', meta)
    write_json(args.out / 'cache-manifest.json', result)
    # Loading the new cache with the independent reader verifies final hashes and boundaries.
    with TokenCache(args.out, 'train') as cache:
        for position in (0, meta['partitions']['train']['rows'] - 1, meta['partitions']['train']['rows'], len(cache) - 1):
            if position < len(cache):
                cache[position]
    print(json.dumps(updated, indent=2))


if __name__ == '__main__':
    main()
