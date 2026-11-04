# SPDX-License-Identifier: Apache-2.0
"""Acquire a pinned Japanese synthetic corpus with license/provenance records.

Downloads data only. No repository code, dataset scripts or remote code executes.
The filtered subset retains original row provenance and attribution.
"""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

from origin_contract import digest, write_json

DATASET = 'OysterCoreAI/SFT-General-Japanese-60K'
UPSTREAM = 'llm-jp/magpie-sft-v1.0'


def get(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def download(url, path, max_bytes=100_000_000):
    if path.exists():
        return {'url': url, 'bytes': path.stat().st_size, 'sha256': digest(path), 'reused_from_pinned_acquisition': True}
    count = 0
    partial = path.with_suffix(path.suffix + '.partial')
    if partial.exists():
        raise FileExistsError(f'Interrupted partial file requires review: {partial}')
    with urllib.request.urlopen(url, timeout=120) as response, partial.open('xb') as output:
        for block in iter(lambda: response.read(1024 * 1024), b''):
            count += len(block)
            if count > max_bytes:
                raise ValueError('Unexpectedly large source file')
            output.write(block)
    partial.rename(path)
    print(f'{path.name}: {count:,} bytes', flush=True)
    return {'url': url, 'bytes': count, 'sha256': digest(path)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--shards', type=int, default=12)
    parser.add_argument('--resume', action='store_true', help='Continue a pinned acquisition without overwriting data')
    args = parser.parse_args()
    if not 1 <= args.shards <= 12:
        parser.error('shards must be 1..12')
    if args.out.exists() and not args.resume:
        raise FileExistsError(args.out)
    if args.resume:
        metadata = json.loads((args.out / 'repository-metadata.json').read_text(encoding='utf-8'))
    else:
        metadata = json.loads(get(f'https://huggingface.co/api/datasets/{DATASET}'))
    revision = metadata['sha']
    if metadata.get('cardData', {}).get('license') != 'apache-2.0':
        raise ValueError('Dataset license changed; review required')
    upstream = (json.loads((args.out / 'upstream-metadata.json').read_text(encoding='utf-8')) if args.resume
                else json.loads(get(f'https://huggingface.co/api/datasets/{UPSTREAM}')))
    if upstream.get('cardData', {}).get('license') != 'apache-2.0':
        raise ValueError('Upstream license changed; review required')
    args.out.mkdir(parents=True, exist_ok=args.resume)
    write_json(args.out / 'repository-metadata.json', metadata)
    write_json(args.out / 'upstream-metadata.json', upstream)
    files = {}
    for name in ('README.md', 'LICENSE', 'ATTRIBUTION.md'):
        target = args.out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        files[name] = download(f'https://huggingface.co/datasets/{DATASET}/resolve/{revision}/{name}', target)
    for i in range(args.shards):
        name = f'data/train-{i:05d}-of-00012.jsonl'
        target = args.out / name
        target.parent.mkdir(parents=True, exist_ok=True)
        files[name] = download(f'https://huggingface.co/datasets/{DATASET}/resolve/{revision}/{name}', target)
    for name in ('README.md',):
        files[f'upstream-{name}'] = download(
            f'https://huggingface.co/datasets/{UPSTREAM}/resolve/{upstream["sha"]}/{name}', args.out / f'upstream-{name}')
    teacher_metadata = {}
    for model in ('cyberagent/calm3-22b-chat', 'Qwen/Qwen2.5-32B-Instruct'):
        info = json.loads(get(f'https://huggingface.co/api/models/{model}'))
        if info.get('cardData', {}).get('license') != 'apache-2.0':
            raise ValueError(f'Teacher license review required: {model}')
        label = model.replace('/', '--')
        write_json(args.out / f'{label}-metadata.json', info)
        available = {item['rfilename'] for item in info.get('siblings', [])}
        for name in ('README.md', 'LICENSE'):
            if name not in available:
                print(f'{model}: no separate {name}; license declaration retained in card/API', flush=True)
                continue
            files[f'{label}-{name}'] = download(
                f'https://huggingface.co/{model}/resolve/{info["sha"]}/{name}', args.out / f'{label}-{name}')
        teacher_metadata[model] = {'reviewed_revision': info['sha'], 'license': 'Apache-2.0',
            'original_generation_revision': 'not recorded by upstream; model identity retained'}
    write_json(args.out / 'acquisition-manifest.json', {
        'dataset': DATASET, 'revision': revision, 'upstream': UPSTREAM,
        'upstream_reviewed_revision': upstream['sha'], 'files': files,
        'teachers': teacher_metadata, 'license': 'Apache-2.0',
        'fetch_code_sha256': digest(Path(__file__)),
        'limitations': ['Upstream exact generation checkpoint revisions are unspecified',
                       'Dataset card mentions audit files not present in the pinned repository',
                       'Published synthetic data; not a rights guarantee for every generated sentence',
                       'Reading labels and evaluation separation not yet applied'],
    })
    print(f'Acquisition complete: {revision}', flush=True)


if __name__ == '__main__':
    main()
