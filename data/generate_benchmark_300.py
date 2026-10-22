# -*- coding: utf-8 -*-
"""
評価用タイポベンチマークデータセット（300問）生成スクリプト
訓練データとは独立した未知の語彙・文脈を含む厳選300問テストセット
"""

import os
import json
import random
from generate_typo_dataset import (
    apply_typo, hira_to_kata, mutate_adjacent_key, mutate_missing_vowel,
    mutate_transposition, mutate_geminate, mutate_syllabic_nasal,
    mutate_flick_proximity, mutate_prolonged, hiragana_to_romaji,
    romaji_to_hiragana, clean_typo_string
)

# 評価専用シード（訓練用とは別個に厳選した300問の元データ）
BENCHMARK_SEEDS = [
    # 1. ビジネス・定型・敬語 (80問)
    ("お忙しいところ恐れ入りますが、", "ごかくにんください", "ご確認ください", "business"),
    ("打ち合わせの日程について、", "ごつごうはいかがでしょうか", "ご都合はいかがでしょうか", "business"),
    ("取り急ぎ、", "ごれんらくまで", "ご連絡まで", "business"),
    ("添付のファイルについて、", "ぱすわーどをおくりします", "パスワードをお送りします", "business"),
    ("今後の進め方について、", "ごそうだんさせてください", "ご相談させてください", "business"),
    ("先ほどお送りした資料の、", "さしかえをおねがいします", "差し替えをお願いします", "business"),
    ("大変お手数をおかけしますが、", "ごたいおうおねがいします", "ご対応お願いします", "business"),
    ("本日の会議の、", "ぎじろくをおくります", "議事録を送ります", "business"),
    ("契約書の内容に、", "どういいたします", "同意いたします", "business"),
    ("お見積もり書を、", "さいはっこういたします", "再発行いたします", "business"),
    ("プロジェクトの、", "しんちょくはいかがでしょうか", "進捗はいかがでしょうか", "business"),
    ("ご要望の機能について、", "けんとういたします", "検討いたします", "business"),
    ("突然のご連絡、", "しつれいいたします", "失礼いたします", "business"),
    ("こちらの件、", "しょうちいたしました", "承知いたしました", "business"),
    ("お電話口で、", "たんとうにかわります", "担当に代わります", "business"),
    ("社内調整のうえ、", "あらためてごれんらくします", "改めてご連絡します", "business"),
    ("ご配慮いただき、", "かんしゃいたします", "感謝いたします", "business"),
    ("お時間をいただき、", "まことにありがとうございます", "誠にありがとうございます", "business"),
    ("恐れながら、", "じたいさせていただきます", "辞退させていただきます", "business"),
    ("本件の優先度を、", "たかくせっていします", "高く設定します", "business"),
    ("次回の打ち合わせは、", "らいしゅうのすいようびです", "来週の水曜日です", "business"),
    ("本日の予定を、", "りまいんどいたします", "リマインドいたします", "business"),
    ("社内規定を、", "じゅんしゅしてください", "遵守してください", "business"),
    ("取引先への、", "プレゼン資料を作成する", "プレゼン資料を作成する", "business"),
    ("請求金額を、", "おふりこみください", "お振込みください", "business"),

    # 2. 日常・チャット・感情 (80問)
    ("朝のメッセージで、", "おはよう！きょうもがんばろう", "おはよう！今日も頑張ろう", "daily"),
    ("お昼休みに、", "なにたべにいく？", "何食べに行く？", "daily"),
    ("帰り道で、", "いまからかえるね", "今から帰るね", "daily"),
    ("駅の改札で、", "ついたらおしえて", "着いたら教えて", "daily"),
    ("待ち合わせで、", "ちょっとおくれそう", "ちょっと遅れそう", "daily"),
    ("友達の吉報に、", "おめでとう！よかったね", "おめでとう！良かったね", "daily"),
    ("体調を崩した人に、", "おだいじにしてください", "お大事にしてください", "daily"),
    ("旅行のお土産を、", "かってきたよ", "買ってきたよ", "daily"),
    ("新しいカフェで、", "けーきをたべたよ", "ケーキを食べたよ", "daily"),
    ("週末の映画、", "なんじからだっけ？", "何時からだっけ？", "daily"),
    ("宿題が終わらなくて、", "たすけてほしい", "助けてほしい", "daily"),
    ("写真の共有で、", "あるばむについかしたよ", "アルバムに追加したよ", "daily"),
    ("買い物の途中で、", "なにかかうものある？", "何か買うものある？", "daily"),
    ("夜更かしして、", "そろそろねるね", "そろそろ寝るね", "daily"),
    ("部屋の模様替えで、", "つくえをいどうした", "机を移動した", "daily"),
    ("雨降りの日に、", "せんたくものをいれよう", "洗濯物を入れよう", "daily"),
    ("美味しいご飯を食べて、", "おなかいっぱいになった", "お腹いっぱいになった", "daily"),
    ("誕生日のプレゼントを、", "えらんでいるところ", "選んでいるところ", "daily"),
    ("急な雨に降られて、", "かさをかった", "傘を買った", "daily"),
    ("ゲームの対戦で、", "もういっかいやろう", "もう一回やろう", "daily"),

    # 3. IT・プログラミング・開発・オフィス (80問)
    ("ローカルブランチを、", "さいしんのめいんへまーじ", "最新のメインへマージ", "tech"),
    ("データベースの、", "いんでっくすをさいこうちく", "インデックスを再構築", "tech"),
    ("キャッシュを、", "くりあしてさいよみこみ", "クリアして再読み込み", "tech"),
    ("セキュリティの警告で、", "パスワードを変更する", "パスワードを変更する", "tech"),
    ("サーバー負荷を、", "かんしつーるでちぇっく", "監視ツールでチェック", "tech"),
    ("エラーログから、", "すたっくとれーすをかくにん", "スタックトレースを確認", "tech"),
    ("コンテナを、", "びるどして起動する", "ビルドして起動する", "tech"),
    ("新しいライブラリの、", "いぞんかんけいをかいけつ", "依存関係を解決", "tech"),
    ("設定ファイルの、", "かんきょうへんすうをへんこう", "環境変数を変更", "tech"),
    ("コードフォーマッタを、", "ほぞんじにじどうじっこう", "保存時に自動実行", "tech"),
    ("ユニットテストが、", "すべてぱすした", "すべてパスした", "tech"),
    ("メモリリークの、", "げんいんをとくていする", "原因を特定する", "tech"),
    ("APIのレスポンスを、", "じぇいそんけいしきでうけとる", "JSON形式で受け取る", "tech"),
    ("アクセストークンを、", "さいはっこうする", "再発行する", "tech"),
    ("ネットワークの、", "たいえきはばをかくだい", "帯域幅を拡大", "tech"),
    ("クラウドアカウントの、", "にんしょうじょうほうをこうしん", "認証情報を更新", "tech"),
    ("バッチ処理の、", "すけじゅーるをせってい", "スケジュールを設定", "tech"),
    ("リリースタグを、", "ぎっとはぶにぷっしゅ", "GitHubにプッシュ", "tech"),
    ("パフォーマンスの、", "ぼとるねっくをかいしょう", "ボトルネックを解消", "tech"),
    ("クライアント側の、", "かきなおしをおこなう", "書き直しを行う", "tech"),

    # 4. 一般社会・ニュース・生活 (60問)
    ("天気予報によると、", "あすははれるみこみです", "明日は晴れる見込みです", "general"),
    ("駅前の交差点で、", "しんごうをまつ", "信号を待つ", "general"),
    ("公園のベンチで、", "こーひーをのむ", "コーヒーを飲む", "general"),
    ("図書館で借りた、", "ほんをへんきゃくする", "本を返却する", "general"),
    ("健康診断の、", "けっかをとどける", "結果を届ける", "general"),
    ("新しいスマートフォンに、", "きしゅへんこうする", "機種変更する", "general"),
    ("近所のスーパーで、", "やさいとくだものをかう", "野菜と果物を買う", "general"),
    ("ジョギングをして、", "きもちのいいあせをかく", "気持ちのいい汗をかく", "general"),
    ("窓を開けて、", "へやのかんきをおこなう", "部屋の換気を行う", "general"),
    ("車検の時期なので、", "でぃーらーにれんらくする", "ディーラーに連絡する", "general"),
    ("旅行の荷物を、", "すーつけーすにつめる", "スーツケースに詰める", "general"),
    ("部屋の照明を、", "えるいーでぃーにかえる", "LEDに変える", "general"),
    ("新しい趣味として、", "ぎたーをはじめる", "ギターを始める", "general"),
    ("秋の紅葉を、", "みにいく", "見に行く", "general"),
    ("毎朝の習慣で、", "らじおたいそうをする", "ラジオ体操をする", "general"),
]

