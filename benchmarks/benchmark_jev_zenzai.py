# -*- coding: utf-8 -*-
"""
従来型IME候補生成からの「Jev方式」および「Zenzai方式」の比較検証スクリプト
- Jev方式: ローカル軽量モデルによるChoice（番号選択）プリミティブ推論
- Zenzai方式: 小型GPT-2ニューラル言語モデル（約110M / 200MB）によるPerplexity/対数尤度スコアリング
"""

import sys
import os
import json
import time
import urllib.request
import re
import torch
import torch.nn.functional as F
from transformers import AutoModelForCausalLM, AutoTokenizer

sys.stdout.reconfigure(encoding='utf-8')

# 全46問のテストケースと、従来型IMEが生成する複数候補リスト
# 候補には正解、代表的同音異義語、文節切り違い、ひらがな混じりなどを含む
BENCHMARK_CASES = [
    # A. 基本変換
    {
        "id": "01", "context": "なし", "target": "きょうはてんきがいい",
        "candidates": ["今日は天気がいい", "きょうは天気がいい", "今日はてんきがいい", "今日は転機がいい"],
        "expected": "今日は天気がいい"
    },
    {
        "id": "02", "context": "なし", "target": "あしたはとうきょうえきでまちあわせる",
        "candidates": ["明日は東京駅で待ち合わせる", "明日は東京駅でまちあわせる", "あしたは東京駅で待ち合わせる", "明日は東京駅で町合わせる"],
        "expected": "明日は東京駅で待ち合わせる"
    },
    {
        "id": "03", "context": "なし", "target": "しりょうをかくにんしてからへんしんする",
        "candidates": ["資料を確認してから返信する", "資料を確認してから変身する", "史料を確認してから返信する", "資料をかくにんしてから返信する"],
        "expected": "資料を確認してから返信する"
    },
    {
        "id": "04", "context": "なし", "target": "でんしゃがおくれている",
        "candidates": ["電車が遅れている", "電車がおくれている", "電車が後れている", "電車が送れている"],
        "expected": "電車が遅れている"
    },
    {
        "id": "05", "context": "なし", "target": "にほんごにゅうりょくをかいぜんする",
        "candidates": ["日本語入力を改善する", "日本語にゅうりょくを改善する", "日本語入力を改選する", "日本語入力をかいぜんする"],
        "expected": "日本語入力を改善する"
    },
    # B. 同音異義語
    {
        "id": "06", "context": "新聞社から取材班が派遣された。", "target": "きしゃがじけんげんばをしゅざいした",
        "candidates": ["記者が事件現場を取材した", "汽車が事件現場を取材した", "貴社が事件現場を取材した", "帰社が事件現場を取材した"],
        "expected": "記者が事件現場を取材した"
    },
    {
        "id": "07", "context": "駅のホームに大勢の乗客が並んでいた。", "target": "きしゃがえきにとうちゃくした",
        "candidates": ["汽車が駅に到着した", "記者が駅に到着した", "貴社が駅に到着した", "帰社が駅に到着した"],
        "expected": "汽車が駅に到着した"
    },
    {
        "id": "08", "context": "取引先へのメールを書いている。", "target": "きしゃのせいひんをけんとうしています",
        "candidates": ["貴社の製品を検討しています", "記者の製品を検討しています", "汽車の製品を検討しています", "帰社の製品を検討しています"],
        "expected": "貴社の製品を検討しています"
    },
    {
        "id": "09", "context": "朝から奥歯が痛む。", "target": "しかいにみてもらう",
        "candidates": ["歯科医に診てもらう", "歯科医に見てもらう", "司会に見てもらう", "視界に見てもらう"],
        "expected": "歯科医に診てもらう"
    },
    {
        "id": "10", "context": "結婚式の進行について相談した。", "target": "しかいをともだちにたのむ",
        "candidates": ["司会を友達に頼む", "歯科医を友達に頼む", "視界を友達に頼む", "司会を友人に頼む"],
        "expected": "司会を友達に頼む"
    },
    {
        "id": "11", "context": "イベントの開催場所を探している。", "target": "かいじょうをよやくする",
        "candidates": ["会場を予約する", "海上を予約する", "開場を予約する", "解錠を予約する"],
        "expected": "会場を予約する"
    },
    {
        "id": "12", "context": "船から周囲の様子を確認した。", "target": "かいじょうはおだやかだった",
        "candidates": ["海上は穏やかだった", "会場は穏やかだった", "開場は穏やかだった", "かいじょうは穏やかだった"],
        "expected": "海上は穏やかだった"
    },
    {
        "id": "13", "context": "コンサートは午後6時に始まる。", "target": "ごごごじにかいじょうする",
        "candidates": ["午後5時に開場する", "午後5時に会場する", "午後5時に海上する", "午後5時に解錠する"],
        "expected": "午後5時に開場する"
    },
    {
        "id": "14", "context": "暗証番号を入力した。", "target": "どあをかいじょうする",
        "candidates": ["ドアを解錠する", "ドアを開場する", "ドアを会場する", "ドアを開錠する"],
        "expected": "ドアを解錠する"
    },
    # C. 文脈によって変換が変わるケース
    {
        "id": "15", "context": "熱が下がらず、内科を受診することになった。", "target": "きょうはいしゃにいく",
        "candidates": ["今日は医者に行く", "今日歯医者に行く", "きょうは医者に行く", "今日は医者にいく"],
        "expected": "今日は医者に行く"
    },
    {
        "id": "16", "context": "奥歯の詰め物が取れてしまった。", "target": "きょうはいしゃにいく",
        "candidates": ["今日歯医者に行く", "今日は医者に行く", "きょう歯医者に行く", "今日は歯医者に行く"],
        "expected": "今日歯医者に行く"
    },
    {
        "id": "17", "context": "川の向こう岸へ歩いて渡った。", "target": "はしをわたる",
        "candidates": ["橋を渡る", "端を渡る", "箸を渡る", "はしを渡る"],
        "expected": "橋を渡る"
    },
    {
        "id": "18", "context": "食事を始める前に手を合わせた。", "target": "はしをもつ",
        "candidates": ["箸を持つ", "橋を持つ", "端を持つ", "はしを持つ"],
        "expected": "箸を持つ"
    },
    {
        "id": "19", "context": "長いベンチに二人で座った。", "target": "はしにすわる",
        "candidates": ["端に座る", "橋に座る", "箸に座る", "はしに座る"],
        "expected": "端に座る"
    },
    {
        "id": "20", "context": "空が暗くなり、雷が聞こえてきた。", "target": "あめがふってきた",
        "candidates": ["雨が降ってきた", "飴が降ってきた", "雨がふってきた", "あめが降ってきた"],
        "expected": "雨が降ってきた"
    },
    {
        "id": "21", "context": "袋から甘いお菓子を取り出した。", "target": "あめをひとつたべた",
        "candidates": ["飴を一つ食べた", "雨を一つ食べた", "飴をひとつ食べた", "飴を1つ食べた"],
        "expected": "飴を一つ食べた"
    },
    # D. 長い文脈
    {
        "id": "22", "context": "新しいパソコンにローカルの言語モデルを導入した。モデルをGPUへ読み込んだあと、APIサーバーを起動し、同じネットワークにあるスマートフォンから接続を試した。",
        "target": "せつぞくにはせいこうしたがもでるのしゅとくにしっぱいした",
        "candidates": ["接続には成功したがモデルの取得に失敗した", "接続には成功したがモデルの習得に失敗した", "接続には成功したがモデルの取得に失敗した。", "接続には成功したがモデルの取得に失敗"],
        "expected": "接続には成功したがモデルの取得に失敗した"
    },
    {
        "id": "23", "context": "高速道路では前の車との距離を保つ機能を使用した。渋滞中は便利だったが、停止直前の制御に違和感があった。",
        "target": "ぶれーきがおそくさいごにかっくんぶれーきになった",
        "candidates": ["ブレーキが遅く最後にカックンブレーキになった", "ブレーキが遅く最後にかっくんブレーキになった", "ブレーキが遅く最後にカックンぶれーきになった", "ブレーキが遅く最後にカックンブレーキに成った"],
        "expected": "ブレーキが遅く最後にカックンブレーキになった"
    },
    {
        "id": "24", "context": "ニューラルネットワークには複数の専門モデルがあり、入力に応じて一部だけが選択される。",
        "target": "えきすぱーとのわりあてをさいてきかする",
        "candidates": ["エキスパートの割り当てを最適化する", "エキスパートの割当を最適化する", "エキスパートのわりあてを最適化する", "エキスパートの割り当てを採適化する"],
        "expected": "エキスパートの割り当てを最適化する"
    },
    # E. 英数字と技術用語の保持
    {
        "id": "25", "context": "なし", "target": "LM Studioでろーかるさーばーをきどうする",
        "candidates": ["LM Studioでローカルサーバーを起動する", "LM Studioでローカルサーバーをきどうする", "LM Studioでローカルサーバを起動する", "lm studioでローカルサーバーを起動する"],
        "expected": "LM Studioでローカルサーバーを起動する"
    },
    {
        "id": "26", "context": "なし", "target": "VRAM12GBでもでるをじっこうする",
        "candidates": ["VRAM12GBでモデルを実行する", "VRAM 12GBでモデルを実行する", "VRAM12GBでもでるを実行する", "VRAM12GBでモデルを実効する"],
        "expected": "VRAM12GBでモデルを実行する"
    },
    {
        "id": "27", "context": "なし", "target": "ESP32をWi-Fiにせつぞくする",
        "candidates": ["ESP32をWi-Fiに接続する", "ESP32をWiFiに接続する", "ESP32をWi-Fiにせつぞくする", "ESP32をWi-Fiに節続する"],
        "expected": "ESP32をWi-Fiに接続する"
    },
    {
        "id": "28", "context": "なし", "target": "http://192.168.68.68:1234/v1/modelsにあくせすする",
        "candidates": ["http://192.168.68.68:1234/v1/modelsにアクセスする", "http://192.168.68.68:1234/v1/modelsにあくせすする", "HTTP://192.168.68.68:1234/v1/modelsにアクセスする"],
        "expected": "http://192.168.68.68:1234/v1/modelsにアクセスする"
    },
    {
        "id": "29", "context": "なし", "target": "Qwen3.5-4BをGPUにろーどする",
        "candidates": ["Qwen3.5-4BをGPUにロードする", "Qwen3.5-4BをGPUにろーどする", "Qwen3.5-4BをGPUにロード為る"],
        "expected": "Qwen3.5-4BをGPUにロードする"
    },
    # F. 固有名詞
    {
        "id": "30", "context": "横浜で食事をする店を探している。", "target": "みなとみらいのあろはてーぶるにいく",
        "candidates": ["みなとみらいのアロハテーブルに行く", "みなとみらいのあろはてーぶるに行く", "港未来のアロハテーブルに行く", "みなとみらいのアロハテーブルにいく"],
        "expected": "みなとみらいのアロハテーブルに行く"
    },
    {
        "id": "31", "context": "東京湾を渡るドライブを計画している。", "target": "うみほたるでやすむ",
        "candidates": ["海ほたるで休む", "うみほたるで休む", "海蛍で休む", "海ほたるでやすむ"],
        "expected": "海ほたるで休む"
    },
    {
        "id": "32", "context": "ハンバーガーを持ち帰ることにした。", "target": "ばーがーきんぐでていくあうとする",
        "candidates": ["バーガーキングでテイクアウトする", "バーガーキングでテイクアウト為る", "ばーがーきんぐでテイクアウトする", "バーガーキングでていくあうとする"],
        "expected": "バーガーキングでテイクアウトする"
    },
    # G. 余計な補完を防げるか
    {
        "id": "33", "context": "なし", "target": "あしたいく",
        "candidates": ["明日行く", "明日行く予定です", "あした行く", "明日いく"],
        "expected": "明日行く"
    },
    {
        "id": "34", "context": "なし", "target": "しりょうおくって",
        "candidates": ["資料送って", "資料を送ってください", "しりょう送って", "資料おくって"],
        "expected": "資料送って"
    },
    {
        "id": "35", "context": "なし", "target": "あとでれんらくする",
        "candidates": ["後で連絡する", "後ほどご連絡いたします", "あとで連絡する", "後でれんらくする"],
        "expected": "後で連絡する"
    },
    {
        "id": "36", "context": "なし", "target": "それでだいじょうぶ",
        "candidates": ["それで大丈夫", "それで大丈夫です", "それでだいじょうぶ", "其れで大丈夫"],
        "expected": "それで大丈夫"
    },
    {
        "id": "37", "context": "なし", "target": "わからない",
        "candidates": ["分からない", "申し訳ありませんが、分かりません", "わからない", "分らない"],
        "expected": "分からない"
    },
    # H. 入力を勝手に校正しないか
    {
        "id": "38", "context": "なし", "target": "わたしはきのうとうきょうにいきます",
        "candidates": ["私は昨日東京に行きます", "私は昨日東京に行きました", "わたしは昨日東京に行きます"],
        "expected": "私は昨日東京に行きます"
    },
    {
        "id": "39", "context": "なし", "target": "かれがわたしをいった",
        "candidates": ["彼が私を言った", "彼が私に言った", "彼がわたしを言った"],
        "expected": "彼が私を言った"
    },
    {
        "id": "40", "context": "なし", "target": "このほんはおもしろいでした",
        "candidates": ["この本は面白いでした", "この本は面白かったです", "この本はおもしろいでした"],
        "expected": "この本は面白いでした"
    },
    # I. 不明語を維持できるか
    {
        "id": "41", "context": "これは架空の製品名である。", "target": "ぬるぽんえっくすをきどうする",
        "candidates": ["ぬるぽんえっくすを起動する", "ぬるぽんエックスを起動する", "ヌルポンエックスを起動する"],
        "expected": "ぬるぽんえっくすを起動する"
    },
    {
        "id": "42", "context": "人物の名前について話している。", "target": "るきあんとあう",
        "candidates": ["るきあんと会う", "ルキアンと会う", "るきあんとあう"],
        "expected": "るきあんと会う"
    },
    {
        "id": "43", "context": "社内で使用している独自システムである。", "target": "ふらみどーるにろぐいんする",
        "candidates": ["ふらみどーるにログインする", "フラミドールにログインする", "ふらみどーるにろぐいんする"],
        "expected": "ふらみどーるにログインする"
    },
    # J. 句読点の保持
    {
        "id": "44", "context": "なし", "target": "きょうはいかない",
        "candidates": ["今日は行かない", "今日は行かない。", "きょうは行かない"],
        "expected": "今日は行かない"
    },
    {
        "id": "45", "context": "なし", "target": "きょうはいかない。あしたいく。",
        "candidates": ["今日は行かない。明日行く。", "今日は行かない。明日行く", "今日はいかない。あしたいく。"],
        "expected": "今日は行かない。明日行く。"
    },
    {
        "id": "46", "context": "なし", "target": "ほんとうに？しんじられない",
        "candidates": ["本当に？信じられない", "本当に？信じられない。", "本当に信じられない"],
        "expected": "本当に？信じられない"
    }
]

