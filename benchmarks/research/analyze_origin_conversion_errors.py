# SPDX-License-Identifier: Apache-2.0
"""Classify development conversion errors without changing any benchmark score.

The morphological reading check is an automatic diagnostic, not a gold-reading
oracle or an output-repair path. No case text is printed or used for training.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re

from build_origin_corpus import hira, orthographic_tokens
from origin_contract import digest, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--result', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    source = json.loads(args.result.read_text(encoding='utf-8'))
    import fugashi
    tagger = fugashi.Tagger()
    dictionaries = {str(info['filename']): digest(Path(info['filename'])) for info in tagger.dictionary_info}
    kanji, totals, cases = re.compile('[一-鿿々]'), Counter(), []
    category_totals = {}
    for testset, result in source['results'].items():
        if sum(row['score'] == 2 for row in result['details']) != result['perfect_count']:
            raise ValueError('Stored aggregate disagrees with scores')
        for row in result['details']:
            totals['cases'] += 1
            if row['score'] == 2:
                totals['correct'] += 1
                continue
            observed = row['output'].strip().replace('\r', '').replace('\n', '')
            reading = hira(row['target'])
            flags = []
            if observed and observed[-1] in '。！？' and not row['expected'].endswith(observed[-1]):
                flags.append('unexpected_terminal_punctuation')
            if not observed:
                label = 'empty_or_invalid_output'
            elif kanji.search(row['expected']) and hira(observed) == reading:
                label = 'exact_reading_copy_when_kanji_expected'
            else:
                try:
                    output_reading = ''.join(r for _, r in orthographic_tokens(observed, tagger))
                except ValueError:
                    label = 'automatic_reading_unresolved'
                else:
                    label = ('same_automatic_reading_wrong_surface' if output_reading == reading else
                             'different_automatic_reading')
                    if label == 'different_automatic_reading' and output_reading.rstrip('。！？') == reading.rstrip('。！？'):
                        flags.append('reading_differs_only_in_terminal_punctuation')
            if observed and kanji.search(row['expected']) and not kanji.search(observed):
                flags.append('no_kanji_despite_expected_kanji')
            totals[label] += 1
            totals.update(flags)
            category_totals.setdefault(row['category'], Counter())[label] += 1
            cases.append({'id': row['id'], 'testset': testset, 'label': label, 'flags': flags})
    result = {'schema': 'origin-conversion-error-diagnostic-v1', 'source_result_sha256': digest(args.result),
              'code_sha256': digest(Path(__file__)), 'dictionary_hashes': dictionaries,
              'counts': dict(totals), 'by_category': {k: dict(v) for k, v in category_totals.items()}, 'cases': cases,
              'limits': ['Development-only diagnostic; original scores and outputs remain unchanged',
                         'UniDic can choose the wrong reading for ambiguous or unknown output words',
                         'Error labels are not human adjudication or training examples',
                         'Flags overlap; the main error labels partition wrong cases']}
    write_json(args.out, result)
    print(json.dumps(result['counts'], indent=2))


if __name__ == '__main__':
    main()
