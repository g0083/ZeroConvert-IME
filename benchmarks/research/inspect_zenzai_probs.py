# -*- coding: utf-8 -*-
import urllib.request
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

prompt = '\uEE02雨が激しく降ってきたので、コンビニで\uEE00カサオカッタ\uEE01'
req = urllib.request.Request(
    'http://127.0.0.1:8090/completion',
    headers={'Content-Type': 'application/json'},
    data=json.dumps({
        'prompt': prompt,
        'n_predict': 6,
        'temperature': 0.0,
        'n_probs': 5,
        'stop': ['</s>', '\uEE00', '\uEE01', '\uEE02']
    }).encode('utf-8')
)
with urllib.request.urlopen(req) as resp:
    res = json.loads(resp.read().decode('utf-8'))
    print("Content:", repr(res.get('content')))
    comp_probs = res.get('completion_probabilities', [])
    for idx, step in enumerate(comp_probs):
        toks = step.get('probs', [])
        summary = []
        for t in toks[:4]:
            summary.append(f"{t['tok_str']}({t['prob']:.3f})")
        print(f"  Token {idx}: {' | '.join(summary)}")
