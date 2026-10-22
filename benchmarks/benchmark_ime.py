# -*- coding: utf-8 -*-
"""
LM Studio 日本語IME変換性能ベンチマーク
対象4モデル:
1. lfm2.5-230m-fable-5
2. lfm2.5-350m
3. liquidai/lfm2.5-1.2b-instruct
4. lmstudio-community/lfm2.5-2.6b
"""

import sys
import os
import json
import time
import urllib.request
import re

sys.stdout.reconfigure(encoding='utf-8')

# 1. 推奨システムプロンプト（長い版）
SYSTEM_PROMPT_LONG = """あなたは日本語IMEのかな漢字変換エンジンです。
ユーザーから与えられた「変換対象」を、自然な漢字かな交じり文へ変換してください。
# 最重要規則
1. かな漢字変換だけを行ってください。
2. 入力されていない意味、単語、説明、敬語、主語、述語を追加してはいけません。
3. 入力に含まれる意味や単語を削除してはいけません。
4. 言い換え、要約、校正、回答、会話をしてはいけません。
5. 原則として、出力をひらがなの読みへ戻したとき、変換対象の読みと一致する表記を選んでください。
6. 助詞、語順、文体は変更しないでください。
7. 句読点は、入力に含まれている場合だけ維持してください。入力にない句読点を追加してはいけません。
8. 数字、英字、記号、URL、メールアドレス、製品名、コードは可能な限り原文を保持してください。
9. 前方文脈は変換判断のためだけに使用し、出力に含めてはいけません。
10. 変換対象だけを出力してください。
11. 説明、注釈、引用符、コードブロック、前置き、候補番号を出力してはいけません。
12. 判断できない部分は、無理に漢字へ変換せず、ひらがなのまま残してください。
# 同音異義語の判断
前方文脈と文全体の意味を使い、同音異義語を選択してください。
例：
前方文脈：新聞社で働いている。
変換対象：きしゃがしゅざいした
出力：記者が取材した
前方文脈：駅のホームで待っていた。
変換対象：きしゃがとうちゃくした
出力：汽車が到着した
# 出力形式
変換後の文字列だけを1行で出力してください。"""

# 3. より厳格な短縮プロンプト（短い版）
SYSTEM_PROMPT_SHORT = """日本語IMEとして動作してください。
入力の「変換対象」だけを、前方文脈に基づいて漢字かな交じり文へ変換してください。
厳守事項：
・かな漢字変換以外は禁止
・語句の追加、削除、言い換えは禁止
・助詞と語順の変更は禁止
・入力にない句読点の追加は禁止
・前方文脈は出力しない
・不明な部分はひらがなのまま残す
・回答は変換結果だけを1行で出力する"""

