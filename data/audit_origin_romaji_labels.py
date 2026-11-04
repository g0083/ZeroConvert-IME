# SPDX-License-Identifier: Apache-2.0
"""Audit synthetic raw labels against the pinned converter-table decoder.

The decoder is a simplified offline approximation, not a TSF state oracle.
Only aggregate counts are printed; sampled issues remain in local artifacts.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import unicodedata

from keyboard_hybrid import RomanDecoder, TABLE
from origin_contract import digest, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    decoder = RomanDecoder()
    totals, samples = Counter(), []
    for partition in ('train', 'val'):
        with (args.dataset / f'{partition}.jsonl').open(encoding='utf-8') as source:
            for line in source:
                row = json.loads(line)
                if row['task'] != 'repair_qwerty':
                    continue
                raw, pending = row['input']['raw'], row['input'].get('pending', '')
                reference = row.get('reference_reading') or row['target'].get('text') or row['input'].get('surface')
                action = row['target']['action']
                decoded = decoder.decode(raw)
                totals[f'{partition}_{action}'] += 1
                issues = []
                equivalent = reference and unicodedata.normalize('NFC', decoded) == unicodedata.normalize('NFC', reference)
                if action == 'keep' and reference and not equivalent:
                    issues.append('clean_does_not_roundtrip_simplified_table')
                if action == 'emit' and equivalent:
                    issues.append('synthetic_error_is_equivalent_valid_romanization')
                if not pending and decoded and decoded[-1].isascii() and decoded[-1].isalpha():
                    issues.append('possible_unreported_terminal_pending')
                if not pending and raw.endswith('n') and not raw.endswith('nn'):
                    issues.append('terminal_n_resolution_sensitive')
                for issue in issues:
                    totals[f'{partition}_{issue}'] += 1
                if issues and len(samples) < 100:
                    samples.append({'id': row['id'], 'partition': partition, 'issues': issues,
                                    'input': row['input'], 'expected': row['target'], 'reference': reference,
                                    'simplified_decoded': decoded})
    result = {'dataset_manifest_sha256': digest(args.dataset / 'manifest.json'),
              'decoder_table_sha256': digest(TABLE), 'decoder_source_sha256': digest(Path(__file__).resolve().parents[1] / 'benchmarks/research/keyboard_hybrid.py'),
              'counts': dict(totals), 'samples': samples,
              'limits': ['Table-driven offline approximation; not real composition state',
                         'Equivalent raw readings are not genuine typo rescue cases',
                         'Terminal residuals need real event-state review, not automatic relabeling by this audit']}
    write_json(args.out, result)
    print(json.dumps(dict(totals), indent=2))


if __name__ == '__main__':
    main()
