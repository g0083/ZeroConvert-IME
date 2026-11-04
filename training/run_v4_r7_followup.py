"""After R7-A, run the predeclared conflict comparison and development evaluations."""
import json
from pathlib import Path
import subprocess
import sys
import time
from origin_contract import write_json, digest


def invoke(script, arguments, name):
    command = [sys.executable, 'scripts/run.py', script, *map(str, arguments)]
    log = Path('artifacts/logs') / name
    if log.exists() and log.stat().st_size:
        raise FileExistsError(log)
    with log.open('a', encoding='utf-8') as stream:
        subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True)


def main():
    run_a = Path('artifacts/checkpoints/zerocon-standard-qwerty-unified-20261030-r7a')
    deadline = time.monotonic() + 3600
    while not (run_a / 'metrics.json').exists():
        if time.monotonic() > deadline:
            raise TimeoutError('R7-A completion not observed within one hour')
        time.sleep(5)
    history = [json.loads(l) for l in (run_a / 'task-gradient-history.jsonl').read_text(encoding='utf-8').splitlines()]
    diagnostic = history[:100]
    rate = sum(r['task_dot'] < 0 for r in diagnostic) / len(diagnostic)
    decision = {'rule': 'At least 20% negative inner products in first100 training steps',
                'observations': len(diagnostic), 'conflict_fraction': rate, 'run_b': rate >= .2,
                'a_manifest_sha256': digest(run_a / 'run-manifest.json')}
    write_json('artifacts/results/v4-r7-conflict-decision-20261030.json', decision)
    print(json.dumps(decision), flush=True)
    runs = [('r7a', run_a)]
    if decision['run_b']:
        run_b = Path('artifacts/checkpoints/zerocon-standard-qwerty-unified-20261030-r7b')
        arguments = json.loads((run_a / 'run-manifest.json').read_text(encoding='utf-8'))['arguments']
        arguments.update(out=str(run_b), task_gradients='project')
        cli = []
        for key, value in arguments.items():
            if value is None or value is False:
                continue
            cli.append('--' + key.replace('_', '-'))
            if value is not True:
                cli.append(str(value))
        invoke('training/train_v4_unified.py', cli, 'v4-unified-training-20261030-r7b.log')
        runs.append(('r7b', run_b))
    for label, run in runs:
        invoke('training/summarize_v4_unified_run.py', ['--run', run, '--out', f'artifacts/results/v4-sampling-20261030-{label}.json'],
               f'v4-sampling-20261030-{label}.log')
        invoke('benchmarks/research/evaluate_v4_unified.py', ['--run', run, '--regression', '--per-group', '100',
               '--out', f'artifacts/results/v4-unified-hf-20261030-{label}.json'], f'v4-unified-hf-20261030-{label}.log')
        invoke('benchmarks/research/evaluate_v4_repair_comparison.py', ['--run', run,
               '--fixture', 'artifacts/results/v4-repair-common-fixture-20261029.json',
               '--out', f'artifacts/results/v4-repair-common-hf-20261030-{label}.json'], f'v4-repair-common-hf-20261030-{label}.log')
        print(f'{label} completed training, sampler replay and HF development evaluation', flush=True)


if __name__ == '__main__':
    main()
