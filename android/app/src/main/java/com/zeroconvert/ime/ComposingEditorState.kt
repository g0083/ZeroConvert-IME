package com.zeroconvert.ime

/** Owns editor preview/caret coordinates separately from the kana reading cursor. */
internal class ComposingEditorState {
    interface Editor {
        fun beginBatch()
        fun composing(text: String): Boolean
        fun selectionEnd(): Int?
        fun select(position: Int): Boolean
        fun endBatch()
    }

    private data class Selection(val position: Int, val start: Int, val end: Int) {
        fun matches(position: Int, reportedStart: Int, reportedEnd: Int): Boolean {
            if (this.position != position) return false
            // Editor callbacks can combine our new caret with a previous
            // composing end, or omit the range altogether. The requested caret
            // and, when present, the composing origin identify our operation.
            // UNKNOWN_ORIGIN accepts any origin because the absolute start was
            // not known when the write happened.
            return (reportedStart == -1 && reportedEnd == -1) ||
                (start == UNKNOWN_ORIGIN && reportedEnd >= reportedStart) ||
                (reportedStart == start && reportedEnd >= reportedStart)
        }
    }
    private val pendingSelections = ArrayDeque<Selection>()
    private var currentSelection: Selection? = null
    private var startHint: Int? = null
    var preview: String = ""
        private set
    var cursor: Int = 0
        private set

    fun recordPreview(text: String, expectTailCursor: Boolean = false) {
        preview = text
        cursor = text.length
        currentSelection = null
        if (text.isEmpty()) {
            startHint = null
            return
        }
        if (!expectTailCursor) return
        // A write that leaves the caret at the end still makes the editor report
        // a selection change for the caret this IME just placed. Record it as an
        // expectation; otherwise the callback looks external and the caller
        // finishes the composition, committing whatever kana the editor holds.
        // Only accepted writes may claim this: a refused write must leave the
        // caret unowned so the caller can recover.
        expectTailCursor(text.length)
    }

    private fun expectTailCursor(offset: Int) {
        val start = startHint
        val selection = Selection(
            position = start?.plus(offset) ?: offset,
            start = start ?: UNKNOWN_ORIGIN,
            end = start?.plus(preview.length) ?: UNKNOWN_ORIGIN
        )
        currentSelection = selection
        pendingSelections.addLast(selection)
        while (pendingSelections.size > MAX_PENDING_SELECTIONS) pendingSelections.removeFirst()
    }

    fun observeRange(start: Int, end: Int) {
        if (preview.isNotEmpty() && start >= 0 && end >= start && end - start == preview.length) startHint = start
    }

    /** Adopts a caret the editor reported inside the current preview. */
    fun adoptCursor(offset: Int): Boolean {
        if (offset !in 0..preview.length) return false
        cursor = offset
        return true
    }

    private fun expect(position: Int, start: Int, end: Int) {
        val selection = Selection(position, start, end)
        currentSelection = selection
        pendingSelections.addLast(selection)
        while (pendingSelections.size > 16) pendingSelections.removeFirst()
    }

    fun ownsSelection(selectionStart: Int, selectionEnd: Int, composingStart: Int, composingEnd: Int): Boolean {
        if (selectionStart != selectionEnd) return false
        val index = pendingSelections.indexOfFirst { it.matches(selectionStart, composingStart, composingEnd) }
        if (index >= 0) {
            repeat(index + 1) { pendingSelections.removeFirst() }
            return true
        }
        return currentSelection?.matches(selectionStart, composingStart, composingEnd) == true
    }

    fun write(editor: Editor, text: String, cursorOffset: Int): Boolean {
        if (!isBoundary(text, cursorOffset)) return false
        if (cursorOffset == text.length) {
            if (!editor.composing(text)) return false
            recordPreview(text, expectTailCursor = true)
            return true
        }
        editor.beginBatch()
        try {
            if (!editor.composing(text)) return false
            recordPreview(text)
            val start = startHint
                ?: editor.selectionEnd()?.let { it - text.length }?.takeIf { it >= 0 }
                ?: return false
            startHint = start
            // Some editors report the temporary end position before the explicit caret update.
            expect(start + text.length, start, start + text.length)
            if (!editor.select(start + cursorOffset)) return false
            cursor = cursorOffset
            expect(start + cursorOffset, start, start + text.length)
            return true
        } finally { editor.endBatch() }
    }

    /** Commit may be followed immediately by a new composing suffix in the same batch. */
    fun finishComposition() {
        recordPreview("")
    }

    fun clear() {
        finishComposition()
        pendingSelections.clear()
    }

    companion object {
        fun absoluteSelectionEnd(startOffset: Int, selectionStart: Int, selectionEnd: Int): Int? {
            if (startOffset < 0 || selectionStart < 0 || selectionStart != selectionEnd) return null
            return (startOffset.toLong() + selectionEnd).takeIf { it <= Int.MAX_VALUE }?.toInt()
        }

        fun isBoundary(text: String, offset: Int): Boolean = offset in 0..text.length &&
            !(offset > 0 && offset < text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

        fun moveByCodePoint(text: String, cursor: Int, direction: Int): Int {
            require(isBoundary(text, cursor) && (direction == -1 || direction == 1))
            return if ((direction < 0 && cursor == 0) || (direction > 0 && cursor == text.length)) cursor
                else text.offsetByCodePoints(cursor, direction)
        }

        /** Marks an expectation whose absolute composing origin is not known yet. */
        private const val UNKNOWN_ORIGIN = Int.MIN_VALUE

        /** Autorepeat moves every 50 ms; keep more than one tick of history. */
        private const val MAX_PENDING_SELECTIONS = 64
    }
}
