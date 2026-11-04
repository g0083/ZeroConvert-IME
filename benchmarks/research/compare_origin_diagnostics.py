# SPDX-License-Identifier: Apache-2.0
"""Compare identical Origin inputs across serial/batched HF diagnostics."""
import argparse
import json
from pathlib import Path

from origin_contract import digest, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--score-tolerance', type=float, default=1e-5)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    left, right = [json.loads(path.read_text(encoding='utf-8')) for path in (args.reference, args.candidate)]
    for key in ('manifest_sha256', 'validation_sha256'):
        if left[key] != right[key]:
            raise ValueError(f'Diagnostic source differs: {key}')
    if left.get('hf_source_hashes') and right.get('hf_source_hashes') and left['hf_source_hashes'] != right['hf_source_hashes']:
        raise ValueError('HF weight/configuration hashes differ')
    if [r['id'] for r in left['cases']] != [r['id'] for r in right['cases']]:
        raise ValueError('Case identity/order differs')
    if len({r['id'] for r in left['cases']}) != len(left['cases']):
        raise ValueError('Duplicate case identities')
    mismatches, actions, tokens, score_cases, maximum = [], 0, 0, 0, 0.0
    for a, b in zip(left['cases'], right['cases']):
        for key in ('id', 'task', 'document_id', 'category', 'input', 'expected', 'reference_reading'):
            if a.get(key) != b.get(key):
                raise ValueError(f'Case inputs or labels differ: {a["id"]}/{key}')
        actions += a['actual'] == b['actual']
        tokens += a['tokens'] == b['tokens']
        if a['actual'] != b['actual'] or a['tokens'] != b['tokens']:
            mismatches.append(a['id'])
        if 'generation_score_features' in a and 'generation_score_features' in b:
            maximum = max(maximum, abs(a['generation_score_features']['emission_score'] - b['generation_score_features']['emission_score']))
            score_cases += 1
    passed = not mismatches and maximum <= args.score_tolerance
    result = {'status': 'PASS' if passed else 'FAIL', 'reference_sha256': digest(args.reference),
              'candidate_sha256': digest(args.candidate), 'code_sha256': digest(Path(__file__)),
              'cases': len(left['cases']), 'output_agreement': actions, 'token_agreement': tokens,
              'emission_scores_compared': score_cases, 'maximum_emission_score_difference': maximum,
              'emission_score_tolerance': args.score_tolerance, 'mismatching_ids': mismatches,
              'limits': ['Only these fixed cases and precisions were compared',
                         'Legacy references may lack HF-file hashes; checkpoint manifest and exact inputs still match',
                         'Batched wall time is not per-input product latency or a correctness calibration']}
    write_json(args.out, result)
    print(json.dumps({k: result[k] for k in ('status', 'cases', 'output_agreement', 'token_agreement',
                                           'emission_scores_compared', 'maximum_emission_score_difference')}, indent=2))
    if not passed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
