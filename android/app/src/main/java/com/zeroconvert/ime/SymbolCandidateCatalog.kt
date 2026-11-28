package com.zeroconvert.ime

/**
 * Gboard-inspired symbol conversion candidates.
 *
 * When the user types symbols such as `()`, `（）`, `（`, `「`, `→`, `☆`, `、`, `。`, `！`, `？`,
 * `ー`, or `〜` (across Japanese, Numeric, and English layouts), this catalog supplies related
 * full-width/half-width and grouped symbol candidates (for example `()` -> `【】`, `「」`, `『』`, etc.)
 * while preserving engine-learned rankings when available.
 */
object SymbolCandidateCatalog {

    private val bracketPairs = listOf(
        "（）", "()", "「」", "【】", "『』", "［］", "[]", "｛｝", "{}", "＜＞", "<>",
        "〈〉", "《》", "〔〕", "“”", "\"\"", "‘’", "''", "〝〟", "〚〛", "〘〙"
    )

    private val openBrackets = listOf(
        "（", "(", "「", "【", "『", "［", "[", "｛", "{", "＜", "<", "〈", "《", "〔", "“", "‘", "〝"
    )

    private val quickPairsForOpenBracket = listOf(
        "（）", "()", "「」", "【】", "『』", "［］", "[]", "｛｝", "{}"
    )

    private val closeBrackets = listOf(
        "）", ")", "」", "】", "』", "］", "]", "｝", "}", "＞", ">", "〉", "》", "〕", "”", "’", "〟"
    )

    private val commaGroup = listOf(
        "、", ",", "，", "。", ".", "．", "・", "…", "‥", "：", ":", "；", ";"
    )

    private val periodGroup = listOf(
        "。", ".", "．", "、", ",", "，", "・", "…", "‥", "゜", "°", "◯", "○", "●"
    )

    private val middleDotSlashGroup = listOf(
        "・", "/", "／", "…", "‥", "･", "：", ":", "；", ";", "※",
        "★", "☆", "●", "○", "■", "□", "◆", "◇", "▲", "△", "▼", "▽"
    )

    private val ellipsisGroup = listOf(
        "…", "……", "‥", "...", "・・・", "。。。", "、、、"
    )

    private val exclamationGroup = listOf(
        "！", "!", "！？", "!?", "！！", "!!", "？", "?", "？！", "?!"
    )

    private val questionGroup = listOf(
        "？", "?", "？！", "?!", "？？", "??", "！", "!", "！？", "!?"
    )

    private val exclaimQuestionGroup = listOf(
        "！？", "!?", "？！", "?!", "！！", "!!", "？？", "??"
    )

    private val questionExclaimGroup = listOf(
        "？！", "?!", "！？", "!?", "？？", "??", "！！", "!!"
    )

    private val doubleExclaimGroup = listOf(
        "！！", "!!", "！", "!", "！？", "!?"
    )

    private val doubleQuestionGroup = listOf(
        "？？", "??", "？", "?", "？！", "?!"
    )

    private val dashArrowGroup = listOf(
        "ー", "-", "－", "―", "‐", "–", "—", "〜", "～", "~",
        "→", "←", "↑", "↓", "↔", "⇒", "⇐", "⇔", "＝", "=", "＿", "_", "─", "━"
    )

    private val arrowGroup = listOf(
        "→", "←", "↑", "↓", "↔", "↕", "⇒", "⇐", "⇑", "⇓", "⇔", "↗", "↘", "↙", "↖", "ー", "―"
    )

    private val waveGroup = listOf(
        "〜", "～", "~", "ー", "―", "−", "-", "－", "≒", "≈", "→", "⇒", "⇔"
    )

    private val longDashGroup = listOf(
        "――", "──", "━━", "——", "--", "〜〜", "～～"
    )

    private val starShapeGroup = listOf(
        "☆", "★", "○", "●", "◎", "◇", "◆", "□", "■", "△", "▲", "▽", "▼", "※", "＊", "*"
    )

    private val circleShapeGroup = listOf(
        "○", "●", "◎", "◯", "〇", "×", "✕", "△", "▲", "□", "■", "◇", "◆", "☆", "★"
    )

    private val musicGroup = listOf(
        "♪", "♫", "♬", "♩", "♭", "♯", "♮"
    )

    private val plusGroup = listOf(
        "＋", "+", "±", "−", "-", "×", "÷", "＝", "=", "≠", "≒", "†", "‡"
    )

    private val multiplyDivideGroup = listOf(
        "×", "÷", "✕", "✖", "＋", "+", "−", "-", "±", "＝", "=", "≠", "≒", "＊", "*", "／", "/"
    )

