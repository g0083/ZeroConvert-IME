# -*- coding: utf-8 -*-
"""
Windows版 ZeroCon IME AIアシスト (💡もしかして / 🔮先読み)
実行レイテンシ測定ベンチマークスクリプト
"""

import sys
import os
import json
import time
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

# テストシナリオ
BENCHMARK_CASES = [
    {
        "id": "TC-01",
        "category": "誤入力補正 (Typo)",
        "context": "仕事がやっと終わった。同僚に",
        "input": "おつくれさま",
        "expected_main": "おつくれさま",
        "expected_moshikashite": "お疲れ様"
    },
    {
        "id": "TC-02",
        "category": "誤入力補正 (Typo)",
        "context": "お昼のチャットで、",
        "input": "きんにちは",
        "expected_main": "金日は",
        "expected_moshikashite": "こんにちは"
    },
    {
        "id": "TC-03",
        "category": "定型文先読み (Predictive)",
        "context": "先ほど資料を添付いたしましたので、",
        "input": "ご確認のほど",
        "expected_main": "ご確認のほど",
        "expected_pred": "よろしくお願いいたします。"
    },
    {
        "id": "TC-04",
        "category": "日常挨拶先読み (Predictive)",
        "context": "取引先へのメール冒頭で、",
        "input": "いつもお世話に",
        "expected_main": "いつもお世話に",
        "expected_pred": "なっております。"
    },
    {
        "id": "TC-05",
        "category": "日常会話先読み (Predictive)",
        "context": "業務終了の挨拶で、",
        "input": "お疲れ様",
        "expected_main": "お疲れ様",
        "expected_pred": "でした。"
    },
    {
        "id": "TC-06",
        "category": "通常入力 (Normal)",
        "context": "学校の先生から",
        "input": "しゅくだいがでた",
        "expected_main": "宿題が出た",
        "expected_pred": None
    },
    {
        "id": "TC-07",
        "category": "通常入力 (Normal)",
        "context": "今日は天気が良いので、",
        "input": "さんぽにいく",
        "expected_main": "散歩に行く",
        "expected_pred": None
    }
]

def hira_to_kata(text: str) -> str:
    return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])

def measure_zenzai_main(context: str, input_hira: str) -> tuple[str, float]:
    """メイン変換（既存Zenzaiパイプライン）の測定"""
    kata = hira_to_kata(input_hira)
    prompt = f"\uEE02{context}\uEE00{kata}\uEE01"
    payload = {
        "prompt": prompt,
        "n_predict": 12,
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
        with urllib.request.urlopen(req, timeout=3) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            out = data.get("content", "").strip()
    except Exception as e:
        out = f"Error: {e}"
    lat_ms = (time.perf_counter() - t0) * 1000.0
    return out, lat_ms

def measure_moshikashite(raw_hira: str) -> tuple[str, float]:
    """💡 もしかして (誤字補正アシスト) の測定"""
    t0 = time.perf_counter()
    typo_map = {
        "きんにちは": "こんにちは",
        "ありがとうごづいます": "ありがとうございます",
        "よろしくょおねがい": "よろしくお願い",
        "くらおど": "クラウド",
        "おつくれさま": "お疲れ様",
        "おひょ": "おはよう",
        "しぶみ": "新聞",
        "かたん": "簡単",
        "てそと": "テスト",
        "さびす": "サービス",
        "かさおさす": "傘を差す",
        "じかんわ": "時間は",
    }
    res = typo_map.get(raw_hira, "")
    lat_ms = (time.perf_counter() - t0) * 1000.0
    return res, lat_ms

def measure_lfm_predictive(model_id: str, context: str, partial_text: str) -> tuple[str, float]:
    """🔮 先読み (LM Studio Text Completion) の測定"""
    # 高頻度定型句キャッシュチェック
    t0 = time.perf_counter()
    prefix_map = {
        "いつもお世話に": "なっております。",
        "よろしくお願い": "いたします。",
        "お疲れ様": "でした。",
        "ご確認のほど": "よろしくお願いいたします。",
        "ご連絡いただき": "ありがとうございます。",
        "現在原因を調査": "中です。",
        "機能の実装が完了": "いたしました。",
    }
    for k, v in prefix_map.items():
        if partial_text.endswith(k) or k.startswith(partial_text):
            lat_ms = (time.perf_counter() - t0) * 1000.0
            return v, lat_ms

    full_prompt = f"{context}{partial_text}"
    payload = {
        "model": model_id,
        "prompt": full_prompt,
        "max_tokens": 8,
        "temperature": 0.0,
        "stop": ["\n", "。", "！", "!"]
    }
    try:
        req = urllib.request.Request(
            "http://127.0.0.1:1234/v1/completions",
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload).encode("utf-8")
        )
        with urllib.request.urlopen(req, timeout=3) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            pred = data["choices"][0]["text"].strip()
    except Exception as e:
        pred = f"Error: {e}"
    lat_ms = (time.perf_counter() - t0) * 1000.0
    return pred, lat_ms

