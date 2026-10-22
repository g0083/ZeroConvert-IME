# -*- coding: utf-8 -*-
import urllib.request
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

test_cases = [
    {
        "ctx": "仕事がやっと終わった。同僚に",
        "variants": [
            ("オツクレサマ", "タイポ: オツクレサマ"),
            ("オツカレサマ", "正解候補: オツカレサマ")
        ]
    },
    {
        "ctx": "朝の新聞社で記者が",
        "variants": [
            ("シンブミヲヨム", "タイポ: シンブミヲヨム"),
            ("シンブンヲヨム", "正解候補: シンブンヲヨム")
        ]
    },
    {
        "ctx": "パソコンの調子が悪いので",
        "variants": [
            ("サイキドスル", "タイポ: サイキドスル"),
            ("サイキドウスル", "正解候補: サイキドウスル")
        ]
    },
    {
        "ctx": "取引先へのお願いの連絡で、",
        "variants": [
            ("ヨロシキョオネガイ", "タイポ: ヨロシキョオネガイ"),
            ("ヨロシクオネガイ",   "正解候補: ヨロシクオネガイ")
        ]
    },
    {
        "ctx": "お昼のチャットで、",
        "variants": [
            ("キンニチハ", "タイポ: キンニチハ"),
            ("コンニチハ", "正解候補: コンニチハ")
        ]
    }
]

print("=== Zenzai ニューラル対数尤度 (Log-Likelihood) によるAI候補選択 ===")
for case in test_cases:
    ctx = case["ctx"]
    print(f"\n文脈: 「{ctx}」")
    ranked = []
    for kata, label in case["variants"]:
        prompt = f"\uEE02{ctx}\uEE00{kata}\uEE01"
        req = urllib.request.Request(
            "http://127.0.0.1:8090/completion",
            headers={"Content-Type": "application/json"},
            data=json.dumps({"prompt": prompt, "n_predict": 8, "n_probs": 1}).encode("utf-8")
        )
        with urllib.request.urlopen(req) as resp:
            d = json.loads(resp.read().decode("utf-8"))
            out = d.get("content", "").strip()
            probs = d.get("completion_probabilities", [])
            avg_logprob = sum(p["logprob"] for p in probs) / max(1, len(probs))
            ranked.append((avg_logprob, kata, out, label))

    ranked.sort(key=lambda x: x[0], reverse=True)
    winner = ranked[0]
    for rank, (score, kata, out, label) in enumerate(ranked, 1):
        mark = "★[AI選択 WINNER]" if rank == 1 else "  [LOSE]"
        print(f" {mark} {label:24} -> 変換: [{out:10}] (平均logprob: {score:.4f})")
