# SPDX-License-Identifier: Apache-2.0
"""Compare state values of two locally owned Origin training checkpoints.

Only use on checkpoints created by this project's trusted trainers. PyTorch
optimizer checkpoints use pickle and must not be loaded from external sources.
"""
import argparse
import json
from pathlib import Path

from origin_contract import digest, write_json


def equal_tree(left, right, torch):
    if isinstance(left, torch.Tensor):
        return isinstance(right, torch.Tensor) and left.dtype == right.dtype and torch.equal(left, right)
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(equal_tree(left[k], right[k], torch) for k in left)
    if isinstance(left, (list, tuple)):
        return len(left) == len(right) and all(equal_tree(a, b, torch) for a, b in zip(left, right))
    return left == right


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--resumed', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    manifests = []
    for path in (args.reference, args.resumed):
        manifest = json.loads((path.parent / 'run-manifest.json').read_text(encoding='utf-8'))
        if manifest.get('schema') != 'zerocon-origin-run-v1':
            raise ValueError('Not an owned Origin checkpoint run')
        manifests.append(manifest)
    import torch
    torch.set_num_threads(4)
    checkpoints = [torch.load(path, map_location='cpu', weights_only=False) for path in (args.reference, args.resumed)]
    for saved, manifest in zip(checkpoints, manifests):
        # Config dictionaries can have integer id2label keys in memory; JSON
        # stores those keys as strings. Compare the exact serialized meaning.
        if json.loads(json.dumps(saved['manifest'])) != manifest:
            raise ValueError('Checkpoint does not match the selected run manifest')
    identities = {key: manifests[0].get(key) == manifests[1].get(key) for key in (
        'dataset_hashes', 'tokenizer_sha256', 'token_cache_manifest_sha256', 'model_config', 'code_hashes', 'parent', 'device')}
    checks = {key: equal_tree(checkpoints[0][key], checkpoints[1][key], torch) for key in
              ('model', 'state', 'optimizer', 'scheduler', 'rng_cpu', 'rng_cuda')}
    passed = all(identities.values()) and all(checks.values())
    result = {'status': 'PASS' if passed else 'FAIL', 'identities': identities, 'exact_state_checks': checks,
              'reference_sha256': digest(args.reference), 'resumed_sha256': digest(args.resumed),
              'code_sha256': digest(Path(__file__)), 'step': checkpoints[0]['state']['step'],
              'processed_tokens': checkpoints[0]['state']['processed_tokens']}
    write_json(args.out, result)
    print(json.dumps(result, indent=2))
    if not passed:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
