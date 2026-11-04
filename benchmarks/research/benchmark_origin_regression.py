# SPDX-License-Identifier: Apache-2.0
"""Score Origin on the same development cases/scorer/CPU protocol as v4.

Only prompt encoding changes. Uses integer IDs; no untrusted special parsing.
This development score alone never establishes the complete performance goal.
"""
import argparse
import json
from pathlib import Path, PureWindowsPath
import urllib.request

from benchmark_kana_kanji_models import LlamaServerInstance, evaluate_model, load_cases
from origin_contract import OriginCodec, digest, write_json


def normalized_hash_paths(values):
    normalized = {}
    for name, value in values.items():
        key = PureWindowsPath(name).as_posix()
        if key in normalized and normalized[key] != value:
            raise ValueError('Conflicting frozen evaluation path hashes')
        normalized[key] = value
    return normalized


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--gguf', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--contract', type=Path, default=Path('artifacts/results/origin-comparison-contract-20261029-r2.json'))
    parser.add_argument('--base-port', type=int, default=8490)
    parser.add_argument('--backend', choices=('cpu', 'vulkan', 'cuda'), default='cpu')
    parser.add_argument('--n-gpu-layers', type=int, default=0)
    parser.add_argument('--concurrent-training', action='store_true', help='Quality diagnostic only; disable timing comparisons')
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    manifest = json.loads((args.run / 'run-manifest.json').read_text(encoding='utf-8'))
    codec = OriginCodec(args.run / 'tokenizer.model')
    if digest(args.run / 'tokenizer.model') != manifest['tokenizer_sha256']:
        raise ValueError('Tokenizer hash mismatch')
    contract = json.loads(args.contract.read_text(encoding='utf-8'))
    evaluation_hashes = normalized_hash_paths(contract['development_evaluation_hashes'])
    if args.backend == 'cpu' and digest(Path(contract['runtime']['path'])) != contract['runtime']['sha256']:
        raise ValueError('Frozen CPU runtime changed')
    class OriginServer(LlamaServerInstance):
        def conversion_prompt(self, context, katakana_reading):
            reading = ''.join(chr(ord(c) - 96) if 'ァ' <= c <= 'ヶ' else c for c in katakana_reading)
            return codec.prompt({'task': 'convert', 'input': {'ctx': context, 'reading': reading}})

        def complete(self, prompt, max_tokens=96):
            data = json.dumps({'prompt': prompt, 'n_predict': max_tokens, 'temperature': 0,
                               'seed': 42, 'cache_prompt': False, 'return_tokens': True,
                               'repeat_penalty': 1.0}).encode()
            request = urllib.request.Request(f'http://127.0.0.1:{self.port}/completion', data=data,
                                             headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=30) as response:
                result = json.load(response)
            tokens = result['tokens']
            ended = result.get('stop_type') == 'eos' or result.get('stopped_eos', False)
            if ended and (not tokens or tokens[-1] != codec.eos_id):
                tokens = tokens + [codec.eos_id]
            answer = codec.decode_answer(tokens)
            result['content'] = answer['text'] if answer['action'] == 'emit' else ''
            result['origin_action'] = answer['action']
            return result
    results = {}
    for i, filename in enumerate(('kana_kanji_llm_testset_5x.jsonl', 'kana_kanji_hard_context_testset.jsonl')):
        path = Path('data') / filename
        if digest(path) != evaluation_hashes[path.as_posix()]:
            raise ValueError('Frozen development cases changed')
        print(f'Evaluating {filename}', flush=True)
        result = evaluate_model(str(args.gguf.resolve()), args.base_port + i, load_cases(str(path)),
                                args.n_gpu_layers, args.backend, server_factory=OriginServer)
        result['testset_sha256'] = digest(path)
        results[filename] = result
        print(f'{result["perfect_count"]}/{result["total_cases"]}; p95={result["p95_latency_ms"]} ms', flush=True)
    write_json(args.out, {'schema': 'origin-regression-v1', 'condition': manifest['condition'],
        'run_manifest_sha256': digest(args.run / 'run-manifest.json'), 'contract_sha256': digest(args.contract),
        'tokenizer_sha256': manifest['tokenizer_sha256'], 'results': results,
        'correct_480': sum(r['perfect_count'] for r in results.values()),
        'concurrent_training': args.concurrent_training, 'timing_comparable_to_v4': not args.concurrent_training,
        'full_goal_status': 'unproven: sealed/task-specific/real-IME gates must also pass'})


if __name__ == '__main__':
    main()
