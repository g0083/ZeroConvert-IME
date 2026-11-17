package com.zeroconvert.ime

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedCorrectionFormatTest {

    private fun fitByLength(source: String, replacement: String, maxChars: Int): FocusedCorrectionParts =
        FocusedCorrectionFormat.fit(source, replacement) { it.text.length <= maxChars }

    @Test
    fun shortTypoFitsWithFullDiffAndArrow() {
        val parts = fitByLength("おkせり", "おかえり", maxChars = 24)
        assertEquals("おkせり → おかえり", parts.text)
        assertEquals("かえ", parts.changed)
    }

    @Test
    fun longPrefixTypoFocusesOnCorrectionAndNeverCutsFromCharZero() {
        val source = "明日の午後の会議の資料をおkせりします"
        val replacement = "明日の午後の会議の資料をおかえりします"

        val parts = fitByLength(source, replacement, maxChars = 23)
        assertEquals("…料をおkせりします → …料をおかえりします", parts.text)
        assertEquals("かえ", parts.changed)
        assertFalse(parts.text.contains("明日の午後"))

        // Compact phone width (~12 chars): keeps focused 誤→正 around the typo.
        val compact = fitByLength(source, replacement, maxChars = 13)
        assertEquals("…おkせり…→…おかえり…", compact.text)
        assertTrue(compact.changed == "かえ")
    }

    @Test
    fun earlierKanjiVarianceDoesNotBloatCorrectionWindow() {
        val source = "昨日のうち合わせでおkせり"
        val replacement = "昨日の打合せでおかえり"
        val parts = fitByLength(source, replacement, maxChars = 20)

        assertEquals("かえ", parts.changed)
        assertFalse(parts.text.contains("昨日"))
        assertTrue(parts.text.contains("kせ") && parts.text.contains("かえ"))
    }

    @Test
    fun pureKanaSourceFallbackFocusesOnTrailingTypoViaLcs() {
        val source = "きょうはお天気なのでさんぽにしよお"
        val replacement = "今日はお天気なので散歩にしよう"
        val parts = fitByLength(source, replacement, maxChars = 16)

        assertEquals("う", parts.changed)
        assertEquals("…にしよお → …にしよう", parts.text)

        val tight = fitByLength(source, replacement, maxChars = 10)
        assertEquals("う", tight.changed)
        assertEquals("…よお → …よう", tight.text)

        val compact = fitByLength(source, replacement, maxChars = 7)
        assertEquals("う", compact.changed)
        assertEquals("…よお→…よう", compact.text)
    }

    @Test
    fun veryNarrowWidthBesideUndoPrioritizesCorrectedPartOverOriginal() {
        val source = "明日の午後の会議の資料をおkせりします"
        val replacement = "明日の午後の会議の資料をおかえりします"

        // Tight 6-char width keeps compact 誤→正 without noisy outer dots.
        val parts = fitByLength(source, replacement, maxChars = 6)
        assertTrue(parts.text.length <= 6)
        assertEquals("kせ→かえ", parts.text)
        assertEquals("かえ", parts.changed)

        // Ultra-narrow 4-char width (e.g. beside "戻す"): drops original to keep the correction readable.
        val ultraNarrow = fitByLength(source, replacement, maxChars = 4)
        assertTrue(ultraNarrow.text.length <= 4)
        assertTrue(ultraNarrow.text.contains("かえ"))
        assertTrue(ultraNarrow.changed.contains("かえ"))
    }

    @Test
    fun oversizedDiffHeadsTrimCleanlyKeepingCorrectedTailVisible() {
        val source = "abcdefghijklmnopqrstuv"
        val replacement = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

        val parts = fitByLength(source, replacement, maxChars = 14)
        assertTrue(parts.text.length <= 14)
        assertTrue(parts.text.contains("→"))
        assertTrue(parts.text.endsWith("XYZ"))
    }

    @Test
    fun identicalSourceAndReplacementDegradeGracefully() {
        val text = "明日の会議は十時からです"
        val parts = fitByLength(text, text, maxChars = 8)
        assertTrue(parts.text.length <= 8)
        assertTrue(parts.text.endsWith("からです"))
        assertTrue(parts.text.startsWith("…"))
    }

    @Test
    fun annotatedStringPreservesFullPlainTextAndHighlightSpan() {
        val parts = fitByLength("おkせり", "おかえり", maxChars = 20)
        val annotated = parts.toAnnotatedString(
            originalColor = Color.Gray,
            targetColor = Color.White,
            highlightBackground = Color.Yellow
        )
        assertEquals(parts.text, annotated.text)
        val highlightStart = annotated.text.indexOf("かえ")
        assertTrue(annotated.spanStyles.any {
            it.start == highlightStart &&
                it.end == highlightStart + 2 &&
                it.item.background == Color.Yellow
        })
    }

    @Test
    fun surrogatePairGraphemesStayIntactWithoutSplitting() {
        val source = "𠮟られるおkせり"
        val replacement = "𠮟られるおかえり"
        val parts = fitByLength(source, replacement, maxChars = 12)
        assertTrue(parts.text.contains("かえ"))
        // Ensure no lone UTF-16 surrogates remain after trimming.
        assertFalse(parts.text.any { Character.isSurrogate(it) && !isValidSurrogatePosition(parts.text, it) })
    }

    private fun isValidSurrogatePosition(text: String, ch: Char): Boolean {
        val idx = text.indexOf(ch)
        return idx >= 0 && Character.isSurrogatePair(text[idx], text.getOrElse(idx + 1) { '\u0000' })
    }
}
