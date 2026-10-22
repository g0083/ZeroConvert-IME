# -*- coding: utf-8 -*-
"""
評価対象エンジンのクライアント群:
1. ZeroCon AI (Zenzai / zenz-v3.2 on llama-server:8090)
2. LM Studio: LFM 2.5 230M (lfm2.5-230m@q4_k_m)
3. LM Studio: LFM 2.5 350M (liquidai/lfm2.5-350m)
"""

import urllib.request
import json
import time

CONTEXT_TOKEN = "\uEE02"
INPUT_START_TOKEN = "\uEE00"
OUTPUT_START_TOKEN = "\uEE01"

def hira_to_kata(text: str) -> str:
    return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])

class ZeroConAIClient:
    def __init__(self, endpoint: str = "http://127.0.0.1:8090/completion"):
        self.endpoint = endpoint

    def typo_correct(self, context: str, raw_input_hira: str) -> tuple[str, float]:
        """
        タイポを含む読みをZenzaiのニューラルLMに通し、自己修復・変換能力を測定
        """
        target_kata = hira_to_kata(raw_input_hira)
        prompt = f"{CONTEXT_TOKEN}{context}{INPUT_START_TOKEN}{target_kata}{OUTPUT_START_TOKEN}"
        payload = {
            "prompt": prompt,
            "n_predict": 32,
            "temperature": 0.0,
            "stop": ["</s>", "\uEE00", "\uEE01", "\uEE02"]
        }
        t0 = time.perf_counter()
        req = urllib.request.Request(
            self.endpoint,
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload).encode("utf-8")
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                output = data.get("content", "").strip()
        except Exception as e:
            output = f"Error: {e}"
        latency_ms = (time.perf_counter() - t0) * 1000.0
        return output, latency_ms

    def predictive_complete(self, context: str, partial_input: str) -> tuple[str, float]:
        """
        ZeroCon AI による入力途中からの先読み生成
        """
        target_kata = hira_to_kata(partial_input)
        prompt = f"{CONTEXT_TOKEN}{context}{INPUT_START_TOKEN}{target_kata}"
        payload = {
            "prompt": prompt,
            "n_predict": 32,
            "temperature": 0.0,
            "stop": ["</s>", "\uEE02"]
        }
        t0 = time.perf_counter()
        req = urllib.request.Request(
            self.endpoint,
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload).encode("utf-8")
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                output = data.get("content", "").strip()
                # \uEE01 が含まれる場合はその後ろを取得
                if OUTPUT_START_TOKEN in output:
                    output = output.split(OUTPUT_START_TOKEN)[-1].strip()
        except Exception as e:
            output = f"Error: {e}"
        latency_ms = (time.perf_counter() - t0) * 1000.0
        return output, latency_ms


class LMStudioClient:
    def __init__(self, model_id: str, endpoint: str = "http://127.0.0.1:1234/v1/chat/completions"):
        self.model_id = model_id
        self.endpoint = endpoint

    def typo_correct(self, context: str, raw_input: str) -> tuple[str, float]:
        system_prompt = (
            "あなたは日本語IMEの誤入力補正・変換エンジンです。\n"
            "入力にはキーボードの打ち間違い（タイポ）や脱落が含まれる場合があります。\n"
            "直前の文脈を考慮し、ユーザーが意図した正しい漢字かな交じり文へ変換してください。\n"
            "規則：\n"
            "・出力は補正後の変換結果1行のみを出力してください。\n"
            "・挨拶、前置き、解説、コードブロックは一切禁止です。"
        )
        user_content = f"文脈：{context}\n入力：{raw_input}\n出力："
        payload = {
            "model": self.model_id,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content}
            ],
            "temperature": 0.0,
            "max_tokens": 32
        }
        t0 = time.perf_counter()
        req = urllib.request.Request(
            self.endpoint,
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload).encode("utf-8")
        )
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                output = data["choices"][0]["message"]["content"].strip()
                # 改行があれば最初の行だけを取得
                output = output.split("\n")[0].strip()
                # 引用符や「出力：」プレフィックスを除去
                if output.startswith("出力："):
                    output = output[3:].strip()
                output = output.strip('「」"\'')
        except Exception as e:
            output = f"Error: {e}"
        latency_ms = (time.perf_counter() - t0) * 1000.0
        return output, latency_ms

    def predictive_complete(self, context: str, partial_input: str) -> tuple[str, float]:
        system_prompt = (
            "あなたは日本語IMEの先読み・予測変換エンジンです。\n"
            "ユーザーが入力途中のフレーズに続く、最も自然で適切な続きの文字列（先読み候補）を予測してください。\n"
            "規則：\n"
            "・入力済み文字列に続く「続きの文字列」だけを1行で出力してください。\n"
            "・入力済み文字列自体を重複して出力しないでください。\n"
            "・説明、挨拶、引用符は一切禁止です。"
        )
        user_content = f"文脈：{context}\n入力途中：{partial_input}\n続き："
        payload = {
            "model": self.model_id,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_content}
            ],
            "temperature": 0.0,
            "max_tokens": 32
        }
        t0 = time.perf_counter()
        req = urllib.request.Request(
            self.endpoint,
            headers={"Content-Type": "application/json"},
            data=json.dumps(payload).encode("utf-8")
        )
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                output = data["choices"][0]["message"]["content"].strip()
                output = output.split("\n")[0].strip()
                if output.startswith("続き："):
                    output = output[3:].strip()
                # もし入力文字列自体を含んで出力してきた場合、プレフィックスをトリム
                if output.startswith(partial_input):
                    output = output[len(partial_input):].strip()
                output = output.strip('「」"\'')
        except Exception as e:
            output = f"Error: {e}"
        latency_ms = (time.perf_counter() - t0) * 1000.0
        return output, latency_ms
