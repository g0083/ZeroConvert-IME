# -*- coding: utf-8 -*-
"""
追加データセット kana_kanji_llm_testset_5x (全230問) に対する
案C（ロジット直接選択 / Top Logprobs Choice）全1.2Bモデル ベンチマーク実行スクリプト
"""

import sys
import os
import json
import time
import urllib.request
import subprocess
import re

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
LOG_FILE = 'artifacts/logs/benchmark_5x_c.log'

def log(msg: str):
    print(msg, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(msg + "\n")

MODELS = [
    # 1. 極小量子化 IQ1_S (290MB)
    {
        "id": "lfm2.5-1.2b-deepseek-3.1-thinking-distill-i1@iq1_s",
        "name": "DeepSeek-3.1 Distill 1.2B (IQ1_S / 290MB)",
        "tag": "deepseek31_iq1s",
        "is_thinking": True
    },
    {
        "id": "glm4.7-distill-lfm2.5-1.2b-i1@iq1_s",
        "name": "GLM-4.7 Distill 1.2B (IQ1_S / 290MB)",
        "tag": "glm47_iq1s",
        "is_thinking": True
    },
    # 2. 超軽量 IQ2_XXS (348MB)
    {
        "id": "lfm2.5-1.2b-deepseek-3.1-thinking-distill-i1@iq2_xxs",
        "name": "DeepSeek-3.1 Distill 1.2B (IQ2_XXS / 348MB)",
        "tag": "deepseek31_iq2xxs",
        "is_thinking": True
    },
    {
        "id": "glm4.7-distill-lfm2.5-1.2b-i1@iq2_xxs",
        "name": "GLM-4.7 Distill 1.2B (IQ2_XXS / 348MB)",
        "tag": "glm47_iq2xxs",
        "is_thinking": True
    },
    # 3. 2-bit Q2_K (461MB)
    {
        "id": "lfm2.5-1.2b-thinking-claude-4.6-opus-heretic-uncensored-distill@q2_k",
        "name": "Claude-4.6-Opus Distill 1.2B (Q2_K / 461MB)",
        "tag": "claude_opus_q2k",
        "is_thinking": True
    },
    # 4. 標準 4-bit Q4_K_M (697MB)
    {
        "id": "lfm2.5-1.2b-deepseek-3.1-thinking-distill-i1@q4_k_m",
        "name": "DeepSeek-3.1 Distill 1.2B (Q4_K_M / 697MB)",
        "tag": "deepseek31_q4km",
        "is_thinking": True
    },
    {
        "id": "glm4.7-distill-lfm2.5-1.2b-i1@q4_k_m",
        "name": "GLM-4.7 Distill 1.2B (Q4_K_M / 697MB)",
        "tag": "glm47_q4km",
        "is_thinking": True
    },
    {
        "id": "lfm2.5-1.2b-thinking-claude-4.6-opus-heretic-uncensored-distill@q4_k_m",
        "name": "Claude-4.6-Opus Distill 1.2B (Q4_K_M / 697MB)",
        "tag": "claude_opus_q4km",
        "is_thinking": True
    },
    # 5. 日本語特化モデル
    {
        "id": "lfm2.5-1.2b-jp-202606@q4_k_m",
        "name": "LFM2.5 1.2B JP-202606 (Q4_K_M / 730MB)",
        "tag": "jp_202606_q4km",
        "is_thinking": False
    },
    {
        "id": "lfm2.5-1.2b-jp-202606@q8_0",
        "name": "LFM2.5 1.2B JP-202606 (Q8_0 / 1.25GB)",
        "tag": "jp_202606_q8",
        "is_thinking": False
    },
    # 6. 公式無印 / 検閲解除
    {
        "id": "lfm2.5-1.2b-instruct",
        "name": "公式無印 LFM2.5 1.2B Instruct (1.25GB)",
        "tag": "official_instruct",
        "is_thinking": False
    },
    {
        "id": "lfm2.5-1.2b-instruct-heretic",
        "name": "検閲解除版 LFM2.5 1.2B Heretic (1.25GB)",
        "tag": "heretic_instruct",
        "is_thinking": False
    }
]

def load_model(model_id: str):
    log(f"\n[LMS LOAD] Loading {model_id} (Context=2048, GPU=max)...")
    cmd = ["lms", "load", model_id, "-c", "2048", "--gpu", "max", "-y"]
    r = subprocess.run(cmd, capture_output=True, encoding='utf-8', errors='ignore')
    log(f"  {r.stdout.strip()[:150]}")
    time.sleep(2)

def unload_model(model_id: str):
    log(f"[LMS UNLOAD] Unloading {model_id}...")
    subprocess.run(["lms", "unload", model_id], capture_output=True)
    time.sleep(1)

def run_case(case: dict, model_id: str, is_thinking: bool) -> tuple[int, str, float]:
    ctx = case["context"]
    target = case["target"]
    cands = case["candidates"]
    
    cand_text = "\n".join([f"{i+1}. {c}" for i, c in enumerate(cands)])
    prompt = f"""あなたは日本語IMEの候補選択器です。
前方文脈と読みを考慮して、最も適切な候補の番号だけを出力してください。
候補を書き換えたり、新しい候補を生成したりしてはいけません。
回答は半角数字（1〜{len(cands)}）のみを出力してください。

前方文脈：
{ctx}
読み：
{target}
候補：
{cand_text}
出力："""

    messages = [{"role": "user", "content": prompt}]
    if is_thinking:
        messages.append({"role": "assistant", "content": "</think>\n"})

    payload = {
        "model": model_id,
        "messages": messages,
        "temperature": 0.0,
        "max_tokens": 1,
        "logprobs": True,
        "top_logprobs": 10
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

        # 案C: Top logprobsから候補番号トークンの確率を直接比較
        content_items = data["choices"][0].get("logprobs", {}).get("content", [])
        if content_items and "top_logprobs" in content_items[0]:
            top_lps = content_items[0]["top_logprobs"]
        else:
            top_lps = []

        cand_logprobs = {}
        for item in top_lps:
            t = item["token"].strip()
            if t in [str(i + 1) for i in range(len(cands))]:
                cand_logprobs[int(t)] = item["logprob"]

        if cand_logprobs:
            best_idx = max(cand_logprobs.items(), key=lambda x: x[1])[0]
            return best_idx, f"logprob:{cand_logprobs}", lat
        else:
            # フォールバック: テキストマッチ
            gen_c = data["choices"][0]["message"].get("content", "").strip()
            m = re.search('\\b([1-4])\\b', gen_c)
            idx = int(m.group(1)) if m else 1
            return idx, f"text:{gen_c}", lat

    except Exception as e:
        lat = (time.time() - t0) * 1000
        return 1, f"error:{e}", lat

def run_benchmark_for_model(model_info: dict, cases: list) -> dict:
    model_id = model_info["id"]
    is_thinking = model_info["is_thinking"]
    load_model(model_id)

    total_score = 0
    max_score = len(cases) * 2
    correct_count = 0
    latencies = []
    category_scores = {}
    details = []

    log(f"Starting Plan C test for {model_info['name']} ({len(cases)} cases)...")
    t_start = time.time()

    for idx, case in enumerate(cases):
        cat = case.get("category", "その他")
        if cat not in category_scores:
            category_scores[cat] = {"score": 0, "max": 0, "correct": 0, "count": 0}
        category_scores[cat]["count"] += 1
        category_scores[cat]["max"] += 2

        selected_idx, raw_out, lat = run_case(case, model_id, is_thinking)
        latencies.append(lat)

        is_correct = (selected_idx == case["correct_idx"])
        score = 2 if is_correct else 0
        total_score += score
        if is_correct:
            correct_count += 1
            category_scores[cat]["correct"] += 1
        category_scores[cat]["score"] += score

        details.append({
            "id": case["id"],
            "category": cat,
            "target": case["target"],
            "expected": case["expected"],
            "correct_idx": case["correct_idx"],
            "selected_idx": selected_idx,
            "raw_output": raw_out,
            "is_correct": is_correct,
            "score": score,
            "latency_ms": round(lat, 1)
        })

        if (idx + 1) % 50 == 0 or idx + 1 == len(cases):
            log(f"  [{idx+1}/{len(cases)}] Current: {total_score}/{(idx+1)*2} (Accuracy: {correct_count/(idx+1)*100:.1f}%) - Avg Latency: {sum(latencies)/len(latencies):.1f}ms")

    t_elapsed = time.time() - t_start
    avg_latency = sum(latencies) / len(latencies) if latencies else 0.0

    log(f"\nResult for {model_info['name']}:")
    log(f"  Total Score: {total_score} / {max_score} ({total_score/max_score*100:.1f}%)")
    log(f"  Correct: {correct_count} / {len(cases)} ({correct_count/len(cases)*100:.1f}%)")
    log(f"  Avg Latency: {avg_latency:.1f} ms | Total Time: {t_elapsed:.1f} s")

    unload_model(model_id)

    return {
        "model_id": model_id,
        "name": model_info["name"],
        "tag": model_info["tag"],
        "total_score": total_score,
        "max_score": max_score,
        "accuracy_pct": round(correct_count / len(cases) * 100, 1),
        "correct_count": correct_count,
        "total_cases": len(cases),
        "avg_latency_ms": round(avg_latency, 1),
        "elapsed_sec": round(t_elapsed, 1),
        "category_scores": category_scores,
        "details": details
    }

def main():
    if os.path.exists(LOG_FILE):
        os.remove(LOG_FILE)

    with open('data/benchmark_5x_cands.json', "r", encoding="utf-8") as f:
        cases = json.load(f)
    log(f"Loaded {len(cases)} cases with realistic candidates from benchmark_5x_cands.json")

    results = []
    for m in MODELS:
        res = run_benchmark_for_model(m, cases)
        results.append(res)
        with open('artifacts/results/benchmark_5x_plan_c_results.json', "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)

    log("\n================ ALL 12 MODELS PLAN C BENCHMARK COMPLETE ================")

if __name__ == "__main__":
    main()
