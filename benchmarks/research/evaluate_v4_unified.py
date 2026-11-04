# SPDX-License-Identifier: Apache-2.0
"""Generation diagnostics for owned v4 unified HF/GGUF, legacy/tagged 480 regression."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import random
import time
import urllib.request

import torch
from transformers import AutoModelForCausalLM

from benchmark_kana_kanji_models import LlamaServerInstance, load_cases, evaluate_output
from build_v4_unified_dataset import rows
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec, legacy_example, hira


class UnifiedServer(LlamaServerInstance):
    def complete_ids(self, ids, codec, max_tokens):
        data = json.dumps({'prompt': ids, 'n_predict': max_tokens, 'temperature': 0, 'seed': 42,
                           'cache_prompt': False, 'return_tokens': True, 'repeat_penalty': 1.0}).encode()
        request = urllib.request.Request(f'http://127.0.0.1:{self.port}/completion', data=data,
                                         headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=60) as response:
            result = json.load(response)
        tokens = result['tokens']
        ended = result.get('stop_type') == 'eos' or result.get('stopped_eos', False)
        if ended and (not tokens or tokens[-1] != codec.eos_id):
            tokens.append(codec.eos_id)
        return tokens


@torch.no_grad()
def hf_generate(model, prompts, max_tokens, device):
    width = max(map(len, prompts))
    ids = torch.full((len(prompts), width), 1, dtype=torch.long, device=device)
    mask = torch.zeros_like(ids)
    for i, p in enumerate(prompts):
        ids[i, -len(p):] = torch.tensor(p, device=device)
        mask[i, -len(p):] = 1
    if width + max_tokens > model.config.n_positions:
        raise ValueError('Generation exceeds learned position limit')
    result = model.generate(input_ids=ids, attention_mask=mask, max_new_tokens=max_tokens,
                            do_sample=False, pad_token_id=1, eos_token_id=3, use_cache=True)
    outputs = []
    for generated in result[:, width:].tolist():
        if 3 in generated:
            generated = generated[:generated.index(3)+1]
        outputs.append(generated)
    return outputs


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--dataset', type=Path)
    p.add_argument('--gguf', type=Path)
    p.add_argument('--regression', action='store_true')
    p.add_argument('--legacy-only', action='store_true')
    p.add_argument('--per-group', type=int, default=80)
    p.add_argument('--tasks', nargs='+', choices=('convert', 'repair', 'predict'), default=['convert', 'repair', 'predict'])
    p.add_argument('--source-id')
    p.add_argument('--batch-size', type=int, default=16)
    p.add_argument('--device', choices=('cpu', 'cuda'), default='cuda')
    p.add_argument('--port', type=int, default=8557)
    p.add_argument('--concurrent-training', action='store_true')
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    codec = V4UnifiedCodec(args.run / 'hf')
    surface_repair = codec.metadata.get('repair_output', 'reading') == 'surface'
    verifier = None
    if surface_repair:
        from evaluate_v4_repair_comparison import ReadingVerifier
        verifier = ReadingVerifier(Path('ZeroConvertIME-windows/Dictionary/Reverse/reverse-v2.bin'))
    from transformers import AutoTokenizer
    run_manifest = json.loads((args.run / 'run-manifest.json').read_text(encoding='utf-8'))
    old = AutoTokenizer.from_pretrained(run_manifest['arguments']['parent'], local_files_only=True)
    cases = []
    if args.regression:
        for filename in ('kana_kanji_llm_testset_5x.jsonl', 'kana_kanji_hard_context_testset.jsonl'):
            for case in load_cases(str(Path('data') / filename)):
                row = {'task': 'convert', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
                    'input': {'ctx': '' if case['left_context'] == 'なし' else case['left_context'],
                              'reading': hira(case['target'])},
                    'target': {'action': 'emit', 'text': case['expected']}}
                legacy = legacy_example(row, old)
                boundary = next(i for i,v in enumerate(legacy['labels']) if v != -100)
                cases.append({'group': 'legacy_480', 'row': row, 'prompt': legacy['input_ids'][:boundary], 'case': case})
                if not args.legacy_only:
                    cases.append({'group': 'tagged_480', 'row': row, 'prompt': codec.prompt(row), 'case': case})
    if args.dataset:
        buckets = defaultdict(list)
        for row in rows(args.dataset / 'val.jsonl'):
            if row['task'] not in args.tasks or (args.source_id and row['source_id'] != args.source_id):
                continue
            group = row['task'] + '_' + row['target']['action']
            if row['task'] == 'convert' and row['category'] == 'v4-anchor':
                group = 'anchor_convert'
            buckets[group].append(row)
        for group, examples in buckets.items():
            chosen = random.Random(577).sample(examples, min(args.per_group, len(examples)))
            for row in chosen:
                if row['task'] == 'repair' and codec.metadata.get('repair_input') == 'observed' and 'raw' in row['input']:
                    original = row['input']
                    row = {**row, 'input': {'ctx': original['ctx'],
                        'reading': codec.observed_reading(original['raw'], original['pending']), 'pending': original['pending']}}
                cases.append({'group': group, 'row': row, 'prompt': codec.prompt(row)})
    if not cases:
        p.error('Select --dataset or --regression')
    server = None
    if args.gguf:
        server = UnifiedServer(str(args.gguf.resolve()), args.port, 0, 'cpu')
        contract_path = Path('artifacts/results/origin-comparison-contract-20261029-r2.json')
        contract = json.loads(contract_path.read_text(encoding='utf-8'))
        if digest(server.server_exe) != contract['runtime']['sha256']:
            raise ValueError('Fixed CPU runtime differs')
        model = None
    else:
        torch.set_num_threads(4)
        model = AutoModelForCausalLM.from_pretrained(args.run / 'hf', local_files_only=True,
            dtype=torch.float32, attn_implementation='sdpa').to(args.device).eval()
    details, timings = [], []
    try:
        if server:
            server.start()
            warmup = codec.prompt({'task': 'convert', 'input_method': 'romaji', 'layout': 'JP-QWERTY',
                                   'input': {'ctx': '', 'reading': 'きょうはいいてんきです'}})
            for _ in range(3):
                server.complete_ids(warmup, codec, 96)
        batch_size = 1 if server else args.batch_size
        # Length sorting is undone in output through explicit case records.
        cases.sort(key=lambda e: len(e['prompt']))
        for start in range(0, len(cases), batch_size):
            chunk = cases[start:start+batch_size]
            begun = time.perf_counter()
            generated = ([server.complete_ids(chunk[0]['prompt'], codec, 96)] if server else
                         hf_generate(model, [c['prompt'] for c in chunk], 96, args.device))
            elapsed = time.perf_counter() - begun
            timings.append({'batch_cases': len(chunk), 'seconds': elapsed})
            for case, tokens in zip(chunk, generated):
                answer = codec.decode_answer(tokens, case['row']['task'])
                row = case['row']
                exact = answer == row['target']
                score, reason = evaluate_output(answer['text'], case['case']) if 'case' in case and answer['action'] == 'emit' else (0, '')
                detail = {'group': case['group'], 'id': row.get('id', case.get('case', {}).get('id')),
                    'input': row['input'], 'target': row['target'], 'answer': answer, 'exact': exact,
                    'tokens': tokens, 'score': score, 'reason': reason,
                    'reference_reading': row.get('reference_reading'),
                    'reference_surface': row.get('reference_surface'),
                    'source_document': row.get('document_id')}
                if row['task'] == 'repair':
                    observed = (row['input'].get('reading') if codec.metadata.get('repair_input') == 'observed'
                                else codec.observed_reading(row['input']['raw'], row['input']['pending'])
                                if codec.observation_mapping is not None else row.get('reference_reading'))
                    text = answer['text']
                    ref = row.get('reference_reading', '')
                    ref_surface = row.get('reference_surface')
                    detail['observed_reading'] = observed
                    detail['reading_matched'] = answer['action'] == 'emit' and (
                        (text == ref) if not surface_repair else
                        ((bool(ref_surface) and text == ref_surface) or verifier.matches(text, ref)))
                    detail['observed_preserved'] = answer['action'] == 'keep' or (answer['action'] == 'emit' and
                        ((text == observed) if not surface_repair else verifier.matches(text, observed)))
                if batch_size == 1:
                    detail['latency_ms'] = elapsed*1000
                details.append(detail)
            if start % max(1, batch_size*10) == 0:
                print(f'{min(start+batch_size,len(cases))}/{len(cases)} generated', flush=True)
    finally:
        if server:
            server.stop()
    summary = {}
    for group in sorted({d['group'] for d in details}):
        selected = [d for d in details if d['group'] == group]
        summary[group] = {'total': len(selected), 'exact': sum(d['exact'] for d in selected),
            'invalid': sum(d['answer']['action'] == 'invalid' for d in selected),
            'emit': sum(d['answer']['action'] == 'emit' for d in selected),
            'perfect_legacy_scorer': sum(d['score'] == 2 for d in selected) if '480' in group else None}
        if group == 'repair_keep':
            summary[group]['normal_changed'] = sum(d['answer']['action'] == 'emit' and not d['observed_preserved'] for d in selected)
        if group == 'repair_emit':
            summary[group]['reading_rescued'] = sum(d['reading_matched'] for d in selected)
        if group == 'predict_emit':
            punctuation = [d for d in selected if not any(c.isalnum() for c in d['target']['text'])]
            longer = [d for d in selected if sum(c.isalnum() for c in d['target']['text']) >= 4]
            summary[group]['punctuation_only'] = {'total': len(punctuation), 'exact': sum(d['exact'] for d in punctuation)}
            summary[group]['four_or_more_alphanumeric_characters'] = {'total': len(longer), 'exact': sum(d['exact'] for d in longer)}
        if server:
            latencies = sorted(d['latency_ms'] for d in selected)
            import math
            summary[group]['p95_latency_ms'] = latencies[max(0, math.ceil(.95*len(latencies))-1)]
    result = {'schema': 'v4-unified-generation-v1', 'summary': summary, 'details': details,
        'run_manifest_sha256': digest(args.run / 'run-manifest.json'),
        'model_sha256': digest(args.gguf if args.gguf else args.run / 'hf/model.safetensors'),
        'backend': 'fixed CPU GGUF' if args.gguf else 'HF FP32',
        'repair_output': codec.metadata.get('repair_output', 'reading'),
        'repair_reading_scorer': 'permissive dictionary path or verified reference surface; not semantic correctness' if surface_repair else 'exact emitted reading',
        'generation': {'max_new_tokens': 96, 'temperature': 0, 'batch_size': args.batch_size if not args.gguf else 1},
        'batch_timings': timings, 'evaluation_code_sha256': digest(__file__),
        'concurrent_training': args.concurrent_training,
        'timing_comparable_to_v4': False,
        'timing_comparison_limit': 'Integer prompt/return_tokens differs from frozen v4 string-prompt/stop-string transport; no direct speed claim',
        'limits': ['Development diagnostics, automatic/synthetic labels; no sealed or real-IME claim',
                   'HF batch wall time is not single-input latency',
                   'No threshold selection, automatic adoption or user-state mutations'],
        'full_goal_status': 'unproven'}
    write_json(args.out, result)
    print(json.dumps(summary, indent=2), flush=True)


if __name__ == '__main__':
    main()
