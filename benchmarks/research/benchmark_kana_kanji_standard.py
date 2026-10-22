# -*- coding: utf-8 -*-
"""
通常の「かな漢字変換」ベンチマーク (全230問) 徹底比較スクリプト
比較対象:
1. 本家メインモデル: zenz-v3.2-small-q4km (61.3MB)
2. 本家 xsmall モデル: zenz-v3.2-xsmall-q4km (18.4MB)
3. ★ 今回のタイポ特化モデル: typo-corrector-xsmall-30k-q4km (17.4MB)
"""

import sys
import os
import json
import time
import subprocess
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

SERVER_EXE = 'ZeroConvertIME-windows/llama_cuda/llama-server.exe'

class LlamaServerInstance:
    def __init__(self, model_path: str, port: int):
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
        self.proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(60):
            try:
                req = urllib.request.Request(f"http://127.0.0.1:{self.port}/health")
                with urllib.request.urlopen(req, timeout=1) as resp:
                    if resp.status == 200:
                        return
            except:
                time.sleep(0.3)
        raise RuntimeError(f"Failed to start server {self.model_path}")

    def stop(self):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except:
                self.proc.kill()
            self.proc = None

    def complete(self, prompt: str, max_tokens: int = 32) -> str:
        url = f"http://127.0.0.1:{self.port}/completion"
        data = json.dumps({
            "prompt": prompt,
            "n_predict": max_tokens,
            "temperature": 0.0,
            "stop": ["</s>", "\uEE00", "\uEE01", "\uEE02", "\n"]
        }).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                res = json.loads(resp.read().decode("utf-8"))
                return res.get("content", "").strip()
        except:
            return ""

def hira_to_kata(text: str) -> str:
    return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])

def evaluate_output(output: str, case: dict) -> tuple[int, str]:
    out = output.strip().replace("\r", "").replace("\n", "")
    expected = case["expected"].strip()
    acceptable = [a.strip() for a in case.get("acceptable", [])]
    forbidden = [f.strip() for f in case.get("forbidden_examples", [])]

    for fb in forbidden:
        if fb and fb in out:
            return 0, f"NG (forbidden: {fb})"

    if out == expected or out in acceptable:
        return 2, "OK (Perfect)"

    if out.replace(" ", "").replace("　", "") == expected.replace(" ", "").replace("　", ""):
        return 2, "OK (Whitespace variant)"

    hira_out = "".join([chr(ord(ch) - 96) if 0x30A1 <= ord(ch) <= 0x30F6 else ch for ch in out])
    hira_exp = "".join([chr(ord(ch) - 96) if 0x30A1 <= ord(ch) <= 0x30F6 else ch for ch in expected])
    if hira_out == hira_exp:
        return 2, "OK (Kana variant)"

    return 0, f"NG (out=[{out}] vs exp=[{expected}])"

def run_evaluation():
    testset_path = "data/kana_kanji_llm_testset_5x.jsonl"
    with open(testset_path, "r", encoding="utf-8") as f:
        cases = [json.loads(line) for line in f if line.strip()]

    print(f"Loaded {len(cases)} standard Kana-Kanji conversion test cases.")

    models = [
        ("本家 zenz-v3.2-small (Q4_K_M)", 'ZeroConvertIME-windows/zenz.gguf', 61.3, 8110),
        ("本家 zenz-v3.2-xsmall (Q4_K_M)", 'artifacts/models/zenz-v3.2-xsmall-q4km.gguf', 18.4, 8111),
        ("★ 新特化 xsmall-30k (LoRA)", 'artifacts/models/typo-corrector-xsmall-30k-q4km.gguf', 17.4, 8112),
    ]

    all_results = {}

    for name, path, sz, port in models:
        print(f"\nEvaluating: {name} (Size: {sz} MB)...")
        srv = LlamaServerInstance(path, port)
        srv.start()
        
        perfect_cnt = 0
        total_score = 0
        cat_stats = {}
        sample_errors = []

        try:
            for idx, c in enumerate(cases):
                ctx = "" if c["left_context"] == "なし" else c["left_context"]
                kata = hira_to_kata(c["target"])
                cat = c.get("category", "その他")
                if cat not in cat_stats:
                    cat_stats[cat] = [0, 0] # [score, max]
                cat_stats[cat][1] += 2

                prompt = f"\uEE02{ctx}\uEE00{kata}\uEE01"
                out = srv.complete(prompt)
                score, reason = evaluate_output(out, c)
                total_score += score
                cat_stats[cat][0] += score
                if score == 2:
                    perfect_cnt += 1
                else:
                    if len(sample_errors) < 5:
                        sample_errors.append((c["left_context"], c["target"], c["expected"], out))

                if (idx + 1) % 50 == 0:
                    print(f"  Processed {idx + 1}/{len(cases)}...")
        finally:
            srv.stop()

        max_score = len(cases) * 2
        acc = (total_score / max_score) * 100
        perfect_acc = (perfect_cnt / len(cases)) * 100

        all_results[name] = {
            "size": sz,
            "total_score": total_score,
            "max_score": max_score,
            "acc": acc,
            "perfect_cnt": perfect_cnt,
            "total_cases": len(cases),
            "perfect_acc": perfect_acc,
            "cat_stats": cat_stats,
            "sample_errors": sample_errors
        }

    print("\n" + "="*85)
    print("      【通常のかな漢字変換ベンチマーク】全230問 徹底比較結果")
    print("="*85)
    print(f"{'モデル':<32} | {'サイズ':<7} | {'得点率 (Score)':<14} | {'正解率 (Perfect)':<14}")
    print("-"*85)
    for name, r in all_results.items():
        print(f"{name:<32} | {r['size']:>5.1f} MB | {r['acc']:>6.1f}% ({r['total_score']}/{r['max_score']}) | {r['perfect_acc']:>6.1f}% ({r['perfect_cnt']}/{r['total_cases']})")
    print("="*85)

    # 失敗例の比較表示
    print("\n【新特化モデルの誤変換パターンの分析 (抜粋)】")
    for ctx, inp, exp, out in all_results["★ 新特化 xsmall-30k (LoRA)"]["sample_errors"]:
        print(f"  文脈: '{ctx}' | 読み: '{inp}'")
        print(f"    正解:   '{exp}'")
        print(f"    モデル: '{out}'")
        print()

if __name__ == "__main__":
    run_evaluation()