    private val equalsGroup = listOf(
        "＝", "=", "≠", "≒", "≡", "≈", "≦", "≧", "＜", "<", "＞", ">", "⇒", "⇔"
    )

    private val asteriskGroup = listOf(
        "＊", "*", "※", "×", "✕", "★", "☆", "・", "†", "‡", "°"
    )

    private val atGroup = listOf(
        "＠", "@", "〒", "©", "®", "™", "§", "¶"
    )

    private val postalMarkGroup = listOf(
        "〒", "々", "〆", "〇", "※", "№", "℡", "§", "¶", "＠", "@"
    )

    private val hashGroup = listOf(
        "＃", "#", "♯", "♭", "♮", "№", "※"
    )

    private val percentDegreeGroup = listOf(
        "％", "%", "‰", "°", "℃", "℉", "′", "″"
    )

    private val ampersandGroup = listOf(
        "＆", "&", "§", "¶", "※"
    )

    private val currencyGroup = listOf(
        "￥", "¥", "＄", "$", "€", "£", "¢", "₩", "\\", "＼"
    )

    private val colonGroup = listOf(
        "：", ":", "；", ";", "・", "…", "‥", "÷"
    )

    private val semicolonGroup = listOf(
        "；", ";", "：", ":", "、", ",", "・"
    )

    private val underscoreGroup = listOf(
        "＿", "_", "－", "-", "―", "￣", "¯"
    )

    private val pipeGroup = listOf(
        "｜", "|", "│", "┃", "‖", "／", "/", "＼", "\\"
    )

    private val caretGroup = listOf(
        "＾", "^", "°", "′", "″"
    )

    private val doubleQuoteGroup = listOf(
        "\"\"", "“”", "\"", "”", "“", "''", "‘’", "「」", "『』", "〝〟"
    )

    private val singleQuoteGroup = listOf(
        "''", "‘’", "'", "’", "‘", "\"\"", "“”", "′", "″"
    )

    private val exactTable: Map<String, List<String>> = buildMap {
        // Bracket pairs: typing any bracket pair suggests all other pairs.
        for (pair in bracketPairs) {
            put(pair, bracketPairs)
        }
        // Single open brackets: suggest other open brackets plus common paired brackets.
        val openWithPairs = openBrackets + quickPairsForOpenBracket
        for (open in openBrackets) {
            put(open, openWithPairs)
        }
        // Single close brackets: suggest other close brackets.
        for (close in closeBrackets) {
            put(close, closeBrackets)
        }
        // Punctuation and ellipsis.
        for (k in listOf("、", ",", "，")) put(k, commaGroup)
        for (k in listOf("。", ".", "．")) put(k, periodGroup)
        for (k in listOf("・", "/", "／", "･")) put(k, middleDotSlashGroup)
        for (k in listOf("、、", "。。", "・・", "...", "。。。", "・・・", "…", "‥")) put(k, ellipsisGroup)

        // Exclamation / question marks.
        for (k in listOf("！", "!")) put(k, exclamationGroup)
        for (k in listOf("？", "?")) put(k, questionGroup)
        for (k in listOf("！？", "!?")) put(k, exclaimQuestionGroup)
        for (k in listOf("？！", "?!")) put(k, questionExclaimGroup)
        for (k in listOf("！！", "!!")) put(k, doubleExclaimGroup)
        for (k in listOf("？？", "??")) put(k, doubleQuestionGroup)

        // Dashes, waves, arrows.
        for (k in listOf("ー", "-", "－", "―", "‐", "–", "—")) put(k, dashArrowGroup)
        for (k in listOf("→", "←", "↑", "↓", "↔", "⇒", "⇐", "⇔")) put(k, arrowGroup)
        for (k in listOf("〜", "～", "~")) put(k, waveGroup)
        for (k in listOf("ーー", "--", "――", "──", "━━")) put(k, longDashGroup)

        // Shapes and music marks from numeric pad.
        for (k in listOf("☆", "★")) put(k, starShapeGroup)
        for (k in listOf("○", "●", "◎", "◯")) put(k, circleShapeGroup)
        for (k in listOf("♪", "♫", "♬", "♩")) put(k, musicGroup)

        // Operators, currency, and common symbols.
        for (k in listOf("＋", "+", "±")) put(k, plusGroup)
        for (k in listOf("×", "÷", "✕")) put(k, multiplyDivideGroup)
        for (k in listOf("＝", "=", "≠", "≒", "≦", "≧")) put(k, equalsGroup)
        for (k in listOf("＊", "*")) put(k, asteriskGroup)
        for (k in listOf("＠", "@")) put(k, atGroup)
        for (k in listOf("〒", "々", "〆", "〇")) put(k, postalMarkGroup)
        for (k in listOf("＃", "#")) put(k, hashGroup)
        for (k in listOf("％", "%", "°", "℃", "℉")) put(k, percentDegreeGroup)
        for (k in listOf("＆", "&")) put(k, ampersandGroup)
        for (k in listOf("＄", "$", "￥", "¥", "€", "£", "\\", "＼")) put(k, currencyGroup)
        for (k in listOf("：", ":")) put(k, colonGroup)
        for (k in listOf("；", ";")) put(k, semicolonGroup)
        for (k in listOf("＿", "_")) put(k, underscoreGroup)
        for (k in listOf("｜", "|")) put(k, pipeGroup)
        for (k in listOf("＾", "^")) put(k, caretGroup)
        for (k in listOf("\"", "”", "“")) put(k, doubleQuoteGroup)
        for (k in listOf("'", "’", "‘")) put(k, singleQuoteGroup)
    }

