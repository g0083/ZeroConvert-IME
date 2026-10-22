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
    r_standard = run_model('ZeroConvertIME-windows/zenz.gguf', 8150)
    r_nanov2 = run_model('artifacts/models/zerocon-model-nano-v2-gentle-q4km.gguf', 8151)

    s_corr = sum(1 for r in r_standard if r["correct"])
    v2_corr = sum(1 for r in r_nanov2 if r["correct"])
    print(f"Standard: {s_corr}/230, Nano v2: {v2_corr}/230")

    gap_items = []
    for s, v in zip(r_standard, r_nanov2):
        if s["correct"] and not v["correct"]:
            c = s["case"]
            gap_items.append((c["category"], c["left_context"], c["target"], c["expected"], v["out"], s["out"]))

    print(f"\nGap items (Standard is correct, Nano v2 failed): {len(gap_items)}")
    for cat, ctx, tgt, exp, v_out, s_out in gap_items:
        print(f"[{cat}] ctx: '{ctx}', tgt: '{tgt}'")
        print(f"   Expected:    {exp}")
        print(f"   Nano v2:     {v_out}")
        print(f"   Standard:    {s_out}")

    v2_beats_standard = []
    for s, v in zip(r_standard, r_nanov2):
        if v["correct"] and not s["correct"]:
            c = s["case"]
            v2_beats_standard.append((c["category"], c["left_context"], c["target"], c["expected"], v["out"], s["out"]))
    print(f"\nNano v2 beats Standard: {len(v2_beats_standard)} items")
    for cat, ctx, tgt, exp, v_out, s_out in v2_beats_standard:
        print(f"[{cat}] ctx: '{ctx}', tgt: '{tgt}' -> exp: '{exp}' (v2: '{v_out}' vs std: '{s_out}')")
