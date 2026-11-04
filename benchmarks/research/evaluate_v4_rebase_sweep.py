# SPDX-License-Identifier: Apache-2.0
"""Validation-only interpolation diagnostic; no weights or fixed test cases changed."""
import argparse
import json
from pathlib import Path

import torch
from transformers import AutoModelForCausalLM

from build_v4_unified_dataset import rows
from origin_contract import digest, write_json
from v4_generation_selection import assess
from v4_unified_contract import V4UnifiedCodec


@torch.no_grad()
def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--probe', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    a = p.parse_args()
    if a.out.exists():
        raise FileExistsError(a.out)
    torch.set_num_threads(4)
    manifest = json.loads((a.run / 'run-manifest.json').read_text(encoding='utf-8'))
    if digest(a.probe) != manifest['generation_validation_sha256']:
        raise ValueError('Use only the run validation selection probe')
    parent_dir = Path(manifest['arguments']['parent'])
    parent = AutoModelForCausalLM.from_pretrained(parent_dir, local_files_only=True, dtype=torch.float32)
    student = AutoModelForCausalLM.from_pretrained(a.run/'hf', local_files_only=True, dtype=torch.float32,
                                                 attn_implementation='sdpa')
    parent_weights = dict(parent.named_parameters())
    changed = {name: parameter.detach().clone() for name, parameter in student.named_parameters()
               if parameter.shape == parent_weights[name].shape and not torch.equal(parameter, parent_weights[name])}
    student.to('cuda')
    codec, cases = V4UnifiedCodec(a.run / 'hf'), list(rows(a.probe))
    results = {}
    for coefficient in (1., .95, .85):
        for name, parameter in student.named_parameters():
            if name in changed:
                parameter.copy_(changed[name].to('cuda') * coefficient + parent_weights[name].to('cuda') * (1-coefficient))
        measured = assess(student, codec, cases, 'cuda')
        results[str(coefficient)] = {'summary': measured['summary'], 'key': measured['key']}
        print(json.dumps({'coefficient': coefficient, **measured['summary']}), flush=True)
    control = results['1.0']['summary']
    acceptable = []
    for coefficient in (.95, .85):
        measured = results[str(coefficient)]['summary']
        if (measured['convert_parent_lost'] < control['convert_parent_lost'] and
                all(measured[k] <= control[k] for k in ('literal_failed', 'invalid', 'normal_changed')) and
                all(measured[k] >= control[k] for k in ('repair_correct', 'convert_exact', 'predict_exact'))):
            acceptable.append(coefficient)
    recommended = min(acceptable, key=lambda c: results[str(c)]['key']) if acceptable else 1.
    write_json(a.out, {'status': 'validation diagnostic only; no derived weights saved', 'results': results,
                       'recommended_coefficient': recommended,
                       'rule': 'Only accept strictly fewer parent-correct losses with no regression in other generated validation counts',
                       'hf_sha256': digest(a.run/'hf/model.safetensors'), 'parent_sha256': digest(parent_dir/'model.safetensors'),
                       'probe_sha256': digest(a.probe), 'code_sha256': digest(__file__),
                       'limits': ['Development selection, not statistical proof or sealed testing', '480 fixed evaluation not consulted']})


if __name__ == '__main__':
    main()
