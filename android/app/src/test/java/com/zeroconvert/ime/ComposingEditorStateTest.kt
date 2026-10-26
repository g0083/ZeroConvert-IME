package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import org.junit.Assert.*
import org.junit.Test

class ComposingEditorStateTest {
    private class Editor(prefix: String = "前文") : ComposingEditorState.Editor {
        var text = prefix
        var cursor = prefix.length
        var start: Int? = null
        var end: Int? = null
        var batchDepth = 0
        var batchStarts = 0
        var selectionQueries = 0
        var selectionCalls = 0
        var refuseText = false
        var refuseSelection = false
        var provideSelection = true
        override fun beginBatch() { batchDepth++; batchStarts++ }
        override fun endBatch() { batchDepth-- }
        override fun composing(text: String): Boolean {
            if (refuseText) return false
            val from = start ?: cursor
            val to = end ?: cursor
            this.text = this.text.take(from) + text + this.text.drop(to)
            start = from
            end = from + text.length
            cursor = checkNotNull(end)
            return true
        }
        override fun selectionEnd(): Int? { selectionQueries++; return cursor.takeIf { provideSelection } }
        override fun select(position: Int): Boolean {
            selectionCalls++
            if (refuseSelection) return false
            cursor = position
            return true
        }
        fun commit(text: String) {
            val from = start ?: cursor
            this.text = this.text.take(from) + text + this.text.drop(end ?: cursor)
            cursor = from + text.length
            start = null
            end = null
        }
        fun beforeCursor() = text.take(cursor)
    }

    @Test fun rawCursorStaysAtThePrefixInsteadOfJumpingToTheEnd() {
        val state = ComposingEditorState()
        val editor = Editor()
        assertTrue(state.write(editor, "とうきょうにいく", 5))
        assertEquals("前文とうきょうにいく", editor.text)
        assertEquals("前文とうきょう", editor.beforeCursor())
        assertEquals("前文", EditorContext.confirmedLeft(editor.beforeCursor(), state.preview, state.cursor))
        assertEquals(0, editor.batchDepth)
    }

    @Test fun conversionUsesTheSurfaceCursorWhileKeepingTheUnconvertedSuffix() {
        val state = ComposingEditorState()
        val editor = Editor()
        assertTrue(state.write(editor, "とうきょうにいく", 5))
        assertTrue(state.write(editor, "東京にいく", 2))
        assertEquals("前文東京にいく", editor.text)
        assertEquals("前文東京", editor.beforeCursor())
        assertEquals("前文", EditorContext.confirmedLeft(editor.beforeCursor(), state.preview, state.cursor))
    }

    @Test fun ownIntermediateAndFinalCallbacksDoNotEndTheComposition() {
        val state = ComposingEditorState()
        assertTrue(state.write(Editor(), "あいう", 1))
        assertTrue(state.ownsSelection(5, 5, 2, 5))
        assertTrue(state.ownsSelection(3, 3, 2, 5))
        assertTrue(state.ownsSelection(3, 3, 2, 5))
        assertFalse(state.ownsSelection(4, 4, 2, 5))
        assertFalse(state.ownsSelection(3, 4, 2, 5))
    }

    @Test fun aLateOwnCallbackDoesNotReplaceTheNewCursorState() {
        val state = ComposingEditorState()
        val editor = Editor()
        assertTrue(state.write(editor, "あいう", 2))
        assertTrue(state.write(editor, "愛う", 1))
        assertTrue(state.ownsSelection(4, 4, 2, 5))
        assertEquals("愛う", state.preview)
        assertEquals(1, state.cursor)
        assertTrue(state.ownsSelection(3, 3, 2, 4))
    }

    @Test fun editorRefusalsDoNotAdvertiseAnUnacceptedMiddleCursor() {
        val state = ComposingEditorState()
        val editor = Editor()
        assertTrue(state.write(editor, "かな", 2))
        editor.refuseText = true
        assertFalse(state.write(editor, "変更", 1))
        assertEquals("かな", state.preview)
        editor.refuseText = false
        editor.refuseSelection = true
        assertFalse(state.write(editor, "あいう", 1))
        assertEquals(3, state.cursor)
        assertFalse(state.ownsSelection(3, 3, 2, 5))
        assertEquals(0, editor.batchDepth)
    }

    @Test fun aKnownComposingRangeWorksWithoutExtractedText() {
        val state = ComposingEditorState()
        state.recordPreview("かなあと")
        state.observeRange(10, 14)
        val editor = Editor("前文".repeat(5)).apply { provideSelection = false }
        assertTrue(state.write(editor, "かなあと", 1))
        assertEquals(11, editor.cursor)
    }

