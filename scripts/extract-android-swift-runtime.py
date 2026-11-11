"""Write a per-ABI Swift runtime overlay from exactly one build.

Usage: extract-android-swift-runtime.py <archive> <output> [--sdk-root DIR]

<output>/<abi>/ receives the official archive runtime for an ABI whose
installed SDK build is incomplete, and a source.json marker either way.
--sdk-root is the SDK per-arch Swift library root
(.../Android.sdk/usr/lib/swift/android, holding aarch64/ and x86_64/) and
defaults to the Swift 6.4.1 Windows install. An ABI is SDK-complete when its
per-arch dir has libswiftCore.so, libswiftDispatch.so and libdispatch.so.
A complete ABI extracts nothing and drops stale archive files, because one
runtime build per ABI is the invariant.
"""
import argparse
import json
import os
import shutil
import tarfile
from pathlib import Path, PurePosixPath

ARCHITECTURES = {'aarch64': 'arm64-v8a', 'x86_64': 'x86_64'}
REQUIRED = ('libdispatch.so', 'libswiftCore.so', 'libswiftDispatch.so')
SDK_LIB_ROOT = ('Programs/Swift/Platforms/6.4.1/Android.platform'
                '/Developer/SDKs/Android.sdk/usr/lib/swift/android')


def sdk_complete(sdk_root, architecture):
    """One installed build is usable alone only if it ships Dispatch as a .so.

    A static libdispatch.a cannot stand in: linking it pulls in only the objects
    the build happened to reference, so the resulting libdispatch.so exports a
    handful of symbols and omits the rest of the public API. arm64 hit exactly
    that: libswift_Concurrency.so and libswiftDispatch.so both need
    _dispatch_main_q, and a 12-symbol stub cannot resolve it, so dlopen fails at
    runtime. The archive ships a complete shared Dispatch for every ABI.
    """
    folder = sdk_root / architecture
    return all((folder / name).is_file() for name in REQUIRED)


def write_manifest(folder, source, archive_name, files):
    """Record the chosen build so the build step never mixes two of them."""
    manifest = {'source': source, 'archiveSha256Source': archive_name,
                'files': sorted(files)}
    with (folder / 'source.json').open('w', encoding='utf-8', newline='\n') as target:
        json.dump(manifest, target, indent=2, sort_keys=True)
        target.write('\n')


def drop_archive_runtime(folder):
    """Clear an older archive overlay so a complete ABI keeps one build."""
    for stale in folder.glob('*.so'):
        if stale.is_file() and not stale.is_symlink():
            stale.unlink()


def main():
    default_root = Path(os.environ.get('LOCALAPPDATA') or Path.home())
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('archive', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--sdk-root', type=Path, default=default_root / SDK_LIB_ROOT,
                        help='installed SDK per-arch Swift library root '
                             f'(default: {default_root / SDK_LIB_ROOT})')
    options = parser.parse_args()
    if not options.sdk_root.is_dir():
        raise SystemExit(f'Installed Swift SDK runtime root is missing {options.sdk_root}')
    archive_name = options.archive.name
    sources = {architecture: 'sdk' if sdk_complete(options.sdk_root, architecture) else 'archive'
               for architecture in ARCHITECTURES}
    extracted = {abi: [] for abi in ARCHITECTURES.values()}
    wanted = {architecture for architecture, source in sources.items() if source == 'archive'}
    if wanted:
        with tarfile.open(options.archive, 'r|gz') as source:
            for member in source:
                parts = PurePosixPath(member.name).parts
                architecture = next((arch for arch in ARCHITECTURES if f'swift-{arch}' in parts), None)
                if architecture not in wanted or len(parts) < 2 or parts[-2] != 'android':
                    continue
                if not member.isfile() or not parts[-1].endswith('.so'):
                    continue
                abi = ARCHITECTURES[architecture]
                destination = options.output / abi / parts[-1]
                destination.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as content, destination.open('wb') as target:
                    shutil.copyfileobj(content, target)
                extracted[abi].append(parts[-1])
    for architecture, abi in ARCHITECTURES.items():
        folder = options.output / abi
        folder.mkdir(parents=True, exist_ok=True)
        if sources[architecture] == 'archive':
            for name in sorted(set(REQUIRED) - set(extracted[abi])):
                raise SystemExit(f'Official archive is missing {abi}/{name}')
            files = sorted(extracted[abi])
            write_manifest(folder, 'archive', archive_name, files)
            print(f'{abi}: source archive, {len(files)} files written')
        else:
            # The manifest mirrors the overlay directory, so a complete ABI
            # lists no files and the build step copies the SDK set itself.
            drop_archive_runtime(folder)
            write_manifest(folder, 'sdk', archive_name, [])
            print(f'{abi}: source sdk, 0 files written')


if __name__ == '__main__':
    main()
