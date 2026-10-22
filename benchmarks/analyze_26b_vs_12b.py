
from pathlib import Path
REPO_ROOT = Path(__file__).resolve().parents[1]

import json
import sys
sys.stdout.reconfigure(encoding='utf-8')

from pathlib import Path

BASE_DIR = REPO_ROOT
adv_path = BASE_DIR / 'artifacts/results' / 'benchmark_advanced_results.json'
if not adv_path.exists():
    adv_path = BASE_DIR / 'artifacts/results/benchmark_advanced_results.json'

c26_path = BASE_DIR / 'artifacts/results' / 'benchmark_c_26b_results.json'
if not c26_path.exists():
    c26_path = BASE_DIR / 'artifacts/results/benchmark_c_26b_results.json'

with open(adv_path, "r", encoding="utf-8") as f:
    adv = json.load(f)
with open(c26_path, "r", encoding="utf-8") as f:
    c26 = json.load(f)

c12_results = adv["plan_c"]['results']
c26_results = c26['results']

categories = {
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

print(f"{'カテゴリ':15s} | {'設問':8s} | {'1.2B Logit':12s} | {'2.6B Logit':12s}")
print("-" * 55)

cat_scores_12 = {}
cat_scores_26 = {}

for cat_name, qids in categories.items():
    s12 = sum(r["score"] for r in c12_results if int(r["id"]) in qids)
    s26 = sum(r["score"] for r in c26_results if int(r["id"]) in qids)
    max_s = len(qids) * 2
    cat_scores_12[cat_name] = s12
    cat_scores_26[cat_name] = s26
    print(f"{cat_name:15s} | {len(qids)}問 ({max_s:2d}点) | {s12:2d} / {max_s:2d}       | {s26:2d} / {max_s:2d}")

print("\n=== 2.6Bと1.2Bで差がついた問題 ===")
for r12, r26 in zip(c12_results, c26_results):
    qid = int(r12["id"])
    if r12["score"] != r26["score"]:
        print(f"問{qid:02d} (期待: {r12['expected']})")
        print(f"  - 1.2B ({r12['score']}pt): {r12['selected']}")
        print(f"  - 2.6B ({r26['score']}pt): {r26['selected']}")
