# SPDX-License-Identifier: Apache-2.0
"""Identical synthetic key cases across current corrector and unified codecs.

Raw-model diagnostics only. No installed IME state, thresholds, or user history.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import struct

import torch
from transformers import AutoModelForCausalLM

from benchmark_kana_kanji_models import LlamaServerInstance
from build_v4_unified_dataset import rows
from evaluate_v4_unified import hf_generate, UnifiedServer
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec, hira, kata


def build_fixture(out):
    if out.exists():
        raise FileExistsError(out)
    source = Path('artifacts/datasets/v4-unified-romaji-observed-20261029-r3/val.jsonl')
    saved = Path('artifacts/results/v4-unified-standard-bootstrap-corrector-q8-20261029-r5.json')
    codec_dir = Path('artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-repair-bootstrap-20261029-r5/hf')
    codec = V4UnifiedCodec(codec_dir)
    lookup = {r['id']: r for r in rows(source)}
    cases = []
    for item in json.loads(saved.read_text(encoding='utf-8'))['details']:
        row = lookup[item['id']]
        if row['input'] != item['input'] or row['target'] != item['target']:
            raise ValueError('Saved R5 case changed')
        cases.append({'id': row['id'], 'input': row['input'], 'target': row['target'],
                      'observed': codec.observed_reading(row['input']['raw'], row['input']['pending']),
                      'reference_reading': row['reference_reading'],
                      'reference_surface': row['source_text'] if row['category'] != 'complete-prefix-during-edit' else None,
                      'source_group': row['origin_corrector_group'], 'category': row['category'],
                      'raw_provenance': row['raw_provenance']})
    write_json(out, {'schema': 'v4-repair-common-fixture-v1', 'cases': cases,
                     'source_sha256': digest(source), 'saved_cohort_sha256': digest(saved),
                     'codec_sha256': digest(codec_dir / 'v4-unified-codec.json'),
                     'limits': ['Previously inspected synthetic development cases; not real or sealed typing',
                                'Legacy raw was not reverse reconstructed', 'Raw model comparison; external policy excluded']})


class ReadingVerifier:
    """Dictionary-constrained scoring only; reference never reaches model input."""
    def __init__(self, path):
        data = path.read_bytes()
        if data[:4] != b'AZR2':
            raise ValueError('Expected pinned reverse dictionary')
        count = struct.unpack_from('<I', data, 4)[0]
        offsets = struct.unpack_from(f'<{count+1}I', data, 8)
        self.options = {}
        for start, end in zip(offsets, offsets[1:]):
            sl, rl, _ = struct.unpack_from('<HHf', data, start)
            if start + 8 + sl + rl != end:
                raise ValueError('Invalid dictionary entry')
            surface = data[start+8:start+8+sl].decode('utf-8')
            reading = hira(data[start+8+sl:end].decode('utf-8'))
            if surface and reading:
                self.options.setdefault(surface, set()).add(reading)

    def matches(self, surface, reading):
        if not surface or not reading:
            return False
        states = {(0, 0)}
        for i in range(len(surface)):
            for si, ri in list(states):
                if si != i:
                    continue
                literal = hira(surface[i])
                if reading.startswith(literal, ri):
                    states.add((i+1, ri+len(literal)))
                for end in range(i+1, min(len(surface), i+40)+1):
                    for value in self.options.get(surface[i:end], ()):
                        if reading.startswith(value, ri):
                            states.add((end, ri+len(value)))
        return (len(surface), len(reading)) in states


def prompt_row(case, codec):
    inputs = dict(case['input'])
    if codec.metadata.get('repair_input') == 'observed':
        inputs = {'ctx': inputs['ctx'], 'reading': case['observed'], 'pending': inputs['pending']}
    return {'task': 'repair', 'input_method': 'romaji', 'layout': 'JP-QWERTY', 'input': inputs}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--build-fixture', type=Path)
    p.add_argument('--fixture', type=Path)
    p.add_argument('--run', type=Path)
    p.add_argument('--gguf', type=Path)
    p.add_argument('--legacy', action='store_true')
    p.add_argument('--candidates', type=Path, help='Optional fixed finite choices; HF reading model only')
    p.add_argument('--port', type=int, default=8561)
    p.add_argument('--batch-size', type=int, default=16)
    p.add_argument('--device', choices=('cpu', 'cuda'), default='cuda')
    p.add_argument('--out', type=Path)
    a = p.parse_args()
    if a.build_fixture:
        build_fixture(a.build_fixture)
        print('Created fixed raw-key fixture', flush=True)
        return
    if not a.out or not a.fixture or (not a.run and not a.legacy) or (a.legacy and not a.gguf):
        p.error('Select fixture, output and run or legacy GGUF')
    if a.out.exists():
        raise FileExistsError(a.out)
    fixture = json.loads(a.fixture.read_text(encoding='utf-8'))
    cases = fixture['cases']
    if a.candidates:
        if a.gguf or a.legacy:
            p.error('Candidate-trie diagnostic currently supports HF only; no native latency claim')
        frozen_candidates = json.loads(a.candidates.read_text(encoding='utf-8'))
        if frozen_candidates['fixture_sha256'] != digest(a.fixture):
            raise ValueError('Candidate fixture differs')
        lookup = {c['id']: c for c in frozen_candidates['cases']}
        cases = [{**c, 'candidates': lookup.get(c['id'], {}).get('candidates', [])} for c in cases]
    dictionary = Path('ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin')
    verifier = ReadingVerifier(dictionary)
    codec = V4UnifiedCodec(a.run / 'hf') if a.run else None
    if a.candidates and codec.metadata.get('repair_output', 'reading') != 'reading':
        raise ValueError('Reading choices require reading output')
    model = None
    server = (LlamaServerInstance(str(a.gguf.resolve()), a.port, 0, 'cpu') if a.legacy else
              UnifiedServer(str(a.gguf.resolve()), a.port, 0, 'cpu')) if a.gguf else None
    if not server:
        torch.set_num_threads(4)
        model = AutoModelForCausalLM.from_pretrained(a.run / 'hf', local_files_only=True, dtype=torch.float32,
                                                    attn_implementation='sdpa').to(a.device).eval()
    details, summary = [], Counter()
    try:
        if server:
            server.start()
        batch_size = 1 if server else a.batch_size
        active = [c for c in cases if not c['input']['pending']]
        for start in range(0, len(active), batch_size):
            chunk = active[start:start+batch_size]
            if a.legacy:
                case = chunk[0]
                response = server.complete('\uee02' + case['input']['ctx'] + '\uee00' + kata(case['observed']) + '\uee01')
                answer = {'action': 'emit', 'text': response['content']}
                if response.get('stop_type') == 'limit' or not answer['text']:
                    answer = {'action': 'invalid', 'text': answer['text']}
                answers = [answer]
            else:
                prompts = [codec.prompt(prompt_row(c, codec)) for c in chunk]
                if a.candidates:
                    from v4_repair_candidates import constrained_generate, repair_choices
                    generated = constrained_generate(model, prompts, [repair_choices(c, codec) for c in chunk], a.device)
                else:
                    generated = ([server.complete_ids(prompts[0], codec, 96)] if server else
                                 hf_generate(model, prompts, 96, a.device))
                answers = [codec.decode_answer(t, 'repair') for t in generated]
            for case, answer in zip(chunk, answers):
                normal = case['target']['action'] == 'keep'
                surface_output = a.legacy or codec.metadata.get('repair_output') == 'surface'
                def matches(reading):
                    text = answer['text']
                    if not surface_output:
                        return text == reading
                    return verifier.matches(text, reading) or (
                        reading == case['reference_reading'] and case['reference_surface'] is not None and text == case['reference_surface'])
                rescued = answer['action'] == 'emit' and matches(case['reference_reading'])
                preserved = answer['action'] == 'keep' or (answer['action'] == 'emit' and matches(case['observed']))
                prefix = 'normal' if normal else 'typo'
                summary[prefix + '_total'] += 1
                summary[prefix + ('_preserved' if normal else '_reading_matched')] += int(preserved if normal else rescued)
                summary[prefix + '_invalid'] += int(answer['action'] == 'invalid')
                if not normal:
                    summary['typo_wrong_changed'] += int(answer['action'] == 'emit' and not rescued and not preserved)
                details.append({**case, 'answer': answer, 'reading_matched': rescued, 'observed_preserved': preserved})
            if start % 50 == 0:
                print(f'{start+len(chunk)}/{len(active)} generated', flush=True)
    finally:
        if server:
            server.stop()
    summary['pending_host_guard_only'] = len(cases) - len(details)
    write_json(a.out, {'schema': 'v4-repair-common-evaluation-v1', 'summary': dict(summary), 'details': details,
                      'fixture_sha256': digest(a.fixture), 'dictionary_sha256': digest(dictionary),
                      'model_sha256': digest(a.gguf if a.gguf else a.run / 'hf/model.safetensors'),
                      'backend': 'CPU GGUF' if server else 'HF FP32 CUDA', 'legacy_surface_model': a.legacy,
                      'candidate_sha256': digest(a.candidates) if a.candidates else None,
                      'timing_comparable': False, 'evaluation_code_sha256': digest(__file__),
                      'limits': fixture['limits'] + ['Dictionary reading match is an automatic permissive scorer, not semantic correctness',
                         'Surface and reading outputs scored separately from end-to-end conversion',
                         'Pending cases skipped by shared host guard; not a model abstention score',
                         'No installed policy, normal candidate protection, thresholds, or real-IME claim']})
    print(json.dumps(dict(summary)), flush=True)


if __name__ == '__main__':
    main()
