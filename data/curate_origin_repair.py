# SPDX-License-Identifier: Apache-2.0
"""Conservative offline label curation; this is not a real TSF state oracle.

Preserve source partitions. Quarantine unresolved terminal states, and treat
fully decoded equivalent spellings as keep rather than synthetic typo rescue.
Validation changes create a new dataset; old diagnostic scores stay immutable.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import shutil
import unicodedata

from keyboard_hybrid import RomanDecoder, TABLE
from origin_contract import digest, validate_row, write_json
from rebalance_origin_repair import iter_rows, prompt_key, target_key


def retain_decoder_evidence(directory):
    """Carry the converter table's MIT notice with labels derived from it."""
    root = Path(__file__).resolve().parents[1]
    resolved = root / 'windows/server-swift/Package.resolved'
    pins = json.loads(resolved.read_text(encoding='utf-8'))['pins']
    pin = next(p for p in pins if p['identity'] == 'azookeykanakanjiconverter')
    license_path = root / 'windows/server-swift/.build/checkouts/AzooKeyKanaKanjiConverter/LICENSE'
    evidence = Path(directory) / 'source-evidence'
    for source, name in ((TABLE, 'defaultRoman2Kana.swift'), (license_path, 'AzooKeyKanaKanjiConverter-MIT-LICENSE'),
                         (root / 'benchmarks/research/keyboard_hybrid.py', 'curation-offline-decoder.py')):
        destination = evidence / name
        if destination.exists() and digest(destination) != digest(source):
            raise ValueError(f'Decoder evidence already differs: {name}')
        if not destination.exists():
            shutil.copy2(source, destination)
    write_json(evidence / 'decoder-label-provenance.json', {
        'use': 'Offline repair label audit/curation, not student initialization or a runtime dependency',
        'table_source': pin['location'], 'resolved_revision': pin['state']['revision'],
        'table_sha256': digest(TABLE), 'license': 'MIT', 'license_sha256': digest(license_path),
        'offline_decoder_sha256': digest(root / 'benchmarks/research/keyboard_hybrid.py'),
        'limits': ['Resolved pin and exact table content retained; decoder is not the TSF state machine']})


def curate(row, decoder):
    if row['task'] != 'repair_qwerty':
        return row, None
    inputs = row['input']
    if set(inputs) != {'ctx', 'raw', 'pending'}:
        raise ValueError('Uniform observed repair slots required before curation')
    if inputs['pending']:
        return row, None
    raw = inputs['raw']
    decoded = decoder.decode(raw)
    if raw.endswith('n') and not raw.endswith('nn'):
        return None, 'terminal_n_requires_event_state_review'
    if decoded and decoded[-1].isascii() and decoded[-1].isalpha():
        return None, 'terminal_residual_requires_event_state_review'
    reference = row.get('reference_reading')
    if reference and row['target']['action'] == 'emit' and unicodedata.normalize('NFC', decoded) == unicodedata.normalize('NFC', reference):
        return {**row, 'target': {'action': 'keep', 'text': ''},
                'pre_curation_target': row['target'],
                'label_curation': 'equivalent_complete_romanization_is_not_a_typo'}, 'equivalent_to_keep'
    return row, None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    parent = json.loads((args.source / 'manifest.json').read_text(encoding='utf-8'))
    for name, expected in parent['output_hashes'].items():
        if digest(args.source / name) != expected:
            raise ValueError(f'Parent changed: {name}')
    decoder = RomanDecoder()
    validation_prompts = set()
    for original in iter_rows(args.source / 'val.jsonl'):
        row, _ = curate(original, decoder)
        if row is not None and row['task'] == 'repair_qwerty':
            validation_prompts.add(prompt_key(row))
    targets, conflicts = {}, set()
    for original in iter_rows(args.source / 'train.jsonl'):
        row, _ = curate(original, decoder)
        if row is None or row['task'] != 'repair_qwerty':
            continue
        key, value = prompt_key(row), target_key(row)
        if key in targets and targets[key] != value:
            conflicts.add(key)
        targets[key] = value
    args.out.mkdir(parents=True)
    counts = Counter()
    for partition in ('train', 'val'):
        seen = set()
        with (args.out / f'{partition}.jsonl').open('x', encoding='utf-8') as output, (args.out / f'{partition}-quarantine.jsonl').open('x', encoding='utf-8') as quarantine:
            for original in iter_rows(args.source / f'{partition}.jsonl'):
                row, reason = curate(original, decoder)
                if reason:
                    counts[f'{partition}_{reason}'] += 1
                if row is None:
                    quarantine.write(json.dumps({'reason': reason, 'row': original}, ensure_ascii=False) + '\n')
                    continue
                if partition == 'train' and row['task'] == 'repair_qwerty':
                    key = prompt_key(row)
                    if key in validation_prompts:
                        counts['train_validation_prompt_excluded'] += 1
                        continue
                    if key in conflicts:
                        row = {**row, 'target': {'action': 'abstain', 'text': ''},
                               'conflicting_target_before_abstain': row['target']}
                        counts['train_conflict_to_abstain'] += 1
                # A label change can merge previously distinct examples.
                signature = (prompt_key(row), target_key(row))
                if signature in seen:
                    counts[f'{partition}_duplicate_removed'] += 1
                    continue
                seen.add(signature)
                validate_row(row)
                output.write(json.dumps(row, ensure_ascii=False) + '\n')
                counts[f'{partition}_{row["task"]}'] += 1
    shutil.copy2(args.source / 'sources.json', args.out / 'sources.json')
    shutil.copytree(args.source / 'source-evidence', args.out / 'source-evidence')
    retain_decoder_evidence(args.out)
    shutil.copy2(Path(__file__), args.out / 'source-evidence/curation-builder.py')
    write_json(args.out / 'manifest.json', {
        'schema': 'zerocon-origin-v1', 'operation': 'offline conservative repair label curation',
        'parent': str(args.source), 'parent_manifest_sha256': digest(args.source / 'manifest.json'),
        'parent_output_hashes': parent['output_hashes'], 'builder_sha256': digest(Path(__file__)),
        'decoder_source_sha256': digest(Path(__file__).resolve().parents[1] / 'benchmarks/research/keyboard_hybrid.py'),
        'decoder_table_sha256': digest(TABLE), 'counts': dict(counts), 'conflicting_training_prompts': len(conflicts),
        'output_hashes': {name: digest(args.out / name) for name in ('train.jsonl', 'val.jsonl', 'sources.json')},
        'quarantine_hashes': {f'{p}-quarantine.jsonl': digest(args.out / f'{p}-quarantine.jsonl') for p in ('train', 'val')},
        'partition_policy': 'No source documents moved or imported; train excludes surviving validation repair prompts',
        'limitations': ['Offline simplified decoder; not a TSF state oracle',
                       'Validation labels/subset changed: compare models on this same new version only',
                       'Synthetic noise and automatic orthographic readings remain',
                       'Sealed documents never imported or decoded for label selection']})
    print(json.dumps(dict(counts), indent=2), flush=True)


if __name__ == '__main__':
    main()
