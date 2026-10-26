"""Copy only same-version Dispatch runtime files from the verified official SDK."""
import shutil
import sys
import tarfile
from pathlib import Path, PurePosixPath

archive, output = Path(sys.argv[1]), Path(sys.argv[2])
found = set()
with tarfile.open(archive, 'r|gz') as source:
    for member in source:
        parts = PurePosixPath(member.name).parts
        name = parts[-1] if parts else ''
        if name not in {'libdispatch.so', 'libswiftDispatch.so'} or not member.isfile():
            continue
        architecture = next((arch for arch in ('aarch64', 'x86_64') if arch in parts or f'swift-{arch}' in parts), None)
        if architecture is None:
            continue
        abi = 'arm64-v8a' if architecture == 'aarch64' else architecture
        destination = output / abi / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        with source.extractfile(member) as content, destination.open('wb') as target:
            shutil.copyfileobj(content, target)
        found.add((abi, name))
for abi in ('arm64-v8a', 'x86_64'):
    for name in ('libdispatch.so', 'libswiftDispatch.so'):
        if (abi, name) not in found:
            raise SystemExit(f'Official SDK is missing {abi}/{name}')
print('Extracted verified Dispatch runtime files for both Android ABIs.')
