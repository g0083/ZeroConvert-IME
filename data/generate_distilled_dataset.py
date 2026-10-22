# -*- coding: utf-8 -*-
"""
zenz-v3.2-small 教師モデル蒸留データセット高速生成スクリプト (改良版: flush=True & 高速推論)
"""

import sys
import os
import json
import time
import random
import subprocess
import urllib.request
import pykakasi

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)
sys.path.append('data')

from distill_seeds import NATURAL_SENTENCES, VOCAB_WORDS
from generate_distill_and_dict import HOMOPHONE_CONTEXT_SEEDS

kakasi = pykakasi.kakasi()

def kanji_to_kata(text: str) -> str:
    res = kakasi.convert(text)
    return "".join([r['kana'] for r in res])

def hira_to_kata(text: str) -> str:
    return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])

# テストセットの読み込み（完全除外用）
TEST_EXCLUSION_SET = set()
with open('data/kana_kanji_llm_testset_5x.jsonl', 'r', encoding='utf-8') as f:
    for line in f:
        if line.strip():
            c = json.loads(line)
            ctx = '' if c['left_context'] == 'なし' else c['left_context']
            tgt = hira_to_kata(c['target'])
            TEST_EXCLUSION_SET.add((ctx, tgt))

print(f"Strict exclusion: loaded {len(TEST_EXCLUSION_SET)} benchmark test items.", flush=True)

SERVER_EXE = 'ZeroConvertIME-windows/llama_cuda/llama-server.exe'
TEACHER_MODEL = 'ZeroConvertIME-windows/zenz.gguf'
PORT = 8130

class TeacherServer:
    def __init__(self):
        self.proc = None

    def start(self):
        print(f"Starting Teacher Model Server (zenz-v3.2-small)...", flush=True)
        cmd = [SERVER_EXE, '-m', TEACHER_MODEL, '--port', str(PORT), '-ngl', '99', '-c', '1024', '--temp', '0.0']
        self.proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(60):
            try:
                req = urllib.request.Request(f'http://127.0.0.1:{PORT}/health')
                with urllib.request.urlopen(req, timeout=1) as resp:
                    if resp.status == 200:
                        print("Teacher Model Server is ready.", flush=True)
                        return
            except:
                time.sleep(0.2)
        raise RuntimeError("Failed to start Teacher Server")

    def stop(self):
        if self.proc:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except:
                self.proc.kill()
            self.proc = None

    def predict(self, prompt: str) -> str:
        url = f'http://127.0.0.1:{PORT}/completion'
        data = json.dumps({
            "prompt": prompt,
            "n_predict": 32,
            "temperature": 0.0,
            "stop": ["</s>", "\uEE00", "\uEE01", "\uEE02", "\n"]
        }).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                res = json.loads(resp.read().decode("utf-8"))
                return res.get("content", "").strip()
        except:
            return ""