    private val convertibleSingleChars: Set<Char> = buildSet {
        for (key in exactTable.keys) {
            if (key.length == 1) add(key[0])
        }
    }

    /**
     * True when [ch] is a symbol that has conversion candidates in this catalog.
     */
    fun isConvertibleSymbolChar(ch: Char): Boolean = ch in convertibleSingleChars

    /**
     * True when [text] is non-empty and consists solely of convertible symbol characters.
     */
    fun isSymbolOnly(text: String): Boolean = text.isNotEmpty() && text.all { it in convertibleSingleChars }

    /**
     * True when typing [ch] should enter composition as a direct symbol so the user can convert it
     * (e.g. `(` then `)` -> `【】`), rather than committing immediately.
     *
     * Only activates when the composition is empty or already contains only convertible symbols,
     * preserving existing immediate-commit behavior after Japanese kana text.
     */
    fun shouldComposeSymbol(ch: Char, currentReading: String): Boolean {
        if (!isConvertibleSymbolChar(ch)) return false
        return currentReading.isEmpty() || isSymbolOnly(currentReading)
    }

    /**
     * True when a multi-character or single-character string [text] (such as `(` or `()` from a
     * numeric pad key) should enter symbol composition.
     */
    fun shouldComposeSymbolText(text: String, currentReading: String): Boolean {
        if (!isSymbolOnly(text)) return false
        return currentReading.isEmpty() || isSymbolOnly(currentReading)
    }

    /**
     * Returns ordered symbol texts for [reading], starting with [reading] itself, or empty when
     * [reading] does not match any symbol entry.
     */
    fun symbolsFor(reading: String): List<String> {
        if (reading.isEmpty()) return emptyList()
        val group = exactTable[reading] ?: return emptyList()
        val ordered = LinkedHashSet<String>(group.size + 1)
        ordered.add(reading)
        ordered.addAll(group)
        return ordered.toList()
    }

    /**
     * Merges engine candidates with Gboard-style symbol candidates for [reading].
     *
     * When [reading] matches a symbol entry:
     * 1. Engine candidates that match the reading or a known symbol in the group come first
     *    (preserving user selection learning).
     * 2. Remaining symbol candidates from the catalog follow in curated order.
     * 3. Any other engine candidates are appended after the symbol candidates without duplicates.
     */
    fun mergeWithEngineCandidates(reading: String, engineCandidates: List<Candidate>): List<Candidate> {
        val symbolTexts = symbolsFor(reading)
        if (symbolTexts.isEmpty()) return engineCandidates

        val symbolSet = symbolTexts.toHashSet()
        val result = mutableListOf<Candidate>()
        val seen = HashSet<String>(symbolTexts.size + engineCandidates.size)

        // 1. Prioritize engine candidates that are themselves in the symbol set (e.g. learned picks).
        for (cand in engineCandidates) {
            if (cand.text in symbolSet && seen.add(cand.text)) {
                result.add(cand)
            }
        }

        // 2. Append remaining curated symbol candidates in order.
        val katakanaReading = KanaKanjiConverter.toKatakana(reading)
        for ((index, sym) in symbolTexts.withIndex()) {
            if (seen.add(sym)) {
                result.add(
                    Candidate(
                        text = sym,
                        reading = katakanaReading,
                        cost = 10f + index * 0.1f,
                        isAI = false,
                        learningAllowed = true,
                        engineConsumedReading = reading.length
                    )
                )
            }
        }

        // 3. Preserve any remaining non-duplicate engine candidates at the tail.
        for (cand in engineCandidates) {
            if (seen.add(cand.text)) {
                result.add(cand)
            }
        }

        return result
    }
}
