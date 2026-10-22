# -*- coding: utf-8 -*-
"""
zenz-v3 の全量子化バリアント（Q5_K_M, Q4_K_M, Q3_K_M, Q2_K）に対する
追加データセット全230問 直接かな漢字変換 比較ベンチマークスクリプト
"""

from pathlib import Path
REPO_ROOT = Path(__file__).resolve().parents[1]


import sys
import os
import json
import time
import subprocess
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
LOG_FILE = 'artifacts/logs/benchmark_zenz_quant.log'

def log(msg: str):
    print(msg, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(msg + "\n")

SERVER_EXE = 'C:/Users/g0083/AppData/Roaming/Azookey/llama_cuda/llama-server.exe'

QUANT_MODELS = [
    {
        "name": "zenz-v3 (Q5_K_M / 元モデル 68.8MB)",
        "path": 'C:/Users/g0083/AppData/Roaming/Azookey/zenz.gguf',
        "tag": "q5km",
        "size_mb": 68.8
    },
    {
        "name": "zenz-v3 (Q4_K_M / 量子化 59.6MB)",
        "path": str(REPO_ROOT / 'scratch/zenz-q4km.gguf'),
        "tag": "q4km",
        "size_mb": 59.6
    },
    {
        "name": "zenz-v3 (Q3_K_M / 量子化 49.4MB)",
        "path": str(REPO_ROOT / 'scratch/zenz-q3km.gguf'),
        "tag": "q3km",
        "size_mb": 49.4
    },
    {
        "name": "zenz-v3 (Q2_K   / 量子化 37.0MB)",
        "path": str(REPO_ROOT / 'scratch/zenz-q2k.gguf'),
        "tag": "q2k",
        "size_mb": 37.0
    }
]

def hira_to_kata(text: str) -> str:
    return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])

CONTEXT_TOKEN = "\uEE02"
INPUT_START_TOKEN = "\uEE00"
OUTPUT_START_TOKEN = "\uEE01"

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

def run_benchmark_on_server(cases: list) -> tuple[int, int, float, list]:
    total_score = 0
    perfect_count = 0
    latencies = []
    details = []

    for case in cases:
        ctx = "" if case["left_context"] == "なし" else case["left_context"]
        target_kata = hira_to_kata(case["target"])
        prompt = f"{CONTEXT_TOKEN}{ctx}{INPUT_START_TOKEN}{target_kata}{OUTPUT_START_TOKEN}"

        payload = {
            "prompt": prompt,
            "n_predict": 32,
            "temperature": 0.0,
            "stop": ["</s>", "\uEE00", "\uEE01", "\uEE02"]
        }

        t0 = time.time()
        try:
            req = urllib.request.Request(
                "http://127.0.0.1:8088/completion",
                headers={"Content-Type": "application/json"},
                data=json.dumps(payload).encode("utf-8")
            )
            with urllib.request.urlopen(req, timeout=5) as resp:
                res = json.loads(resp.read().decode("utf-8"))
            lat = (time.time() - t0) * 1000
            out_raw = res.get("content", "").strip()
        except Exception as e:
            lat = (time.time() - t0) * 1000
            out_raw = f"ERR: {e}"

        latencies.append(lat)
        score, reason = evaluate_output(out_raw, case)
        total_score += score
        if score == 2:
            perfect_count += 1

        details.append({
            "id": case["id"],
            "target": case["target"],
            "expected": case["expected"],
            "output": out_raw,
            "score": score,
            "reason": reason,
            "latency_ms": round(lat, 1)
        })

    avg_lat = sum(latencies) / len(latencies) if latencies else 0.0
    return total_score, perfect_count, avg_lat, details

def main():
    if os.path.exists(LOG_FILE):
        os.remove(LOG_FILE)

    cases = []
    with open('data/kana_kanji_llm_testset_5x.jsonl', "r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                cases.append(json.loads(line.strip()))

    log(f"Loaded {len(cases)} cases for Quantization Benchmark")

    all_results = []

    for m in QUANT_MODELS:
        log(f"\n================ Starting {m['name']} ================")
        # llama-server を起動
        cmd = [SERVER_EXE, "-m", m["path"], "--port", "8088", "--host", "127.0.0.1", "-ngl", "99", "--log-disable"]
        srv = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(2)  # 起動待機

        try:
            t0 = time.time()
            score, perfect, avg_lat, details = run_benchmark_on_server(cases)
            elapsed = time.time() - t0

            acc = perfect / len(cases) * 100
            log(f"  結果: 正解 {perfect}/{len(cases)} ({acc:.1f}%) | スコア: {score}/{len(cases)*2} | 平均遅延: {avg_lat:.1f}ms | 処理時間: {elapsed:.1f}s")

            all_results.append({
                "name": m["name"],
                "tag": m["tag"],
                "size_mb": m["size_mb"],
                "perfect_count": perfect,
                "total_cases": len(cases),
                "accuracy_pct": round(acc, 1),
                "total_score": score,
                "max_score": len(cases) * 2,
                "avg_latency_ms": round(avg_lat, 1),
                "elapsed_sec": round(elapsed, 1)
            })
        finally:
            srv.terminate()
            srv.wait()
            time.sleep(1)

    log("\n================ 全量子化バリアント評価完了 ================")
    log("\n| 量子化タイプ | ファイルサイズ | 正解数 / 230 | 正解率 (%) | 平均遅延 |")
    log("|---|---|---|---|---|")
    for r in all_results:
        log(f"| **{r['tag'].upper()}** | **{r['size_mb']} MB** | {r['perfect_count']} / {r['total_cases']} | **{r['accuracy_pct']}%** | {r['avg_latency_ms']} ms |")

    with open('artifacts/results/benchmark_zenz_quant_results.json', "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2)

if __name__ == "__main__":
    main()
