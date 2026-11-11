package com.zeroconvert.ime

/**
 * Chooses the text shown inline for a reading, and decides when an established kanji
 * result must not be replaced by kana.
 *
 * The engine reports how much of the reading each candidate consumes. A candidate that
 * consumes only part of it gets the leftover kana appended for display, so a partial
 * candidate that happens to rank first (祖 for そ) would otherwise surface as 祖う once
 * the next kana arrives.
 */
object InlinePreviewPolicy {
    fun select(results: List<Candidate>, reading: String): Candidate? =
        results.firstOrNull { it.coversReading(reading) }
            ?: results.firstOrNull()

    /**
     * True only when re-converting the *same* reading would replace an established kanji
     * result with kana. A longer reading is a new inference whose preview must win,
     * otherwise the text from the shorter reading stays on screen.
     */
    fun isSameReadingKanaDowngrade(
        previousReading: String,
        reading: String,
        previousPreview: String,
        preview: String
    ): Boolean = previousReading == reading && preview == reading &&
        previousPreview.any { it.isKanji() } && preview.none { it.isKanji() }

    private fun Char.isKanji(): Boolean = this in '\u3400'..'\u9FFF'
}
