# -*- coding: utf-8 -*-
"""
単語・文節単位に特化した大規模タイポ補正学習データセット（30,000件）生成スクリプト
IMEの実運用（3〜8文字の未確定単語・文節入力）に完全に最適化
"""

import os
import json
import random
import re
from typing import List, Tuple, Dict
from dataset_isolation import evaluation_keys, separated_rows, validate_rows
from generate_typo_dataset import (
    apply_typo, hira_to_kata, mutate_adjacent_key, mutate_missing_vowel,
    mutate_transposition, mutate_geminate, mutate_syllabic_nasal,
    mutate_flick_proximity, mutate_prolonged, hiragana_to_romaji,
    romaji_to_hiragana, clean_typo_string, is_pure_japanese_kana
)

# IMEで実際によく入力される重要単語・文節シード（約300語の基本核）
# (文脈候補リスト, 正解読み, 正解表記, カテゴリ)
WORD_SEEDS = [
    # 1. 挨拶・定型・敬語
    (["お仕事", "本日の業務", "作業"], "おつかれさま", "お疲れ様", "greeting"),
    (["お仕事", "本日の業務", "今日"], "おつかれさまでした", "お疲れ様でした", "greeting"),
    (["いつも大変", "平素は格別の"], "おせわになっております", "お世話になっております", "greeting"),
    (["朝のチャットで、", "起きてすぐに、"], "おはよう", "おはよう", "greeting"),
    (["朝の挨拶で、", "出社時に、"], "おはようございます", "おはようございます", "greeting"),
    (["お昼休みに、", "チャットで、"], "こんにちは", "こんにちは", "greeting"),
    (["夜の挨拶で、", "退勤時に、"], "こんばんは", "こんばんは", "greeting"),
    (["寝る前に、", "夜遅くに、"], "おやすみなさい", "おやすみなさい", "greeting"),
    (["本当に", "心より", "深く"], "ありがとうございます", "ありがとうございます", "greeting"),
    (["手伝ってくれて", "いつも"], "ありがとう", "ありがとう", "greeting"),
    (["何卒", "今後とも", "引き続き"], "よろしくおねがいします", "よろしくお願いします", "greeting"),
    (["何卒", "ご検討のほど"], "よろしくおねがいいたします", "よろしくお願いいたします", "greeting"),
    (["友達に", "同僚に"], "よろしくね", "よろしくね", "greeting"),
    (["大変", "誠に", "ご迷惑をおかけし"], "もうしわけございません", "申し訳ございません", "greeting"),
    (["遅れて", "返信が遅くなり"], "ごめんなさい", "ごめんなさい", "greeting"),
    (["内容について", "ご要望の件、"], "しょうちいたしました", "承知いたしました", "greeting"),
    (["ご指示の件、", "連絡事項、"], "りょうかいいたしました", "了解いたしました", "greeting"),
    (["急ぎで", "社内向けに"], "りょうかい", "了解", "greeting"),
    (["添付の資料を", "メールの本文を"], "ごらんください", "ご覧ください", "greeting"),
    (["ご不明な点がございましたら", "質問があれば"], "おしらせください", "お知らせください", "greeting"),
    (["こちらの件、", "詳細について"], "ごきょうじください", "ご教示ください", "greeting"),
    (["本件の進め方を", "企画書を"], "ごけんとうください", "ご検討ください", "greeting"),
    (["お忙しいところ恐縮ですが、", "急ぎの用件で、"], "おでんわいたしました", "お電話いたしました", "greeting"),
    (["日程を", "面談の日時を"], "ちょうせいいたします", "調整いたします", "business"),
    (["契約書を", "請求書を"], "そうふいたします", "送付いたします", "business"),
    (["見積もりを", "金額を"], "さいけいさんする", "再計算する", "business"),
    (["議事録を", "報告書を"], "さくせいいたしました", "作成いたしました", "business"),
    (["プロジェクトの", "現在の"], "しんちょくはいかがでしょうか", "進捗はいかがでしょうか", "business"),
    (["都合が合わず、", "恐れながら、"], "じたいいたします", "辞退いたします", "business"),
    (["来週の月曜日に", "予定通り"], "お伺いいたします", "お伺いいたします", "business"),
    (["社内調整のうえ、", "追って"], "ごれんらくいたします", "ご連絡いたします", "business"),
    (["担当の者に", "窓口に"], "おつなぎいたします", "お繋ぎいたします", "business"),

    # 2. ビジネス名詞・単語
    (["明日の", "午後の", "社内の"], "かいぎ", "会議", "business"),
    (["プロジェクトの", "作業の"], "しんちょく", "進捗", "business"),
    (["取引先への", "月次の"], "せいきゅうしょ", "請求書", "business"),
    (["商品の", "サービスの"], "みつもり", "見積もり", "business"),
    (["新機能の", "システムの"], "しようしょ", "仕様書", "business"),
    (["本日の", "会議の"], "ぎじろく", "議事録", "business"),
    (["重要な", "取引の"], "けいやくしょ", "契約書", "business"),
    (["担当者の", "お客様の"], "れんらくさき", "連絡先", "business"),
    (["打ち合わせの", "会議の"], "にってい", "日程", "business"),
    (["メールの", "重要なお知らせの"], "けんめい", "件名", "business"),
    (["企画の", "新商品の"], "ていあん", "提案", "business"),
    (["来週の", "明日の"], "よてい", "予定", "business"),
    (["問題の", "エラーの"], "げんいん", "原因", "business"),
    (["障害の", "不具合の"], "たいさく", "対策", "business"),
    (["業務の", "作業の"], "こうりつか", "効率化", "business"),
    (["お客様への", "取引先への"], "ごあんない", "ご案内", "business"),
    (["社内への", "チーム内の"], "きょうゆう", "共有", "business"),

    # 3. IT・開発・プログラミング単語・文節
    (["AWSの", "Azureの", "GCPの"], "くらうど", "クラウド", "tech"),
    (["Webアプリの", "本番の"], "さーばー", "サーバー", "tech"),
    (["MySQLの", "リレーショナル"], "でーたべーす", "データベース", "tech"),
    (["ソースコードの", "Gitの"], "りぽじとり", "リポジトリ", "tech"),
    (["最新の", "機能追加の"], "ぷるりくえすと", "プルリクエスト", "tech"),
    (["Gitの", "作業用の"], "ぶらんち", "ブランチ", "tech"),
    (["変更内容を", "ステージングして"], "こみっとする", "コミットする", "tech"),
    (["修正コードを", "リモートへ"], "ぷっしゅする", "プッシュする", "tech"),
    (["本番サーバーに", "最新版を"], "でぷろいする", "デプロイする", "tech"),
    (["プログラムの", "致命的な"], "ばぐ", "バグ", "tech"),
    (["バグの原因を", "ツールで"], "でばっぐする", "デバッグする", "tech"),
    (["単体", "結合", "自動化の"], "てすと", "テスト", "tech"),
    (["プログラムの", "TypeScriptの"], "びるど", "ビルド", "tech"),
    (["認証情報の", "ユーザーの"], "ぱすわーど", "パスワード", "tech"),
    (["APIの", "アクセスのための"], "とーくん", "トークン", "tech"),
    (["システムの", "エラー発生時の"], "ろぐ", "ログ", "tech"),
    (["セキュリティの", "重大な"], "ぜいじゃくせい", "脆弱性", "tech"),
    (["システムの", "DBの"], "せってい", "設定", "tech"),
    (["PCの", "サーバーの"], "かんきょうへんすう", "環境変数", "tech"),
    (["新しいツールの", "ライブラリの"], "いんすとーる", "インストール", "tech"),
    (["古いバージョンの", "アプリの"], "あっぷでーと", "アップデート", "tech"),
    (["メモリの", "リソースの"], "かいほう", "解放", "tech"),
    (["関数の", "メソッドの"], "じっこう", "実行", "tech"),

    # 4. 日常生活・感情・行動
    (["駅の", "電車の", "バスの"], "きっぷ", "切符", "daily"),
    (["朝一番の", "今日の", "日刊の"], "しんぶん", "新聞", "daily"),
    (["スマートフォンで", "家族に"], "でんわ", "電話", "daily"),
    (["お昼の", "夕方の", "おいしい"], "ごはん", "ご飯", "daily"),
    (["雨が降ってきたので", "折りたたみの"], "かさ", "傘", "daily"),
    (["近くの", "駅前の"], "すーぱー", "スーパー", "daily"),
    (["お気に入りの", "新しい"], "かふぇ", "カフェ", "daily"),
    (["香りのいい", "挽きたての"], "こーひー", "コーヒー", "daily"),
    (["週末の", "最新の"], "えいが", "映画", "daily"),
    (["待ち合わせの", "最寄りの"], "えき", "駅", "daily"),
    (["急ぎで", "走って"], "いまむかってます", "今向かってます", "daily"),
    (["待ち合わせ場所に", "時間通りに"], "ついたよ", "着いたよ", "daily"),
    (["体調は", "怪我は"], "だいじょうぶ？", "大丈夫？", "daily"),
    (["ゆっくり", "今日は無理せず"], "やすんでね", "休んでね", "daily"),
    (["夜道だから", "車に"], "きをつけてね", "気をつけてね", "daily"),
    (["週末を", "イベントを"], "たのしみにしてる", "楽しみにしてる", "daily"),
    (["お腹が", "すごく"], "すいた", "空いた", "daily"),
    (["そろそろ眠いので", "疲れたから"], "ねるね", "寝るね", "daily"),
    (["写真の", "動画の"], "あるばむ", "アルバム", "daily"),
    (["旅行の", "ホテルの"], "よやく", "予約", "daily"),
    (["部屋の", "机の周りの"], "そうじ", "掃除", "daily"),
    (["近所の公園を", "朝の"], "さんぽ", "散歩", "daily"),
    (["図書館で借りた", "話題の"], "ほん", "本", "daily"),
]

