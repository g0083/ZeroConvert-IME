# SPDX-License-Identifier: Apache-2.0
"""Describe prediction-target difficulty without changing scores or labels.

Length and automatic POS are descriptive proxies, not human usefulness labels.
No withheld source is opened. Diagnostics retain their original sampled cases.
"""
import argparse
from collections import Counter, defaultdict
import importlib.metadata
import json
from pathlib import Path
import unicodedata

from origin_contract import digest, write_json

CONTENT_POS = {'名詞', '動詞', '形容詞', '副詞', '連体詞', '感動詞'}


def target_profile(text, tagger):
    alnum = sum(c.isalnum() for c in text)
    content = any(w.feature.pos1 in CONTENT_POS and w.feature.pos2 != '非自立可能' for w in tagger(text))
    return {'characters': len(text), 'alphanumeric_characters': alnum,
            'no_alphanumeric_characters': alnum == 0,
            'punctuation_or_space_only': bool(text) and all(c.isspace() or unicodedata.category(c).startswith('P') for c in text),
            'content_pos_proxy': content, 'at_least_four_alphanumeric': alnum >= 4}


def aggregate(counter, profile):
    counter['cases'] += 1
    counter['target_characters'] += profile['characters']
    counter['target_alphanumeric_characters'] += profile['alphanumeric_characters']
    for name in ('no_alphanumeric_characters', 'punctuation_or_space_only', 'content_pos_proxy', 'at_least_four_alphanumeric'):
        counter[name] += profile[name]
    counter['characters_1_to_3'] += profile['characters'] <= 3
    counter['characters_4_to_7'] += 4 <= profile['characters'] <= 7
    counter['characters_8_plus'] += profile['characters'] >= 8


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--diagnostics', type=Path, nargs='*', default=[])
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    manifest = json.loads((args.dataset / 'manifest.json').read_text(encoding='utf-8'))
    import fugashi
    tagger = fugashi.Tagger()
    dictionaries = [{**d, 'sha256': digest(d['filename'])} for d in tagger.dictionary_info]
    # Only cache short prediction targets, not entire source documents or rows.
    cache = {}
    def profile(text):
        if text not in cache:
            cache[text] = target_profile(text, tagger)
        return cache[text]
    population, hashes = {}, {}
    for split in ('train', 'val'):
        path = args.dataset / f'{split}.jsonl'
        before = digest(path)
        if before != manifest['output_hashes'][path.name]:
            raise ValueError('Dataset identity changed')
        counts, actions = Counter(), Counter()
        with path.open(encoding='utf-8') as stream:
            for line in stream:
                row = json.loads(line)
                if row['task'] != 'predict':
                    continue
                actions[row['target']['action']] += 1
                if row['target']['action'] == 'emit':
                    aggregate(counts, profile(row['target']['text']))
        if before != digest(path):
            raise ValueError('Dataset changed during profile')
        hashes[path.name] = before
        population[split] = {'actions': dict(actions), 'emit_target_profile': dict(counts)}
    diagnostics = []
    for path in args.diagnostics:
        data = json.loads(path.read_text(encoding='utf-8'))
        if data['validation_sha256'] != hashes['val.jsonl']:
            raise ValueError('Diagnostic validation identity differs')
        profiles, strata = Counter(), defaultdict(Counter)
        for row in data['cases']:
            if row['task'] != 'predict' or row['expected']['action'] != 'emit':
                continue
            p = profile(row['expected']['text'])
            exact = row['actual'] == row['expected']
            aggregate(profiles, p)
            for name in ('no_alphanumeric_characters', 'punctuation_or_space_only', 'content_pos_proxy', 'at_least_four_alphanumeric'):
                key = name + ('=true' if p[name] else '=false')
                strata[key]['cases'] += 1
                strata[key]['exact'] += exact
        diagnostics.append({'path': str(path), 'sha256': digest(path), 'emit_target_profile': dict(profiles),
                            'exact_by_target_stratum': {k: dict(v) for k, v in sorted(strata.items())}})
    write_json(args.out, {'schema': 'origin-prediction-target-profile-v1', 'population': population,
                         'dataset_manifest_sha256': digest(args.dataset / 'manifest.json'),
                         'dataset_hashes': hashes, 'diagnostics': diagnostics,
                         'labeler': {'fugashi': importlib.metadata.version('fugashi'),
                                     'unidic-lite': importlib.metadata.version('unidic-lite'),
                                     'dictionaries': dictionaries},
                         'content_pos_policy': 'POS1 in noun/verb/adjective/adverb/adnominal/interjection and POS2 != non-independent-possible',
                         'limitations': ['Automatic POS and character length are not human usefulness judgments',
                                        'Short function-word completion can still reduce typing',
                                        'Existing expected answers and exact scores unchanged',
                                        'No withheld sources accessed'], 'code_sha256': digest(Path(__file__))})
    print(json.dumps(population, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
