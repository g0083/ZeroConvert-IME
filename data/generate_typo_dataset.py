# -*- coding: utf-8 -*-
"""
高品質タイポ（誤入力）補正学習データセット生成スクリプト（改良版）
zenz v3 / v3.2 アーキテクチャ向け

タイポの種類:
1. QWERTY隣接キー打鍵ミス (Adjacent Key Error)
2. 母音脱落 (Missing Vowel)
3. 促音・撥音・二重打ちミス (Geminate / Nasal / Double Consonant)
4. 指の順序入れ替わり (Transposition)
5. フリック入力の方向・マス誤り (Flick Proximity)
6. 長音・濁点・拗音の誤り (Phonetic / Prolonged Sound)
7. 正例（タイポなし正常入力: 過剰補正抑制用 20%）
"""

import os
import json
import random
import re
from typing import List, Tuple, Dict
from dataset_isolation import evaluation_keys, separated_rows, validate_rows

# QWERTY隣接キーマップ (英字のみ)
QWERTY_ADJACENT = {
    'q': ['w', 'a', 's'],
    'w': ['q', 'e', 'a', 's', 'd'],
    'e': ['w', 'r', 's', 'd', 'f'],
    'r': ['e', 't', 'd', 'f', 'g'],
    't': ['r', 'y', 'f', 'g', 'h'],
    'y': ['t', 'u', 'g', 'h', 'j'],
    'u': ['y', 'i', 'h', 'j', 'k'],
    'i': ['u', 'o', 'j', 'k', 'l'],
    'o': ['i', 'p', 'k', 'l'],
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

# 12キー（フリック）隣接マップ
FLICK_ADJACENT = {
    'あ': ['い', 'う', 'え', 'お', 'か'],
    'い': ['あ', 'う', 'え', 'き'],
    'う': ['あ', 'い', 'え', 'お', 'く'],
    'え': ['あ', 'い', 'う', 'お', 'け'],
    'お': ['あ', 'う', 'え', 'こ'],
    'か': ['き', 'く', 'け', 'こ', 'あ', 'さ'],
    'き': ['か', 'く', 'け', 'い', 'し'],
    'く': ['か', 'き', 'け', 'こ', 'う', 'す'],
    'け': ['か', 'き', 'く', 'こ', 'え', 'せ'],
    'こ': ['か', 'く', 'け', 'お', 'そ'],
    'さ': ['し', 'す', 'せ', 'そ', 'か', 'た'],
    'し': ['さ', 'す', 'せ', 'き', 'ち'],
    'す': ['さ', 'し', 'せ', 'そ', 'く', 'つ'],
    'せ': ['さ', 'し', 'す', 'そ', 'け', 'て'],
    'そ': ['さ', 'す', 'せ', 'こ', 'と'],
    'た': ['ち', 'つ', 'て', 'と', 'さ', 'な'],
    'ち': ['た', 'つ', 'て', 'し', 'に'],
    'つ': ['た', 'ち', 'て', 'と', 'す', 'ぬ'],
    'て': ['た', 'ち', 'つ', 'と', 'せ', 'ね'],
    'と': ['た', 'つ', 'て', 'そ', 'の'],
    'な': ['に', 'ぬ', 'ね', 'の', 'た', 'は'],
    'に': ['な', 'ぬ', 'ね', 'ち', 'ひ'],
    'ぬ': ['な', 'に', 'ね', 'の', 'つ', 'ふ'],
    'ね': ['な', 'に', 'ぬ', 'の', 'て', 'へ'],
    'の': ['な', 'ぬ', 'ね', 'と', 'ほ'],
    'は': ['ひ', 'ふ', 'へ', 'ほ', 'な', 'ま', 'ば', 'ぱ'],
    'ひ': ['は', 'ふ', 'へ', 'に', 'み', 'び', 'ぴ'],
    'ふ': ['は', 'ひ', 'へ', 'ほ', 'ぬ', 'む', 'ぶ', 'ぷ'],
    'へ': ['は', 'ひ', 'ふ', 'ほ', 'ね', 'め', 'べ', 'ぺ'],
    'ほ': ['は', 'ふ', 'へ', 'の', 'も', 'ぼ', 'ぽ'],
    'ま': ['み', 'む', 'め', 'も', 'は', 'や'],
    'み': ['ま', 'む', 'め', 'ひ', 'り'],
    'む': ['ま', 'み', 'め', 'も', 'ふ', 'ゆ'],
    'め': ['ま', 'み', 'む', 'も', 'へ', 'れ'],
    'も': ['ま', 'む', 'め', 'ほ', 'よ'],
    'や': ['ゆ', 'よ', 'ま', 'ら'],
    'ゆ': ['や', 'よ', 'む', 'る'],
    'よ': ['や', 'ゆ', 'も', 'ろ'],
    'ら': ['り', 'る', 'れ', 'ろ', 'や', 'わ'],
    'り': ['ら', 'る', 'れ', 'み'],
    'る': ['ら', 'り', 'れ', 'ろ', 'ゆ'],
    'れ': ['ら', 'り', 'る', 'ろ', 'め'],
    'ろ': ['ら', 'る', 'れ', 'よ'],
    'わ': ['を', 'ん', 'ら', 'は'],
    'を': ['わ', 'ん', 'お'],
    'ん': ['わ', 'を']
}

KANA_TO_ROMAJI = {
    'あ': 'a', 'い': 'i', 'う': 'u', 'え': 'e', 'お': 'o',
    'か': 'ka', 'き': 'ki', 'く': 'ku', 'け': 'ke', 'こ': 'ko',
    'さ': 'sa', 'し': 'si', 'す': 'su', 'せ': 'se', 'そ': 'so',
    'た': 'ta', 'ち': 'ti', 'つ': 'tu', 'て': 'te', 'と': 'to',
    'な': 'na', 'に': 'ni', 'ぬ': 'nu', 'ね': 'ne', 'の': 'no',
    'は': 'ha', 'ひ': 'hi', 'ふ': 'hu', 'へ': 'he', 'ほ': 'ho',
    'ま': 'ma', 'み': 'mi', 'む': 'mu', 'め': 'me', 'も': 'mo',
    'や': 'ya', 'ゆ': 'yu', 'よ': 'yo',
    'ら': 'ra', 'り': 'ri', 'る': 'ru', 'れ': 're', 'ろ': 'ro',
    'わ': 'wa', 'を': 'wo', 'ん': 'nn',
    'が': 'ga', 'ぎ': 'gi', 'ぐ': 'gu', 'げ': 'ge', 'ご': 'go',
    'ざ': 'za', 'じ': 'zi', 'ず': 'zu', 'ぜ': 'ze', 'ぞ': 'zo',
    'だ': 'da', 'ぢ': 'di', 'づ': 'du', 'で': 'de', 'ど': 'do',
    'ば': 'ba', 'び': 'bi', 'ぶ': 'bu', 'べ': 'be', 'ぼ': 'bo',
    'ぱ': 'pa', 'ぴ': 'pi', 'ぷ': 'pu', 'ぺ': 'pe', 'ぽ': 'po',
    'きゃ': 'kya', 'きゅ': 'kyu', 'きょ': 'kyo',
    'しゃ': 'sya', 'しゅ': 'syu', 'しょ': 'syo',
    'ちゃ': 'tya', 'ちゅ': 'tyu', 'ちょ': 'tyo',
    'にゃ': 'nya', 'にゅ': 'nyu', 'にょ': 'nyo',
    'ひゃ': 'hya', 'ひゅ': 'hyu', 'ひょ': 'hyo',
    'みゃ': 'mya', 'みゅ': 'myu', 'みょ': 'myo',
    'りゃ': 'rya', 'りゅ': 'ryu', 'りょ': 'ryo',
    'ぎゃ': 'gya', 'ぎゅ': 'gyu', 'ぎょ': 'gyo',
    'じゃ': 'ja', 'じゅ': 'ju', 'じょ': 'jo',
    'びゃ': 'bya', 'びゅ': 'byu', 'びょ': 'byo',
    'ぴゃ': 'pya', 'ぴゅ': 'pyu', 'ぴょ': 'pyo',
    'っ': 'xtsu', 'ー': '-'
}

ROMAJI_TO_KANA_RULES = sorted([
    ('kya', 'きゃ'), ('kyu', 'きゅ'), ('kyo', 'きょ'),
    ('sya', 'しゃ'), ('syu', 'しゅ'), ('syo', 'しょ'),
    ('sha', 'しゃ'), ('shu', 'しゅ'), ('sho', 'しょ'),
    ('tya', 'ちゃ'), ('tyu', 'ちゅ'), ('tyo', 'ちょ'),
    ('cha', 'ちゃ'), ('chu', 'ちゅ'), ('cho', 'ちょ'),
    ('nya', 'にゃ'), ('nyu', 'にゅ'), ('nyo', 'にょ'),
    ('hya', 'ひゃ'), ('hyu', 'ひゅ'), ('hyo', 'ひょ'),
    ('mya', 'みゃ'), ('myu', 'みゅ'), ('myo', 'みょ'),
    ('rya', 'りゃ'), ('ryu', 'りゅ'), ('ryo', 'りょ'),
    ('gya', 'ぎゃ'), ('gyu', 'ぎゅ'), ('gyo', 'ぎょ'),
    ('ja', 'じゃ'), ('ju', 'じゅ'), ('jo', 'じょ'),
    ('zya', 'じゃ'), ('zyu', 'じゅ'), ('zyo', 'じょ'),
    ('bya', 'びゃ'), ('byu', 'びゅ'), ('byo', 'びょ'),
    ('pya', 'ぴゃ'), ('pyu', 'ぴゅ'), ('pyo', 'ぴょ'),
    ('ka', 'か'), ('ki', 'き'), ('ku', 'く'), ('ke', 'け'), ('ko', 'こ'),
    ('sa', 'さ'), ('si', 'し'), ('su', 'す'), ('se', 'せ'), ('so', 'そ'),
    ('shi', 'し'), ('tsu', 'つ'), ('chi', 'ち'),
    ('ta', 'た'), ('ti', 'ち'), ('tu', 'つ'), ('te', 'て'), ('to', 'と'),
    ('na', 'な'), ('ni', 'に'), ('nu', 'ぬ'), ('ne', 'ね'), ('no', 'の'),
    ('ha', 'は'), ('hi', 'ひ'), ('hu', 'ふ'), ('he', 'へ'), ('ho', 'ほ'),
    ('fu', 'ふ'),
    ('ma', 'ま'), ('mi', 'み'), ('mu', 'む'), ('me', 'め'), ('mo', 'も'),
    ('ya', 'や'), ('yu', 'ゆ'), ('yo', 'よ'),
    ('ra', 'ら'), ('ri', 'り'), ('ru', 'る'), ('re', 'れ'), ('ro', 'ろ'),
    ('wa', 'わ'), ('wo', 'を'), ('nn', 'ん'),
    ('ga', 'が'), ('gi', 'ぎ'), ('gu', 'ぐ'), ('ge', 'げ'), ('go', 'ご'),
    ('za', 'ざ'), ('zi', 'じ'), ('zu', 'ず'), ('ze', 'ぜ'), ('zo', 'ぞ'),
    ('ji', 'じ'),
    ('da', 'だ'), ('di', 'ぢ'), ('du', 'づ'), ('de', 'で'), ('do', 'ど'),
    ('ba', 'ば'), ('bi', 'び'), ('bu', 'ぶ'), ('be', 'べ'), ('bo', 'ぼ'),
    ('pa', 'ぱ'), ('pi', 'ぴ'), ('pu', 'ぷ'), ('pe', 'ぺ'), ('po', 'ぽ'),
    ('a', 'あ'), ('i', 'い'), ('u', 'う'), ('e', 'え'), ('o', 'お'),
    ('-', 'ー')
], key=lambda x: len(x[0]), reverse=True)

def romaji_to_hiragana(text: str) -> str:
    res = []
    i = 0
    n = len(text)
    while i < n:
        if i + 1 < n and text[i] == text[i+1] and text[i] in "bcdfghjklmnpqrstvwxyz" and text[i] != 'n':
            res.append('っ')
            i += 1
            continue
        matched = False
        for rom, kana in ROMAJI_TO_KANA_RULES:
            if text.startswith(rom, i):
                res.append(kana)
                i += len(rom)
                matched = True
                break
        if not matched:
            res.append(text[i])
            i += 1
    return "".join(res)

def hiragana_to_romaji(text: str) -> str:
    res = []
    i = 0
    n = len(text)
    while i < n:
        if i + 1 < n and text[i:i+2] in KANA_TO_ROMAJI:
            res.append(KANA_TO_ROMAJI[text[i:i+2]])
            i += 2
        elif text[i] == 'っ' and i + 1 < n:
            next_char = text[i+1:i+3] if i + 2 <= n and text[i+1:i+3] in KANA_TO_ROMAJI else text[i+1]
            next_rom = KANA_TO_ROMAJI.get(next_char, '')
            if next_rom:
                res.append(next_rom[0])
            i += 1
        elif text[i] in KANA_TO_ROMAJI:
            res.append(KANA_TO_ROMAJI[text[i]])
            i += 1
        else:
            res.append(text[i])
            i += 1
    return "".join(res)

def hira_to_kata(text: str) -> str:
    res = []
    for c in text:
        code = ord(c)
        if 0x3041 <= code <= 0x3096:
            res.append(chr(code + 0x60))
        else:
            res.append(c)
    return "".join(res)

def is_pure_japanese_kana(text: str) -> bool:
    """純粋なひらがな・長音のみで構成されているかチェック"""
    return bool(re.match(r'^[ぁ-んー]+$', text))

def mutate_adjacent_key(romaji: str) -> str:
    chars = list(romaji)
    indices = [i for i, c in enumerate(chars) if c in QWERTY_ADJACENT]
    if not indices:
        return romaji
    idx = random.choice(indices)
    chars[idx] = random.choice(QWERTY_ADJACENT[chars[idx]])
    return "".join(chars)

def mutate_missing_vowel(romaji: str) -> str:
    vowels = [i for i, c in enumerate(romaji) if c in "aiueo" and i > 0]
    if not vowels:
        return romaji
    idx = random.choice(vowels)
    return romaji[:idx] + romaji[idx+1:]

def mutate_transposition(romaji: str) -> str:
    if len(romaji) < 3:
        return romaji
    idx = random.randint(0, len(romaji) - 2)
    chars = list(romaji)
    chars[idx], chars[idx+1] = chars[idx+1], chars[idx]
    return "".join(chars)

def mutate_geminate(hiragana: str) -> str:
    if 'っ' in hiragana:
        return hiragana.replace('っ', '', 1)
    candidates = [i for i, c in enumerate(hiragana) if c not in "あいうえおんーっ" and i > 0]
    if candidates:
        idx = random.choice(candidates)
        return hiragana[:idx] + 'っ' + hiragana[idx:]
    return hiragana

def mutate_syllabic_nasal(hiragana: str) -> str:
    if 'ん' in hiragana:
        return hiragana.replace('ん', '', 1)
    if len(hiragana) > 2:
        idx = random.randint(1, len(hiragana) - 1)
        return hiragana[:idx] + 'ん' + hiragana[idx:]
    return hiragana

def mutate_flick_proximity(hiragana: str) -> str:
    chars = list(hiragana)
    candidates = [i for i, c in enumerate(chars) if c in FLICK_ADJACENT]
    if not candidates:
        return hiragana
    idx = random.choice(candidates)
    chars[idx] = random.choice(FLICK_ADJACENT[chars[idx]])
    return "".join(chars)

def mutate_prolonged(hiragana: str) -> str:
    if 'ー' in hiragana:
        return hiragana.replace('ー', '', 1)
    return hiragana

def clean_typo_string(text: str) -> str:
    """タイポ変異文字列から英数字などの残渣をクリーンアップ"""
    # 残ってしまった英字を母音に補完または除去
    clean_chars = []
    for c in text:
        if c.isascii() and c.isalpha():
            # 英語キーが残った場合はローマ字変換フォールバック
            kana = romaji_to_hiragana(c + 'a')
            if kana:
                clean_chars.append(kana[0])
        elif not c.isascii() or c in "ーっ":
            clean_chars.append(c)
    return "".join(clean_chars)

def apply_typo(hiragana: str) -> Tuple[str, str]:
    romaji = hiragana_to_romaji(hiragana)
    typo_type = random.choices(
        ['adjacent', 'missing_vowel', 'transposition', 'geminate', 'nasal', 'flick', 'prolonged'],
        weights=[30, 20, 15, 10, 10, 10, 5]
    )[0]

    typo_hira = hiragana
    if typo_type == 'adjacent':
        mut_rom = mutate_adjacent_key(romaji)
        typo_hira = romaji_to_hiragana(mut_rom)
    elif typo_type == 'missing_vowel':
        mut_rom = mutate_missing_vowel(romaji)
        typo_hira = romaji_to_hiragana(mut_rom)
    elif typo_type == 'transposition':
        mut_rom = mutate_transposition(romaji)
        typo_hira = romaji_to_hiragana(mut_rom)
    elif typo_type == 'geminate':
        typo_hira = mutate_geminate(hiragana)
    elif typo_type == 'nasal':
        typo_hira = mutate_syllabic_nasal(hiragana)
    elif typo_type == 'flick':
        typo_hira = mutate_flick_proximity(hiragana)
    elif typo_type == 'prolonged':
        typo_hira = mutate_prolonged(hiragana)

    typo_hira = clean_typo_string(typo_hira)
    if typo_hira == hiragana or not typo_hira:
        # 変異しなかった場合はフリック隣接で強制変異
        typo_hira = mutate_flick_proximity(hiragana)

    return hira_to_kata(typo_hira), typo_type

# 基礎シードコーパス（自然な文脈、純粋なひらがな読み、期待される変換表記、カテゴリ）
SEED_CORPUS = [
    # ビジネス・メール・挨拶
    ("お仕事", "おつかれさまです", "お疲れ様です", "business"),
    ("本日の業務、", "おつかれさまでした", "お疲れ様でした", "business"),
    ("いつも大変", "おせわになっております", "お世話になっております", "business"),
    ("内容を", "かくにんいたしました", "確認いたしました", "business"),
    ("日程を", "ちょうせいいたします", "調整いたします", "business"),
    ("何卒", "よろしくおねがいいたします", "よろしくお願いいたします", "business"),
    ("ご要望の件、", "しょうちいたしました", "承知いたしました", "business"),
    ("ご連絡が遅くなり", "もうしわけございません", "申し訳ございません", "business"),
    ("添付の資料を", "ごらんください", "ご覧ください", "business"),
    ("ご不明な点がございましたら", "おしらせください", "お知らせください", "business"),
    ("迅速なご対応", "ありがとうございます", "ありがとうございます", "business"),
    ("急ぎの要件のため", "おでんわいたしました", "お電話いたしました", "business"),
    ("ご確認のほど", "よろしくおねがいします", "よろしくお願いします", "business"),
    ("詳細について", "ごきょうじください", "ご教示ください", "business"),
    ("本件について", "ごけんとうください", "ご検討ください", "business"),
    ("進捗状況を", "ほうこくいたします", "報告いたします", "business"),
    ("来週の月曜日に", "お伺いいたします", "お伺いいたします", "business"),
    ("資料の送付を", "おねがいできますでしょうか", "お願いできますでしょうか", "business"),
    ("請求書の発行を", "おねがいします", "お願いします", "business"),
    ("打ち合わせのリンクを", "お送りいたします", "お送りいたします", "business"),
    ("本日のアジェンダを", "きょうゆういたします", "共有いたします", "business"),
    ("議事録を", "さくせいいたしました", "作成いたしました", "business"),
    ("契約書の送付を", "てはいいたします", "手配いたします", "business"),
    ("見積もりを", "さいけいさんいたします", "再計算いたします", "business"),
    ("引き続き", "よろしくおねがいします", "よろしくお願いします", "business"),

    # 日常会話・チャット・SNS
    ("朝起きて、", "おはようございます", "おはようございます", "daily"),
    ("お昼休みに、", "こんにちは", "こんにちは", "daily"),
    ("夜の挨拶で、", "こんばんは", "こんばんは", "daily"),
    ("寝る前に、", "おやすみなさい", "おやすみなさい", "daily"),
    ("友達との約束で、", "いまむかってます", "今向かってます", "daily"),
    ("待ち合わせ場所の", "えきについたよ", "駅に着いたよ", "daily"),
    ("お昼ご飯は", "なにたべる？", "何食べる？", "daily"),
    ("今日の天気は", "とてもいいね", "とてもいいね", "daily"),
    ("雨が降ってきたから", "かさをもっていこう", "傘を持っていこう", "daily"),
    ("久しぶりに会えて", "うれしかったよ", "嬉しかったよ", "daily"),
    ("週末の予定、", "たのしみにしてるね", "楽しみにしてるね", "daily"),
    ("体調が悪そうだけど", "だいじょうぶ？", "大丈夫？", "daily"),
    ("無理しないで", "ゆっくりやすんでね", "ゆっくり休んでね", "daily"),
    ("帰り道、", "きをつけてね", "気をつけてね", "daily"),
    ("手伝ってくれて", "ほんとうにありがとう", "本当にありがとう", "daily"),
    ("明日の朝は", "はやくおきないと", "早く起きないと", "daily"),
    ("映画のチケットを", "よやくしたよ", "予約したよ", "daily"),
    ("カフェで少し", "おちゃしよう", "お茶しよう", "daily"),
    ("写真のデータを", "おくったよ", "送ったよ", "daily"),
    ("返信が遅れて", "ごめんね", "ごめんね", "daily"),
    ("お腹が空いたので", "ごはんをたべよう", "ご飯を食べよう", "daily"),
    ("美味しいお店を", "みつけたよ", "見つけたよ", "daily"),
    ("今度の休みに", "あそびにいこう", "遊びに行こう", "daily"),

    # IT・開発・PC操作
    ("最新の変更を", "さくせいする", "作成する", "tech"),
    ("プログラムの", "ばぐをしゅうせいする", "バグを修正する", "tech"),
    ("テストコードを", "じっこうする", "実行する", "tech"),
    ("データベースの接続を", "かくにんする", "確認する", "tech"),
    ("サーバーを再起動して", "ろぐをかくにんする", "ログを確認する", "tech"),
    ("セキュリティの", "ぜいじゃくせいをなおす", "脆弱性を直す", "tech"),
    ("環境変数を", "せっていする", "設定する", "tech"),
    ("仕様書を", "こうしんする", "更新する", "tech"),
    ("フロントエンドの", "びるどがせいこうした", "ビルドが成功した", "tech"),
    ("コードレビューを", "いらいする", "依頼する", "tech"),
    ("新しいツールを", "いんすとーるする", "インストールする", "tech"),
    ("コンパイルエラーを", "かいしょうする", "解消する", "tech"),
    ("クラウドの利用料を", "さくげんする", "削減する", "tech"),
    ("ログイン画面の", "きのうをじっそうする", "機能を実装する", "tech"),
    ("通信速度を", "そくていする", "測定する", "tech"),
    ("バックアップを", "ていきしゃくせいする", "定期作成する", "tech"),
    ("パスワードを", "さいせっていする", "再設定する", "tech"),

    # 一般動作・生活・表現
    ("青空の下で", "さんぽをする", "散歩をする", "general"),
    ("図書館で静かに", "ほんをよむ", "本を読む", "general"),
    ("イヤホンをつけて", "おんがくをきく", "音楽を聴く", "general"),
    ("景色のいい場所で", "しゃしんをとる", "写真を撮る", "general"),
    ("スーパーで今日の", "かいものをする", "買い物をする", "general"),
    ("温かいお風呂に", "ゆっくりつかる", "ゆっくり浸かる", "general"),
    ("新しい目標に向かって", "ちょうせんする", "挑戦する", "general"),
    ("秋の夜長に", "すいみんをとる", "睡眠をとる", "general"),
    ("旅行の計画を", "たてる", "立てる", "general"),
    ("電車に乗って", "つうきんする", "通勤する", "general"),
    ("部屋の掃除を", "すませる", "済ませる", "general"),
    ("健康のために", "うんどうをする", "運動をする", "general"),
    ("新しい服を", "かいにいく", "買いに行く", "general"),
    ("朝一番に", "しんぶんをよむ", "新聞を読む", "general"),
    ("美味しい珈琲を", "いれる", "淹れる", "general"),
]

def load_clean_testset_pairs() -> List[Tuple[str, str, str, str]]:
    """Compatibility name: evaluation examples are never training seeds."""
    return list(SEED_CORPUS)

def generate_dataset(output_dir: str = "data", total_samples: int = 6000):
    os.makedirs(output_dir, exist_ok=True)
    random.seed(20261027)
    raw_pairs = load_clean_testset_pairs()
    print(f"Total verified seed pairs: {len(raw_pairs)}")

    train_samples = []
    val_samples = []

    # 文脈バリエーション
    CONTEXT_PREFIXES = [
        "", "先ほど、", "昨日の件ですが、", "急ぎの用件で、", "念のため、", "共有です。",
        "ところで、", "ちなみに、", "本日の予定について、", "チャットで、"
    ]

    for i in range(total_samples):
        base_ctx, reading, expected, cat = random.choice(raw_pairs)

        # 文脈装飾（base_ctxがある場合のみ修飾）
        if base_ctx:
            ctx_prefix = random.choice(CONTEXT_PREFIXES) if random.random() < 0.25 else ""
            left_ctx = (ctx_prefix + base_ctx).strip()
        else:
            left_ctx = ""

        # 20%は「タイポなしの正例」（過剰補正の抑制用）
        is_clean = (random.random() < 0.20)

        if is_clean:
            input_kata = hira_to_kata(reading)
            typo_type = "clean"
        else:
            input_kata, typo_type = apply_typo(reading)

        # zenz 制御プロンプト構築: \uEE02{left_context}\uEE00{input_reading}\uEE01
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

    train_path = os.path.join(output_dir, 'typo_dataset_train.jsonl')
    val_path = os.path.join(output_dir, 'typo_dataset_val.jsonl')

    with open(train_path, "w", encoding="utf-8") as f:
        for s in train_samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")

    with open(val_path, "w", encoding="utf-8") as f:
        for s in val_samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")

    print(f"Generated {len(train_samples)} train samples -> {train_path}")
    print(f"Generated {len(val_samples)} val samples -> {val_path}")

    # 内訳の集計
    types_count = {}
    for s in train_samples:
        t = s["typo_type"]
        types_count[t] = types_count.get(t, 0) + 1
    print("Typo types distribution in train:")
    for k, v in sorted(types_count.items(), key=lambda x: x[1], reverse=True):
        print(f"  {k:15s}: {v:5d} ({v/len(train_samples)*100:.1f}%)")

if __name__ == "__main__":
    generate_dataset(output_dir="data", total_samples=6000)
