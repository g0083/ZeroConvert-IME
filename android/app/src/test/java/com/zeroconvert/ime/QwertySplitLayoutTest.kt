package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwertySplitLayoutTest {
    @Test fun splitRequiresWideKeyboard() {
        assertFalse(QwertySplitLayout.supportsWidth(599f))
        assertTrue(QwertySplitLayout.supportsWidth(600f))
    }

    @Test fun sliderControlsTheGapAcrossScreenWidths() {
        assertEquals(30f, QwertySplitLayout.gapDp(600f, QwertySplitLayout.MIN_GAP_PERCENT), 0.01f)
        assertEquals(90f, QwertySplitLayout.gapDp(600f, QwertySplitLayout.DEFAULT_GAP_PERCENT), 0.01f)
        assertEquals(480f, QwertySplitLayout.gapDp(1200f, QwertySplitLayout.MAX_GAP_PERCENT), 0.01f)
        assertEquals(QwertySplitLayout.MIN_GAP_PERCENT, QwertySplitLayout.clampGapPercent(0))
        assertEquals(QwertySplitLayout.MAX_GAP_PERCENT, QwertySplitLayout.clampGapPercent(100))
    }

    @Test fun originalRowsShareOneWidthAfterRemovingTheCut() {
        assertEquals(
            QwertySplitLayout.Spacing(cutWidthPx = 90, keyAreaWidthPx = 510),
            QwertySplitLayout.spacing(rowWidthPx = 600, gapPx = 90, duplicateMiddle = false)
        )
    }

    @Test fun duplicatesFitBesideTheRequestedGapWithoutMovingIndividualKeys() {
        val gapPx = 30
        val spacing = QwertySplitLayout.spacing(rowWidthPx = 600, gapPx = gapPx, duplicateMiddle = true)
        val gWidth = spacing.keyWidthPx(QwertySplitLayout.HOME_ROW_WEIGHT)
        val vWidth = spacing.keyWidthPx(QwertySplitLayout.BOTTOM_LETTER_ROW_WEIGHT)
        assertEquals(600, spacing.cutWidthPx + spacing.keyAreaWidthPx)
        assertTrue(gWidth > 0)
        assertTrue(vWidth >= gWidth)
        assertTrue(spacing.cutWidthPx - vWidth >= gapPx)
    }

    @Test fun theOriginalMiddleKeyStaysOnTheLeft() {
        assertEquals(5, QwertySplitLayout.cutAfter("qwertyuiop".length))
        assertEquals(5, QwertySplitLayout.cutAfter("asdfghjkl".length))
        assertEquals(4, QwertySplitLayout.cutAfter("zxcvbnm".length))
    }
}
