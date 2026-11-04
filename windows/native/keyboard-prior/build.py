"""Build the offline CPU probe against the exact installed b4846 runtime ABI.

Fetches five pinned official headers into a NEW artifact directory. Does not
install the executable or change the IME. Requires clang++ with MSVC libraries.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import urllib.request
from pathlib import Path

HEADERS = {
    'llama.h': ('include/llama.h', '09a745137e1e5de07ab2c9f05e8ff7daf9d3a74f0d996f45c3897d7cf9876ee3'),
    'ggml.h': ('ggml/include/ggml.h', '4bd5a73af85d5ac7a43fb9e8c30db05d0ca21518216d64fc6d64e33ec855a798'),
    'ggml-cpu.h': ('ggml/include/ggml-cpu.h', '3f43c5f58ff276bf1c3dc16609c5433aeb0c5333427a538cb8d1555c0ce5619e'),
    'ggml-backend.h': ('ggml/include/ggml-backend.h', '760b374e1e1d80a854b4d5385006b36d82e544e43281b5a070d6c895dc301252'),
    'ggml-alloc.h': ('ggml/include/ggml-alloc.h', '8344c5eb56ce9d0e0d45e1ef59846e0dcd0e4f84eb9bb68a6126252247bc0338'),
}


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
    if '4846 (10131b23)' not in version_text:
        raise RuntimeError('Unsupported runtime ABI; expected b4846 (10131b23)')
    include = args.output/'include'
    include.mkdir(parents=True)
    provenance = {'runtime_version': version_text, 'headers': {}}
    for name, (relative, expected) in HEADERS.items():
        url = 'https://raw.githubusercontent.com/ggml-org/llama.cpp/10131b23/'+relative
        with urllib.request.urlopen(url, timeout=30) as response:
            content = response.read(2**20)
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
               str(source), str(args.runtime/'llama.lib'), '-o', str(executable)]
    subprocess.run(command, check=True, timeout=120, creationflags=subprocess.CREATE_NO_WINDOW)
    if not executable.is_file():
        raise RuntimeError('Compiler returned without producing an executable')
    provenance.update(command=command, source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
                      executable_sha256=hashlib.sha256(executable.read_bytes()).hexdigest())
    (args.output/'build.json').write_text(json.dumps(provenance, indent=2), encoding='utf-8')
    print(executable)


if __name__ == '__main__':
    main()
