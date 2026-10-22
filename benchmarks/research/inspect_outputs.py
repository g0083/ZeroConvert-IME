# -*- coding: utf-8 -*-
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

with open('benchmarks/research/benchmark_results.json', 'r', encoding='utf-8') as f:
    res = json.load(f)

print("=== 誤入力補正 サンプル出力 (先頭5件) ===")
for mod in ['Dictionary', 'ZeroConAI', 'LFM-230M', 'LFM-350M', 'LFM-1.2B']:
    details = res['typo'][mod]['details']
    print(f"\n-- {mod} --")
    for d in details[:5]:
        print(f"  入力: {d['input']:12} -> 出力: [{d['output']}] (正解: {d['expected']})")

print("\n=== 入力内容先読み サンプル出力 (先頭5件) ===")
for mod in ['Dictionary', 'ZeroConAI', 'LFM-230M', 'LFM-350M', 'LFM-1.2B']:
    details = res['predictive'][mod]['details']
    print(f"\n-- {mod} --")
    for d in details[:5]:
        print(f"  入力途中: {d['input']:16} -> 出力: [{d['output']}] (期待: {d['expected']})")
