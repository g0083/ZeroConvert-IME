# -*- coding: utf-8 -*-
"""
誤入力補正・入力内容先読み 包括的比較ベンチマークスクリプト
比較対象:
1. 辞書/ルールベース (Dictionary Baseline)
2. ZeroCon AI (zenz-v3.2 on llama-server)
3. LFM 2.5 230M (LM Studio)
4. LFM 2.5 350M (LM Studio)
5. [参考] LFM 2.5 1.2B (LM Studio)
"""

import sys
import os
import json
import time
import urllib.request
import re

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

from dictionary_baseline import rule_based_typo_correct, dictionary_predictive_completion

CONTEXT_TOKEN = "\uEE02"
INPUT_START_TOKEN = "\uEE00"
OUTPUT_START_TOKEN = "\uEE01"

def hira_to_kata(text: str) -> str:
    return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])

def clean_output(text: str) -> str:
    # 記号や改行、余計な空白をトリム
    text = text.replace("\r", "").replace("\n", " ").strip()
    text = text.strip('「」"\'')
    if text.startswith("出力：") or text.startswith("続き：") or text.startswith("正解：") or text.startswith("修正："):
        text = text.split("：", 1)[1].strip()
    return text

# --- 各エンジンの呼び出し関数 ---

def run_zerocon_ai_typo(context: str, raw_input: str) -> tuple[str, float]:
    target_kata = hira_to_kata(raw_input)
    prompt = f"{CONTEXT_TOKEN}{context}{INPUT_START_TOKEN}{target_kata}{OUTPUT_START_TOKEN}"
    payload = {
        "prompt": prompt,
        "n_predict": 16,
        "temperature": 0.0,
        "stop": ["</s>", "\uEE00", "\uEE01", "\uEE02"]
    }
    t0 = time.perf_counter()
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:8090/completion",
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload).encode("utf-8")
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            out = clean_output(data.get("content", ""))
    except Exception as e:
        out = f"Error: {e}"
    lat = (time.perf_counter() - t0) * 1000.0
    return out, lat

def run_zerocon_ai_predictive(context: str, partial_input: str) -> tuple[str, float]:
    target_kata = hira_to_kata(partial_input)
    prompt = f"{CONTEXT_TOKEN}{context}{INPUT_START_TOKEN}{target_kata}"
    payload = {
        "prompt": prompt,
        "n_predict": 24,
        "temperature": 0.0,
        "stop": ["</s>", "\uEE02"]
    }
    t0 = time.perf_counter()
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:8090/completion",
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload).encode("utf-8")
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            raw = data.get("content", "").strip()
            if OUTPUT_START_TOKEN in raw:
                out = raw.split(OUTPUT_START_TOKEN)[-1].strip()
            else:
                out = raw
            out = clean_output(out)
    except Exception as e:
        out = f"Error: {e}"
    lat = (time.perf_counter() - t0) * 1000.0
    return out, lat

def run_lmstudio_typo(model_id: str, context: str, raw_input: str) -> tuple[str, float]:
    prompt = (
        f"文脈を考慮して、打ち間違いのある入力文字列を正しい漢字かな交じり文へ修正してください。\n"
        f"文脈: {context}\n"
        f"入力: {raw_input}\n"
        f"修正結果:"
    )
    payload = {
        "model": model_id,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 20,
        "temperature": 0.0,
        "stop": ["\n", "文脈:", "入力:"]
    }
    t0 = time.perf_counter()
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:1234/v1/chat/completions",
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload).encode("utf-8")
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            out = clean_output(data["choices"][0]["message"]["content"])
    except Exception as e:
        out = f"Error: {e}"
    lat = (time.perf_counter() - t0) * 1000.0
    return out, lat

