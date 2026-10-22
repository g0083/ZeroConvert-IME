# -*- coding: utf-8 -*-
"""
追加データセット kana_kanji_llm_testset_5x (全230問) に対する
案C（候補番号選択）2モデル同時並列実行ベンチマークスクリプト

- 正解位置は 1〜4 に均等分散（シャッフル済み）
- フォールバックによる下駄（第1候補自動選択）を完全排除
- 2モデルを同時にLM Studioにロードし、マルチスレッドで並行評価
"""

import sys
import os
import json
import time
import urllib.request
import subprocess
import re
from concurrent.futures import ThreadPoolExecutor

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
LOG_FILE = 'artifacts/logs/benchmark_5x_parallel.log'

def log(msg: str):
    print(msg, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(msg + "\n")

MODEL_PAIRS = [
    # ペア1: 日本語特化 vs 公式無印
    (
        {"id": "lfm2.5-1.2b-jp-202606@q4_k_m", "name": "JP-202606 (Q4_K_M / 730MB)", "is_thinking": False},
        {"id": "lfm2.5-1.2b-instruct", "name": "公式無印 Instruct (1.25GB)", "is_thinking": False}
    ),
    # ペア2: 蒸留極小 IQ1_S 2モデル
    (
        {"id": "lfm2.5-1.2b-deepseek-3.1-thinking-distill-i1@iq1_s", "name": "DeepSeek-3.1 Distill (IQ1_S / 290MB)", "is_thinking": True},
        {"id": "glm4.7-distill-lfm2.5-1.2b-i1@iq1_s", "name": "GLM-4.7 Distill (IQ1_S / 290MB)", "is_thinking": True}
    ),
    # ペア3: 蒸留 Q4_K_M 2モデル
    (
        {"id": "lfm2.5-1.2b-deepseek-3.1-thinking-distill-i1@q4_k_m", "name": "DeepSeek-3.1 Distill (Q4_K_M / 697MB)", "is_thinking": True},
        {"id": "glm4.7-distill-lfm2.5-1.2b-i1@q4_k_m", "name": "GLM-4.7 Distill (Q4_K_M / 697MB)", "is_thinking": True}
    ),
    # ペア4: Claude蒸留 vs 検閲解除
    (
        {"id": "lfm2.5-1.2b-thinking-claude-4.6-opus-heretic-uncensored-distill@q4_k_m", "name": "Claude-4.6-Opus Distill (Q4_K_M / 697MB)", "is_thinking": True},
        {"id": "lfm2.5-1.2b-instruct-heretic", "name": "検閲解除 Heretic (1.25GB)", "is_thinking": False}
    )
]

def load_two_models(m1: dict, m2: dict):
    log(f"\n[LMS DUAL LOAD] Loading {m1['id']} and {m2['id']} simultaneously...")
    subprocess.run(["lms", "unload", "--all"], capture_output=True)
    time.sleep(1)
    
    cmd1 = ["lms", "load", m1["id"], "-c", "2048", "--gpu", "max", "-y"]
    cmd2 = ["lms", "load", m2["id"], "-c", "2048", "--gpu", "max", "-y"]
    
    p1 = subprocess.run(cmd1, capture_output=True, encoding='utf-8', errors='ignore')
    p2 = subprocess.run(cmd2, capture_output=True, encoding='utf-8', errors='ignore')
    log(f"  Loaded 1: {m1['name']}")
    log(f"  Loaded 2: {m2['name']}")
    time.sleep(2)

def evaluate_case_for_model(case: dict, model_info: dict) -> dict:
    model_id = model_info["id"]
    is_thinking = model_info["is_thinking"]
    ctx = case["context"]
    target = case["target"]
    cands = case["candidates"]
    cand_text = "\n".join([f"{i+1}. {c}" for i, c in enumerate(cands)])

    # Few-shot プロンプトで選択ルールをモデルに徹底
    prompt = f"""日本語IMEの候補選択器です。文脈に最も合う候補の番号（1〜{len(cands)}の数字1文字）だけを出力してください。

文脈：食事を始める。
読み：はしをもつ
候補：
1. 橋を持つ
2. 箸を持つ
3. 端を持つ
出力：2

文脈：川の向こう側へ行く。
読み：はしをわたる
候補：
1. 端を渡る
2. 箸を渡る
3. 橋を渡る
出力：3

文脈：{ctx}
読み：{target}
候補：
{cand_text}
出力："""

    messages = [{"role": "user", "content": prompt}]
    if is_thinking:
        # thinking モデルの思考を抑制
        messages.append({"role": "assistant", "content": "</think>\n"})

    payload = {
        "model": model_id,
        "messages": messages,
        "temperature": 0.0,
        "max_tokens": 8
    }

    t0 = time.time()
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:1234/v1/chat/completions",
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload).encode("utf-8")
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        lat = (time.time() - t0) * 1000
        raw_ans = data["choices"][0]["message"].get("content", "").strip()

        # 数字の抽出（先頭または文中の1〜4）
        m = re.search(r'[1-4]', raw_ans)
        if m:
            selected_idx = int(m.group(0))
        else:
            selected_idx = -1  # 数字が出なければ不正解（下駄を履かせない）

        is_correct = (selected_idx == case["correct_idx"])
        score = 2 if is_correct else 0

        return {
            "id": case["id"],
            "selected_idx": selected_idx,
            "correct_idx": case["correct_idx"],
            "is_correct": is_correct,
            "score": score,
            "raw": raw_ans[:30],
            "latency_ms": round(lat, 1)
        }
    except Exception as e:
        lat = (time.time() - t0) * 1000
        return {
            "id": case["id"],
            "selected_idx": -1,
            "correct_idx": case["correct_idx"],
            "is_correct": False,
            "score": 0,
            "raw": f"ERR: {e}",
            "latency_ms": round(lat, 1)
        }