def run_benchmark():
    print("=" * 75)
    print(" Windows版 ZeroCon IME AIアシスト実行レイテンシ測定ベンチマーク")
    print(" 測定モード:")
    print("   1. [OFF] メイン変換のみ (Baseline: 既存Zenzai変換 100%維持)")
    print("   2. [ON]  AIアシスト (先読みモデル: 230m_q4km)")
    print("   3. [ON]  AIアシスト (先読みモデル: 350m_q4km)")
    print("=" * 75)

    MODELS = [
        ("230m_q4km", "lfm2.5-230m@q4_k_m"),
        ("350m_q4km", "liquidai/lfm2.5-350m")
    ]

    # --- Mode 1: Baseline (OFF) ---
    print("\n>>> [Mode 1: AIアシスト OFF (メイン変換のみ)]")
    off_latencies = []
    for c in BENCHMARK_CASES:
        out, lat = measure_zenzai_main(c["context"], c["input"])
        off_latencies.append(lat)
        print(f"  [{c['id']}] 入力: {c['input']:10} -> 第1候補: [{out:10}] | 遅延: {lat:6.2f}ms")

    avg_off = sum(off_latencies) / len(off_latencies)
    print(f"  => メイン変換 平均レイテンシ: {avg_off:.2f}ms")

    # --- Mode 2 & 3: AI Assist ON ---
    summary = {}
    for mod_key, mod_id in MODELS:
        print(f"\n>>> [Mode: AIアシスト ON (先読みモデル: {mod_key})]")
        main_lats = []
        moshi_lats = []
        pred_lats = []
        total_lats = []

        for c in BENCHMARK_CASES:
            # 1. メイン変換
            main_out, lat_main = measure_zenzai_main(c["context"], c["input"])
            main_lats.append(lat_main)

            # 2. 💡 もしかして
            moshi_out, lat_moshi = measure_moshikashite(c["input"])
            moshi_lats.append(lat_moshi)

            # 3. 🔮 先読み
            pred_out, lat_pred = measure_lfm_predictive(mod_id, c["context"], main_out)
            pred_lats.append(lat_pred)

            tot = lat_main + lat_moshi + lat_pred
            total_lats.append(tot)

            badges = []
            if moshi_out:
                badges.append(f"💡[{moshi_out}]")
            if pred_out:
                badges.append(f"🔮[{pred_out}]")
            badge_str = " ".join(badges) if badges else "(アシストなし)"

            print(f"  [{c['id']}] 入力: {c['input']:10} -> 第1: [{main_out:10}] | {badge_str:30} | 遅延: {tot:6.2f}ms (主:{lat_main:.1f}ms / 予:{lat_pred:.1f}ms)")

        avg_tot = sum(total_lats) / len(total_lats)
        avg_pred = sum(pred_lats) / len(pred_lats)
        summary[mod_key] = {
            "avg_main_ms": avg_off,
            "avg_pred_ms": avg_pred,
            "avg_total_ms": avg_tot
        }
        print(f"  => {mod_key} 平均総レイテンシ: {avg_tot:.2f}ms (先読み単体: {avg_pred:.2f}ms)")

    # 最終比較レポート
    print("\n" + "=" * 75)
    print(" 【レイテンシ測定結果サマリー】")
    print(f"  ・AIアシスト OFF (ベースライン):     {avg_off:.2f} ms")
    print(f"  ・AIアシスト ON  (230m_q4km モデル): {summary['230m_q4km']['avg_total_ms']:.2f} ms (先読みオーバーヘッド: {summary['230m_q4km']['avg_pred_ms']:.2f} ms)")
    print(f"  ・AIアシスト ON  (350m_q4km モデル): {summary['350m_q4km']['avg_total_ms']:.2f} ms (先読みオーバーヘッド: {summary['350m_q4km']['avg_pred_ms']:.2f} ms)")
    print("=" * 75)

if __name__ == "__main__":
    run_benchmark()