# テスト文章定義（全46問）
TEST_CASES = [
    # A. 基本変換
    {"id": "01", "category": "A. 基本変換", "context": "なし", "target": "きょうはてんきがいい", "expected": ["今日は天気がいい"], "acceptable": ["きょうは天気がいい", "今日はてんきがいい"], "bad_examples": []},
    {"id": "02", "category": "A. 基本変換", "context": "なし", "target": "あしたはとうきょうえきでまちあわせる", "expected": ["明日は東京駅で待ち合わせる"], "acceptable": ["明日は東京駅でまちあわせる", "あしたは東京駅で待ち合わせる"], "bad_examples": []},
    {"id": "03", "category": "A. 基本変換", "context": "なし", "target": "しりょうをかくにんしてからへんしんする", "expected": ["資料を確認してから返信する"], "acceptable": ["資料を確認してからへんしんする"], "bad_examples": []},
    {"id": "04", "category": "A. 基本変換", "context": "なし", "target": "でんしゃがおくれている", "expected": ["電車が遅れている"], "acceptable": ["電車がおくれている"], "bad_examples": []},
    {"id": "05", "category": "A. 基本変換", "context": "なし", "target": "にほんごにゅうりょくをかいぜんする", "expected": ["日本語入力を改善する"], "acceptable": ["日本語にゅうりょくを改善する"], "bad_examples": []},
    # B. 同音異義語
    {"id": "06", "category": "B. 同音異義語", "context": "新聞社から取材班が派遣された。", "target": "きしゃがじけんげんばをしゅざいした", "expected": ["記者が事件現場を取材した"], "acceptable": ["記者が事件現場をしゅざいした"], "bad_examples": []},
    {"id": "07", "category": "B. 同音異義語", "context": "駅のホームに大勢の乗客が並んでいた。", "target": "きしゃがえきにとうちゃくした", "expected": ["汽車が駅に到着した"], "acceptable": ["汽車が駅にとうちゃくした"], "bad_examples": ["記者が駅に到着した", "貴社が駅に到着した"]},
    {"id": "08", "category": "B. 同音異義語", "context": "取引先へのメールを書いている。", "target": "きしゃのせいひんをけんとうしています", "expected": ["貴社の製品を検討しています"], "acceptable": ["貴社のせいひんを検討しています", "貴社の製品をけんとうしています"], "bad_examples": ["記者の製品を検討しています", "汽車の製品を検討しています"]},
    {"id": "09", "category": "B. 同音異義語", "context": "朝から奥歯が痛む。", "target": "しかいにみてもらう", "expected": ["歯科医に診てもらう"], "acceptable": ["歯科医に見てもらう", "歯科医にみてもらう", "しかいに診てもらう"], "bad_examples": ["司会に見てもらう", "司会に診てもらう", "視界に見てもらう"]},
    {"id": "10", "category": "B. 同音異義語", "context": "結婚式の進行について相談した。", "target": "しかいをともだちにたのむ", "expected": ["司会を友達に頼む"], "acceptable": ["司会を友人に頼む", "司会をともだちに頼む", "司会を友達にたのむ"], "bad_examples": ["歯科医を友達に頼む", "視界を友達に頼む"]},
    {"id": "11", "category": "B. 同音異義語", "context": "イベントの開催場所を探している。", "target": "かいじょうをよやくする", "expected": ["会場を予約する"], "acceptable": ["会場をよやくする"], "bad_examples": ["海上を予約する", "開場を予約する", "解錠を予約する"]},
    {"id": "12", "category": "B. 同音異義語", "context": "船から周囲の様子を確認した。", "target": "かいじょうはおだやかだった", "expected": ["海上は穏やかだった"], "acceptable": ["海上はおだやかだった"], "bad_examples": ["会場は穏やかだった", "開場は穏やかだった"]},
    {"id": "13", "category": "B. 同音異義語", "context": "コンサートは午後6時に始まる。", "target": "ごごごじにかいじょうする", "expected": ["午後5時に開場する"], "acceptable": ["午後五時に開場する", "ごご5時に開場する"], "bad_examples": ["午後5時に会場する", "午後5時に海上する", "午後5時に解錠する"]},
    {"id": "14", "category": "B. 同音異義語", "context": "暗証番号を入力した。", "target": "どあをかいじょうする", "expected": ["ドアを解錠する"], "acceptable": ["ドアを開錠する", "どあを解錠する"], "bad_examples": ["ドアを会場する", "ドアを海上する", "ドアを開場する"]},
    # C. 文脈によって変換が変わるケース
    {"id": "15", "category": "C. 文脈分岐", "context": "熱が下がらず、内科を受診することになった。", "target": "きょうはいしゃにいく", "expected": ["今日は医者に行く"], "acceptable": ["きょうは医者に行く", "今日は医者にいく"], "bad_examples": ["今日歯医者に行く"]},
    {"id": "16", "category": "C. 文脈分岐", "context": "奥歯の詰め物が取れてしまった。", "target": "きょうはいしゃにいく", "expected": ["今日歯医者に行く"], "acceptable": ["きょう歯医者に行く", "今日歯医者にいく"], "bad_examples": ["今日は医者に行く"]},
    {"id": "17", "category": "C. 文脈分岐", "context": "川の向こう岸へ歩いて渡った。", "target": "はしをわたる", "expected": ["橋を渡る"], "acceptable": ["橋をわたる"], "bad_examples": ["箸を渡る", "端を渡る"]},
    {"id": "18", "category": "C. 文脈分岐", "context": "食事を始める前に手を合わせた。", "target": "はしをもつ", "expected": ["箸を持つ"], "acceptable": ["箸をもつ"], "bad_examples": ["橋を持つ", "端を持つ"]},
    {"id": "19", "category": "C. 文脈分岐", "context": "長いベンチに二人で座った。", "target": "はしにすわる", "expected": ["端に座る"], "acceptable": ["端にすわる"], "bad_examples": ["橋に座る", "箸に座る"]},
    {"id": "20", "category": "C. 文脈分岐", "context": "空が暗くなり、雷が聞こえてきた。", "target": "あめがふってきた", "expected": ["雨が降ってきた"], "acceptable": ["雨がふってきた"], "bad_examples": ["飴が降ってきた"]},
    {"id": "21", "category": "C. 文脈分岐", "context": "袋から甘いお菓子を取り出した。", "target": "あめをひとつたべた", "expected": ["飴を一つ食べた", "飴をひとつ食べた"], "acceptable": ["飴を1つ食べた"], "bad_examples": ["雨を一つ食べた", "雨をひとつ食べた"]},
    # D. 長い文脈
    {"id": "22", "category": "D. 長い文脈", "context": "新しいパソコンにローカルの言語モデルを導入した。モデルをGPUへ読み込んだあと、APIサーバーを起動し、同じネットワークにあるスマートフォンから接続を試した。", "target": "せつぞくにはせいこうしたがもでるのしゅとくにしっぱいした", "expected": ["接続には成功したがモデルの取得に失敗した"], "acceptable": ["接続には成功したがもでるの取得に失敗した", "接続には成功したがモデルのしゅとくに失敗した"], "bad_examples": []},
    {"id": "23", "category": "D. 長い文脈", "context": "高速道路では前の車との距離を保つ機能を使用した。渋滞中は便利だったが、停止直前の制御に違和感があった。", "target": "ぶれーきがおそくさいごにかっくんぶれーきになった", "expected": ["ブレーキが遅く最後にカックンブレーキになった", "ブレーキが遅く最後にかっくんブレーキになった"], "acceptable": ["ブレーキが遅く最後にカックンぶれーきになった"], "bad_examples": []},
    {"id": "24", "category": "D. 長い文脈", "context": "ニューラルネットワークには複数の専門モデルがあり、入力に応じて一部だけが選択される。", "target": "えきすぱーとのわりあてをさいてきかする", "expected": ["エキスパートの割り当てを最適化する"], "acceptable": ["エキスパートのわりあてを最適化する", "エキスパートの割当てを最適化する"], "bad_examples": []},
    # E. 英数字と技術用語の保持
    {"id": "25", "category": "E. 英数字保持", "context": "なし", "target": "LM Studioでろーかるさーばーをきどうする", "expected": ["LM Studioでローカルサーバーを起動する"], "acceptable": ["LM Studioでローカルサーバーをきどうする"], "bad_examples": []},
    {"id": "26", "category": "E. 英数字保持", "context": "なし", "target": "VRAM12GBでもでるをじっこうする", "expected": ["VRAM12GBでモデルを実行する"], "acceptable": ["VRAM12GBでもでるを実行する", "VRAM 12GBでモデルを実行する"], "bad_examples": []},
    {"id": "27", "category": "E. 英数字保持", "context": "なし", "target": "ESP32をWi-Fiにせつぞくする", "expected": ["ESP32をWi-Fiに接続する"], "acceptable": ["ESP32をWi-Fiにせつぞくする"], "bad_examples": []},
    {"id": "28", "category": "E. 英数字保持", "context": "なし", "target": "http://192.168.68.68:1234/v1/modelsにあくせすする", "expected": ["http://192.168.68.68:1234/v1/modelsにアクセスする"], "acceptable": ["http://192.168.68.68:1234/v1/modelsにあくせすする"], "bad_examples": []},
    {"id": "29", "category": "E. 英数字保持", "context": "なし", "target": "Qwen3.5-4BをGPUにろーどする", "expected": ["Qwen3.5-4BをGPUにロードする"], "acceptable": ["Qwen3.5-4BをGPUにろーどする"], "bad_examples": []},
    # F. 固有名詞
    {"id": "30", "category": "F. 固有名詞", "context": "横浜で食事をする店を探している。", "target": "みなとみらいのあろはてーぶるにいく", "expected": ["みなとみらいのアロハテーブルに行く"], "acceptable": ["みなとみらいのアロハテーブルにいく"], "bad_examples": []},
    {"id": "31", "category": "F. 固有名詞", "context": "東京湾を渡るドライブを計画している。", "target": "うみほたるでやすむ", "expected": ["海ほたるで休む", "海ほたるでやすむ", "うみほたるで休む"], "acceptable": ["海蛍で休む"], "bad_examples": []},
    {"id": "32", "category": "F. 固有名詞", "context": "ハンバーガーを持ち帰ることにした。", "target": "ばーがーきんぐでていくあうとする", "expected": ["バーガーキングでテイクアウトする"], "acceptable": ["バーガーキングでていくあうとする"], "bad_examples": []},
    # G. 余計な補完を防げるか
    {"id": "33", "category": "G. 補完防止", "context": "なし", "target": "あしたいく", "expected": ["明日行く"], "acceptable": ["あした行く", "明日いく", "あしたいく"], "bad_examples": ["明日行く予定です", "明日行きます", "あした行きます"]},
    {"id": "34", "category": "G. 補完防止", "context": "なし", "target": "しりょうおくって", "expected": ["資料送って"], "acceptable": ["資料おくって", "しりょう送って"], "bad_examples": ["資料を送ってください", "資料送ってください", "資料をお送りください"]},
    {"id": "35", "category": "G. 補完防止", "context": "なし", "target": "あとでれんらくする", "expected": ["後で連絡する"], "acceptable": ["あとで連絡する", "後でれんらくする"], "bad_examples": ["後ほどご連絡いたします", "後で連絡します", "後ほど連絡します"]},
    {"id": "36", "category": "G. 補完防止", "context": "なし", "target": "それでだいじょうぶ", "expected": ["それで大丈夫"], "acceptable": ["それでだいじょうぶ"], "bad_examples": ["それで大丈夫です", "それで大丈夫でしょうか"]},
    {"id": "37", "category": "G. 補完防止", "context": "なし", "target": "わからない", "expected": ["分からない"], "acceptable": ["わからない"], "bad_examples": ["申し訳ありませんが、分かりません", "分かりません", "わかりません"]},
    # H. 入力を勝手に校正しないか
    {"id": "38", "category": "H. 勝手な校正防止", "context": "なし", "target": "わたしはきのうとうきょうにいきます", "expected": ["私は昨日東京に行きます"], "acceptable": ["わたしは昨日東京に行きます", "私はきのう東京に行きます"], "bad_examples": ["私は昨日東京に行きました"]},
    {"id": "39", "category": "H. 勝手な校正防止", "context": "なし", "target": "かれがわたしをいった", "expected": ["彼が私を言った"], "acceptable": ["彼がわたしを言った", "かれが私を言った"], "bad_examples": ["彼が私に言った", "彼が私へ言った"]},
    {"id": "40", "category": "H. 勝手な校正防止", "context": "なし", "target": "このほんはおもしろいでした", "expected": ["この本は面白いでした"], "acceptable": ["この本はおもしろいでした", "このほんは面白いでした"], "bad_examples": ["この本は面白かったです", "この本は面白いです"]},
    # I. 不明語を維持できるか
    {"id": "41", "category": "I. 不明語維持", "context": "これは架空の製品名である。", "target": "ぬるぽんえっくすをきどうする", "expected": ["ぬるぽんえっくすを起動する", "ぬるぽんエックスを起動する"], "acceptable": ["ぬるぽんXを起動する", "ヌルポンエックスを起動する"], "bad_examples": []},
    {"id": "42", "category": "I. 不明語維持", "context": "人物の名前について話している。", "target": "るきあんとあう", "expected": ["るきあんと会う"], "acceptable": ["ルキアンと会う", "るきあんとあう"], "bad_examples": []},
    {"id": "43", "category": "I. 不明語維持", "context": "社内で使用している独自システムである。", "target": "ふらみどーるにろぐいんする", "expected": ["ふらみどーるにログインする", "フラミドールにログインする"], "acceptable": ["ふらみどーるにろぐいんする"], "bad_examples": []},
    # J. 句読点の保持
    {"id": "44", "category": "J. 句読点保持", "context": "なし", "target": "きょうはいかない", "expected": ["今日は行かない"], "acceptable": ["きょうは行かない", "今日はいかない"], "bad_examples": ["今日は行かない。", "きょうは行かない。"]},
    {"id": "45", "category": "J. 句読点保持", "context": "なし", "target": "きょうはいかない。あしたいく。", "expected": ["今日は行かない。明日行く。"], "acceptable": ["きょうは行かない。明日行く。", "今日は行かない。あした行く。"], "bad_examples": ["今日は行かない。明日行く", "今日はいかない、あしたいく"]},
    {"id": "46", "category": "J. 句読点保持", "context": "なし", "target": "ほんとうに？しんじられない", "expected": ["本当に？信じられない"], "acceptable": ["本当に?信じられない", "ほんとうに？信じられない"], "bad_examples": ["本当に？信じられない。", "本当に信じられない"]}
]

