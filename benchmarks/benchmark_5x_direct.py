# -*- coding: utf-8 -*-
"""
追加データセット kana_kanji_llm_testset_5x.jsonl (全230問) に対する
全1.2Bモデル 直接生成（かな漢字変換）ベンチマーク実行スクリプト
"""

import sys
import os
import json
import time
import urllib.request
import subprocess
import re

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
LOG_FILE = 'artifacts/logs/benchmark_5x.log'

def log(msg: str):
    print(msg, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(msg + "\n")

SYSTEM_PROMPT = """日本語IMEとして動作してください。
入力の「変換対象」だけを、前方文脈に基づいて漢字かな交じり文へ変換してください。
厳守事項：
・かな漢字変換以外は禁止
・語句の追加、削除、言い換えは禁止
・助詞と語順の変更は禁止
・入力にない句読点の追加は禁止
・前方文脈は出力しない
・不明な部分はひらがなのまま残す
・回答は変換結果だけを1行で出力する"""

MODELS = [
    {
        "id": "lfm2.5-1.2b-deepseek-3.1-thinking-distill-i1@iq1_s",
        "name": "DeepSeek-3.1 蒸留 1.2B (IQ1_S / 290MB)",
        "tag": "deepseek31_iq1s",
        "is_thinking": True
    },
    {
        "id": "glm4.7-distill-lfm2.5-1.2b-i1@iq1_s",
        "name": "GLM-4.7 蒸留 1.2B (IQ1_S / 290MB)",
        "tag": "glm47_iq1s",
        "is_thinking": True
    },
    {
        "id": "lfm2.5-1.2b-deepseek-3.1-thinking-distill-i1@q4_k_m",
        "name": "DeepSeek-3.1 蒸留 1.2B (Q4_K_M / 697MB)",
        "tag": "deepseek31_q4",
        "is_thinking": True
    },
    {
        "id": "glm4.7-distill-lfm2.5-1.2b-i1@q4_k_m",
        "name": "GLM-4.7 蒸留 1.2B (Q4_K_M / 697MB)",
        "tag": "glm47_q4",
        "is_thinking": True
    },
    {
        "id": "lfm2.5-1.2b-thinking-claude-4.6-opus-heretic-uncensored-distill@q4_k_m",
        "name": "Claude-4.6-Opus 蒸留 1.2B (Q4_K_M / 697MB)",
        "tag": "claude_opus_q4",
        "is_thinking": True
    },
    {
        "id": "lfm2.5-1.2b-jp-202606@q4_k_m",
        "name": "日本語特化 LFM2.5 1.2B JP-202606 (Q4_K_M / 730MB)",
        "tag": "jp_202606_q4",
        "is_thinking": False
    },
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

def load_dataset():
    cases = []
    with open('data/kana_kanji_llm_testset_5x.jsonl', "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))
    return cases

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

def evaluate_output(output: str, case: dict) -> tuple[int, str]:
    out = output.strip().replace("\r", "").replace("\n", " ")
    out = re.sub(r'^["「](.*)[」"]$', '\\1', out).strip()

    expected = case["expected"].strip()
    acceptable = [a.strip() for a in case.get("acceptable", [])]
    forbidden = [f.strip() for f in case.get("forbidden_examples", [])]

    for fb in forbidden:
        if fb and fb in out:
            return 0, f"NG (forbidden: {fb})"

    if out == expected or out in acceptable:
        return 2, "OK (Perfect)"

    if len(out) > len(expected) * 2.5:
        return 0, "NG (Over-generation/Hallucination)"

    if out.replace(" ", "") == expected.replace(" ", ""):
        return 2, "OK (Whitespace variant)"

    return 0, "NG (Mismatch)"

def run_benchmark_for_model(model_info: dict, cases: list) -> dict:
    model_id = model_info["id"]
    is_thinking = model_info["is_thinking"]
    load_model(model_id)

    total_score = 0
    max_score = len(cases) * 2
    perfect_count = 0
    latencies = []
    category_scores = {}
    details = []

    log(f"Starting test for {model_info['name']} ({len(cases)} cases)...")
    t_start = time.time()

    for idx, case in enumerate(cases):
        ctx = case["left_context"]
        target = case["target"]
        cat = case.get("category", "その他")
        if cat not in category_scores:
            category_scores[cat] = {"score": 0, "max": 0, "perfect": 0, "count": 0}
        category_scores[cat]["count"] += 1
        category_scores[cat]["max"] += 2

        user_content = f"前方文脈：{ctx}\n変換対象：{target}\n出力："
        messages = [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_content}
        ]
        if is_thinking:
            messages.append({"role": "assistant", "content": "</think>\n"})

        payload = {
            "model": model_id,
            "messages": messages,
            "temperature": 0.0,
            "max_tokens": 32
        }

        t0 = time.time()
        try:
            req = urllib.request.Request(
                "http://127.0.0.1:1234/v1/chat/completions",
                headers={"Content-Type": "application/json"},
                data=json.dumps(payload).encode("utf-8")
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                res = json.loads(resp.read().decode("utf-8"))
            lat = (time.time() - t0) * 1000
            latencies.append(lat)
            msg = res["choices"][0]["message"]
            out_raw = msg.get("content", "").strip()
        except Exception as e:
            lat = (time.time() - t0) * 1000
            latencies.append(lat)
            out_raw = f"[ERROR: {e}]"

        score, reason = evaluate_output(out_raw, case)
        total_score += score
        if score == 2:
            perfect_count += 1
            category_scores[cat]["perfect"] += 1
        category_scores[cat]["score"] += score

        details.append({
            "id": case["id"],
            "category": cat,
            "target": target,
            "expected": case["expected"],
            "output": out_raw,
            "score": score,
            "reason": reason,
            "latency_ms": round(lat, 1)
        })

        if (idx + 1) % 50 == 0 or idx + 1 == len(cases):
            log(f"  [{idx+1}/{len(cases)}] Current Score: {total_score}/{ (idx+1)*2 } (Perfect: {perfect_count}) - Avg Latency: {sum(latencies)/len(latencies):.1f}ms")

    t_elapsed = time.time() - t_start
    avg_latency = sum(latencies) / len(latencies) if latencies else 0.0

    log(f"\nResult for {model_info['name']}:")
    log(f"  Total Score: {total_score} / {max_score} ({total_score/max_score*100:.1f}%)")
    log(f"  Perfect Matches: {perfect_count} / {len(cases)} ({perfect_count/len(cases)*100:.1f}%)")
    log(f"  Avg Latency: {avg_latency:.1f} ms | Elapsed: {t_elapsed:.1f} s")

    unload_model(model_id)

    return {
        "model_id": model_id,
        "name": model_info["name"],
        "tag": model_info["tag"],
        "total_score": total_score,
        "max_score": max_score,
        "accuracy_pct": round(total_score / max_score * 100, 1),
        "perfect_count": perfect_count,
        "total_cases": len(cases),
        "avg_latency_ms": round(avg_latency, 1),
        "elapsed_sec": round(t_elapsed, 1),
        "category_scores": category_scores,
        "details": details
    }

def main():
    if os.path.exists(LOG_FILE):
        os.remove(LOG_FILE)

    cases = load_dataset()
    log(f"Loaded {len(cases)} test cases from kana_kanji_llm_testset_5x.jsonl")

    results = []
    for m in MODELS:
        res = run_benchmark_for_model(m, cases)
        results.append(res)
        with open('artifacts/results/benchmark_5x_direct_results.json', "w", encoding="utf-8") as f:
            json.dump(results, f, ensure_ascii=False, indent=2)

    log("\n================ BENCHMARK ALL DONE ================")

if __name__ == "__main__":
    main()
