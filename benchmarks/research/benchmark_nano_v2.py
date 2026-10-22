# -*- coding: utf-8 -*-
"""
zerocon model nano v2 かな漢字変換ベンチマーク (全230問 厳格評価)
比較対象:
1. zenz-v3.1-xsmall (Base)
2. zenz-v3.2-xsmall (Nano v1)
3. zenz-v3.2-small (Standard - 教師モデル)
4. zerocon-model-nano-v2 (Nano v2 - 蒸留＆辞書ファインチューニング)
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
                time.sleep(0.2)
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
        ("zenz-v3.1-xsmall (Base)", 'artifacts/models/zenz-v3.1-xsmall-q4km.gguf', 17.41, 8140),
        ("zenz-v3.2-xsmall (Nano v1)", 'artifacts/models/zenz-v3.2-xsmall-q4km.gguf', 18.45, 8141),
        ("zenz-v3.2-small (Standard)", 'ZeroConvertIME-windows/zenz.gguf', 61.28, 8142),
        ("★ zerocon nano v2 (LoRA)", 'artifacts/models/zerocon-model-nano-v2-gentle-q4km.gguf', 17.41, 8143),
        ("★★ zerocon nano v2 (Full-FT)", 'artifacts/models/zerocon-model-nano-v2-fullft-q4km.gguf', 17.41, 8144),
    ]

    all_results = {}

    for name, path, sz, port in models:
        print(f"\nEvaluating: {name} (Size: {sz:.2f} MB)...")
        srv = LlamaServerInstance(path, port)
        srv.start()
        
        perfect_cnt = 0
        total_score = 0
        cat_stats = {}
        error_list = []

        t0 = time.time()
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
                    error_list.append((c["category"], c["left_context"], c["target"], c["expected"], out))

                if (idx + 1) % 50 == 0:
                    print(f"  Processed {idx + 1}/{len(cases)}...")
        finally:
            srv.stop()

        elapsed = time.time() - t0
        avg_latency = (elapsed / len(cases)) * 1000

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
            "avg_latency": avg_latency,
            "cat_stats": cat_stats,
            "error_list": error_list
        }

    print("\n" + "="*95)
    print("      【ZeroConvert-IME かな漢字変換ベンチマーク】全230問 徹底比較検証")
    print("="*95)
    print(f"{'モデル名':<30} | {'サイズ':<9} | {'正解数 / 230':<13} | {'正解率 (%)':<11} | {'得点率 (%)':<11} | {'遅延':<8}")
    print("-"*95)
    for name, r in all_results.items():
        print(f"{name:<30} | {r['size']:>6.2f} MB | {r['perfect_cnt']:>4}/{r['total_cases']} 問 | {r['perfect_acc']:>9.1f}% | {r['acc']:>9.1f}% | {r['avg_latency']:>5.1f}ms")
    print("="*95)

    # カテゴリ別正解率
    categories = sorted(list(cases[0].keys()))
    cats = list(all_results[models[0][0]]["cat_stats"].keys())
    print("\n【カテゴリ別 正解率比較 (得点率)】")
    header = f"{'カテゴリ':<24} | " + " | ".join([f"{n.split(' ')[0]:<15}" for n, _, _, _ in models])
    print(header)
    print("-" * len(header))
    for cat in cats:
        row = f"{cat:<24} | "
        vals = []
        for name, _, _, _ in models:
            c_score, c_max = all_results[name]["cat_stats"][cat]
            c_pct = (c_score / c_max) * 100
            vals.append(f"{c_pct:>5.1f}% ({c_score}/{c_max})")
        row += " | ".join(vals)
        print(row)

    # Nano v2 で改善した具体例
    base_errors = { (e[1], e[2]): e for e in all_results["zenz-v3.1-xsmall (Base)"]["error_list"] }
    v2_errors = { (e[1], e[2]): e for e in all_results["★ zerocon model nano v2"]["error_list"] }
    recovered = []
    for k, b_err in base_errors.items():
        if k not in v2_errors:
            recovered.append(b_err)

    print(f"\n【Nano v2 で劇的に改善・正解化した問題 (抜粋: 全{len(recovered)}件)】")
    for cat, ctx, tgt, exp, old_out in recovered[:15]:
        print(f"  [{cat}] 文脈: '{ctx}' | 読み: '{tgt}' -> 正解: '{exp}' (Base旧出力: '{old_out}')")

if __name__ == "__main__":
    run_evaluation()
