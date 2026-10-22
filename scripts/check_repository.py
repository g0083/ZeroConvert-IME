"""Check publishable source files without importing model/training dependencies."""

import ast
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MAX_FILE_BYTES = 50 * 1024 * 1024
LOCAL_ONLY_PARTS = {'.local', 'scratch', 'artifacts', 'node_modules', '.venv', '.venv_train', '__pycache__', '.gradle', '.build', '.cxx', 'target'}
MODEL_SUFFIXES = {'.gguf', '.safetensors', '.pt', '.pth', '.dll', '.exe', '.obj', '.lib', '.exp'}
JSON_WITH_COMMENTS = {'windows/frontend/tsconfig.json', 'windows/server-swift/.devcontainer/devcontainer.json'}


def main():
    paths = subprocess.check_output(
        ['git', '-C', str(ROOT), 'ls-files', '-z', '--cached', '--others', '--exclude-standard']
    ).decode('utf-8').split('\0')
    errors = []
    checked = 0
    for name in dict.fromkeys(paths):
        if not name:
            continue
        path = ROOT / name
        if not path.is_file():
            continue  # Gitlinks and not-yet-populated external dependencies.
        checked += 1
        if set(Path(name).parts) & LOCAL_ONLY_PARTS or path.suffix.lower() in MODEL_SUFFIXES:
            errors.append(f'Local artifact is included: {name}')
        if path.stat().st_size >= MAX_FILE_BYTES:
            errors.append(f'File is too large for source publishing: {name}')
        try:
            if path.suffix == '.py':
                ast.parse(path.read_text(encoding='utf-8-sig'), filename=name)
            elif path.suffix == '.json' and name.replace('\\', '/') not in JSON_WITH_COMMENTS:
                json.loads(path.read_text(encoding='utf-8-sig'))
        except (SyntaxError, ValueError, UnicodeError) as error:
            errors.append(f'{name}: {error}')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print(f'Validated {checked} source files: syntax/JSON, artifact exclusions and file sizes.')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
