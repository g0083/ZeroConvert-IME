"""Calibrate only on dev, freeze the policy, then apply unchanged to held-out data."""
import argparse
import json
import math
from pathlib import Path

from dataset_isolation import load_jsonl, sha256


def ranking(row, policy):
    by_reading = {}
    for candidate in row['candidates']:
        score = candidate['dictionary'] * policy['dictionary_weight']
        if policy['lm_weight']:
            if 'lm_sum' not in candidate or not math.isfinite(candidate['lm_sum']):
                raise ValueError('Neural policy requires finite LM scores for every candidate')
            score += candidate['lm_sum'] * policy['lm_weight']
        reading = candidate['reading']
        if reading not in by_reading or score > by_reading[reading][0]:
            by_reading[reading] = (score, candidate)
    baseline = by_reading[row['observed_reading']][0]
    return [c for score, c in sorted(by_reading.values(), key=lambda x: (-x[0], x[1]['reading']))
            if c['reading'] != row['observed_reading'] and score-baseline >= policy['margin']][:3]


def metrics(rows, policy):
    stats = {}
    for kind in ('a_s', 'other_adjacent', 'clean', 'a_to_s', 's_to_a'):
        subset = [r for r in rows if r['kind'] == kind or
                  (kind in ('a_to_s', 's_to_a') and r['kind'] == 'a_s' and
                   r['edit'][1:] == (['a', 's'] if kind == 'a_to_s' else ['s', 'a']))]
        counts = {'n': len(subset), 'suggested': 0, 'top1_correct': 0, 'top3_correct': 0,
                  'wrong_top1': 0, 'candidate_coverage': 0}
        for row in subset:
            visible = ranking(row, policy)
            expected = row['expected_reading']
            counts['candidate_coverage'] += any(c['reading'] == expected for c in row['candidates'])
            if visible:
                counts['suggested'] += 1
                counts['top1_correct'] += visible[0]['reading'] == expected
                counts['top3_correct'] += any(c['reading'] == expected for c in visible)
                counts['wrong_top1'] += visible[0]['reading'] != expected
        stats[kind] = counts
    return stats


def calibrate(rows, neural):
    clean = sum(r['kind'] == 'clean' for r in rows)
    if not clean or not all(any(r['kind'] == k for r in rows) for k in ('a_s', 'other_adjacent')):
        raise ValueError('Calibration needs normal and both typo strata')
    choices = []
    # Freeze objective before opening the held-out test: maximize equally weighted
    # typo top1 under <= 1% normal false suggestions. Also retain stricter 0% dev.
    policies = []
    for lm_weight in ([0., .125, .25, .5, 1., 2., 4.] if neural else [0.]):
        for margin in [x*.5 for x in range(0, 81)]:
            policy = {'dictionary_weight': 1., 'lm_weight': lm_weight, 'margin': margin}
            stat = metrics(rows, policy)
            correct = sum(stat[k]['top1_correct'] for k in ('a_s', 'other_adjacent'))
            wrong = sum(stat[k]['wrong_top1'] for k in ('a_s', 'other_adjacent'))
            false = stat['clean']['suggested']
            policies.append((correct, wrong, false, policy, stat))
    for budget in (0., .01):
        feasible = [p for p in policies if p[2] <= clean*budget]
        if not feasible:
            raise ValueError('No policy meets normal-error budget')
        best = max(feasible, key=lambda p: (p[0], -p[2], -p[1], -p[3]['lm_weight'], -p[3]['margin']))
        choices.append({'clean_dev_budget': budget, 'policy': best[3], 'dev_metrics': best[4]})
    return choices


def prevalence_sensitivity(stats):
    # Equal a/s and other-adjacent mixture is a scenario, not an empirical prior.
    kinds = ('a_s', 'other_adjacent')
    recall = sum(stats[k]['top1_correct']/stats[k]['n'] for k in kinds)/2
    shown = sum(stats[k]['suggested']/stats[k]['n'] for k in kinds)/2
    false = stats['clean']['suggested']/stats['clean']['n']
    return [{'typo_prevalence': p, 'top1_precision': (p*recall/(p*shown+(1-p)*false))
             if p*shown+(1-p)*false else None} for p in (.01, .05, .1, .2)]


