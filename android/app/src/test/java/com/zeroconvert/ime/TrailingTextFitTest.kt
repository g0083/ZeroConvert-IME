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

    @Test fun fitWithCursorPreservesFullTextAndMapsCursorWhenFitting() {
        val text = "あいうえお"
        for (cursor in listOf(0, 2, text.length)) {
            val fitted = TrailingTextFit.fitWithCursor(text, cursor) { it.length <= 10 }
            assertEquals(text, fitted.text)
            assertEquals(cursor, fitted.cursor)
        }
        val empty = TrailingTextFit.fitWithCursor("", 3) { true }
        assertEquals("", empty.text)
        assertEquals(0, empty.cursor)
    }

    @Test fun fitWithCursorMatchesFitAtEndAndKeepsStartAtZero() {
        val text = "あいうえおかきくけこ"
        val atEnd = TrailingTextFit.fitWithCursor(text, text.length) { it.length <= 4 }
        assertEquals("…くけこ", atEnd.text)
        assertEquals(atEnd.text.length, atEnd.cursor)

        val atStart = TrailingTextFit.fitWithCursor(text, 0) { it.length <= 4 }
        assertEquals("あいう…", atStart.text)
        assertEquals(0, atStart.cursor)
    }

    @Test fun fitWithCursorKeepsMiddleCursorVisibleAndNeverSplitsGraphemes() {
        val text = "あいうえおかきくけこ"
        val mid = TrailingTextFit.fitWithCursor(text, 5) { it.length <= 5 }
        assertTrue("Expected '${mid.text}' to fit width 5", mid.text.length <= 5)
        assertTrue(mid.text.startsWith(TrailingTextFit.ELLIPSIS))
        assertTrue(mid.text.endsWith(TrailingTextFit.ELLIPSIS))
        assertTrue(mid.cursor in 1 until mid.text.length)
        assertEquals('か', mid.text[mid.cursor])

        val emojiText = "あ😀い👨‍👩‍👧‍👦う"
        val emojiStart = emojiText.indexOf("👨‍👩‍👧‍👦")
        val fittedEmoji = TrailingTextFit.fitWithCursor(emojiText, emojiStart + 1) { it.length <= 4 }
        assertFalse(fittedEmoji.text.contains("\uD83D"))
        assertTrue(fittedEmoji.cursor in 0..fittedEmoji.text.length)
    }
}
