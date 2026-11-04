# SPDX-License-Identifier: Apache-2.0
"""Run the pinned Windows policy evaluator against an isolated unified model.

Legacy HTTP is adapted only for this research benchmark. No product/IME changes.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import urllib.request

from benchmark_kana_kanji_models import LlamaServerInstance
from evaluate_v4_unified import UnifiedServer
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec, hira


def parse_prompt(prompt):
    prefix, rest = prompt.split('\uee00', 1)
    reading, suffix = rest.rsplit('\uee01', 1)
    if suffix or ('\uee02' in prefix and not prefix.startswith('\uee02')):
        raise ValueError('Unexpected legacy prompt framing')
    return prefix.removeprefix('\uee02'), hira(reading)


def legacy_reply(answer, observed, tokens):
    valid = answer['action'] != 'invalid'
    # Echoing an unchanged observation is the pinned policy's explicit veto of
    # fuzzy fallback. An empty response would incorrectly mean model failure.
    content = observed if answer['action'] in ('keep', 'abstain') else answer['text']
    return {'content': content, 'stopped_eos': valid, 'stopped_limit': not valid,
            'stop_type': 'eos' if valid else 'limit', 'tokens_predicted': len(tokens),
            'unified_action': answer['action'], 'unified_repair_output': 'surface'}


def reconstructed_keep(answer, observed, original):
    return answer['action'] == 'keep' and original is not None and original != observed


def summarize(report):
    def accepted(row, output):
        return output is not None and output in [row['expected'], *(row.get('acceptable') or [])]
    cases = report['cases']
    typo = [r for r in cases if r['is_clean'] is not True]
    clean = [r for r in cases if r['is_clean'] is True]
    return {'total': len(cases), 'typo_total': len(typo), 'clean_total': len(clean),
            'typo_correct_suggestion': sum(accepted(r, r['correction']) for r in typo),
            'typo_wrong_suggestion': sum(r['correction'] is not None and not accepted(r, r['correction']) for r in typo),
            'typo_correct_available': sum(accepted(r, r['baseline']) or accepted(r, r['correction']) for r in typo),
            'clean_wrong_suggestion': sum(r['correction'] is not None and not accepted(r, r['correction']) for r in clean),
            'wrong_auto_apply': sum(r['auto_apply'] and not accepted(r, r['correction']) for r in cases),
            'crashed_cases': sum(r['crashed'] for r in cases),
            'failed_completion_requests': sum(c['response'] is None for r in cases for c in r['calls'])}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--gguf', type=Path, required=True)
    p.add_argument('--fixed-normal', type=Path, help='If absent, convert and repair share the same model process')
    p.add_argument('--cases', type=Path, default=Path('data/typo_benchmark_300.jsonl'))
    p.add_argument('--runner', type=Path, default=Path('engine/rust/target/debug/examples/evaluate_corrector.exe'))
    p.add_argument('--base-port', type=int, default=8571)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--concurrent-training', action='store_true')
    a = p.parse_args()
    if a.out.exists():
        raise FileExistsError(a.out)
    expected_runner = json.loads(Path('artifacts/results/corrector-v32-xsmall-300.json').read_text(encoding='utf-8'))['identity']['runner_sha256']
    if digest(a.runner) != expected_runner:
        raise ValueError('Policy runner differs from the current-corrector reference')
    codec = V4UnifiedCodec(a.run / 'hf')
    if codec.metadata.get('repair_output') != 'surface' or codec.metadata.get('repair_input') != 'observed':
        raise ValueError('Surface/observed codec required')
    native = UnifiedServer(str(a.gguf.resolve()), a.base_port, 0, 'cpu')
    normal = LlamaServerInstance(str(a.fixed_normal.resolve()), a.base_port+3, 0, 'cpu') if a.fixed_normal else None
    adapters, threads, trace = [], [], []
    lock = threading.Lock()
    state_lock = threading.Lock()
    current_original = {'reading': None}

    def convert(ctx, observed, payload):
        if normal:
            # Forward the identical normal payload used by the reference runner.
            request = urllib.request.Request(f'http://127.0.0.1:{normal.port}/completion',
                data=json.dumps(payload).encode('utf-8'), headers={'Content-Type': 'application/json'})
            with urllib.request.urlopen(request, timeout=5) as response:
                return json.load(response)
        row = {'task': 'convert', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
               'input': {'ctx': ctx, 'reading': observed}}
        prompt = codec.prompt(row)
        with lock:
            tokens = native.complete_ids(prompt, codec, min(int(payload.get('n_predict', 256)), 256, 1024-len(prompt)))
        answer = codec.decode_answer(tokens, 'convert')
        if answer['action'] != 'emit':
            raise ValueError('Unified normal conversion returned no usable baseline')
        return legacy_reply(answer, observed, tokens)

    def make_handler(task):
        class Handler(BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.0'
            def log_message(self, *_):
                pass
            def do_POST(self):
                try:
                    length = int(self.headers.get('Content-Length', '0'))
                    if self.path != '/completion' or not 0 < length <= 128*1024:
                        raise ValueError('Unsupported benchmark request')
                    payload = json.loads(self.rfile.read(length))
                    ctx, observed = parse_prompt(payload['prompt'])
                    if task == 'convert':
                        result = convert(ctx, observed, payload)
                        with state_lock:
                            current_original['reading'] = observed
                        data = json.dumps(result, ensure_ascii=False).encode('utf-8')
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Content-Length', str(len(data)))
                        self.end_headers()
                        self.wfile.write(data)
                        return
                    with state_lock:
                        original = current_original['reading']
                    row = {'task': task, 'input_method': 'romaji', 'layout': 'JP-QWERTY',
                           'input': {'ctx': ctx, 'reading': observed, **({'pending': ''} if task == 'repair' else {})}}
                    prompt = codec.prompt(row)
                    budget = min(int(payload.get('n_predict', 96)), 256, 1024-len(prompt))
                    if budget <= 0:
                        raise ValueError('Position budget exhausted')
                    started = time.perf_counter()
                    with lock:
                        tokens = native.complete_ids(prompt, codec, budget)
                    answer = codec.decode_answer(tokens, task)
                    if task == 'convert' and answer['action'] != 'emit':
                        raise ValueError('Unified normal conversion returned no usable baseline')
                    result = legacy_reply(answer, observed, tokens)
                    if reconstructed_keep(answer, observed, original):
                        # The policy already changed the reading before invoking
                        # the model. Keep that repair, then obtain its surface.
                        # Inputs come only from observed requests, never labels.
                        converted = convert(ctx, observed, {**payload, 'n_predict': 256, 'seed': 42, 'cache_prompt': False})
                        result = {**converted, 'unified_action': 'keep', 'upstream_reconstruction': True}
                    trace.append({'task': task, 'ctx': ctx, 'observed': observed, 'answer': answer,
                                  'original_observed': original, 'upstream_reconstruction': reconstructed_keep(answer, observed, original),
                                  'seconds': time.perf_counter()-started})
                    data = json.dumps(result, ensure_ascii=False).encode('utf-8')
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass
                except Exception as error:
                    trace.append({'task': task, 'error': str(error)})
                    self.send_error(500, str(error))
        return Handler

    raw_path = a.out.with_suffix('.runner.json')
    if raw_path.exists():
        raise FileExistsError(raw_path)
    try:
        native.start()
        if normal:
            normal.start()
        tasks = [('repair', a.base_port+1), ('convert', a.base_port+2)]
        for task, port in tasks:
            server = ThreadingHTTPServer(('127.0.0.1', port), make_handler(task))
            adapters.append(server)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            threads.append(thread)
        normal_port = a.base_port+2
        command = [str(a.runner.resolve()), str(a.cases.resolve()), str(a.base_port+1), str(normal_port),
                   str(Path('ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin').resolve()), str(raw_path.resolve())]
        result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace',
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        if result.returncode:
            raise RuntimeError(f'Pinned policy evaluator failed: {result.stderr}')
    finally:
        for server in adapters:
            server.shutdown()
            server.server_close()
        for thread in threads:
            thread.join(timeout=5)
        if normal:
            normal.stop()
        native.stop()
        write_json(a.out.with_suffix('.adapter-trace.json'), trace)
    report = json.loads(raw_path.read_text(encoding='utf-8'))
    report['summary'] = summarize(report)
    report['identity'] = {'runner_sha256': digest(a.runner), 'model_sha256': digest(a.gguf),
                          'cases_sha256': digest(a.cases), 'codec_sha256': digest(a.run/'hf/v4-unified-codec.json'),
                          'fixed_normal_sha256': digest(a.fixed_normal) if a.fixed_normal else None,
                          'normal_and_repair_share_model_process': not bool(normal),
                          'reconstructed_keep_obtains_normal_surface': True,
                          'runtime_sha256': digest(native.server_exe), 'adapter_code_sha256': digest(__file__)}
    report['limits'] = ['Pinned offline policy; not Swift/TSF or actual editing',
                        'Legacy HTTP adapter overhead; no direct latency comparison',
                        'Unchanged original input vetoes fuzzy fallback; keep after upstream reading reconstruction obtains normal surface',
                        'Existing development 300 questions; not sealed cases']
    report['concurrent_training'] = a.concurrent_training
    report['timing_comparable'] = False
    write_json(a.out, report)
    print(json.dumps(report['summary']), flush=True)


if __name__ == '__main__':
    main()
