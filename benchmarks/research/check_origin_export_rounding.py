# SPDX-License-Identifier: Apache-2.0
"""Check every F16 GGUF matrix against the same export in original F32 precision."""
import argparse
import json
from pathlib import Path
import sys

from origin_contract import digest, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--f32-export', type=Path, required=True)
    parser.add_argument('--f16-export', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    directories = [args.f32_export, args.f16_export]
    manifests = [json.loads((p / 'export-manifest.json').read_text(encoding='utf-8')) for p in directories]
    for key in ('source_manifest_sha256', 'hf_source_hashes', 'converter_sha256', 'converter_code_hashes'):
        if manifests[0][key] != manifests[1][key]:
            raise ValueError(f'Exports differ beyond output precision: {key}')
    files = [args.f32_export / 'origin-f32.gguf', args.f16_export / 'origin-f16.gguf']
    for path, manifest in zip(files, manifests):
        if digest(path) != manifest['gguf_sha256']:
            raise ValueError('GGUF export changed')
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'android/app/src/main/cpp/llama.cpp/gguf-py'))
    import gguf
    import numpy as np
    readers = [gguf.GGUFReader(str(p)) for p in files]
    tensors = [{t.name: t for t in reader.tensors} for reader in readers]
    if set(tensors[0]) != set(tensors[1]):
        raise ValueError('Tensor names differ')
    checks = []
    for name, full in tensors[0].items():
        half = tensors[1][name]
        if full.tensor_type.name != 'F32' or full.shape.tolist() != half.shape.tolist():
            raise ValueError('Original precision or shape differs')
        if len(full.shape) == 2:
            if half.tensor_type.name != 'F16':
                raise ValueError('Expected F16 matrix')
            expected = full.data.astype(np.float16).view(np.uint16)
            actual = half.data.view(np.uint16)
        else:
            if half.tensor_type.name != 'F32':
                raise ValueError('Expected unchanged F32 vector')
            expected, actual = full.data.view(np.uint32), half.data.view(np.uint32)
        exact = np.array_equal(expected, actual)
        checks.append({'tensor': name, 'shape': full.shape.tolist(), 'type': half.tensor_type.name, 'exact_expected_bits': bool(exact)})
        if not exact:
            raise ValueError(f'Unexpected serialized tensor values: {name}')
    result = {'status': 'PASS', 'source_run_manifest_sha256': manifests[0]['source_manifest_sha256'],
              'gguf_sha256': {'f32': digest(files[0]), 'f16': digest(files[1])},
              'tensors_checked': len(checks), 'checks': checks, 'code_sha256': digest(Path(__file__)),
              'limits': ['Proves serialized rounding, not identical floating-point runtime arithmetic or task quality']}
    write_json(args.out, result)
    print(f'PASS: {len(checks)} tensors equal the expected rounded/unchanged bit patterns')


if __name__ == '__main__':
    main()
