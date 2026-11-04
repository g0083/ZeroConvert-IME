# SPDX-License-Identifier: Apache-2.0
"""Run an isolated research model without connecting to or changing the IME."""
import argparse
import json
from pathlib import Path
import time

import torch
from transformers import AutoModelForCausalLM

from evaluate_v4_unified import UnifiedServer, hf_generate
from v4_unified_contract import V4UnifiedCodec, hira


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--codec', type=Path, required=True, help='HF or exported directory containing codec/tokenizer')
    p.add_argument('--gguf', type=Path)
    p.add_argument('--task', choices=('convert', 'repair', 'predict'), required=True)
    p.add_argument('--ctx', default='')
    p.add_argument('--reading', default='')
    p.add_argument('--raw', default='')
    p.add_argument('--surface', default='')
    p.add_argument('--pending', default='')
    p.add_argument('--device', choices=('cpu', 'cuda'), default='cpu')
    p.add_argument('--port', type=int, default=8567)
    args = p.parse_args()
    if args.task in ('repair', 'predict') and args.pending:
        print(json.dumps({'action': 'abstain', 'text': '', 'reason': 'pending input', 'automatic_adoption': False}, ensure_ascii=False))
        return
    codec = V4UnifiedCodec(args.codec)
    observed = None
    inputs = {'ctx': args.ctx}
    if args.task == 'convert':
        if not args.reading:
            p.error('--reading is required for convert')
        inputs['reading'] = args.reading
    elif args.task == 'repair':
        if codec.metadata.get('repair_input') == 'observed':
            if args.reading:
                observed = hira(args.reading)
                if args.raw and codec.observation_mapping is not None and observed != codec.observed_reading(args.raw, args.pending):
                    p.error('--raw and observed --reading disagree under the bundled decoder')
            elif args.raw and codec.observation_mapping is not None:
                observed = codec.observed_reading(args.raw, args.pending)
            else:
                p.error('Observed repair needs --reading, or --raw with a bundled observation decoder')
            inputs.update(reading=observed, pending=args.pending)
        else:
            if not args.raw:
                p.error('--raw is required for repair')
            inputs.update(raw=args.raw, pending=args.pending)
            if codec.observation_mapping is not None:
                observed = codec.observed_reading(args.raw, args.pending)
    else:
        if not args.surface:
            p.error('--surface is required for predict')
        inputs.update(surface=args.surface, pending=args.pending)
    prompt = codec.prompt({'task': args.task, 'input_method': 'romaji', 'layout': 'JP-QWERTY', 'input': inputs})
    if args.gguf:
        server = UnifiedServer(str(args.gguf.resolve()), args.port, 0, 'cpu')
        try:
            server.start()
            started = time.perf_counter()
            tokens = server.complete_ids(prompt, codec, 96)
            elapsed = time.perf_counter()-started
        finally:
            server.stop()
    else:
        torch.set_num_threads(4)
        model = AutoModelForCausalLM.from_pretrained(args.codec, local_files_only=True,
            dtype=torch.float32, attn_implementation='sdpa').to(args.device).eval()
        started = time.perf_counter()
        tokens = hf_generate(model, [prompt], 96, args.device)[0]
        elapsed = time.perf_counter()-started
    answer = codec.decode_answer(tokens, args.task)
    repair_kind = codec.metadata.get('repair_output', 'reading')
    if args.task == 'repair' and repair_kind == 'reading' and answer['action'] == 'emit' and any(
            not ('ぁ' <= c <= 'ゖ' or c in 'ーゝゞ') for c in answer['text']):
        answer = {'action': 'abstain', 'text': '', 'reason': 'non-reading repair output'}
    if (args.task == 'repair' and answer['action'] == 'emit' and observed is not None and answer['text'] == observed):
        answer = {'action': 'keep', 'text': '', 'reason': 'observed reading unchanged'}
    if answer['action'] == 'invalid':
        answer = {**answer, 'action': 'abstain'}
    print(json.dumps({**answer, 'generation_ms': elapsed*1000, 'automatic_adoption': False,
                      **({'repair_output': repair_kind, 'reading_verification_required': repair_kind == 'surface' and answer['action'] == 'emit'}
                         if args.task == 'repair' else {})}, ensure_ascii=False))


if __name__ == '__main__':
    main()
