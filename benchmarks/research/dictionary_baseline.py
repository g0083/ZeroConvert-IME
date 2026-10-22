# -*- coding: utf-8 -*-
"""
辞書・ルールベースの誤入力補正および先読みエンジンのシミュレータ
Mozc KeyCorrectorの規則、QWERTY隣接キーモデル、編集距離、定型句辞書に基づく実装
"""

import unicodedata
import re

# QWERTY隣接キーマップ（キー -> 打ち間違えやすい隣接キーリスト）
KEYBOARD_ADJACENT = {
    'q': ['w', 'a', 's'],
    'w': ['q', 'e', 's', 'a', 'd'],
    'e': ['w', 'r', 'd', 's', 'f'],
    'r': ['e', 't', 'f', 'd', 'g'],
    't': ['r', 'y', 'g', 'f', 'h'],
    'y': ['t', 'u', 'h', 'g', 'j'],
    'u': ['y', 'i', 'j', 'h', 'k'],
    'i': ['u', 'o', 'k', 'j', 'l'],
    'o': ['i', 'p', 'l', 'k'],
    'p': ['o', 'l'],
    'a': ['q', 'w', 's', 'z'],
    's': ['a', 'w', 'e', 'd', 'x', 'z'],
    'd': ['s', 'e', 'r', 'f', 'c', 'x'],
    'f': ['d', 'r', 't', 'g', 'v', 'c'],
    'g': ['f', 't', 'y', 'h', 'b', 'v'],
    'h': ['g', 'y', 'u', 'j', 'n', 'b'],
    'j': ['h', 'u', 'i', 'k', 'm', 'n'],
    'k': ['j', 'i', 'o', 'l', 'm'],
    'l': ['k', 'o', 'p'],
    'z': ['a', 's', 'x'],
    'x': ['z', 's', 'd', 'c'],
    'c': ['x', 'd', 'f', 'v'],
    'v': ['c', 'f', 'g', 'b'],
    'b': ['v', 'g', 'h', 'n'],
    'n': ['b', 'h', 'j', 'm'],
    'm': ['n', 'j', 'k']
}

# 一般的なIME定型句・単語補正辞書（JWTD v2 / IME頻出語から抽出した語彙ベース）
STANDARD_VOCAB = {
    "こんにちは": "こんにちは",
    "ありがとうございます": "ありがとうございます",
    "よろしくおねがい": "よろしくお願い",
    "くらうど": "クラウド",
    "あしたの": "明日の",
    "おはよう": "おはよう",
    "おきゃくさん": "お客さん",
    "おつかれさま": "お疲れ様",
    "しんちょく": "進捗",
    "きっぷ": "切符",
    "けっか": "結果",
    "いってらっしゃい": "いってらっしゃい",
    "がっこう": "学校",
    "しっぱい": "失敗",
    "はっけん": "発見",
    "しんぶん": "新聞",
    "かんたん": "簡単",
    "てんけん": "点検",
    "でんわ": "電話",
    "あんぜん": "安全",
    "てすと": "テスト",
    "ひんと": "ヒント",
    "すぐ": "すぐ",
    "かいいん": "会員",
    "こうせい": "校正",
    "さーびす": "サービス",
    "ゆーざー": "ユーザー",
    "こぴー": "コピー",
    "りーだー": "リーダー",
    "てーま": "テーマ",
    "かさをさす": "傘を差す",
    "めにゅーをみせて": "メニューを見せて",
    "かくにんをすぐに": "確認をすぐに",
    "じかんは": "時間は",
    "せってい": "設定",
    "せってぃんぐ": "セッティング"
}

