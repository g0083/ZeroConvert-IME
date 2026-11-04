# SPDX-License-Identifier: Apache-2.0
"""Freeze verified v4 artifacts and predeclared Origin comparison gates."""
import argparse
import json
from pathlib import Path

from dataset_isolation import EVALUATION_FILES
from origin_contract import digest, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    root = Path(__file__).resolve().parents[2]
    paths = [root / 'artifacts/results' / name for name in ('nano_standard_v4_cpu_230.json', 'nano_standard_v4_cpu_250hard.json')]
    tables = [json.loads(p.read_text(encoding='utf-8')) for p in paths]
    test_paths = [root / 'data/kana_kanji_llm_testset_5x.jsonl', root / 'data/kana_kanji_hard_context_testset.jsonl']
    runtime = root / 'ZeroConvertIME-windows/llama_cpu/llama-server.exe'
    runtime_hash = digest(runtime)
    frozen = {}
    for name in tables[0]:
        records = [table[name] for table in tables]
        if any(r['runtime_sha256'] != runtime_hash for r in records):
            raise ValueError('CPU runtime changed; remeasure baselines')
        if records[0]['protocol'] != records[1]['protocol']:
            raise ValueError('Baseline protocol mismatch')
        model = Path(records[0]['model_path'])
        model_hash = digest(model)
        if any(r['model_sha256'] != model_hash for r in records):
            raise ValueError('Baseline model changed; remeasure')
        if sum(r['total_cases'] for r in records) != 480:
            raise ValueError('Wrong regression population')
        for record, test_path in zip(records, test_paths):
            if record.get('testset_sha256') != digest(test_path):
                raise ValueError('Baseline evaluation data changed; remeasure')
            details = record['details']
            if len(details) != record['total_cases'] or len({r['id'] for r in details}) != len(details):
                raise ValueError('Baseline case identities are incomplete')
            if sum(r['score'] == 2 for r in details) != record['perfect_count']:
                raise ValueError('Baseline exact score disagrees with case outputs')
        frozen[name] = {'path': str(model), 'sha256': model_hash,
                        'correct_480': sum(r['perfect_count'] for r in records),
                        'protocol': records[0]['protocol'], 'source_result_hashes': {str(p.relative_to(root)): digest(p) for p in paths}}
    plan = root / 'reports/models/zerocon-origin-build-plan-20261029.md'
    write_json(args.out, {
        'schema': 'origin-comparison-contract-v1', 'baseline_models': frozen,
        'runtime': {'path': str(runtime), 'sha256': runtime_hash},
        'development_evaluation_hashes': {str(p.relative_to(root)): digest(p) for p in EVALUATION_FILES},
        'plan_sha256': digest(plan), 'code_sha256': digest(Path(__file__)),
        'predeclared_gates': {
            'standard_regression_correct_min': 468, 'nano_regression_correct_min': 466,
            'sealed_conversion_paired_ci95_lower_bound_gt': 0,
            'sealed_conversion_improvement_pp_target': 2,
            'new_major_protected_corruptions_max': 0,
            'qwerty_reading_recovery_target': .90,
            'autocorrection_precision_target': .995,
            'normal_false_change_rate_one_sided_ci95_upper_lt': .001,
            'prediction_keystroke_savings_relative_target': 1.20,
            'standard_cpu_p95_relative_to_v4_q8_target': .75,
            'full_dominance_cpu_p95_ms_max': 59.86,
            'sealed_truncation_duplicates_runaway_max': 0,
        },
        'not_yet_baselined': ['real QWERTY rescue and false corrections', 'prediction usefulness at matched proposal rate',
                             'independent sealed gold labels', 'ordered Windows edit traces',
                             'total product resident memory and Rust migration gates'],
        'status': 'Historical CPU artifacts identity verified; not a fresh timing measurement or complete P0',
    })
    print(json.dumps({name: v['correct_480'] for name, v in frozen.items()}, indent=2))


if __name__ == '__main__':
    main()
