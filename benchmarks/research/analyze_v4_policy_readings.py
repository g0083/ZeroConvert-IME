# SPDX-License-Identifier: Apache-2.0
"""Secondary reading checks without modifying fixed surface scores/acceptable labels."""
import argparse
from collections import Counter
import json
from pathlib import Path

from evaluate_v4_repair_comparison import ReadingVerifier
from origin_contract import digest, write_json
from v4_unified_contract import hira


def analyze(report, verifier):
    counts, exceptions = Counter(), []
    for row in report['cases']:
        output = row['correction']
        accepted = output is not None and output in [row['expected'], *(row.get('acceptable') or [])]
        reference = hira(row.get('expected_reading') or '')
        verified = bool(reference) and all('ぁ' <= c <= 'ゖ' or c in 'ーゝゞ、。！？!?・' for c in reference)
        reading = bool(output) and verified and (hira(output) == reference or verifier.matches(output, reference))
        if row['is_clean'] is not True:
            counts['typo_total'] += 1
            counts['surface_accepted_suggestions'] += int(accepted)
            counts['surface_accepted_or_reading_matched_suggestions'] += int(accepted or reading)
            counts['reading_matched_but_surface_not_accepted'] += int(reading and not accepted)
        if row['auto_apply'] and output is not None and not accepted:
            counts['auto_surface_nonmatch'] += 1
            counts['auto_surface_nonmatch_reading_matched'] += int(reading)
            counts['auto_surface_nonmatch_reading_not_confirmed'] += int(not reading)
            exceptions.append({k: row.get(k) for k in ('id', 'input', 'context', 'expected', 'expected_reading', 'correction')}
                              | {'reading_matched': reading})
    return {'summary': dict(counts), 'auto_surface_nonmatches': exceptions}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--reports', type=Path, nargs='+', required=True)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    if a.out.exists():
        raise FileExistsError(a.out)
    dictionary = Path('ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin')
    verifier = ReadingVerifier(dictionary)
    result = {'reports': {str(path): {'source_sha256': digest(path), **analyze(json.loads(path.read_text(encoding='utf-8')), verifier)}
                          for path in a.reports}, 'dictionary_sha256': digest(dictionary), 'code_sha256': digest(__file__),
              'limits': ['Original surface scores/acceptable labels unchanged',
                         'Secondary automatic reading check, not semantic or actual-IME correctness',
                         'Surface-accepted outputs included even where explicit reading reference is unavailable']}
    write_json(a.out, result)
    print(json.dumps({k: v['summary'] for k, v in result['reports'].items()}), flush=True)
