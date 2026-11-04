# SPDX-License-Identifier: Apache-2.0
"""Shrink a diagnostic transformer delta toward immutable v4; retain learned new rows."""
import argparse
import json
from pathlib import Path
import shutil

import torch
from transformers import AutoModelForCausalLM

from origin_contract import digest, write_json
from v4_unified_contract import BASE_VOCAB, V4UnifiedCodec


@torch.no_grad()
def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--parent', type=Path, default=Path('artifacts/checkpoints/standard-v4-nano-transfer-merged'))
    p.add_argument('--student', type=Path, required=True, help='Owned HF directory')
    p.add_argument('--coefficient', type=float, default=.25)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    if a.out.exists() or not 0 <= a.coefficient <= 1:
        raise ValueError('New output and coefficient in [0,1] required')
    codec = V4UnifiedCodec(a.student)
    parent = AutoModelForCausalLM.from_pretrained(a.parent, local_files_only=True, dtype=torch.float32)
    student = AutoModelForCausalLM.from_pretrained(a.student, local_files_only=True, dtype=torch.float32)
    if not torch.equal(student.get_input_embeddings().weight[:BASE_VOCAB], parent.get_input_embeddings().weight):
        raise ValueError('Expected frozen original embedding/head rows')
    original = dict(parent.named_parameters())
    modified = []
    for name, parameter in student.named_parameters():
        value = original[name]
        if parameter.shape != value.shape:
            if name != 'transformer.wte.weight' or parameter.shape[0] != len(codec.tokenizer):
                raise ValueError('Unexpected shape difference')
            continue
        if not torch.equal(parameter, value):
            parameter.mul_(a.coefficient).add_(value, alpha=1-a.coefficient)
            modified.append(name)
    if not torch.equal(student.get_input_embeddings().weight[:BASE_VOCAB], parent.get_input_embeddings().weight):
        raise ValueError('Original rows changed during rebase')
    a.out.mkdir(parents=True)
    hf = a.out / 'hf'
    student.save_pretrained(hf, safe_serialization=True)
    for source in a.student.iterdir():
        if source.is_file() and source.suffix != '.safetensors' and source.name not in ('config.json', 'generation_config.json'):
            shutil.copy2(source, hf / source.name)
    write_json(hf / 'initialization-provenance.json', {
        'status': 'initialization only; not selected, trained or deployed',
        'parent': str(a.parent), 'student': str(a.student), 'coefficient': a.coefficient,
        'formula': 'original v4 + coefficient * (bridge - original v4) for changed matching transformer tensors; learned appended rows retained',
        'parent_sha256': digest(a.parent / 'model.safetensors'), 'student_sha256': digest(a.student / 'model.safetensors'),
        'modified_tensors': modified, 'original_embedding_head_rows_unchanged': True,
        'hf_sha256': digest(hf / 'model.safetensors'), 'code_sha256': digest(__file__),
        'selection': 'Coefficient 0.25 set as a recovery experiment, not tuned on 480 evaluation cases'})
    print(f'Created initialization from {len(modified)} shrunk transformer tensors', flush=True)


if __name__ == '__main__':
    main()
