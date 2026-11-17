package com.zeroconvert.ime

/**
 * Snapshot of the confirmed reading prefix reused while newer characters or modifiers
 * are still waiting on debounced conversion.
 */
data class TentativePrefix(
    val text: String,
    val inferredSurface: String,
    val inferredResult: String
)

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
    fun select(results: List<Candidate>, reading: String): Candidate? {
        val completedLen = completedReadingLength(reading)
        if (completedLen in 1 until reading.length) {
            val completed = reading.substring(0, completedLen)
            val pending = reading.substring(completedLen)
            val katakanaCompleted = KanaKanjiConverter.toKatakana(completed)
            val treatTrailingNAsComplete = pending.equals("n", ignoreCase = true)
            if (treatTrailingNAsComplete) {
                results.firstOrNull {
                    it.coversReading(reading) &&
                        !isRawPendingKanaEcho(
                            it.text, reading, completed, pending, katakanaCompleted, allowKanaN = true
                        )
                }?.let { return it }
            }
            results.firstOrNull {
                it.coversCompletedPrefix(completedLen) &&
                    !isRawPendingKanaEcho(
                        it.text, reading, completed, pending, katakanaCompleted,
                        allowKanaN = treatTrailingNAsComplete
                    )
            }?.let { return it }
        }
        return results.firstOrNull { it.coversReading(reading) }
            ?: results.firstOrNull()
    }

    /**
     * Computes immediate inline preview text before async conversion completes, preserving
     * an already-converted prefix across trailing romaji completion, 12-key modifiers,
     * toggle replacements, and backspace deletions.
     */
    fun tentativePrefix(
        surface: String,
        lastInferredSurface: String,
        lastInferredResult: String,
        history: List<InferenceSnapshot> = emptyList()
    ): TentativePrefix {
        if (surface.isEmpty()) return TentativePrefix("", "", "")
        if (lastInferredSurface.isNotEmpty() && lastInferredResult.isNotEmpty() &&
            surface.startsWith(lastInferredSurface)
        ) {
            return TentativePrefix(
                text = lastInferredResult + surface.substring(lastInferredSurface.length),
                inferredSurface = lastInferredSurface,
                inferredResult = lastInferredResult
            )
        }

        val historyMatch = history
            .asReversed()
            .filter {
                it.readingHiragana.isNotEmpty() &&
                    it.aiSurface.isNotEmpty() &&
                    surface.startsWith(it.readingHiragana)
            }
            .maxByOrNull { it.readingHiragana.length }

        var commonMatch: InferenceSnapshot? = null
        if (lastInferredSurface.isNotEmpty() && lastInferredResult.isNotEmpty()) {
            val rawCommonLen = commonPrefixLength(surface, lastInferredSurface)
            val commonLen = ComposingEditorState.snapBoundary(lastInferredSurface, rawCommonLen)
            if (commonLen > 0) {
                val dropped = lastInferredSurface.substring(commonLen)
                if (dropped.isNotEmpty() && lastInferredResult.endsWith(dropped)) {
                    val baseSurface = lastInferredSurface.substring(0, commonLen)
                    val baseResult = lastInferredResult.removeSuffix(dropped)
                    if (baseResult.isNotEmpty()) {
                        commonMatch = InferenceSnapshot(baseSurface, baseResult)
                    }
                }
            }
        }

        val chosen = when {
            historyMatch != null && commonMatch != null -> {
                val commonLen = commonMatch.readingHiragana.length
                val historyLen = historyMatch.readingHiragana.length
                val commonBeatsPlainHistory = commonLen == historyLen &&
                    commonMatch.aiSurface != commonMatch.readingHiragana &&
                    historyMatch.aiSurface == historyMatch.readingHiragana
                if (commonLen > historyLen || commonBeatsPlainHistory) commonMatch else historyMatch
            }
            else -> historyMatch ?: commonMatch
        }

        return if (chosen != null) {
            TentativePrefix(
                text = chosen.aiSurface + surface.substring(chosen.readingHiragana.length),
                inferredSurface = chosen.readingHiragana,
                inferredResult = chosen.aiSurface
            )
        } else {
            TentativePrefix(surface, "", "")
        }
    }

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

    /**
     * Suppresses kana downgrades for both the same reading and readings that only append
     * an incomplete non-'n' romaji consonant prefix onto an established conversion.
     */
    fun shouldSuppressPreviewDowngrade(
        previousReading: String,
        reading: String,
        previousPreview: String,
        preview: String
    ): Boolean {
        if (isSameReadingKanaDowngrade(previousReading, reading, previousPreview, preview)) {
            return true
        }
        val completedLen = completedReadingLength(reading)
        if (completedLen !in 1 until reading.length) return false
        val completed = reading.substring(0, completedLen)
        val pending = reading.substring(completedLen)
        if (pending.equals("n", ignoreCase = true)) return false
        val sameCompletedBase = completedReadingLength(previousReading) == completedLen &&
            previousReading.startsWith(completed)
        if (!sameCompletedBase) return false
        return (previousPreview.any { it.isKanji() } && preview.none { it.isKanji() }) ||
            (preview == reading && previousPreview != previousReading)
    }

    internal fun completedReadingLength(reading: String): Int {
        var end = reading.length
        while (end > 0 && reading[end - 1].isAsciiLetter()) {
            end--
        }
        return end
    }

    private fun Candidate.coversCompletedPrefix(completedLength: Int): Boolean {
        val consumed = engineConsumedReading ?: return true
        return consumed >= completedLength
    }

    private fun isRawPendingKanaEcho(
        text: String,
        reading: String,
        completed: String,
        pending: String,
        katakanaCompleted: String,
        allowKanaN: Boolean
    ): Boolean {
        if (text == reading || text == katakanaCompleted + pending) return true
        return allowKanaN && text == completed + "ん"
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) {
            i++
        }
        return i
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    private fun Char.isKanji(): Boolean = this in '\u3400'..'\u9FFF'
}
