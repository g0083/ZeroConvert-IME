# SPDX-License-Identifier: Apache-2.0
"""Check archived v4 outputs with the exact scorer used for Origin regression."""
import argparse
import json
from pathlib import Path, PureWindowsPath

from benchmark_kana_kanji_models import evaluate_output
from origin_contract import digest, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--contract', type=Path, default=Path('artifacts/results/origin-comparison-contract-20261029-r2.json'))
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    contract = json.loads(args.contract.read_text(encoding='utf-8'))
    cases = {}
    for filename in ('kana_kanji_llm_testset_5x.jsonl', 'kana_kanji_hard_context_testset.jsonl'):
        path = Path('data') / filename
        expected = {PureWindowsPath(k).as_posix(): v for k, v in contract['development_evaluation_hashes'].items()}[path.as_posix()]
        if digest(path) != expected:
            raise ValueError('Development cases changed')
        cases[filename] = {row['id']: row for row in (json.loads(line) for line in path.open(encoding='utf-8'))}
    totals = {}
    for name, model in contract['baseline_models'].items():
        correct, scored = 0, 0
        for stored_path, expected in model['source_result_hashes'].items():
            path = Path(PureWindowsPath(stored_path).as_posix())
            if digest(path) != expected:
                raise ValueError('Archived outputs changed')
            record = json.loads(path.read_text(encoding='utf-8'))[name]
            filename = 'kana_kanji_hard_context_testset.jsonl' if record['total_cases'] == 250 else 'kana_kanji_llm_testset_5x.jsonl'
            expected_cases = cases[filename]
            if len(record['details']) != len(expected_cases) or {r['id'] for r in record['details']} != set(expected_cases):
                raise ValueError('Archived case identities differ')
            for detail in record['details']:
                score, _ = evaluate_output(detail['output'], expected_cases[detail['id']])
                if score != detail['score']:
                    raise ValueError(f'Scorer drift: {name}/{detail["id"]}')
                correct += score == 2
                scored += 1
        if scored != 480 or correct != model['correct_480']:
            raise ValueError('Frozen aggregate differs')
        totals[name] = {'cases_rescored': scored, 'correct': correct, 'score_disagreements': 0}
    result = {'status': 'PASS', 'contract_sha256': digest(args.contract),
              'scorer_source_sha256': digest(Path(__file__).with_name('benchmark_kana_kanji_models.py')),
              'auditor_source_sha256': digest(Path(__file__)), 'models': totals,
              'limits': ['Recomputes quality scores from archived outputs; does not rerun inference or measure latency']}
    write_json(args.out, result)
    print(json.dumps(totals, indent=2))


if __name__ == '__main__':
    main()
