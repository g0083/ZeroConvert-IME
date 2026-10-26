package com.zeroconvert.ime

import org.junit.Assert.*
import org.junit.Test

class SelectionCharacterCountTest {
    private fun count(text: String) = SelectionCharacterCount.fromSelection(0, text.length)!!.resolve(text)!!

    @Test fun collapsedAndUnknownSelectionsAreHidden() {
        assertNull(SelectionCharacterCount.fromSelection(3, 3))
        assertNull(SelectionCharacterCount.fromSelection(-1, 3))
        assertNull(SelectionCharacterCount.fromSelection(3, -1))
    }

    @Test fun forwardAndBackwardSelectionHaveTheSameCount() {
        assertEquals(SelectionCharacterCount.fromSelection(2, 8), SelectionCharacterCount.fromSelection(8, 2))
        assertEquals("約6文字選択", SelectionCharacterCount.fromSelection(2, 8)!!.label)
        assertEquals("1文字選択", SelectionCharacterCount.fromSelection(4, 5)!!.label)
    }

    @Test fun japaneseAndLatinTextShowAnExactCount() {
        assertEquals("6文字選択", count("日本語abc").label)
        assertTrue(count("日本語abc").exact)
    }

    @Test fun anEmojiSurrogatePairIsOneCharacter() {
        assertEquals(3, count("あ😀い").characters)
    }

    @Test fun combiningMarksAreCountedWithTheirBase() {
        assertEquals(1, count("か\u3099").characters)
        assertEquals(1, count("e\u0301").characters)
    }

    @Test fun joinedEmojiFlagsAndSkinTonesAreSingleCharacters() {
        assertEquals(1, count("👨‍👩‍👧‍👦").characters)
        assertEquals(1, count("🇯🇵").characters)
        assertEquals(1, count("👍🏽").characters)
    }

    @Test fun spacesAndLineBreaksAreIncluded() {
        assertEquals(5, count("a b\nc").characters)
    }

    @Test fun missingOrStaleSelectedTextKeepsTheApproximation() {
        val initial = SelectionCharacterCount.fromSelection(2, 8)!!
        assertEquals(initial, initial.resolve(null))
        assertEquals(initial, initial.resolve("ab"))
        assertFalse(initial.resolve(null)!!.exact)
    }

    @Test fun hugeSelectionsDoNotRequestAFullTextCount() {
        val selection = SelectionCharacterCount.fromSelection(0, SelectionCharacterCount.MAX_RESOLVED_UTF16 + 1)!!
        assertFalse(selection.canResolve)
        assertEquals(selection, selection.resolve("abc"))
    }

    @Test fun cancelledCountingCannotPublishAResult() {
        val initial = SelectionCharacterCount.fromSelection(0, 512)!!
        assertNull(initial.resolve("a".repeat(512)) { true })
        var checks = 0
        assertNull(initial.resolve("a".repeat(512)) { ++checks > 2 })
    }

    @Test fun oldRequestsAreRejectedEvenWhenOffsetsAreReselected() {
        val gate = SelectionCountGate()
        val old = gate.capture(2, 8)!!
        val current = gate.capture(2, 8)!!
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(current))
        gate.capture(8, 8)
        assertFalse(gate.isCurrent(current))
    }

    @Test fun finishingInputInvalidatesThePreviousFieldCount() {
        val gate = SelectionCountGate()
        val old = gate.capture(0, 10)!!
        gate.capture(-1, -1)
        assertFalse(gate.isCurrent(old))
        val next = gate.capture(0, 2)!!
        assertTrue(gate.isCurrent(next))
    }
}
