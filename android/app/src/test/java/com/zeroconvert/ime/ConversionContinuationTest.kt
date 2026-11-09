package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversionContinuationTest {

    @Test
    fun kanaReadingAndSurfaceAreAccepted() {
        assertEquals(
            ConversionContinuation("きょうは", "今日は"),
            ConversionContinuation.create("きょうは", "今日は"),
        )
    }

    @Test
    fun longVowelAndIterationMarksAreAccepted() {
        assertEquals("ラーメン", ConversionContinuation.create("らーめん", "ラーメン")?.surface)
        assertEquals("いすゞ", ConversionContinuation.create("いすゞ", "いすゞ")?.surface)
        assertEquals("すゝめ", ConversionContinuation.create("すゝめ", "すゝめ")?.surface)
    }

    @Test
    fun emptyReadingIsRejected() {
        assertNull(ConversionContinuation.create("", "今日"))
    }

    @Test
    fun emptySurfaceIsRejected() {
        assertNull(ConversionContinuation.create("きょう", ""))
    }

    @Test
    fun katakanaReadingIsRejected() {
        assertNull(ConversionContinuation.create("キョウハ", "今日は"))
    }

    @Test
    fun latinAndDigitReadingAreRejected() {
        assertNull(ConversionContinuation.create("kyouha", "今日は"))
        assertNull(ConversionContinuation.create("123", "123"))
    }

    @Test
    fun readingAtCapIsAcceptedAndOverCapIsRejected() {
        val atCap = "あ".repeat(ConversionContinuation.MAX_READING_CHARS)
        assertEquals(atCap, ConversionContinuation.create(atCap, "あ")?.reading)

        val overCap = "あ".repeat(ConversionContinuation.MAX_READING_CHARS + 1)
        assertNull(ConversionContinuation.create(overCap, "あ"))
    }

    @Test
    fun surfaceAtCapIsAcceptedAndOverCapIsRejected() {
        val atCap = "あ".repeat(ConversionContinuation.MAX_SURFACE_CHARS)
        assertEquals(atCap, ConversionContinuation.create("あ", atCap)?.surface)

        val overCap = "あ".repeat(ConversionContinuation.MAX_SURFACE_CHARS + 1)
        assertNull(ConversionContinuation.create("あ", overCap))
    }

    @Test
    fun surfaceCapCountsCodePointsNotUtf16Units() {
        val atCap = "😀".repeat(ConversionContinuation.MAX_SURFACE_CHARS)
        assertEquals(atCap, ConversionContinuation.create("あ", atCap)?.surface)

        val overCap = "😀".repeat(ConversionContinuation.MAX_SURFACE_CHARS + 1)
        assertNull(ConversionContinuation.create("あ", overCap))
    }

    @Test
    fun deriveSplitsTheRemainingReadingFromTheCommittedPrefix() {
        assertEquals(
            ConversionContinuation("ついかじょうほうをえ", "追加情報を得"),
            ConversionContinuation.derive("ついかじょうほうをえたり", "たり", "追加情報を得"),
        )
    }

    @Test
    fun deriveRejectsRemainingReadingThatIsNotASuffix() {
        assertNull(ConversionContinuation.derive("ついか", "かり", "追加"))
        assertNull(ConversionContinuation.derive("ついか", "ついかあ", "追加"))
    }

    @Test
    fun deriveRejectsEmptyRemainingReading() {
        assertNull(ConversionContinuation.derive("ついか", "", "追加"))
    }

    @Test
    fun deriveRejectsEmptyCommittedSurface() {
        assertNull(ConversionContinuation.derive("ついか", "か", ""))
    }

    @Test
    fun deriveRejectsNonKanaDerivedReading() {
        assertNull(ConversionContinuation.derive("abcたり", "たり", "追加"))
    }

    @Test
    fun deriveRejectsDerivedReadingOverCap() {
        val atCap = "あ".repeat(ConversionContinuation.MAX_READING_CHARS)
        assertEquals(atCap, ConversionContinuation.derive(atCap + "たり", "たり", "追加")?.reading)

        val overCap = "あ".repeat(ConversionContinuation.MAX_READING_CHARS + 1)
        assertNull(ConversionContinuation.derive(overCap + "たり", "たり", "追加"))
    }
}
