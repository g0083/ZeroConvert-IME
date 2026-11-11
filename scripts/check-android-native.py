"""Check packaged ELF closure and Swift runtime symbol coherence without loading Android code."""
import struct
import sys
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SYSTEM = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so', 'libstdc++.so'}
SHT_STRTAB = 3
SHT_DYNSYM = 11
# The real Dispatch shared library exports far more; a static-archive link stub
# ends up with around a dozen and cannot resolve _dispatch_main_q.
MIN_DISPATCH_EXPORTS = 64


def dependencies(path, expected_machine):
    return elf_dependencies(path.read_bytes(), path.name, expected_machine)


def elf_dependencies(data, name, expected_machine):
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError(f'{name}: expected little-endian ELF64')
    machine = struct.unpack_from('<H', data, 18)[0]
    if machine != expected_machine:
        raise ValueError(f'{name}: wrong machine {machine}')
    offset = struct.unpack_from('<Q', data, 32)[0]
    size, count = struct.unpack_from('<HH', data, 54)
    headers = [struct.unpack_from('<IIQQQQQQ', data, offset + index * size) for index in range(count)]
    dynamic = next(header for header in headers if header[0] == 2)
    entries = [struct.unpack_from('<QQ', data, at) for at in range(dynamic[2], dynamic[2] + dynamic[5], 16)]
    address = next(value for tag, value in entries if tag == 5)
    load = next(header for header in headers if header[0] == 1 and header[3] <= address < header[3] + header[5])
    strings = load[2] + address - load[3]

    def string(index):
        start = strings + index
        return data[start:data.index(0, start)].decode('utf-8')

    sonames = [string(value) for tag, value in entries if tag == 14]
    if sonames and sonames != [name]:
        raise ValueError(f'{name}: unexpected SONAME {sonames}')
    return [string(value) for tag, value in entries if tag == 1]


def dynamic_symbols(path):
    """Return defined names and undefined names in dynamic symbol table order."""
    data = path.read_bytes()
    if data[:6] != b'\x7fELF\x02\x01':
        raise ValueError(f'{path.name}: expected little-endian ELF64')
    offset = struct.unpack_from('<Q', data, 40)[0]
    size, count = struct.unpack_from('<HH', data, 58)
    if not offset or not count:
        raise ValueError(f'{path.name}: missing section headers')
    headers = [struct.unpack_from('<IIQQQQIIQQ', data, offset + index * size) for index in range(count)]
    dynsym = next((header for header in headers if header[1] == SHT_DYNSYM), None)
    if dynsym is None:
        raise ValueError(f'{path.name}: missing dynamic symbol table')
    strings = headers[dynsym[6]]
    if strings[1] != SHT_STRTAB:
        raise ValueError(f'{path.name}: dynamic symbols do not link a string table')

    def string(index):
        start = strings[4] + index
        return data[start:data.index(0, start)].decode('utf-8')

    defined, undefined = set(), []
    seen = set()
    for at in range(dynsym[4], dynsym[4] + dynsym[5], 24):
        name_offset, _info, _other, shndx, _value, _size = struct.unpack_from('<IBBHQQ', data, at)
        name = string(name_offset).split('@')[0]
        if not name:
            continue
        if shndx != 0:
            defined.add(name)
        elif name not in seen:
            seen.add(name)
            undefined.append(name)
    return defined, undefined


