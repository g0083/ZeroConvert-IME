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
    r_standard = run_model('ZeroConvertIME-windows/zenz.gguf', 8160)
    r_base = run_model('artifacts/models/zenz-v3.1-xsmall-q4km.gguf', 8161)

    with open('benchmarks/research/diff_base_standard.txt', "w", encoding="utf-8") as f:
        f.write(f"Standard: {sum(1 for r in r_standard if r['correct'])}/230\n")
        f.write(f"Base: {sum(1 for r in r_base if r['correct'])}/230\n\n")

        f.write("=== Standard 正解かつ Base 不正解の全問題 ===\n")
        cnt = 0
        for s, b in zip(r_standard, r_base):
            if s["correct"] and not b["correct"]:
                cnt += 1
                c = s["case"]
                f.write(f"[{cnt}] カテゴリ: {c['category']}\n")
                f.write(f"   文脈: '{c['left_context']}' | 読み: '{c['target']}'\n")
                f.write(f"   正解期待: '{c['expected']}'\n")
                f.write(f"   Base出力: '{b['out']}'\n")
                f.write(f"   Standard: '{s['out']}'\n\n")

    print(f"Done! Found {cnt} cases where Standard is correct and Base is wrong.")