def run_lmstudio_predictive(model_id: str, context: str, partial_input: str) -> tuple[str, float]:
    prompt = (
        f"文脈と入力途中の文字列を読み取り、それに続く自然なフレーズ（先読み補完）を出力してください。\n"
        f"文脈: {context}\n"
        f"入力途中: {partial_input}\n"
        f"続き:"
    )
    payload = {
        "model": model_id,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 20,
        "temperature": 0.0,
        "stop": ["\n", "文脈:", "入力途中:"]
    }
    t0 = time.perf_counter()
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:1234/v1/chat/completions",
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload).encode("utf-8")
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            out = clean_output(data["choices"][0]["message"]["content"])
            # もし入力文字列を含んでいたらトリム
            if out.startswith(partial_input):
                out = out[len(partial_input):].strip()
    except Exception as e:
        out = f"Error: {e}"
    lat = (time.perf_counter() - t0) * 1000.0
    return out, lat

# --- ベンチマーク評価本体 ---

def evaluate_typo_case(out: str, case: dict) -> int:
    """
    2: 完全一致 (Exact Match)
    1: 許容解一致 (Acceptable Match)
    0: 不一致 (Mismatch)
    """
    out_clean = out.strip()
    exp = case["expected"].strip()
    acc = [a.strip() for a in case.get("acceptable", [])]

    if out_clean == exp:
        return 2
    if out_clean in acc:
        return 1
    # 記号除去後の比較
    norm_out = re.sub(r"[!！?？。、 　]", "", out_clean)
    norm_exp = re.sub(r"[!！?？。、 　]", "", exp)
    if norm_out == norm_exp and norm_out:
        return 2
    for a in acc:
        if norm_out == re.sub(r"[!！?？。、 　]", "", a) and norm_out:
            return 1
    return 0

def evaluate_pred_case(out: str, case: dict) -> int:
    out_clean = out.strip()
    exp = case["expected_continuation"].strip()
    acc = [a.strip() for a in case.get("acceptable_continuations", [])]
    full = case.get("full_sentence", "").strip()

    if out_clean == exp:
        return 2
    if out_clean in acc:
        return 1
    
    norm_out = re.sub(r"[!！?？。、 　]", "", out_clean)
    norm_exp = re.sub(r"[!！?？。、 　]", "", exp)
    if norm_out == norm_exp and norm_out:
        return 2
    for a in acc:
        if norm_out == re.sub(r"[!！?？。、 　]", "", a) and norm_out:
            return 1
    
    # 部分一致判定（先読みの先頭が期待値に適合しているか）
    if norm_exp.startswith(norm_out) and len(norm_out) >= 2:
        return 1
    if norm_out.startswith(norm_exp) and len(norm_exp) >= 2:
        return 1
    return 0

