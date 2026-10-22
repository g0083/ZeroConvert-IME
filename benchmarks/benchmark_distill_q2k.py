
from pathlib import Path
REPO_ROOT = Path(__file__).resolve().parents[1]

import json
import re
import time
import urllib.request
import subprocess
import sys
import os

sys.stdout.reconfigure(encoding='utf-8')
from benchmark_jev_zenzai import BENCHMARK_CASES

MODELS = [
    {
        "id": "glm4.7-distill-lfm2.5-1.2b-i1@q2_k",
        "name": "GLM-4.7 Distill 1.2B (Q2_K / 461MB)",
        "tag": "glm47_q2k"
    },
    {
        "id": "lfm2.5-1.2b-deepseek-3.1-thinking-distill-i1@q2_k",
        "name": "DeepSeek-3.1 Thinking Distill 1.2B (Q2_K / 461MB)",
        "tag": "deepseek31_q2k"
    },
    {
        "id": "lfm2.5-1.2b-thinking-claude-4.6-opus-heretic-uncensored-distill@q2_k",
        "name": "Claude 4.6 Opus Distill 1.2B (Q2_K / 461MB)",
        "tag": "opus_q2k"
    }
]

CATEGORIES = {
    "A. 基本変換": list(range(1, 6)),
    "B. 同音異義語": list(range(6, 15)),
    "C. 文脈分岐": list(range(15, 22)),
    "D. 長い文脈": list(range(22, 25)),
    "E. 英数字保持": list(range(25, 30)),
    "F. 固有名詞": list(range(30, 33)),
    "G. 補完防止": list(range(33, 38)),
    "H. 校正防止": list(range(38, 41)),
    "I. 不明語維持": list(range(41, 44)),
    "J. 句読点保持": list(range(44, 47)),
}

def load_model(model_id: str):
    print(f"\n[LMS LOAD] Loading {model_id} (Context=2048, GPU=max)...")
    cmd = ["lms", "load", model_id, "-c", "2048", "--gpu", "max", "-y"]
    r = subprocess.run(cmd, capture_output=True, encoding='utf-8', errors='ignore')
    print(r.stdout.strip()[:150])
    time.sleep(2)

def unload_model(model_id: str):
    print(f"[LMS UNLOAD] Unloading {model_id}...")
    subprocess.run(["lms", "unload", model_id], capture_output=True)
    time.sleep(1)