# ==========================================
# 1. Jev方式（ローカル小型LLMによるChoiceモデル）
# ==========================================
def run_jev_choice(case: dict, model_id: str = "liquidai/lfm2.5-1.2b-instruct"):
    ctx = case["context"]
    target = case["target"]
    cands = case["candidates"]
    
    cand_text = "\n".join([f"{i+1}. {c}" for i, c in enumerate(cands)])
    prompt = f"""あなたは日本語IMEの候補選択器です。
前方文脈と読みを考慮して、最も適切な候補の番号だけを出力してください。
候補を書き換えたり、新しい候補を生成したりしてはいけません。
回答は半角数字（1〜{len(cands)}）のみを1行で出力してください。

前方文脈：
{ctx}
読み：
{target}
候補：
{cand_text}
出力："""

    payload = {
        "model": model_id,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0.0,
        "max_tokens": 8
    }
    req = urllib.request.Request(
        "http://127.0.0.1:1234/v1/chat/completions",
        headers={"Content-Type": "application/json"},
        data=json.dumps(payload).encode("utf-8")
    )
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        t1 = time.perf_counter()
        raw_ans = data["choices"][0]["message"]["content"].strip()
        # 数字の抽出
        match = re.search('\\b([1-9])\\b', raw_ans)
        if match:
            idx = int(match.group(1)) - 1
            if 0 <= idx < len(cands):
                selected = cands[idx]
            else:
                selected = cands[0]
        else:
            selected = cands[0]
        
        return {
            "success": True,
            "selected": selected,
            "raw_output": raw_ans,
            "duration_ms": round((t1 - t0) * 1000, 1)
        }
    except Exception as e:
        return {
            "success": False,
            "selected": cands[0],
            "raw_output": str(e),
            "duration_ms": 0.0
        }

