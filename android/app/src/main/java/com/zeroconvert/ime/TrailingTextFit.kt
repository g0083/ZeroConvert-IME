package com.zeroconvert.ime

import java.util.regex.Pattern

/**
 * Fits a reading into a fixed width by dropping its oldest (leftmost) characters.
 *
 * The composing reading grows to the right, so the newest input must stay visible: an
 * overflow removes whole graphemes from the start and marks the cut with "…".
 */
internal object TrailingTextFit {
    const val ELLIPSIS = "…"

    internal data class FittedReading(
        val text: String,
        val cursor: Int
    )

    // Initialized on first overflow only; ordinary short readings never build the pattern.
    private val graphemes by lazy { Pattern.compile("\\X") }

    /** Returns [text] unchanged when it fits, otherwise the longest fitting "…"-prefixed suffix. */
    fun fit(text: String, fits: (String) -> Boolean): String {
        if (text.isEmpty() || fits(text)) return text
        val starts = graphemeStarts(text)
        // Width shrinks monotonically as the cut moves right, so the first fitting cut is the longest suffix.
        var low = 1
        var high = starts.size
        var best = -1
        while (low <= high) {
            val middle = (low + high) / 2
            if (fits(candidate(text, starts, middle))) {
                best = middle
                high = middle - 1
            } else {
                low = middle + 1
            }
        }
        return if (best < 0) ELLIPSIS else candidate(text, starts, best)
    }

    /**
     * Fits [text] into a fixed width while keeping [cursor] inside the visible window.
     * Any cut side(s) are marked with "…", and [FittedReading.cursor] is mapped into
     * the returned [FittedReading.text].
     */
    fun fitWithCursor(text: String, cursor: Int, fits: (String) -> Boolean): FittedReading {
        if (text.isEmpty()) return FittedReading("", 0)
        val starts = graphemeStarts(text)
        val graphemeCount = starts.size
        val (cursorG, snappedCursor) = snapCursorToGrapheme(text, starts, cursor)
        if (fits(text)) return FittedReading(text, snappedCursor)

        fun buildWindow(windowGraphemes: Int): FittedReading {
            var leftTake = minOf(cursorG, windowGraphemes / 2)
            val rightTake = minOf(graphemeCount - cursorG, windowGraphemes - leftTake)
            if (leftTake + rightTake < windowGraphemes) {
                leftTake = minOf(cursorG, windowGraphemes - rightTake)
            }
            val startG = cursorG - leftTake
            val endG = cursorG + rightTake
            val startOffset = if (startG < graphemeCount) starts[startG] else text.length
            val endOffset = if (endG < graphemeCount) starts[endG] else text.length
            val leftEllipsis = if (startG > 0) ELLIPSIS else ""
            val rightEllipsis = if (endG < graphemeCount) ELLIPSIS else ""
            val windowText = leftEllipsis + text.substring(startOffset, endOffset) + rightEllipsis
            val mappedCursor = leftEllipsis.length + (snappedCursor - startOffset)
            return FittedReading(windowText, mappedCursor)
        }

        var low = 0
        var high = (graphemeCount - 1).coerceAtLeast(0)
        var best: FittedReading? = null
        while (low <= high) {
            val middle = (low + high) / 2
            val candidate = buildWindow(middle)
            if (fits(candidate.text)) {
                best = candidate
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (best != null) return best
        return FittedReading(
            text = ELLIPSIS,
            cursor = if (cursorG >= graphemeCount) ELLIPSIS.length else 0
        )
    }

    private fun snapCursorToGrapheme(text: String, starts: IntArray, cursor: Int): Pair<Int, Int> {
        if (cursor <= 0 || starts.isEmpty()) return 0 to 0
        if (cursor >= text.length) return starts.size to text.length
        var low = 0
        var high = starts.size - 1
        var index = 0
        while (low <= high) {
            val middle = (low + high) / 2
            if (starts[middle] <= cursor) {
                index = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return index to starts[index]
    }

    private fun candidate(text: String, starts: IntArray, cut: Int): String =
        ELLIPSIS + text.substring(if (cut < starts.size) starts[cut] else text.length)

    private fun graphemeStarts(text: String): IntArray {
        val matcher = graphemes.matcher(text)
        val starts = ArrayList<Int>()
        while (matcher.find()) starts.add(matcher.start())
        return starts.toIntArray()
    }
}
