# SPDX-License-Identifier: Apache-2.0
"""Local teacher generation smoke probe; generated candidates never enter training.

Prompts here are original task instructions, with no benchmark sentences or
validation content. Output is untrusted text saved for review, never executed.
"""
import argparse
import json
from pathlib import Path
import socket
import subprocess
import time
import urllib.request

from origin_contract import digest, write_json

PROMPTS = (
    '職場での短い連絡文を4個作ってください。各文は12～30文字、自然な日本語の一文とし、内容は互いに変えてください。挨拶だけの文、番号、解説は不要です。文字列4個のJSON配列だけを返してください。',
    '外出先で家族へ送る短い連絡文を4個作ってください。各文は12～30文字、自然な日本語の一文とし、内容は互いに変えてください。番号、解説は不要です。文字列4個のJSON配列だけを返してください。',
    'パソコンの操作や設定についての短文を4個作ってください。各文は12～30文字、自然な日本語の一文とし、英数字を含む文を2個含めてください。番号、解説は不要です。文字列4個のJSON配列だけを返してください。',
    '家事や買い物についての短いメモを4個作ってください。各文は12～30文字、自然な日本語の一文とし、内容は互いに変えてください。番号、解説は不要です。文字列4個のJSON配列だけを返してください。',
)


def request_json(url, payload=None, timeout=30):
    body = json.dumps(payload, ensure_ascii=False).encode('utf-8') if payload is not None else None
    req = urllib.request.Request(url, data=body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--gguf', type=Path, required=True)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    source = json.loads((args.source / 'teacher-source-manifest.json').read_text(encoding='utf-8'))
    if source['repository'] != 'Qwen/Qwen3-4B-Instruct-2507' or source['license'] != 'apache-2.0':
        raise ValueError('Not the reviewed local teacher')
    # Hash identities describe the actual artifacts; do not assume a filename
    # proves its relationship to the acquired source or its quantization quality.
    model_hash, server_hash = digest(args.gguf), digest(args.server)
    args.out.mkdir(parents=True)
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    command = [str(args.server.resolve()), '-m', str(args.gguf.resolve()), '--host', '127.0.0.1',
               '--port', str(port), '-c', '2048', '-t', '4', '-ngl', '0', '--no-webui']
    base = f'http://127.0.0.1:{port}'
    records = []
    with (args.out / 'server.log').open('x', encoding='utf-8') as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            deadline = time.monotonic() + 180
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f'Owned server exited {process.returncode}; inspect server.log')
                try:
                    request_json(base + '/health', timeout=2)
                    break
                except (OSError, ValueError):
                    if time.monotonic() >= deadline:
                        raise TimeoutError('Owned teacher startup exceeded 180 seconds')
                    time.sleep(.5)
            for index, prompt in enumerate(PROMPTS):
                payload = {'messages': [{'role': 'user', 'content': prompt}], 'temperature': 0,
                           'seed': 42, 'max_tokens': 192, 'stream': False, 'repeat_penalty': 1.0}
                started = time.monotonic()
                response = request_json(base + '/v1/chat/completions', payload, timeout=180)
                choice = response['choices'][0]
                text = choice['message']['content']
                try:
                    parsed = json.loads(text)
                    valid = isinstance(parsed, list) and len(parsed) == 4 and all(isinstance(s, str) and 12 <= len(s) <= 30 for s in parsed)
                except (ValueError, TypeError):
                    valid = False
                records.append({'id': index, 'request': payload, 'response': response,
                                'format_and_length_valid': valid,
                                'wall_seconds': time.monotonic() - started})
                write_json(args.out / 'outputs.json', records)
                print(f'{index + 1}/{len(PROMPTS)} responses saved; format/length={valid}', flush=True)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
    if model_hash != digest(args.gguf) or server_hash != digest(args.server):
        raise ValueError('Artifacts changed during probe')
    write_json(args.out / 'probe-manifest.json', {
        'teacher_source_manifest_sha256': digest(args.source / 'teacher-source-manifest.json'),
        'model_sha256': model_hash, 'server_sha256': server_hash, 'command': command,
        'code_sha256': digest(Path(__file__)), 'response_count': len(records),
        'format_and_length_valid_count': sum(r['format_and_length_valid'] for r in records),
        'outputs_sha256': digest(args.out / 'outputs.json'),
        'status': 'generation smoke probe only; language/reading correctness not adjudicated',
        'used_as_student_training_data': False, 'timing_comparable_to_ime': False,
    })


if __name__ == '__main__':
    main()
