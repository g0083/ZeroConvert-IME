"""Build the on-demand dictionary asset and its pinned catalog entry.

The Japanese dictionary (azooKey base + reviewed ZeroConvert expansion) is no
longer bundled with the Windows installer or the Android APK. This script packs
a prepared `Dictionary` directory into one zip, then records the size, SHA-256
and content facts the clients verify before extracting it.

Both the Windows settings app and Android read `windows/deployment/dictionary-catalog.json`,
so regenerate it whenever the dictionary changes:

    python scripts/run.py scripts/build-dictionary-asset.py \
        --dictionary windows/target/trial-dictionary/Dictionary \
        --output artifacts/build/dictionary-asset/zerocon-dictionary.zip

Only the catalog file is committed; the zip belongs in a GitHub release.
"""

import argparse
import hashlib
import json
import os
import struct
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / 'windows/deployment/dictionary-catalog.json'
OVERLAY = ROOT / 'data/dictionary/additional-louds.zip'
NOTICES = ROOT / 'data/dictionary/AdditionalDictionaryNotices'
ARCHIVE_ROOT = 'Dictionary'
REVERSE = 'Reverse/reverse-v2.bin'
# Guardrails: a truncated or unexpectedly large input must not silently ship.
MIN_FILES = 3_000
MAX_FILES = 20_000
MAX_EXPANDED_BYTES = 512 * 1024 * 1024


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def reverse_pairs(path):
    with path.open('rb') as binary:
        magic, pairs = struct.unpack('<4sI', binary.read(8))
    if magic != b'AZR2':
        raise ValueError(f'Invalid reverse dictionary magic in {path}')
    return pairs


def dictionary_files(dictionary):
    return sorted(
        (path for path in dictionary.rglob('*') if path.is_file()),
        key=lambda path: path.relative_to(dictionary).as_posix(),
    )


def validate(dictionary):
    for required in ('louds/charID.chid', 'mm.binary', REVERSE):
        if not (dictionary / required).is_file():
            raise ValueError(f'Prepared dictionary is missing {required}: run scripts/prepare_dictionary.py first')
    for source in sorted(NOTICES.iterdir()):
        bundled = dictionary / NOTICES.name / source.name
        if not bundled.is_file() or bundled.read_bytes() != source.read_bytes():
            raise ValueError(f'Prepared dictionary is missing the original notice: {NOTICES.name}/{source.name}')
    files = dictionary_files(dictionary)
    if not MIN_FILES <= len(files) <= MAX_FILES:
        raise ValueError(f'Unexpected dictionary file count: {len(files)}')
    expanded = sum(path.stat().st_size for path in files)
    if expanded > MAX_EXPANDED_BYTES:
        raise ValueError(f'Dictionary is unexpectedly large: {expanded} bytes')
    return files, expanded


def write_archive(dictionary, files, output):
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_name(output.name + '.tmp')
    with ZipFile(temporary, 'w', compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            relative = path.relative_to(dictionary).as_posix()
            info = ZipInfo(f'{ARCHIVE_ROOT}/{relative}', date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())
    temporary.replace(output)


def additional_pairs():
    if not OVERLAY.is_file():
        return None
    with ZipFile(OVERLAY) as archive:
        return json.loads(archive.read('manifest.json'))['additionalPairs']


def build(dictionary, output, identifier, release_tag, catalog_output=CATALOG):
    files, expanded = validate(dictionary)
    write_archive(dictionary, files, output)
    pairs = reverse_pairs(dictionary / REVERSE)
    entry = {
        'id': identifier,
        'filename': output.name,
        'sha256': sha256(output),
        'bytes': output.stat().st_size,
        'expandedBytes': expanded,
        'files': len(files),
        'reversePairs': pairs,
    }
    overlay = additional_pairs()
    if overlay is not None:
        entry['overlayPairs'] = overlay
    catalog = {
        'schemaVersion': 1,
        'releaseTag': release_tag,
        'downloadBaseUrl': f'https://github.com/g0083/ZeroConvert-IME/releases/download/{release_tag}',
        'dictionary': entry,
    }
    catalog_output.parent.mkdir(parents=True, exist_ok=True)
    catalog_output.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f'{output}: {entry["bytes"]:,} bytes ({entry["files"]:,} files, {pairs:,} reverse pairs)')
    print(f'Wrote {catalog_output}')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--dictionary', type=Path, required=True, help='Prepared Dictionary directory')
    parser.add_argument('--output', type=Path, required=True, help='Destination zip path')
    parser.add_argument('--catalog-output', type=Path, default=CATALOG, help='Destination catalog JSON path')
    parser.add_argument('--id', default='azookey-expanded', help='Stable dictionary identifier recorded in the catalog')
    parser.add_argument('--release-tag', default='dictionary-v2', help='GitHub release tag that will host the zip')
    args = parser.parse_args()

    dictionary = args.dictionary.resolve()
    if not dictionary.is_dir():
        parser.error(f'Not a directory: {dictionary}')
    output = args.output if args.output.is_absolute() else (Path(os.getcwd()) / args.output)
    output = output.resolve()
    if output.suffix != '.zip':
        parser.error('--output must be a .zip path')
    if output.is_relative_to(dictionary):
        parser.error('--output must be outside the dictionary directory')
    catalog_output = args.catalog_output.resolve()
    if catalog_output.suffix != '.json' or catalog_output.is_relative_to(dictionary):
        parser.error('--catalog-output must be a .json file outside the dictionary directory')
    try:
        build(dictionary, output, args.id, args.release_tag, catalog_output)
    except ValueError as failure:
        print(failure, file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
