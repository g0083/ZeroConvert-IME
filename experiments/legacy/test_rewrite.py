# -*- coding: utf-8 -*-
import urllib.request
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

test_cases = [
    {
        'context': '駅のホームに大勢の乗客が並んでいた。',
        'raw_ime': '記者が駅に到着した',
        'expected': '汽車が駅に到着した'
    },
    {
        'context': '奥歯の詰め物が取れてしまった。',
        'raw_ime': '今日は医者に行く',
        'expected': '今日歯医者に行く'
    },
    {
        'context': '川の向こう岸へ歩いて渡った。',
        'raw_ime': '端を渡る',
        'expected': '橋を渡る'
    },
    {
        'context': '取引先へのメールを書いている。',
        'raw_ime': '記者の製品を検討しています',
        'expected': '貴社の製品を検討しています'
    },
    {
        'context': '空が暗くなり、雷が聞こえてきた。',
        'raw_ime': '飴が降ってきた',
        'expected': '雨が降ってきた'
    },
    {
        'context': '食事を始める前に手を合わせた。',
        'raw_ime': '橋を持つ',
        'expected': '箸を持つ'
    }
]

prompt_tmpl = """あなたは日本語IMEの文脈同音異義語判定エンジンです。
直前の文脈を考慮し、「変換候補」の中に文脈に合わない同音異義語があれば、文脈に適合する漢字へ1箇所だけ置換してください。
文脈に適合していれば、そのまま出力してください。
余計な単語の追加、削除、言い換えは禁止です。結果の1行だけを出力してください。

例：
文脈：新聞社で働いている。
変換候補：汽車が取材した
出力：記者が取材した

文脈：{context}
変換候補：{raw_ime}
出力："""

for tc in test_cases:
    p = prompt_tmpl.format(context=tc['context'], raw_ime=tc['raw_ime'])
    payload = {
        'model': 'liquidai/lfm2.5-1.2b-instruct',
        'messages': [{'role': 'user', 'content': p}],
        'temperature': 0.0,
        'max_tokens': 32
    }
    req = urllib.request.Request(
        'http://127.0.0.1:1234/v1/chat/completions',
        headers={'Content-Type': 'application/json'},
        data=json.dumps(payload).encode('utf-8')
    )
    with urllib.request.urlopen(req) as resp:
        d = json.loads(resp.read().decode('utf-8'))
        out = d['choices'][0]['message']['content'].strip()
        mark = "O" if out == tc['expected'] else "X"
        print(f"[{mark}] Exp: {tc['expected']} | Got: {out}")
