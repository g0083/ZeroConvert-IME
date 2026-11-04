# SPDX-License-Identifier: Apache-2.0
"""Inspect the first divergent token before/after FP16 weight rounding in RAM."""
import argparse
import json
from pathlib import Path
import sys

from origin_contract import OriginCodec, digest, write_json


def next_logits(model, prompt, prefix, torch):
    output = model(input_ids=torch.tensor([prompt]), use_cache=True)
    for token in prefix:
        output = model(input_ids=torch.tensor([[token]]), past_key_values=output.past_key_values, use_cache=True)
    return output.logits[0, -1].float().clone()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--gguf', type=Path, required=True, help='F16 matrix/F32 norm export being compared')
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    reference = json.loads(args.reference.read_text(encoding='utf-8'))
    candidate = json.loads(args.candidate.read_text(encoding='utf-8'))
    identity = digest(args.run / 'run-manifest.json')
    if reference['manifest_sha256'] != identity or candidate['run_manifest_sha256'] != identity:
        raise ValueError('Checkpoint identity differs')
    if candidate['gguf_sha256'] != digest(args.gguf):
        raise ValueError('Candidate GGUF identity differs')
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'android/app/src/main/cpp/llama.cpp/gguf-py'))
    import gguf
    reader = gguf.GGUFReader(str(args.gguf))
    if any((len(t.shape) == 2 and t.tensor_type.name != 'F16') or
           (len(t.shape) == 1 and t.tensor_type.name != 'F32') for t in reader.tensors):
        raise ValueError('Rounding diagnostic requires F16 matrices and unchanged F32 one-dimensional tensors')
    codec = OriginCodec(args.run / 'tokenizer.model')
    import torch
    from transformers import AutoModelForCausalLM
    torch.set_num_threads(4)
    model = AutoModelForCausalLM.from_pretrained(args.run / 'hf', local_files_only=True, trust_remote_code=False).eval()
    if any(p.dtype != torch.float32 for p in model.parameters()):
        raise ValueError('This diagnostic requires original FP32 source parameters')
    reference_rows = {r['id']: r for r in reference['cases']}
    selected = []
    for row in candidate['cases']:
        original = reference_rows[row['id']]
        expected, actual = original['tokens'], row['tokens']
        if expected == actual:
            continue
        index = next((i for i, (a, b) in enumerate(zip(expected, actual)) if a != b), min(len(expected), len(actual)))
        if index == min(len(expected), len(actual)):
            raise ValueError('Unexpected prefix-only token difference; inspect the EOS contract')
        selected.append((original, expected[:index], expected[index], actual[index], index))
    records = []
    with torch.inference_mode():
        for original, prefix, expected, actual, index in selected:
            logits = next_logits(model, codec.prompt({'task': original['task'], 'input': original['input']}), prefix, torch)
            records.append({'id': original['id'], 'task': original['task'], 'first_divergent_position': index,
                            'hf_next_id': expected, 'gguf_next_id': actual,
                            'fp32_argmax': int(logits.argmax()),
                            'fp32_hf_minus_gguf_logit_margin': float(logits[expected] - logits[actual])})
        for parameter in model.parameters():
            if parameter.ndim == 2:
                parameter.copy_(parameter.to(torch.float16).to(torch.float32))
        for record, (original, prefix, expected, actual, _) in zip(records, selected):
            logits = next_logits(model, codec.prompt({'task': original['task'], 'input': original['input']}), prefix, torch)
            record.update(fp16_rounded_weights_argmax=int(logits.argmax()),
                          fp16_rounded_hf_minus_gguf_logit_margin=float(logits[expected] - logits[actual]),
                          weight_rounding_alone_reproduces_next_token=int(logits.argmax()) == actual)
    result = {'run_manifest_sha256': identity, 'reference_sha256': digest(args.reference),
              'candidate_sha256': digest(args.candidate), 'code_sha256': digest(Path(__file__)), 'cases': records,
              'gguf_sha256': digest(args.gguf),
              'rounding_policy': 'Only two-dimensional matrices rounded to F16; norm/scalar tensors remain F32, matching inspected GGUF',
              'limits': ['Diagnostic model weights rounded in RAM only; saved HF weights are unchanged',
                         'A local rounding explanation is not proof that every runtime difference is benign',
                         'Check original-precision GGUF separately; no quality gate is waived']}
    write_json(args.out, result)
    print(json.dumps(records, indent=2))


if __name__ == '__main__':
    main()
