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

    private data class Selection(val position: Int, val start: Int, val end: Int)
    private val pendingSelections = ArrayDeque<Selection>()
    private var currentSelection: Selection? = null
    private var startHint: Int? = null
    var preview: String = ""
        private set
    var cursor: Int = 0
        private set

    fun recordPreview(text: String) {
        preview = text
        cursor = text.length
        currentSelection = null
        if (text.isEmpty()) startHint = null
    }

    fun observeRange(start: Int, end: Int) {
        if (preview.isNotEmpty() && start >= 0 && end >= start && end - start == preview.length) startHint = start
    }

    private fun expect(position: Int, start: Int, end: Int) {
        val selection = Selection(position, start, end)
        currentSelection = selection
        pendingSelections.addLast(selection)
        while (pendingSelections.size > 16) pendingSelections.removeFirst()
    }

    fun ownsSelection(selectionStart: Int, selectionEnd: Int, composingStart: Int, composingEnd: Int): Boolean {
        if (selectionStart != selectionEnd) return false
        val selection = Selection(selectionStart, composingStart, composingEnd)
        val index = pendingSelections.indexOf(selection)
        if (index >= 0) {
            repeat(index + 1) { pendingSelections.removeFirst() }
            return true
        }
        return selection == currentSelection
    }

    fun write(editor: Editor, text: String, cursorOffset: Int): Boolean {
        if (!isBoundary(text, cursorOffset)) return false
        if (cursorOffset == text.length) {
            if (!editor.composing(text)) return false
            recordPreview(text)
            return true
        }
        editor.beginBatch()
        try {
            if (!editor.composing(text)) return false
            recordPreview(text)
            val start = editor.selectionEnd()?.let { it - text.length }?.takeIf { it >= 0 } ?: startHint ?: return false
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
    }
}
