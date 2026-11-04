# SPDX-License-Identifier: Apache-2.0
"""Export owned GPT-2 unified weights, retaining codec IDs and source notices.

The upstream pre-tokenizer detector hashes token IDs, which change on append-only
byte completion. This local subclass checks the actual ByteLevel/BPE configuration
and selects its existing gpt-2 implementation. Vendor code remains unchanged.
"""
import argparse
import json
import logging
from pathlib import Path
import shutil
import sys

from origin_contract import digest, write_json
from v4_unified_contract import V4UnifiedCodec


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--outtype', choices=('f32', 'f16'), default='f16')
    args = parser.parse_args()
    if args.out.exists():
        raise FileExistsError(args.out)
    metrics = json.loads((args.run / 'metrics.json').read_text(encoding='utf-8'))
    if metrics['status'] != 'training_completed':
        raise ValueError('Training did not complete')
    hf = args.run / 'hf'
    for name, expected in metrics['hf_hashes'].items():
        if digest(hf / name) != expected:
            raise ValueError('Trained HF changed')
    codec = V4UnifiedCodec(hf)
    spec = json.loads(codec.tokenizer.backend_tokenizer.to_str())
    expected_pre = {'type': 'ByteLevel', 'add_prefix_space': False, 'trim_offsets': True, 'use_regex': True}
    if spec['pre_tokenizer'] != expected_pre or spec['normalizer'] is not None or spec['model']['type'] != 'BPE':
        raise ValueError('Unsupported tokenizer: cannot select GPT-2 pre-tokenizer')
    converter_root = Path('android/app/src/main/cpp/llama.cpp').resolve()
    sys.path.insert(0, str(converter_root))
    sys.path.insert(0, str(converter_root / 'gguf-py'))
    import gguf
    from conversion.gpt2 import GPT2Model

    class UnifiedGPT2Model(GPT2Model):
        model_arch = gguf.MODEL_ARCH.GPT2

        def get_vocab_base_pre(self, tokenizer):
            if json.loads(tokenizer.backend_tokenizer.to_str())['pre_tokenizer'] != expected_pre:
                raise ValueError('Pre-tokenizer changed during export')
            return 'gpt-2'

    args.out.mkdir(parents=True)
    logging.basicConfig(level=logging.INFO)
    target = args.out / f'standard-qwerty-unified-{args.outtype}.gguf'
    converter = UnifiedGPT2Model(hf, gguf.LlamaFileType.ALL_F32 if args.outtype == 'f32' else gguf.LlamaFileType.MOSTLY_F16, target)
    converter.write()
    reader = gguf.GGUFReader(str(target))
    tokens = reader.fields['tokenizer.ggml.tokens']
    strings = [tokens.parts[i].tobytes().decode('utf-8') for i in tokens.data]
    if len(strings) != len(codec.tokenizer) or any(strings[v] != k for k,v in codec.ids.items()):
        raise ValueError('Exported control IDs differ')
    for name in ('v4-unified-codec.json', 'tokenizer.json', 'tokenizer_config.json',
                 'special_tokens_map.json', 'vocab.json', 'merges.txt',
                 'qwerty-observation-table.json', 'qwerty-observation-MIT-LICENSE'):
        if (hf / name).exists():
            shutil.copy2(hf / name, args.out / name)
    for name in ('LICENSE', 'sources.json', 'run-manifest.json', 'metrics.json', 'mixture.json', 'environment-supplement.json'):
        if (args.run / name).exists():
            shutil.copy2(args.run / name, args.out / name)
    shutil.copytree(args.run / 'source-evidence', args.out / 'source-evidence')
    if (args.run / 'source-code').is_dir():
        shutil.copytree(args.run / 'source-code', args.out / 'source-code')
    write_json(args.out / 'export-manifest.json', {
        'schema': 'v4-unified-export-v1', 'source_run': str(args.run),
        'run_manifest_sha256': digest(args.run / 'run-manifest.json'),
        'gguf_path': str(target), 'gguf_sha256': digest(target), 'gguf_bytes': target.stat().st_size,
        'precision': args.outtype, 'vocab_size': len(strings), 'controls_verified': codec.ids,
        'export_code_sha256': digest(__file__),
        'converter_hashes': {str(p.relative_to(converter_root)): digest(p)
            for directory in ('conversion', 'gguf-py/gguf') for p in sorted((converter_root / directory).rglob('*.py'))},
        'pre_tokenizer': 'verified ByteLevel/BPE -> existing gpt-2, no ID-fingerprint guess',
        'status': 'converted; quality and runtime parity require separate measurements'})
    print(f'Exported {target}', flush=True)


if __name__ == '__main__':
    main()
