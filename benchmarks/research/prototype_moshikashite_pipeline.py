# -*- coding: utf-8 -*-
"""
非破壊型 IME 変換パイプライン プロトタイプ
1. メイン変換: ユーザーの入力通りに忠実に変換（既存方式を100%維持、勝手な書き換え禁止）
2. 💡 もしかして: Zenzai対数尤度またはLFMによる誤字補正アシスト候補
3. 🔮 先読み: LFM Text Completionまたは辞書による後続予測アシスト候補
"""

import urllib.request
import json
import time
import sys
import re

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

from dictionary_baseline import damerau_levenshtein_distance, STANDARD_VOCAB, dictionary_predictive_completion

CONTEXT_TOKEN = "\uEE02"
INPUT_START_TOKEN = "\uEE00"
OUTPUT_START_TOKEN = "\uEE01"

def hira_to_kata(text: str) -> str:
    return "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in text])

class Candidate:
    def __init__(self, text: str, cand_type: str = "NORMAL", badge: str = "", confidence: float = 1.0):
        self.text = text
        self.cand_type = cand_type  # "NORMAL", "MOSHIKASHITE", "PREDICTIVE"
        self.badge = badge          # "", "💡 もしかして", "🔮 先読み"
        self.confidence = confidence

    def display(self) -> str:
        if self.badge:
            return f"[{self.badge}: {self.text}]"
        return self.text

    def __repr__(self):
        return self.display()

class ZeroConSafeHybridEngine:
    def __init__(self, zenzai_url: str = "http://127.0.0.1:8090/completion", lfm_url: str = "http://127.0.0.1:1234/v1/completions"):
        self.zenzai_url = zenzai_url
        self.lfm_url = lfm_url

    def _call_zenzai(self, context: str, kata: str) -> tuple[str, float]:
        """Zenzaiを呼び出して変換結果と平均logprobを取得"""
        prompt = f"{CONTEXT_TOKEN}{context}{INPUT_START_TOKEN}{kata}{OUTPUT_START_TOKEN}"
        req = urllib.request.Request(
            self.zenzai_url,
            headers={"Content-Type": "application/json"},
            data=json.dumps({"prompt": prompt, "n_predict": 12, "n_probs": 1, "temperature": 0.0}).encode("utf-8")
        )
        try:
            with urllib.request.urlopen(req, timeout=3) as resp:
                d = json.loads(resp.read().decode("utf-8"))
                out = d.get("content", "").strip()
                probs = d.get("completion_probabilities", [])
                avg_lp = sum(p["logprob"] for p in probs) / max(1, len(probs)) if probs else -1.0
                return out, avg_lp
        except Exception:
            return kata, -9.0

    def _call_lfm_predictive(self, full_context: str) -> str:
        """LFM 230M/350M Text Completionによる先読み"""
        req = urllib.request.Request(
            self.lfm_url,
            headers={"Content-Type": "application/json"},
            data=json.dumps({
                "model": "liquidai/lfm2.5-350m",
                "prompt": full_context,
                "max_tokens": 10,
                "temperature": 0.0,
                "stop": ["\n", "。", "！", "!"]
            }).encode("utf-8")
        )
        try:
            with urllib.request.urlopen(req, timeout=3) as resp:
                d = json.loads(resp.read().decode("utf-8"))
                return d["choices"][0]["text"].strip()
        except Exception:
            return ""

    def convert(self, context: str, raw_hira: str) -> list[Candidate]:
        """
        メイン変換（入力通り）＋ 💡もしかして ＋ 🔮先読み を統合生成
        """
        raw_kata = hira_to_kata(raw_hira)
        candidates = []

        # ========================================================
        # 1. メイン変換ストリーム (入力通りを100%保証・絶対に勝手に直さない)
        # ========================================================
        main_out, main_lp = self._call_zenzai(context, raw_kata)
        # 第1候補: ユーザーが打った通りのZenzai変換
        candidates.append(Candidate(text=main_out, cand_type="NORMAL", confidence=1.0))

        # 通常の辞書・カナ・ひらがなFallback
        if main_out != raw_hira:
            candidates.append(Candidate(text=raw_hira, cand_type="NORMAL"))
        if main_out != raw_kata:
            candidates.append(Candidate(text=raw_kata, cand_type="NORMAL"))

        # ========================================================
        # 2. 💡 もしかして (AI 誤字補正アシスト候補)
        # ========================================================
        # 音韻の近接・タイポ候補（QWERTY隣接、母音、促音、撥音）を生成
        typo_variants = set()
        
        # 辞書引きからの補正案
        for v_hira, v_surf in STANDARD_VOCAB.items():
            if damerau_levenshtein_distance(raw_hira, v_hira) == 1:
                typo_variants.add((hira_to_kata(v_hira), v_surf))

        # もし補正候補が存在すれば、Zenzaiスコアで評価
        best_correction = None
        best_lp = -999.0
        for alt_kata, alt_surf in typo_variants:
            _, alt_lp = self._call_zenzai(context, alt_kata)
            # もし補正候補の文脈尤度が、メイン入力の尤度より有意に高い場合のみ採用
            if alt_lp > main_lp + 0.02 and alt_lp > best_lp:
                best_lp = alt_lp
                best_correction = alt_surf

        if best_correction and best_correction != main_out:
            candidates.append(Candidate(
                text=best_correction,
                cand_type="MOSHIKASHITE",
                badge="💡 もしかして",
                confidence=0.8
            ))

        # ========================================================
        # 3. 🔮 先読み (AI & 辞書 後続予測アシスト候補)
        # ========================================================
        # まず辞書の完全一致先読み
        pred_text = dictionary_predictive_completion(main_out)
        if not pred_text:
            pred_text = dictionary_predictive_completion(raw_hira)

        # 辞書にない場合は LFM 350M Text Completion
        if not pred_text:
            full_ctx = f"{context}{main_out}"
            pred_text = self._call_lfm_predictive(full_ctx)

        if pred_text:
            candidates.append(Candidate(
                text=pred_text,
                cand_type="PREDICTIVE",
                badge="🔮 先読み",
                confidence=0.7
            ))

        return candidates

