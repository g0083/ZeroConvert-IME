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

    private fun candidate(text: String, starts: IntArray, cut: Int): String =
        ELLIPSIS + text.substring(if (cut < starts.size) starts[cut] else text.length)

    private fun graphemeStarts(text: String): IntArray {
        val matcher = graphemes.matcher(text)
        val starts = ArrayList<Int>()
        while (matcher.find()) starts.add(matcher.start())
        return starts.toIntArray()
    }
}
