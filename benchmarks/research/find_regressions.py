# -*- coding: utf-8 -*-
import json, time, subprocess, urllib.request

SERVER_EXE = 'ZeroConvertIME-windows/llama_cuda/llama-server.exe'

def run_model(model_path, port):
    cmd = [SERVER_EXE, "-m", model_path, "--port", str(port), "-ngl", "99", "-c", "1024", "--temp", "0.0"]
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

    def hira_to_kata(text):
        return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])

    with open("data/kana_kanji_llm_testset_5x.jsonl", "r", encoding="utf-8") as f:
        cases = [json.loads(line) for line in f if line.strip()]

    results = []
    for c in cases:
        ctx = "" if c["left_context"] == "なし" else c["left_context"]
        kata = hira_to_kata(c["target"])
        prompt = f"\uEE02{ctx}\uEE00{kata}\uEE01"
        out = complete(prompt).strip().replace("\r", "").replace("\n", "")
        exp = c["expected"].strip()
        acc = [a.strip() for a in c.get("acceptable", [])]
        fb = [f.strip() for f in c.get("forbidden_examples", [])]
        is_fb = any(f and f in out for f in fb)
        correct = (not is_fb) and (out == exp or out in acc or out.replace(" ","") == exp.replace(" ",""))
        results.append({"case": c, "out": out, "correct": correct})

    proc.terminate()
    return results

if __name__ == '__main__':
    r_base = run_model('artifacts/models/zenz-v3.1-xsmall-q4km.gguf', 8180)
    r_v2 = run_model('artifacts/models/zerocon-model-nano-v2-gentle-q4km.gguf', 8181)

    regressed = [] # base OK -> v2 NG
    improved = []  # base NG -> v2 OK

    for i, (b, v) in enumerate(zip(r_base, r_v2)):
        c = b["case"]
        if b["correct"] and not v["correct"]:
            regressed.append((i, c, b["out"], v["out"]))
        elif not b["correct"] and v["correct"]:
            improved.append((i, c, b["out"], v["out"]))

    with open('benchmarks/research/improved_vs_regressed.txt', "w", encoding="utf-8") as f:
        f.write(f"Improved: {len(improved)} items\n")
        for i, c, b_out, v_out in improved:
            f.write(f"  [+] Q{i} ({c['category']}) ctx: '{c['left_context']}' | tgt: '{c['target']}'\n")
            f.write(f"      exp: '{c['expected']}'\n")
            f.write(f"      Base (NG): '{b_out}' -> NanoV2 (OK): '{v_out}'\n\n")

        f.write(f"\nRegressed: {len(regressed)} items\n")
        for i, c, b_out, v_out in regressed:
            f.write(f"  [-] Q{i} ({c['category']}) ctx: '{c['left_context']}' | tgt: '{c['target']}'\n")
            f.write(f"      exp: '{c['expected']}'\n")
            f.write(f"      Base (OK): '{b_out}' -> NanoV2 (NG): '{v_out}'\n\n")

    print(f"Improved: {len(improved)}, Regressed: {len(regressed)}")
