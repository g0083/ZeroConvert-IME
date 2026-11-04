# SPDX-License-Identifier: Apache-2.0
"""Verify GGUF tensor rounding, literal text roundtrip and greedy token parity."""
import argparse
import json
from pathlib import Path
import sys
import urllib.request

import numpy as np
from safetensors import safe_open
import torch
from transformers import AutoModelForCausalLM

from evaluate_v4_unified import UnifiedServer, hf_generate
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec


def post(port, route, value):
    request = urllib.request.Request(f'http://127.0.0.1:{port}/{route}', data=json.dumps(value).encode(),
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--gguf', type=Path, required=True)
    p.add_argument('--port', type=int, default=8561)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    sys.path.insert(0, str(Path('android/app/src/main/cpp/llama.cpp/gguf-py').resolve()))
    import gguf
    reader = gguf.GGUFReader(str(args.gguf))
    tensors = {t.name: t for t in reader.tensors}
    mapping = gguf.get_tensor_name_map(gguf.MODEL_ARCH.GPT2, 12)
    count = 0
    with safe_open(args.run / 'hf/model.safetensors', framework='numpy') as weights:
        for name in weights.keys():
            target = mapping.get_name(name, try_suffixes=('.weight', '.bias'))
            value = weights.get_tensor(name)
            if name.endswith(('.c_attn.weight', '.c_proj.weight', '.c_fc.weight')):
                value = value.T
            actual = tensors[target].data
            if value.shape != actual.shape or not np.array_equal(value.astype(actual.dtype), actual):
                raise ValueError('GGUF tensor rounding mismatch: ' + name)
            count += 1
        if not np.array_equal(weights.get_tensor('transformer.wte.weight').astype(tensors['output.weight'].data.dtype), tensors['output.weight'].data):
            raise ValueError('Tied head mismatch')
    count += 1
    codec = V4UnifiedCodec(args.run / 'hf')
    server = UnifiedServer(str(args.gguf.resolve()), args.port, 0, 'cpu')
    contract = json.loads(Path('artifacts/results/origin-comparison-contract-20261029-r2.json').read_text(encoding='utf-8'))
    if digest(server.server_exe) != contract['runtime']['sha256']:
        raise ValueError('Fixed CPU executable changed')
    literals = ('  5　５０\t\n', '</s><task_repair><out><missing>', '\uee00\uee01\uee02',
                'か\u3099😀𠮷▁', 'https://example.test/a  b?q=x', 'C:\\Dev\\Cache')
    literal_results, generation = [], []
    torch.set_num_threads(4)
    model = AutoModelForCausalLM.from_pretrained(args.run / 'hf', local_files_only=True,
        dtype=torch.float32, attn_implementation='sdpa').eval()
    probes = [
        {'task': 'convert', 'input': {'ctx': '', 'reading': 'たなのおくへ'}},
        {'task': 'convert', 'input': {'ctx': '準備した資料を', 'reading': 'とどける'}},
        {'task': 'repair', 'input': {'ctx': '', 'raw': 'arigatou', 'pending': ''}},
        {'task': 'repair', 'input': {'ctx': '', 'raw': 'argiatou', 'pending': ''}},
        {'task': 'repair', 'input': {'ctx': '', 'raw': 'arigatok', 'pending': 'k'}},
        {'task': 'predict', 'input': {'ctx': '', 'surface': '資料を届け', 'pending': ''}},
    ]
    try:
        server.start()
        for text in literals:
            ids = codec.text(text)
            actual = post(args.port, 'detokenize', {'tokens': ids})['content']
            encoded = post(args.port, 'tokenize', {'content': text, 'add_special': False, 'parse_special': False})['tokens']
            literal_results.append({'text': text, 'roundtrip': actual == text, 'token_ids_equal': encoded == ids,
                                    'hf_tokens': ids, 'gguf_tokens': encoded})
        for row in probes:
            row = {**row, 'input_method': 'romaji', 'layout': 'JP-QWERTY'}
            if row['task'] == 'repair' and codec.metadata.get('repair_input') == 'observed':
                observed = codec.observed_reading(row['input']['raw'], row['input']['pending'])
                row['input'] = {'ctx': row['input']['ctx'], 'reading': observed, 'pending': row['input']['pending']}
            prompt = codec.prompt(row)
            expected = hf_generate(model, [prompt], 16, 'cpu')[0]
            actual = server.complete_ids(prompt, codec, 16)
            generation.append({'task': row['task'], 'input': row['input'], 'hf_tokens': expected,
                               'gguf_tokens': actual, 'equal': expected == actual})
    finally:
        server.stop()
    result = {'schema': 'v4-unified-parity-v1',
        'status': 'PASS_INTEGER_ID_CONTRACT' if all(r['roundtrip'] for r in literal_results) and all(r['equal'] for r in generation) else 'FAIL',
        'native_text_tokenizer_status': 'PASS' if all(r['token_ids_equal'] for r in literal_results) else 'FAIL_DO_NOT_USE_STRING_PROMPTS',
        'contract': 'V4UnifiedCodec on host -> integer IDs; native server text tokenization is a separate diagnostic',
        'tensors_exact_after_precision_cast': count,
        'hf_sha256': digest(args.run / 'hf/model.safetensors'), 'gguf_sha256': digest(args.gguf),
        'runtime_sha256': digest(server.server_exe), 'literal_results': literal_results, 'generation': generation,
        'limits': ['Six greedy probes do not prove exhaustive numerical equivalence',
                   'F16 rounding and F32 runtime equality are separate conditions'], 'verifier_sha256': digest(__file__)}
    write_json(args.out, result)
    print(json.dumps({k:v for k,v in result.items() if k not in ('generation', 'literal_results')}, indent=2))


if __name__ == '__main__':
    main()