def run_single_model(model_info: dict, cases: list) -> dict:
    t_start = time.time()
    results = []
    correct_count = 0
    total_score = 0
    latencies = []

    for c in cases:
        r = evaluate_case_for_model(c, model_info)
        results.append(r)
        if r["is_correct"]:
            correct_count += 1
            total_score += 2
        latencies.append(r["latency_ms"])

    t_elapsed = time.time() - t_start
    avg_lat = sum(latencies) / len(latencies) if latencies else 0.0

    return {
        "model_id": model_info["id"],
        "name": model_info["name"],
        "total_score": total_score,
        "max_score": len(cases) * 2,
        "accuracy_pct": round(correct_count / len(cases) * 100, 1),
        "correct_count": correct_count,
        "total_cases": len(cases),
        "avg_latency_ms": round(avg_lat, 1),
        "elapsed_sec": round(t_elapsed, 1),
        'results': results
    }

def run_pair(m1: dict, m2: dict, cases: list) -> tuple[dict, dict]:
    load_two_models(m1, m2)
    log(f"Starting parallel benchmark on {len(cases)} cases for:\n  1. {m1['name']}\n  2. {m2['name']}...")

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=2) as executor:
        f1 = executor.submit(run_single_model, m1, cases)
        f2 = executor.submit(run_single_model, m2, cases)
        res1 = f1.result()
        res2 = f2.result()
    total_time = time.time() - t0

    log(f"\n--- PAIR COMPLETE ({total_time:.1f}s) ---")
    log(f"  [1] {res1['name']}: {res1['correct_count']}/{res1['total_cases']} ({res1['accuracy_pct']}%) - {res1['avg_latency_ms']}ms")
    log(f"  [2] {res2['name']}: {res2['correct_count']}/{res2['total_cases']} ({res2['accuracy_pct']}%) - {res2['avg_latency_ms']}ms")

    return res1, res2

def main():
    if os.path.exists(LOG_FILE):
        os.remove(LOG_FILE)

    with open('data/benchmark_5x_cands.json', "r", encoding="utf-8") as f:
        cases = json.load(f)
    log(f"Loaded {len(cases)} cases (shuffled candidates, no-cheat benchmark)")

    all_results = []
    for m1, m2 in MODEL_PAIRS:
        r1, r2 = run_pair(m1, m2, cases)
        all_results.extend([r1, r2])
        with open('artifacts/results/benchmark_5x_parallel_results.json', "w", encoding="utf-8") as f:
            json.dump(all_results, f, ensure_ascii=False, indent=2)

    subprocess.run(["lms", "unload", "--all"], capture_output=True)
    log("\n================ ALL PAIRS COMPLETE ================")

if __name__ == "__main__":
    main()
