# SPDX-License-Identifier: Apache-2.0
"""Action-aware Origin validation diagnostic using owned HF checkpoint weights.

CPU by default, bounded stratified validation sample, no sealed text access.
Exact auto-label scores are not human prediction usefulness or deployed auto-
correction precision. Concurrent-training timings cannot establish CPU p95.
"""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import random
import re

from origin_contract import OriginCodec, digest, read_rows, write_json


def stratified_sample(rows, maximum, seed):
    if maximum == 0 or maximum >= len(rows):
        return list(rows)
    strata = defaultdict(list)
    for row in rows:
        strata[(row['task'], row['target']['action'], row.get('category', 'unspecified'))].append(row)
    for key, group in strata.items():
        random.Random(f'{seed}:{key}').shuffle(group)
    chosen, cursor = [], 0
    keys = sorted(strata)
    while len(chosen) < maximum:
        added = False
        for key in keys:
            if cursor < len(strata[key]):
                chosen.append(strata[key][cursor])
                added = True
                if len(chosen) == maximum:
                    break
        if not added:
            break
        cursor += 1
    return chosen


def reference_reading(case):
    return case.get('reference_reading') or case['input'].get('surface')


def summarize(cases):
    totals = {}
    for task in sorted({c['task'] for c in cases}):
        selected = [c for c in cases if c['task'] == task]
        report = {'cases': len(selected), 'strict_target_exact': sum(c['exact'] for c in selected),
                  'actual_actions': dict(Counter(c['actual']['action'] for c in selected)),
                  'by_category': {category: {'cases': sum(c['category'] == category for c in selected),
                      'exact': sum(c['category'] == category and c['exact'] for c in selected)}
                      for category in sorted({c['category'] for c in selected})}}
        if task.startswith('repair'):
            noisy = [c for c in selected if c['expected']['action'] == 'emit']
            clean = [c for c in selected if c['expected']['action'] == 'keep']
            pending = [c for c in selected if c['expected']['action'] == 'abstain']
            emitted = [c for c in selected if c['actual']['action'] == 'emit']
            correct_emits = sum(c['actual']['text'] == (c['expected']['text'] if c['expected']['action'] == 'emit'
                                else reference_reading(c)) and c['expected']['action'] != 'abstain' for c in emitted)
            report.update({'noisy_cases': len(noisy), 'noisy_exact_reading_recovery': sum(c['exact'] for c in noisy),
                'clean_cases': len(clean), 'clean_changed_reading_proposals': sum(c['actual']['action'] == 'emit' and
                     c['actual']['text'] != reference_reading(c) for c in clean),
                'clean_unchanged_emit_proposals': sum(c['actual']['action'] == 'emit' and
                     c['actual']['text'] == reference_reading(c) for c in clean),
                'pending_cases': len(pending), 'pending_emit_proposals': sum(c['actual']['action'] == 'emit' for c in pending),
                'emitted_proposals': len(emitted), 'correct_reading_proposals': correct_emits,
                'proposal_precision': correct_emits / len(emitted) if emitted else None,
                'deployment_autocorrection_precision': 'not measured; no adoption calibration or real typing trace'})
        elif task == 'predict':
            continuations = [c for c in selected if c['expected']['action'] == 'emit']
            complete = [c for c in selected if c['expected']['action'] == 'abstain']
            report.update({'suffix_cases': len(continuations), 'suffix_exact': sum(c['exact'] for c in continuations),
                'suffix_proposals': sum(c['actual']['action'] == 'emit' for c in continuations),
                'complete_cases': len(complete), 'complete_emit_proposals': sum(c['actual']['action'] == 'emit' for c in complete),
                'human_usefulness': 'not measured; alternate valid continuations not annotated'})
        elif task == 'convert':
            protected = [c for c in selected if re.search(r'[0-9０-９A-Za-zＡ-Ｚａ-ｚ]', c['expected']['text'])]
            pattern = r'[0-9０-９A-Za-zＡ-Ｚａ-ｚ]+'
            report.update({'protected_ascii_numeric_cases': len(protected),
                'protected_sequence_mismatches': sum(re.findall(pattern, c['expected']['text']) !=
                                                     re.findall(pattern, c['actual']['text']) for c in protected)})
        totals[task] = report
    return totals


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--max-cases', type=int, default=256)
    parser.add_argument('--seed', type=int, default=20261029)
    parser.add_argument('--device', choices=('cpu', 'cuda'), default='cpu')
    parser.add_argument('--concurrent-training', action='store_true')
    parser.add_argument('--batch-size', type=int, default=1)
    parser.add_argument('--record-scores', action='store_true', help='Record uncalibrated features from generated tokens only')
    parser.add_argument('--operating-cohort', type=Path, help='Frozen validation-document partition; QWERTY repair only')
    parser.add_argument('--cohort-partition', choices=('calibration', 'validation-check'))
    parser.add_argument('--tasks', nargs='+', choices=('convert', 'repair_qwerty', 'predict'))
    parser.add_argument('--repair-state', choices=('training', 'raw-only'), default='training',
                        help='Counterfactual diagnostic: remove surface/cursor based only on observed pending state')
    parser.add_argument('--source-preserving-transform', action='store_true',
                        help='Allow only a verified raw-state transform of the original validation sources')
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    if args.max_cases < 0:
        parser.error('max-cases must be nonnegative; zero means all validation cases')
    if args.batch_size < 1:
        parser.error('batch-size must be positive')
    if bool(args.operating_cohort) != bool(args.cohort_partition):
        parser.error('Specify both operating-cohort and cohort-partition')
    manifest = json.loads((args.run / 'run-manifest.json').read_text(encoding='utf-8'))
    path = args.dataset / 'val.jsonl'
    evaluation_lineage = None
    if digest(path) != manifest['dataset_hashes']['val.jsonl']:
        if not args.source_preserving_transform:
            raise ValueError('Validation identity mismatch')
        from adapt_origin import verify_transfer
        verified = verify_transfer(args.dataset, manifest)
        evaluation_lineage = {'manifest_sha256': digest(args.dataset / 'manifest.json'),
                               'parent_manifest_sha256': verified['parent_manifest_sha256']}
    if digest(args.run / 'tokenizer.model') != manifest['tokenizer_sha256']:
        raise ValueError('Tokenizer identity mismatch')
    all_rows = read_rows(path)
    operating_cohort = None
    if args.operating_cohort:
        if args.tasks and set(args.tasks) != {'repair_qwerty'}:
            parser.error('Operating cohorts cover repair_qwerty only')
        from origin_operating_cohort import select_cohort_rows
        cohort = json.loads(args.operating_cohort.read_text(encoding='utf-8'))
        eligible = select_cohort_rows(all_rows, cohort, args.cohort_partition, digest(path))
        operating_cohort = {'sha256': digest(args.operating_cohort), 'partition': args.cohort_partition,
                            'scope': 'development-only existing validation; source-document groups preserved'}
    else:
        eligible = [r for r in all_rows if not args.tasks or r['task'] in args.tasks]
    rows = stratified_sample(eligible, args.max_cases, args.seed)
    import torch
    from transformers import AutoModelForCausalLM
    torch.set_num_threads(4)
    codec = OriginCodec(args.run / 'tokenizer.model')
    hf = args.run / 'hf'
    hf_hashes = {str(p.relative_to(hf)): digest(p) for p in sorted(hf.rglob('*')) if p.is_file()}
    model = AutoModelForCausalLM.from_pretrained(hf, local_files_only=True, trust_remote_code=False).to(args.device).eval()
    prepared = []
    for row in rows:
        original_reference = row.get('reference_reading') or row['input'].get('surface')
        row = {**row, 'reference_reading': original_reference}
        if args.repair_state == 'raw-only' and row['task'] == 'repair_qwerty' and not row['input'].get('pending'):
            row['input'] = {k: v for k, v in row['input'].items() if k not in ('surface', 'cursor')}
        prepared.append(row)
    from origin_batch_inference import generate_cases
    cases = generate_cases(model, codec, prepared, args.device, args.batch_size, args.record_scores)
    if {str(p.relative_to(hf)): digest(p) for p in sorted(hf.rglob('*')) if p.is_file()} != hf_hashes:
        raise ValueError('HF weights/configuration changed during evaluation')
    result = {'schema': 'origin-task-diagnostic-v1', 'manifest_sha256': digest(args.run / 'run-manifest.json'),
        'validation_sha256': digest(path), 'validation_population': len(all_rows), 'sampled_cases': len(rows),
        'sample_seed': args.seed, 'sample_method': ('all eligible rows in source order' if args.max_cases == 0 or args.max_cases >= len(eligible)
            else 'equal round-robin by task/action/category; not deployment frequency'),
        'eligible_population': len(eligible), 'tasks': args.tasks, 'repair_state_condition': args.repair_state,
        'source_preserving_evaluation_lineage': evaluation_lineage,
        'summary': summarize(cases), 'cases': cases, 'device': args.device,
        'batch_size': args.batch_size, 'generation_scores_recorded': args.record_scores,
        'hf_source_hashes': hf_hashes,
        'model_parameter_dtypes': sorted({str(p.dtype) for p in model.parameters()}),
        'operating_cohort': operating_cohort,
        'score_features_status': 'uncalibrated model features; not automatic-adoption probabilities',
        'concurrent_training': args.concurrent_training, 'timing_valid_for_v4_comparison': False,
        'limits': ['Automatic labels, synthetic errors, alternate valid answers not adjudicated',
                   'No sealed sources accessed', 'Not an end-to-end IME or calibrated automatic adoption test',
                   'One-pass HF diagnostics cannot establish product CPU p95'],
        'code_sha256': digest(Path(__file__)),
        'batch_inference_code_sha256': digest(Path(__file__).with_name('origin_batch_inference.py'))}
    write_json(args.out, result)
    print(json.dumps(result['summary'], indent=2))


if __name__ == '__main__':
    main()
