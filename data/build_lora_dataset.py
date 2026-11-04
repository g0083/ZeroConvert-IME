# -*- coding: utf-8 -*-
"""CLEANKEEP build."""

import json
import argparse
import os
import re
import sys
from collections import Counter, defaultdict
from typing import Dict, List, Tuple
from dataset_isolation import evaluation_keys, separated_rows, validate_rows

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TESTSET = os.path.join(ROOT, 'data', 'kana_kanji_llm_testset_5x.jsonl')

PUA_CTX = '\uEE02'
PUA_READ = '\uEE00'
PUA_END = '\uEE01'

SOURCES: List[Tuple[str, str]] = [
    ('data/typo_dataset_train.jsonl', 'typo'),
    ('data/typo_dataset_val.jsonl', 'typo'),
    ('data/typo_dataset_train_30k.jsonl', 'typo'),
    ('data/typo_dataset_val_30k.jsonl', 'typo'),
    ('data/distilled_train.jsonl', 'distill'),
    ('data/distilled_val.jsonl', 'distill'),
]


def hira_to_kata(text: str) -> str:
    return ''.join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])


def norm(s: str) -> str:
    return re.sub(r'\s+', '', s or '')


def load_jsonl(path: str) -> List[Dict]:
    rows = []
    with open(path, 'r', encoding='utf-8') as f:
        for line in f:
            if line.strip():
                rows.append(json.loads(line))
    return rows


def split_prompt(prompt: str) -> Tuple[str, str]:
    ctx = prompt.split(PUA_CTX)[-1].split(PUA_READ)[0]
    kata = prompt.split(PUA_READ)[-1].split(PUA_END)[0]
    return ctx, kata


def normalize_space(s: str) -> str:
    return (s or '').replace(' ', '\u3000')


def build_test_index():
    cases = load_jsonl(TESTSET)
    pairs = set()
    answers = set()
    ctxs = set()
    ctx_ans = set()
    ctx_answers: Dict[str, set] = defaultdict(set)
    for c in cases:
        ctx = '' if c['left_context'] == 'なし' else c['left_context']
        ctx = normalize_space(ctx)
        pairs.add((ctx, hira_to_kata(c['target'])))
        ctxs.add(ctx)
        ans = {norm(c['expected'])} | {norm(a) for a in c.get('acceptable', [])}
        ctx_answers[ctx].update(ans)
        for a in ans:
            answers.add(a)
            ctx_ans.add((ctx, a))
    return cases, pairs, answers, ctxs, ctx_ans, ctx_answers


