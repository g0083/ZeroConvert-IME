# SPDX-License-Identifier: Apache-2.0
"""Development-only repair operating points with target-blind proposal filtering.

Threshold fitting uses calibration only. A separate invocation can assess the
frozen candidate on validation-check. Neither approves real IME auto-adoption.
"""
import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import re

from keyboard_hybrid import RomanDecoder
from origin_contract import digest, write_json
from origin_operating_cohort import document_partition


def binomial_upper(errors, trials, alpha=.05):
    """One-sided Clopper-Pearson upper bound (assuming independent trials)."""
    if not 0 <= errors <= trials or not 0 < alpha < 1:
        raise ValueError('Invalid binomial counts or alpha')
    if trials == 0:
        return None
    if errors == trials:
        return 1.0
    if errors == 0:
        return -math.expm1(math.log(alpha) / trials)
    coefficients = [math.lgamma(trials + 1) - math.lgamma(j + 1) - math.lgamma(trials - j + 1)
                    for j in range(errors + 1)]
    low, high = errors / trials, 1.0
    for _ in range(65):
        p = (low + high) / 2
        logs = [value + j * math.log(p) + (trials - j) * math.log1p(-p) for j, value in enumerate(coefficients)]
        maximum = max(logs)
        cdf = math.exp(maximum) * sum(math.exp(value - maximum) for value in logs)
        if cdf > alpha:
            low = p
        else:
            high = p
    return (low + high) / 2


def binomial_lower(successes, trials, alpha=.05):
    value = binomial_upper(trials - successes, trials, alpha)
    return None if value is None else 1 - value


def proposal_decision(inputs, actual, score_features, decoder, threshold):
    # Intentionally takes no expected answer, reference reading or source text.
    if actual['action'] != 'emit':
        return 'no_emit'
    if inputs.get('pending'):
        return 'observed_pending'
    if not re.fullmatch('[ぁ-ゖーゝゞ]+', actual['text']):
        return 'not_hiragana_reading'
    if actual['text'] == decoder.decode(inputs['raw']):
        return 'unchanged_offline_observed_reading'
    score = score_features['emission_score']
    if not isinstance(score, (int, float)) or not math.isfinite(score) or not 0 <= score <= 1:
        raise ValueError('Invalid generated emission score')
    return 'accepted' if score >= threshold else 'below_score_threshold'


def operating_point(cases, decoder, threshold):
    counts = Counter({key: 0 for key in ('keep_cases', 'emit_cases', 'abstain_cases', 'raw_model_pending_emit',
                      'accepted_reading_changes', 'correct_noisy_reading_changes', 'normal_false_changes', 'abstain_label_changes')})
    reasons = Counter()
    accepted_documents = defaultdict(list)
    normal_documents, normal_error_documents = set(), set()
    for case in cases:
        expected, actual = case['expected'], case['actual']
        label = expected['action']
        counts[f'{label}_cases'] += 1
        if label == 'keep':
            reference = case.get('reference_reading')
            if not reference or reference != decoder.decode(case['input']['raw']):
                raise ValueError('Clean reference does not match pinned offline decoding; review labels first')
            normal_documents.add(case['document_id'])
        if case['input'].get('pending') and actual['action'] == 'emit':
            counts['raw_model_pending_emit'] += 1
        decision = proposal_decision(case['input'], actual, case['generation_score_features'], decoder, threshold)
        reasons[decision] += 1
        if decision != 'accepted':
            continue
        correct = label == 'emit' and actual['text'] == expected['text']
        counts['accepted_reading_changes'] += 1
        counts['correct_noisy_reading_changes'] += correct
        accepted_documents[case['document_id']].append(correct)
        if label == 'keep':
            counts['normal_false_changes'] += 1
            normal_error_documents.add(case['document_id'])
        if label == 'abstain':
            counts['abstain_label_changes'] += 1
    accepted = counts['accepted_reading_changes']
    all_correct_docs = sum(all(values) for values in accepted_documents.values())
    return {'threshold': threshold, 'counts': dict(counts), 'filter_reasons': dict(reasons),
            'reading_change_precision_point': counts['correct_noisy_reading_changes'] / accepted if accepted else None,
            'noisy_reading_recall_point': counts['correct_noisy_reading_changes'] / counts['emit_cases'] if counts['emit_cases'] else None,
            'normal_false_change_rate_point': counts['normal_false_changes'] / counts['keep_cases'] if counts['keep_cases'] else None,
            'document_cluster_diagnostics': {
                'accepted_documents': len(accepted_documents), 'all_accepted_correct_documents': all_correct_docs,
                'accepted_document_all_correct_lower95': binomial_lower(all_correct_docs, len(accepted_documents)),
                'normal_documents': len(normal_documents), 'normal_any_error_documents': len(normal_error_documents),
                'normal_document_any_error_upper95': binomial_upper(len(normal_error_documents), len(normal_documents)),
                'scope': 'Document events assuming independent source documents; not a per-keystroke correctness CI'},
            'automatic_adoption_approved': False}


