package com.zeroconvert.ime

import java.util.regex.Pattern

/** Selection offsets are UTF-16; the resolved count uses visible Unicode grapheme clusters. */
data class SelectionCharacterCount private constructor(
    val utf16Length: Int,
    val characters: Int,
    val exact: Boolean
) {
    val label: String get() = if (exact) "${characters}文字選択" else "約${characters}文字選択"
    val canResolve: Boolean get() = !exact && utf16Length <= MAX_RESOLVED_UTF16

    fun resolve(text: CharSequence?, cancelled: () -> Boolean = { false }): SelectionCharacterCount? {
        if (cancelled()) return null
        if (!canResolve || text == null || text.length != utf16Length) return this
        val matcher = graphemes.matcher(text)
        var count = 0
        while (matcher.find()) {
            if ((count and 255) == 0 && cancelled()) return null
            count++
        }
        return copy(characters = count, exact = true)
    }

    companion object {
        const val MAX_RESOLVED_UTF16 = 131072
        // Initialized only on the counting worker, not while publishing selection offsets.
        private val graphemes by lazy { Pattern.compile("\\X") }

        fun fromSelection(start: Int, end: Int): SelectionCharacterCount? {
            if (start < 0 || end < 0 || start == end) return null
            val length = kotlin.math.abs(end.toLong() - start).toInt()
            return SelectionCharacterCount(length, length, exact = length == 1)
        }
    }
}

/** Closing input, collapsing, or reselecting the same offsets must invalidate old count jobs. */
internal class SelectionCountGate {
    data class Ticket(val generation: Long, val count: SelectionCharacterCount)
    private var generation = 0L
    fun capture(start: Int, end: Int): Ticket? {
        generation++
        return SelectionCharacterCount.fromSelection(start, end)?.let { Ticket(generation, it) }
    }
    fun isCurrent(ticket: Ticket): Boolean = generation == ticket.generation
}
