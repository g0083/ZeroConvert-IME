# SPDX-License-Identifier: Apache-2.0
"""Materialize a stable best adapter snapshot without modifying the active run."""
import argparse
import json
from pathlib import Path
import shutil

from safetensors.torch import load_file
from transformers import set_seed

from origin_contract import digest, write_json
from train_v4_unified import create_models, export_hf
from v4_unified_contract import V4UnifiedCodec


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    manifest = json.loads((args.run/'run-manifest.json').read_text(encoding='utf-8'))
    settings = manifest['arguments']
    names = ('best-selection.json', 'best-adapter.safetensors', 'best-new-rows.safetensors')
    identities = {name: digest(args.run/name) for name in names}
    selection = json.loads((args.run/'best-selection.json').read_text(encoding='utf-8'))
    selected = {'adapter': load_file(str(args.run/'best-adapter.safetensors')),
                'new_rows': load_file(str(args.run/'best-new-rows.safetensors'))['new_rows']}
    if any(digest(args.run/name) != value for name,value in identities.items()):
        raise ValueError('Snapshot changed during read; retry with a fresh output')
    set_seed(settings['seed'])
    codec_path, parent = Path(settings['codec']), Path(settings['parent'])
    student = Path(settings['student_model']) if settings.get('student_model') else None
    model, teacher = create_models(parent, V4UnifiedCodec(codec_path), settings['rank'], 'cpu', student)
    del teacher
    args.out.mkdir(parents=True)
    export_hf(args.out, parent, codec_path, model, selected)
    shutil.copy2(args.run/'run-manifest.json', args.out/'run-manifest.json')
    write_json(args.out/'snapshot.json', {'source_run': str(args.run), 'selection': selection,
        'snapshot_hashes': identities, 'code_sha256': digest(__file__),
        'status': 'diagnostic snapshot; source run continues independently'})
    print(f'Materialized step {selection["step"]} -> {args.out}')


if __name__ == '__main__':
    main()
