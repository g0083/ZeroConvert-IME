# SPDX-License-Identifier: Apache-2.0
"""Generated validation with normal-input and literal-preservation constraints."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import random

import torch

from build_v4_unified_dataset import rows
from dataset_isolation import evaluation_keys, normalize
from evaluate_v4_unified import hf_generate
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec


def make_probe(dataset, codec_path, out, with_parent_reference=False):
    if out.exists():
        raise FileExistsError(out)
    codec = V4UnifiedCodec(codec_path)
    buckets = defaultdict(list)
    for row in rows(dataset / 'val.jsonl'):
        if len(codec.answer(row)) > 80 or len(codec.prompt(row)) + 96 > 1024:
            continue
        group = row['task'] + '_' + row['target']['action']
        if with_parent_reference and row['task'] == 'convert' and row['category'] == 'v4-anchor':
            group = 'anchor_convert'
        if row['task'] == 'repair' and row['target']['action'] in ('keep', 'emit'):
            group += '_corrector' if row['source_id'] == 'corrector-qwerty-replay' else '_main'
        buckets[group].append(row)
    cases = []
    for key in sorted(buckets):
        count = len(buckets[key]) if key == 'anchor_convert' else 50 if key.startswith('repair_') else 30
        for row in random.Random(1531).sample(buckets[key], min(count, len(buckets[key]))):
            cases.append({**row, 'selection_group': key})
    reserved = evaluation_keys()
    for index, text in enumerate(('M:\\Project\\Assets', 'N:\\Temp\\Output', 'https://example.test/cache?id=71', 'alpha beta')):
        if normalize(text) in reserved:
            continue
        cases.append({'id': f'literal-canary-{index}', 'task': 'convert', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
                      'input': {'ctx': '', 'reading': text}, 'target': {'action': 'emit', 'text': text},
                      'selection_group': 'literal_canary', 'quality_canary': True,
                      'provenance': 'Independent synthetic validation literal, not an evaluation example'})
    parent_hash = None
    if with_parent_reference:
        from transformers import AutoModelForCausalLM, AutoTokenizer
        from v4_unified_contract import legacy_example
        parent = Path('artifacts/checkpoints/standard-v4-nano-transfer-merged')
        parent_hash = digest(parent / 'model.safetensors')
        old = AutoTokenizer.from_pretrained(parent, local_files_only=True)
        device = 'cuda' if torch.cuda.is_available() else 'cpu'
        model = AutoModelForCausalLM.from_pretrained(parent, local_files_only=True, dtype=torch.float32,
                                                    attn_implementation='sdpa').to(device).eval()
        converts = [r for r in cases if r['task'] == 'convert']
        for start in range(0, len(converts), 16):
            chunk = converts[start:start+16]
            prompts = []
            for row in chunk:
                legacy = legacy_example(row, old)
                boundary = next(i for i, v in enumerate(legacy['labels']) if v != -100)
                prompts.append(legacy['input_ids'][:boundary])
            for row, output in zip(chunk, hf_generate(model, prompts, 96, device)):
                answer = codec.decode_answer(output, 'convert')
                row['parent_answer'] = answer
                row['parent_correct'] = answer == row['target']
        del model
    out.write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in cases), encoding='utf-8')
    write_json(out.with_suffix('.manifest.json'), {'codec_sha256': digest(codec_path / 'v4-unified-codec.json'),
                'dataset_manifest_sha256': digest(dataset / 'manifest.json'), 'probe_sha256': digest(out),
                'source': 'Existing validation partitions plus independent literal canaries',
                'parent_reference_hf_sha256': parent_hash,
                'counts': {key: sum(r['selection_group'] == key for r in cases) for key in sorted({r['selection_group'] for r in cases})},
                'code_sha256': digest(__file__), 'limits': 'Development selection only; not a sealed or final test'})


@torch.no_grad()
def assess(model, codec, cases, device):
    model.eval()
    details = []
    surface = codec.metadata.get('repair_output', 'reading') == 'surface'
    verifier = None
    if surface:
        from evaluate_v4_repair_comparison import ReadingVerifier
        verifier = ReadingVerifier(Path('ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin'))
    for start in range(0, len(cases), 16):
        chunk = cases[start:start+16]
        tokens = hf_generate(model, [codec.prompt(r) for r in chunk], 96, device)
        for row, output in zip(chunk, tokens):
            answer = codec.decode_answer(output, row['task'])
            normal_changed = False
            if row['task'] == 'repair' and row['target']['action'] == 'keep':
                same = answer['action'] == 'keep'
                if answer['action'] == 'emit':
                    same = (answer['text'] == row['input']['reading']) if not surface else (
                        answer['text'] == row.get('reference_surface') or verifier.matches(answer['text'], row['input']['reading']))
                normal_changed = answer['action'] == 'emit' and not same
            repair_correct = answer == row['target']
            if surface and row['task'] == 'repair' and row['target']['action'] == 'emit' and answer['action'] == 'emit':
                repair_correct = repair_correct or verifier.matches(answer['text'], row['reference_reading'])
            details.append({'id': row['id'], 'group': row['selection_group'], 'target': row['target'],
                            'answer': answer, 'exact': answer == row['target'], 'normal_changed': normal_changed,
                            'normal_abstained': row['task'] == 'repair' and row['target']['action'] == 'keep' and answer['action'] == 'abstain',
                            'repair_correct': repair_correct, 'task': row['task'],
                            'parent_correct': row.get('parent_correct', False)})
    summary = {'literal_failed': sum(d['group'] == 'literal_canary' and not d['exact'] for d in details),
               'invalid': sum(d['answer']['action'] == 'invalid' for d in details),
               'normal_changed': sum(d['normal_changed'] for d in details),
               'normal_abstained': sum(d['normal_abstained'] for d in details),
               'convert_parent_lost': sum(d['task'] == 'convert' and d['parent_correct'] and not d['exact'] for d in details),
               'repair_correct': sum(d['task'] == 'repair' and d['target']['action'] == 'emit' and d['repair_correct'] for d in details),
               'convert_exact': sum(d['task'] == 'convert' and d['exact'] for d in details),
               'predict_exact': sum(d['task'] == 'predict' and d['exact'] for d in details)}
    key = [summary['literal_failed'], summary['invalid'], summary['normal_changed'], -summary['repair_correct'],
           -summary['convert_exact'], -summary['predict_exact']]
    if any('parent_correct' in r for r in cases):
        key = [summary['literal_failed'], summary['convert_parent_lost'], summary['normal_changed'], summary['invalid'],
               -summary['repair_correct'], -summary['convert_exact'], -summary['predict_exact']]
    model.train()
    return {'summary': summary, 'key': key, 'details': details}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--dataset', type=Path, required=True)
    p.add_argument('--codec', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--with-parent-reference', action='store_true')
    a = p.parse_args()
    make_probe(a.dataset, a.codec, a.out, a.with_parent_reference)
