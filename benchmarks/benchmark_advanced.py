# -*- coding: utf-8 -*-
"""
次世代IMEアーキテクチャ検証: 案A (双方向クロスエンコーダー) vs 案C (Jevロジット直接判定)
比較基準: 従来のZenzai (単方向GPT-2 110M)
"""

import sys
import os
import json
import time
import urllib.request
import re
import torch
import torch.nn.functional as F
from transformers import AutoTokenizer, AutoModelForMaskedLM, AutoModelForCausalLM

sys.stdout.reconfigure(encoding='utf-8')

# 全46問のテストケース定義（benchmark_jev_zenzai.pyと同じデータセット）
from benchmark_jev_zenzai import BENCHMARK_CASES

# ==========================================
# 案A: 双方向クロスエンコーダー (cl-tohoku/bert-base-japanese-v3, 111M)
# ==========================================
class BertCrossEncoderScorer:
    def __init__(self, model_name: str = "cl-tohoku/bert-base-japanese-v3"):
        print(f"[案A: BERT Cross-Encoder] Loading {model_name}...")
        self.tok = AutoTokenizer.from_pretrained(model_name)
        self.model = AutoModelForMaskedLM.from_pretrained(model_name)
        self.model.eval()
        self.param_count = sum(p.numel() for p in self.model.parameters())
        print(f"[案A: BERT Cross-Encoder] Loaded! {self.param_count/1e6:.1f}M params")

    def score(self, context: str, candidate: str) -> float:
        ctx_text = "" if context == "なし" else context
        full_text = ctx_text + candidate
        
        ctx_enc = self.tok.encode(ctx_text, add_special_tokens=False)
        inputs = self.tok(full_text, return_tensors='pt')
        
        with torch.no_grad():
            logits = self.model(**inputs).logits
            labels = inputs['input_ids']
            
            # 候補部分のトークンインデックス
            cand_start = 1 + len(ctx_enc)
            cand_end = labels.shape[1] - 1
            if cand_end <= cand_start:
                return -999.0
                
            cand_logits = logits[0, cand_start:cand_end]
            cand_labels = labels[0, cand_start:cand_end]
            loss = F.cross_entropy(cand_logits, cand_labels, reduction='mean')
            return -loss.item() # 疑似対数尤度（高いほど自然）

    def select_best(self, case: dict):
        ctx = case["context"]
        cands = case["candidates"]
        t0 = time.perf_counter()
        scores = []
        for c in cands:
            s = self.score(ctx, c)
            scores.append((c, s))
        t1 = time.perf_counter()
        best_cand, best_score = max(scores, key=lambda x: x[1])
        return {
            "selected": best_cand,
            "scores": scores,
            "duration_ms": round((t1 - t0) * 1000, 1)
        }

# ==========================================
# 案C: Jev ロジット直接判定 (liquidai/lfm2.5-1.2b-instruct, 1.2B)
# ==========================================
def run_jev_logit_choice(case: dict, model_id: str = "liquidai/lfm2.5-1.2b-instruct"):
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
        "model": model_id,
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
        
        top_lps = data["choices"][0]["logprobs"]["content"][0]["top_logprobs"]
        
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
            gen_c = data["choices"][0]["message"]["content"].strip()
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
    print("==================================================")
    print(" 案A (双方向クロスエンコーダー) vs 案C (Jevロジット直接判定)")
    print("==================================================")
    
    bert_scorer = BertCrossEncoderScorer("cl-tohoku/bert-base-japanese-v3")
    
    results_a = []
    results_c = []
    
    print("\n--- Running 46 test cases ---")
    for case in BENCHMARK_CASES:
        cid = case["id"]
        exp = case["expected"]
        
        # 案A: 双方向クロスエンコーダー
        res_a = bert_scorer.select_best(case)
        score_a = evaluate_selection(res_a["selected"], exp)
        results_a.append({
            "id": cid, "selected": res_a["selected"], "expected": exp,
            "score": score_a, "duration_ms": res_a["duration_ms"]
        })
        
        # 案C: Jev ロジット直接判定
        res_c = run_jev_logit_choice(case)
        score_c = evaluate_selection(res_c["selected"], exp)
        results_c.append({
            "id": cid, "selected": res_c["selected"], "expected": exp,
            "score": score_c, "duration_ms": res_c["duration_ms"]
        })
        
        mark_a = "O" if score_a == 2 else ("^" if score_a == 1 else "X")
        mark_c = "O" if score_c == 2 else ("^" if score_c == 1 else "X")
        print(f"#{cid:02s} | Target: {case['target']} | Exp: {exp}")
        print(f"   [案A: BERT ] {mark_a} ({score_a}pt): {res_a['selected']} ({res_a['duration_ms']}ms)")
        print(f"   [案C: Logit] {mark_c} ({score_c}pt): {res_c['selected']} ({res_c['duration_ms']}ms)")

    total = len(BENCHMARK_CASES)
    score_total_a = sum(r["score"] for r in results_a)
    exact_a = sum(1 for r in results_a if r["score"] == 2)
    lat_a = sum(r["duration_ms"] for r in results_a) / total
    
    score_total_c = sum(r["score"] for r in results_c)
    exact_c = sum(1 for r in results_c if r["score"] == 2)
    lat_c = sum(r["duration_ms"] for r in results_c) / total
    
    print("\n==================================================")
    print(" ベンチマーク集計サマリー")
    print("==================================================")
    print(f"案A (双方向BERT 111M)  : スコア {score_total_a}/{total*2} (完全一致: {exact_a}/{total}, 平均遅延: {lat_a:.1f}ms, メモリ: ~220MB)")
    print(f"案C (Jev Logit 1.2B) : スコア {score_total_c}/{total*2} (完全一致: {exact_c}/{total}, 平均遅延: {lat_c:.1f}ms, メモリ: ~1.2GB)")
    
    output = {
        "plan_a": {
            "name": "案A: 双方向クロスエンコーダー (cl-tohoku/bert-base-japanese-v3)",
            "memory_mb": 220,
            "params_m": 111,
            "score": score_total_a,
            "exact_count": exact_a,
            "avg_latency_ms": round(lat_a, 1),
            'results': results_a
        },
        "plan_c": {
            "name": "案C: Jev ロジット直接判定 (liquidai/lfm2.5-1.2b-instruct)",
            "memory_mb": 1250,
            "params_m": 1200,
            "score": score_total_c,
            "exact_count": exact_c,
            "avg_latency_ms": round(lat_c, 1),
            'results': results_c
        }
    }
    
    with open('artifacts/results/benchmark_advanced_results.json', "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)
    print('artifacts/results/Saved to benchmark_advanced_results.json')

if __name__ == "__main__":
    main()
