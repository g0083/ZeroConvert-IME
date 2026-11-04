# SPDX-License-Identifier: Apache-2.0
"""Matched observed-input repair labels; original keys are never reconstructed."""
import argparse
from collections import Counter, defaultdict
import copy
import json
from pathlib import Path
import random

from dataset_isolation import EVALUATION_FILES, evaluation_keys, load_jsonl, normalize, source_keys, validate_rows
from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec, build_tokenizer, hira


def verified_reading(value):
    return bool(value) and all('ぁ' <= c <= 'ゖ' or c in 'ーゝゞ' for c in value)


def paired_rows(parts, reserved):
    """Exclude complete source families before selecting either output condition."""
    blocked = {r['source_group'] for rs in parts.values() for r in rs if source_keys(r) & reserved}
    result, counts = {}, Counter()
    for split, source in parts.items():
        selected, originals, seen = [], [], set()
        for old in source:
            if old['source_group'] in blocked:
                counts[f'{split}_blocked_family_rows'] += 1
                continue
            canonical = hira(old.get('target_reading', ''))
            observed = hira(old['prompt'].split('\uee00')[1].split('\uee01')[0])
            ctx = old['prompt'].split('\uee00')[0].removeprefix('\uee02')
            if not verified_reading(canonical) or not observed or not all(
                'ぁ' <= c <= 'ゖ' or c in 'ーゝゞ、。！？!?・' or c.isascii() for c in observed):
                counts[f'{split}_unverified_reading_rows'] += 1
                continue
            clean = normalize(observed) == normalize(canonical)
            if old.get('is_clean') and not clean:
                counts[f'{split}_ambiguous_clean_rows'] += 1
                continue
            key = ctx, observed, canonical, old['completion']
            if key in seen:
                continue
            seen.add(key)
            row = {'id': f'{split}-{len(selected)}', 'task': 'repair', 'input_method': 'romaji',
                   'layout': 'JP-QWERTY', 'input': {'ctx': ctx, 'reading': observed, 'pending': ''},
                   'target': {'action': 'keep' if clean else 'emit', 'text': '' if clean else canonical},
                   'reference_reading': canonical, 'reference_surface': old['completion'],
                   'source_group': old['source_group'], 'category': old.get('typo_type', 'unknown'),
                   'provenance': {'source_split': split, 'source_id': old.get('id'),
                                  'raw': 'unavailable; original observed input retained without reconstruction'}}
            selected.append(row)
            originals.append(old)
        # Explicit normal readings protect real words and kana-only inputs. They
        # are observed-reading examples, never claimed to contain original keys.
        normal_seen = {(r['input']['ctx'], r['input']['reading']) for r in selected if r['target']['action'] == 'keep'}
        for row in list(selected):
            key = row['input']['ctx'], row['reference_reading']
            if key not in normal_seen:
                normal_seen.add(key)
                normal = copy.deepcopy(row)
                normal['id'] = f'{split}-normal-{len(normal_seen)}'
                normal['input']['reading'] = row['reference_reading']
                normal['target'] = {'action': 'keep', 'text': ''}
                normal['category'] = 'verified-canonical-normal'
                selected.append(normal)
        result[split] = selected
        result[split + '_originals'] = originals
    observations = defaultdict(set)
    for split in ('train', 'val'):
        for row in result[split]:
            key = row['input']['ctx'], row['input']['reading']
            answer = ('keep',) if row['target']['action'] == 'keep' else ('emit', row['reference_reading'], row['reference_surface'])
            observations[key].add(answer)
    for split in ('train', 'val'):
        clean = [r for r in result[split] if len(observations[(r['input']['ctx'], r['input']['reading'])]) == 1]
        counts[f'{split}_conflicting_observations_removed'] = len(result[split]) - len(clean)
        result[split] = clean
        counts[f'{split}_selected'] = len(clean)
        counts.update({f'{split}_{k}': v for k, v in Counter(r['target']['action'] for r in clean).items()})
    validate_rows(result['train_originals'], result['val_originals'], reserved)
    return result, dict(counts)


def write_rows(path, rows):
    path.write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows), encoding='utf-8')


def build(source, parent, out, max_length=256):
    if out.exists():
        raise FileExistsError(out)
    source_parts = {s: load_jsonl(source / f'{s}.jsonl') for s in ('train', 'val')}
    selected, counts = paired_rows(source_parts, evaluation_keys())
    out.mkdir(parents=True)
    codecs = {mode: build_tokenizer(parent, out / mode / 'codec', repair_emit_marker=True,
                                   compact=True, repair_input='observed', repair_output=mode)
              for mode in ('reading', 'surface')}
    parts = {s: [] for s in ('train', 'val')}
    for split in parts:
        for row in selected[split]:
            variants = {}
            for mode, codec in codecs.items():
                variant = copy.deepcopy(row)
                if mode == 'surface' and variant['target']['action'] == 'emit':
                    variant['target']['text'] = row['reference_surface']
                variants[mode] = variant
            if any(len(codecs[m].prompt(r)) + len(codecs[m].answer(r)) > max_length for m, r in variants.items()):
                counts[f'{split}_paired_overlength_excluded'] = counts.get(f'{split}_paired_overlength_excluded', 0) + 1
                continue
            if codecs['reading'].prompt(variants['reading']) != codecs['surface'].prompt(variants['surface']):
                raise ValueError('Input conditions differ')
            parts[split].append(variants)
    manifest = {'schema': 'v4-repair-matched-data-v1', 'max_length': max_length, 'counts': counts,
                'sources': {str(source / f'{s}.jsonl'): digest(source / f'{s}.jsonl') for s in ('train', 'val')},
                'evaluation_hashes': {str(p): digest(p) for p in EVALUATION_FILES},
                'code_sha256': digest(__file__), 'conditions': {},
                'limits': ['Observed mixed-kana inputs; original raw keystrokes unavailable',
                           'Existing development partitions, not sealed evaluation',
                           'Verified kana-only reading subset; not the entire 40,111-row replay',
                           'Local research; original source distribution terms retained']}
    for mode, codec in codecs.items():
        folder = out / mode
        hashes = {}
        for split, items in parts.items():
            rows = [v[mode] for v in items]
            write_rows(folder / f'{split}.jsonl', rows)
            hashes[f'{split}.jsonl'] = digest(folder / f'{split}.jsonl')
        buckets = defaultdict(list)
        for v in parts['val']:
            buckets[v[mode]['target']['action']].append(v[mode])
        probe = []
        for key in sorted(buckets):
            probe.extend(random.Random(113).sample(buckets[key], min(100, len(buckets[key]))))
        write_rows(folder / 'validation-generation.jsonl', probe)
        hashes['validation-generation.jsonl'] = digest(folder / 'validation-generation.jsonl')
        manifest['conditions'][mode] = {'files': hashes, 'codec_sha256': digest(folder / 'codec/v4-unified-codec.json'),
                                      'rows': {s: len(v) for s, v in parts.items()}}
    write_json(out / 'manifest.json', manifest)
    return manifest


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', type=Path, default=Path('artifacts/datasets/corrector-v2-20261027'))
    p.add_argument('--parent', type=Path, default=Path('artifacts/checkpoints/standard-v4-nano-transfer-merged'))
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--max-length', type=int, default=256)
    a = p.parse_args()
    print(json.dumps(build(a.source, a.parent, a.out, a.max_length), indent=2))