# ストリーミング逐次入力テスト定義
STREAMING_TESTS = [
    {
        "case_name": "ケース1：歯医者と医者",
        "context": "奥歯の詰め物が取れてしまった。",
        "steps": [
            "きょう",
            "きょうは",
            "きょうはい",
            "きょうはいし",
            "きょうはいしゃ",
            "きょうはいしゃに",
            "きょうはいしゃにいく"
        ],
        "final_expected": "今日歯医者に行く"
    },
    {
        "case_name": "ケース2：記者と汽車",
        "context": "駅で列車を待っている。",
        "steps": [
            "き",
            "きし",
            "きしゃ",
            "きしゃが",
            "きしゃがえ",
            "きしゃがえきに",
            "きしゃがえきにとうちゃくした"
        ],
        "final_expected": "汽車が駅に到着した"
    },
    {
        "case_name": "ケース3：長文の逐次変換",
        "context": "なし",
        "steps": [
            "きょう",
            "きょうは",
            "きょうはてんき",
            "きょうはてんきが",
            "きょうはてんきがいい",
            "きょうはてんきがいいので",
            "きょうはてんきがいいのでこうえん",
            "きょうはてんきがいいのでこうえんをさんぽする"
        ],
        "final_expected": "今日は天気がいいので公園を散歩する"
    }
]

