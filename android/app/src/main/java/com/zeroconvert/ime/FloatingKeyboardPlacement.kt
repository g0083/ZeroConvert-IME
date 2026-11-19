package com.zeroconvert.ime

import kotlin.math.roundToInt

internal data class FloatingKeyboardPlacement(
    val horizontal: Float = 0.5f,
    val vertical: Float = 0.75f,
    val widthDp: Float = DEFAULT_WIDTH_DP,
    val heightDp: Float = 0f
) {
    companion object {
        const val DEFAULT_WIDTH_DP = 320f
        const val MIN_WIDTH_DP = 260f
        const val MIN_HEIGHT_DP = 236f
    }

    fun widthPx(density: Float, available: Int): Int =
        (widthDp.coerceAtLeast(MIN_WIDTH_DP) * density).roundToInt().coerceAtMost(available)

    fun heightPx(density: Float, available: Int, defaultHeightDp: Float): Int =
        ((heightDp.takeIf { it > 0f } ?: defaultHeightDp).coerceAtLeast(MIN_HEIGHT_DP) * density)
            .roundToInt().coerceAtMost(available)

    fun left(min: Int, max: Int): Int = min + ((max - min) * horizontal).roundToInt()

    fun top(min: Int, max: Int): Int = min + ((max - min) * vertical).roundToInt()

    fun placedAt(left: Int, top: Int, minX: Int, maxX: Int, minY: Int, maxY: Int) = copy(
        horizontal = fraction(left, minX, maxX, horizontal),
        vertical = fraction(top, minY, maxY, vertical)
    )

    private fun fraction(position: Int, min: Int, max: Int, fallback: Float): Float =
        if (max > min) (position - min).toFloat().div(max - min).coerceIn(0f, 1f) else fallback
}

internal enum class FloatingKeyboardCorner(val left: Boolean, val top: Boolean) {
    TOP_LEFT(true, true),
    TOP_RIGHT(false, true),
    BOTTOM_LEFT(true, false),
    BOTTOM_RIGHT(false, false)
}

internal fun resizeFloatingKeyboardAxis(
    initial: IntRange,
    viewport: IntRange,
    delta: Int,
    minimumSize: Int,
    movesStart: Boolean
): IntRange {
    return if (movesStart) {
        val anchor = initial.last
        val maxStart = (anchor - minimumSize).coerceAtLeast(viewport.first)
        (initial.first + delta).coerceIn(viewport.first, maxStart)..anchor
    } else {
        val anchor = initial.first
        val minEnd = (anchor + minimumSize).coerceAtMost(viewport.last)
        anchor..(initial.last + delta).coerceIn(minEnd, viewport.last)
    }
}

internal fun shouldDockFloatingKeyboard(
    downwardDrag: Float,
    keyboardBottom: Float,
    viewportBottom: Int,
    snapDistance: Int
): Boolean = downwardDrag > 0f && keyboardBottom >= viewportBottom - snapDistance
