# -*- coding: utf-8 -*-
"""
LoRA マージ比率 (Weight Interpolation) チューニングスクリプト
Base モデルの知識を維持しながら、蒸留による改善分だけを純増させる最適比率を探索
"""

import os
import sys
import json
import time
import subprocess
import urllib.request
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

BASE_MODEL = "Miwa-Keita/zenz-v3.1-xsmall"
LORA_DIR = 'artifacts/checkpoints/nano-v2-gentle-lora'
CONVERT_SCRIPT = 'android/app/src/main/cpp/llama.cpp/convert_hf_to_gguf.py'
QUANTIZE_EXE = 'ZeroConvertIME-windows/llama_cuda/llama-quantize.exe'
SERVER_EXE = 'ZeroConvertIME-windows/llama_cuda/llama-server.exe'

def hira_to_kata(text: str) -> str:
    return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])

def evaluate_output(output: str, case: dict) -> tuple[int, str]:
    out = output.strip().replace("\r", "").replace("\n", "")
    expected = case["expected"].strip()
    acceptable = [a.strip() for a in case.get("acceptable", [])]
    forbidden = [f.strip() for f in case.get("forbidden_examples", [])]

    for fb in forbidden:
        if fb and fb in out:
            return 0, f"NG (forbidden: {fb})"

    if out == expected or out in acceptable:
        return 2, "OK (Perfect)"

    if out.replace(" ", "").replace("　", "") == expected.replace(" ", "").replace("　", ""):
        return 2, "OK (Whitespace variant)"

    hira_out = "".join([chr(ord(ch) - 96) if 0x30A1 <= ord(ch) <= 0x30F6 else ch for ch in out])
    hira_exp = "".join([chr(ord(ch) - 96) if 0x30A1 <= ord(ch) <= 0x30F6 else ch for ch in expected])
    if hira_out == hira_exp:
        return 2, "OK (Kana variant)"

    return 0, f"NG (out=[{out}] vs exp=[{expected}])"

def build_model(ratio: float, out_gguf: str):
    merged_dir = f"artifacts/checkpoints/nano-v2-ratio-{int(ratio*100)}"
    f16_gguf = f"artifacts/models/nano-v2-ratio-{int(ratio*100)}-f16.gguf"
    
    print(f"\nBuilding model with ratio={ratio}...", flush=True)
    base = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float32, device_map="cpu")
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    model = PeftModel.from_pretrained(base, LORA_DIR)

    # adapter weight scaling
    # adapter_name = "default"
    merged = model.merge_and_unload(adapter_names=["default"])
    
    # 手動補間: base + ratio * (merged - base)
    state_base = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float32, device_map="cpu").state_dict()
    state_merged = merged.state_dict()
    interp_state = {}
    for k in state_base.keys():
        if k in state_merged:
            interp_state[k] = state_base[k] + ratio * (state_merged[k] - state_base[k])
        else:
            interp_state[k] = state_base[k]

    merged.load_state_dict(interp_state)
    merged.save_pretrained(merged_dir)
    tokenizer.save_pretrained(merged_dir)

    # GGUF 変換
    cmd_convert = [sys.executable, CONVERT_SCRIPT, merged_dir, "--outfile", f16_gguf, "--outtype", "f16"]
    subprocess.run(cmd_convert, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

    # 量子化
    cmd_quant = [QUANTIZE_EXE, f16_gguf, out_gguf, "Q4_K_M"]
    subprocess.run(cmd_quant, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

    # f16 削除してディスク節約
    if os.path.exists(f16_gguf):
        os.remove(f16_gguf)
    print(f"Quantized to {out_gguf}", flush=True)

def eval_gguf(gguf_path: str, port: int, cases: list) -> int:
    cmd = [SERVER_EXE, "-m", gguf_path, "--port", str(port), "-ngl", "99", "-c", "1024", "--temp", "0.0"]
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(60):
        try:
            req = urllib.request.Request(f"http://127.0.0.1:{port}/health")
            with urllib.request.urlopen(req, timeout=1) as resp:
                if resp.status == 200:
                    break
        except:
            time.sleep(0.2)

    def complete(prompt):
        url = f"http://127.0.0.1:{port}/completion"
        data = json.dumps({"prompt": prompt, "n_predict": 32, "temperature": 0.0, "stop": ["</s>", "\uEE00", "\uEE01", "\uEE02", "\n"]}).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return json.loads(resp.read().decode("utf-8")).get("content", "").strip()
        except:
            return ""

    perfect = 0
    try:
        for c in cases:
            ctx = "" if c["left_context"] == "なし" else c["left_context"]
            kata = hira_to_kata(c["target"])
            prompt = f"\uEE02{ctx}\uEE00{kata}\uEE01"
            out = complete(prompt)
            score, _ = evaluate_output(out, c)
            if score == 2:
                perfect += 1
    finally:
        proc.terminate()

    return perfect

def main():
    with open("data/kana_kanji_llm_testset_5x.jsonl", "r", encoding="utf-8") as f:
        cases = [json.loads(line) for line in f if line.strip()]

    ratios = [0.2, 0.4, 0.6, 0.8]
    results = {}

    for r in ratios:
        gguf = f"artifacts/models/nano-v2-r{int(r*100)}-q4km.gguf"
        build_model(r, gguf)
        score = eval_gguf(gguf, 8190, cases)
        results[r] = score
        print(f"==> Ratio {r:.1f}: {score}/230 ({score/230*100:.1f}%)", flush=True)

    print("\nSummary of ratios:")
    for r, s in results.items():
        print(f"  Ratio {r:.1f}: {s}/230 ({s/230*100:.1f}%)")

if __name__ == "__main__":
    main()