def call_lm_studio(model: str, system_prompt: str, user_content: str, max_tokens: int = 128, timeout: int = 60):
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_content}
        ],
        "temperature": 0.0,
        "top_p": 1.0,
        "max_tokens": max_tokens,
        "stream": True
    }
    
    req = urllib.request.Request(
        "http://127.0.0.1:1234/v1/chat/completions",
        headers={"Content-Type": "application/json"},
        data=json.dumps(payload).encode("utf-8")
    )
    
    t_start = time.perf_counter()
    first_token_time = None
    first_content_time = None
    content_chunks = []
    reasoning_chunks = []
    
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            for line in response:
                line = line.decode("utf-8").strip()
                if not line or not line.startswith("data: "):
                    continue
                data_str = line[6:]
                if data_str == "[DONE]":
                    break
                try:
                    data = json.loads(data_str)
                    delta = data.get("choices", [{}])[0].get("delta", {})
                    if first_token_time is None:
                        first_token_time = time.perf_counter() - t_start
                    
                    r_text = delta.get("reasoning_content", "")
                    if r_text:
                        reasoning_chunks.append(r_text)
                        
                    c_text = delta.get("content", "")
                    if c_text:
                        if first_content_time is None:
                            first_content_time = time.perf_counter() - t_start
                        content_chunks.append(c_text)
                except Exception:
                    pass
        t_end = time.perf_counter()
        total_time = t_end - t_start
        if first_token_time is None:
            first_token_time = total_time
        if first_content_time is None:
            first_content_time = total_time
            
        full_content = "".join(content_chunks).strip()
        full_reasoning = "".join(reasoning_chunks).strip()
        
        return {
            "success": True,
            "raw_output": full_content,
            "reasoning_output": full_reasoning,
            "ttft": first_token_time,
            "content_ttft": first_content_time,
            "total_time": total_time,
            "content_tokens": len(content_chunks),
            "reasoning_tokens": len(reasoning_chunks)
        }
    except Exception as e:
        return {
            "success": False,
            "error": str(e),
            "raw_output": "",
            "reasoning_output": "",
            "ttft": 0.0,
            "content_ttft": 0.0,
            "total_time": 0.0,
            "content_tokens": 0,
            "reasoning_tokens": 0
        }

