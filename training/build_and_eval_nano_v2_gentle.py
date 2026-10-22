# -*- coding: utf-8 -*-
"""
gentle LoRA のマージ、GGUF変換、Q4_K_M量子化、そしてベンチマーク評価を一括実行するスクリプト
"""

import os
import sys
import subprocess
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

BASE_MODEL = "Miwa-Keita/zenz-v3.1-xsmall"
LORA_DIR = 'artifacts/checkpoints/nano-v2-gentle-lora'
MERGED_DIR = 'artifacts/checkpoints/nano-v2-gentle-merged'
F16_GGUF = 'artifacts/models/zerocon-model-nano-v2-gentle-f16.gguf'
Q4KM_GGUF = 'artifacts/models/zerocon-model-nano-v2-gentle-q4km.gguf'
CONVERT_SCRIPT = 'android/app/src/main/cpp/llama.cpp/convert_hf_to_gguf.py'
QUANTIZE_EXE = 'ZeroConvertIME-windows/llama_cuda/llama-quantize.exe'

def main():
    print("1. Merging gentle LoRA into base model...", flush=True)
    base_model = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL,
        torch_dtype=torch.float32,
        device_map="cpu"
    )
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    model = PeftModel.from_pretrained(base_model, LORA_DIR)
    merged = model.merge_and_unload()
    merged.save_pretrained(MERGED_DIR)
    tokenizer.save_pretrained(MERGED_DIR)
    print("Merge complete!", flush=True)

    print("2. Converting merged model to GGUF f16...", flush=True)
    cmd_convert = [
        sys.executable, CONVERT_SCRIPT,
        MERGED_DIR,
        "--outfile", F16_GGUF,
        "--outtype", "f16"
    ]
    subprocess.run(cmd_convert, check=True)

    print("3. Quantizing to Q4_K_M...", flush=True)
    cmd_quant = [QUANTIZE_EXE, F16_GGUF, Q4KM_GGUF, "Q4_K_M"]
    subprocess.run(cmd_quant, check=True)
    print("Quantization complete! Output:", Q4KM_GGUF, flush=True)

if __name__ == "__main__":
    main()
