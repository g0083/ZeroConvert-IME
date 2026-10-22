# -*- coding: utf-8 -*-
"""
300問 大規模タイポ補正ベンチマーク評価スクリプト
比較対象:
1. 現行の辞書式ルールベース (TYPO_TABLE + 前方一致)
2. 旧モデル: 素の zenz-v3.2-small-q4km.gguf
3. 新モデル: LoRAファインチューニング済み typo-corrector-q4km.gguf
"""

from pathlib import Path
REPO_ROOT = Path(__file__).resolve().parents[2]


import sys
import os
import json
import time
import subprocess
import urllib.request
from typing import Dict, List, Any

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

# 1. 現行の辞書式ルールベース (ai_assist.rs より移植)
TYPO_TABLE = [
    ("おつくれさま", "お疲れ様"),
    ("おつかれさめ", "お疲れ様"),
    ("おつかれさまてす", "お疲れ様です"),
    ("ありかとう", "ありがとう"),
    ("ありがとうごさい", "ありがとうござい"),
    ("ありかとうこさい", "ありがとうござい"),
    ("ありかとうごさま", "ありがとうござい"),
    ("こんにちわ", "こんにちは"),
    ("こんいちは", "こんにちは"),
    ("こんには", "こんにちは"),
    ("よろしくおねがいしまふ", "よろしくお願いします"),
    ("よろしくおねかい", "よろしくお願い"),
    ("よろしき", "よろしく"),
    ("すみませｎ", "すみません"),
    ("すいません", "すみません"),
    ("ごめんなさいい", "ごめんなさい"),
    ("ごめなさい", "ごめんなさい"),
    ("おねがいしまふ", "お願いします"),
    ("おねがいしあす", "お願いします"),
    ("もうしわけありませｎ", "申し訳ありません"),
    ("もうしわけございませｎ", "申し訳ございません"),
    ("りょうかいしまいた", "了解しました"),
    ("しょうちしまいた", "承知しました"),
    ("かくにんしまいた", "確認しました"),
]

def rule_based_correction(reading: str) -> str:
    # 完全一致
    for typo, corr in TYPO_TABLE:
        if reading == typo:
            return corr
    # 前方一致
    for typo, corr in TYPO_TABLE:
        if reading.startswith(typo):
            rem = reading[len(typo):]
            return corr + rem
    return ""

# LLM 推論クライアント (llama-server 経由)
SERVER_EXE = 'ZeroConvertIME-windows/llama_cuda/llama-server.exe'

class LlamaServerInstance:
    def __init__(self, model_path: str, port: int = 8089):
        self.model_path = model_path
        self.port = port
        self.proc = None

    def start(self):
        cmd = [
            SERVER_EXE,
            "-m", self.model_path,
            "--port", str(self.port),
            "-ngl", "99",
            "-c", "1024",
            "--temp", "0.0"
        ]
        self.proc = subprocess.Popen(
            cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )
        print(f"Waiting for llama-server on port {self.port} ({os.path.basename(self.model_path)})...")
        for _ in range(60):
            try:
                req = urllib.request.Request(f"http://127.0.0.1:{self.port}/health")
                with urllib.request.urlopen(req, timeout=1) as resp:
                    if resp.status == 200:
                        print("Server is ready.")
                        return
            except:
                time.sleep(0.5)
        raise RuntimeError("Failed to start llama-server")

    def stop(self):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except:
                self.proc.kill()
            self.proc = None
            print("Server stopped.")

    def complete(self, prompt: str, max_tokens: int = 16) -> str:
        url = f"http://127.0.0.1:{self.port}/completion"
        data = json.dumps({
            "prompt": prompt,
            "n_predict": max_tokens,
            "temperature": 0.0,
            "stop": ["\uEE00", "\uEE01", "\uEE02", "\n", "</s>"]
        }).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                res = json.loads(resp.read().decode("utf-8"))
                return res.get("content", "").strip()
        except Exception as e:
            return ""