def evaluate_output(test_case: dict, output_text: str):
    out = output_text.strip()
    format_violation = False
    if "```" in out or out.startswith('"') or out.startswith('「') or "\n" in out or "出力：" in out or "変換後：" in out:
        format_violation = True
    
    clean_out = out
    for prefix in ["出力：", "出力:", "変換後：", "変換後:"]:
        if clean_out.startswith(prefix):
            clean_out = clean_out[len(prefix):].strip()
    clean_out = clean_out.strip('"`\'「」')
    first_line = clean_out.splitlines()[0] if clean_out else ""
    
    context_leak = False
    if test_case["context"] != "なし" and test_case["context"] in out:
        context_leak = True
    
    expected_list = test_case.get("expected", [])
    acceptable_list = test_case.get("acceptable", [])
    bad_examples = test_case.get("bad_examples", [])
    
    score = 0
    match_type = "wrong"
    
    if first_line in expected_list:
        score = 2
        match_type = "exact"
    elif first_line in acceptable_list:
        score = 1
        match_type = "acceptable"
    elif any(re.sub('[\\s、。]', '', first_line) == re.sub('[\\s、。]', '', exp) for exp in expected_list):
        score = 1
        match_type = "punct_or_space_diff"
    else:
        score = 0
        match_type = "wrong"
    
    addition_violation = False
    omission_violation = False
    correction_violation = False
    protect_violation = False
    
    cid = test_case["id"]
    
    # Gカテゴリ（補完防止）テスト33-37
    if cid in ["33", "34", "35", "36", "37"]:
        for bad in bad_examples:
            if bad in first_line:
                addition_violation = True
        if len(first_line) > len(expected_list[0]) + 3:
            addition_violation = True
            
    # Hカテゴリ（勝手な校正防止）テスト38-40
    if cid in ["38", "39", "40"]:
        for bad in bad_examples:
            if bad in first_line:
                correction_violation = True
                
    # Eカテゴリ（英数字保護）テスト25-29
    if cid == "25" and "LM Studio" not in first_line:
        protect_violation = True
    if cid == "26" and "VRAM12GB" not in first_line and "VRAM 12GB" not in first_line:
        protect_violation = True
    if cid == "27" and ("ESP32" not in first_line or "Wi-Fi" not in first_line):
        protect_violation = True
    if cid == "28" and "http://192.168.68.68:1234/v1/models" not in first_line:
        protect_violation = True
    if cid == "29" and ("Qwen3.5-4B" not in first_line or "GPU" not in first_line):
        protect_violation = True
        
    # Jカテゴリ（句読点）
    if cid == "44" and ("。" in first_line or "." in first_line):
        addition_violation = True
    if cid == "45" and ("。" not in first_line and "." not in first_line):
        omission_violation = True
    if cid == "46" and ("？" not in first_line and "?" not in first_line):
        omission_violation = True

    return {
        "score": score,
        "match_type": match_type,
        "first_line": first_line,
        "addition_violation": addition_violation,
        "omission_violation": omission_violation,
        "correction_violation": correction_violation,
        "format_violation": format_violation,
        "protect_violation": protect_violation,
        "context_leak": context_leak
    }

