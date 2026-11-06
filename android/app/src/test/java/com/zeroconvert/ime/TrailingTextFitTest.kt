package com.zeroconvert.ime

import org.junit.Assert.*
import org.junit.Test

class TrailingTextFitTest {
    /** Stands in for text measurement: one unit per UTF-16 char, so width == length. */
    private fun fit(text: String, width: Int) = TrailingTextFit.fit(text) { it.length <= width }

    @Test fun textThatFitsIsUnchanged() {
        assertEquals("あいうえお", fit("あいうえお", 5))
        assertEquals("あいうえお", fit("あいうえお", 40))
        assertEquals("", fit("", 0))
    }

    @Test fun overflowKeepsTheNewestCharactersAndMarksTheCut() {
        assertEquals("…えお", fit("あいうえお", 3))
        assertEquals("…お", fit("あいうえお", 2))
    }

    @Test fun theLongestFittingSuffixIsChosen() {
        val reading = "わたしはがっこうにいってべんきょうしています"
        for (width in 2 until reading.length) {
            val fitted = fit(reading, width)
            assertTrue("width $width produced $fitted", fitted.length <= width)
            assertTrue(fitted.startsWith(TrailingTextFit.ELLIPSIS))
            assertEquals(reading.takeLast(fitted.length - 1), fitted.drop(1))
            // One unit per character: the marker plus the suffix must fill the width exactly.
            assertEquals(width, fitted.length)
        }
    }

    @Test fun aGraphemeIsNeverSplit() {
        val reading = "あ😀い👨‍👩‍👧‍👦う"
        val fitted = TrailingTextFit.fit(reading) { it.length <= 4 }
        assertEquals("…う", fitted)
        assertFalse(fitted.contains("\uD83D"))
    }

    @Test fun aWidthTooSmallForAnyCharacterStillReturnsTheMarker() {
        assertEquals(TrailingTextFit.ELLIPSIS, fit("あいうえお", 0))
    }
}