# 先読み用定型句・フレーズ辞書（古典的IMEのプレフィックス一致辞書）
PREDICTIVE_PHRASES = [
    ("いつもお世話に", "なっております。"),
    ("よろしくお願い", "いたします。"),
    ("お疲れ様", "でした。"),
    ("ご確認のほど", "よろしくお願いいたします。"),
    ("ご連絡いただき", "ありがとうございます。"),
    ("現在原因を調査", "中です。"),
    ("機能の実装が完了", "いたしました。"),
    ("進捗状況をご報告", "いたします。"),
    ("メンテナンス作業は無事終了", "しました。"),
    ("最新バージョンを公開", "しました。"),
    ("ご都合のよろしい", "日時をご教示ください。"),
    ("日程の調整を", "お願いできますでしょうか。"),
    ("お忙しいところ恐縮ですが、", "ご確認をお願いいたします。"),
    ("添付の資料を", "ご覧ください。"),
    ("出欠のご連絡を", "お願いいたします。"),
    ("プルリクエストを", "作成しました。"),
    ("デプロイが正常に", "完了しました。"),
    ("エラーログを", "確認してください。"),
    ("タイポの修正と", "リファクタリングを行いました。"),
    ("承知いた", "しました。"),
    ("今からそちらへ", "向かいます。"),
    ("ぜひ参加させて", "いただきます。"),
]

def damerau_levenshtein_distance(s1: str, s2: str) -> int:
    d = {}
    len1 = len(s1)
    len2 = len(s2)
    for i in range(-1, len1 + 1):
        d[(i, -1)] = i + 1
    for j in range(-1, len2 + 1):
        d[(-1, j)] = j + 1

    for i in range(len1):
        for j in range(len2):
            if s1[i] == s2[j]:
                cost = 0
            else:
                cost = 1
            d[(i, j)] = min(
                d[(i - 1, j)] + 1,       # deletion
                d[(i, j - 1)] + 1,       # insertion
                d[(i - 1, j - 1)] + cost # substitution
            )
            if i > 0 and j > 0 and s1[i] == s2[j - 1] and s1[i - 1] == s2[j]:
                d[(i, j)] = min(d[(i, j)], d[(i - 2, j - 2)] + 1) # transposition
    return d[(len1 - 1, len2 - 1)]

def rule_based_typo_correct(raw_hira: str, raw_romaji: str = "") -> str:
    """
    辞書・ルールベースの誤入力補正
    1. 完全一致
    2. 促音・撥音・長音正規化ルール (KeyCorrector的ルール)
    3. 編集距離<=2の登録語検索
    """
    # 1. 完全一致
    if raw_hira in STANDARD_VOCAB:
        return STANDARD_VOCAB[raw_hira]

    # 2. Mozc KeyCorrector / ルールベース展開
    # 促音補正: 2連続子音や脱落の補正（例: けか -> けっか）
    candidates = []
    
    # ルールA: 促音の挿入（1文字目と2文字目の間に 'っ'）
    if len(raw_hira) == 2:
        cand = raw_hira[0] + "っ" + raw_hira[1]
        if cand in STANDARD_VOCAB:
            return STANDARD_VOCAB[cand]

    # ルールB: 撥音の挿入（'ん' の挿入）
    if len(raw_hira) >= 2:
        for idx in range(1, len(raw_hira)):
            cand = raw_hira[:idx] + "ん" + raw_hira[idx:]
            if cand in STANDARD_VOCAB:
                return STANDARD_VOCAB[cand]

    # ルールC: 長音の付与（末尾に 'ー'）
    cand = raw_hira + "ー"
    if cand in STANDARD_VOCAB:
        return STANDARD_VOCAB[cand]

    # ルールD: 助詞 "わ" -> "は"
    if raw_hira.endswith("わ"):
        cand = raw_hira[:-1] + "は"
        if cand in STANDARD_VOCAB:
            return STANDARD_VOCAB[cand]

    # 3. 編集距離ベースの辞書引き（Damerau-Levenshtein <= 2）
    best_word = None
    min_dist = 999
    for vocab_reading, vocab_surface in STANDARD_VOCAB.items():
        dist = damerau_levenshtein_distance(raw_hira, vocab_reading)
        if dist <= 2 and dist < min_dist:
            min_dist = dist
            best_word = vocab_surface

    if best_word:
        return best_word

    # 補正できなければそのまま返す
    return raw_hira

def dictionary_predictive_completion(partial_input: str) -> str:
    """
    辞書ベースの先読み（Prefix一致）
    """
    for prefix, continuation in PREDICTIVE_PHRASES:
        if partial_input == prefix or prefix.startswith(partial_input):
            return continuation
        if partial_input.startswith(prefix):
            # プレフィックスの余剰分を除いた補完
            remain = partial_input[len(prefix):]
            if continuation.startswith(remain):
                return continuation[len(remain):]
    return ""
