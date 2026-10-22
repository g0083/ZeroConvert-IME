# -*- coding: utf-8 -*-
import json
import urllib.request
import subprocess
import time

SERVER_EXE = 'ZeroConvertIME-windows/llama_cuda/llama-server.exe'

with open("data/typo_benchmark_300.jsonl", "r", encoding="utf-8") as f:
    cases = [json.loads(line) for line in f if line.strip()][:15]

cmd = [
    SERVER_EXE,
    "-m", 'ZeroConvertIME-windows/zenz.gguf',
    "--port", "8096",
    "-ngl", "99",
    "-c", "1024",
    "--temp", "0.0"
]
proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(2)

out_lines = []
try:
    for c in cases:
        url = "http://127.0.0.1:8096/completion"
        data = json.dumps({
            "prompt": c["prompt"],
            "n_predict": 16,
            "temperature": 0.0,
            "stop": ["\uEE00", "\uEE01", "\uEE02", "\n", "</s>"]
        }).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req) as resp:
            res = json.loads(resp.read().decode("utf-8"))
            pred = res.get("content", "").strip()
        out_lines.append(f"[{c['typo_type']}] Context: '{c['context']}' Input: '{c['input_reading']}'")
        out_lines.append(f"  Expected: '{c['expected']}'")
        out_lines.append(f"  Old Model:'{pred}'")
        out_lines.append(f"  Match:    {pred == c['expected']}")
        out_lines.append("")
finally:
    proc.terminate()

with open("scratch/inspect_old_utf8.txt", "w", encoding="utf-8") as f:
    f.write("\n".join(out_lines))
print("Done writing scratch/inspect_old_utf8.txt")
