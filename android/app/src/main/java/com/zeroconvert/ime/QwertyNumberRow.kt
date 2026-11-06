package com.zeroconvert.ime

/**
 * Digit assigned to each QWERTY top-row letter when the number row is hidden.
 * The up-flick on q..p keeps 1..0 reachable without the dedicated row, and the
 * shifted symbols stay reachable through the same flick while shift is active.
 */
object QwertyNumberRow {
    private const val LETTERS = "qwertyuiop"
    private const val DIGITS = "1234567890"
    private const val SHIFTED_SYMBOLS = "!@#$%^&*()"

    fun digitFor(letter: Char): Char? = at(LETTERS.indexOf(letter.lowercaseChar()), DIGITS)

    fun shiftedSymbolFor(letter: Char): Char? = at(LETTERS.indexOf(letter.lowercaseChar()), SHIFTED_SYMBOLS)

    private fun at(index: Int, source: String): Char? = if (index >= 0) source[index] else null
}
