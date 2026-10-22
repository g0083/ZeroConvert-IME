# -*- coding: utf-8 -*-
"""
LoRA 重みをベースモデルにマージして単一モデルとして保存するスクリプト
マージ後、llama.cpp の convert_hf_to_gguf.py で GGUF 化できます。

実行方法:
python export_typo_corrector.py --base_model Miwa-Keita/zenz-v3.1-small --lora_dir ./typo-corrector-lora --output_dir ./typo-corrector-merged
"""

import argparse
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base_model", type=str, default="Miwa-Keita/zenz-v3.1-small")
    parser.add_argument("--lora_dir", type=str, default='artifacts/checkpoints/typo-corrector-lora')
    parser.add_argument("--output_dir", type=str, default='artifacts/checkpoints/typo-corrector-merged')
    args = parser.parse_args()

    print(f"Loading base model: {args.base_model}...")
    base_model = AutoModelForCausalLM.from_pretrained(
        args.base_model,
        torch_dtype=torch.float32,
        device_map="cpu"
    )
    tokenizer = AutoTokenizer.from_pretrained(args.base_model)

    print(f"Loading LoRA weights: {args.lora_dir}...")
    model = PeftModel.from_pretrained(base_model, args.lora_dir)

    print("Merging weights...")
    merged_model = model.merge_and_unload()

    print(f"Saving merged model to {args.output_dir}...")
    merged_model.save_pretrained(args.output_dir)
    tokenizer.save_pretrained(args.output_dir)
    print("Done! You can now convert it to GGUF using:")
    print(f"python llama.cpp/convert_hf_to_gguf.py {args.output_dir} --outfile typo-corrector.gguf")

if __name__ == "__main__":
    main()
