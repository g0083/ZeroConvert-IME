"""Build the offline CPU probe against the pinned b12125 runtime ABI.

Copies verified pinned official headers into a NEW artifact directory. Does not
install the executable or change the IME. Requires clang++ with MSVC libraries.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--name', default='keyboard-prior.exe')
    parser.add_argument('--runtime', type=Path, default=Path('artifacts/runtime/windows/llama_cpu'))
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    compiler = shutil.which('clang++')
    if not compiler:
        raise RuntimeError('clang++ is required')
    version = subprocess.run([str((args.runtime/'llama-server.exe').resolve()), '--version'],
                             check=True, capture_output=True, text=True,
                             creationflags=subprocess.CREATE_NO_WINDOW, timeout=15)
    version_text = version.stdout + version.stderr
    if '12125' not in version_text and '7d516e2' not in version_text:
        raise RuntimeError('Unsupported runtime ABI; expected b12125 (7d516e2)')
    include = args.output/'include'
    include.mkdir(parents=True)
    provenance = {'runtime_version': version_text, 'headers': {}}
    # The runtime generator verifies headers against pinned upstream hashes.
    from importlib.util import spec_from_file_location, module_from_spec
    spec = spec_from_file_location('modern_llama', Path(__file__).resolve().parents[2] / 'scripts/prepare-modern-llama.py')
    modern = module_from_spec(spec)
    spec.loader.exec_module(modern)
    for name, (relative, expected) in modern.HEADERS.items():
        url = 'https://raw.githubusercontent.com/ggml-org/llama.cpp/7d516e2/'+relative
        content = (args.runtime.parent / 'include' / name).read_bytes()
        actual = hashlib.sha256(content).hexdigest()
        if actual != expected:
            raise ValueError('Pinned header hash mismatch: '+name)
        (include/name).write_bytes(content)
        provenance['headers'][name] = {'url': url, 'sha256': actual}
    source = Path(__file__).with_name('main.cpp')
    if args.name not in ('keyboard-prior.exe', 'keyboard_prior_score_masked.exe'):
        raise ValueError('Unsupported executable name')
    executable = args.output/args.name
    command = [compiler, '-std=c++17', '-O2', '-Wno-deprecated-declarations', '-DLLAMA_SHARED', '-I', str(include),
               str(source), str(args.runtime/'llama.lib'), str(args.runtime/'ggml.lib'), '-o', str(executable)]
    subprocess.run(command, check=True, timeout=120, creationflags=subprocess.CREATE_NO_WINDOW)
    if not executable.is_file():
        raise RuntimeError('Compiler returned without producing an executable')
    provenance.update(command=command, source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                      executable_sha256=hashlib.sha256(executable.read_bytes()).hexdigest())
    (args.output/'build.json').write_text(json.dumps(provenance, indent=2), encoding='utf-8')
    print(executable)


if __name__ == '__main__':
    main()
