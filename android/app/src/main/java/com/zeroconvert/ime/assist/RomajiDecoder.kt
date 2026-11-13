package com.zeroconvert.ime.assist

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import com.zeroconvert.ime.azookey.input.InputPiece

object RomajiDecoder {

    private val qwertyAdjacent = mapOf(
        'q' to listOf('w', 'a', 's'),
        'w' to listOf('q', 'e', 'a', 's', 'd'),
        'e' to listOf('w', 'r', 's', 'd', 'f'),
        'r' to listOf('e', 't', 'd', 'f', 'g'),
        't' to listOf('r', 'y', 'f', 'g', 'h'),
        'y' to listOf('t', 'u', 'g', 'h', 'j'),
        'u' to listOf('y', 'i', 'h', 'j', 'k'),
        'i' to listOf('u', 'o', 'j', 'k', 'l'),
        'o' to listOf('i', 'p', 'k', 'l'),
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

    fun decodeFloatingConsonantTypo(rawInput: String): String? {
        if (rawInput.length > 128) return null
        val rawRomaji = normalizedRomaji(rawInput) ?: return null

        // Pattern 0: direct conversion yields valid word
        val directHira = romajiToHiragana(rawRomaji)
        if (isValidJapaneseWord(directHira)) return directHira

        val romChars = rawRomaji.toList()

        // Pattern A: adjacent key substitution
        for (i in romChars.indices) {
            val orig = romChars[i]
            for (adj in (qwertyAdjacent[orig] ?: emptyList())) {
                val cand = romChars.toMutableList().apply { this[i] = adj }
                val hira = romajiToHiragana(cand.joinToString(""))
                if (isValidJapaneseWord(hira)) return hira
            }
        }

        // Pattern B: insert vowel after consonant
        for (i in romChars.indices) {
            val c = romChars[i]
            if (c in "bcdfghjklmnpqrstvwxyz") {
                for (v in "aiueo") {
                    val cand = romChars.toMutableList().apply { add(i + 1, v) }
                    val hira = romajiToHiragana(cand.joinToString(""))
                    if (isValidJapaneseWord(hira)) return hira
                }
            }
        }

        // Pattern C: delete floating consonant
        for (i in romChars.indices) {
            val c = romChars[i]
            if (c in "bcdfghjklmnpqrstvwxyz") {
                val cand = romChars.toMutableList().apply { removeAt(i) }
                val hira = romajiToHiragana(cand.joinToString(""))
                if (isValidJapaneseWord(hira)) return hira
            }
        }

        return null
    }

    fun normalizedRomaji(rawInput: String): String? {
        val hasAlpha = rawInput.any { (it in 'a'..'z' || it in 'A'..'Z') || it in '\uFF21'..'\uFF3A' || it in '\uFF41'..'\uFF5A' }
        if (!hasAlpha) return null

        val sb = StringBuilder()
        val chars = rawInput.toList()
        var idx = 0
        while (idx < chars.size) {
            val c = chars[idx]
            when {
                c in '\uFF21'..'\uFF3A' -> { sb.append((c.code - 0xFF21 + 'a'.code).toChar()); idx++ }
                c in '\uFF41'..'\uFF5A' -> { sb.append((c.code - 0xFF41 + 'a'.code).toChar()); idx++ }
                (c in 'a'..'z' || c in 'A'..'Z') -> { sb.append(c.lowercaseChar()); idx++ }
                else -> {
                    // digraph check
                    if (idx + 1 < chars.size) {
                        val digraph = digraphToRomaji(c, chars[idx + 1])
                        if (digraph != null) { sb.append(digraph); idx += 2; continue }
                    }
                    val r = kanaCharToRomaji(c)
                    if (r.isNotEmpty()) sb.append(r) else sb.append(c)
                    idx++
                }
            }
        }
        return sb.toString()
    }

    /** Evidence for automatic application: exactly one full-word adjacent-key repair. */
    fun uniqueAdjacentCorrection(
        rawInput: String,
        knownReading: (String) -> Boolean = AssistVocabulary::containsReading
    ): String? {
        if (rawInput.length !in 2..128 || rawInput.none { it in 'ぁ'..'ん' }) return null
        val raw = normalizedRomaji(rawInput) ?: return null
        if (raw.lastOrNull()?.let { it in 'a'..'z' && it !in "aeioun" } == true) return null
        var unique: String? = null
        for (index in raw.indices) {
            for (neighbor in qwertyAdjacent[raw[index]].orEmpty()) {
                val candidate = raw.substring(0, index) + neighbor + raw.substring(index + 1)
                val reading = romajiToHiragana(candidate)
                if (reading != rawInput && knownReading(reading)) {
                    if (unique != null && unique != reading) return null
                    unique = reading
                }
            }
        }
        return unique
    }

    private fun digraphToRomaji(c1: Char, c2: Char): String? = when (c1 to c2) {
        'き' to 'ゃ' -> "kya"; 'き' to 'ゅ' -> "kyu"; 'き' to 'ょ' -> "kyo"
        'し' to 'ゃ' -> "sha"; 'し' to 'ゅ' -> "shu"; 'し' to 'ょ' -> "sho"
        'ち' to 'ゃ' -> "cha"; 'ち' to 'ゅ' -> "chu"; 'ち' to 'ょ' -> "cho"
        'に' to 'ゃ' -> "nya"; 'に' to 'ゅ' -> "nyu"; 'に' to 'ょ' -> "nyo"
        'ひ' to 'ゃ' -> "hya"; 'ひ' to 'ゅ' -> "hyu"; 'ひ' to 'ょ' -> "hyo"
        'み' to 'ゃ' -> "mya"; 'み' to 'ゅ' -> "myu"; 'み' to 'ょ' -> "myo"
        'り' to 'ゃ' -> "rya"; 'り' to 'ゅ' -> "ryu"; 'り' to 'ょ' -> "ryo"
        'ぎ' to 'ゃ' -> "gya"; 'ぎ' to 'ゅ' -> "gyu"; 'ぎ' to 'ょ' -> "gyo"
        'じ' to 'ゃ' -> "ja"; 'じ' to 'ゅ' -> "ju"; 'じ' to 'ょ' -> "jo"
        'び' to 'ゃ' -> "bya"; 'び' to 'ゅ' -> "byu"; 'び' to 'ょ' -> "byo"
        'ぴ' to 'ゃ' -> "pya"; 'ぴ' to 'ゅ' -> "pyu"; 'ぴ' to 'ょ' -> "pyo"
        else -> null
    }

    private fun kanaCharToRomaji(c: Char): String = when (c) {
        'あ' -> "a"; 'い' -> "i"; 'う' -> "u"; 'え' -> "e"; 'お' -> "o"
        'か' -> "ka"; 'き' -> "ki"; 'く' -> "ku"; 'け' -> "ke"; 'こ' -> "ko"
        'さ' -> "sa"; 'し' -> "si"; 'す' -> "su"; 'せ' -> "se"; 'そ' -> "so"
        'た' -> "ta"; 'ち' -> "ti"; 'つ' -> "tu"; 'て' -> "te"; 'と' -> "to"
        'な' -> "na"; 'に' -> "ni"; 'ぬ' -> "nu"; 'ね' -> "ne"; 'の' -> "no"
        'は' -> "ha"; 'ひ' -> "hi"; 'ふ' -> "hu"; 'へ' -> "he"; 'ほ' -> "ho"
        'ま' -> "ma"; 'み' -> "mi"; 'む' -> "mu"; 'め' -> "me"; 'も' -> "mo"
        'や' -> "ya"; 'ゆ' -> "yu"; 'よ' -> "yo"
        'ら' -> "ra"; 'り' -> "ri"; 'る' -> "ru"; 'れ' -> "re"; 'ろ' -> "ro"
        'わ' -> "wa"; 'を' -> "wo"; 'ん' -> "n'"
        'が' -> "ga"; 'ぎ' -> "gi"; 'ぐ' -> "gu"; 'げ' -> "ge"; 'ご' -> "go"
        'ざ' -> "za"; 'じ' -> "zi"; 'ず' -> "zu"; 'ぜ' -> "ze"; 'ぞ' -> "zo"
        'だ' -> "da"; 'ぢ' -> "di"; 'づ' -> "du"; 'で' -> "de"; 'ど' -> "do"
        'ば' -> "ba"; 'び' -> "bi"; 'ぶ' -> "bu"; 'べ' -> "be"; 'ぼ' -> "bo"
        'ぱ' -> "pa"; 'ぴ' -> "pi"; 'ぷ' -> "pu"; 'ぺ' -> "pe"; 'ぽ' -> "po"
        'ゃ' -> "xya"; 'ゅ' -> "xyu"; 'ょ' -> "xyo"; 'っ' -> "xtu"; 'ー' -> "-"
        else -> ""
    }

    internal fun romajiToHiragana(romaji: String): String {
        val input = ComposingText()
        val elements = mutableListOf<ComposingText.InputElement>()
        for ((index, ch) in romaji.withIndex()) {
            if (ch == '\'') {
                elements.add(ComposingText.InputElement(InputPiece.CompositionSeparator, InputStyle.ROMAN2KANA))
            } else {
                elements.add(ComposingText.InputElement(ch, InputStyle.ROMAN2KANA))
                // Full-word reconstruction: konnitiha means kon + nitiha.
                // Separate the nasal before the next syllable rather than consuming nn together.
                if (ch == 'n' && romaji.getOrNull(index + 1) == 'n' &&
                    romaji.getOrNull(index + 2)?.let { it in "aiueoy" } == true
                ) {
                    elements.add(ComposingText.InputElement(InputPiece.CompositionSeparator, InputStyle.ROMAN2KANA))
                }
            }
        }
        input.insertAtCursorPosition(elements)
        return input.convertTarget
    }

    private fun isValidJapaneseWord(hira: String): Boolean {
        if (hira.isEmpty() || hira.any { (it in 'a'..'z' || it in 'A'..'Z') }) return false
        // Check STANDARD_VOCAB
        if (AssistVocabulary.containsReading(hira)) return true
        if (TypoCorrectionEngine.STANDARD_VOCAB.values.any { it == hira }) return true
        // A vocabulary prefix does not validate the rest of a reconstructed word.
        if (TypoCorrectionEngine.STANDARD_VOCAB.containsKey(hira)) return true
        return false
    }
}
