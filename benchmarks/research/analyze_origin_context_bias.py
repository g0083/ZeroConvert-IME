# SPDX-License-Identifier: Apache-2.0
"""Audit repair context/action coverage and paired normal predictions.

This reports data priors and fixed diagnostic pairs. It changes neither labels
nor model outputs and does not infer real user-error frequencies.
"""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import re

from origin_contract import digest, write_json
from rebalance_origin_repair import iter_rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--diagnostic', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    manifest = json.loads((args.dataset / 'manifest.json').read_text(encoding='utf-8'))
    diagnostic = json.loads(args.diagnostic.read_text(encoding='utf-8'))
    if diagnostic['validation_sha256'] != manifest['output_hashes']['val.jsonl']:
        raise ValueError('Diagnostic uses a different validation partition')
    for name, value in manifest['output_hashes'].items():
        if digest(args.dataset / name) != value:
            raise ValueError('Dataset changed')
    counts = Counter()
    for row in iter_rows(args.dataset / 'train.jsonl'):
        if row['task'] == 'repair_qwerty':
            counts[(bool(row['input'].get('ctx')), bool(row['input'].get('pending')), row['target']['action'])] += 1
    pairs = defaultdict(dict)
    for row in diagnostic['cases']:
        if row['task'] != 'repair_qwerty' or row['category'] not in ('clean', 'clean-no-context'):
            continue
        match = re.fullmatch(r'(magpie-\d+-s\d+)-\d+', row['id'])
        if not match:
            raise ValueError('Paired grouping requires the recorded Magpie sentence identity scheme')
        key = (match.group(1), row['input']['raw'])
        if row['category'] in pairs[key]:
            raise ValueError('Duplicate paired normal category')
        pairs[key][row['category']] = row
    paired = Counter()
    for group in pairs.values():
        if set(group) != {'clean', 'clean-no-context'}:
            continue
        left, right = group['clean'], group['clean-no-context']
        for field in ('document_id', 'expected', 'reference_reading'):
            if left[field] != right[field]:
                raise ValueError('Paired source identities or labels differ')
        if {k: v for k, v in left['input'].items() if k != 'ctx'} != {k: v for k, v in right['input'].items() if k != 'ctx'}:
            raise ValueError('Paired inputs differ beyond context')
        if not left['input'].get('ctx') or right['input'].get('ctx'):
            continue  # Empty original contexts are not a context/no-context contrast.
        wrong = lambda row: row['actual']['action'] == 'emit' and row['actual']['text'] != row['reference_reading']
        a, b = wrong(left), wrong(right)
        label = 'both_wrong' if a and b else 'only_with_context_wrong' if a else 'only_without_context_wrong' if b else 'neither_wrong'
        paired[label] += 1
    complete = {}
    for has_context in (False, True):
        subset = {action: counts[(has_context, False, action)] for action in ('keep', 'emit', 'abstain')}
        total = sum(subset.values())
        complete[str(has_context)] = {'counts': subset, 'emit_label_fraction': subset['emit'] / total if total else None}
    result = {'schema': 'origin-repair-context-bias-v1', 'dataset_manifest_sha256': digest(args.dataset / 'manifest.json'),
              'diagnostic_sha256': digest(args.diagnostic), 'code_sha256': digest(Path(__file__)),
              'train_coverage': [{'has_context': ctx, 'has_pending': pending, 'action': action, 'rows': value}
                                 for (ctx, pending, action), value in sorted(counts.items())],
              'complete_input_action_priors': complete, 'paired_normal_results': dict(paired),
              'paired_normal_cases': sum(paired.values()),
              'limits': ['Only canonical normal pairs with exactly equal non-context inputs and labels are compared',
                         'A context prior difference is not alone proof of the cause of every model error',
                         'These are synthetic/development distributions, not real typing frequencies',
                         'No new training data, labels or threshold decisions are created']}
    write_json(args.out, result)
    print(json.dumps({'complete_input_action_priors': complete, 'paired_normal_results': dict(paired),
                      'paired_normal_cases': sum(paired.values())}, indent=2))


if __name__ == '__main__':
    main()
