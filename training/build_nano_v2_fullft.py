# -*- coding: utf-8 -*-
"""
Full-FT モデルを直接 GGUF (f16) および Q4_K_M に変換するスクリプト
"""

import os
import sys
import subprocess

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

MODEL_DIR = 'artifacts/checkpoints/nano-v2-fullft'
F16_GGUF = 'artifacts/models/zerocon-model-nano-v2-fullft-f16.gguf'
Q4KM_GGUF = 'artifacts/models/zerocon-model-nano-v2-fullft-q4km.gguf'
CONVERT_SCRIPT = 'android/app/src/main/cpp/llama.cpp/convert_hf_to_gguf.py'
QUANTIZE_EXE = 'ZeroConvertIME-windows/llama_cuda/llama-quantize.exe'

def main():
    print("1. Converting Full-FT model to GGUF f16...", flush=True)
    cmd_convert = [
        sys.executable, CONVERT_SCRIPT,
        MODEL_DIR,
        "--outfile", F16_GGUF,
        "--outtype", "f16"
    ]
    subprocess.run(cmd_convert, check=True)

    print("2. Quantizing to Q4_K_M...", flush=True)
    cmd_quant = [QUANTIZE_EXE, F16_GGUF, Q4KM_GGUF, "Q4_K_M"]
    subprocess.run(cmd_quant, check=True)
    print("Full-FT Quantization complete! Output:", Q4KM_GGUF, flush=True)

if __name__ == "__main__":
    main()
