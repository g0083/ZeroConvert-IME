# SPDX-License-Identifier: Apache-2.0
"""Preserve audited three-task labels, expose observed readings via compact codec."""
import argparse
from collections import Counter, defaultdict
import copy
import json
from pathlib import Path
import shutil

from build_v4_unified_dataset import rows, isolation_keys, benchmark_collision
from dataset_isolation import evaluation_keys
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec


def build(parent, codec_path, observation_codec_path, out, max_length=512, matched=None):
    if out.exists():
        raise FileExistsError(out)
    pm = json.loads((parent / 'manifest.json').read_text(encoding='utf-8'))
    for name, expected in pm['output_hashes'].items():
        if digest(parent / name) != expected:
            raise ValueError('Parent data changed')
    codec, observer = V4UnifiedCodec(codec_path), V4UnifiedCodec(observation_codec_path)
    if not codec.metadata.get('compact') or codec.metadata.get('repair_input') != 'observed':
        raise ValueError('Expected compact observed-reading repair contract')
    surface_mode = codec.metadata['repair_output'] == 'surface'
    reserved = evaluation_keys()
    matched_parts = None
    if matched:
        from audit_v4_repair_comparison import audit
        audit(matched.parent)
        matched_parts = {s: list(rows(matched / f'{s}.jsonl')) for s in ('train', 'val')}
    parts, counts, teachers = {}, Counter(), defaultdict(set)
    for split in ('val', 'train'):
        selected = []
        for old in rows(parent / f'{split}.jsonl'):
            row = copy.deepcopy(old)
            if row['task'] == 'repair':
                raw = row['input']
                row['raw_observation'] = dict(raw)
                row['input'] = {'ctx': raw['ctx'], 'reading': observer.observed_reading(raw['raw'], raw['pending']),
                                'pending': raw['pending']}
                row['reference_surface'] = row['source_text'] if row['source_id'] == 'corrector-qwerty-replay' and row['category'] != 'complete-prefix-during-edit' else None
                if surface_mode and row['target']['action'] == 'emit':
                    if row['reference_surface'] is None:
                        counts[split + '_unverified_surface_emit_excluded'] += 1
                        continue
                    row['target']['text'] = row['reference_surface']
            selected.append(row)
        if matched_parts:
            for old in matched_parts[split]:
                row = {**copy.deepcopy(old), 'id': 'observed-legacy-' + old['id'],
                       'document_id': 'corrector-' + old['source_group'],
                       'source_id': 'corrector-observed-replay', 'source_text': old['reference_surface'],
                       'quality': 'Original observed corrector input; no reconstructed raw'}
                selected.append(row)
                if row['target']['action'] == 'keep' and len(row['input']['reading']) >= 5:
                    prefix = copy.deepcopy(row)
                    prefix['id'] += '-deletion-prefix'
                    prefix['input']['reading'] = row['input']['reading'][:-1]
                    prefix['reference_reading'] = prefix['input']['reading']
                    prefix['reference_surface'] = None
                    prefix['category'] = 'observed-deletion-prefix'
                    if not benchmark_collision(prefix, reserved):
                        selected.append(prefix)
        deduplicated, seen = [], set()
        for row in selected:
            prompt = tuple(codec.prompt(row))
            target = json.dumps(row['target'], ensure_ascii=False, sort_keys=True)
            teachers[prompt].add(target)
            example = codec.example(row, max_length)
            if codec.decode_answer(codec.answer(row), row['task']) != row['target']:
                raise ValueError('Changed target')
            if (prompt, target) in seen:
                counts[split + '_duplicate_observations_removed'] += 1
                continue
            seen.add((prompt, target))
            deduplicated.append(row)
        parts[split] = deduplicated
    blocked_evaluation_docs = {r['document_id'] for selected in parts.values() for r in selected
                               if benchmark_collision(r, reserved)}
    for split in parts:
        before = len(parts[split])
        parts[split] = [r for r in parts[split] if r['document_id'] not in blocked_evaluation_docs]
        counts[split + '_evaluation_touching_document_rows_removed'] = before - len(parts[split])
    bad = {k for k, v in teachers.items() if len(v) > 1}
    # Remove identical-observation contradictions without relabeling either side.
    for split in parts:
        before = len(parts[split])
        parts[split] = [r for r in parts[split] if tuple(codec.prompt(r)) not in bad]
        counts[split + '_conflicting_observation_rows_removed'] = before - len(parts[split])
    val_keys = set().union(*(isolation_keys(r) for r in parts['val']))
    blocked_docs = {r['document_id'] for r in parts['train'] if isolation_keys(r) & val_keys}
    before = len(parts['train'])
    parts['train'] = [r for r in parts['train'] if r['document_id'] not in blocked_docs]
    counts['train_cross_split_document_rows_removed'] = before - len(parts['train'])
    out.mkdir(parents=True)
    for split, selected in parts.items():
        counts.update({f'{split}_{k}': v for k, v in Counter(r['task'] + '_' + r['target']['action'] for r in selected).items()})
        (out / f'{split}.jsonl').write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in selected), encoding='utf-8')
    shutil.copy2(parent / 'sources.json', out / 'sources.json')
    shutil.copytree(parent / 'source-evidence', out / 'source-evidence')
    shutil.copy2(__file__, out / 'source-evidence/build_v4_observed_unified.py')
    if matched:
        shutil.copy2(matched.parent / 'manifest.json', out / 'source-evidence/matched-corrector-manifest.json')
    manifest = {'schema': 'zerocon-v4-unified-dataset-v2', 'arguments': {'max_length': max_length},
                'operation': 'fixed observed decoder and compact prompts; matched observed replay; validated surface labels where available',
                'repair_output': codec.metadata['repair_output'],
                'repair_groups': 'corrector suffix = synthetic raw replay; base = original observed corrector plus other retained data',
                'parent': str(parent), 'parent_manifest_sha256': digest(parent / 'manifest.json'),
                'matched': str(matched) if matched else None,
                'matched_manifest_sha256': digest(matched.parent / 'manifest.json') if matched else None,
                'codec_sha256': digest(codec_path / 'v4-unified-codec.json'),
                'observation_codec_sha256': digest(observation_codec_path / 'v4-unified-codec.json'),
                'counts': dict(counts), 'output_hashes': {n: digest(out / n) for n in ('train.jsonl', 'val.jsonl', 'sources.json')},
                'code_sha256': digest(__file__), 'publication_status': 'local research; inherited data provenance',
                'limits': ['Observed reading derived exclusively from raw/pending', 'Preserved synthetic development partitions; not sealed']}
    write_json(out / 'manifest.json', manifest)
    return manifest


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--parent', type=Path, default=Path('artifacts/datasets/v4-unified-romaji-observed-20261029-r3'))
    p.add_argument('--codec', type=Path, required=True)
    p.add_argument('--observation-codec', type=Path,
                   default=Path('artifacts/checkpoints/zerocon-v4-unified-standard-qwerty-repair-bootstrap-20261029-r5/hf'))
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--matched', type=Path, help='Audited reading or surface condition directory')
    a = p.parse_args()
    print(json.dumps(build(a.parent, a.codec, a.observation_codec, a.out, matched=a.matched), indent=2))
