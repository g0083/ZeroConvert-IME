# -*- coding: utf-8 -*-

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

jz_path = BASE_DIR / 'artifacts/results' / 'benchmark_jev_zenzai_results.json'
if not jz_path.exists():
    jz_path = BASE_DIR / 'artifacts/results/benchmark_jev_zenzai_results.json'

with open(adv_path, 'r', encoding='utf-8') as f:
    adv = json.load(f)

with open(jz_path, 'r', encoding='utf-8') as f:
    jz = json.load(f)

print("# 全方式の総合比較サマリー\n")
print("| アプローチ | 方式名 | 実装モデル | メモリ | 平均遅延 | 基本スコア (満点92) | 完全一致率 |")
print("|---|---|---|---|---|---|---|")
print(f"| 従来ベースライン | 自由生成 (短縮) | LFM 1.2B | 1.25 GB | 93.0 ms | 9 / 92 | 4.3% (2問) |")
print(f"| Zenzai方式 | GPT-2 対数尤度 | rinna GPT-2 110M | **220 MB** | 167.9 ms | 43 / 92 | 43.5% (20問) |")
print(f"| Jev通常方式 | Choice テキスト生成 | LFM 1.2B | 1.25 GB | 59.4 ms | 49 / 92 | 52.2% (24問) |")
print(f"| **案A** | **双方向クロスエンコーダー** | 東北大 BERT 111M | **220 MB** | 197.0 ms | 37 / 92 | 37.0% (17問) |")
print(f"| **案C (本命)** | **Jev ロジット直接判定** | LFM 1.2B (Logprobs) | 1.25 GB | **44.0 ms** | **59 / 92** | **63.0% (29問)** |")

# カテゴリ別比較
from benchmark_jev_zenzai import BENCHMARK_CASES

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

print("\n## カテゴリ別スコア比較 (満点)")
print("| カテゴリ | 設問 | Zenzai (GPT-2) | 案A (双方向BERT) | Jev通常 (生成) | **案C (ロジット直接)** |")
print("|---|---|---|---|---|---|")

for cat_code in ['A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J']:
    c_name = cat_names[cat_code]
    sub_z = [r for r in jz['zenzai']['results'] if get_cat(r['id']) == cat_code]
    sub_a = [r for r in adv['plan_a']['results'] if get_cat(r['id']) == cat_code]
    sub_j = [r for r in jz['jev']['results'] if get_cat(r['id']) == cat_code]
    sub_c = [r for r in adv['plan_c']['results'] if get_cat(r['id']) == cat_code]
    
    max_pts = len(sub_c) * 2
    pts_z = sum(r['score'] for r in sub_z)
    pts_a = sum(r['score'] for r in sub_a)
    pts_j = sum(r['score'] for r in sub_j)
    pts_c = sum(r['score'] for r in sub_c)
    
    print(f"| {c_name} | {len(sub_c)}問 ({max_pts}点) | {pts_z} | {pts_a} | {pts_j} | **{pts_c}** |")

print("\n## 案C (ロジット直接) で正解した注目ケース")
for r in adv['plan_c']['results']:
    if r['score'] == 2 and int(r['id']) in [6, 7, 8, 9, 10, 11, 12, 13, 14, 25, 26, 27, 28, 29, 38, 39, 40]:
        case_info = next(c for c in BENCHMARK_CASES if c['id'] == r['id'])
        print(f"- 問{r['id']}: [{case_info['context']}] `{case_info['target']}` -> `{r['selected']}` ({r['duration_ms']}ms)")

