# -*- coding: utf-8 -*-
import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
result_path = BASE_DIR / "results" / "benchmark_results.json"
if not result_path.exists():
    result_path = BASE_DIR / "benchmark_results.json"

with open(result_path, 'r', encoding='utf-8') as f:
    data = json.load(f)

print("# Benchmark Aggregation Summary\n")

models = [
    "lfm2.5-230m-fable-5",
    "lfm2.5-350m",
    "liquidai/lfm2.5-1.2b-instruct",
    "lmstudio-community/lfm2.5-2.6b"
]

header = "| モデル | 条件 | 基本スコア (満点92) | 完全一致数 | 許容一致数 | 誤答数 | 追加違反 | 削除違反 | 形式違反 | 保護違反 | 総合点 | 実効TTFT | 総時間 |"
sep = "|---|---|---|---|---|---|---|---|---|---|---|---|---|"
print(header)
print(sep)

for m in models:
    v = data[m]
    for cond_key, cond_name in [("long_prompt", "推奨長文"), ("short_prompt", "短縮厳格")]:
        met = v[cond_key]["metrics"]
        print(f"| `{m}` | {cond_name} | **{met['raw_score']}/92** | {met['exact_count']} | {met['acceptable_count']} | {met['wrong_count']} | {met['add_count']} | {met['omit_count']} | {met['format_count']} | {met['protect_count']} | **{met['comprehensive_score']}** | {met['avg_content_ttft_ms']}ms | {met['avg_total_time_ms']}ms |")

print("\n## モデル別・カテゴリ別正解数内訳 (長文プロンプト)")
cat_header = "| モデル | A.基本(5) | B.同音(9) | C.文脈(7) | D.長文脈(3) | E.英数(5) | F.固有(3) | G.補完防止(5) | H.校正防止(3) | I.不明語(3) | J.句読点(3) |"
cat_sep = "|---|---|---|---|---|---|---|---|---|---|---|"
print(cat_header)
print(cat_sep)

categories = [
    "A. 基本変換",
    "B. 同音異義語",
    "C. 文脈分岐",
    "D. 長い文脈",
    "E. 英数字保持",
    "F. 固有名詞",
    "G. 補完防止",
    "H. 勝手な校正防止",
    "I. 不明語維持",
    "J. 句読点保持"
]

for m in models:
    results = data[m]["long_prompt"]["results"]
    cat_scores = []
    for cat in categories:
        c_items = [r for r in results if r["category"] == cat]
        total_pts = sum(r["score"] for r in c_items)
        max_pts = len(c_items) * 2
        cat_scores.append(f"{total_pts}/{max_pts}")
    print(f"| `{m}` | " + " | ".join(cat_scores) + " |")

print("\n## ストリーミング逐次入力テスト結果サマリー")
for m in models:
    print(f"\n### `{m}`")
    for sc in data[m]["streaming"]:
        print(f"- **{sc['case_name']}** (期待: `{sc['expected']}`)")
        print(f"  - 最終出力: `{sc['final_output']}`")
        step_strs = [f"`{st['step']}` -> `{st['output']}`" for st in sc['steps']]
        print(f"  - 逐次変遷: {' → '.join(step_strs)}")

