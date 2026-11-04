# SPDX-License-Identifier: Apache-2.0
"""Verify that a span augmentation only appends source-aligned training rows."""
import argparse
import importlib.metadata
import json
from pathlib import Path

from build_origin_corpus import KANJI, orthographic_tokens
from origin_contract import digest, validate_row, write_json
from rebalance_origin_repair import iter_rows


def validate_span(parent, child, tokens):
    span = child['source_span']
    if set(span) != {'start', 'end', 'token_start', 'token_end'} or any(type(v) is not int for v in span.values()):
        raise ValueError('Invalid source-span offsets')
    first, stop = span['token_start'], span['token_end']
    if not 0 <= first < stop <= len(tokens) or stop - first > 3:
        raise ValueError('Span exceeds bounded source token range')
    sentence = parent['source_text']
    if parent['task'] != 'convert' or parent.get('category') != 'full' or set(parent['input']) - {'ctx', 'reading'}:
        raise ValueError('Not an eligible training parent')
    if ''.join(s for s, _ in tokens) != sentence or ''.join(r for _, r in tokens) != parent['input']['reading']:
        raise ValueError('Parent labeler alignment differs')
    if parent['target'] != {'action': 'emit', 'text': sentence}:
        raise ValueError('Parent target is not the full source sentence')
    begin = sum(len(s) for s, _ in tokens[:first])
    end = sum(len(s) for s, _ in tokens[:stop])
    if (span['start'], span['end']) != (begin, end):
        raise ValueError('Character offsets do not match token boundaries')
    surface = sentence[begin:end]
    reading = ''.join(r for _, r in tokens[first:stop])
    if not (1 <= len(surface) <= 16 and 2 <= len(reading) <= 32 and KANJI.search(surface)) or surface == sentence:
        raise ValueError('Span is not a bounded content word/phrase')
    expected = {**parent, 'id': f'{parent["id"]}-span-{first}-{stop}',
                'input': {'ctx': (parent['input'].get('ctx', '') + sentence[:begin])[-48:],
                          'reading': reading},
                'target': {'action': 'emit', 'text': surface}, 'category': 'morph-span',
                'span_parent_id': parent['id'], 'source_span': span,
                'span_policy': 'one single-word and one short multiword span where available; preceding context only'}
    if child != expected:
        raise ValueError('Span changed source identity, context, reading, target or metadata')
    validate_row(child)


def identical_prefix(parent, augmented):
    with parent.open('rb') as original, augmented.open('rb') as derived:
        while block := original.read(1024 * 1024):
            if derived.read(len(block)) != block:
                raise ValueError('Original training bytes changed, reordered or removed')
    return parent.stat().st_size


def verify(dataset):
    manifest = json.loads((dataset / 'manifest.json').read_text(encoding='utf-8'))
    if manifest['operation'] != 'source-aligned training conversion span augmentation':
        raise ValueError('Not a span augmentation')
    parent = Path(manifest['parent'])
    if digest(parent / 'manifest.json') != manifest['parent_manifest_sha256']:
        raise ValueError('Parent manifest changed')
    original = json.loads((parent / 'manifest.json').read_text(encoding='utf-8'))
    if original['output_hashes'] != manifest['parent_output_hashes']:
        raise ValueError('Wrong parent output identity')
    for name, value in original['output_hashes'].items():
        if digest(parent / name) != value:
            raise ValueError('Parent outputs changed')
    for name, value in manifest['output_hashes'].items():
        if digest(dataset / name) != value:
            raise ValueError('Augmented outputs changed')
    for name in ('val.jsonl', 'sources.json'):
        if manifest['output_hashes'][name] != original['output_hashes'][name]:
            raise ValueError('Validation or source licensing changed')
    for evidence in (parent / 'source-evidence').rglob('*'):
        if evidence.is_file() and digest(evidence) != digest(dataset / evidence.relative_to(parent)):
            raise ValueError('Parent evidence changed')
    import fugashi
    import unidic_lite
    labeler = manifest['labeler']
    if (digest(Path(unidic_lite.DICDIR) / 'sys.dic') != labeler['dictionary_sha256'] or
            importlib.metadata.version('fugashi') != labeler['fugashi'] or
            importlib.metadata.version('unidic-lite') != labeler['unidic-lite']):
        raise ValueError('Span verification requires the recorded labeler')
    tagger = fugashi.Tagger()
    offset = identical_prefix(parent / 'train.jsonl', dataset / 'train.jsonl')
    verified = 0
    with (dataset / 'train.jsonl').open('rb') as stream:
        stream.seek(offset)
        def next_child():
            line = stream.readline()
            return json.loads(line) if line else None
        child = next_child()
        for row in iter_rows(parent / 'train.jsonl'):
            if child is None:
                break
            if child['span_parent_id'] != row['id']:
                continue
            tokens = orthographic_tokens(row['source_text'], tagger)
            local = 0
            while child is not None and child['span_parent_id'] == row['id']:
                validate_span(row, child, tokens)
                local += 1
                verified += 1
                if local > manifest['arguments']['max_spans']:
                    raise ValueError('Too many spans for one training parent')
                child = next_child()
            if verified % 10000 < 2:
                print(f'Validated {verified:,} source-aligned spans', flush=True)
        if child is not None:
            raise ValueError('A new span has no ordered training parent')
    if verified != manifest['counts'].get('new_training_spans', 0):
        raise ValueError('Declared and verified span counts differ')
    return {'status': 'PASS', 'dataset_manifest_sha256': digest(dataset / 'manifest.json'),
            'parent_manifest_sha256': digest(parent / 'manifest.json'),
            'parent_output_hashes': original['output_hashes'], 'output_hashes': manifest['output_hashes'],
            'verified_spans': verified, 'original_training_prefix_bytes': offset,
            'code_sha256': digest(Path(__file__)),
            'checks': ['all original training bytes preserved', 'validation/source license hashes unchanged',
                       'all new spans have a training parent', 'token/character boundaries and reading aligned',
                       'context contains preceding text only', 'source IDs and all other parent metadata unchanged',
                       'parent source evidence retained'],
            'limits': ['Automatic readings are not independently human-reviewed',
                       'Run the separate source/withheld/development-key isolation audit before training']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    result = verify(args.dataset)
    write_json(args.out, result)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
