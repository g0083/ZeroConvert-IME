# SPDX-License-Identifier: Apache-2.0
"""Freeze source-document groups for development-only operating-point checks.

This subdivides existing validation, not independent sealed gold evaluation.
No model output or confidence score participates in assignment.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from origin_contract import digest, read_rows, write_json

PARTITIONS = ('calibration', 'validation-check')


def document_partition(document_id, seed):
    if not isinstance(document_id, str) or not document_id:
        raise ValueError('A nonempty source document ID is required')
    bit = hashlib.sha256(f'origin-operating-v1:{seed}:{document_id}'.encode()).digest()[0] & 1
    return PARTITIONS[bit]


def build_cohort(rows, validation_sha256, seed):
    eligible = [row for row in rows if row['task'] == 'repair_qwerty']
    if len({row['id'] for row in eligible}) != len(eligible):
        raise ValueError('Repair case IDs must be unique')
    partitions = {}
    for name in PARTITIONS:
        subset = [row for row in eligible if document_partition(row['document_id'], seed) == name]
        actions = Counter(row['target']['action'] for row in subset)
        partitions[name] = {'case_ids': [row['id'] for row in subset], 'rows': len(subset),
                            'documents': len({row['document_id'] for row in subset}),
                            'rows_by_action': dict(actions),
                            'documents_by_action': {action: len({row['document_id'] for row in subset if row['target']['action'] == action})
                                                    for action in sorted(actions)}}
    return {'schema': 'origin-operating-cohort-v1', 'validation_sha256': validation_sha256, 'seed': seed,
            'task': 'repair_qwerty', 'partitions': partitions, 'code_sha256': digest(Path(__file__)),
            'assignment': 'SHA256 of fixed seed and source document ID; all derived examples stay together',
            'limits': ['Existing validation was used for model development; neither partition is sealed evaluation',
                       'Synthetic errors and automatic labels, not real typing or calibrated auto-adoption quality',
                       'Rows within a source document are correlated; retain document IDs for uncertainty analysis',
                       'Do not select thresholds using validation-check outcomes']}


def select_cohort_rows(rows, cohort, partition, validation_sha256):
    if partition not in PARTITIONS or cohort.get('schema') != 'origin-operating-cohort-v1':
        raise ValueError('Unknown cohort or partition')
    if cohort['validation_sha256'] != validation_sha256 or cohort['code_sha256'] != digest(Path(__file__)):
        raise ValueError('Cohort source or assignment implementation changed')
    expected = build_cohort(rows, validation_sha256, cohort['seed'])
    if cohort['partitions'] != expected['partitions']:
        raise ValueError('Cohort IDs/counts differ from the source-only assignment')
    return [row for row in rows if row['task'] == 'repair_qwerty' and document_partition(row['document_id'], cohort['seed']) == partition]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--seed', type=int, default=20261029)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    path = args.dataset / 'val.jsonl'
    identity = digest(path)
    manifest = json.loads((args.dataset / 'manifest.json').read_text(encoding='utf-8'))
    if identity != manifest['output_hashes']['val.jsonl']:
        raise ValueError('Validation differs from the source manifest')
    cohort = build_cohort(read_rows(path), identity, args.seed)
    cohort['source_manifest_sha256'] = digest(args.dataset / 'manifest.json')
    write_json(args.out, cohort)
    print(json.dumps({name: {k: v for k, v in part.items() if k != 'case_ids'} for name, part in cohort['partitions'].items()}, indent=2))


if __name__ == '__main__':
    main()
