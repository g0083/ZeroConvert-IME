package com.zeroconvert.ime

/**
 * Gboard-style secondary symbols for QWERTY keys across all rows, plus quick-symbol
 * popup candidates for long-pressing the period (`。` / `.`) key.
 */
object QwertySymbolHints {
    private val row2SymbolsEn: Map<Char, Char> = mapOf(
        'a' to '@',
        's' to '#',
        'd' to '$',
        'f' to '_',
        'g' to '&',
        'h' to '-',
        'j' to '+',
        'k' to '(',
        'l' to ')'
    )

    private val row2SymbolsJp: Map<Char, Char> = mapOf(
        'a' to '@',
        's' to '#',
        'd' to '￥',
        'f' to '_',
        'g' to '&',
        'h' to '-',
        'j' to '+',
        'k' to '(',
        'l' to ')',
        'ー' to '〜'
    )

    private val row3Symbols: Map<Char, Char> = mapOf(
        'z' to '*',
        'x' to '"',
        'c' to '\'',
        'v' to ':',
        'b' to ';',
        'n' to '!',
        'm' to '?'
    )

    val japanesePeriodPopupSymbols: List<String> = listOf(
        "。", "、", "？", "！", "・", "…", "「」", "（）", "ー", "〜"
    )

    val englishPeriodPopupSymbols: List<String> = listOf(
        ".", ",", "?", "!", "'", "\"", ":", ";", "-", "/"
    )

    /**
     * Returns the secondary (long-press / up-flick) symbol for a QWERTY letter key,
     * or `null` when unavailable for the current row/settings.
     */
    fun secondarySymbolFor(
        key: Char,
        isJapanese: Boolean,
        showNumberRow: Boolean,
        longPressSymbolsEnabled: Boolean,
        shifted: Boolean = false
    ): Char? {
        val lower = key.lowercaseChar()
        // Top row (q..p): digits when number row is hidden (always available),
        // or shifted symbols (!..)) when number row is shown and long-press symbols are enabled.
        val row1Digit = QwertyNumberRow.digitFor(lower)
        if (row1Digit != null) {
            return when {
                !showNumberRow -> if (shifted) QwertyNumberRow.shiftedSymbolFor(lower) else row1Digit
                longPressSymbolsEnabled -> QwertyNumberRow.shiftedSymbolFor(lower)
                else -> null
            }
        }
        if (!longPressSymbolsEnabled) return null
        val row2Map = if (isJapanese) row2SymbolsJp else row2SymbolsEn
        return row2Map[lower] ?: row3Symbols[lower]
    }

    fun periodPopupSymbols(isJapanese: Boolean): List<String> =
        if (isJapanese) japanesePeriodPopupSymbols else englishPeriodPopupSymbols
}
