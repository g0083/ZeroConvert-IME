
from pathlib import Path
REPO_ROOT = Path(__file__).resolve().parents[1]

import json
import re
import time
import urllib.request
import os

import sys
sys.stdout.reconfigure(encoding='utf-8')
from benchmark_jev_zenzai import BENCHMARK_CASES
OUTPUT_PATH = str(REPO_ROOT / 'artifacts/results/benchmark_c_26b_dspark_results.json')
MODEL_ID = "lfm2.5-2.6b-dspark"

def run_jev_logit_choice_26b(case: dict):
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

    # 思考なし(thinking無効化)のために assistant prefix </think>\n を渡す
    payload = {
        "model": MODEL_ID,
        "messages": [
            {"role": "user", "content": prompt},
            {"role": "assistant", "content": "</think>\n"}
        ],
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
        
        # '1'〜'N'のトークンのlogprobを探索
        cand_logprobs = {}
        for item in top_lps:
            t = item["token"].strip()
            if t in [str(i+1) for i in range(len(cands))]:
                cand_logprobs[int(t) - 1] = item["logprob"]
                
        # 最も確率の高いインデックスを選択
        if cand_logprobs:
            best_idx = max(cand_logprobs.items(), key=lambda x: x[1])[0]
            selected = cands[best_idx]
        else:
            # top_logprobsに含まれない場合はfallbackとしてgenerated content
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
    print(f"=== 案C (Jev ロジット直接判定) on {MODEL_ID} (Context=2k, 思考オフ) ===")
    cases = BENCHMARK_CASES
        
    results = []
    total_score = 0
    exact_count = 0
    total_latency = 0.0
    
    for case in cases:
        cid = case["id"]
        expected = case["expected"]
        
        res = run_jev_logit_choice_26b(case)
        selected = res["selected"]
        score = evaluate_selection(selected, expected)
        is_exact = (score == 2)
        
        total_score += score
        if is_exact:
            exact_count += 1
        total_latency += res["duration_ms"]
        
        mark = "O" if score == 2 else ("^" if score == 1 else "X")
        print(f"#{cid:02s} {mark} ({score}pt) {res['duration_ms']:5.1f}ms | 期待:{expected} | 選択:{selected}")
        
        results.append({
            "id": cid,
            "context": case["context"],
            "target": case["target"],
            "expected": expected,
            "selected": selected,
            "score": score,
            "is_exact": is_exact,
            "duration_ms": res["duration_ms"],
            "cand_logprobs": res.get("cand_logprobs", {})
        })
        
    avg_latency = round(total_latency / len(cases), 1)
    exact_rate = round(exact_count / len(cases) * 100, 1)
    
    summary = {
        "model": MODEL_ID,
        "context_length": 2048,
        "thinking": "disabled_via_prefix",
        "total_cases": len(cases),
        "total_score": total_score,
        "max_score": len(cases) * 2,
        "exact_count": exact_count,
        "exact_rate": exact_rate,
        "avg_duration_ms": avg_latency,
        'results': results
    }
    
    with open(OUTPUT_PATH, "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
        
    print("\n==========================================")
    print(f"モデル: {MODEL_ID} (Q4_K_M, Context 2048, 思考オフ)")
    print(f"合計スコア: {total_score} / {len(cases) * 2}")
    print(f"完全一致数: {exact_count} / {len(cases)} ({exact_rate}%)")
    print(f"平均遅延  : {avg_latency} ms")
    print("==========================================")

if __name__ == "__main__":
    main()
