"""Compare the actual native key-input protocol against frozen research scores."""
import argparse
import json
import os
import subprocess
import time
from pathlib import Path

from analyze_keyboard_hybrid import ranking
from dataset_isolation import load_jsonl, sha256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('executable', 'model', 'dictionary', 'runtime', 'scores', 'policy', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    rows = load_jsonl(args.scores)
    frozen = json.loads(args.policy.read_text(encoding='utf-8'))['policies'][0]['policy']
    env = dict(os.environ)
    env['PATH'] = str(args.runtime.resolve()) + os.pathsep + env.get('PATH', '')
    started = time.perf_counter()
    process = subprocess.run([str(args.executable.resolve()), '--hybrid', str(args.model.resolve()),
                              str(args.dictionary.resolve())], input=''.join(r['observed']+'\n' for r in rows),
                             env=env, capture_output=True, text=True, encoding='utf-8', timeout=180,
                             creationflags=subprocess.CREATE_NO_WINDOW)
    if process.returncode:
        raise RuntimeError(f'Native worker failed: {process.returncode}: {process.stderr[-1000:]}')
    lines = process.stdout.splitlines()
    if not lines or not lines[0].startswith('ready ') or len(lines) != len(rows)+1:
        raise ValueError('Invalid native response count')
    differences, records = [], []
    for row, line in zip(rows, lines[1:]):
        response = json.loads(line)
        expected = ranking(row, frozen)
        expected = expected[0]['reading'] if expected else None
        record = {'id': row['id'], 'observed': row['observed'], 'input_reading': row['observed_reading'],
                  'expected_proposal': expected, 'actual': response}
        records.append(record)
        if response.get('observed') != row['observed_reading'] or response.get('reading') != expected:
            differences.append(record)
    result = {'rows': len(rows), 'elapsed_seconds': time.perf_counter()-started,
              'hashes': {name: sha256(getattr(args, name)) for name in ('executable', 'model', 'dictionary', 'scores', 'policy')},
              'differences': differences, 'records': records}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps({'rows': len(rows), 'differences': len(differences), 'seconds': result['elapsed_seconds']}), flush=True)
    if differences:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
