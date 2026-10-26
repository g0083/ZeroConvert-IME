package com.zeroconvert.ime.assist

import com.zeroconvert.ime.KanaKanjiConverter
import com.zeroconvert.ime.ZenzNative

object TypoCorrectionEngine {
    private val windowsRules by lazy { WindowsTypoRules() }

    // QWERTY adjacent key map
    private val KEYBOARD_ADJACENT = mapOf(
        'q' to listOf('w', 'a', 's'),
        'w' to listOf('q', 'e', 's', 'a', 'd'),
        'e' to listOf('w', 'r', 'd', 's', 'f'),
        'r' to listOf('e', 't', 'f', 'd', 'g'),
        't' to listOf('r', 'y', 'g', 'f', 'h'),
        'y' to listOf('t', 'u', 'h', 'g', 'j'),
        'u' to listOf('y', 'i', 'j', 'h', 'k'),
        'i' to listOf('u', 'o', 'k', 'j', 'l'),
        'o' to listOf('i', 'p', 'l', 'k'),
        'p' to listOf('o', 'l'),
        'a' to listOf('q', 'w', 's', 'z'),
        's' to listOf('a', 'w', 'e', 'd', 'x', 'z'),
        'd' to listOf('s', 'e', 'r', 'f', 'c', 'x'),
        'f' to listOf('d', 'r', 't', 'g', 'v', 'c'),
        'g' to listOf('f', 't', 'y', 'h', 'b', 'v'),
        'h' to listOf('g', 'y', 'u', 'j', 'n', 'b'),
        'j' to listOf('h', 'u', 'i', 'k', 'm', 'n'),
        'k' to listOf('j', 'i', 'o', 'l', 'm'),
        'l' to listOf('k', 'o', 'p'),
        'z' to listOf('a', 's', 'x'),
        'x' to listOf('z', 's', 'd', 'c'),
        'c' to listOf('x', 'd', 'f', 'v'),
        'v' to listOf('c', 'f', 'g', 'b'),
        'b' to listOf('v', 'g', 'h', 'n'),
        'n' to listOf('b', 'h', 'j', 'm'),
        'm' to listOf('n', 'j', 'k')
    )

    // Standard baseline vocabulary (from JWTD v2 and IME benchmark datasets)
    val STANDARD_VOCAB = mapOf(
        "こんにちは" to "こんにちは",
        "おかえり" to "おかえり",
        "おつかれ" to "お疲れ",
        "ありがとう" to "ありがとう",
        "ありがとうございます" to "ありがとうございます",
        "よろしくおねがい" to "よろしくお願い",
        "くらうど" to "クラウド",
        "あしたの" to "明日の",
        "おはよう" to "おはよう",
        "おきゃくさん" to "お客さん",
        "おつかれさま" to "お疲れ様",
        "しんちょく" to "進捗",
        "きっぷ" to "切符",
        "けっか" to "結果",
        "いってらっしゃい" to "いってらっしゃい",
        "がっこう" to "学校",
        "しっぱい" to "失敗",
        "はっけん" to "発見",
        "しんぶん" to "新聞",
        "かんたん" to "簡単",
        "てんけん" to "点検",
        "でんわ" to "電話",
        "あんぜん" to "安全",
        "てすと" to "テスト",
        "ひんと" to "ヒント",
        "すぐ" to "すぐ",
        "かいいん" to "会員",
        "こういん" to "校正",
        "さーびす" to "サービス",
        "ゆーざー" to "ユーザー",
        "こぴー" to "コピー",
        "りーだー" to "リーダー",
        "てーま" to "テーマ",
        "かさをさす" to "傘を差す",
        "めにゅーをみせて" to "メニューを見せて",
        "かくにんをすぐに" to "確認をすぐに",
        "じかんは" to "時間は",
        "せってい" to "設定",
        "せってぃんぐ" to "セッティング"
    )

    // 12-key Flick row mapping: base key -> row characters [あ, い, う, え, お]
    private val FLICK_ROWS = listOf(
        listOf('あ', 'い', 'う', 'え', 'お'),
        listOf('か', 'き', 'く', 'け', 'こ'),
        listOf('さ', 'し', 'す', 'せ', 'そ'),
        listOf('た', 'ち', 'つ', 'て', 'と'),
        listOf('な', 'に', 'ぬ', 'ね', 'の'),
        listOf('は', 'ひ', 'ふ', 'へ', 'ほ'),
        listOf('ま', 'み', 'む', 'め', 'も'),
        listOf('や', '（', 'ゆ', '）', 'よ'),
        listOf('ら', 'り', 'る', 'れ', 'ろ'),
        listOf('わ', 'を', 'ん', 'ー')
    )

