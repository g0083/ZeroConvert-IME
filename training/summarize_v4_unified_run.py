# SPDX-License-Identifier: Apache-2.0
"""Replay the owned deterministic sampler to count processed/target tokens."""
import argparse
from collections import Counter
import json
from pathlib import Path
import random

from transformers import AutoTokenizer

from origin_contract import digest, write_json
from train_v4_unified import prepare, sample_batch
from v4_unified_contract import V4UnifiedCodec


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run', type=Path, required=True)
    p.add_argument('--out', type=Path, required=True)
    args = p.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    manifest = json.loads((args.run / 'run-manifest.json').read_text(encoding='utf-8'))
    settings = manifest['arguments']
    codec = V4UnifiedCodec(Path(settings['codec']))
    old = AutoTokenizer.from_pretrained(settings['parent'], local_files_only=True)
    examples, pools, _ = prepare(Path(settings['dataset']), codec, old, settings['max_length'])
    rng = random.Random(settings['seed'])
    counts, unique = Counter(), set()
    metrics = json.loads((args.run / 'metrics.json').read_text(encoding='utf-8'))
    selected_step = metrics['best']['step']
    selected_counts, selected_unique = None, None
    for step in range(1, metrics['steps'] + 1):
        for example in sample_batch(examples['train'], pools['train'], rng, settings['effective_batch'], manifest['task_weights']):
            counts['processed_tokens'] += len(example['input_ids'])
            counts['target_tokens'] += sum(x != -100 for x in example['labels'])
            counts['sampled_' + example['group']] += 1
            unique.add(id(example))
        if step == selected_step:
            selected_counts, selected_unique = dict(counts), len(unique)
    if counts['processed_tokens'] != metrics['processed_tokens']:
        raise ValueError('Sampler replay differs from training record')
    write_json(args.out, {'status': 'PASS', 'counts': dict(counts), 'unique_tokenized_examples_sampled': len(unique),
        'selected_checkpoint_step': selected_step, 'selected_checkpoint_counts': selected_counts,
        'selected_checkpoint_unique_examples': selected_unique,
        'run_manifest_sha256': digest(args.run / 'run-manifest.json'), 'code_sha256': digest(__file__),
        'meaning': 'repeated training tokens and sequence count, not independent source document count'})
    print(json.dumps(dict(counts), indent=2))


if __name__ == '__main__':
    main()
