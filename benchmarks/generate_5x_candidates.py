# -*- coding: utf-8 -*-
"""
kana_kanji_llm_testset_5x.jsonl (全230問) に対する
案C（従来型IME候補選択）ベンチマーク用 候補リスト（candidates）生成スクリプト

従来型IME（Mozc/Google日本語入力等）が出力する代表的な4候補をシミュレート：
- 第1〜第4候補の中に、正解（expected）、同音異義語、文節・表記揺れ、ひらがな混じり等を含める。
- ランダムではなく、従来IMEで実際に出る現実的な候補群を構成する。
"""

import json
import re
import sys

sys.stdout.reconfigure(encoding='utf-8')

HOMOPHONE_MAP = {
    "きしゃ": ["記者", "汽車", "貴社", "帰社"],
    "はし": ["橋", "箸", "端"],
    "あめ": ["雨", "飴"],
    "いしゃ/はいしゃ": ["医者", "歯医者"],
    "しかい": ["司会", "歯科医", "視界", "死海"],
    "かいじょう": ["会場", "海上", "開場", "解錠"],
    "こうせい": ["構成", "校正", "鋼製", "後世", "公正"],
    "ふきゅう": ["普及", "復旧"],
    "せいせい": ["精算", "精製", "生成"],
    "しょうひん": ["商品", "賞品"],
    "きせい": ["帰省", "規制", "既成", "気勢"],
    "しよう": ["仕様", "使用", "私用", "試用"],
    "しりょう": ["史料", "資料", "飼料", "思量"],
    "こうか": ["効果", "高価", "硬貨", "降下"],
    "こうか/こうそく": ["硬貨", "校則", "高速", "拘束"],
    "きかい": ["機械", "機会", "器械"],
    "あう": ["合意", "体に合う", "会う", "遭う"],
    "しょうにん/にんしょう": ["承認", "認証", "証人", "商人"],
    "みる": ["観る", "診る", "見る", "看る"],
    "ひょう": ["標高", "票", "表", "氷"],
    "こうよう/こよう": ["紅葉", "雇用", "効用", "公用"],
    "はいたつ/せいさく": ["配達", "政策", "製作", "配属"],
    "へんかん/のりかえ": ["変換", "乗り換える", "返還", "変色"],
    "ほしょう": ["保証", "補償", "保障"],
    "しゅうぜん/しゅうれん": ["修繕", "修練", "集積", "終点"],
    "たいしょう": ["対象", "対照", "大正", "対称"],
    "いどう": ["移動", "異動", "緯度"],
    "かんそう": ["乾燥", "感想", "完走", "歓送"],
    "かんしん": ["感心", "関心", "歓心"],
    "きょうちょう": ["強調", "協調", "同調"],
    "けっこう": ["結構", "血行", "決行", "欠航"],
    "しんこう": ["進行", "振興", "信仰", "深奥"],
    "せいさく": ["制作", "製作", "政策"],
    "そうぞう": ["想像", "創造", "相殺"],
    "たいせい": ["態勢", "体制", "大勢", "耐性"],
    "てんかん": ["転換", "点呼", "てんかん"],
    "とうせん": ["当選", "当籤", "当座"],
    "はかる": ["測る", "計る", "図る", "量る"],
    "へいこう": ["並行", "平衡", "平行", "閉口"],
    "ほうもん": ["訪問", "放電", "放任"],
    "めんかい": ["面会", "面談", "明快"],
    "ゆうこう": ["有効", "友好", "遊興"],
    "ようし": ["用紙", "要旨", "容姿", "養子"],
    "りれき": ["履歴", "利潤", "利器"],
    "れんけい": ["連携", "連係", "連打"]
}

