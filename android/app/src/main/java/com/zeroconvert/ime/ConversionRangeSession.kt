package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText

/** An explicit prefix range. Counts use UTF-16 like ComposingText, at code-point boundaries. */
class ConversionRangeSession {
    private var original: ComposingText? = null
    var originalPreview: String = ""
        private set
    val active: Boolean get() = original != null

    fun begin(input: ComposingText, preview: String): Boolean {
        if (active || !input.isAtEndIndex || input.convertTarget.codePointCount(0, input.convertTarget.length) !in 2..128) return false
        original = input.clone()
        originalPreview = preview
        val boundary = input.convertTarget.offsetByCodePoints(input.convertTarget.length, -1)
        input.moveCursorFromCursorPosition(boundary - input.convertTargetCursorPosition)
        return true
    }

    fun canAdjust(input: ComposingText, direction: Int): Boolean = original?.convertTarget == input.convertTarget && active && when (direction) {
        -1 -> input.convertTargetCursorPosition > input.convertTarget.offsetByCodePoints(0, 1)
        1 -> input.convertTargetCursorPosition < input.convertTarget.length
        else -> false
    }

    fun adjust(input: ComposingText, direction: Int): Boolean {
        if (!canAdjust(input, direction)) return false
        val boundary = input.convertTarget.offsetByCodePoints(input.convertTargetCursorPosition, direction)
        input.moveCursorFromCursorPosition(boundary - input.convertTargetCursorPosition)
        return true
    }

    fun cancel(input: ComposingText): Boolean {
        val snapshot = original ?: return false
        if (snapshot.convertTarget != input.convertTarget) { clear(); return false }
        input.restoreFrom(snapshot)
        clear()
        return true
    }

    fun clear() {
        original = null
        originalPreview = ""
    }

    companion object {
        fun preview(input: ComposingText, convertedPrefix: String, inline: Boolean): String =
            if (inline) convertedPrefix + input.convertTarget.drop(input.convertTargetCursorPosition) else input.convertTarget

        fun candidatesForRange(input: ComposingText, candidates: List<Candidate>): List<Candidate> {
            val reading = input.convertTargetBeforeCursor
            val katakana = KanaKanjiConverter.toKatakana(reading)
            return candidates.filter { it.assist == null && it.reading == katakana &&
                ConversionCandidateMerge.validSurface(it.text, reading) }
        }
    }
}
