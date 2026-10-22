# -*- coding: utf-8 -*-
"""
単語・文節単位 300問 ベンチマーク評価スクリプト
比較対象:
1. 現行 Windows ルール辞書 (TSV + QWERTY変種)
2. 旧 zenz-v3.2-xsmall-q5km.gguf (手元にある素の v3.2-xsmall)
3. 新 特化型 zenz-v3.1-xsmall-30k-q4km.gguf (17.4MB)
"""

import sys
import os
import json
import subprocess
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
from research.benchmark_typo_full_comparison import (
    windows_current_ai_assist, kata_to_hira, LlamaServerInstance
)

def run_word_benchmark():
    val_path = "data/typo_dataset_val_30k.jsonl"
    with open(val_path, "r", encoding="utf-8") as f:
        all_val = [json.loads(line) for line in f if line.strip()]

    # 検証セットから 300 件を厳選
    cases = all_val[:300]
    print(f"Loaded {len(cases)} word/phrase-level test cases.")

    # 1. 現行 Windows ルール辞書
    print("Evaluating 1. Windows Current Rule Dictionary...")
    rule_results = []
    for c in cases:
        hira = kata_to_hira(c["input_reading"])
        pred = windows_current_ai_assist(hira)
        if c["is_clean"]:
            success = (pred == c["expected"] or pred == "")
            is_fp = (pred != "" and pred != c["expected"])
            rule_results.append({"id": c["id"], "is_clean": True, "success": not is_fp, "is_fp": is_fp, "pred": pred})
        else:
            success = (pred == c["expected"])
            rule_results.append({"id": c["id"], "is_clean": False, "success": success, "pred": pred})

    # 2. 旧 zenz-v3.2-xsmall-q5km.gguf (手元の素モデル)
    old_model = 'artifacts/models/zenz-v3.2-xsmall-q5km.gguf'
    print(f"Evaluating 2. Old Base zenz-v3.2-xsmall ({os.path.basename(old_model)})...")
    srv_old = LlamaServerInstance(old_model, 8102)
    srv_old.start()
    old_results = []
    try:
        for c in cases:
            pred = srv_old.complete(c["prompt"])
            success = (pred == c["expected"])
            old_results.append({"id": c["id"], "is_clean": c["is_clean"], "success": success, "is_fp": (c["is_clean"] and not success), "pred": pred})
    finally:
        srv_old.stop()

    # 3. 新 特化型 zenz-v3.1-xsmall-30k-q4km.gguf (17.4MB)
    new_model = 'artifacts/models/typo-corrector-xsmall-30k-q4km.gguf'
    print(f"Evaluating 3. New Word-Level Typo Corrector ({os.path.basename(new_model)})...")
    srv_new = LlamaServerInstance(new_model, 8103)
    srv_new.start()
    new_results = []
    try:
        for c in cases:
            pred = srv_new.complete(c["prompt"])
            success = (pred == c["expected"])
            new_results.append({"id": c["id"], "is_clean": c["is_clean"], "success": success, "is_fp": (c["is_clean"] and not success), "pred": pred})
    finally:
        srv_new.stop()

    def get_summary(res):
        typo = [r for r in res if not r["is_clean"]]
        clean = [r for r in res if r["is_clean"]]
        t_acc = sum(1 for r in typo if r["success"]) / len(typo) * 100
        c_acc = sum(1 for r in clean if r["success"]) / len(clean) * 100
        fp = sum(1 for r in clean if r.get("is_fp", False)) / len(clean) * 100
        return t_acc, sum(1 for r in typo if r["success"]), len(typo), c_acc, fp

    print("\n" + "="*85)
    print("       【最適化後】単語・文節単位 300問 ベンチマーク総合比較結果")
    print("="*85)
    header = f"{'モデル / 方式':<28} | {'サイズ':<7} | {'タイポ補正正解率':<14} | {'正常入力保持率':<12} | {'過剰補正率':<10}"
    print(header)
    print("-" * 85)

    for name, sz, r in [
        ("現行 Windows ルール辞書", "0 MB", rule_results),
        ("手元の旧 zenz-v3.2-xsmall", "20.9 MB", old_results),
        ("★ 新 特化 xsmall-30k (LoRA)", "17.4 MB", new_results),
    ]:
        t_acc, corr, tot, c_acc, fp = get_summary(r)
        print(f"{name:<28} | {sz:<7} | {t_acc:>6.1f}% ({corr:3d}/{tot}) | {c_acc:>10.1f}% | {fp:>8.1f}%")
    print("="*85)

if __name__ == "__main__":
    run_word_benchmark()
