package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlickDirectionHintTest {

    @Test
    fun testJapaneseKeyHintsFollowAssignedDirections() {
        val hints = flickDirectionHints(FlickKeyDefinitions.keyKa)
        assertEquals("く", hints.up)
        assertEquals("こ", hints.down)
        assertEquals("き", hints.left)
        assertEquals("け", hints.right)
        assertFalse(hints.isEmpty)
    }

    @Test
    fun testModifierKeyHintsMatchFlickDirections() {
        val hints = flickDirectionHints(FlickKeyDefinitions.keyMod)
        assertEquals("小", hints.up)
        assertEquals("", hints.down)
        assertEquals("゛", hints.left)
        assertEquals("゜", hints.right)
    }

    @Test
    fun testUnassignedDirectionStaysEmpty() {
        val hints = flickDirectionHints(FlickEnglishKeyDefinitions.getKey2(false))
        assertEquals("c", hints.up)
        assertEquals("b", hints.left)
        assertEquals("2", hints.right)
        assertEquals("", hints.down)
    }

    @Test
    fun testKeyWithoutDirectionsHasNoHints() {
        val hints = flickDirectionHints(FlickKeyDef(label = "、", center = "、", subText = "。・？・！"))
        assertTrue(hints.isEmpty)
    }

    @Test
    fun testHintTextIsTrimmed() {
        val hints = flickDirectionHints(FlickKeyDef(label = "x", center = "x", up = " ", left = " a "))
        assertEquals("", hints.up)
        assertEquals("a", hints.left)
    }

    @Test
    fun testLabelEmWidthCountsAsciiNarrower() {
        assertEquals(1.0f, flickLabelEmWidth("あ"), 0.001f)
        assertEquals(0.6f, flickLabelEmWidth("a"), 0.001f)
        assertEquals(3.0f, flickLabelEmWidth("小゛゜"), 0.001f)
    }

    @Test
    fun testFittedLabelKeepsRoomForSideHints() {
        // A wide phone key keeps a single-character label at its normal size.
        assertEquals(
            19.0f,
            fittedFlickLabelSizeSp("あ", 68.6f, 8.5f, true, true, 19f),
            0.01f
        )
        // Narrow floating keys shrink the label instead of running under the hints.
        assertEquals(
            15.5f,
            fittedFlickLabelSizeSp("、。", 60f, 8.5f, true, true, 19f),
            0.01f
        )
        assertEquals(
            9.0f,
            fittedFlickLabelSizeSp("小゛゜", 48f, 8.5f, true, true, 19f),
            0.01f
        )
        // Without side hints the label only keeps its normal size cap.
        assertEquals(
            19.0f,
            fittedFlickLabelSizeSp("、。", 68.6f, 8.5f, false, false, 19f),
            0.01f
        )
    }
}
