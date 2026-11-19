package com.zeroconvert.ime

import kotlin.math.roundToInt

internal object QwertySplitLayout {
    const val MIN_WIDTH_DP = 600f
    const val MIN_GAP_PERCENT = 5
    const val DEFAULT_GAP_PERCENT = 15
    const val MAX_GAP_PERCENT = 40
    const val DEFAULT_DUPLICATE_MIDDLE_KEYS = true
    const val MODIFIER_WEIGHT = 1.35f
    const val HOME_ROW_WEIGHT = 10f
    const val BOTTOM_LETTER_ROW_WEIGHT = 7f + 2 * MODIFIER_WEIGHT

    fun supportsWidth(widthDp: Float): Boolean = widthDp >= MIN_WIDTH_DP

    fun clampGapPercent(percent: Int): Int = percent.coerceIn(MIN_GAP_PERCENT, MAX_GAP_PERCENT)

    fun gapDp(widthDp: Float, percent: Int): Float = widthDp * (clampGapPercent(percent) / 100f)

    fun spacing(rowWidthPx: Int, gapPx: Int, duplicateMiddle: Boolean): Spacing {
        val spaceAfterGap = (rowWidthPx - gapPx).coerceAtLeast(0)
        // Reserve room for V, the widest duplicate. Every row gets the same cut width
        // so the right side of each row moves by the same amount.
        val keyAreaWidth = if (duplicateMiddle) {
            (spaceAfterGap * (BOTTOM_LETTER_ROW_WEIGHT / (BOTTOM_LETTER_ROW_WEIGHT + 1f))).toInt()
        } else {
            spaceAfterGap
        }
        return Spacing(rowWidthPx - keyAreaWidth, keyAreaWidth)
    }

    data class Spacing(val cutWidthPx: Int, val keyAreaWidthPx: Int) {
        fun keyWidthPx(totalRowWeight: Float): Int = (keyAreaWidthPx / totalRowWeight).roundToInt()
    }

    fun cutAfter(keyCount: Int): Int = (keyCount + 1) / 2
}
