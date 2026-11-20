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
DICTIONARY = ROOT / 'data/dictionary'


def main():
    metadata = json.loads((OUTPUT / 'output-metadata.json').read_text(encoding='utf-8'))
    with zipfile.ZipFile(DICTIONARY / 'additional-louds.zip') as overlay:
        dictionary = json.loads(overlay.read('manifest.json'))
    dictionary_files = dict(dictionary['files'])
    dictionary_files['Reverse/reverse-v2.bin'] = dictionary['reverse']['sha256']
    for notice in (DICTIONARY / 'AdditionalDictionaryNotices').iterdir():
        dictionary_files[f'AdditionalDictionaryNotices/{notice.name}'] = hashlib.sha256(notice.read_bytes()).hexdigest()
    for artifact in metadata['elements']:
        apk = OUTPUT / artifact['outputFile']
        # Android asset names are UTF-8 even when ZIP's UTF-8 flag is unset.
        with zipfile.ZipFile(apk, metadata_encoding='utf-8') as archive:
            names = set(archive.namelist())
            if 'assets/NOTICE-keyboard-typo-dictionary.md' in names or 'assets/keyboard-typo-cases.tsv' in names:
                raise ValueError(f'{apk.name}: obsolete keyboard typo dictionary asset')
            if 'assets/NOTICE-keyboard-typo-rewrite.md' in names:
                raise ValueError(f'{apk.name}: obsolete standalone Mozc rewrite notice')
            mozc_notice = 'assets/licenses/NOTICE-Mozc.txt'
            if archive.read(mozc_notice) != (ROOT / 'android/app/src/main' / mozc_notice).read_bytes():
                raise ValueError(f'{apk.name}: bundled Mozc notice differs from source')
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
            for name, expected_hash in dictionary_files.items():
                asset = f'assets/shared-engine/Dictionary/{name}'
                if asset not in names or hashlib.sha256(archive.read(asset)).hexdigest() != expected_hash:
                    raise ValueError(f'{apk.name}: missing or changed dictionary asset {asset}')
            for name in dictionary['removed']:
                if f'assets/shared-engine/Dictionary/{name}' in names:
                    raise ValueError(f'{apk.name}: stale dictionary asset {name}')
            # The packaged lock is what identifies the engine revision this APK
            # was built from, so it must be the committed one.
            packaged_lock = archive.read('assets/shared-engine/lock.json')
            if packaged_lock != (ROOT / 'engine/shared-engine.lock.json').read_bytes():
                raise ValueError(f'{apk.name}: packaged engine lock differs from engine/shared-engine.lock.json')
            engine_lock = json.loads(packaged_lock)
            catalog = json.loads((ROOT / 'windows/deployment/model-catalog.json').read_text(encoding='utf-8'))
            specs = [m for m in catalog['models'] if m['id'].startswith('nano_v4_1_')]
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
        print(f'{apk.name}: {apk.stat().st_size} bytes; {", ".join(sorted(abis))}; models, runtime and {dictionary["reverse"]["pairs"]:,}-pair dictionary verified.')
        print(f'  engine lock: converter {engine_lock["converterRevision"]}, swift {engine_lock["swiftSources"][:12]}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, zipfile.BadZipFile) as failure:
        print(failure, file=sys.stderr)
        sys.exit(1)