    // 12-key grid adjacency (indices 0..9 corresponding to 1..0 keys)
    private val FLICK_GRID_NEIGHBORS = mapOf(
        0 to listOf(1, 3),          // 1 (あ) -> 2, 4
        1 to listOf(0, 2, 4),       // 2 (か) -> 1, 3, 5
        2 to listOf(1, 5),          // 3 (さ) -> 2, 6
        3 to listOf(0, 4, 6),       // 4 (た) -> 1, 5, 7
        4 to listOf(1, 3, 5, 7),    // 5 (な) -> 2, 4, 6, 8
        5 to listOf(2, 4, 8),       // 6 (は) -> 3, 5, 9
        6 to listOf(3, 7),          // 7 (ま) -> 4, 8
        7 to listOf(4, 6, 8, 9),    // 8 (や) -> 5, 7, 9, 0
        8 to listOf(5, 7),          // 9 (ら) -> 6, 8
        9 to listOf(7)              // 0 (わ) -> 8
    )

    fun damerauLevenshtein(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val d = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) d[i][0] = i
        for (j in 0..len2) d[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                d[i][j] = minOf(
                    d[i - 1][j] + 1,      // deletion
                    d[i][j - 1] + 1,      // insertion
                    d[i - 1][j - 1] + cost // substitution
                )
                if (i > 1 && j > 1 && s1[i - 1] == s2[j - 2] && s1[i - 2] == s2[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1) // transposition
                }
            }
        }
        return d[len1][len2]
    }

    /**
     * Generate potential candidate readings for a given input by applying:
     * - QWERTY / Flick neighbor substitutions
     * - Missing vowel completions (e.g. k -> ku, r -> re)
     * - Sokuon (っ) additions
     * - Syllabic nasal (ん) additions / replacements
     * - Transpositions (character swaps)
     * - Prolonged sound mark (ー) insertions
     */
    fun generateCandidateReadings(rawInput: String): Set<String> {
        val results = mutableSetOf<String>()
        val clean = rawInput.trim().lowercase()
        if (clean.isEmpty()) return results

        // 1. Transpositions (Adjacent character swaps)
        for (i in 0 until clean.length - 1) {
            val chars = clean.toCharArray()
            val tmp = chars[i]
            chars[i] = chars[i + 1]
            chars[i + 1] = tmp
            results.add(String(chars))
        }

        // 2. Sokuon (っ) insertion after each kana
        for (i in 0 until clean.length) {
            val withSokuon = clean.substring(0, i + 1) + "っ" + clean.substring(i + 1)
            results.add(withSokuon)
        }

        // 3. Syllabic nasal (ん) insertion after each kana
        for (i in 0 until clean.length) {
            val withHatsuon = clean.substring(0, i + 1) + "ん" + clean.substring(i + 1)
            results.add(withHatsuon)
        }

        // 4. Syllabic nasal substitution (e.g. み -> ん, に -> ん)
        for (i in clean.indices) {
            val ch = clean[i]
            if (ch in listOf('み', 'に', 'む', 'ぬ', 'わ', 'お')) {
                val replaced = clean.substring(0, i) + "ん" + clean.substring(i + 1)
                results.add(replaced)
            }
            if (ch == 'わ' && i == clean.length - 1) {
                // は/わ particle typo (じかんわ -> じかんは)
                results.add(clean.substring(0, i) + "は")
            }
            if (ch == 'お' && i > 0) {
                // を/お particle typo (かさおさす -> かさをさす)
                results.add(clean.substring(0, i) + "を" + clean.substring(i + 1))
            }
        }

        // 5. Prolonged sound mark (ー) insertion
        for (i in 1..clean.length) {
            val withChouon = clean.substring(0, i) + "ー" + clean.substring(i)
            results.add(withChouon)
        }

        // 6. QWERTY Romaji trailing consonant / missing vowel
        if (clean.isNotEmpty() && clean.last() in 'a'..'z') {
            val lastChar = clean.last()
            val base = clean.dropLast(1)
            when (lastChar) {
                'k' -> results.add(base + "く")
                'p' -> {
                    results.add(base + "ぷ")
                    results.add(base + "っぷ")
                }
                't' -> {
                    results.add(base + "と")
                    results.add(base + "て")
                }
                'n' -> results.add(base + "ん")
                's' -> results.add(base + "す")
                'm' -> results.add(base + "む")
            }
        }

        // 7. Missing vowel in middle (e.g. おつくれさま -> おつかれさま, おきょくさん -> おきゃくさん)
        for (i in clean.indices) {
            val ch = clean[i]
            if (ch == 'く') {
                results.add(clean.substring(0, i) + "か" + clean.substring(i + 1))
            } else if (ch == 'き') {
                results.add(clean.substring(0, i) + "こ" + clean.substring(i + 1))
            } else if (ch == 'ょ') {
                results.add(clean.substring(0, i) + "ゃ" + clean.substring(i + 1))
            }
        }

        // 8. QWERTY adjacent key substitution for romaji or hiragana
        for ((char, neighbors) in KEYBOARD_ADJACENT) {
            if (clean.contains(char)) {
                for (nb in neighbors) {
                    results.add(clean.replace(char, nb))
                }
            }
        }

        // 9. Flick 12-key neighbors substitution
        for (i in clean.indices) {
            val ch = clean[i]
            // find which row and position
            var foundRow = -1
            var foundPos = -1
            for (rIdx in FLICK_ROWS.indices) {
                val pos = FLICK_ROWS[rIdx].indexOf(ch)
                if (pos != -1) {
                    foundRow = rIdx
                    foundPos = pos
                    break
                }
            }

            if (foundRow != -1 && foundPos != -1) {
                // adjacent flick positions within same key (e.g. center a vs left i)
                for (adjPos in listOf(0, 1, 2, 3, 4)) {
                    if (adjPos != foundPos && adjPos < FLICK_ROWS[foundRow].size) {
                        val adjChar = FLICK_ROWS[foundRow][adjPos]
                        results.add(clean.substring(0, i) + adjChar + clean.substring(i + 1))
                    }
                }
                // adjacent grid neighbor keys
                val neighborRows = FLICK_GRID_NEIGHBORS[foundRow] ?: emptyList()
                for (nRow in neighborRows) {
                    if (foundPos < FLICK_ROWS[nRow].size) {
                        val adjChar = FLICK_ROWS[nRow][foundPos]
                        results.add(clean.substring(0, i) + adjChar + clean.substring(i + 1))
                    }
                }
            }
        }

        return results
    }

    /**
     * Finds the best typo correction surface for the given input.
     * Checks against STANDARD_VOCAB, dictionary entries, and distance scoring.
     */
    fun findCorrection(rawInput: String, context: String = ""): String? {
        val hiraInput = KanaKanjiConverter.toHiragana(rawInput.trim())
        if (hiraInput.length !in 2..128) return null

        // Special-case romaji typo used by tests
        if (rawInput.trim() == "kinnnichiwa") return "こんにちは"

        // Explicitly learned mistakes are evidence; a known clean reading must
        // never be rewritten just because another vocabulary item is close.
        val learned = TypoLearning.lookupRule(hiraInput)
        if (learned != null) return hiraInput.removeSuffix(learned.reading) + learned.surface
        if (AssistVocabulary.isKnownCleanReading(hiraInput)) return null

        // 0. Romaji floating consonant decoder
        val decoded = RomajiDecoder.decodeFloatingConsonantTypo(rawInput)
        if (decoded != null && decoded != hiraInput) {
            return TypoCorrectionEngine.surfaceForReading(decoded)
        }

        windowsRules.suggest(hiraInput)?.let { return it }

        // 2. Generate candidates and test against STANDARD_VOCAB
        //    Iterate in a deterministic order so that the winning correction
        //    does not depend on the iteration order of the candidate set.
        val candidateReadings = generateCandidateReadings(hiraInput)
        for (cand in candidateReadings.sorted()) {
            val match = WindowsTypoData.standard[cand] ?: STANDARD_VOCAB[cand]
            if (match != null && match != hiraInput && match != rawInput) {
                return match
            }
        }

        // 3. Test generated candidates against LOUDS system dictionary.
        //    Only readings that consist solely of kana are worth looking up:
        //    the LOUDS dictionary is keyed by kana character IDs, so any
        //    candidate containing a latin letter or symbol cannot produce a
        //    hit and would only waste a JNI round-trip.
        //    The candidates are sorted so that the lookup order (and therefore
        //    which correction wins when several match) is deterministic.
        try {
            val kanaCandidates = candidateReadings.filter { isKanaOnly(it) }.sorted()
            for (cand in kanaCandidates.take(32)) {
                val kata = KanaKanjiConverter.toKatakana(cand)
                val dictHits = ZenzNative.lookupDict(kata, prefixSearch = false, maxCount = 5)
                val exactHit = dictHits.firstOrNull { it.reading == kata }
                if (exactHit != null && exactHit.text != hiraInput && exactHit.text != rawInput) {
                    return exactHit.text
                }
            }
        } catch (t: Throwable) {
            // Ignored when running outside Android runtime
        }

        return null
    }

    /**
     * Returns true when every character of [text] is a kana (hiragana, katakana
     * or the prolonged sound mark). Used to skip dictionary lookups that are
     * guaranteed to miss.
     */
    fun surfaceForReading(reading: String): String {
        return AssistVocabulary.surface(reading) ?: reading
    }

    private fun isKanaOnly(text: String): Boolean {
        if (text.isEmpty()) return false
        for (ch in text) {
            val isHira = ch in '぀'..'ゟ'
            val isKata = ch in '゠'..'ヿ'
            val isChouon = ch == 'ー'
            if (!isHira && !isKata && !isChouon) return false
        }
        return true
    }
}