def mix_sensitivity(stats):
    """A scenario grid per composing phrase, not observed per-keystroke rates."""
    result = []
    false = stats['clean']['suggested']/stats['clean']['n']
    for share in (0., .25, .5, .75, 1.):
        recall = sum(weight*stats[k]['top1_correct']/stats[k]['n']
                     for k, weight in [('a_s', share), ('other_adjacent', 1-share)])
        shown = sum(weight*stats[k]['suggested']/stats[k]['n']
                    for k, weight in [('a_s', share), ('other_adjacent', 1-share)])
        for prevalence in (.01, .05, .1):
            denominator = prevalence*shown + (1-prevalence)*false
            result.append({'a_s_share_among_typos': share, 'typo_phrase_prevalence': prevalence,
                           'typo_top1_recall': recall,
                           'top1_precision': prevalence*recall/denominator if denominator else None})
    return result


def percentile(values, p):
    values = sorted(values)
    return values[min(len(values)-1, math.ceil(p*len(values))-1)]


def provenance(path, neural):
    meta = json.loads(path.with_suffix('.metadata.json').read_text(encoding='utf-8'))
    expected_hash = meta.get('result_sha256', meta.get('candidate_sha256'))
    if expected_hash != sha256(path):
        raise ValueError('Result/candidate hash mismatch')
    signature = {'generation': meta['generation']}
    if neural:
        if 'model_sha256' in meta:
            signature['scoring'] = {k: meta[k] for k in ('model_sha256', 'tokenizer_hashes', 'mode',
                                                       'executable_sha256', 'runtime_hashes', 'threads', 'device')}
        else:
            signature['scoring'] = {k: meta[k] for k in ('model_files', 'mode', 'device')}
    return signature


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dev', type=Path)
    parser.add_argument('--test', type=Path)
    parser.add_argument('--policy', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--dictionary-only', action='store_true')
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    if args.dev:
        if args.test or args.policy:
            parser.error('Calibrate and test in separate invocations')
        rows = load_jsonl(args.dev)
        result = {'dev_path': str(args.dev), 'dev_sha256': sha256(args.dev),
                  'provenance': provenance(args.dev, not args.dictionary_only),
                  'dev_groups': sorted({r['split_group'] for r in rows}),
                  'policies': calibrate(rows, not args.dictionary_only)}
    else:
        if not args.test or not args.policy:
            parser.error('Need --dev or --test with --policy')
        rows = load_jsonl(args.test)
        frozen = json.loads(args.policy.read_text(encoding='utf-8'))
        neural = 'scoring' in frozen['provenance']
        if provenance(args.test, neural) != frozen['provenance']:
            raise ValueError('Frozen candidate/model/scoring provenance does not match evaluation')
        if set(frozen['dev_groups']) & {r['split_group'] for r in rows}:
            raise ValueError('Calibration and evaluation source groups overlap')
        result = {'test_path': str(args.test), 'test_sha256': sha256(args.test),
                  'policy_path': str(args.policy), 'policy_sha256': sha256(args.policy), 'policies': []}
        for choice in frozen['policies']:
            stats = metrics(rows, choice['policy'])
            result['policies'].append({'clean_dev_budget': choice['clean_dev_budget'], 'policy': choice['policy'],
                                      'test_metrics': stats, 'prevalence_scenarios': prevalence_sensitivity(stats),
                                      'mix_scenarios': mix_sensitivity(stats)})
        result['timings'] = {field: {'p50': percentile([r[field] for r in rows], .5),
                                    'p95': percentile([r[field] for r in rows], .95)}
                             for field in ('generation_ms', 'score_ms') if field in rows[0]}
        if 'score_ms' in rows[0]:
            combined = [r['generation_ms']+r['score_ms'] for r in rows]
            result['timings']['generation_plus_score_ms'] = {'p50': percentile(combined, .5),
                                                            'p95': percentile(combined, .95)}
        result['metric_scope'] = 'Exact source-romaji reading restoration; not final kanji or live IME success'
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({k:v for k,v in result.items() if k != 'dev_groups'}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