def collect_prompts_to_distill():
    prompts_map = {} # prompt -> (ctx, kata, expected_hint)

    # 1. 同音異義語・高難度文脈シード
    for ctx, hira_tgt, exp in HOMOPHONE_CONTEXT_SEEDS:
        c = "" if ctx == "なし" else ctx
        kata = hira_to_kata(hira_tgt)
        if (c, kata) not in TEST_EXCLUSION_SET:
            p = f"\uEE02{c}\uEE00{kata}\uEE01"
            prompts_map[p] = (c, kata, exp)

    # 2. 頻出単語語彙（単体辞書 ＆ 文脈つき）
    CONTEXT_TEMPLATES = [
        "", "最新の", "本日の", "明日の", "重要な", "必要な", "毎日の", "社内の",
        "業務の", "作業の", "お客様の", "地域の", "日本の", "新しい", "過去の",
        "この", "その", "あの", "どのような", "多くの", "一般的な", "非常に", "大変"
    ]
    for kanji, hira in VOCAB_WORDS:
        kata = hira_to_kata(hira)
        # 文脈なし（単体）
        if ("", kata) not in TEST_EXCLUSION_SET:
            p = f"\uEE02\uEE00{kata}\uEE01"
            prompts_map[p] = ("", kata, kanji)
        
        # 文脈つき (4パターン抽出)
        for tmpl in random.sample(CONTEXT_TEMPLATES, 4):
            if tmpl and (tmpl, kata) not in TEST_EXCLUSION_SET:
                p = f"\uEE02{tmpl}\uEE00{kata}\uEE01"
                prompts_map[p] = (tmpl, kata, kanji)

    # 3. 自然な文章からの文節スライス蒸留
    for sent in NATURAL_SENTENCES:
        tokens = kakasi.convert(sent)
        accum_orig = ""
        for i, tok in enumerate(tokens):
            w_orig = tok['orig']
            w_kana = tok['kana']
            if len(w_orig) == 0 or len(w_kana) == 0:
                continue
            
            ctx = accum_orig[-20:] if len(accum_orig) > 20 else accum_orig
            if (ctx, w_kana) not in TEST_EXCLUSION_SET:
                p = f"\uEE02{ctx}\uEE00{w_kana}\uEE01"
                prompts_map[p] = (ctx, w_kana, w_orig)
            
            if i + 1 < len(tokens):
                nxt = tokens[i+1]
                combo_orig = w_orig + nxt['orig']
                combo_kana = w_kana + nxt['kana']
                if (ctx, combo_kana) not in TEST_EXCLUSION_SET:
                    p = f"\uEE02{ctx}\uEE00{combo_kana}\uEE01"
                    prompts_map[p] = (ctx, combo_kana, combo_orig)

            accum_orig += w_orig

    # 4. 補完禁止・口語・スラングパターン
    NO_CORRECT = [
        "こんにちわ", "よろしくおねがいしまｓ", "ありがと", "おｋ", "あざす",
        "わら", "草", "りょ", "おつかれー", "了解ですー", "なるほどです",
        "草生える", "わかりみ", "まじで", "それな", "うぽつ", "わこつ", "乙です",
        "こんにちは", "よろしくお願いします", "ありがとうございます", "了解しました"
    ]
    for nc in NO_CORRECT:
        kata = hira_to_kata(nc)
        if ("", kata) not in TEST_EXCLUSION_SET:
            p = f"\uEE02\uEE00{kata}\uEE01"
            prompts_map[p] = ("", kata, nc)

    return prompts_map

def main():
    prompts_map = collect_prompts_to_distill()
    print(f"Total unique prompts collected to distill: {len(prompts_map)}", flush=True)

    teacher = TeacherServer()
    teacher.start()

    distilled_data = []
    t0 = time.time()
    try:
        items = list(prompts_map.items())
        for idx, (prompt, (ctx, kata, hint)) in enumerate(items):
            # 補完禁止など直接指定がある場合は優先、それ以外は教師モデル推論
            if hint in ["こんにちわ", "よろしくおねがいしまｓ", "ありがと", "おｋ", "あざす", "わら", "草", "りょ", "おつかれー", "了解ですー", "なるほどです"]:
                teacher_out = hint
            else:
                teacher_out = teacher.predict(prompt)
                if not teacher_out:
                    teacher_out = hint

            distilled_data.append({
                "prompt": prompt,
                "completion": teacher_out,
                "left_context": ctx,
                "target_kata": kata,
                "hint": hint
            })

            if (idx + 1) % 200 == 0 or (idx + 1) == len(items):
                elapsed = time.time() - t0
                speed = (idx + 1) / max(0.001, elapsed)
                print(f"  Distilled {idx + 1}/{len(items)} items ({speed:.1f} items/sec, elapsed: {elapsed:.1f}s)...", flush=True)
    finally:
        teacher.stop()

    random.seed(42)
    random.shuffle(distilled_data)
    val_size = min(200, int(len(distilled_data) * 0.05))
    val_data = distilled_data[:val_size]
    train_data = distilled_data[val_size:]

    os.makedirs('data', exist_ok=True)
    with open('data/distilled_train.jsonl', 'w', encoding='utf-8') as f:
        for item in train_data:
            f.write(json.dumps(item, ensure_ascii=False) + '\n')

    with open('data/distilled_val.jsonl', 'w', encoding='utf-8') as f:
        for item in val_data:
            f.write(json.dumps(item, ensure_ascii=False) + '\n')

    print(f"\nDistillation complete! Train: {len(train_data)}, Val: {len(val_data)}", flush=True)
    print(f"Saved to data/distilled_train.jsonl and data/distilled_val.jsonl", flush=True)

if __name__ == '__main__':
    main()
