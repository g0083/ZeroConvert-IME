# -*- coding: utf-8 -*-
import json, time, subprocess, urllib.request

SERVER_EXE = 'ZeroConvertIME-windows/llama_cuda/llama-server.exe'

def run_model(model_path, port):
    cmd = [SERVER_EXE, '-m', model_path, '--port', str(port), '-ngl', '99', '-c', '1024', '--temp', '0.0']
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(60):
        try:
            req = urllib.request.Request(f'http://127.0.0.1:{port}/health')
            with urllib.request.urlopen(req, timeout=1) as resp:
                if resp.status == 200:
                    break
        except:
            time.sleep(0.2)

    def complete(prompt):
        url = f'http://127.0.0.1:{port}/completion'
        data = json.dumps({'prompt': prompt, 'n_predict': 32, 'temperature': 0.0, 'stop': ['</s>', '\uEE00', '\uEE01', '\uEE02', '\n']}).encode('utf-8')
        req = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                return json.loads(resp.read().decode('utf-8')).get('content', '').strip()
        except:
            return ''

    def hira_to_kata(text):
        return ''.join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])

    with open('data/kana_kanji_llm_testset_5x.jsonl', 'r', encoding='utf-8') as f:
        cases = [json.loads(line) for line in f if line.strip()]

    results = []
    for c in cases:
        ctx = '' if c['left_context'] == 'なし' else c['left_context']
        kata = hira_to_kata(c['target'])
        prompt = f'\uEE02{ctx}\uEE00{kata}\uEE01'
        out = complete(prompt).strip().replace('\r', '').replace('\n', '')
        exp = c['expected'].strip()
        acc = [a.strip() for a in c.get('acceptable', [])]
        fb = [f.strip() for f in c.get('forbidden_examples', [])]
        is_fb = any(f and f in out for f in fb)
        correct = (not is_fb) and (out == exp or out in acc or out.replace(' ','') == exp.replace(' ',''))
        results.append({'case': c, 'out': out, 'correct': correct})

    proc.terminate()
    return results

if __name__ == '__main__':
    r_small = run_model('ZeroConvertIME-windows/zenz.gguf', 8120)
    r_xsmall_v31 = run_model('artifacts/models/zenz-v3.1-xsmall-q4km.gguf', 8121)
    r_xsmall_v32 = run_model('artifacts/models/zenz-v3.2-xsmall-q4km.gguf', 8122)

    s_corr = sum(1 for r in r_small if r['correct'])
    x31_corr = sum(1 for r in r_xsmall_v31 if r['correct'])
    x32_corr = sum(1 for r in r_xsmall_v32 if r['correct'])

    print(f"Summary: small={s_corr}/230 ({s_corr/2.3:.1f}%), xsmall_v32={x32_corr}/230 ({x32_corr/2.3:.1f}%), xsmall_v31={x31_corr}/230 ({x31_corr/2.3:.1f}%)")

    diff_v32small_beats_v31x = []
    for i, (sm, x31) in enumerate(zip(r_small, r_xsmall_v31)):
        if sm['correct'] and not x31['correct']:
            c = sm['case']
            diff_v32small_beats_v31x.append((c['category'], c['left_context'], c['target'], c['expected'], x31['out'], sm['out']))

    print(f"\nTotal cases where v3.2-small is correct but v3.1-xsmall is wrong: {len(diff_v32small_beats_v31x)}")
    for cat, ctx, tgt, exp, x_out, sm_out in diff_v32small_beats_v31x:
        print(f"[{cat}] Context: \"{ctx}\", Target: \"{tgt}\"")
        print(f"   Expected:   {exp}")
        print(f"   v3.1-xsmall:{x_out}")
        print(f"   v3.2-small: {sm_out}")