def format_prompt(context: str, target: str) -> str:
    return f"前方文脈：\n{context}\n変換対象：\n{target}"

def run_single_test(model: str, system_prompt: str, test_case: dict, max_tokens: int = 128):
    prompt = format_prompt(test_case["context"], test_case["target"])
    res = call_lm_studio(model, system_prompt, prompt, max_tokens=max_tokens)
    if not res["success"]:
        return {
            "test_id": test_case["id"],
            "category": test_case["category"],
            "target": test_case["target"],
            "raw_output": "",
            "first_line": "",
            "ttft": 0,
            "content_ttft": 0,
            "total_time": 0,
            "tps": 0,
            "score": 0,
            "match_type": "error",
            "error": res.get("error", "Unknown error"),
            "addition_violation": False,
            "omission_violation": False,
            "correction_violation": False,
            "format_violation": False,
            "protect_violation": False,
            "context_leak": False
        }
    
    eval_res = evaluate_output(test_case, res["raw_output"])
    tps = res["content_tokens"] / res["total_time"] if res["total_time"] > 0 else 0
    return {
        "test_id": test_case["id"],
        "category": test_case["category"],
        "target": test_case["target"],
        "raw_output": res["raw_output"],
        "first_line": eval_res["first_line"],
        "ttft": res["ttft"],
        "content_ttft": res["content_ttft"],
        "total_time": res["total_time"],
        "tps": tps,
        "score": eval_res["score"],
        "match_type": eval_res["match_type"],
        "addition_violation": eval_res["addition_violation"],
        "omission_violation": eval_res["omission_violation"],
        "correction_violation": eval_res["correction_violation"],
        "format_violation": eval_res["format_violation"],
        "protect_violation": eval_res["protect_violation"],
        "context_leak": eval_res["context_leak"]
    }

