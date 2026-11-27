package com.zeroconvert.ime.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeBackgroundProcessorTest {

    @Test
    fun computeCropRect_respectsAspectRatioScaleAndBounds() {
        val centered = ThemeBackgroundProcessor.computeCropRect(
            srcWidth = 1600,
            srcHeight = 1200,
            targetAspectRatio = 2.0f,
            cropCenterX = 0.5f,
            cropCenterY = 0.5f,
            cropScale = 1.0f
        )
        assertEquals(1600, centered.width)
        assertEquals(800, centered.height)
        assertEquals(0, centered.left)
        assertEquals(200, centered.top)

        val zoomed = ThemeBackgroundProcessor.computeCropRect(
            srcWidth = 1600,
            srcHeight = 1200,
            targetAspectRatio = 2.0f,
            cropCenterX = 1.0f,
            cropCenterY = 1.0f,
            cropScale = 2.0f
        )
        assertEquals(800, zoomed.width)
        assertEquals(400, zoomed.height)
        assertTrue(zoomed.left >= 0 && zoomed.left + zoomed.width <= 1600)
        assertTrue(zoomed.top >= 0 && zoomed.top + zoomed.height <= 1200)
        assertEquals(800, zoomed.left)
        assertEquals(800, zoomed.top)
    }

    @Test
    fun computeCenterCropSourceRect_centersOnBothOrientations() {
        val cropWideTarget = ThemeBackgroundProcessor.computeCenterCropSourceRect(
            imgWidth = 1000,
            imgHeight = 1000,
            dstWidth = 400,
            dstHeight = 200
        )
        assertEquals(1000, cropWideTarget.width)
        assertEquals(500, cropWideTarget.height)
        assertEquals(0, cropWideTarget.left)
        assertEquals(250, cropWideTarget.top)

        val cropTallTarget = ThemeBackgroundProcessor.computeCenterCropSourceRect(
            imgWidth = 1000,
            imgHeight = 500,
            dstWidth = 200,
            dstHeight = 400
        )
        assertEquals(250, cropTallTarget.width)
        assertEquals(500, cropTallTarget.height)
        assertEquals(375, cropTallTarget.left)
        assertEquals(0, cropTallTarget.top)
    }

    @Test
    fun stackBlurPixels_smoothsSharpTransitionAndKeepsSolidColorInvariant() {
        val solid = IntArray(20 * 20) { 0xFF336699.toInt() }
        ThemeBackgroundProcessor.stackBlurPixels(solid, 20, 20, radius = 4)
        for (px in solid) {
            assertEquals(0xFF336699.toInt(), px)
        }

        val w = 24
        val h = 12
        val halfSplit = IntArray(w * h) { idx ->
            val x = idx % w
            if (x < w / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        ThemeBackgroundProcessor.stackBlurPixels(halfSplit, w, h, radius = 4)
        val midRow = (h / 2) * w
        val edgeLeftRed = (halfSplit[midRow + (w / 2) - 1] ushr 16) and 0xFF
        val edgeRightRed = (halfSplit[midRow + (w / 2)] ushr 16) and 0xFF
        assertTrue("Expected softened dark edge, got $edgeLeftRed", edgeLeftRed in 20..200)
        assertTrue("Expected softened light edge, got $edgeRightRed", edgeRightRed in 55..235)
    }

    @Test
    fun contrastChecker_computesExpectedContrastAndComposite() {
        val blackWhite = ThemeContrastChecker.contrastRatio(0xFFFFFFFFL, 0xFF000000L)
        assertEquals(21.0, blackWhite, 0.05)

        val midGray = ThemeContrastChecker.compositeOver(0xFF000000L, 0.5f, 0xFFFFFFFFL)
        val r = (midGray shr 16) and 0xFF
        assertTrue(r in 127L..128L)

        val goodTheme = KeyboardThemeCatalog.defaultDark
        assertTrue(ThemeContrastChecker.effectiveKeyContrast(goodTheme) >= 4.5)

        val lowContrastTheme = goodTheme.copy(
            keySurface = goodTheme.keySurface.copy(normalKeyFill = 0xFF808080L, keyFillAlpha = 1f),
            typography = goodTheme.typography.copy(primaryTextColor = 0xFF888888L)
        )
        assertTrue(ThemeContrastChecker.effectiveKeyContrast(lowContrastTheme) < 1.5)
    }

    @Test
    fun suggestPaletteFromPixels_adaptsDarkAndLightPhotosWithReadableText() {
        val darkPixels = IntArray(64) { 0xFF102040.toInt() }
        val darkPalette = ThemeBackgroundProcessor.suggestPaletteFromPixels(darkPixels)
        assertTrue(darkPalette.isDark)
        assertTrue(
            ThemeContrastChecker.contrastRatio(
                darkPalette.primaryTextColor,
                darkPalette.normalKeyFill
            ) >= 4.5
        )

        val lightPixels = IntArray(64) { 0xFFF2ECE4.toInt() }
        val lightPalette = ThemeBackgroundProcessor.suggestPaletteFromPixels(lightPixels)
        assertFalse(lightPalette.isDark)
        assertTrue(
            ThemeContrastChecker.contrastRatio(
                lightPalette.primaryTextColor,
                lightPalette.normalKeyFill
            ) >= 4.5
        )

        val emptyFallback = ThemeBackgroundProcessor.suggestPaletteFromPixels(IntArray(0))
        assertTrue(emptyFallback.isDark)
    }

    @Test
    fun computeInSampleSizeAndTargetDimensions_scaleSafely() {
        assertEquals(1, ThemeBackgroundProcessor.computeInSampleSize(1440, 1080, 1440))
        assertEquals(1, ThemeBackgroundProcessor.computeInSampleSize(2880, 2160, 1440))
        assertEquals(2, ThemeBackgroundProcessor.computeInSampleSize(4000, 3000, 1440))
        assertEquals(1, ThemeBackgroundProcessor.computeInSampleSize(0, -10, 1440))

        assertEquals(800 to 600, ThemeBackgroundProcessor.computeTargetDimensions(800, 600, 1440))
        assertEquals(1440 to 1080, ThemeBackgroundProcessor.computeTargetDimensions(2880, 2160, 1440))
        assertEquals(720 to 1440, ThemeBackgroundProcessor.computeTargetDimensions(1500, 3000, 1440))
        assertEquals(1 to 1, ThemeBackgroundProcessor.computeTargetDimensions(0, 0, 1440))
    }
}
