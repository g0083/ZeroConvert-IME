"""Stage the pinned azooKey base and the reviewed ZeroConvert dictionary expansion."""

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import struct
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'windows/server-swift/azooKey_dictionary_storage/Dictionary'
DATA = ROOT / 'data/dictionary'
OVERLAY = DATA / 'additional-louds.zip'
NOTICES = DATA / 'AdditionalDictionaryNotices'
REVERSE = 'Reverse/reverse-v2.bin'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def revision():
    return subprocess.check_output(['git', '-C', BASE.parent, 'rev-parse', 'HEAD'], text=True).strip()


def files(directory):
    return {p.relative_to(directory).as_posix(): p for p in directory.rglob('*') if p.is_file()}


def louds_hashes(directory):
    return {name: digest(path) for name, path in files(directory).items()
            if name.startswith('louds/') and path.name != '.gitkeep'}


def louds_digest(entries):
    return hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()


def reverse_metadata(path):
    with path.open('rb') as binary:
        magic, pairs = struct.unpack('<4sI', binary.read(8))
    if magic != b'AZR2':
        raise ValueError('Invalid reverse dictionary magic')
    return {'pairs': pairs, 'sha256': digest(path)}


def louds_path(name):
    path = PurePosixPath(name)
    if (len(path.parts) != 2 or path.parts[0] != 'louds' or path.as_posix() != name
            or path.name == '..' or any(char in name for char in '\\:')):
        raise ValueError(f'Invalid LOUDS path: {name}')
    return path


def freeze(trial):
    manifest = json.loads((trial / 'additional-dictionary.json').read_text(encoding='utf-8'))
    source = trial / 'Dictionary'
    if manifest['baseRevision'] != revision():
        raise ValueError('Trial was generated for a different base dictionary')
    base, expanded = louds_hashes(BASE), louds_hashes(source)
    replaced = {name: sha for name, sha in expanded.items() if base.get(name) != sha}
    removed = sorted(base.keys() - expanded.keys())
    for name in [*replaced, *removed]:
        louds_path(name)
    expected = {entry['path']: entry['sha256'] for entry in manifest['files']}
    if {name: digest(path) for name, path in files(source).items()} != expected:
        raise ValueError('Trial dictionary does not match its manifest')
    metadata = {
        'schema': 1, 'baseRevision': manifest['baseRevision'], 'additionalPairs': manifest['counts']['total'],
        'trialManifestSha256': digest(trial / 'additional-dictionary.json'),
        'reverse': reverse_metadata(source / REVERSE), 'loudsSha256': louds_digest(expanded),
        'files': replaced, 'removed': removed,
    }
    DATA.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=DATA) as temporary:
        archive_path = Path(temporary) / OVERLAY.name
        with ZipFile(archive_path, 'w') as archive:
            archive.writestr(ZipInfo('manifest.json'), json.dumps(metadata, ensure_ascii=False, sort_keys=True) + '\n',
                             compress_type=ZIP_DEFLATED, compresslevel=9)
            for name in sorted(replaced):
                archive.writestr(ZipInfo(name), (source / name).read_bytes(),
                                 compress_type=ZIP_DEFLATED, compresslevel=9)
        archive_path.replace(OVERLAY)
    NOTICES.mkdir(exist_ok=True)
    for name in ('LEGAL', 'LICENSE-2.0.txt', 'Wikidata.txt'):
        shutil.copyfile(source / 'AdditionalDictionaryNotices' / name, NOTICES / name)
    print(f'Frozen {metadata["additionalPairs"]:,} additions in {OVERLAY}')


def apply_overlay(stage):
    with ZipFile(OVERLAY) as archive:
        metadata = json.loads(archive.read('manifest.json'))
        if metadata['schema'] != 1 or metadata['baseRevision'] != revision():
            raise ValueError('The dictionary expansion requires its pinned azooKey revision')
        expected = {'manifest.json', *metadata['files']}
        if len(archive.namelist()) != len(expected) or set(archive.namelist()) != expected:
            raise ValueError('Unexpected entries in dictionary expansion')
        for name in metadata['removed']:
            (stage / louds_path(name)).unlink()
        for name, sha in metadata['files'].items():
            path = stage / louds_path(name)
            data = archive.read(name)
            if hashlib.sha256(data).hexdigest() != sha:
                raise ValueError(f'Dictionary expansion hash mismatch: {name}')
            path.write_bytes(data)
    if louds_digest(louds_hashes(stage)) != metadata['loudsSha256']:
        raise ValueError('Expanded LOUDS files differ from the verified trial')
    shutil.copytree(NOTICES, stage / NOTICES.name)
    return metadata['reverse']


def prepare(output, base_only):
    if (output.resolve() != output or BASE.is_relative_to(output) or output.is_relative_to(BASE)
            or DATA.is_relative_to(output) or output.is_relative_to(DATA)):
        raise ValueError(f'Expected a dictionary staging path separate from source data: {output}')
    if output.exists() and (not output.is_dir() or any(
            path.resolve() != path for path in output.rglob('*'))):
        raise ValueError(f'Expected a regular dictionary output directory: {output}')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.dictionary-', dir=output.parent) as temporary:
        stage = Path(temporary) / 'Dictionary'
        shutil.copytree(BASE, stage, ignore=shutil.ignore_patterns('.gitkeep'))
        expected_reverse = None if base_only else apply_overlay(stage)
        subprocess.run(['node', str(ROOT / 'windows/scripts/generate-reverse-dictionary.mjs'),
                        '--source', str(stage / 'louds'), '--output', str(stage / REVERSE)], check=True)
        if expected_reverse is not None and reverse_metadata(stage / REVERSE) != expected_reverse:
            raise ValueError('Expanded dictionary reverse index differs from the verified trial')
        if output.exists():
            staged = files(stage)
            for name, old in files(output).items():
                if name not in staged:
                    old.unlink()
            shutil.copytree(stage, output, dirs_exist_ok=True)
        else:
            stage.rename(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    command = parser.add_mutually_exclusive_group(required=True)
    command.add_argument('--output', type=Path)
    command.add_argument('--freeze-trial', type=Path)
    parser.add_argument('--base-only', action='store_true')
    args = parser.parse_args()
    if args.freeze_trial:
        freeze(args.freeze_trial)
    else:
        prepare(args.output.absolute(), args.base_only)


if __name__ == '__main__':
    main()
