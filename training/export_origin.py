# SPDX-License-Identifier: Apache-2.0
"""Export an Origin checkpoint through the repository's pinned GGUF converter.

The exported model still needs OriginCodec: assemble prompt token IDs, do not
concatenate untrusted strings with control tags. Does not update any IME.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys

from origin_contract import OriginCodec, digest, write_json

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path, help='Fresh artifact directory')
    parser.add_argument('--outtype', choices=('f16', 'f32'), default='f16')
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    manifest = json.loads((args.run / 'run-manifest.json').read_text(encoding='utf-8'))
    if manifest.get('schema') != 'zerocon-origin-run-v1':
        raise ValueError('Not an Origin run')
    if digest(args.run / 'tokenizer.model') != manifest['tokenizer_sha256']:
        raise ValueError('Tokenizer identity mismatch')
    converter = ROOT / 'android/app/src/main/cpp/llama.cpp/convert_hf_to_gguf.py'
    codec = OriginCodec(args.run / 'tokenizer.model')
    staging = args.out / 'hf'
    shutil.copytree(args.run / 'hf', staging)
    for name in ('tokenizer.model', 'tokenizer.vocab', 'origin-tokenizer.json'):
        shutil.copy2(args.run / name, staging / name)
    controls = {codec.bos_id: '<bos>', codec.eos_id: '<eot>', codec.pad_id: '<pad>', **{v: k for k, v in codec.ids.items()}}
    write_json(staging / 'tokenizer_config.json', {
        'tokenizer_class': 'LlamaTokenizer', 'bos_token': '<bos>', 'eos_token': '<eot>',
        'unk_token': '<unk>', 'pad_token': '<pad>', 'add_bos_token': False,
        'add_eos_token': False, 'add_prefix_space': False, 'clean_up_tokenization_spaces': False,
        'added_tokens_decoder': {str(i): {'content': tag, 'special': True} for i, tag in controls.items()},
    })
    gguf = args.out / f'origin-{args.outtype}.gguf'
    command = [sys.executable, str(converter), str(staging), '--outfile', str(gguf), '--outtype', args.outtype]
    with (args.out / 'conversion.log').open('w', encoding='utf-8') as log:
        subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT, cwd=ROOT)
    for name in ('LICENSE', 'sources.json', 'run-manifest.json', 'metrics.json'):
        if (args.run / name).is_file():
            shutil.copy2(args.run / name, args.out / name)
    shutil.copytree(args.run / 'third-party-licenses', args.out / 'third-party-licenses')
    if (args.run / 'source-evidence').is_dir():
        shutil.copytree(args.run / 'source-evidence', args.out / 'source-evidence')
    write_json(args.out / 'export-manifest.json', {
        'source_run': str(args.run), 'source_manifest_sha256': digest(args.run / 'run-manifest.json'),
        'converter_sha256': digest(converter), 'gguf_sha256': digest(gguf),
        'converter_code_hashes': {str(p.relative_to(converter.parent)): digest(p)
            for directory in ('conversion', 'gguf-py/gguf')
            for p in sorted((converter.parent / directory).rglob('*.py'))},
        'hf_source_hashes': {p.name: digest(p) for p in sorted((args.run / 'hf').iterdir()) if p.is_file()},
        'export_code_sha256': digest(Path(__file__)),
        'output_precision': args.outtype,
        'gguf_bytes': gguf.stat().st_size, 'command': command,
        'status': 'converted; runtime parity must be measured separately',
    })
    print(f'Exported {gguf}: {gguf.stat().st_size} bytes')


if __name__ == '__main__':
    main()
