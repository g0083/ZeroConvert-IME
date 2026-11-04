# SPDX-License-Identifier: Apache-2.0
"""Make an experimental Q8_0 Origin artifact without calibration data access."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys

from origin_contract import digest, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--export', dest='source', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--quantizer', type=Path, default=Path('ZeroConvertIME-windows/llama_cpu/llama-quantize.exe'))
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    source = args.source / 'origin-f16.gguf'
    provenance = json.loads((args.source / 'export-manifest.json').read_text(encoding='utf-8'))
    if digest(source) != provenance['gguf_sha256']:
        raise ValueError('Original GGUF export changed')
    args.out.mkdir(parents=True)
    target = args.out / 'origin-q8_0.gguf'
    command = [str(args.quantizer.resolve()), '--token-embedding-type', 'q8_0', '--output-tensor-type', 'q8_0',
               str(source.resolve()), str(target.resolve()), 'Q8_0', '4']
    with (args.out / 'quantization.log').open('x', encoding='utf-8') as log:
        subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT,
                       creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == 'win32' else 0)
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'android/app/src/main/cpp/llama.cpp/gguf-py'))
    import gguf
    reader = gguf.GGUFReader(str(target))
    tensors = {t.name: {'shape': t.shape.tolist(), 'type': t.tensor_type.name} for t in reader.tensors}
    if tensors['token_embd.weight']['type'] != 'Q8_0' or any(v['type'] != 'Q8_0' for v in tensors.values() if len(v['shape']) == 2):
        raise ValueError('Q8 tensor inspection failed')
    for name in ('LICENSE', 'sources.json', 'run-manifest.json', 'metrics.json'):
        if (args.source / name).is_file():
            shutil.copy2(args.source / name, args.out / name)
    for name in ('source-evidence', 'third-party-licenses'):
        if (args.source / name).is_dir():
            shutil.copytree(args.source / name, args.out / name)
    write_json(args.out / 'quantization-manifest.json', {
        'source_export': str(args.source), 'export_manifest_sha256': digest(args.source / 'export-manifest.json'),
        'source_gguf_sha256': provenance['gguf_sha256'], 'output_gguf_sha256': digest(target),
        'source_bytes': source.stat().st_size, 'output_bytes': target.stat().st_size, 'command': command,
        'quantizer_sha256': digest(args.quantizer),
        'runtime_dll_hashes': {p.name: digest(p) for p in sorted(args.quantizer.parent.glob('*.dll'))},
        'tensor_types': tensors, 'calibration_data': 'none; Q8 conversion only',
        'code_sha256': digest(Path(__file__)), 'status': 'experimental; all task/quality/latency gates still require evaluation',
    })
    print(f'Q8_0: {target.stat().st_size:,} bytes; {len(tensors)} tensors inspected')


if __name__ == '__main__':
    main()
