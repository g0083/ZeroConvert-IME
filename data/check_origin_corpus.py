# SPDX-License-Identifier: Apache-2.0
"""Stream-audit the pilot against withheld source IDs and exact sentences.

Only aggregate counts and hashes are emitted. Withheld texts never enter a
tokenizer/model or model-selection metric in this audit.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

from dataset_isolation import evaluation_keys, normalize
from origin_contract import digest, validate_row, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--withheld', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    manifest = json.loads((args.dataset / 'manifest.json').read_text(encoding='utf-8'))
    for name, value in manifest['output_hashes'].items():
        if digest(args.dataset / name) != value:
            raise ValueError(f'Dataset hash mismatch: {name}')
    reserved = evaluation_keys()
    withheld_ids, withheld_sentences = set(), set()
    with (args.withheld / 'documents.jsonl').open(encoding='utf-8') as stream:
        for line in stream:
            row = json.loads(line)
            withheld_ids.add(f'magpie-{row["provenance"]["source_record_id"]}')
            for message in row['messages']:
                for match in re.finditer(r'[^。！？\n]+[。！？]?', message['content']):
                    text = match.group().strip()
                    if 8 <= len(text) <= 96:
                        withheld_sentences.add(normalize(text))
    identities = {}
    counts = Counter()
    for partition in ('train', 'val'):
        with (args.dataset / f'{partition}.jsonl').open(encoding='utf-8') as stream:
            for line in stream:
                row = json.loads(line)
                validate_row(row)
                if row['document_id'] in withheld_ids or normalize(row['source_text']) in withheld_sentences:
                    raise ValueError('Withheld document or sentence leaked')
                texts = [row['source_text'], row['target'].get('text', '')] + [v for v in row['input'].values() if isinstance(v, str)]
                if any(normalize(s) in reserved for s in texts if s):
                    raise ValueError('Development benchmark collision')
                keys = [('document', row['document_id']), ('source', normalize(row['source_text'])),
                        ('example', json.dumps([row['task'], row['input'], row['target']], sort_keys=True, ensure_ascii=False))]
                for key in keys:
                    compact = hashlib.sha256(repr(key).encode()).digest()
                    if compact in identities and identities[compact] != partition:
                        raise ValueError(f'Cross-partition overlap: {key[0]}')
                    identities[compact] = partition
                counts[f'{partition}_{row["task"]}'] += 1
    result = {'status': 'PASS', 'counts': dict(counts), 'withheld_documents': len(withheld_ids),
              'withheld_source_sentence_keys': len(withheld_sentences), 'manifest_sha256': digest(args.dataset / 'manifest.json'),
              'checks': ['source documents', 'exact normalized source sentences', 'exact task prompts/targets',
                         'existing development keys', 'withheld IDs and exact source sentences'],
              'limits': ['Does not prove zero semantic overlap or exhaustive near-duplicate recall']}
    write_json(args.out, result)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
