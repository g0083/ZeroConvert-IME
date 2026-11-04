# SPDX-License-Identifier: Apache-2.0
"""Bind the preserved Standard float parent to its measured Q8/F16 artifacts."""
import argparse
import json
from pathlib import Path
import sys

import numpy as np
from safetensors import safe_open

from origin_contract import digest, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--parent', type=Path, default=Path('artifacts/checkpoints/standard-v4-nano-transfer-merged'))
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    sys.path.insert(0, str(Path('android/app/src/main/cpp/llama.cpp/gguf-py').resolve()))
    import gguf
    f16 = Path('artifacts/models/standard-v4-nano-transfer-f16.gguf')
    quant_manifest_path = Path('artifacts/datasets/standard-v4-nano-transfer-imatrix/manifest.json')
    quant = json.loads(quant_manifest_path.read_text(encoding='utf-8'))
    contract_path = Path('artifacts/results/origin-comparison-contract-20261029-r2.json')
    contract = json.loads(contract_path.read_text(encoding='utf-8'))
    if digest(f16) != quant['source_sha256']:
        raise ValueError('F16 quantization source changed')
    baseline = contract['baseline_models']['Standard v4 Q8']
    if digest(baseline['path']) != baseline['sha256']:
        raise ValueError('Q8 baseline changed')
    for name, expected in quant['outputs'].items():
        if digest(name) != expected:
            raise ValueError('Quantized artifact changed')
    reader = gguf.GGUFReader(str(f16))
    tensors = {t.name: t for t in reader.tensors}
    mapping = gguf.get_tensor_name_map(gguf.MODEL_ARCH.GPT2, 12)
    checked = []
    with safe_open(args.parent / 'model.safetensors', framework='numpy') as weights:
        for name in weights.keys():
            target = mapping.get_name(name, try_suffixes=('.weight', '.bias'))
            if target is None or target not in tensors:
                raise ValueError('Missing GGUF tensor: ' + name)
            value = weights.get_tensor(name)
            if name.endswith(('.c_attn.weight', '.c_proj.weight', '.c_fc.weight')):
                value = value.T
            actual = tensors[target].data
            if value.shape != actual.shape or not np.array_equal(value.astype(actual.dtype), actual):
                raise ValueError('HF/F16 tensor mismatch: ' + name)
            checked.append(target)
        embedding = weights.get_tensor('transformer.wte.weight')
        if not np.array_equal(embedding.astype(tensors['output.weight'].data.dtype), tensors['output.weight'].data):
            raise ValueError('Tied head differs')
    checked.append('output.weight')
    if set(checked) != set(tensors):
        raise ValueError('Unverified GGUF tensors')
    write_json(args.out, {'schema': 'v4-unified-standard-parent-v1', 'status': 'PASS',
        'parent_hashes': {p.name: digest(p) for p in args.parent.iterdir() if p.is_file()},
        'float_to_f16_tensors_verified': len(checked), 'float_to_f16_comparison': 'exact after F16 rounding; 1D tensors stay F32',
        'f16': {'path': str(f16), 'sha256': digest(f16)}, 'q8_baseline': baseline,
        'quantization_manifest_sha256': digest(quant_manifest_path), 'contract_sha256': digest(contract_path),
        'upstream_provenance': json.loads((args.parent / 'provenance.json').read_text(encoding='utf-8')),
        'limitation': 'Inherited upstream source was quantized; this is preserved v4 float master, not original unquantized zenz recovery',
        'inspector_sha256': digest(__file__)})
    print(f'PASS: {len(checked)} tensors; Standard Q8 {baseline["correct_480"]}/480 baseline bound')


if __name__ == '__main__':
    main()