def evaluate_models():
    testset_path = "data/typo_benchmark_300.jsonl"
    with open(testset_path, "r", encoding="utf-8") as f:
        cases = [json.loads(line) for line in f if line.strip()]

    print(f"Loaded {len(cases)} test cases.")

    # 1. 辞書式ルールの評価
    print("\n=== Evaluating Method 1: Rule-Based Dictionary (TYPO_TABLE) ===")
    rule_results = []
    for c in cases:
        hira_input = c["target_reading"] if c["is_clean"] else c["input_reading"]
        # カタカナからひらがなへ変換して辞書引き
        hira = "".join([chr(ord(ch) - 0x60) if 0x30A1 <= ord(ch) <= 0x30F6 else ch for ch in c["input_reading"]])
        pred = rule_based_correction(hira)
        
        # 評価判定
        if c["is_clean"]:
            # 正常入力に対する過剰補正チェック (空または同じならOK)
            is_fp = (pred != "" and pred != c["expected"])
            rule_results.append({"id": c["id"], "type": c["typo_type"], "is_clean": True, "success": not is_fp, "is_fp": is_fp, "pred": pred})
        else:
            # タイポ補正成功チェック
            success = (pred == c["expected"])
            rule_results.append({"id": c["id"], "type": c["typo_type"], "is_clean": False, "success": success, "pred": pred})

    # 2. 旧モデル (素の zenz-v3.2-small-q4km) の評価
    old_model_path = str(REPO_ROOT / 'ZeroConvertIME-windows/zenz.gguf')
    print(f"\n=== Evaluating Method 2: Old Base Model ({os.path.basename(old_model_path)}) ===")
    old_server = LlamaServerInstance(old_model_path, port=8089)
    old_server.start()
    old_results = []
    try:
        for idx, c in enumerate(cases):
            pred = old_server.complete(c["prompt"])
            if c["is_clean"]:
                success = (pred == c["expected"])
                is_fp = not success
                old_results.append({"id": c["id"], "type": c["typo_type"], "is_clean": True, "success": success, "is_fp": is_fp, "pred": pred})
            else:
                success = (pred == c["expected"])
                old_results.append({"id": c["id"], "type": c["typo_type"], "is_clean": False, "success": success, "pred": pred})
            if (idx + 1) % 50 == 0:
                print(f"  Processed {idx + 1}/{len(cases)} cases...")
    finally:
        old_server.stop()

    # 3. 新モデル (特化型 typo-corrector-q4km) の評価
    new_model_path = 'artifacts/models/typo-corrector-q4km.gguf'
    print(f"\n=== Evaluating Method 3: New Typo Corrector Model ({os.path.basename(new_model_path)}) ===")
    new_server = LlamaServerInstance(new_model_path, port=8090)
    new_server.start()
    new_results = []
    try:
        for idx, c in enumerate(cases):
            pred = new_server.complete(c["prompt"])
            if c["is_clean"]:
                success = (pred == c["expected"])
                is_fp = not success
                new_results.append({"id": c["id"], "type": c["typo_type"], "is_clean": True, "success": success, "is_fp": is_fp, "pred": pred})
            else:
                success = (pred == c["expected"])
                new_results.append({"id": c["id"], "type": c["typo_type"], "is_clean": False, "success": success, "pred": pred})
            if (idx + 1) % 50 == 0:
                print(f"  Processed {idx + 1}/{len(cases)} cases...")
    finally:
        new_server.stop()

    # 結果の集計
    def calc_stats(results):
        typo_cases = [r for r in results if not r["is_clean"]]
        clean_cases = [r for r in results if r["is_clean"]]
        
        typo_acc = sum(1 for r in typo_cases if r["success"]) / len(typo_cases) * 100
        fp_rate = sum(1 for r in clean_cases if r.get("is_fp", False)) / len(clean_cases) * 100
        clean_acc = sum(1 for r in clean_cases if r["success"]) / len(clean_cases) * 100
        
        # カテゴリ別正解率
        cat_acc = {}
        for r in typo_cases:
            t = r["type"]
            if t not in cat_acc:
                cat_acc[t] = [0, 0]
            cat_acc[t][1] += 1
            if r["success"]:
                cat_acc[t][0] += 1
                
        cat_pct = {k: (v[0] / v[1] * 100) for k, v in cat_acc.items()}
        return {
            "typo_acc": typo_acc,
            "typo_correct": sum(1 for r in typo_cases if r["success"]),
            "typo_total": len(typo_cases),
            "fp_rate": fp_rate,
            "clean_acc": clean_acc,
            "cat_pct": cat_pct
        }

    s_rule = calc_stats(rule_results)
    s_old = calc_stats(old_results)
    s_new = calc_stats(new_results)

    print("\n" + "="*70)
    print("           300問 タイポ補正ベンチマーク総合比較結果")
    print("="*70)
    print(f"{'メトリック':<25} | {'辞書式ルール':<12} | {'旧 zenz-v3.2':<12} | {'新 特化モデル':<12}")
    print("-"*70)
    print(f"{'タイポ補正正解率 (Accuracy)':<25} | {s_rule['typo_acc']:>10.1f}% | {s_old['typo_acc']:>10.1f}% | {s_new['typo_acc']:>10.1f}%")
    print(f"{'タイポ補正正解数':<25} | {s_rule['typo_correct']:>5}/{s_rule['typo_total']}   | {s_old['typo_correct']:>5}/{s_old['typo_total']}   | {s_new['typo_correct']:>5}/{s_new['typo_total']}")
    print(f"{'正常入力保持率 (Clean Acc)':<25} | {s_rule['clean_acc']:>10.1f}% | {s_old['clean_acc']:>10.1f}% | {s_new['clean_acc']:>10.1f}%")
    print(f"{'過剰補正率 (False Positive)':<25} | {s_rule['fp_rate']:>10.1f}% | {s_old['fp_rate']:>10.1f}% | {s_new['fp_rate']:>10.1f}%")
    print("-"*70)
    print("【タイポ種別ごとの正解率】")
    all_cats = sorted(list(s_new["cat_pct"].keys()))
    for cat in all_cats:
        r_val = s_rule['cat_pct'].get(cat, 0.0)
        o_val = s_old['cat_pct'].get(cat, 0.0)
        n_val = s_new['cat_pct'].get(cat, 0.0)
        print(f"  - {cat:<20} | {r_val:>10.1f}% | {o_val:>10.1f}% | {n_val:>10.1f}%")
    print("="*70)

    # JSON 保存
    report_data = {
        "rule_based": s_rule,
        "old_model": s_old,
        "new_model": s_new,
        "samples_count": len(cases)
    }
    with open('artifacts/results/typo_benchmark_300_comparison.json', "w", encoding="utf-8") as f:
        json.dump(report_data, f, ensure_ascii=False, indent=2)
    print("Saved report to results/typo_benchmark_300_comparison.json")

if __name__ == "__main__":
    evaluate_models()
