# -*- coding: utf-8 -*-
"""
Nano v2 LoRA 重みマージスクリプト
"""

import argparse
import sys
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base_model", type=str, default="Miwa-Keita/zenz-v3.1-xsmall")
    parser.add_argument("--lora_dir", type=str, default='artifacts/checkpoints/nano-v2-lora')
    parser.add_argument("--output_dir", type=str, default='artifacts/checkpoints/nano-v2-merged')
    args = parser.parse_args()

    print(f"Loading base model: {args.base_model}...", flush=True)
    base_model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        torch_dtype=torch.float32,
        device_map="cpu"
    )
    tokenizer = AutoTokenizer.from_pretrained(args.base_model)

    print(f"Loading LoRA adapter: {args.lora_dir}...", flush=True)
    model = PeftModel.from_pretrained(base_model, args.lora_dir)

    print("Merging weights into base model...", flush=True)
    merged_model = model.merge_and_unload()

    print(f"Saving merged model to {args.output_dir}...", flush=True)
    merged_model.save_pretrained(args.output_dir)
    tokenizer.save_pretrained(args.output_dir)
    print("Export complete!", flush=True)

if __name__ == "__main__":
    main()
