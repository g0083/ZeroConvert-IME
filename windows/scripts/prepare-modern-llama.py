"""Prepare pinned native dependencies in new, content-addressed artifact folders.

Never edits an installed IME, an existing dependency checkout, or model weights.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import converter_patch

ROOT = Path(__file__).resolve().parents[2]
REVISION = '7d516e2'
CONVERTER = 'c228776b0b869f81ee2a1031ff9dbd679f4b3cd9'
HEADERS = {
    'gguf.h': ('ggml/include/gguf.h', 'e56714aab702e5ce62ee587a409643c08f7e93e8fbb77f48ef7cc85075f96fa4'),
    'llama.h': ('include/llama.h', 'f07ffbabd3fbe9ef21d97b4d4ce8328d9c2a123ccbf5266f04a3146d70ce4f91'),
    'ggml.h': ('ggml/include/ggml.h', '1aef7a5f465b1c19de305dd6f9a0c303f88e485133a94b9a0ee1cce6345c9101'),
    'ggml-cpu.h': ('ggml/include/ggml-cpu.h', '316279e004cdeb8e6ef78599acb602bf79a8abdf897fed9fd1914808c1518c6e'),
    'ggml-backend.h': ('ggml/include/ggml-backend.h', '34464da4b46314c710cd634626578c6f2a6811431d41eddaf5e8aa77082fea2a'),
    'ggml-alloc.h': ('ggml/include/ggml-alloc.h', 'd114067f9099c4a0e2bbc0625c676d4894d9cf0ea27168ee072c7de0e71bb51d'),
    'ggml-opt.h': ('ggml/include/ggml-opt.h', '3586de1bc8a934b5c72339e2b6937b0641e8f149b512231e666f67de0736eea2'),
}
ARCHIVES = {
    'llama_cpu': ('llama-b12125-bin-win-cpu-x64.zip', '968091ccb8c230e0cf7e0eed386310703bebae4a4fd4375f0c57db53958307d8'),
    'llama_vulkan': ('llama-b12125-bin-win-vulkan-x64.zip', '29db801266e784d17fbc9dd7ff9b6a350d2b665bdc5658216f37d91c09918f07'),
}


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def fetch(name, url, expected):
    path = ROOT / 'artifacts/downloads/llama-b12125' / name
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.is_file() or sha(path) != expected:
        partial = path.with_suffix(path.suffix + '.partial')
        with urllib.request.urlopen(url, timeout=60) as response, partial.open('wb') as out:
            shutil.copyfileobj(response, out)
        if sha(partial) != expected:
            raise ValueError('Dependency checksum mismatch: ' + name)
        partial.replace(path)
    return path


def extract(archive, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as package:
        for entry in package.infolist():
            target = (destination / entry.filename).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise ValueError('Archive escapes destination')
        package.extractall(destination)


def import_library(directory, name):
    info = subprocess.check_output(['llvm-readobj', '--coff-exports', str(directory / (name + '.dll'))], text=True)
    exports = re.findall(r'^\s+Name: (\S+)\s*$', info, re.MULTILINE)
    if not exports:
        raise ValueError('No exports: ' + name)
    definition = directory / (name + '.def')
    definition.write_text('LIBRARY ' + name + '.dll\nEXPORTS\n' + '\n'.join(exports) + '\n')
    subprocess.run(['llvm-dlltool', '-m', 'i386:x86-64', '-d', str(definition), '-l', str(directory / (name + '.lib'))], check=True)


def check_converter():
    """Verify the converter patch anchors against the pinned archive.

    Runs without a toolchain, so a converter revision bump fails here instead of
    only in the Android build that stages the same patched sources.
    """
    archive = fetch('converter.zip', f'https://codeload.github.com/batao9/AzooKeyKanaKanjiConverter/zip/{CONVERTER}',
                    'acbf8398b67891f51ba37cdc16ae6f5514798ffea1e7270077915b433a93866e')
    prefix = 'AzooKeyKanaKanjiConverter-' + CONVERTER + '/'
    with zipfile.ZipFile(archive) as package:
        modulemap = package.read(prefix + converter_patch.MODULEMAP_PATH).decode('utf-8')
        context = package.read(prefix + converter_patch.ZENZ_CONTEXT_PATH).decode('utf-8')
    converter_patch.patch_modulemap(modulemap)
    converter_patch.patch_zenz_context(context)
    print(json.dumps({'converter': CONVERTER, 'anchors': 'ok'}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-converter', action='store_true',
                        help='Verify the converter patch anchors and exit without preparing anything.')
    options = parser.parse_args()
    if options.check_converter:
        check_converter()
        return
    key = sha(Path(__file__))[:12]
    runtime = ROOT / ('artifacts/runtime/windows-b12125-' + key)
    converter_root = ROOT / ('artifacts/dependencies/azookey-b12125-' + key)
    receipt = runtime / 'prepared.json'
    if receipt.is_file():
        print(receipt.read_text())
        return
    for backend, (filename, expected) in ARCHIVES.items():
        cached = 'cpu.zip' if backend == 'llama_cpu' else 'vulkan.zip'
        # Reuse the previously verified official CPU archive if present.
        previous = ROOT / 'artifacts/tools/plamo-llama-b12125/runtime.zip'
        target = ROOT / 'artifacts/downloads/llama-b12125/cpu.zip'
        if backend == 'llama_cpu' and previous.is_file() and sha(previous) == expected and not target.exists():
            shutil.copy2(previous, target)
        archive = fetch(cached, 'https://github.com/ggml-org/llama.cpp/releases/download/b12125/' + filename, expected)
        extract(archive, runtime / backend)
        for name in ('llama', 'ggml', 'ggml-base'):
            import_library(runtime / backend, name)
    include = runtime / 'include'
    include.mkdir(exist_ok=True)
    for name, (relative, expected) in HEADERS.items():
        source = fetch(name, f'https://raw.githubusercontent.com/ggml-org/llama.cpp/{REVISION}/{relative}', expected)
        shutil.copy2(source, include / name)
    archive = fetch('converter.zip', f'https://codeload.github.com/batao9/AzooKeyKanaKanjiConverter/zip/{CONVERTER}',
                    'acbf8398b67891f51ba37cdc16ae6f5514798ffea1e7270077915b433a93866e')
    extract(archive, converter_root)
    converter = converter_root / ('AzooKeyKanaKanjiConverter-' + CONVERTER)
    headers = converter / 'Sources/llama.cpp'
    for name in HEADERS:
        shutil.copy2(include / name, headers / name)
    module_map = headers / 'module.modulemap'
    module_map.write_text(converter_patch.patch_modulemap(module_map.read_text()))
    context = converter / converter_patch.ZENZ_CONTEXT_PATH
    context.write_text(
        converter_patch.patch_zenz_context(context.read_text(encoding='utf-8')),
        encoding='utf-8',
    )
    # Preserve redistributable MSVC/plutil inputs from the original immutable
    # dependency store; do not import live application binaries or settings.
    legacy = ROOT / 'artifacts/runtime/windows'
    manifest = legacy / 'dependencies-manifest.json'
    if manifest.is_file():
        for entry in json.loads(manifest.read_text(encoding='utf-8-sig'))['files']:
            name = entry['path']
            if '/' not in name and '\\' not in name and (name.endswith('.dll') or name == 'plutil.exe'):
                if sha(legacy / name) != entry['sha256']:
                    raise ValueError('Original redistributable changed: ' + name)
                shutil.copy2(legacy / name, runtime / name)
    files = []
    for path in runtime.rglob('*'):
        if path.is_file() and 'include' not in path.relative_to(runtime).parts:
            files.append({'path': path.relative_to(runtime).as_posix(), 'bytes': path.stat().st_size, 'sha256': sha(path)})
    (runtime / 'dependencies-manifest.json').write_text(json.dumps({'schemaVersion': 1, 'llamaRevision': REVISION, 'files': files}))
    result = {'runtime': str(runtime), 'converter': str(converter), 'include': str(include), 'revision': REVISION}
    receipt.write_text(json.dumps(result))
    print(json.dumps(result))


if __name__ == '__main__':
    main()
