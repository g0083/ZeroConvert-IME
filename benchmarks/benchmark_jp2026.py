
from pathlib import Path
REPO_ROOT = Path(__file__).resolve().parents[1]

import json
import re
import time
import urllib.request
import sys
import os

sys.stdout.reconfigure(encoding='utf-8')
from benchmark_jev_zenzai import BENCHMARK_CASES

OUTPUT_PATH = str(REPO_ROOT / 'artifacts/results/benchmark_jp2026_results.json')
MODEL_ID = "lfm2.5-1.2b-jp-202606"

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

def run_case(case: dict):
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

    payload = {
        "model": MODEL_ID,
        "messages": [{"role": "user", "content": prompt}],
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

def main():
    print(f"=== 案C on {MODEL_ID} (Context=2048, 730MB) ===")
    cases = BENCHMARK_CASES
    results = []
    total_score = 0
    exact_count = 0
    total_latency = 0.0
    
    for case in cases:
        cid = case["id"]
        exp = case["expected"]
        
        res = run_case(case)
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
        
    summary = {
        "model_id": MODEL_ID,
        "total_cases": len(cases),
        "total_score": total_score,
        "max_score": len(cases) * 2,
        "exact_count": exact_count,
        "exact_rate": exact_rate,
        "avg_duration_ms": avg_latency,
        "cat_scores": cat_scores,
        'results': results
    }
    
    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
        
    print("\n==========================================")
    print(f"モデル: {MODEL_ID}")
    print(f"合計スコア: {total_score} / {len(cases) * 2}")
    print(f"完全一致数: {exact_count} / {len(cases)} ({exact_rate}%)")
    print(f"平均遅延  : {avg_latency} ms")
    print("==========================================")
    for c, s in cat_scores.items():
        max_s = len(CATEGORIES[c]) * 2
        print(f"  {c:15s}: {s:2d} / {max_s:2d}")

if __name__ == "__main__":
    main()
