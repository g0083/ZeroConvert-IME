package com.zeroconvert.ime

import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeUndoHistoryTest {

    @Test
    fun testPushAndPopLifoOrder() {
        val history = ImeUndoHistory()
        val composing = ComposingText().apply {
            insertAtCursorPosition("か", InputStyle.DIRECT)
        }
        history.push(ImeUndoEntry.JapaneseComposing(composing.clone(), emptyList(), ""))
        history.push(ImeUndoEntry.CommittedText(committedText = "1"))
        history.push(ImeUndoEntry.DeletedText(deletedText = "あ"))

        assertEquals(3, history.size)
        assertEquals(ImeUndoEntry.DeletedText("あ"), history.pop())
        assertEquals(ImeUndoEntry.CommittedText("1"), history.pop())
        val poppedComposing = history.pop() as ImeUndoEntry.JapaneseComposing
        assertEquals("か", poppedComposing.previousComposing.convertTarget)
        assertTrue(history.isEmpty())
        assertNull(history.pop())
    }

    @Test
    fun testCapacityEvictsOldestEntries() {
        val history = ImeUndoHistory(maxEntries = 3)
        history.push(ImeUndoEntry.CommittedText("a"))
        history.push(ImeUndoEntry.CommittedText("b"))
        history.push(ImeUndoEntry.CommittedText("c"))
        history.push(ImeUndoEntry.CommittedText("d"))

        assertEquals(3, history.size)
        assertEquals(ImeUndoEntry.CommittedText("d"), history.pop())
        assertEquals(ImeUndoEntry.CommittedText("c"), history.pop())
        assertEquals(ImeUndoEntry.CommittedText("b"), history.pop())
        assertNull(history.pop())
    }

    @Test
    fun testUpdateLatestCommittedSuffixForToggleCycle() {
        val history = ImeUndoHistory()
        history.push(ImeUndoEntry.CommittedText("a"))
        assertTrue(history.updateLatestCommittedSuffix(oldLength = 1, newText = "b"))
        assertTrue(history.updateLatestCommittedSuffix(oldLength = 1, newText = "c"))
        assertEquals(ImeUndoEntry.CommittedText("c"), history.pop())

        history.push(ImeUndoEntry.DeletedText("x"))
        assertFalse(history.updateLatestCommittedSuffix(oldLength = 1, newText = "y"))
        assertEquals(ImeUndoEntry.DeletedText("x"), history.pop())
    }

    @Test
    fun testClearEmptiesStack() {
        val history = ImeUndoHistory()
        history.push(ImeUndoEntry.EnglishComposing("hel", emptyList()))
        history.push(ImeUndoEntry.ReplacedCommittedText("a", "A"))
        history.clear()
        assertTrue(history.isEmpty())
        assertNull(history.pop())
    }
}