def generate_word_dataset(output_dir="data", total_samples=30000):
    os.makedirs(output_dir, exist_ok=True)
    random.seed(42)

    train_samples = []
    val_samples = []

    for i in range(total_samples):
        ctx_list, reading, expected, cat = random.choice(WORD_SEEDS)

        # 30% は文脈なし、70% は文脈あり
        if random.random() < 0.30 or not ctx_list:
            left_ctx = ""
        else:
            left_ctx = random.choice(ctx_list)

        # 20% は正例（タイポなし）
        is_clean = (random.random() < 0.20)

        if is_clean:
            input_kata = hira_to_kata(reading)
            typo_type = "clean"
        else:
            input_kata, typo_type = apply_typo(reading)

        # zenz 制御プロンプト: \uEE02{left_context}\uEE00{input_reading}\uEE01
        prompt = f"\uEE02{left_ctx}\uEE00{input_kata}\uEE01" if left_ctx else f"\uEE00{input_kata}\uEE01"
        completion = expected

        sample = {
            "id": i + 1,
            "prompt": prompt,
            "completion": completion,
            "left_context": left_ctx,
            "input_reading": input_kata,
            "target_reading": reading,
            "expected": expected,
            "typo_type": typo_type,
            "is_clean": is_clean,
            "category": cat
        }

        train_samples.append(sample)

    reserved = evaluation_keys()
    train_samples, val_samples, excluded = separated_rows(train_samples, reserved)
    validate_rows(train_samples, val_samples, reserved)
    print(f"Excluded evaluation-related rows: {len(excluded)}")

    train_path = os.path.join(output_dir, 'typo_dataset_train_30k.jsonl')
    val_path = os.path.join(output_dir, 'typo_dataset_val_30k.jsonl')

    with open(train_path, "w", encoding="utf-8") as f:
        for s in train_samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")

    with open(val_path, "w", encoding="utf-8") as f:
        for s in val_samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")

    print(f"Generated {len(train_samples)} train samples -> {train_path}")
    print(f"Generated {len(val_samples)} val samples -> {val_path}")

if __name__ == "__main__":
    generate_word_dataset()
