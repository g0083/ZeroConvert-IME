# -*- coding: utf-8 -*-
"""
追加データセット kana_kanji_llm_testset_5x (全230問) に対する
アプローチ②: 双方向BERT (cl-tohoku/bert-base-japanese-v3, 111M)
Masked PLL (疑似対数尤度スコアリング) ベンチマーク実行スクリプト
"""

import sys
import os
import json
import time
import torch
import torch.nn.functional as F
from transformers import AutoTokenizer, AutoModelForMaskedLM

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
LOG_FILE = 'artifacts/logs/benchmark_5x_bert.log'

def log(msg: str):
    print(msg, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(msg + "\n")

def main():
    if os.path.exists(LOG_FILE):
        os.remove(LOG_FILE)

    log("[アプローチ②: 双方向BERT Masked PLL] モデルをロード中...")
    model_name = "cl-tohoku/bert-base-japanese-v3"
    tok = AutoTokenizer.from_pretrained(model_name, local_files_only=True)
    model = AutoModelForMaskedLM.from_pretrained(model_name, local_files_only=True)
    model.eval()

    # GPUが使えればGPU、なければCPU
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model.to(device)
    log(f"  モデルロード完了: {model_name} (デバイス: {device})")

    with open('data/benchmark_5x_cands.json', "r", encoding="utf-8") as f:
        cases = json.load(f)
    log(f"  テストケース数: {len(cases)}問")

    total_score = 0
    max_score = len(cases) * 2
    correct_count = 0
    latencies = []
    category_stats = {}
    details = []

    t_start = time.time()

    for idx, case in enumerate(cases):
        ctx = case["context"]
        cands = case["candidates"]
        cat = case.get("category", "その他")

        if cat not in category_stats:
            category_stats[cat] = {"correct": 0, "total": 0}
        category_stats[cat]["total"] += 1

        t0 = time.time()
        scores = []
        ctx_text = "" if ctx == "なし" else ctx
        ctx_ids = tok.encode(ctx_text, add_special_tokens=False)
        ctx_len = len(ctx_ids)

        for cand in cands:
            full_text = ctx_text + cand
            inputs = tok(full_text, return_tensors='pt').to(device)
            input_ids = inputs['input_ids'][0]

            start_idx = 1 + ctx_len
            end_idx = len(input_ids) - 1
            cand_token_count = max(1, end_idx - start_idx)

            # バッチ化して一括推論（高速化）
            # 各トークンを [MASK] にしたシーケンスを作成
            batch_masked = []
            target_token_ids = []
            mask_positions = []

            for pos in range(start_idx, end_idx):
                target_token_ids.append(input_ids[pos].item())
                mask_positions.append(pos)
                mids = input_ids.clone()
                mids[pos] = tok.mask_token_id
                batch_masked.append(mids)

            if batch_masked:
                batch_tensor = torch.stack(batch_masked).to(device)
                with torch.no_grad():
                    logits = model(batch_tensor).logits
                
                # 各マスク位置の対数尤度を抽出
                total_lp = 0.0
                for i, (pos, tid) in enumerate(zip(mask_positions, target_token_ids)):
                    lp = F.log_softmax(logits[i, pos], dim=-1)[tid].item()
                    total_lp += lp
                
                avg_lp = total_lp / cand_token_count
            else:
                avg_lp = -999.0

            scores.append(avg_lp)

        lat = (time.time() - t0) * 1000
        latencies.append(lat)

        # 最も対数尤度が高い候補を選択（1-based index）
        best_cand_idx = scores.index(max(scores)) + 1
        is_correct = (best_cand_idx == case["correct_idx"])
        score = 2 if is_correct else 0
        total_score += score

        if is_correct:
            correct_count += 1
            category_stats[cat]["correct"] += 1

        details.append({
            "id": case["id"],
            "category": cat,
            "target": case["target"],
            "expected": case["expected"],
            "correct_idx": case["correct_idx"],
            "selected_idx": best_cand_idx,
            "is_correct": is_correct,
            "latency_ms": round(lat, 1),
            "scores": [round(s, 4) for s in scores]
        })

        if (idx + 1) % 50 == 0 or idx + 1 == len(cases):
            cur_acc = correct_count / (idx + 1) * 100
            cur_lat = sum(latencies) / len(latencies)
            log(f"  [{idx+1}/{len(cases)}] 正解数: {correct_count}/{idx+1} (正解率: {cur_acc:.1f}%) - 平均遅延: {cur_lat:.1f}ms")

    t_elapsed = time.time() - t_start
    avg_lat = sum(latencies) / len(latencies) if latencies else 0.0

    log("\n================ BERT BENCHMARK 完了 ================")
    log(f"総合スコア: {total_score} / {max_score} ({total_score/max_score*100:.1f}%)")
    log(f"正解数: {correct_count} / {len(cases)} ({correct_count/len(cases)*100:.1f}%)")
    log(f"平均遅延: {avg_lat:.1f} ms | 総処理時間: {t_elapsed:.1f} 秒")
    log("\nカテゴリ別正解率:")
    for cat, stat in category_stats.items():
        pct = stat["correct"] / stat["total"] * 100 if stat["total"] > 0 else 0
        log(f"  - {cat}: {stat['correct']}/{stat['total']} ({pct:.1f}%)")

    # 結果を保存
    output_result = {
        "model": "cl-tohoku/bert-base-japanese-v3",
        "total_score": total_score,
        "max_score": max_score,
        "accuracy_pct": round(correct_count / len(cases) * 100, 1),
        "correct_count": correct_count,
        "total_cases": len(cases),
        "avg_latency_ms": round(avg_lat, 1),
        "elapsed_sec": round(t_elapsed, 1),
        "category_stats": category_stats,
        "details": details
    }
    with open('artifacts/results/benchmark_5x_bert_results.json', "w", encoding="utf-8") as f:
        json.dump(output_result, f, ensure_ascii=False, indent=2)

if __name__ == "__main__":
    main()