def build_candidates_for_case(case: dict) -> list[str]:
    expected = case["expected"].strip()
    target = case["target"].strip()
    cat = case.get("category", "")
    focus = case.get("focus", "")
    forbidden = case.get("forbidden_examples", [])

    candidates = [expected]

    # 1. 同音異義語カテゴリ
    if "同音異義語" in cat:
        # focus に対応する単語リストを探索
        words = HOMOPHONE_MAP.get(focus, [])
        if not words:
            # スラッシュ分割
            for f_part in focus.split("/"):
                words.extend(HOMOPHONE_MAP.get(f_part, []))
        
        # expected の中に含まれるキーワードを他の同音語で置換して誤変換候補を作成
        for w in words:
            # expected の中の漢字部分を別の漢字に置換
            for alt in words:
                if alt != w and w in expected:
                    cand = expected.replace(w, alt, 1)
                    if cand not in candidates and cand != expected:
                        candidates.append(cand)
        
        # 足りない場合はひらがな戻しや部分変換
        if len(candidates) < 4:
            # ターゲットひらがなを先頭や末尾だけ平仮名にしたもの
            cand_hira = target
            if cand_hira not in candidates:
                candidates.append(cand_hira)
        
        # まだ足りない場合は語尾をひらがな化
        if len(candidates) < 4:
            cand_partial = expected
            if "する" in expected:
                cand_partial = expected.replace("する", "した")
            elif "した" in expected:
                cand_partial = expected.replace("した", "する")
            elif "を" in expected:
                cand_partial = expected.replace("を", "の")
            if cand_partial not in candidates:
                candidates.append(cand_partial)

    # 2. 基本変換カテゴリ
    elif "基本変換" in cat:
        # 典型的なIME候補：1. 正解、2. 一部ひらがな、3. 全部ひらがな、4. 誤変換
        # 例えば「今日は天気がいい」->「きょうは天気がいい」「今日はてんきがいい」「きょうはてんきがいい」
        hira = target
        candidates.append(hira)
        
        # 部分ひらがな（最初の2文字をひらがなにするなど）
        # 簡単なルール：漢字を1箇所ひらがなに
        cand2 = expected
        if len(expected) >= 4:
            # 漢字を含む最初の単語をひらがな化
            cand2 = target[:3] + expected[2:]
            if cand2 not in candidates and cand2 != expected:
                candidates.append(cand2)
        
        # 別の漢字候補（同音誤変換風）
        cand3 = expected.replace("確認", "確信").replace("設定", "判定").replace("開始", "怪死").replace("改善", "改選").replace("記録", "起録")
        if cand3 == expected:
            cand3 = expected.replace("する", "為る") if "する" in expected else expected + "。"
        if cand3 not in candidates:
            candidates.append(cand3)

    # 3. 英数字・保護文字列カテゴリ
    elif "英数字" in cat:
        # 英数字の全角化、小文字化、カタカナ化
        # 1. 正解（半角英数）
        # 2. 全角英数
        zen_map = str.maketrans("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
                                "０１２３４５６７８９ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ")
        cand_zen = expected.translate(zen_map)
        if cand_zen != expected:
            candidates.append(cand_zen)
        # 3. ひらがなターゲット
        candidates.append(target)
        # 4. カタカナ化や小文字化
        cand_lower = expected.lower()
        if cand_lower not in candidates:
            candidates.append(cand_lower)

    # 4. 校正・補完禁止カテゴリ
    elif "校正" in cat or "補完" in cat:
        # 1. 正解（入力維持・単なる漢字化）
        # 2. forbidden_examples（勝手な補完・敬語化）
        for fb in forbidden:
            if fb not in candidates:
                candidates.append(fb)
        # 3. ひらがなそのまま
        if target not in candidates:
            candidates.append(target)
        # 4. 別の敬語補完
        extra_cand = expected + "です"
        if extra_cand not in candidates:
            candidates.append(extra_cand)

    # 5. 未知語・固有名詞カテゴリ
    elif "未知語" in cat or "固有名詞" in cat:
        # 1. 正解（未知語ひらがな維持＋動詞漢字化）
        # 2. 全部ひらがな
        if target not in candidates:
            candidates.append(target)
        # 3. 無理な当て字（未知語部分を漢字に）
        # 4. カタカナ化
        kata_cand = expected
        # ひらがなをカタカナに変換
        kata_cand = "".join([chr(ord(ch) + 96) if 0x3041 <= ord(ch) <= 0x3096 else ch for ch in expected])
        if kata_cand not in candidates:
            candidates.append(kata_cand)

    # 4件になるようにパディング
    while len(candidates) < 4:
        dummy = target if target not in candidates else f"{target}（候補）"
        candidates.append(dummy)

    # 4件に切り詰め
    candidates = candidates[:4]

    # 従来型IMEのように、正解が常に1番目だとカンニングになるため、
    # 各問題のIDに基づいて正解の配置順をシャッフル（再現性ある固定シード）
    # 例: id % 4 の位置に正解を配置
    target_pos = (case["id"] * 7) % len(candidates)
    if target_pos != 0:
        candidates[0], candidates[target_pos] = candidates[target_pos], candidates[0]

    return candidates

def main():
    from pathlib import Path
    base_dir = Path(__file__).resolve().parents[1]
    data_dir = base_dir / "data"
    data_dir.mkdir(exist_ok=True)

    in_file = data_dir / 'kana_kanji_llm_testset_5x.jsonl'
    if not in_file.exists():
        in_file = base_dir / 'data/kana_kanji_llm_testset_5x.jsonl'

    with open(in_file, "r", encoding="utf-8") as f:
        cases = [json.loads(line) for line in f if line.strip()]

    benchmark_cases = []
    for c in cases:
        cands = build_candidates_for_case(c)
        # 正解のインデックス（1-based）
        expected = c["expected"].strip()
        correct_idx = -1
        for i, cand in enumerate(cands):
            if cand == expected:
                correct_idx = i + 1
                break
        
        benchmark_cases.append({
            "id": c["id"],
            "category": c.get("category", ""),
            "context": c.get("left_context", "なし"),
            "target": c.get("target", ""),
            "expected": expected,
            "candidates": cands,
            "correct_idx": correct_idx,
            "focus": c.get("focus", "")
        })

    out_file = data_dir / 'benchmark_5x_cands.json'
    with open(out_file, "w", encoding="utf-8") as f:
        json.dump(benchmark_cases, f, ensure_ascii=False, indent=2)

    print(f"Generated {len(benchmark_cases)} benchmark cases with realistic candidates -> {out_file}")
    
    # 最初の5ケースを表示確認
    for b in benchmark_cases[:5]:
        print(f"\n[ID {b['id']}] Context: {b['context']}")
        print(f"  Target: {b['target']} | Correct ({b['correct_idx']}): {b['expected']}")
        for i, c in enumerate(b['candidates']):
            mark = "(*)" if i + 1 == b['correct_idx'] else "   "
            print(f"   {i+1}. {c} {mark}")

if __name__ == "__main__":
    main()
