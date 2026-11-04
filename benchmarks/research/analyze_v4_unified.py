# SPDX-License-Identifier: Apache-2.0
"""Describe paired development changes and quantization differences, no release gate."""
import argparse
from collections import defaultdict, deque
import json
from pathlib import Path

from origin_contract import digest, write_json
from v4_unified_contract import hira


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def key(detail):
    inputs = detail['input']
    return inputs['ctx'], hira(inputs['reading']), detail['target']['text']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--hf', type=Path, required=True)
    p.add_argument('--q8', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    baseline = []
    for name in ('nano_standard_v4_cpu_230.json', 'nano_standard_v4_cpu_250hard.json'):
        baseline += read(Path('artifacts/results') / name)['Standard v4 Q8']['details']
    results = {'schema': 'v4-unified-comparison-v1', 'parent_q8_correct_480': sum(d['score'] == 2 for d in baseline),
               'sources': {str(p): digest(p) for p in (args.hf, args.q8)}, 'comparisons': {}}
    for name, path in (('hf', args.hf), ('q8', args.q8)):
        candidate = read(path)
        result = {'summary': candidate['summary'], 'paired': {}}
        if any('latency_ms' in d for d in candidate['details']):
            result['cpu_reference_p95_ms'] = {}
            for group in candidate['summary']:
                values = sorted(d['latency_ms'] for d in candidate['details'] if d['group'] == group and 'latency_ms' in d)
                if values:
                    result['cpu_reference_p95_ms'][group] = values[min(len(values)-1, int(len(values)*.95))]
            result['p95_definition'] = 'same sorted[int(n*0.95)] definition as the frozen v4 comparator; raw evaluator also reports nearest-rank'
        for group in ('legacy_480', 'tagged_480'):
            queues = defaultdict(deque)
            for d in baseline:
                ctx = '' if d['left_context'] == 'なし' else d['left_context']
                queues[(ctx, hira(d['target']), d['expected'])].append(d)
            gains, losses, unchanged = [], [], 0
            for d in (d for d in candidate['details'] if d['group'] == group):
                previous = queues[key(d)].popleft()
                was, now = previous['score'] == 2, d['score'] == 2
                detail = {'input': d['input'], 'expected': d['target']['text'],
                          'parent_output': previous['output'], 'output': d['answer']['text']}
                if was and not now:
                    losses.append(detail)
                elif now and not was:
                    gains.append(detail)
                else:
                    unchanged += 1
            if sum(map(len, queues.values())):
                raise ValueError('Development case alignment incomplete')
            result['paired'][group] = {'gained': len(gains), 'lost': len(losses), 'unchanged': unchanged,
                                      'gain_cases': gains, 'loss_cases': losses}
        results['comparisons'][name] = result
    first, second = read(args.hf), read(args.q8)
    def identity(d):
        return d['group'], json.dumps(d['input'], ensure_ascii=False, sort_keys=True), json.dumps(d['target'], ensure_ascii=False, sort_keys=True)
    hf_cases = {identity(d): d for d in first['details']}
    matched, changes = 0, []
    for d in second['details']:
        if identity(d) in hf_cases:
            matched += 1
            previous = hf_cases[identity(d)]
            if previous['answer'] != d['answer']:
                changes.append({'group': d['group'], 'input': d['input'], 'hf': previous['answer'], 'q8': d['answer']})
    results['quantization'] = {'matched_cases': matched, 'changed_outputs': len(changes), 'changes': changes,
        'comparison': 'HF FP32 versus F16-to-Q8 pipeline; F16 rounding is included in this difference'}
    results['goal_status'] = 'P2 research artifact; sealed, real typing, calibration, end-to-end repair latency and IME integration unproven'
    results['timing_comparable_to_frozen_v4'] = False
    results['timing_limit'] = 'Integer prompt/return_tokens versus frozen string prompt/stop strings; raw no-concurrent-training flag is insufficient for direct speed comparison'
    results['analysis_code_sha256'] = digest(__file__)
    baseline_latencies = sorted(d['latency_ms'] for d in baseline)
    results['parent_q8_reference_p95_ms'] = baseline_latencies[min(len(baseline_latencies)-1, int(len(baseline_latencies)*.95))]
    write_json(args.out, results)
    print(json.dumps({name: value['summary'] for name,value in results['comparisons'].items()}, indent=2))


if __name__ == '__main__':
    main()