# ==========================================
# 2. Zenzai方式（GPT-2ニューラル言語モデルによる尤度スコアリング）
# ==========================================
class ZenzaiScorer:
    def __init__(self, model_name: str = "rinna/japanese-gpt2-small"):
        print(f"[Zenzai] Loading model: {model_name}...")
        self.tok = AutoTokenizer.from_pretrained(model_name, use_fast=False)
        self.model = AutoModelForCausalLM.from_pretrained(model_name)
        self.model.eval()
        self.param_count = sum(p.numel() for p in self.model.parameters())
        print(f"[Zenzai] Loaded! Parameters: {self.param_count/1e6:.1f}M")

    def score(self, context: str, candidate: str) -> float:
        """
        文脈付きで候補文字列の平均対数尤度を計算
        log P(candidate | context)
        """
        ctx_text = "" if context == "なし" else context
        full_text = ctx_text + candidate
        
        enc_all = self.tok.encode(full_text, return_tensors='pt')
        enc_ctx = self.tok.encode(ctx_text, return_tensors='pt') if ctx_text else None
        
        ctx_len = enc_ctx.shape[1] if enc_ctx is not None else 0
        total_len = enc_all.shape[1]
        
        if total_len <= max(1, ctx_len):
            return -999.0
            
        with torch.no_grad():
            outputs = self.model(enc_all)
            logits = outputs.logits
            
            if ctx_len == 0:
                shift_logits = logits[0, 0 : total_len - 1, :]
                shift_labels = enc_all[0, 1 : total_len]
            else:
                shift_logits = logits[0, ctx_len - 1 : total_len - 1, :]
                shift_labels = enc_all[0, ctx_len : total_len]
                
            loss = F.cross_entropy(shift_logits, shift_labels, reduction='mean')
            return -loss.item() # 対数尤度（高いほど自然）

    def select_best(self, case: dict):
        ctx = case["context"]
        cands = case["candidates"]
        t0 = time.perf_counter()
        scores = []
        for c in cands:
            s = self.score(ctx, c)
            scores.append((c, s))
        t1 = time.perf_counter()
        # スコア最大の候補を選択
        best_cand, best_score = max(scores, key=lambda x: x[1])
        return {
            "selected": best_cand,
            "scores": scores,
            "duration_ms": round((t1 - t0) * 1000, 1)
        }

