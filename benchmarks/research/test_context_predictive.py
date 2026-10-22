# -*- coding: utf-8 -*-
import urllib.request
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

prompt = """文脈と入力途中の文字列を読み取り、それに続く自然なフレーズ（先読み補完）を出力してください。結果の続きの文字列だけを出力してください。

文脈: 取引先への返信メールの冒頭で挨拶する。
入力途中: いつもお世話に
続き:"""

for model in ['lfm2.5-230m@q4_k_m', 'liquidai/lfm2.5-350m', 'lfm2.5-1.2b-instruct']:
    req = urllib.request.Request(
        'http://127.0.0.1:1234/v1/chat/completions',
        headers={'Content-Type': 'application/json'},
        data=json.dumps({
            'model': model,
            'messages': [{'role': 'user', 'content': prompt}],
            'max_tokens': 16,
            'temperature': 0.0,
            'stop': ['\n']
        }).encode('utf-8')
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            res = json.loads(resp.read().decode('utf-8'))
            out = res['choices'][0]['message']['content'].strip()
            print(f"{model}: {out}")
    except Exception as e:
        print(f"{model} error: {e}")
