"""Summarize all eight matched correction conditions and paired changes."""
import argparse
from collections import Counter
import json
from pathlib import Path


def accepted(row, output):
    return output is not None and output in [row['expected'], *(row.get('acceptable') or [])]


def hira(text):
    return ''.join(chr(ord(c)-96) if 'ァ' <= c <= 'ヶ' else c for c in text)


def summarize(report):
    extra = Counter()
    for row in report['cases']:
        calls = row['calls']
        raw = next((c for c in calls if c.get('stage') == 'corrector'), None)
        converted = next((c for c in calls if c.get('stage') == 'reading_conversion'), None)
        extra['invoked_cases'] += bool(raw)
        extra['conversion_calls'] += bool(converted)
        if raw:
            response = raw['response'] or {}
            output = response.get('content', '').strip()
            extra['output_limit_calls'] += bool(response.get('stopped_limit') or response.get('stop_type') == 'limit')
            if report.get('reading_stage'):
                if row['is_clean'] is not True:
                    extra['raw_reading_unchanged_typos'] += hira(output) == hira(row['input'])
                    extra['raw_reading_invalid_format_typos'] += not bool(output) or any(
                        not ('ぁ' <= c <= 'ゖ' or c in 'ー、。！？!?・') for c in hira(output))
                gold = row.get('expected_reading')
                if row['is_clean'] is not True and isinstance(gold, str) and gold and all('ぁ' <= c <= 'ゖ' or c in 'ー、。！？!?・' for c in hira(gold)):
                    extra['reading_scored_calls'] += 1
                    exact = hira(output) == hira(gold)
                    extra['raw_reading_exact'] += exact
                    if exact and not accepted(row, row['correction']):
                        extra['exact_reading_without_final_correct'] += 1
                    if exact and converted:
                        second = converted['response'] or {}
                        surface_ok = accepted(row, second.get('content', '').strip())
                        extra['exact_reading_conversion_surface_correct'] += surface_ok
                        extra['exact_reading_conversion_surface_wrong'] += not surface_ok
                        extra['correct_conversion_rejected'] += surface_ok and not accepted(row, row['correction'])
                extra['reading_requests_without_conversion'] += not bool(converted)
            else:
                extra['raw_surface_exact'] += accepted(row, output)
    return {**report['summary'], **extra}


def compare(a, b):
    if len(a['cases']) != len(b['cases']):
        raise ValueError('Case counts differ')
    changes, types = [], {}
    for x, y in zip(a['cases'], b['cases']):
        if (x['id'], x['input'], x['expected'], x['baseline']) != (y['id'], y['input'], y['expected'], y['baseline']):
            raise ValueError('Case order or baseline changed')
        if x['is_clean'] is True:
            continue
        ca, cb = accepted(x,x['correction']), accepted(y,y['correction'])
        stats = types.setdefault(str(x['typo_type']), Counter())
        stats['total'] += 1
        stats['before_correct'] += ca
        stats['after_correct'] += cb
        if ca != cb:
            changes.append({'id':x['id'],'input':x['input'],'expected':x['expected'],
                            'before':x['correction'],'after':y['correction'],'gain':cb})
    return {'gains':sum(c['gain'] for c in changes), 'losses':sum(not c['gain'] for c in changes),
            'changes':changes,'by_type':types}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    reports = {p.stem:json.loads(p.read_text(encoding='utf-8')) for p in args.directory.glob('*.json')
               if p.stem.endswith(('-300','-35'))}
    if len(reports) != 16:
        raise ValueError(f'Expected 16 completed result files, got {len(reports)}')
    fingerprints = {(r['identity']['runner_sha256'], r['identity']['runtime_sha256'],
                     r['identity']['normal']['sha256'],r['identity']['reverse_dictionary_sha256']) for r in reports.values()}
    if len(fingerprints)!=1:
        raise ValueError('Evaluation runtime/model identities differ')
    for count in ('300','35'):
        reference = reports[f'xsmall-narrow-surface-{count}']['cases']
        for name, report in reports.items():
            if name.endswith(f'-{count}'):
                assert [(r['id'],r['input'],r['baseline']) for r in report['cases']] == [(r['id'],r['input'],r['baseline']) for r in reference], name
    result = {'runs':{name:summarize(r) for name,r in reports.items()}, 'paired':{}}
    for size in ('xsmall','small'):
        for count in ('300','35'):
            prefix=f'{size}-'
            for label,before,after in [('diversity','narrow-surface','diverse-surface'),
                                       ('reading','narrow-surface','narrow-reading'),
                                       ('combined','narrow-surface','diverse-reading'),
                                       ('diversity_with_reading','narrow-reading','diverse-reading'),
                                       ('reading_with_diversity','diverse-surface','diverse-reading')]:
                result['paired'][f'{size}-{label}-{count}']=compare(reports[f'{prefix}{before}-{count}'],reports[f'{prefix}{after}-{count}'])
    args.out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result['runs'],ensure_ascii=False,indent=2))


if __name__ == '__main__':
    main()
