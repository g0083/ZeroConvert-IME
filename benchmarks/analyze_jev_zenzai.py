# -*- coding: utf-8 -*-

from pathlib import Path
REPO_ROOT = Path(__file__).resolve().parents[1]

import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

from pathlib import Path

BASE_DIR = REPO_ROOT
result_path = BASE_DIR / 'artifacts/results' / 'benchmark_jev_zenzai_results.json'
if not result_path.exists():
    result_path = BASE_DIR / 'artifacts/results/benchmark_jev_zenzai_results.json'

with open(result_path, 'r', encoding='utf-8') as f:
    data = json.load(f)

for method in ['jev', 'zenzai']:
    m = data[method]
    print(f"=== {method.upper()} ===")
    print(f"Model: {m['model']}")
    print(f"Memory: {m['memory_mb']} MB")
    print(f"Latency: {m['avg_latency_ms']} ms")
    print(f"Score: {m['raw_score']} / 92")
    print(f"Exact: {m['exact_count']} / 46")
    print(f"Acceptable: {m['acceptable_count']} / 46")

from benchmark_jev_zenzai import BENCHMARK_CASES

cat_names = {
    'A': 'A. 基本変換',
    'B': 'B. 同音異義語',
    'C': 'C. 文脈分岐',
    'D': 'D. 長い文脈',
    'E': 'E. 英数字保持',
    'F': 'F. 固有名詞',
    'G': 'G. 補完防止',
    'H': 'H. 校正防止',
    'I': 'I. 不明語維持',
    'J': 'J. 句読点保持'
}

def get_cat(cid):
    i = int(cid)
    if i <= 5: return 'A'
    if i <= 14: return 'B'
    if i <= 21: return 'C'
    if i <= 24: return 'D'
    if i <= 29: return 'E'
    if i <= 32: return 'F'
    if i <= 37: return 'G'
    if i <= 40: return 'H'
    if i <= 43: return 'I'
    return 'J'

print("\n| カテゴリ | 設問数 | Jev方式 (Choice 1.2B) | Zenzai方式 (GPT-2 110M) | 備考 |")
print("|---|---|---|---|---|")

for cat_code in ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J']:
    c_name = cat_names[cat_code]
    j_sub = [r for r in data['jev']['results'] if get_cat(r['id']) == cat_code]
    z_sub = [r for r in data['zenzai']['results'] if get_cat(r['id']) == cat_code]
    max_pts = len(j_sub) * 2
    j_pts = sum(r['score'] for r in j_sub)
    z_pts = sum(r['score'] for r in z_sub)
    print(f"| {c_name} | {len(j_sub)}問 | **{j_pts}/{max_pts}** ({sum(1 for r in j_sub if r['score']==2)}完全) | **{z_pts}/{max_pts}** ({sum(1 for r in z_sub if r['score']==2)}完全) | |")

# 代表的なケースの勝敗比較
print("\n## 注目ケースの比較")
interesting_ids = ["06", "07", "08", "09", "15", "16", "17", "18", "20", "21", "33", "38"]
for i_id in interesting_ids:
    j_item = next(r for r in data['jev']['results'] if r['id'] == i_id)
    z_item = next(r for r in data['zenzai']['results'] if r['id'] == i_id)
    case_info = next(c for c in BENCHMARK_CASES if c['id'] == i_id)
    print(f"- **問{i_id}** [文脈: {case_info['context']} | 対象: {case_info['target']}]")
    print(f"  - 期待: `{case_info['expected']}`")
    print(f"  - Jev   : `{j_item['selected']}` ({'○' if j_item['score']==2 else '×'})")
    print(f"  - Zenzai: `{z_item['selected']}` ({'○' if z_item['score']==2 else '×'})")