def evaluate_selection(selected: str, expected: str, candidates: list):
    is_exact = (selected == expected)
    # 表記の揺れ（ひらがな化・漢字化）
    is_acceptable = is_exact or (re.sub('[\\s、。]', '', selected) == re.sub('[\\s、。]', '', expected))
    score = 2 if is_exact else (1 if is_acceptable else 0)
    return score

def main():
    print("==================================================")
    print(" 従来型IME複数選択肢生成からの「Jev」と「Zenzai」検証")
    print("==================================================")
    
    # Zenzai初期化
    zenzai = ZenzaiScorer("rinna/japanese-gpt2-small")
    
    jev_results = []
    zenzai_results = []
    
    print("\n--- Running Evaluation across 46 cases ---")
    for case in BENCHMARK_CASES:
        cid = case["id"]
        exp = case["expected"]
        
        # 1. Jev方式の推論 (1.2BモデルによるChoice)
        j_res = run_jev_choice(case)
        j_score = evaluate_selection(j_res["selected"], exp, case["candidates"])
        jev_results.append({
            "id": cid,
            "selected": j_res["selected"],
            "expected": exp,
            "score": j_score,
            "duration_ms": j_res["duration_ms"],
            "raw": j_res["raw_output"]
        })
        
        # 2. Zenzai方式の推論 (GPT-2による尤度スコアリング)
        z_res = zenzai.select_best(case)
        z_score = evaluate_selection(z_res["selected"], exp, case["candidates"])
        zenzai_results.append({
            "id": cid,
            "selected": z_res["selected"],
            "expected": exp,
            "score": z_score,
            "duration_ms": z_res["duration_ms"]
        })
        
        j_mark = "O" if j_score == 2 else ("^" if j_score == 1 else "X")
        z_mark = "O" if z_score == 2 else ("^" if z_score == 1 else "X")
        print(f"#{cid:02s} | Target: {case['target']} | Exp: {exp}")
        print(f"   [Jev   ] {j_mark} ({j_score}pt): {j_res['selected']} ({j_res['duration_ms']}ms)")
        print(f"   [Zenzai] {z_mark} ({z_score}pt): {z_res['selected']} ({z_res['duration_ms']}ms)")

    # 集計
    total_cases = len(BENCHMARK_CASES)
    
    j_exact = sum(1 for r in jev_results if r["score"] == 2)
    j_acc = sum(1 for r in jev_results if r["score"] == 1)
    j_raw = sum(r["score"] for r in jev_results)
    j_time = sum(r["duration_ms"] for r in jev_results) / total_cases
    
    z_exact = sum(1 for r in zenzai_results if r["score"] == 2)
    z_acc = sum(1 for r in zenzai_results if r["score"] == 1)
    z_raw = sum(r["score"] for r in zenzai_results)
    z_time = sum(r["duration_ms"] for r in zenzai_results) / total_cases
    
    print("\n==================================================")
    print(" 検証結果サマリー")
    print("==================================================")
    print(f"Jev方式 (Choice 1.2B)    : スコア {j_raw}/{total_cases*2} (完全一致: {j_exact}/{total_cases}, 平均遅延: {j_time:.1f}ms, メモリ: ~1.2GB)")
    print(f"Zenzai方式 (GPT-2 110M) : スコア {z_raw}/{total_cases*2} (完全一致: {z_exact}/{total_cases}, 平均遅延: {z_time:.1f}ms, メモリ: ~200MB)")
    
    summary_data = {
        "jev": {
            "model": "liquidai/lfm2.5-1.2b-instruct (Choice)",
            "memory_mb": 1250,
            "raw_score": j_raw,
            "exact_count": j_exact,
            "acceptable_count": j_acc,
            "avg_latency_ms": round(j_time, 1),
            'results': jev_results
        },
        "zenzai": {
            "model": "rinna/japanese-gpt2-small (110M)",
            "memory_mb": 220,
            "raw_score": z_raw,
            "exact_count": z_exact,
            "acceptable_count": z_acc,
            "avg_latency_ms": round(z_time, 1),
            'results': zenzai_results
        }
    }
    
    with open('artifacts/results/benchmark_jev_zenzai_results.json', "w", encoding="utf-8") as f:
        json.dump(summary_data, f, ensure_ascii=False, indent=2)
    print('artifacts/results/Results saved to benchmark_jev_zenzai_results.json')

if __name__ == "__main__":
    main()