def detect_is_thinking(model_id: str) -> bool:
    payload = {
        "model": model_id,
        "messages": [{"role": "user", "content": "1+1=? 数字だけで答えて"}],
        "max_tokens": 10,
        "temperature": 0.0
    }
    req = urllib.request.Request(
        "http://127.0.0.1:1234/v1/chat/completions",
        headers={"Content-Type": "application/json"},
        data=json.dumps(payload).encode("utf-8")
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        msg = data["choices"][0]["message"]
        is_think = bool(msg.get("reasoning_content")) or ("<think>" in msg.get("content", ""))
        print(f"[{model_id}] Thinking Model Detected: {is_think}")
        return is_think
    except Exception as e:
        print(f"[{model_id}] Detection Error: {e}, defaulting to False")
        return False

def run_case(case: dict, model_id: str, is_thinking: bool):
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
    req = urllib.request.Request(
        "http://127.0.0.1:1234/v1/chat/completions",
        headers={"Content-Type": "application/json"},
        data=json.dumps(payload).encode("utf-8")
    )
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        t1 = time.perf_counter()
        
        content_items = data["choices"][0]["logprobs"]["content"]
        if content_items and "top_logprobs" in content_items[0]:
            top_lps = content_items[0]["top_logprobs"]
        else:
            top_lps = []
        
        cand_logprobs = {}
        for item in top_lps:
            t = item["token"].strip()
            if t in [str(i+1) for i in range(len(cands))]:
                cand_logprobs[int(t) - 1] = item["logprob"]
                
        if cand_logprobs:
            best_idx = max(cand_logprobs.items(), key=lambda x: x[1])[0]
            selected = cands[best_idx]
        else:
            gen_c = data["choices"][0]["message"].get("content", "").strip()
            m = re.search('\\b([1-9])\\b', gen_c)
            idx = int(m.group(1)) - 1 if m else 0
            selected = cands[idx] if 0 <= idx < len(cands) else cands[0]
            
        return {
            "success": True,
            "selected": selected,
            "cand_logprobs": cand_logprobs,
            "duration_ms": round((t1 - t0) * 1000, 1)
        }
    except Exception as e:
        return {
            "success": False,
            "selected": cands[0],
            "error": str(e),
            "duration_ms": 0.0
        }

def evaluate_selection(selected: str, expected: str):
    is_exact = (selected == expected)
    is_acceptable = is_exact or (re.sub('[\\s、。]', '', selected) == re.sub('[\\s、。]', '', expected))
    score = 2 if is_exact else (1 if is_acceptable else 0)
    return score

def run_benchmark_for_model(m_info: dict):
    m_id = m_info["id"]
    m_name = m_info["name"]
    load_model(m_id)
    is_thinking = detect_is_thinking(m_id)
    
    cases = BENCHMARK_CASES
    results = []
    total_score = 0
    exact_count = 0
    total_latency = 0.0
    
    print(f"\n--- Testing {m_name} (Thinking={is_thinking}) ---")
    for case in cases:
        cid = case["id"]
        exp = case["expected"]
        
        res = run_case(case, m_id, is_thinking)
        selected = res["selected"]
        score = evaluate_selection(selected, exp)
        is_exact = (score == 2)
        
        total_score += score
        if is_exact:
            exact_count += 1
        total_latency += res["duration_ms"]
        
        mark = "O" if score == 2 else ("^" if score == 1 else "X")
        print(f"#{cid:02s} {mark} ({score}pt) {res['duration_ms']:5.1f}ms | 期待:{exp} | 選択:{selected}")
        
        results.append({
            "id": cid,
            "context": case["context"],
            "target": case["target"],
            "expected": exp,
            "selected": selected,
            "score": score,
            "is_exact": is_exact,
            "duration_ms": res["duration_ms"],
            "cand_logprobs": res.get("cand_logprobs", {})
        })
        
    avg_latency = round(total_latency / len(cases), 1)
    exact_rate = round(exact_count / len(cases) * 100, 1)
    
    cat_scores = {}
    for cat_name, qids in CATEGORIES.items():
        s = sum(r["score"] for r in results if int(r["id"]) in qids)
        cat_scores[cat_name] = s
        
    unload_model(m_id)
    
    return {
        "model_id": m_id,
        "model_name": m_name,
        "tag": m_info["tag"],
        "is_thinking": is_thinking,
        "total_score": total_score,
        "max_score": len(cases) * 2,
        "exact_count": exact_count,
        "exact_rate": exact_rate,
        "avg_duration_ms": avg_latency,
        "cat_scores": cat_scores,
        'results': results
    }

def main():
    all_summaries = {}
    
    for m in MODELS:
        res = run_benchmark_for_model(m)
        all_summaries[m["tag"]] = res
        
    with open(str(REPO_ROOT / 'artifacts/results/benchmark_distill_q2k_results.json'), "w", encoding="utf-8") as f:
        json.dump(all_summaries, f, ensure_ascii=False, indent=2)
        
    print("\n=======================================================")
    print(" 蒸留3種 Q2_K (461MB) 検証 集計結果サマリー")
    print("=======================================================")
    print(f"{'モデル名':50s} | {'スコア (92)':12s} | {'完全一致':12s} | {'平均遅延':10s}")
    print("-" * 90)
    for tag, s in all_summaries.items():
        print(f"{s['model_name']:50s} | {s['total_score']:2d} / 92      | {s['exact_count']:2d}問 ({s['exact_rate']}%) | {s['avg_duration_ms']:5.1f} ms")

if __name__ == "__main__":
    main()