def run_streaming_case(model: str, system_prompt: str, stream_case: dict, max_tokens: int = 64):
    context = stream_case["context"]
    steps = stream_case["steps"]
    history = []
    for step in steps:
        prompt = format_prompt(context, step)
        res = call_lm_studio(model, system_prompt, prompt, max_tokens=max_tokens)
        clean_text = res["raw_output"].strip('"`\'「」')
        for prefix in ["出力：", "出力:", "変換後：", "変換後:"]:
            if clean_text.startswith(prefix):
                clean_text = clean_text[len(prefix):].strip()
        first_line = clean_text.splitlines()[0] if clean_text else ""
        history.append({
            "step": step,
            "output": first_line,
            "content_ttft": res["content_ttft"],
            "total_time": res["total_time"]
        })
    return {
        "case_name": stream_case["case_name"],
        "expected": stream_case["final_expected"],
        "final_output": history[-1]["output"] if history else "",
        "steps": history
    }

def calculate_metrics(results: list):
    total = len(results)
    if total == 0:
        return {}
    exact_count = sum(1 for r in results if r["score"] == 2)
    acceptable_count = sum(1 for r in results if r["score"] == 1)
    wrong_count = sum(1 for r in results if r["score"] == 0)
    
    add_count = sum(1 for r in results if r["addition_violation"])
    omit_count = sum(1 for r in results if r["omission_violation"])
    corr_count = sum(1 for r in results if r["correction_violation"])
    format_count = sum(1 for r in results if r["format_violation"])
    protect_count = sum(1 for r in results if r["protect_violation"])
    leak_count = sum(1 for r in results if r["context_leak"])
    
    exact_rate = exact_count / total
    acceptable_rate = acceptable_count / total
    add_rate = add_count / total
    omit_rate = omit_count / total
    corr_rate = corr_count / total
    format_rate = format_count / total
    protect_rate = protect_count / total
    
    comp_score = (
        exact_rate * 50
        + acceptable_rate * 20
        - add_rate * 100
        - omit_rate * 100
        - corr_rate * 60
        - format_rate * 40
        - protect_rate * 100
    )
    
    raw_score = sum(r["score"] for r in results)
    avg_ttft = sum(r["ttft"] for r in results) / total
    avg_content_ttft = sum(r["content_ttft"] for r in results) / total
    avg_total_time = sum(r["total_time"] for r in results) / total
    avg_tps = sum(r["tps"] for r in results) / total
    
    return {
        "total_cases": total,
        "raw_score": raw_score,
        "max_score": total * 2,
        "exact_count": exact_count,
        "acceptable_count": acceptable_count,
        "wrong_count": wrong_count,
        "exact_rate": exact_rate,
        "acceptable_rate": acceptable_rate,
        "add_count": add_count,
        "omit_count": omit_count,
        "corr_count": corr_count,
        "format_count": format_count,
        "protect_count": protect_count,
        "leak_count": leak_count,
        "add_rate": add_rate,
        "omit_rate": omit_rate,
        "corr_rate": corr_rate,
        "format_rate": format_rate,
        "protect_rate": protect_rate,
        "comprehensive_score": round(comp_score, 2),
        "avg_ttft_ms": round(avg_ttft * 1000, 1),
        "avg_content_ttft_ms": round(avg_content_ttft * 1000, 1),
        "avg_total_time_ms": round(avg_total_time * 1000, 1),
        "avg_tps": round(avg_tps, 1)
    }

