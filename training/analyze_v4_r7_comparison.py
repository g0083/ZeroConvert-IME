"""Verify matched R7 experiments and summarize development results without adoption."""
import json
from pathlib import Path
from origin_contract import digest, write_json


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def main():
    out = Path('artifacts/results/v4-unified-r7-comparison-20261030-r2.json')
    if out.exists():
        raise FileExistsError(out)
    root = Path('artifacts/checkpoints')
    runs = {name: root / f'zerocon-standard-qwerty-unified-20261030-{name}' for name in ('r7a', 'r7b')}
    manifests = {name: read(path / 'run-manifest.json') for name, path in runs.items()}
    a, b = manifests.values()
    for field in ('dataset_manifest_sha256', 'parent_hashes', 'student_hashes', 'codec_hashes', 'code_hashes', 'task_weights'):
        assert a[field] == b[field], field
    changed = [k for k in a['arguments'] if a['arguments'][k] != b['arguments'][k]]
    assert set(changed) == {'out', 'task_gradients'}
    assert read(runs['r7a'] / 'initial-generated-validation.json') == read(runs['r7b'] / 'initial-generated-validation.json')
    result = {'status': 'measured development comparison; not installed', 'matched_inputs_and_code': True,
              'different_arguments': changed, 'runs': {}, 'ime_applied': False, 'automatic_adoption': False}
    token_counts = []
    for label, run in runs.items():
        metrics = read(run / 'metrics.json')
        sampler = read(f'artifacts/results/v4-sampling-20261030-{label}.json')
        hf = read(f'artifacts/results/v4-unified-hf-20261030-{label}.json')
        common = read(f'artifacts/results/v4-repair-common-hf-20261030-{label}.json')
        assert metrics['status'] == 'training_completed' and sampler['status'] == 'PASS'
        assert metrics['hf_hashes']['model.safetensors'] == hf['model_sha256'] == common['model_sha256']
        assert digest(run / 'hf/model.safetensors') == hf['model_sha256']
        assert sampler['counts']['processed_tokens'] == metrics['processed_tokens']
        token_counts.append(metrics['processed_tokens'])
        baseline_common = read('artifacts/results/v4-repair-common-consolidated-hf-20261029-r6d.json')
        assert common['fixture_sha256'] == baseline_common['fixture_sha256']
        history = [json.loads(l) for l in (run / 'task-gradient-history.jsonl').read_text(encoding='utf-8').splitlines()]
        assert len(history) == metrics['steps']
        result['runs'][label] = {'steps': metrics['steps'], 'processed_tokens': metrics['processed_tokens'],
            'selected_step': metrics['best']['step'], 'generated_validation': metrics['best']['generated'],
            'hf_summary': hf['summary'], 'common_summary': common['summary'],
            'negative_gradient_steps': sum(r['task_dot'] < 0 for r in history),
            'projected_steps': sum(r['projection_coefficient'] < 0 for r in history),
            'model_sha256': hf['model_sha256'], 'run_manifest_sha256': digest(run / 'run-manifest.json'),
            'development_conversion_gate': all(hf['summary'][g]['perfect_legacy_scorer'] >= 459 for g in ('legacy_480', 'tagged_480'))}
    assert token_counts[0] == token_counts[1]
    result['total_processed_tokens'] = sum(token_counts)
    task_diagnostics = {name: read(f'artifacts/results/v4-task-diagnostics-20261030-{name}.json') for name in ('r6d', 'r7a', 'r7b')}
    def case_key(d):
        return d['group'], str(d['id']), json.dumps(d['input'], sort_keys=True), json.dumps(d['target'], sort_keys=True)
    reference = {case_key(d) for d in task_diagnostics['r6d']['details']}
    assert all({case_key(d) for d in v['details']} == reference for v in task_diagnostics.values())
    result['same_task_diagnostic_cases_verified'] = len(reference)
    result['task_diagnostics'] = {k: v['summary'] for k,v in task_diagnostics.items()}
    result['limits'] = ['Only first bounded comparison unit; remaining training plan conditional',
        'Common raw cohort is synthetic and uses permissive reading verification',
        'No new sealed normal set, fixed-policy300 evaluation, quantization, comparable CPU latency or actual IME verification',
        'HF regression files cover fixed conversion only; separate task diagnostics use the same appended development validation for all3 models']
    result['analysis_code_sha256'] = digest(__file__)
    write_json(out, result)
    print(json.dumps({k: {'steps': v['steps'], 'selected': v['selected_step'],
        'legacy': v['hf_summary']['legacy_480']['perfect_legacy_scorer'],
        'compact': v['hf_summary']['tagged_480']['perfect_legacy_scorer'],
        'common': v['common_summary']} for k,v in result['runs'].items()}, indent=2))


if __name__ == '__main__':
    main()
