# -*- coding: utf-8 -*-
"""Generate multiple WiSE-FT blends and benchmark them on hard context testset."""

import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "training"))

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM

BASE_DIR = str(ROOT / "artifacts" / "models" / "hf" / "zenz-v3.2-small")
LORA_DIR = str(ROOT / "artifacts" / "checkpoints" / "standard-v3-lora")
CONVERT_SCRIPT = str(ROOT / "android" / "app" / "src" / "main" / "cpp" / "llama.cpp" / "convert_hf_to_gguf.py")


def create_blend(alpha_blend: float, out_dir: str, gguf_path: str):
    os.makedirs(out_dir, exist_ok=True)
    base_state = AutoModelForCausalLM.from_pretrained(
        BASE_DIR, dtype=torch.float32, device_map="cpu"
    ).state_dict()

    base_for_peft = AutoModelForCausalLM.from_pretrained(
        BASE_DIR, dtype=torch.float32, device_map="cpu"
    )
    merged_model = PeftModel.from_pretrained(base_for_peft, LORA_DIR).merge_and_unload()

    if alpha_blend < 1.0:
        with torch.no_grad():
            for name, param in merged_model.named_parameters():
                if name in base_state:
                    param.copy_((1.0 - alpha_blend) * base_state[name] + alpha_blend * param.data)

    merged_model.save_pretrained(out_dir)

    for fname in ("config.json", "vocab.json", "merges.txt", "tokenizer_config.json", "special_tokens_map.json", "provenance.json"):
        src = Path(BASE_DIR) / fname
        if src.exists():
            shutil.copy2(src, Path(out_dir) / fname)

    cmd = [
        sys.executable,
        CONVERT_SCRIPT,
        out_dir,
        "--outfile", gguf_path,
        "--outtype", "f16",
    ]
    subprocess.check_call(cmd)
    print(f"Created blend {alpha_blend:.2f} -> {gguf_path}", flush=True)


def main():
    blends = [0.70, 0.60, 0.50, 0.40]
    created = []
    for b in blends:
        name = f"standard-v3-blend{int(b*100)}"
        out_dir = str(ROOT / "artifacts" / "checkpoints" / name)
        gguf_path = str(ROOT / "artifacts" / "models" / f"zerocon-{name}-f16.gguf")
        create_blend(b, out_dir, gguf_path)
        created.append((f"Blend {int(b*100)}", gguf_path))


if __name__ == "__main__":
    main()
