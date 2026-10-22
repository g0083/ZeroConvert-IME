# -*- coding: utf-8 -*-
"""
zenz-v3 vs zenz-v3.2-small vs zenz-v3.2-xsmall
全量子化バリアント（Q5_K_M, Q4_K_M, Q3_K_M, Q2_K）
全230問 徹底比較ベンチマークスクリプト
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
LOG_FILE = 'artifacts/logs/benchmark_zenz_v32_full.log'
os.makedirs('artifacts/logs', exist_ok=True)
os.makedirs('artifacts/results', exist_ok=True)

def log(msg: str):
    print(msg, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(msg + "\n")

SERVER_EXE = 'C:/Users/g0083/AppData/Roaming/Azookey/llama_cuda/llama-server.exe'

EVAL_MODELS = [
    # v3 (ベースライン)
    {
        "series": "v3",
        "name": "zenz-v3 (Q5_K_M)",
        "quant": "Q5_K_M",
        "path": 'C:/Users/g0083/AppData/Roaming/Azookey/zenz.gguf',
    },
    {
        "series": "v3",
        "name": "zenz-v3 (Q4_K_M)",
        "quant": "Q4_K_M",
        "path": str(REPO_ROOT / 'scratch/zenz-q4km.gguf'),
    },
    {
        "series": "v3",
        "name": "zenz-v3 (Q3_K_M)",
        "quant": "Q3_K_M",
        "path": str(REPO_ROOT / 'scratch/zenz-q3km.gguf'),
    },
    {
        "series": "v3",
        "name": "zenz-v3 (Q2_K)",
        "quant": "Q2_K",
        "path": str(REPO_ROOT / 'scratch/zenz-q2k.gguf'),
    },

    # v3.2-small
    {
        "series": "v3.2-small",
        "name": "zenz-v3.2-small (Q5_K_M)",
        "quant": "Q5_K_M",
        "path": str(REPO_ROOT / 'artifacts/models/zenz-v3.2-small-q5km.gguf'),
    },
    {
        "series": "v3.2-small",
        "name": "zenz-v3.2-small (Q4_K_M)",
        "quant": "Q4_K_M",
        "path": str(REPO_ROOT / 'artifacts/models/zenz-v3.2-small-q4km.gguf'),
    },
    {
        "series": "v3.2-small",
        "name": "zenz-v3.2-small (Q3_K_M)",
        "quant": "Q3_K_M",
        "path": str(REPO_ROOT / 'artifacts/models/zenz-v3.2-small-q3km.gguf'),
    },
    {
        "series": "v3.2-small",
        "name": "zenz-v3.2-small (Q2_K)",
        "quant": "Q2_K",
        "path": str(REPO_ROOT / 'artifacts/models/zenz-v3.2-small-q2k.gguf'),
    },

    # v3.2-xsmall
    {
        "series": "v3.2-xsmall",
        "name": "zenz-v3.2-xsmall (Q5_K_M)",
        "quant": "Q5_K_M",
        "path": str(REPO_ROOT / 'artifacts/models/zenz-v3.2-xsmall-q5km.gguf'),
    },
    {
        "series": "v3.2-xsmall",
        "name": "zenz-v3.2-xsmall (Q4_K_M)",
        "quant": "Q4_K_M",
        "path": str(REPO_ROOT / 'artifacts/models/zenz-v3.2-xsmall-q4km.gguf'),
    },
    {
        "series": "v3.2-xsmall",
        "name": "zenz-v3.2-xsmall (Q3_K_M)",
        "quant": "Q3_K_M",
        "path": str(REPO_ROOT / 'artifacts/models/zenz-v3.2-xsmall-q3km.gguf'),
    },
    {
        "series": "v3.2-xsmall",
        "name": "zenz-v3.2-xsmall (Q2_K)",
        "quant": "Q2_K",
        "path": str(REPO_ROOT / 'artifacts/models/zenz-v3.2-xsmall-q2k.gguf'),
    },
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

def run_benchmark_on_server(cases: list) -> tuple[int, int, float, dict, list]:
    total_score = 0
    perfect_count = 0
    latencies = []
    category_stats = {}
    details = []

    for case in cases:
        ctx = "" if case["left_context"] == "なし" else case["left_context"]
        target_kata = hira_to_kata(case["target"])
        cat = case.get("category", "その他")

        if cat not in category_stats:
            category_stats[cat] = {"score": 0, "max": 0, "perfect": 0, "total": 0}
        category_stats[cat]["total"] += 1
        category_stats[cat]["max"] += 2

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
                "http://127.0.0.1:8090/completion",
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
            category_stats[cat]["perfect"] += 1
        category_stats[cat]["score"] += score

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
    return total_score, perfect_count, avg_lat, category_stats, details

def main():
    if os.path.exists(LOG_FILE):
        os.remove(LOG_FILE)

    dataset_path = "data/kana_kanji_llm_testset_5x.jsonl"
    cases = []
    with open(dataset_path, "r", encoding="utf-8") as f:
        for line in f:
            if line.strip():
                cases.append(json.loads(line.strip()))

    log(f"Loaded {len(cases)} cases from {dataset_path}")

    all_results = []

    for m in EVAL_MODELS:
        m_path = m["path"]
        if not os.path.exists(m_path):
            log(f"WARNING: File not found: {m_path}, skipping...")
            continue

        sz_mb = round(os.path.getsize(m_path) / 1024 / 1024, 1)
        log(f"\n================ Starting {m['name']} ({sz_mb} MB) ================")

        # llama-server を起動
        cmd = [SERVER_EXE, "-m", m_path, "--port", "8090", "--host", "127.0.0.1", "-ngl", "99", "--log-disable"]
        srv = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(2)  # 起動待機

        try:
            t0 = time.time()
            score, perfect, avg_lat, cat_stats, details = run_benchmark_on_server(cases)
            elapsed = time.time() - t0

            acc = perfect / len(cases) * 100
            homophone_acc = 0.0
            if "文脈依存・同音異義語" in cat_stats:
                h_stat = cat_stats["文脈依存・同音異義語"]
                homophone_acc = (h_stat["perfect"] / h_stat["total"]) * 100 if h_stat["total"] > 0 else 0.0

            log(f"  総合正解率: {perfect}/{len(cases)} ({acc:.1f}%) | 同音異義語: {homophone_acc:.1f}% | 平均遅延: {avg_lat:.1f}ms | 所要時間: {elapsed:.1f}s")

            all_results.append({
                "series": m["series"],
                "name": m["name"],
                "quant": m["quant"],
                "size_mb": sz_mb,
                "perfect_count": perfect,
                "total_cases": len(cases),
                "accuracy_pct": round(acc, 1),
                "homophone_accuracy_pct": round(homophone_acc, 1),
                "total_score": score,
                "max_score": len(cases) * 2,
                "avg_latency_ms": round(avg_lat, 1),
                "elapsed_sec": round(elapsed, 1),
                "category_stats": cat_stats
            })
        finally:
            srv.terminate()
            srv.wait()
            time.sleep(1)

    log("\n================ 全モデルベンチマーク完了 ================")
    log("\n| モデル系列 | 量子化 | ファイルサイズ | 総合正解数/230 | 総合正解率 (%) | 同音異義語正解率 (%) | 平均推論遅延 |")
    log("|---|---|---|---|---|---|---|")
    for r in all_results:
        log(f"| **{r['series']}** | **{r['quant']}** | **{r['size_mb']} MB** | {r['perfect_count']} / {r['total_cases']} | **{r['accuracy_pct']}%** | **{r['homophone_accuracy_pct']}%** | {r['avg_latency_ms']} ms |")

    with open('artifacts/results/benchmark_zenz_v32_full_results.json', "w", encoding="utf-8") as f:
        json.dump(all_results, f, ensure_ascii=False, indent=2)

    log("\nResults saved to results/benchmark_zenz_v32_full_results.json")

if __name__ == "__main__":
    main()
