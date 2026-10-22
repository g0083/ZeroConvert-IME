package com.zeroconvert.ime

object RomajiConverter {
    private val TABLE = mapOf(
        "a" to "ア", "i" to "イ", "u" to "ウ", "e" to "エ", "o" to "オ",
        "ka" to "カ", "ki" to "キ", "ku" to "ク", "ke" to "ケ", "ko" to "コ",
        "sa" to "サ", "shi" to "シ", "si" to "シ", "su" to "ス", "se" to "セ", "so" to "ソ",
        "ta" to "タ", "chi" to "チ", "ti" to "チ", "tsu" to "ツ", "tu" to "ツ", "te" to "テ", "to" to "ト",
        "na" to "ナ", "ni" to "ニ", "nu" to "ヌ", "ne" to "ネ", "no" to "ノ",
        "ha" to "ハ", "hi" to "ヒ", "fu" to "フ", "hu" to "フ", "he" to "ヘ", "ho" to "ホ",
        "ma" to "マ", "mi" to "ミ", "mu" to "ム", "me" to "メ", "mo" to "モ",
        "ya" to "ヤ", "yu" to "ユ", "yo" to "ヨ",
        "ra" to "ラ", "ri" to "リ", "ru" to "ル", "re" to "レ", "ro" to "ロ",
        "wa" to "ワ", "wo" to "ヲ", "nn" to "ン", "n" to "ン",
        "ga" to "ガ", "gi" to "ギ", "gu" to "グ", "ge" to "ゲ", "go" to "ゴ",
        "za" to "ザ", "ji" to "ジ", "zi" to "ジ", "zu" to "ズ", "ze" to "ゼ", "zo" to "ゾ",
        "da" to "ダ", "di" to "ヂ", "du" to "ヅ", "de" to "デ", "do" to "ド",
        "ba" to "バ", "bi" to "ビ", "bu" to "ブ", "be" to "ベ", "bo" to "ボ",
        "pa" to "パ", "pi" to "ピ", "pu" to "プ", "pe" to "ペ", "po" to "ポ",
        "kya" to "キャ", "kyu" to "キュ", "kyo" to "キョ",
        "sha" to "シャ", "shu" to "シュ", "sho" to "ショ", "sya" to "シャ", "syu" to "シュ", "syo" to "ショ",
        "cha" to "チャ", "chu" to "チュ", "cho" to "チョ", "tya" to "チャ", "tyu" to "チュ", "tyo" to "チョ",
        "nya" to "ニャ", "nyu" to "ニュ", "nyo" to "ニョ",
        "hya" to "ヒャ", "hyu" to "ヒュ", "hyo" to "ヒョ",
        "mya" to "ミャ", "myu" to "ミュ", "myo" to "ミョ",
        "rya" to "リャ", "ryu" to "リュ", "ryo" to "リョ",
        "gya" to "ギャ", "gyu" to "ギュ", "gyo" to "ギョ",
        "ja" to "ジャ", "ju" to "ジュ", "jo" to "ジョ", "zya" to "ジャ", "zyu" to "ジュ", "zyo" to "ジョ",
        "bya" to "ビャ", "byu" to "ビュ", "byo" to "ビョ",
        "pya" to "ピャ", "pyu" to "ピュ", "pyo" to "ピョ",
        "fa" to "ファ", "fi" to "フィ", "fe" to "フェ", "fo" to "フォ",
        "va" to "ヴァ", "vi" to "ヴィ", "vu" to "ヴ", "ve" to "ヴェ", "vo" to "ヴォ",
        "la" to "ァ", "li" to "ィ", "lu" to "ゥ", "le" to "ェ", "lo" to "ォ",
        "xa" to "ァ", "xi" to "ィ", "xu" to "ゥ", "xe" to "ェ", "xo" to "ォ",
        "xtsu" to "ッ", "xtu" to "ッ", "ltu" to "ッ", "ltsu" to "ッ",
        "-" to "ー", "ー" to "ー"
    )

    fun toHiragana(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            if (c in '\u30A1'..'\u30F6') {
                sb.append((c.code - 0x60).toChar())
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    data class ConversionResult(
        val katakana: String,
        val pendingRomaji: String
    )

    fun toKatakana(input: String): ConversionResult {
        var str = input.lowercase()
        val result = StringBuilder()
        var i = 0

        while (i < str.length) {
            // Check double consonants (促音 ッ) like "tt", "kk", "ss", etc.
            if (i + 1 < str.length && str[i] == str[i + 1] && str[i] !in "aeioun") {
                result.append("ッ")
                i++
                continue
            }

            // Check match of length 4, 3, 2, 1
            var matched = false
            for (len in 4 downTo 1) {
                if (i + len <= str.length) {
                    val sub = str.substring(i, i + len)
                    val kana = TABLE[sub]
                    if (kana != null) {
                        // Special handling for single 'n': don't consume if next is vowel or 'y'
                        if (sub == "n" && i + 1 < str.length && str[i + 1] in "aeiouy") {
                            // wait for next char
                            break
                        }
                        result.append(kana)
                        i += len
                        matched = true
                        break
                    }
                }
            }

            if (!matched) {
                // If remaining cannot be matched yet (e.g. "k" waiting for "a"), leave as pending
                return ConversionResult(result.toString(), str.substring(i))
            }
        }

        return ConversionResult(result.toString(), "")
    }
}
