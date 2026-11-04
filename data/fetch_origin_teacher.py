# SPDX-License-Identifier: Apache-2.0
"""Acquire one reviewed, revision-pinned local teacher without remote code.

This only downloads data files. It neither loads the model nor produces student
training examples. Use a fresh artifacts directory, or --resume for this same
pinned acquisition. Hugging Face cache metadata stays inside that directory.
"""
import argparse
import json
from pathlib import Path

from origin_contract import digest, write_json

REPO = 'Qwen/Qwen3-4B-Instruct-2507'
REVISION = 'cdbee75f17c01a7cc42f958dc650907174af0554'
FILES = ('LICENSE', 'README.md', 'config.json', 'generation_config.json',
         'merges.txt', 'vocab.json', 'tokenizer.json', 'tokenizer_config.json',
         'model.safetensors.index.json',
         'model-00001-of-00003.safetensors', 'model-00002-of-00003.safetensors',
         'model-00003-of-00003.safetensors')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    if args.out.exists() and not args.resume:
        raise FileExistsError(args.out)
    from huggingface_hub import HfApi, hf_hub_download
    info = HfApi(token=False).model_info(REPO, revision=REVISION, files_metadata=True)
    if info.sha != REVISION or info.card_data.to_dict().get('license') != 'apache-2.0':
        raise ValueError('Reviewed teacher revision/license identity differs')
    remote = {f.rfilename: f for f in info.siblings}
    if set(FILES) - remote.keys() or sum(remote[n].size for n in FILES) > 9_000_000_000:
        raise ValueError('Unexpected teacher file inventory/size')
    identity = {'repository': REPO, 'revision': REVISION, 'license': 'apache-2.0',
                'files': {n: {'bytes': remote[n].size,
                             'lfs_sha256': remote[n].lfs.sha256 if remote[n].lfs else None}
                          for n in FILES}}
    binding = args.out / 'acquisition-identity.json'
    if args.resume and (not binding.is_file() or json.loads(binding.read_text(encoding='utf-8')) != identity):
        raise ValueError('Resume must match this exact public acquisition')
    args.out.mkdir(parents=True, exist_ok=True)
    if not binding.exists():
        write_json(binding, identity)
    recorded = {}
    for name in FILES:
        path = Path(hf_hub_download(REPO, name, revision=REVISION, token=False,
                                   local_dir=args.out / 'hf'))
        expected = identity['files'][name]
        actual = digest(path)
        if path.stat().st_size != expected['bytes'] or (expected['lfs_sha256'] and actual != expected['lfs_sha256']):
            raise ValueError(f'Public file size/hash mismatch: {name}')
        recorded[name] = {'bytes': path.stat().st_size, 'sha256': actual,
                          'url': f'https://huggingface.co/{REPO}/resolve/{REVISION}/{name}'}
        print(f'{name}: {path.stat().st_size:,} bytes verified', flush=True)
        write_json(args.out / 'acquisition-progress.json', recorded)
    root = args.out / 'hf'
    config = json.loads((root / 'config.json').read_text(encoding='utf-8'))
    index = json.loads((root / 'model.safetensors.index.json').read_text(encoding='utf-8'))
    if config.get('model_type') != 'qwen3' or set(index['weight_map'].values()) != {n for n in FILES if n.endswith('.safetensors')}:
        raise ValueError('Model architecture/shard inventory differs')
    write_json(args.out / 'teacher-source-manifest.json', {
        **identity, 'verified_files': recorded, 'code_sha256': digest(Path(__file__)),
        'source_role': 'separate local teacher candidate; not student initialization',
        'remote_code_executed': False, 'teacher_outputs_created': False,
        'student_data_changed': False,
        'status': 'source acquired and verified; inference/quality remain unverified',
    })


if __name__ == '__main__':
    main()
