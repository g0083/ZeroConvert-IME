"""Prepare pinned native dependencies in new, content-addressed artifact folders.

Never edits an installed IME, an existing dependency checkout, or model weights.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import converter_patch

ROOT = Path(__file__).resolve().parents[2]
REVISION = '7d516e2'
CONVERTER = 'c228776b0b869f81ee2a1031ff9dbd679f4b3cd9'
PATCH_FILE = ROOT / 'patches/llama.cpp/zerocon-compat-b12125.patch'
SOURCE_ARCHIVE = (
    'source-7d516e2.zip',
    'a58819f5f8d8869f4a4d0e90215f88f28b67b211d0ba80e4e2af6644eee56618',
)
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


def _strip_eols(line):
    return line.rstrip('\r\n')


def apply_unified_patch(source_dir, patch_path):
    """Apply a unified diff strictly inside source_dir without relying on git."""
    lines = patch_path.read_text(encoding='utf-8').splitlines()
    root_resolved = source_dir.resolve()
    i = 0
    applied_files = 0
    while i < len(lines):
        if not lines[i].startswith('--- a/'):
            raise ValueError(f'Unexpected patch line {i + 1}: {lines[i]!r}')
        old_rel = lines[i][6:].strip()
        if i + 1 >= len(lines) or not lines[i + 1].startswith('+++ b/'):
            raise ValueError(f'Missing +++ header at line {i + 1}')
        new_rel = lines[i + 1][6:].strip()
        if old_rel != new_rel:
            raise ValueError(f'Path rename unsupported in patch: {old_rel} -> {new_rel}')
        i += 2
        target = (source_dir / old_rel).resolve()
        if not target.is_relative_to(root_resolved) or not target.is_file():
            raise ValueError(f'Patch target missing or unsafe: {old_rel}')
        raw = target.read_text(encoding='utf-8')
        eol = '\r\n' if '\r\n' in raw else '\n'
        orig_lines = raw.splitlines()
        out_lines = []
        cursor = 0
        while i < len(lines) and not lines[i].startswith('--- a/'):
            m = re.match(r'^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@', lines[i])
            if not m:
                raise ValueError(f'Expected hunk header at line {i + 1}: {lines[i]!r}')
            old_start = int(m.group(1))
            old_count = int(m.group(2)) if m.group(2) is not None else 1
            new_count = int(m.group(4)) if m.group(4) is not None else 1
            i += 1
            target_idx = max(0, old_start - 1)
            if target_idx < cursor or target_idx > len(orig_lines):
                raise ValueError(f'Invalid hunk offset {old_start} in {old_rel}')
            out_lines.extend(orig_lines[cursor:target_idx])
            cursor = target_idx
            seen_old = 0
            seen_new = 0
            while i < len(lines) and (seen_old < old_count or seen_new < new_count):
                hline = lines[i]
                if not hline:
                    raise ValueError(f'Unexpected blank diff line in {old_rel} at line {i + 1}')
                tag, body = hline[0], hline[1:]
                if tag == ' ':
                    if cursor >= len(orig_lines) or _strip_eols(orig_lines[cursor]) != _strip_eols(body):
                        raise ValueError(f'Context mismatch in {old_rel} around line {cursor + 1}')
                    out_lines.append(orig_lines[cursor])
                    cursor += 1
                    seen_old += 1
                    seen_new += 1
                elif tag == '-':
                    if cursor >= len(orig_lines) or _strip_eols(orig_lines[cursor]) != _strip_eols(body):
                        raise ValueError(f'Removal mismatch in {old_rel} around line {cursor + 1}')
                    cursor += 1
                    seen_old += 1
                elif tag == '+':
                    out_lines.append(body)
                    seen_new += 1
                else:
                    raise ValueError(f'Unexpected diff tag {tag!r} in {old_rel}')
                i += 1
                if i < len(lines) and lines[i].startswith('\\ No newline'):
                    i += 1
            if seen_old != old_count or seen_new != new_count:
                raise ValueError(f'Hunk count mismatch in {old_rel}')
        out_lines.extend(orig_lines[cursor:])
        trailing = eol if raw.endswith(('\n', '\r')) else ''
        target.write_text(eol.join(out_lines) + trailing, encoding='utf-8')
        applied_files += 1
    if applied_files == 0:
        raise ValueError(f'No files patched by {patch_path}')


def build_patched_llama_dll(destination):
    """Build patched llama.dll from pinned upstream source + zerocon-compat-b12125.patch."""
    if not PATCH_FILE.is_file():
        raise FileNotFoundError(f'Missing llama.cpp patch: {PATCH_FILE}')
    cmake_exe = shutil.which('cmake')
    if not cmake_exe:
        raise RuntimeError('cmake is required to build patched llama.dll')
    src_filename, src_expected = SOURCE_ARCHIVE
    source_zip = fetch(
        src_filename,
        f'https://github.com/ggml-org/llama.cpp/archive/{REVISION}.zip',
        src_expected,
    )
    work_root = ROOT / 'artifacts/runtime/_llama_build_tmp'
    if work_root.exists():
        shutil.rmtree(work_root)
    work_root.mkdir(parents=True, exist_ok=True)
    try:
        src_parent = work_root / 'src'
        extract(source_zip, src_parent)
        dirs = [p for p in src_parent.iterdir() if p.is_dir()]
        if len(dirs) != 1:
            raise RuntimeError(f'Expected single extracted directory in {src_parent}')
        src_dir = dirs[0]
        apply_unified_patch(src_dir, PATCH_FILE)
        build_dir = work_root / 'bld'
        configure_cmd = [
            cmake_exe, '-S', str(src_dir), '-B', str(build_dir),
            '-DBUILD_SHARED_LIBS=ON',
            '-DGGML_NATIVE=OFF',
            '-DGGML_VULKAN=OFF',
            '-DGGML_OPENMP=OFF',
            '-DLLAMA_BUILD_TESTS=OFF',
            '-DLLAMA_BUILD_EXAMPLES=OFF',
            '-DLLAMA_BUILD_TOOLS=OFF',
            '-DLLAMA_BUILD_SERVER=OFF',
            '-DLLAMA_BUILD_COMMON=OFF',
            '-DLLAMA_OPENSSL=OFF',
            '-DLLAMA_USE_PREBUILT_UI=OFF',
            '-DLLAMA_BUILD_NUMBER=12125',
            f'-DLLAMA_BUILD_COMMIT={REVISION}',
        ]
        ninja_exe = shutil.which('ninja')
        if ninja_exe and shutil.which('clang') and shutil.which('clang++'):
            configure_cmd += [
                '-G', 'Ninja',
                f'-DCMAKE_MAKE_PROGRAM={ninja_exe}',
                '-DCMAKE_BUILD_TYPE=Release',
                f'-DCMAKE_TOOLCHAIN_FILE={src_dir / "cmake/x64-windows-llvm.cmake"}',
            ]
        else:
            configure_cmd += ['-A', 'x64']
        subprocess.run(configure_cmd, check=True, stdout=sys.stderr)
        subprocess.run(
            [cmake_exe, '--build', str(build_dir), '--config', 'Release', '--target', 'llama', '-j'],
            check=True,
            stdout=sys.stderr,
        )
        built_dll = None
        for candidate in (build_dir / 'bin/Release/llama.dll', build_dir / 'bin/llama.dll'):
            if candidate.is_file():
                built_dll = candidate
                break
        if built_dll is None:
            raise FileNotFoundError(f'Patched llama.dll not produced under {build_dir}')
        if b'gpt2-small-japanese-char' not in built_dll.read_bytes():
            raise RuntimeError('Built llama.dll is missing gpt2-small-japanese-char patch')
        shutil.copy2(built_dll, destination)
    finally:
        if work_root.exists():
            shutil.rmtree(work_root, ignore_errors=True)


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
        evaluator = package.read(prefix + converter_patch.ZENZ_EVALUATOR_PATH).decode('utf-8')
        learning_memory = package.read(prefix + converter_patch.LEARNING_MEMORY_PATH).decode('utf-8')
        converter_api = package.read(prefix + converter_patch.CONVERTER_API_PATH).decode('utf-8')
        lattice = package.read(prefix + converter_patch.LATTICE_PATH).decode('utf-8')
    converter_patch.patch_modulemap(modulemap)
    converter_patch.patch_zenz_context(context)
    converter_patch.patch_zenz_evaluator(evaluator)
    converter_patch.patch_learning_memory(learning_memory)
    converter_patch.patch_converter_api(converter_api)
    converter_patch.patch_lattice(lattice)
    print(json.dumps({'converter': CONVERTER, 'anchors': 'ok'}))


def prepare_converter(converter_root):
    """Fetch, extract and patch the pinned converter. Idempotent.

    Needs no Windows toolchain, so the Android staging step can call this alone
    and stay independent of the Windows llama runtime.
    """
    converter = converter_root / ('AzooKeyKanaKanjiConverter-' + CONVERTER)
    # The patch is part of the prepared content, so a changed patch must
    # re-extract instead of reusing an already-patched tree. The stamp covers
    # exactly the inputs that decide the result, which keeps the directory name
    # stable: an unrelated edit to this script must not invalidate the Android
    # fingerprint and force a rebuild of both ABIs.
    stamp = hashlib.sha256(
        (CONVERTER + sha(Path(converter_patch.__file__))).encode('utf-8')
    ).hexdigest()
    marker = converter_root / 'prepared.json'
    if converter.is_dir() and marker.is_file() and json.loads(marker.read_text()).get('stamp') == stamp:
        return converter
    if converter_root.is_dir():
        shutil.rmtree(converter_root)
    archive = fetch('converter.zip', f'https://codeload.github.com/batao9/AzooKeyKanaKanjiConverter/zip/{CONVERTER}',
                    'acbf8398b67891f51ba37cdc16ae6f5514798ffea1e7270077915b433a93866e')
    extract(archive, converter_root)
    module_map = converter / converter_patch.MODULEMAP_PATH
    module_map.write_text(converter_patch.patch_modulemap(module_map.read_text()))
    context = converter / converter_patch.ZENZ_CONTEXT_PATH
    context.write_text(
        converter_patch.patch_zenz_context(context.read_text(encoding='utf-8')),
        encoding='utf-8',
    )
    evaluator = converter / converter_patch.ZENZ_EVALUATOR_PATH
    evaluator.write_text(
        converter_patch.patch_zenz_evaluator(evaluator.read_text(encoding='utf-8')),
        encoding='utf-8',
    )
    learning_memory = converter / converter_patch.LEARNING_MEMORY_PATH
    learning_memory.write_text(
        converter_patch.patch_learning_memory(learning_memory.read_text(encoding='utf-8')),
        encoding='utf-8',
    )
    converter_api = converter / converter_patch.CONVERTER_API_PATH
    converter_api.write_text(
        converter_patch.patch_converter_api(converter_api.read_text(encoding='utf-8')),
        encoding='utf-8',
    )
    lattice = converter / converter_patch.LATTICE_PATH
    lattice.write_text(
        converter_patch.patch_lattice(lattice.read_text(encoding='utf-8')),
        encoding='utf-8',
    )
    marker.write_text(json.dumps({'stamp': stamp, 'converter': CONVERTER}))
    return converter


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-converter', action='store_true',
                        help='Verify the converter patch anchors and exit without preparing anything.')
    parser.add_argument('--converter-only', action='store_true',
                        help='Prepare only the pinned converter, without the Windows llama runtime.')
    options = parser.parse_args()
    if options.check_converter:
        check_converter()
        return
    if not PATCH_FILE.is_file():
        raise FileNotFoundError(f'Missing llama.cpp patch: {PATCH_FILE}')
    key = hashlib.sha256(
        (sha(Path(__file__)) + sha(PATCH_FILE)).encode('utf-8')
    ).hexdigest()[:12]
    runtime = ROOT / ('artifacts/runtime/windows-b12125-' + key)
    # Stable name: the converter revision and the patch stamp already decide the
    # content, and this path is recorded in the Android source fingerprint.
    # Keep each patch revision immutable. SwiftPM may have placed read-only Git
    # checkouts in an earlier prepared tree; a new patch must use a new tree.
    converter_root = ROOT / ('artifacts/dependencies/azookey-b12125-' + sha(Path(converter_patch.__file__))[:12])
    if options.converter_only:
        print(json.dumps({'converter': str(prepare_converter(converter_root)), 'revision': REVISION}))
        return
    receipt = runtime / 'prepared.json'
    if receipt.is_file():
        existing = json.loads(receipt.read_text(encoding='utf-8'))
        converter = prepare_converter(converter_root)
        include = runtime / 'include'
        headers = converter / 'Sources/llama.cpp'
        for name in HEADERS:
            shutil.copy2(include / name, headers / name)
        if existing.get('converter') != str(converter):
            existing['converter'] = str(converter)
            receipt.write_text(json.dumps(existing), encoding='utf-8')
        print(json.dumps(existing))
        return
    if runtime.is_dir():
        shutil.rmtree(runtime)
    patched_llama = Path(tempfile.mkdtemp(prefix='zerocon-llama-')) / 'llama.dll'
    try:
        build_patched_llama_dll(patched_llama)
        for backend, (filename, expected) in ARCHIVES.items():
            cached = 'cpu.zip' if backend == 'llama_cpu' else 'vulkan.zip'
            # Reuse the previously verified official CPU archive if present.
            previous = ROOT / 'artifacts/tools/plamo-llama-b12125/runtime.zip'
            target = ROOT / 'artifacts/downloads/llama-b12125/cpu.zip'
            if backend == 'llama_cpu' and previous.is_file() and sha(previous) == expected and not target.exists():
                shutil.copy2(previous, target)
            archive = fetch(cached, 'https://github.com/ggml-org/llama.cpp/releases/download/b12125/' + filename, expected)
            extract(archive, runtime / backend)
            shutil.copy2(patched_llama, runtime / backend / 'llama.dll')
            for name in ('llama', 'ggml', 'ggml-base'):
                import_library(runtime / backend, name)
    finally:
        if patched_llama.parent.exists():
            shutil.rmtree(patched_llama.parent, ignore_errors=True)
    include = runtime / 'include'
    include.mkdir(exist_ok=True)
    for name, (relative, expected) in HEADERS.items():
        source = fetch(name, f'https://raw.githubusercontent.com/ggml-org/llama.cpp/{REVISION}/{relative}', expected)
        shutil.copy2(source, include / name)
    converter = prepare_converter(converter_root)
    headers = converter / 'Sources/llama.cpp'
    for name in HEADERS:
        shutil.copy2(include / name, headers / name)
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