def main():
    print("=" * 60)
    print(" 誤入力補正 & 入力内容先読み 包括的ベンチマークテスト")
    print("=" * 60)

    # 1. データセット読み込み
    with open('benchmarks/research/typo_testset.jsonl', "r", encoding="utf-8") as f:
        typo_cases = [json.loads(line) for line in f if line.strip()]

    with open('benchmarks/research/predictive_testset.jsonl', "r", encoding="utf-8") as f:
        pred_cases = [json.loads(line) for line in f if line.strip()]

    print(f"データセットロード完了: 誤入力補正={len(typo_cases)}件, 先読み={len(pred_cases)}件\n")

    MODELS = [
        ("Dictionary", "辞書/ルールベース", "dict"),
        ("ZeroConAI", "ZeroCon AI (zenz-v3.2)", "zerocon"),
        ("LFM-230M", "LFM 2.5 230M (Q4_K_M)", "lfm230"),
        ("LFM-350M", "LFM 2.5 350M", "lfm350"),
        ("LFM-1.2B", "LFM 2.5 1.2B Instruct", "lfm1.2b")
    ]

    results = {
        "typo": {},
        "predictive": {}
    }

    # ==========================
    # Part 1: 誤入力補正ベンチマーク
    # ==========================
    print("--- [Part 1: 誤入力補正ベンチマーク実行中] ---")
    for mod_key, mod_name, mod_type in MODELS:
        print(f"\n>> 評価対象: {mod_name}")
        scores = []
        latencies = []
        logs = []

        for c in typo_cases:
            ctx = c["context"]
            raw_in = c["raw_input"]
            raw_ro = c.get("raw_romaji", "")

            t0 = time.perf_counter()
            if mod_type == "dict":
                out = rule_based_typo_correct(raw_in, raw_ro)
                lat = (time.perf_counter() - t0) * 1000.0
            elif mod_type == "zerocon":
                out, lat = run_zerocon_ai_typo(ctx, raw_in)
            elif mod_type == "lfm230":
                out, lat = run_lmstudio_typo("lfm2.5-230m@q4_k_m", ctx, raw_in)
            elif mod_type == "lfm350":
                out, lat = run_lmstudio_typo("liquidai/lfm2.5-350m", ctx, raw_in)
            elif mod_type == "lfm1.2b":
                out, lat = run_lmstudio_typo("lfm2.5-1.2b-instruct", ctx, raw_in)

            score = evaluate_typo_case(out, c)
            scores.append(score)
            latencies.append(lat)
            logs.append({
                "id": c["id"],
                "category": c["category"],
                "input": raw_in,
                "expected": c["expected"],
                "output": out,
                "score": score,
                "latency_ms": lat
            })

        exact_cnt = sum(1 for s in scores if s == 2)
        acc_cnt = sum(1 for s in scores if s >= 1)
        total = len(scores)
        exact_rate = exact_cnt / total * 100.0
        acc_rate = acc_cnt / total * 100.0
        avg_lat = sum(latencies) / len(latencies)

        print(f"   完全一致率: {exact_rate:.1f}% ({exact_cnt}/{total}) | 許容一致率: {acc_rate:.1f}% ({acc_cnt}/{total}) | 平均遅延: {avg_lat:.2f}ms")

        results["typo"][mod_key] = {
            "name": mod_name,
            "exact_rate": exact_rate,
            "acceptable_rate": acc_rate,
            "avg_latency_ms": avg_lat,
            "details": logs
        }

    # ==========================
    # Part 2: 入力内容先読みベンチマーク
    # ==========================
    print("\n--- [Part 2: 入力内容先読みベンチマーク実行中] ---")
    for mod_key, mod_name, mod_type in MODELS:
        print(f"\n>> 評価対象: {mod_name}")
        scores = []
        latencies = []
        logs = []

        for c in pred_cases:
            ctx = c["context"]
            partial = c["partial_input"]

            t0 = time.perf_counter()
            if mod_type == "dict":
                out = dictionary_predictive_completion(partial)
                lat = (time.perf_counter() - t0) * 1000.0
            elif mod_type == "zerocon":
                out, lat = run_zerocon_ai_predictive(ctx, partial)
            elif mod_type == "lfm230":
                out, lat = run_lmstudio_predictive("lfm2.5-230m@q4_k_m", ctx, partial)
            elif mod_type == "lfm350":
                out, lat = run_lmstudio_predictive("liquidai/lfm2.5-350m", ctx, partial)
            elif mod_type == "lfm1.2b":
                out, lat = run_lmstudio_predictive("lfm2.5-1.2b-instruct", ctx, partial)

            score = evaluate_pred_case(out, c)
            scores.append(score)
            latencies.append(lat)
            logs.append({
                "id": c["id"],
                "category": c["category"],
                "input": partial,
                "expected": c["expected_continuation"],
                "output": out,
                "score": score,
                "latency_ms": lat
            })

        exact_cnt = sum(1 for s in scores if s == 2)
        acc_cnt = sum(1 for s in scores if s >= 1)
        total = len(scores)
        exact_rate = exact_cnt / total * 100.0
        acc_rate = acc_cnt / total * 100.0
        avg_lat = sum(latencies) / len(latencies)

        print(f"   完全一致率: {exact_rate:.1f}% ({exact_cnt}/{total}) | 許容一致率: {acc_rate:.1f}% ({acc_cnt}/{total}) | 平均遅延: {avg_lat:.2f}ms")

        results["predictive"][mod_key] = {
            "name": mod_name,
            "exact_rate": exact_rate,
            "acceptable_rate": acc_rate,
            "avg_latency_ms": avg_lat,
            "details": logs
        }

    # 結果をJSONとして保存
    with open('benchmarks/research/benchmark_results.json', "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print("\n" + "=" * 60)
    print(" 全ベンチマーク完了！結果を research/benchmark_results.json に保存しました。")
    print("=" * 60)

if __name__ == "__main__":
    main()
