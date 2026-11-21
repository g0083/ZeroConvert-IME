"""Inspect actual APK outputs: ABI closure, compressed runtime and unchanged models."""
import json
import re
import runpy
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ELF = runpy.run_path(str(ROOT / 'scripts/check-android-native.py'))
OUTPUT = ROOT / 'android/rebuilt/build/outputs/apk/debug'


def main():
    metadata = json.loads((OUTPUT / 'output-metadata.json').read_text(encoding='utf-8'))
    catalog = json.loads((ROOT / 'windows/deployment/dictionary-catalog.json').read_text(encoding='utf-8'))
    dictionary = catalog['dictionary']
    manager = (ROOT / 'android/app/src/main/java/com/zeroconvert/ime/model/DictionaryDownloadManager.kt').read_text(encoding='utf-8')
    manager = re.sub(r'(?<=\d)_(?=\d)', '', manager)
    for name, value in (
        ('FILENAME', f'"{dictionary["filename"]}"'),
        ('EXPECTED_BYTES', f'{dictionary["bytes"]}L'),
        ('SHA256', f'"{dictionary["sha256"]}"'),
        ('FILE_COUNT', str(dictionary['files'])),
        ('RELEASE_TAG', f'"{catalog["releaseTag"]}"'),
    ):
        if f'const val {name} = {value}' not in manager:
            raise ValueError(f'DictionaryDownloadManager.{name} does not match dictionary-catalog.json')
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
            notices = ROOT / 'data/dictionary/AdditionalDictionaryNotices'
            for source in sorted(notices.iterdir()):
                asset = f'assets/licenses/{notices.name}/{source.name}'
                if archive.read(asset) != source.read_bytes():
                    raise ValueError(f'{apk.name}: bundled dictionary notice differs from source: {asset}')
            if archive.read('assets/licenses/third-party.json') != (ROOT / 'licenses/third-party.json').read_bytes():
                raise ValueError(f'{apk.name}: bundled license list differs from source')
            terms = 'assets/licenses/USE-TERMS.txt'
            if archive.read(terms) != (ROOT / 'android/app/src/main' / terms).read_bytes():
                raise ValueError(f'{apk.name}: bundled use terms differ from source')
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
            # 日本語辞書は初回起動時に files/dictionary へ取得する。APKに辞書が
            # 入っていれば、同梱が復活したかアセット除外が壊れている。
            bundled = [name for name in names if name.startswith('assets/shared-engine/Dictionary/')]
            if bundled:
                raise ValueError(f'{apk.name}: bundled Japanese dictionary ({len(bundled)} files); it must be downloaded on device')
            # The packaged lock is what identifies the engine revision this APK
            # was built from, so it must be the committed one.
            packaged_lock = archive.read('assets/shared-engine/lock.json')
            if packaged_lock != (ROOT / 'engine/shared-engine.lock.json').read_bytes():
                raise ValueError(f'{apk.name}: packaged engine lock differs from engine/shared-engine.lock.json')
            engine_lock = json.loads(packaged_lock)
            catalog = json.loads((ROOT / 'windows/deployment/model-catalog.json').read_text(encoding='utf-8'))
            specs = catalog['models']
            # 変換モデルはAPKに同梱しない。端末側のキャッシュへ GitHub リリースの
            # 固定アセット（models-v4.1）から取得し、サイズ+SHA-256で検証する。
            if any(name.endswith('.gguf') for name in names):
                raise ValueError(f'{apk.name}: bundled converter model; models must be downloaded on device')
            for spec in specs:
                assert spec['sha256'] and len(spec['sha256']) == 64 and spec['bytes'] > 0, f'bad catalog entry: {spec["id"]}'
        print(f'{apk.name}: {apk.stat().st_size} bytes; {", ".join(sorted(abis))}; no bundled models or dictionary, runtime verified.')
        print(f'  engine lock: converter {engine_lock["converterRevision"]}, swift {engine_lock["swiftSources"][:12]}')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, zipfile.BadZipFile) as failure:
        print(failure, file=sys.stderr)
        sys.exit(1)
