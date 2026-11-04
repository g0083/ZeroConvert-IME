# SPDX-License-Identifier: Apache-2.0
"""Independent paired label/prompt/split audit before repair-only learning."""
import argparse
from collections import Counter
import json
from pathlib import Path

from build_v4_unified_dataset import rows
from dataset_isolation import evaluation_keys, normalize
from origin_contract import digest
from v4_unified_contract import V4UnifiedCodec


def audit(directory):
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
    codecs = {m: V4UnifiedCodec(directory / m / 'codec') for m in ('reading', 'surface')}
    reserved, seen_split, counts = evaluation_keys(), set(), Counter()
    for mode, metadata in manifest['conditions'].items():
        for name, expected in metadata['files'].items():
            if digest(directory / mode / name) != expected:
                raise ValueError('Data changed')
        if digest(directory / mode / 'codec/v4-unified-codec.json') != metadata['codec_sha256']:
            raise ValueError('Codec changed')
    for split in ('train', 'val'):
        paired = {m: list(rows(directory / m / f'{split}.jsonl')) for m in codecs}
        if len(paired['reading']) != len(paired['surface']):
            raise ValueError('Unmatched row count')
        current, prompt_targets = set(), {}
        for left, right in zip(paired['reading'], paired['surface']):
            if left['id'] != right['id'] or left['input'] != right['input'] or left['source_group'] != right['source_group']:
                raise ValueError('Paired observations differ')
            if set(left['input']) != {'ctx', 'reading', 'pending'} or left['input']['pending']:
                raise ValueError('Invented raw or pending state')
            keys = {normalize(left['input']['reading']), normalize(left['reference_reading']), normalize(left['reference_surface'])}
            if keys & reserved:
                raise ValueError('Evaluation collision')
            current |= {'text:' + k for k in keys} | {'group:' + left['source_group']}
            if current & seen_split:
                raise ValueError('Source or observation leaked across split')
            if codecs['reading'].prompt(left) != codecs['surface'].prompt(right):
                raise ValueError('Prompt differs')
            for mode, row in [('reading', left), ('surface', right)]:
                encoded = codecs[mode].example(row, manifest['max_length'])
                if codecs[mode].decode_answer(codecs[mode].answer(row), 'repair') != row['target']:
                    raise ValueError('Target round trip')
                prompt = (mode, tuple(codecs[mode].prompt(row)))
                target = tuple(v for v in encoded['labels'] if v != -100)
                if prompt in prompt_targets and prompt_targets[prompt] != target:
                    raise ValueError('Conflicting teacher labels')
                prompt_targets[prompt] = target
            if left['target']['action'] != right['target']['action']:
                raise ValueError('Action differs')
            if left['target']['action'] == 'emit' and (left['target']['text'] != left['reference_reading'] or
                                                       right['target']['text'] != right['reference_surface']):
                raise ValueError('Wrong paired teacher')
            counts[f'{split}_{left["target"]["action"]}'] += 1
        seen_split |= current
        probe_ids = None
        for mode in codecs:
            probe = list(rows(directory / mode / 'validation-generation.jsonl'))
            ids = [r['id'] for r in probe]
            if probe_ids is not None and ids != probe_ids:
                raise ValueError('Generated validation cases differ')
            probe_ids = ids
            canonical = {r['id']: r for r in paired[mode]} if split == 'val' else None
            if canonical is not None and any(r != canonical.get(r['id']) for r in probe):
                raise ValueError('Validation cases are not from held-out partition')
    return {'status': 'PASS', 'manifest_sha256': digest(directory / 'manifest.json'), 'counts': dict(counts),
            'checks': ['same IDs and prompts', 'verified paired targets', 'source/reading/surface split isolation',
                       'current evaluation excluded', 'generation subset identity', 'lossless length and target round trip'],
            'code_sha256': digest(__file__)}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=Path)
    print(json.dumps(audit(p.parse_args().directory), indent=2))
