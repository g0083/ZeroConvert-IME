# SPDX-License-Identifier: Apache-2.0
"""Produce Q8 from an owned F16 export; no calibration or evaluation ingestion."""
import argparse
import json
from pathlib import Path
import subprocess

from origin_contract import digest, write_json


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--export', type=Path, required=True)
    args = p.parse_args()
    metadata_path = args.export / 'export-manifest.json'
    metadata = json.loads(metadata_path.read_text(encoding='utf-8'))
    source = Path(metadata['gguf_path'])
    if metadata['precision'] != 'f16' or digest(source) != metadata['gguf_sha256']:
        raise ValueError('Expected unchanged owned F16 export')
    output = args.export / 'standard-qwerty-unified-q8_0.gguf'
    if output.exists() or (args.export / 'q8-manifest.json').exists():
        raise FileExistsError(output)
    quantizer = Path('ZeroConvertIME-windows/llama_vulkan/llama-quantize.exe')
    command = [str(quantizer), str(source), str(output), 'Q8_0']
    with (args.export / 'q8-quantization.log').open('x', encoding='utf-8') as log:
        subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT)
    write_json(args.export / 'q8-manifest.json', {
        'schema': 'v4-unified-q8-v1', 'source_export_manifest_sha256': digest(metadata_path),
        'source_sha256': digest(source), 'quantizer_sha256': digest(quantizer),
        'output': str(output), 'output_sha256': digest(output), 'output_bytes': output.stat().st_size,
        'command': command, 'code_sha256': digest(__file__),
        'calibration': 'none; Q8 does not ingest evaluation or tokenizer corpus',
        'status': 'quantized; quality and CPU latency require separate measurement'})
    print(f'Created {output}: {output.stat().st_size} bytes')


if __name__ == '__main__':
    main()