def inference_condition(diagnostic):
    return {key: diagnostic.get(key) for key in ('device', 'batch_size', 'model_parameter_dtypes', 'batch_inference_code_sha256')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--diagnostic', type=Path, required=True)
    parser.add_argument('--cohort', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--selection', type=Path, help='Frozen calibration report; assess its candidate on validation-check only')
    parser.add_argument('--contract', type=Path, default=Path('artifacts/results/origin-comparison-contract-20261029-r2.json'))
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    diagnostic = json.loads(args.diagnostic.read_text(encoding='utf-8'))
    cohort = json.loads(args.cohort.read_text(encoding='utf-8'))
    identity = digest(args.run / 'run-manifest.json')
    role = 'validation-check' if args.selection else 'calibration'
    if diagnostic['manifest_sha256'] != identity or not diagnostic.get('generation_scores_recorded'):
        raise ValueError('Matching checkpoint diagnostic with generated scores required')
    if (not diagnostic.get('operating_cohort') or diagnostic['operating_cohort']['sha256'] != digest(args.cohort) or
            diagnostic['operating_cohort']['partition'] != role or diagnostic['validation_sha256'] != cohort['validation_sha256']):
        raise ValueError('Use the required frozen cohort partition; fitting on validation-check is forbidden')
    hf = args.run / 'hf'
    hashes = {str(p.relative_to(hf)): digest(p) for p in sorted(hf.rglob('*')) if p.is_file()}
    if hashes != diagnostic.get('hf_source_hashes'):
        raise ValueError('HF source hashes differ from the diagnostic')
    allowed_ids = set(cohort['partitions'][role]['case_ids'])
    cases = diagnostic['cases']
    if len({c['id'] for c in cases}) != len(cases):
        raise ValueError('Duplicate diagnostic cases')
    if any(c['task'] != 'repair_qwerty' or c['id'] not in allowed_ids or document_partition(c['document_id'], cohort['seed']) != role for c in cases):
        raise ValueError('A diagnostic case does not belong to this cohort')
    evidence = args.run / 'source-evidence'
    provenance = json.loads((evidence / 'decoder-label-provenance.json').read_text(encoding='utf-8'))
    decoder_source = Path(__file__).with_name('keyboard_hybrid.py')
    table = evidence / 'defaultRoman2Kana.swift'
    if digest(decoder_source) != provenance['offline_decoder_sha256'] or digest(table) != provenance['table_sha256']:
        raise ValueError('Pinned offline decoder evidence differs')
    decoder = RomanDecoder(table)
    contract = json.loads(args.contract.read_text(encoding='utf-8'))
    gates = contract['predeclared_gates']
    targets = {'precision': gates['autocorrection_precision_target'], 'recall': gates['qwerty_reading_recovery_target'],
               'normal_false_change': gates['normal_false_change_rate_one_sided_ci95_upper_lt']}
    prior = None
    if args.selection:
        prior = json.loads(args.selection.read_text(encoding='utf-8'))
        for key, expected in (('run_manifest_sha256', identity), ('cohort_sha256', digest(args.cohort)),
                              ('code_sha256', digest(Path(__file__))), ('contract_sha256', digest(args.contract)),
                              ('inference_condition', inference_condition(diagnostic))):
            if prior[key] != expected:
                raise ValueError(f'Frozen selection identity differs: {key}')
        candidate = prior['candidate_by_point_estimates_only']
        if candidate is None:
            raise ValueError('Calibration found no candidate; do not choose one using validation-check')
        thresholds = [candidate['threshold']]
    else:
        thresholds = [0, .25, .5, .75, .9, .95, .99, .995, .999]
    points = [operating_point(cases, decoder, threshold) for threshold in thresholds]
    qualifying = [p for p in points if p['reading_change_precision_point'] is not None and
                  p['reading_change_precision_point'] >= targets['precision'] and
                  p['noisy_reading_recall_point'] is not None and p['noisy_reading_recall_point'] >= targets['recall'] and
                  p['normal_false_change_rate_point'] is not None and p['normal_false_change_rate_point'] < targets['normal_false_change']]
    candidate = max(qualifying, key=lambda p: (p['noisy_reading_recall_point'], -p['threshold'])) if qualifying else None
    result = {'schema': 'origin-operating-points-v1', 'run_manifest_sha256': identity,
              'diagnostic_sha256': digest(args.diagnostic), 'cohort_sha256': digest(args.cohort),
              'contract_sha256': digest(args.contract), 'code_sha256': digest(Path(__file__)),
              'inference_condition': inference_condition(diagnostic), 'role': role,
              'assessed_selection_sha256': digest(args.selection) if args.selection else None,
              'sampled_cases': len(cases), 'cohort_population': cohort['partitions'][role]['rows'],
              'source_sample_method': diagnostic['sample_method'], 'point_estimate_targets': targets,
              'decoder_table_sha256': digest(table), 'decoder_source_sha256': digest(decoder_source),
              'candidate_by_point_estimates_only': candidate if not args.selection else prior['candidate_by_point_estimates_only'],
              'points': points, 'automatic_adoption_approved': False,
              'limits': ['Exploratory synthetic development data, not real typing or sealed evaluation',
                         'Confidence features are not probabilities of correctness',
                         'No-op changes are excluded with an offline approximation, not real TSF state',
                         'Row precision/recall are sample point estimates; document-event CIs have a different unit',
                         'A point-estimate candidate does not satisfy product confidence or IME gates',
                         'Validation-check outcomes must not be used to refit a threshold']}
    write_json(args.out, result)
    print(json.dumps({'role': role, 'sampled_cases': len(cases), 'candidate_found': candidate is not None,
                      'unthresholded': points[0], 'automatic_adoption_approved': False}, indent=2))


if __name__ == '__main__':
    main()
