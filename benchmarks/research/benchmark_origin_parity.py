# SPDX-License-Identifier: Apache-2.0
"""Compare HF and owned llama.cpp CPU inference for Origin wiring fixtures.

Never connects to the installed IME. The HTTP process uses a fresh local port
and is terminated by process identity in finally. Latency includes full output.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.request

from origin_contract import OriginCodec, digest, read_rows, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--gguf', type=Path, required=True)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--server', type=Path, default=Path('ZeroConvertIME-windows/llama_cpu/llama-server.exe'))
    parser.add_argument('--reference-diagnostic', type=Path, help='Bounded HF task diagnostic instead of full T0 train/val generation')
    parser.add_argument('--concurrent-training', action='store_true')
    parser.add_argument('--compare-quantized-quality', action='store_true',
                        help='Report task quality/differences; exact HF output parity is not required')
    args = parser.parse_args()
    if args.compare_quantized_quality and not args.reference_diagnostic:
        parser.error('Quantized quality comparison requires a bounded HF reference diagnostic')
    if args.out.exists():
        raise FileExistsError(args.out)
    codec = OriginCodec(args.run / 'tokenizer.model')
    manifest = json.loads((args.run / 'run-manifest.json').read_text(encoding='utf-8'))
    if digest(args.run / 'tokenizer.model') != manifest['tokenizer_sha256']:
        raise ValueError('Tokenizer hash mismatch')
    if any(digest(args.dataset / name) != expected for name, expected in manifest['dataset_hashes'].items()):
        raise ValueError('Dataset identity mismatch')
    groups = []
    if args.reference_diagnostic:
        diagnostic = json.loads(args.reference_diagnostic.read_text(encoding='utf-8'))
        if diagnostic['manifest_sha256'] != digest(args.run / 'run-manifest.json') or diagnostic['validation_sha256'] != manifest['dataset_hashes']['val.jsonl']:
            raise ValueError('HF diagnostic provenance mismatch')
        rows = [{'id': r['id'], 'task': r['task'], 'input': r['input'], 'target': r['expected'],
                 'category': r['category'], 'reference_reading': r.get('reference_reading')} for r in diagnostic['cases']]
        groups.append(('validation', rows, {r['id']: r for r in diagnostic['cases']}))
    else:
        metrics = json.loads((args.run / 'metrics.json').read_text(encoding='utf-8'))
        for partition, metric_name, filename in [('train', 'training_overfit_diagnostic', 'train.jsonl'),
                                                  ('validation', 'final_validation', 'val.jsonl')]:
            if not metrics.get(metric_name) or not metrics[metric_name]['outputs']:
                raise ValueError('Use a reference diagnostic or run with both generation diagnostics')
            groups.append((partition, read_rows(args.dataset / filename), {r['id']: r for r in metrics[metric_name]['outputs']}))
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    base = f'http://127.0.0.1:{port}'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(endpoint, body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(base + endpoint, data=data, headers={'Content-Type': 'application/json'})
        with opener.open(req, timeout=10) as response:
            return json.load(response)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    log_path = args.out.with_suffix('.server.log')
    context_length = manifest['arguments']['max_length']
    cmd = [str(args.server.resolve()), '-m', str(args.gguf.resolve()), '--host', '127.0.0.1',
           '--port', str(port), '-c', str(context_length), '-t', '4', '-ngl', '0', '--no-webui']
    with log_path.open('w', encoding='utf-8') as log:
        process = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            deadline = time.monotonic() + 45
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f'Owned server exited: {log_path}')
                try:
                    request('/health')
                    break
                except (OSError, ValueError):
                    if time.monotonic() >= deadline:
                        raise TimeoutError(f'Owned server did not become healthy: {log_path}')
                    time.sleep(0.2)
            probes = ['  ASCII ＡＢＣ　１２３\n\t', 'か\u3099', '👩🏽\u200d💻𠮷󠄀',
                      '<task_predict><out><keep><eot>', '▁', 'a ▁ b']
            token_checks = []
            for text in probes:
                expected = codec.text(text)
                native = request('/tokenize', {'content': text, 'add_special': False, 'parse_special': False})['tokens']
                # Port of OriginCodec's literal U+2581 handling to runtime requests.
                adapted = []
                for i, part in enumerate(text.split('▁')):
                    if i:
                        adapted.extend(codec.sp.piece_to_id(f'<0x{b:02X}>') for b in '▁'.encode())
                    if part:
                        adapted.extend(request('/tokenize', {'content': part, 'add_special': False, 'parse_special': False})['tokens'])
                # Installed b4846 ignores the newer parse_special option. Never
                # send literal tag strings to its text tokenizer: provide IDs.
                literal_control = any(tag in text for tag in [*codec.ids, '<bos>', '<eot>', '<pad>', '<unk>'])
                if literal_control:
                    adapted = codec.text(text)
                decoded = request('/detokenize', {'tokens': adapted})['content']
                token_checks.append({'text': text, 'native_exact': native == expected,
                                     'adapted_exact': adapted == expected and decoded == text,
                                     'uses_origin_ids_for_literal_tags': literal_control,
                                     'detokenized': decoded, 'expected': expected, 'actual': adapted})
            cases = []
            for partition, rows, hf in groups:
                for row in rows:
                    start = time.perf_counter()
                    prompt = codec.prompt(row)
                    response = request('/completion', {'prompt': prompt, 'temperature': 0,
                        'seed': 42, 'n_predict': min(96, context_length - len(prompt)), 'cache_prompt': False, 'return_tokens': True,
                        'repeat_penalty': 1.0, 'special': True})
                    latency = (time.perf_counter() - start) * 1000
                    tokens = response['tokens']
                    # llama-server omits the terminating EOS from response tokens.
                    ended = response.get('stop_type') == 'eos' or response.get('stopped_eos', False)
                    if ended and (not tokens or tokens[-1] != codec.eos_id):
                        tokens = tokens + [codec.eos_id]
                    actual = codec.decode_answer(tokens)
                    cases.append({'id': row['id'], 'partition': partition, 'task': row['task'],
                        'category': row.get('category', 'unspecified'), 'input': row['input'],
                        'reference_reading': row.get('reference_reading'),
                        'expected': row['target'], 'actual': actual, 'hf': hf[row['id']]['actual'],
                        'hf_agrees': actual == hf[row['id']]['actual'], 'exact': actual == row['target'],
                        'hf_tokens_agree': tokens == hf[row['id']]['tokens'] if 'tokens' in hf[row['id']] else None,
                        'latency_ms': latency, 'stop_type': response.get('stop_type'),
                        'truncated': response.get('truncated'), 'tokens': tokens})
            summary = {}
            for partition in sorted({r['partition'] for r in cases}):
                subset = [r for r in cases if r['partition'] == partition]
                summary[partition] = {'count': len(subset), 'exact': sum(r['exact'] for r in subset),
                    'hf_agreement': sum(r['hf_agrees'] for r in subset),
                    'hf_token_agreement': sum(r['hf_tokens_agree'] is True for r in subset),
                    'by_task': {task: {'count': sum(r['task'] == task for r in subset),
                                      'exact': sum(r['task'] == task and r['exact'] for r in subset)}
                                for task in sorted({r['task'] for r in subset})}}
            from evaluate_origin_tasks import summarize
            write_json(args.out, {'gguf_sha256': digest(args.gguf), 'server_sha256': digest(args.server),
                'code_sha256': digest(Path(__file__)),
                'mode': 'quantized-quality-comparison' if args.compare_quantized_quality else 'exact-hf-cpu-parity',
                'hf_output_equality_required': not args.compare_quantized_quality,
                'run_manifest_sha256': digest(args.run / 'run-manifest.json'), 'command': cmd,
                'conditions': 'CPU, 4 threads, no offload, no cache, greedy; one pass; full-output HTTP latency',
                'reference_diagnostic_sha256': digest(args.reference_diagnostic) if args.reference_diagnostic else None,
                'concurrent_training': args.concurrent_training, 'timing_comparable_to_v4': False,
                'tokenization': token_checks, 'summary': summary, 'task_quality': summarize(cases), 'cases': cases})
            print(json.dumps(summary, indent=2))
            if not all(p['adapted_exact'] for p in token_checks) or (not args.compare_quantized_quality and
                    not all(r['hf_agrees'] and r['hf_tokens_agree'] is not False for r in cases)):
                raise ValueError(f'Parity mismatch; inspect {args.out}')
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


if __name__ == '__main__':
    main()
