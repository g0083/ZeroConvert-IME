package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions for a caret moved into a region the engine already converted.
 * Rewriting the editor with the raw reading there destroys the conversion and
 * lets the whole composition be finished as kana.
 */
class CursorMoveCompositionTest {
    private class RecordingEditor : ComposingEditorState.Editor {
        var text = ""
        var cursor = 0
        var composingStart: Int? = null
        val written = mutableListOf<String>()

        override fun beginBatch() = Unit
        override fun endBatch() = Unit

        override fun composing(text: String): Boolean {
            val start = composingStart ?: cursor
            val replacedEnd = composingStart?.let { it + this.text.length - start } ?: cursor
            this.text = this.text.take(start) + text + this.text.drop(replacedEnd)
            composingStart = start
            cursor = start + text.length
            written.add(text)
            return true
        }

        override fun selectionEnd(): Int? = cursor

        override fun select(position: Int): Boolean {
            cursor = position
            return true
        }
    }

    @Test fun theConvertedTailMeansTheRawReadingMustNotReplaceThePreview() {
        // 今日はいい天気ですね for きょうはいいてんきですね with the caret at 2.
        // Keeping the preview is only possible while its tail matches the
        // remaining reading; a kanji tail never does.
        val preview = "今日はいい天気ですね"
        val reading = "きょうはいいてんきですね"
        val caret = 2
        val remainingReading = reading.drop(caret)
        assertFalse(
            "a converted tail must not be appended to the raw reading",
            preview.endsWith(remainingReading)
        )
    }

    @Test fun aCaretAtTheEndKeepsTheConvertedPreview() {
        val state = ComposingEditorState()
        val editor = RecordingEditor()
        val preview = "今日はいい天気ですね"
        assertTrue(state.write(editor, preview, preview.length))
        assertEquals(listOf(preview), editor.written)
        assertEquals(preview, editor.text)
        assertEquals(preview.length, state.cursor)
    }

    @Test fun adoptCursorAcceptsOnlyOffsetsInsideThePreview() {
        val state = ComposingEditorState()
        state.recordPreview("今日はいい天気ですね")
        assertTrue(state.adoptCursor(2))
        assertEquals(2, state.cursor)
        assertTrue(state.adoptCursor(10))
        assertEquals(10, state.cursor)
    }

    @Test fun adoptCursorRejectsOffsetsOutsideThePreview() {
        val state = ComposingEditorState()
        state.recordPreview("今日はいい天気ですね")
        assertFalse(state.adoptCursor(-1))
        assertFalse(state.adoptCursor(11))
        assertEquals(10, state.cursor)
    }

    @Test fun aSelfIssuedTailWriteIsOwnedSoItCannotFinishTheComposition() {
        val state = ComposingEditorState()
        val editor = RecordingEditor()
        val preview = "東京にいく"
        assertTrue(state.write(editor, preview, preview.length))
        // The editor reports the caret the IME itself just placed.
        assertTrue(state.ownsSelection(preview.length, preview.length, 0, preview.length))
    }
}