def main():
    MODELS = [
        "lfm2.5-230m-fable-5",
        "lfm2.5-350m",
        "liquidai/lfm2.5-1.2b-instruct",
        "lmstudio-community/lfm2.5-2.6b"
    ]
    
    all_results = {}
    
    for model in MODELS:
        print(f"\n==========================================", flush=True)
        print(f"Testing Model: {model}", flush=True)
        print(f"==========================================", flush=True)
        
        max_tokens_test = 512 if "2.6b" in model else 128
        max_tokens_stream = 512 if "2.6b" in model else 64
        
        all_results[model] = {
            "long_prompt": {},
            "short_prompt": {},
            "streaming": []
        }
        
        # 1. 長いプロンプト (46問)
        print(f"--- Running 46 test cases with LONG prompt ---", flush=True)
        long_tests = []
        for tc in TEST_CASES:
            r = run_single_test(model, SYSTEM_PROMPT_LONG, tc, max_tokens=max_tokens_test)
            long_tests.append(r)
            status_char = "O" if r["score"] == 2 else ("^" if r["score"] == 1 else "X")
            print(f"[{status_char}] #{r['test_id']} ({r['score']}pt): {r['target']} -> {repr(r['first_line'])} ({round(r['total_time']*1000)}ms)", flush=True)
        
        long_metrics = calculate_metrics(long_tests)
        all_results[model]["long_prompt"]['results'] = long_tests
        all_results[model]["long_prompt"]["metrics"] = long_metrics
        print(f">> Long Prompt Summary: Raw Score={long_metrics['raw_score']}/{long_metrics['max_score']}, "
              f"Comp Score={long_metrics['comprehensive_score']}, Content TTFT={long_metrics['avg_content_ttft_ms']}ms, Total Time={long_metrics['avg_total_time_ms']}ms", flush=True)
        
        # 2. 短いプロンプト (46問)
        print(f"\n--- Running 46 test cases with SHORT prompt ---", flush=True)
        short_tests = []
        for tc in TEST_CASES:
            r = run_single_test(model, SYSTEM_PROMPT_SHORT, tc, max_tokens=max_tokens_test)
            short_tests.append(r)
            status_char = "O" if r["score"] == 2 else ("^" if r["score"] == 1 else "X")
            print(f"[{status_char}] #{r['test_id']} ({r['score']}pt): {r['target']} -> {repr(r['first_line'])} ({round(r['total_time']*1000)}ms)", flush=True)
            
        short_metrics = calculate_metrics(short_tests)
        all_results[model]["short_prompt"]['results'] = short_tests
        all_results[model]["short_prompt"]["metrics"] = short_metrics
        print(f">> Short Prompt Summary: Raw Score={short_metrics['raw_score']}/{short_metrics['max_score']}, "
              f"Comp Score={short_metrics['comprehensive_score']}, Content TTFT={short_metrics['avg_content_ttft_ms']}ms, Total Time={short_metrics['avg_total_time_ms']}ms", flush=True)
        
        # 3. ストリーミング逐次入力テスト (ケース1-3)
        print(f"\n--- Running Streaming Progressive Input Tests ---", flush=True)
        stream_results = []
        for sc in STREAMING_TESTS:
            sr = run_streaming_case(model, SYSTEM_PROMPT_LONG, sc, max_tokens=max_tokens_stream)
            stream_results.append(sr)
            print(f"[{sr['case_name']}] Final: '{sr['final_output']}' (Expected: '{sr['expected']}')", flush=True)
            for st in sr["steps"]:
                print(f"   '{st['step']}' -> '{st['output']}' ({round(st['total_time']*1000)}ms)", flush=True)
        all_results[model]["streaming"] = stream_results

        # 中間保存
        with open('artifacts/results/benchmark_results.json', "w", encoding="utf-8") as f:
            json.dump(all_results, f, ensure_ascii=False, indent=2)

    print("\nBenchmark completed. Results saved to benchmark_results.json", flush=True)

if __name__ == "__main__":
    main()