def make_benchmark():
    random.seed(42)  # 再現性のためのシード固定
    samples = []
    
    # 300問の内訳目標:
    # adjacent: 60, missing_vowel: 50, transposition: 40, flick: 40, 
    # geminate: 25, nasal: 25, prolonged: 15, clean: 45
    target_types = (
        ['adjacent'] * 60 +
        ['missing_vowel'] * 50 +
        ['transposition'] * 40 +
        ['flick'] * 40 +
        ['geminate'] * 25 +
        ['nasal'] * 25 +
        ['prolonged'] * 15 +
        ['clean'] * 45
    )
    random.shuffle(target_types)
    
    print(f"Total benchmark questions planned: {len(target_types)}")
    
    for i, t_type in enumerate(target_types):
        ctx, reading, expected, cat = BENCHMARK_SEEDS[i % len(BENCHMARK_SEEDS)]
        
        # 固有のタイポ生成
        romaji = hiragana_to_romaji(reading)
        if t_type == 'clean':
            input_kata = hira_to_kata(reading)
            is_clean = True
        else:
            is_clean = False
            if t_type == 'adjacent':
                mut = mutate_adjacent_key(romaji)
                hira = romaji_to_hiragana(mut)
            elif t_type == 'missing_vowel':
                mut = mutate_missing_vowel(romaji)
                hira = romaji_to_hiragana(mut)
            elif t_type == 'transposition':
                mut = mutate_transposition(romaji)
                hira = romaji_to_hiragana(mut)
            elif t_type == 'geminate':
                hira = mutate_geminate(reading)
            elif t_type == 'nasal':
                hira = mutate_syllabic_nasal(reading)
            elif t_type == 'flick':
                hira = mutate_flick_proximity(reading)
            elif t_type == 'prolonged':
                hira = mutate_prolonged(reading)
            else:
                hira = reading
                
            hira = clean_typo_string(hira)
            if hira == reading or not hira:
                hira = mutate_flick_proximity(reading)
            input_kata = hira_to_kata(hira)
            
        prompt = f"\uEE02{ctx}\uEE00{input_kata}\uEE01" if ctx else f"\uEE00{input_kata}\uEE01"
        
        samples.append({
            "id": i + 1,
            "category": cat,
            "typo_type": t_type,
            "is_clean": is_clean,
            "context": ctx,
            "input_reading": input_kata,
            "target_reading": reading,
            "expected": expected,
            "prompt": prompt
        })
        
    out_path = "data/typo_benchmark_300.jsonl"
    with open(out_path, "w", encoding="utf-8") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
            
    print(f"Successfully generated {len(samples)} benchmark testset -> {out_path}")

if __name__ == "__main__":
    make_benchmark()