class Rules:
    def __init__(self):
        (self.cases, self.t_pairs, self.t_answers, self.t_ctx,
         self.t_ctx_ans, self.ctx_answers) = build_test_index()

    @staticmethod
    def readings_of(r):
        return {r['reading'], r['kata'], r['correct_kata']} - {''}

    def testset_clean(self, r):
        return r['category'] == 'testset_clean'

    def l1_ctx_reading(self, r):
        rs = self.readings_of(r)
        for (ctx, kata) in self.t_pairs:
            if kata not in rs:
                continue
            if ctx == r['ctx'] or (not ctx and not r['ctx']):
                return True
        return False

    def l1_reading_answer(self, r):
        rs = self.readings_of(r)
        for (ctx, kata) in self.t_pairs:
            if kata not in rs:
                continue
            if ctx != r['ctx'] and (ctx or r['ctx']):
                continue
            if norm(r['completion']) in self.ctx_answers.get(ctx, set()):
                return True
        return False

    def l2_ctx_answer(self, r):
        return (r['ctx'], norm(r['completion'])) in self.t_ctx_ans

    def l3_answer_only(self, r):
        return bool(norm(r['completion'])) and norm(r['completion']) in self.t_answers

    def ctx_only(self, r):
        return bool(r['ctx']) and r['ctx'] in self.t_ctx

    @staticmethod
    def quality(r):
        c = r['completion'] or ''
        if not c.strip():
            return 'empty'
        if len(c.strip()) <= 1:
            return 'too_short'
        if any(0xE000 <= ord(ch) <= 0xF8FF for ch in c):
            return 'pua'
        if re.search(r'(.)\1{3,}', c):
            return 'repeat_char'
        for n in (1, 2, 3):
            unit = c[:n]
            if len(c) >= n * 3 and c == unit * (len(c) // n):
                return 'repeat_block'
        if r['kind'] == 'distill' and norm(c) == norm(r['kata']):
            return 'echo_reading'
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', default=os.path.join(ROOT, 'artifacts', 'datasets', 'lora-cleankeep-v1'))
    args = parser.parse_args()
    if os.path.exists(args.out):
        raise FileExistsError(f'Use a new output directory: {args.out}')
    cases, t_pairs, t_answers, t_ctx, t_ctx_ans, ctx_answers = build_test_index()
    rules = Rules()

    collected: List[Dict] = []
    for rel, kind in SOURCES:
        path = os.path.join(ROOT, rel)
        if not os.path.exists(path):
            print(f'  skip (missing): {rel}', flush=True)
            continue
        rows = load_jsonl(path)
        for r in rows:
            prompt = r.get('prompt', '')
            completion = r.get('completion', '')
            if not prompt or not completion:
                continue
            ctx_raw, kata = split_prompt(prompt)
            ctx = normalize_space(ctx_raw)
            reading = normalize_space(r.get('input_reading') or kata)
            correct_hira = r.get('target_reading') or ''
            correct_kata = hira_to_kata(correct_hira) if correct_hira else ''
            if ctx:
                nprompt = f'{PUA_CTX}{ctx}{PUA_READ}{kata}{PUA_END}'
            else:
                nprompt = f'{PUA_READ}{kata}{PUA_END}'
            collected.append({
                'prompt': nprompt, 'completion': completion,
                'left_context': ctx, 'target_kata': kata,
                'ctx': ctx, 'kata': kata, 'reading': reading,
                'correct_kata': correct_kata,
                'source': kind, 'origin': rel,
                'expected': r.get('expected'),
                'typo_type': r.get('typo_type'),
                'is_clean': r.get('is_clean'),
                'category': r.get('category'),
                'kind': kind,
            })
        print(f'  loaded {rel}: {len(rows)} (total {len(collected)})', flush=True)

    print(f'Total collected: {len(collected)}', flush=True)

    drop_reasons = Counter()
    dropped_by_origin = Counter()
    dropped_by_reason_origin = defaultdict(Counter)
    kept: List[Dict] = []
    l3_only = []
    for r in collected:
        drop = None
        if rules.testset_clean(r):
            drop = 'testset_clean'
        elif rules.l1_ctx_reading(r):
            drop = 'l1_ctx_reading'
        elif rules.l1_reading_answer(r):
            drop = 'l1_reading_answer'
        elif rules.l2_ctx_answer(r):
            drop = 'l2_ctx_answer'
        elif rules.ctx_only(r):
            drop = 'ctx_only'
        else:
            q = rules.quality(r)
            if q:
                drop = 'quality_' + q
        if (rules.l3_answer_only(r) and not rules.testset_clean(r)
                and not rules.l1_ctx_reading(r) and not rules.l1_reading_answer(r)
                and not rules.l2_ctx_answer(r) and not rules.ctx_only(r)):
            l3_only.append(r)
        if drop:
            drop_reasons[drop] += 1
            dropped_by_origin[r['origin']] += 1
            dropped_by_reason_origin[drop][r['origin']] += 1
        else:
            kept.append(r)

    print(f'Dropped: {dict(drop_reasons)}', flush=True)
    print(f'Kept after CLEANKEEP: {len(kept)}', flush=True)

    seen_pc = set()
    deduped = []
    dup_pc = 0
    for r in kept:
        key = (r['prompt'], r['completion'])
        if key in seen_pc:
            dup_pc += 1
            continue
        seen_pc.add(key)
        deduped.append(r)
    print(f'Exact (prompt,completion) dup removed: {dup_pc}', flush=True)

    seen_cr = set()
    deduped2 = []
    dup_cr = 0
    for r in deduped:
        key = (r['ctx'], r['reading'])
        if key in seen_cr:
            dup_cr += 1
            continue
        seen_cr.add(key)
        deduped2.append(r)
    print(f'(ctx,reading) dup removed: {dup_cr}', flush=True)
    deduped = deduped2

    reserved = evaluation_keys()
    # Group the original rows before deduplication: clean-reading metadata on a
    # duplicate can connect its typo siblings to evaluation or validation.
    safe_train, safe_val, isolated_excluded = separated_rows(collected, reserved)
    train_keys = {(r['prompt'], r['completion']) for r in safe_train}
    val_keys = {(r['prompt'], r['completion']) for r in safe_val}
    train_rows = [r for r in deduped if (r['prompt'], r['completion']) in train_keys]
    val_rows = [r for r in deduped if (r['prompt'], r['completion']) in val_keys]
    isolation_checks = validate_rows(train_rows, val_rows, reserved)

    print(f'Train: {len(train_rows)}  Val: {len(val_rows)}', flush=True)

    os.makedirs(args.out, exist_ok=False)
    for name, rows in (('lora_train.jsonl', train_rows), ('lora_val.jsonl', val_rows)):
        with open(os.path.join(args.out, name), 'w', encoding='utf-8') as f:
            for r in rows:
                f.write(json.dumps({
                    'prompt': r['prompt'], 'completion': r['completion'],
                    'left_context': r['left_context'], 'target_kata': r['target_kata'],
                    'source': r['source'], 'category': r.get('category'),
                    'typo_type': r.get('typo_type'),
                    'target_reading': r['correct_kata'],
                    'origin': r['origin'],
                }, ensure_ascii=False) + '\n')

    co = [r for r in collected if rules.ctx_only(r) and r['category'] != 'testset_clean']

    manifest = {
        'benchmark_cases': len(cases),
        'benchmark_unique_pairs': len(t_pairs),
        'collected_total': len(collected),
        'dropped_by_reason': dict(drop_reasons),
        'dropped_total': len(collected) - len(kept),
        'kept_after_cleankeep': len(kept),
        'dedup_exact_pc': dup_pc,
        'dedup_ctx_reading': dup_cr,
        'kept_before_isolation': len(deduped),
        'kept_final': len(train_rows) + len(val_rows),
        'train': len(train_rows),
        'val': len(val_rows),
        'source_breakdown': dict(Counter(r['source'] for r in train_rows + val_rows)),
        'category_breakdown': dict(Counter(r.get('category') or 'unknown' for r in train_rows + val_rows)),
        'origin_kept': dict(Counter(r['origin'] for r in train_rows + val_rows)),
        'origin_dropped': dict(dropped_by_origin),
        'l3_only_extra_hits': len(l3_only),
        'l3_only_by_origin': dict(Counter(r['origin'] for r in l3_only)),
        'l3_only_by_category': dict(Counter(str(r.get('category')) for r in l3_only)),
        'ctx_only_detail_count': len(co),
        'space_normalized': True,
        'isolation_checks': isolation_checks,
        'evaluation_related_rows': len(isolated_excluded),
    }
    out_dir = args.out
    with open(os.path.join(out_dir, 'dedup_manifest.json'), 'w', encoding='utf-8') as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print('\n=== manifest ===', flush=True)
    print(json.dumps(manifest, ensure_ascii=False, indent=2), flush=True)
    print(f'Saved: {args.out}', flush=True)


if __name__ == '__main__':
    main()
