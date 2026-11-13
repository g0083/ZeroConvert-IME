package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import org.junit.Assert.*
import org.junit.Test

class ComposingEditorStateTest {
    @Test fun ownCursorCallbackMayCarryThePreviousComposingEnd() {
        val state = ComposingEditorState()
        assertTrue(state.write(Editor(), "とうきょうにいく", 5))
        // The selection position is current, but this editor still reports the
        // previous converted preview's shorter composing range.
        assertTrue(state.ownsSelection(7, 7, 2, 7))
        assertTrue(state.ownsSelection(7, 7, 2, 10))
        assertFalse(state.ownsSelection(6, 6, 2, 10))
    }

    @Test fun ownCursorCallbackMayOmitTheComposingRange() {
        val state = ComposingEditorState()
        assertTrue(state.write(Editor(), "東京にいく", 2))
        assertTrue(state.ownsSelection(4, 4, -1, -1))
        assertTrue(state.ownsSelection(4, 4, -1, -1))
        assertFalse(state.ownsSelection(3, 3, -1, -1))
    }

    @Test fun mismatchedOriginsAndRealSelectionsAreNeverOwned() {
        val state = ComposingEditorState()
        assertTrue(state.write(Editor(), "東京にいく", 2))
        assertFalse(state.ownsSelection(4, 4, 1, 6))
        assertFalse(state.ownsSelection(4, 4, 2, 1))
        assertFalse(state.ownsSelection(4, 5, -1, -1))
        assertFalse(state.ownsSelection(4, 4, -1, 7))
    }

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
        var overrideSelectionEnd: Int? = null
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
        override fun selectionEnd(): Int? {
            selectionQueries++
            return overrideSelectionEnd ?: cursor.takeIf { provideSelection }
        }
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

    @Test fun writePrefersExistingStartHintOverStaleSelectionEnd() {
        val state = ComposingEditorState()
        val editor = Editor(prefix = "")
        // First, write "たいちょうかく" with cursor at 6 (prefix "たいちょうか")
        assertTrue(state.write(editor, "たいちょうかく", 6))
        assertEquals("たいちょうかく", editor.text)
        assertEquals(6, editor.cursor)

        // Now, conversion finishes and writes "体調かく" (length 4) with cursor at 3.
        // Simulate editor returning stale selectionEnd (6 from previous batch) rather than new length (4).
        editor.overrideSelectionEnd = 6 // stale cursor in editor
        assertTrue(state.write(editor, "体調かく", 3))
        assertEquals("体調かく", editor.text)
        // Cursor must be correctly set to 3 ("体調か|く"), NOT 5 or broken
        assertEquals(3, editor.cursor)
        assertEquals("体調かく", state.preview)
        assertEquals(3, state.cursor)
    }

    @Test fun writeSupportsMultipleConsecutiveLeftMovesWithoutCorruption() {
        val state = ComposingEditorState()
        val editor = Editor(prefix = "")
        // Write initial converted text "東京にいく" (5 chars)
        assertTrue(state.write(editor, "東京にいく", 5))
        assertEquals(5, editor.cursor)

        // Move 1: suffix "く" (len 1) -> cursor at 4 ("東京にい|く")
        assertTrue(state.write(editor, "東京にいく", 4))
        assertEquals(4, editor.cursor)
        assertEquals(4, state.cursor)

        // Move 2: suffix "いく" (len 2) -> cursor at 3 ("東京に|いく")
        assertTrue(state.write(editor, "東京にいく", 3))
        assertEquals(3, editor.cursor)
        assertEquals(3, state.cursor)

        // Move 3: suffix "にいく" (len 3) -> cursor at 2 ("東京|にいく")
        assertTrue(state.write(editor, "東京にいく", 2))
        assertEquals(2, editor.cursor)
        assertEquals(2, state.cursor)
    }

    @Test fun moveByCodePointRecoversFromOutOfBoundsAndMidSurrogateCursor() {
        val text = "a\uD842\uDFB7b" // "a𠮷b", length = 4, 𠮷 at 1..3
        // Mid-surrogate offset 2 must recover cleanly without throwing IllegalArgumentException.
        assertEquals(1, ComposingEditorState.moveByCodePoint(text, 2, -1))
        assertEquals(3, ComposingEditorState.moveByCodePoint(text, 2, 1))
        // Out-of-bounds cursors clamp safely before moving.
        assertEquals(0, ComposingEditorState.moveByCodePoint(text, -5, -1))
        assertEquals(1, ComposingEditorState.moveByCodePoint(text, -5, 1))
        assertEquals(3, ComposingEditorState.moveByCodePoint(text, 99, -1))
        assertEquals(4, ComposingEditorState.moveByCodePoint(text, 99, 1))
        assertEquals(1, ComposingEditorState.snapBoundary(text, 2))
        assertEquals(3, ComposingEditorState.snapBoundary(text, 2, preferForward = true))
    }

    @Test fun adoptCursorSnapsMidSurrogateAndExposesPreviewCursorAndStartHint() {
        val state = ComposingEditorState()
        val text = "a\uD842\uDFB7b"
        state.recordPreview(text)
        state.observeRange(10, 14)
        assertEquals(10, state.composingStartHint)
        assertTrue(state.adoptCursor(2))
        assertEquals(1, state.cursor)
        assertEquals(ComposingEditorState.PreviewCursor(text, 1), state.previewCursor)
    }

    @Test fun composingTextArrowMoveAndPrefixCompleteNeverSplitSurrogates() {
        val input = ComposingText().apply { insertAtCursorPosition("a\uD842\uDFB7b", InputStyle.DIRECT) }
        assertEquals(4, input.convertTargetCursorPosition)
        assertEquals(-1, input.moveCursorFromCursorPosition(-1))
        assertEquals(3, input.convertTargetCursorPosition)
        // Moving left by 1 UTF-16 unit from right of 𠮷 steps across the entire surrogate pair.
        assertEquals(-2, input.moveCursorFromCursorPosition(-1))
        assertEquals(1, input.convertTargetCursorPosition)
        // Moving right by 1 UTF-16 unit from left of 𠮷 steps forward across 𠮷.
        assertEquals(2, input.moveCursorFromCursorPosition(1))
        assertEquals(3, input.convertTargetCursorPosition)

        // A prefix length splitting the surrogate pair snaps to the whole preceding character.
        input.prefixComplete(2)
        assertEquals("\uD842\uDFB7b", input.convertTarget)
        assertTrue(ComposingEditorState.isBoundary(input.convertTarget, input.convertTargetCursorPosition))
    }

    @Test fun conversionRangeSessionHandlesSurrogatesSafely() {
        val input = ComposingText().apply { insertAtCursorPosition("a\uD842\uDFB7b", InputStyle.DIRECT) }
        val range = ConversionRangeSession()
        assertTrue(range.begin(input, "a\uD842\uDFB7b"))
        assertEquals(3, input.convertTargetCursorPosition)
        assertTrue(range.adjust(input, -1))
        assertEquals(1, input.convertTargetCursorPosition)
        assertFalse(range.canAdjust(input, -1))
        assertTrue(range.adjust(input, 1))
        assertEquals(3, input.convertTargetCursorPosition)
    }
}
