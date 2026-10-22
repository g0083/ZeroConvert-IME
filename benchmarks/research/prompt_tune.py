# -*- coding: utf-8 -*-
import urllib.request
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

test_prompts = [
    ('A_Strict', '「おつくれさま」の誤字を修正した日本語単語のみを出力せよ。'),
    ('B_Proofread', '以下の誤字を修正してください。\n誤: おつくれさま\n正:'),
    ('C_IME', 'あなたは日本語IMEです。タイポを補正して変換してください。\n入力: おつくれさま\n変換:')
]

for model in ['lfm2.5-230m@q4_k_m', 'liquidai/lfm2.5-350m']:
    print(f"=== Model: {model} ===")
    for name, p in test_prompts:
        req = urllib.request.Request(
            'http://127.0.0.1:1234/v1/chat/completions',
            headers={'Content-Type': 'application/json'},
            data=json.dumps({
                'model': model,
                'messages': [{'role': 'user', 'content': p}],
                'max_tokens': 20,
                'temperature': 0.0,
                'stop': ['\n', '入力:', '誤:']
            }).encode('utf-8')
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                res = json.loads(resp.read().decode('utf-8'))
                text = res['choices'][0]['message']['content'].strip()
                print(f"  [{name}]: {text}")
        except Exception as e:
            print(f"  [{name}] Error: {e}")