    @Test fun unknownEditorCoordinatesFailSafelyAndClearDoesNotReuseOldOwnership() {
        val state = ComposingEditorState()
        val editor = Editor().apply { provideSelection = false }
        assertFalse(state.write(editor, "かなあと", 1))
        assertEquals(0, editor.selectionCalls)
        editor.provideSelection = true
        assertTrue(state.write(editor, "かなあと", 1))
        state.clear()
        assertFalse(state.ownsSelection(3, 3, 2, 6))
        assertEquals("", state.preview)
    }

    @Test fun endTypingDoesNotQueryOrRepositionTheEditor() {
        val state = ComposingEditorState()
        val editor = Editor()
        assertTrue(state.write(editor, "かな", 2))
        assertEquals(0, editor.selectionQueries)
        assertEquals(0, editor.selectionCalls)
        assertEquals(0, editor.batchStarts)
    }

    @Test fun committingTheLeftCandidateRetainsTheRightReadingExactlyOnce() {
        val input = ComposingText().apply { insertAtCursorPosition("toukyouniiku", InputStyle.ROMAN2KANA) }
        input.moveCursorFromCursorPosition(-3)
        val state = ComposingEditorState()
        val editor = Editor()
        assertEquals("とうきょう", input.convertTargetBeforeCursor)
        assertTrue(state.write(editor, input.convertTarget, input.convertTargetCursorPosition))
        assertTrue(state.write(editor, "東京にいく", 2))
        editor.commit("東京")
        state.finishComposition()
        input.prefixComplete(5)
        assertTrue(state.write(editor, input.convertTarget, input.convertTargetCursorPosition))
        assertEquals("前文東京にいく", editor.text)
        assertTrue(state.ownsSelection(4, 4, 2, 7))
        assertEquals("にいく", state.preview)
        assertEquals(3, state.cursor)
        input.insertAtCursorPosition("よ", InputStyle.DIRECT)
        assertTrue(state.write(editor, input.convertTarget, input.convertTargetCursorPosition))
        assertEquals("前文東京にいくよ", editor.text)
    }

    @Test fun aNativePartialCandidateKeepsTheRemainingPrefixCursor() {
        val input = ComposingText().apply { insertAtCursorPosition("とうきょうにいく", InputStyle.DIRECT) }
        input.moveCursorFromCursorPosition(-2)
        val state = ComposingEditorState()
        val editor = Editor()
        assertTrue(state.write(editor, input.convertTarget, input.convertTargetCursorPosition))
        editor.commit("東京")
        state.finishComposition()
        input.prefixComplete(5)
        assertEquals("に", input.convertTargetBeforeCursor)
        assertTrue(state.write(editor, input.convertTarget, input.convertTargetCursorPosition))
        assertEquals("前文東京に", editor.beforeCursor())
        assertEquals("前文東京", EditorContext.confirmedLeft(editor.beforeCursor(), state.preview, state.cursor))
    }

    @Test fun deletingTheLastLeftCharacterDoesNotDeleteTheRightReading() {
        val input = ComposingText().apply { insertAtCursorPosition("あにいく", InputStyle.DIRECT) }
        input.moveCursorFromCursorPosition(-3)
        val state = ComposingEditorState()
        val editor = Editor()
        assertTrue(state.write(editor, input.convertTarget, input.convertTargetCursorPosition))
        input.deleteBackwardFromCursorPosition(1)
        assertTrue(state.write(editor, input.convertTarget, input.convertTargetCursorPosition))
        assertEquals("前文にいく", editor.text)
        assertEquals("前文", editor.beforeCursor())
        input.insertAtCursorPosition("と", InputStyle.DIRECT)
        assertTrue(state.write(editor, input.convertTarget, input.convertTargetCursorPosition))
        assertEquals("前文とにいく", editor.text)
    }

    @Test fun arrowsAndPreviewBoundariesDoNotSplitEmoji() {
        val text = "あ😀い"
        assertEquals(3, ComposingEditorState.moveByCodePoint(text, 4, -1))
        assertEquals(1, ComposingEditorState.moveByCodePoint(text, 3, -1))
        assertEquals(3, ComposingEditorState.moveByCodePoint(text, 1, 1))
        assertEquals(0, ComposingEditorState.moveByCodePoint(text, 0, -1))
        val editor = Editor()
        assertFalse(ComposingEditorState().write(editor, text, 2))
        assertEquals("前文", editor.text)
    }

    @Test fun extractedTextOffsetsAreAbsoluteAndSelectionsAreRejected() {
        assertEquals(124, ComposingEditorState.absoluteSelectionEnd(100, 24, 24))
        assertNull(ComposingEditorState.absoluteSelectionEnd(100, 20, 24))
        assertNull(ComposingEditorState.absoluteSelectionEnd(-1, 24, 24))
        assertNull(ComposingEditorState.absoluteSelectionEnd(Int.MAX_VALUE, 24, 24))
    }
}
