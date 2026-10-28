"""Inspect actual APK outputs: ABI closure, compressed runtime and unchanged models."""
import hashlib
import json
import runpy
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ELF = runpy.run_path(str(ROOT / 'scripts/check-android-native.py'))
OUTPUT = ROOT / 'android/rebuilt/build/outputs/apk/debug'


def main():
    metadata = json.loads((OUTPUT / 'output-metadata.json').read_text(encoding='utf-8'))
    for artifact in metadata['elements']:
        apk = OUTPUT / artifact['outputFile']
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
            abis = {name.split('/')[1] for name in names if name.startswith('lib/') and name.endswith('.so')}
            expected = {item['value'] for item in artifact['filters'] if item['filterType'] == 'ABI'}
            if expected and abis != expected:
                raise ValueError(f'{apk.name}: wrong ABI set {abis}, expected {expected}')
            if not abis:
                raise ValueError(f'{apk.name}: missing native engine')
            for abi in abis:
                libraries = {name.split('/')[-1] for name in names if name.startswith(f'lib/{abi}/') and name.endswith('.so')}
                for name in libraries:
                    entry = archive.getinfo(f'lib/{abi}/{name}')
                    if entry.compress_type != zipfile.ZIP_DEFLATED:
                        raise ValueError(f'{apk.name}: uncompressed library {name}')
                    machine = {'arm64-v8a': 183, 'x86_64': 62}[abi]
                    for needed in ELF['elf_dependencies'](archive.read(entry), name, machine):
                        if needed not in libraries | ELF['SYSTEM']:
                            raise ValueError(f'{apk.name}: missing {needed} needed by {name}')
                # CPU implementations are selected by upstream's feature score.
                backend_manifest = ROOT / 'engine/build/jniLibs' / abi / 'cpu-backends.json'
                for backend in json.loads(backend_manifest.read_text(encoding='utf-8-sig')):
                    if backend not in libraries:
                        raise ValueError(f'{apk.name}: missing runtime CPU backend {backend}')
            if 'assets/dictionary/reverse-v2.bin' in names:
                raise ValueError(f'{apk.name}: duplicate reverse dictionary')
            shared_reverse = archive.read('assets/shared-engine/Dictionary/Reverse/reverse-v2.bin')
            if hashlib.sha256(shared_reverse).digest() != hashlib.sha256(
                (ROOT / 'engine/build/assets/shared-engine/Dictionary/Reverse/reverse-v2.bin').read_bytes()).digest():
                raise ValueError(f'{apk.name}: wrong shared reverse dictionary')
            catalog = json.loads((ROOT / 'windows/deployment/model-catalog.json').read_text(encoding='utf-8'))
            specs = [m for m in catalog['models'] if m['id'].startswith('nano_v4_') or m['id'] == 'corrector']
            expected_models = {'assets/models/' + m['filename'] for m in specs}
            actual_models = {name for name in names if name.endswith('.gguf')}
            if actual_models != expected_models:
                raise ValueError(f'{apk.name}: unexpected model set: {actual_models ^ expected_models}')
            for spec in specs:
                payload = archive.read('assets/models/' + spec['filename'])
                if len(payload) != spec['bytes'] or hashlib.sha256(payload).hexdigest() != spec['sha256']:
                    raise ValueError(f'{apk.name}: model differs from catalog: {spec["filename"]}')
            for model in (ROOT / 'android/app/src/main/assets').rglob('*.gguf'):
                if 'assets/' + model.relative_to(ROOT / 'android/app/src/main/assets').as_posix() not in expected_models:
                    continue
                name = 'assets/' + model.relative_to(ROOT / 'android/app/src/main/assets').as_posix()
                if hashlib.sha256(archive.read(name)).digest() != hashlib.sha256(model.read_bytes()).digest():
                    raise ValueError(f'{apk.name}: model was changed or omitted: {name}')
        print(f'{apk.name}: {apk.stat().st_size} bytes; {", ".join(sorted(abis))}; runtime closure, CPU backends, shared reverse and model hashes verified.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, zipfile.BadZipFile) as failure:
        print(failure, file=sys.stderr)
        sys.exit(1)
