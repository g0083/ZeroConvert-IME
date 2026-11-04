# SPDX-License-Identifier: Apache-2.0
"""Aggregate conversion-length coverage without displaying case text."""
import argparse
from collections import Counter
import json
from pathlib import Path

from origin_contract import digest, write_json


def length_bin(length):
    for maximum in (4, 8, 16, 32):
        if length <= maximum:
            return f'<= {maximum}'
    return '> 32'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    summaries = {}
    paths = [args.dataset / 'train.jsonl', Path('data/kana_kanji_llm_testset_5x.jsonl'),
             Path('data/kana_kanji_hard_context_testset.jsonl')]
    for path in paths:
        bins, categories = Counter(), Counter()
        count, total = 0, 0
        with path.open(encoding='utf-8') as stream:
            for line in stream:
                row = json.loads(line)
                if row.get('task') and row['task'] != 'convert':
                    continue
                reading = row['input']['reading'] if 'input' in row else row['target']
                bins[length_bin(len(reading))] += 1
                categories[row.get('category', 'unspecified')] += 1
                count += 1
                total += len(reading)
        summaries[str(path)] = {'sha256': digest(path), 'rows': count,
                                'mean_reading_characters': total / max(1, count),
                                'length_bins': dict(bins), 'categories': dict(categories)}
    result = {'source_manifest_sha256': digest(args.dataset / 'manifest.json'), 'summaries': summaries,
              'code_sha256': digest(Path(__file__)),
              'limits': ['Development distribution diagnostic only; no sealed data or text examples',
                         'Character lengths are not model tokens, frequency in real typing, or quality scores']}
    write_json(args.out, result)
    print(json.dumps(summaries, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
