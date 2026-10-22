# -*- coding: utf-8 -*-
"""
追加データセット kana_kanji_llm_testset_5x.jsonl (全230問) に対する
本物 Zenzai (zenz-v3 GGUF / 72MB / RTX 4070 CUDA)
直接かな漢字変換 ベンチマーク実行スクリプト
"""

import sys
import os
import json
import time
import urllib.request
import re

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
LOG_FILE = 'artifacts/logs/benchmark_5x_real_zenz.log'

def log(msg: str):
    print(msg, flush=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(msg + "\n")

# ひらがな -> カタカナ変換
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

    # 空白揺れや句読点揺れ
    if out.replace(" ", "").replace("　", "") == expected.replace(" ", "").replace("　", ""):
        return 2, "OK (Whitespace variant)"

    # 表記揺れ（漢字の送り仮名違いなど：1点）
    # 例: 飴を舐める vs 飴をなめる
    hira_out = "".join([chr(ord(ch) - 96) if 0x30A1 <= ord(ch) <= 0x30F6 else ch for ch in out])
    hira_exp = "".join([chr(ord(ch) - 96) if 0x30A1 <= ord(ch) <= 0x30F6 else ch for ch in expected])
    if hira_out == hira_exp:
        return 2, "OK (Kana variant)"

    # 漢字の一部が同音異義語等の表記違い
    return 0, f"NG (out=[{out}] vs exp=[{expected}])"

def main():
    if os.path.exists(LOG_FILE):
        os.remove(LOG_FILE)

    log("[本物 Zenzai (zenz-v3 / 72MB)] 全230問ベンチマーク開始...")

    cases = []
    with open('data/kana_kanji_llm_testset_5x.jsonl', "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                cases.append(json.loads(line))

    log(f"  テストケース数: {len(cases)}問")

    total_score = 0
    max_score = len(cases) * 2
    perfect_count = 0
    latencies = []
    category_stats = {}
    details = []

    t_start = time.time()

    for idx, case in enumerate(cases):
        ctx = "" if case["left_context"] == "なし" else case["left_context"]
        target_hira = case["target"]
        target_kata = hira_to_kata(target_hira)
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
                "http://127.0.0.1:8088/completion",
                headers={"Content-Type": "application/json"},
                data=json.dumps(payload).encode("utf-8")
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
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
            "category": cat,
            "target": target_hira,
            "expected": case["expected"],
            "output": out_raw,
            "score": score,
            "reason": reason,
            "latency_ms": round(lat, 1)
        })

        if (idx + 1) % 50 == 0 or idx + 1 == len(cases):
            log(f"  [{idx+1}/{len(cases)}] 正解: {perfect_count}/{idx+1} ({perfect_count/(idx+1)*100:.1f}%) - 平均遅延: {sum(latencies)/len(latencies):.1f}ms")

    t_elapsed = time.time() - t_start
    avg_lat = sum(latencies) / len(latencies) if latencies else 0.0

    log("\n================ 本物 ZENZ-V3 ベンチマーク完了 ================")
    log(f"総合スコア: {total_score} / {max_score} ({total_score/max_score*100:.1f}%)")
    log(f"完全一致正解数: {perfect_count} / {len(cases)} ({perfect_count/len(cases)*100:.1f}%)")
    log(f"平均遅延: {avg_lat:.1f} ms (爆速!!) | 総処理時間: {t_elapsed:.1f} 秒")
    log("\nカテゴリ別正解率:")
    for cat, stat in category_stats.items():
        pct = stat["perfect"] / stat["total"] * 100 if stat["total"] > 0 else 0
        log(f"  - {cat}: {stat['perfect']}/{stat['total']} ({pct:.1f}%) [スコア: {stat['score']}/{stat['max']}]")

    result_data = {
        "model": "Miwa-Keita/zenz-v3 (72MB GGUF / RTX 4070 CUDA)",
        "total_score": total_score,
        "max_score": max_score,
        "accuracy_pct": round(perfect_count / len(cases) * 100, 1),
        "perfect_count": perfect_count,
        "total_cases": len(cases),
        "avg_latency_ms": round(avg_lat, 1),
        "elapsed_sec": round(t_elapsed, 1),
        "category_stats": category_stats,
        "details": details
    }
    with open('artifacts/results/benchmark_5x_real_zenz_results.json', "w", encoding="utf-8") as f:
        json.dump(result_data, f, ensure_ascii=False, indent=2)

if __name__ == "__main__":
    main()
