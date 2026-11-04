"""Rebind unchanged development probes and append split-independent mixed validation."""
import json
from pathlib import Path
from build_v4_unified_dataset import rows
from origin_contract import digest, write_json


def main():
    dataset = Path('artifacts/datasets/v4-unified-mixed-preservation-20261030-r7')
    source = Path('artifacts/results/v4-unified-surface-generation-selection-20261029-r4.jsonl')
    out = Path('artifacts/results/v4-unified-generation-selection-20261030-r7.jsonl')
    if out.exists():
        raise FileExistsError(out)
    cases = list(rows(source))
    mixed = [r for r in rows(dataset / 'val.jsonl') if r['source_id'] == 'synthetic-mixed-preservation']
    for row in mixed[:120]:
        cases.append({**row, 'selection_group': 'literal_canary', 'quality_canary': True})
    out.write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in cases), encoding='utf-8')
    meta = json.loads(source.with_suffix('.manifest.json').read_text(encoding='utf-8'))
    write_json(out.with_suffix('.manifest.json'), {**meta, 'dataset_manifest_sha256': digest(dataset/'manifest.json'),
        'probe_sha256': digest(out), 'source': 'Inherited development429 +120 mixed validation with separate suffix templates',
        'inherited_probe_sha256': digest(source), 'counts': {'inherited': len(cases)-120, 'new_mixed': 120},
        'code_sha256': digest(__file__)})
    print(f'{len(cases)} validation probes', flush=True)


if __name__ == '__main__':
    main()
