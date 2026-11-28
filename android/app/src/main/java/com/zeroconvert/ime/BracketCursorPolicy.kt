package com.zeroconvert.ime

/**
 * Policy for placing the editor caret inside paired brackets/quotes after committing them
 * (e.g. committing `「」` or `（）` leaves the caret between the opening and closing characters).
 */
object BracketCursorPolicy {
    private val pairedBrackets: Set<String> = setOf(
        "（）", "()", "「」", "【】", "『』", "［］", "[]", "｛｝", "{}",
        "＜＞", "<>", "〈〉", "《》", "〔〕", "“”", "\"\"", "‘’", "''",
        "〝〟", "〚〛", "〘〙"
    )

    fun isBracketPair(text: String): Boolean = text.length == 2 && text in pairedBrackets

    fun shouldMoveCursorInside(
        committedText: String,
        trailingText: String = "",
        enabled: Boolean = true
    ): Boolean = enabled && trailingText.isEmpty() && isBracketPair(committedText)
}