def swift_symbol_gaps(folder):
    """Describe packaged symbols that nothing provides or that must exist.

    Two failure modes are checked. First, a Swift mangled import that no other
    packaged library defines. Second, a libdispatch.so that is a link stub
    rather than the real library: linking libdispatch.a pulls in only the
    objects the build referenced, so the result has a few dozen exports and
    silently lacks the rest of the public API. libswift_Concurrency.so and
    libswiftDispatch.so import _dispatch_main_q, which such a stub cannot
    resolve, and the whole engine then fails to dlopen.
    """
    tables = {path.name: dynamic_symbols(path) for path in sorted(folder.glob('*.so'))}
    providers = {}
    for library, (defined, _undefined) in tables.items():
        for name in defined:
            providers.setdefault(name, set()).add(library)
    gaps = []
    for library, (own, undefined) in tables.items():
        for name in undefined:
            if not name.startswith('$s') or name in own:
                continue
            if providers.get(name, set()) - {library}:
                continue
            gaps.append(f'{library} requires {name} but no packaged library defines it')
    dispatch = tables.get('libdispatch.so')
    if dispatch is not None:
        exports = len(dispatch[0])
        if exports < MIN_DISPATCH_EXPORTS:
            gaps.append(f'libdispatch.so exports only {exports} symbols; '
                        f'expected the shared Dispatch library, not a link stub')
        elif not any(name.lstrip('_') == 'dispatch_main_q' for name in dispatch[0]):
            gaps.append('libdispatch.so does not define dispatch_main_q')
    return gaps


def dependency_closure(folder, machine):
    required = set()
    backend_manifest = folder / 'cpu-backends.json'
    backends = json.loads(backend_manifest.read_text(encoding='utf-8-sig')) if backend_manifest.exists() else []
    if any(not name.startswith('libggml-cpu') or not name.endswith('.so') for name in backends):
        raise ValueError(f'Invalid runtime CPU backend manifest: {backend_manifest}')
    pending = ['libzenz_jni.so'] + backends
    while pending:
        name = pending.pop()
        if name in SYSTEM or name in required:
            continue
        if '/' in name or '\\' in name or not (folder / name).is_file():
            raise ValueError(f'{folder.name}: unpackaged or absolute dependency {name}')
        required.add(name)
        pending.extend(dependencies(folder / name, machine))
    return required


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64'])
    parser.add_argument('--prune-unused', action='store_true', help='Remove unreachable runtime files from generated output only')
    parser.add_argument('--skip-symbol-check', action='store_true',
                        help='Skip Swift symbol coherence; only valid together with --prune-unused')
    options = parser.parse_args()
    if options.skip_symbol_check and not options.prune_unused:
        parser.error('--skip-symbol-check is only valid with --prune-unused')
    for abi, machine in [('arm64-v8a', 183), ('x86_64', 62)]:
        if options.abi and options.abi != abi:
            continue
        folder = ROOT / 'engine/build/jniLibs' / abi
        required = dependency_closure(folder, machine)
        if options.prune_unused:
            generated = (ROOT / 'engine/build/jniLibs').resolve()
            if folder.is_symlink() or folder.resolve().parent != generated:
                raise ValueError(f'Refusing to prune outside generated JNI folder: {folder}')
            # No recursive deletion; dependency closure is computed and checked
            # before any removal. The installed SDK is never changed.
            for library in sorted(folder.glob('*.so')):
                if library.is_symlink() or library.resolve().parent != folder.resolve():
                    raise ValueError(f'Refusing to prune linked library: {library}')
                if library.name not in required:
                    print(f'{abi}: omit unused runtime {library.name} ({library.stat().st_size} bytes).')
                    library.unlink()
        libraries = sorted(folder.glob('*.so'))
        if not libraries:
            raise ValueError(f'No native libraries for {abi}')
        available = {path.name for path in libraries} | SYSTEM
        for library in libraries:
            for needed in dependencies(library, machine):
                if '/' in needed or '\\' in needed or needed not in available:
                    raise ValueError(f'{abi}/{library.name}: unpackaged or absolute dependency {needed}')
        if not options.skip_symbol_check:
            gaps = swift_symbol_gaps(folder)
            if gaps:
                raise ValueError(f'{abi}: unresolved Swift runtime symbols: ' + '; '.join(gaps[:10]))
            print(f'{abi}: Swift runtime symbols resolved across {len(libraries)} libraries.')
        print(f'{abi}: checked ABI, SONAME and dependency closure for {len(libraries)} libraries.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, StopIteration, struct.error) as failure:
        print(failure, file=sys.stderr)
        sys.exit(1)
