# -*- coding: utf-8 -*-
import urllib.request
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

ctx = "雨が激しく降ってきたので、コンビニで"

variants = [
    ("カサオカッタ", "タイポ (オ)"),
    ("カサヲカッタ", "正解 (ヲ)"),
    ("カサカッタ",   "脱落 (オ抜け)")
]

for kata, note in variants:
    prompt = f"\uEE02{ctx}\uEE00{kata}\uEE01"
    req = urllib.request.Request(
        "http://127.0.0.1:8090/completion",
        headers={"Content-Type": "application/json"},
        data=json.dumps({"prompt": prompt, "n_predict": 6, "n_probs": 1}).encode("utf-8")
    )
    with urllib.request.urlopen(req) as resp:
        d = json.loads(resp.read().decode("utf-8"))
        out = d.get("content", "").strip()
        probs = d.get("completion_probabilities", [])
        total_logprob = sum(p["logprob"] for p in probs)
        avg_logprob = total_logprob / max(1, len(probs))
        print(f"[{note}] 入力: {kata} -> 出力: [{out}] | 平均logprob: {avg_logprob:.3f}")
