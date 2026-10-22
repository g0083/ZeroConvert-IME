"""Run a research/training script with paths relative to the repository root.

Example: python scripts/run.py training/train_nano_v2.py --help
"""

import argparse
import os
from pathlib import Path
import runpy
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('script', help='Repository-relative Python script path')
    parser.add_argument('args', nargs=argparse.REMAINDER)
    options = parser.parse_args()
    target = (ROOT / options.script).resolve()
    if not target.is_relative_to(ROOT) or not target.is_file() or target.suffix != '.py':
        parser.error('script must be an existing Python file inside this repository')
    os.chdir(ROOT)
    for directory in ('models', 'results', 'logs', 'checkpoints'):
        (ROOT / 'artifacts' / directory).mkdir(parents=True, exist_ok=True)
    for directory in (ROOT / 'benchmarks', ROOT / 'benchmarks/research', ROOT / 'training', ROOT / 'data', target.parent):
        sys.path.insert(0, str(directory))
    sys.argv = [str(target), *options.args]
    runpy.run_path(str(target), run_name='__main__')


if __name__ == '__main__':
    main()
