# -*- coding: utf-8 -*-
"""Build calibration dataset for llama-imatrix covering standard-v3 vocabulary first."""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "artifacts" / "datasets" / "standard-v3-imatrix"
OUT_FILE = OUT_DIR / "calibration.txt"

NANO_CALIBRATION = ROOT / "artifacts" / "datasets" / "nano-v3-imatrix-20261028" / "calibration.txt"
TRAIN_JSONL = ROOT / "artifacts" / "datasets" / "standard-v3-knowledge" / "train.jsonl"


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    lines = []

    # 1. Put all standard-v3 train knowledge lines FIRST (repeated 2x with both ' ' and '\u3000' variants)
    #    so --chunks 32 (16,384 tokens) in llama-imatrix always covers 100% of standard-v3 knowledge & ASCII spaces!
    if TRAIN_JSONL.exists():
        with open(TRAIN_JSONL, "r", encoding="utf-8") as f:
            train_rows = [json.loads(line) for line in f if line.strip()]
        for _ in range(2):
            for row in train_rows:
                prompt = row.get("prompt", "")
                comp = row.get("completion", "").replace(" ", "\u3000")
                if prompt and comp:
                    lines.append(f"{prompt}{comp}</s>")
                    if " " in prompt:
                        lines.append(f"{prompt.replace(' ', '\u3000')}{comp}</s>")

    # 2. Base everyday calibration lines
    if NANO_CALIBRATION.exists():
        with open(NANO_CALIBRATION, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    lines.append(line)

    print(f"Total calibration lines collected: {len(lines)}")

    with open(OUT_FILE, "w", encoding="utf-8") as f:
        for l in lines:
            f.write(l + "\n")

    print(f"Calibration text written to: {OUT_FILE}")


if __name__ == "__main__":
    main()
