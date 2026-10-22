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
    r_base = run_model('artifacts/models/zenz-v3.1-xsmall-q4km.gguf', 8192)
    r_v2 = run_model('artifacts/models/zerocon-model-nano-v2-q4km.gguf', 8193)

    b_score = sum(1 for r in r_base if r["correct"])
    v2_score = sum(1 for r in r_v2 if r["correct"])

    print(f"Base (zenz-v3.1-xsmall): {b_score}/230 ({b_score/230*100:.1f}%)")
    print(f"Nano v2 (Official):      {v2_score}/230 ({v2_score/230*100:.1f}%)")

    improved = []
    regressed = []
    for i, (b, v) in enumerate(zip(r_base, r_v2)):
        c = b["case"]
        if not b["correct"] and v["correct"]:
            improved.append((i, c, b["out"], v["out"]))
        elif b["correct"] and not v["correct"]:
            regressed.append((i, c, b["out"], v["out"]))

    print(f"\nNet improvement: +{len(improved) - len(regressed)} questions (Improved: {len(improved)}, Regressed: {len(regressed)})")
    print("\n[+] Improved items (Base NG -> Nano v2 OK):")
    for i, c, b_out, v_out in improved:
        print(f"  Q{i} [{c['category']}] ctx: '{c['left_context']}' | tgt: '{c['target']}'")
        print(f"      exp: '{c['expected']}' | Base: '{b_out}' -> Nano v2: '{v_out}'")

    if regressed:
        print("\n[-] Regressed items:")
        for i, c, b_out, v_out in regressed:
            print(f"  Q{i} [{c['category']}] ctx: '{c['left_context']}' | tgt: '{c['target']}'")
            print(f"      exp: '{c['expected']}' | Base: '{b_out}' -> Nano v2: '{v_out}'")
