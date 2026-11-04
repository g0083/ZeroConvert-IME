# SPDX-License-Identifier: Apache-2.0
"""Measure anchor loss with identical initial weights and student dropout only."""
import argparse
import copy
import json
from pathlib import Path

import torch
from transformers import AutoTokenizer, set_seed

from build_v4_unified_dataset import rows
from origin_contract import digest, write_json
from train_v4_unified import create_models, loss_for
from v4_unified_contract import V4UnifiedCodec, legacy_example


@torch.no_grad()
def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--codec', type=Path, default=Path('artifacts/checkpoints/v4-unified-codec-compact-surface-20261029-r1'))
    p.add_argument('--dataset', type=Path, default=Path('artifacts/datasets/v4-unified-compact-surface-literals-20261029-r1'))
    a = p.parse_args()
    if a.out.exists():
        raise FileExistsError(a.out)
    torch.set_num_threads(4)
    set_seed(42)
    parent = Path('artifacts/checkpoints/standard-v4-nano-transfer-merged')
    codec = V4UnifiedCodec(a.codec)
    old = AutoTokenizer.from_pretrained(parent, local_files_only=True)
    examples = []
    for row in rows(a.dataset / 'val.jsonl'):
        if row['task'] != 'convert':
            continue
        e = legacy_example(row, old)
        if len(e['input_ids']) > 256:
            continue
        e['teacher'] = copy.deepcopy(e)
        examples.append(e)
        if len(examples) == 8:
            break
    model, teacher = create_models(parent, codec, 16, 'cuda', adapter_kind='lora', adapter_dropout=.05)
    # Recreate the old mismatch explicitly; current anchored construction turns
    # every student dropout off by default.
    for name, module in model.named_modules():
        if isinstance(module, torch.nn.Dropout):
            module.p = .05 if 'lora_dropout' in name else .1
    dropout = [{k: float(module.p)} for k, module in model.named_modules() if isinstance(module, torch.nn.Dropout) and module.p]
    measurements = {}
    for mode in ('student_dropout_on', 'student_dropout_off'):
        if mode.endswith('_off'):
            for module in model.modules():
                if isinstance(module, torch.nn.Dropout):
                    module.p = 0.
        measurements[mode] = [loss_for(model, teacher, examples, 'cuda', 1.)[1]['kl'] for _ in range(3)]
    write_json(a.out, {'status': 'measured; no optimization performed', 'measurements': measurements,
                       'dropout_modules': dropout, 'examples': len(examples), 'initial_parameters': 'identical parent, zero LoRA updates',
                       'parent_sha256': digest(parent / 'model.safetensors'), 'code_sha256': digest(__file__),
                       'limits': 'Demonstrates stochastic anchor mismatch; not a causal attribution of every conversion regression'})
    print(json.dumps(measurements), flush=True)


if __name__ == '__main__':
    main()