def test_pipeline():
    engine = ZeroConSafeHybridEngine()

    test_scenarios = [
        {
            "desc": "ケース1: 明らかなタイポだが入力通りも欲しい時",
            "context": "仕事がやっと終わった。同僚に",
            "input": "おつくれさま"
        },
        {
            "desc": "ケース2: ビジネス定型文の先読み",
            "context": "先ほど資料を添付いたしましたので、",
            "input": "ご確認のほど"
        },
        {
            "desc": "ケース3: 隣接キー誤打 (きんにちは)",
            "context": "お昼のチャットで、",
            "input": "きんにちは"
        },
        {
            "desc": "ケース4: 正常な入力（タイポ無し）",
            "context": "先生から",
            "input": "しゅくだいがでた"
        },
        {
            "desc": "ケース5: 会話文の先読み（雨の文脈）",
            "context": "外は雨が激しく降っているので、",
            "input": "傘を持って"
        }
    ]

    print("=" * 65)
    print(" 【非破壊・安心設計】ZeroCon IME 新世代変換パイプライン")
    print("  方針: メインは入力通り100%尊重 ＋ 「💡もしかして」「🔮先読み」を追加")
    print("=" * 65)

    for sc in test_scenarios:
        print(f"\n【{sc['desc']}】")
        print(f"  文脈: 「{sc['context']}」")
        print(f"  入力: 「{sc['input']}」")
        
        t0 = time.perf_counter()
        cands = engine.convert(sc["context"], sc["input"])
        lat = (time.perf_counter() - t0) * 1000.0

        print(f"  変換候補一覧 (所要時間: {lat:.1f}ms):")
        for idx, c in enumerate(cands, 1):
            if c.cand_type == "NORMAL" and idx == 1:
                prefix = "  [1. 第1候補 (入力通り)]"
            elif c.cand_type == "NORMAL":
                prefix = f"  [{idx}. 通常候補        ]"
            elif c.cand_type == "MOSHIKASHITE":
                prefix = f"  [{idx}. 💡 もしかして   ]"
            elif c.cand_type == "PREDICTIVE":
                prefix = f"  [{idx}. 🔮 先読み       ]"
            print(f"    {prefix} {c.text}")

if __name__ == "__main__":
    test_pipeline()
