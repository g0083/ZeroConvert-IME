package com.zeroconvert.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeSelectionTest {
    @Test fun offersTheSameSixThemesAsTheWindowsSettings() {
        assertEquals(
            listOf("dark_blue", "graphite", "forest", "plum", "sakura", "paper"),
            ZeroConThemes.map { it.id }
        )
        assertEquals(
            listOf("ダークブルー", "グラファイト", "フォレスト", "プラム", "サクラ", "ペーパー"),
            ZeroConThemes.map { it.label }
        )
        assertEquals(listOf(true, true, true, true, false, false), ZeroConThemes.map { it.isDark })
    }

    @Test fun systemModeFollowsTheDeviceDarkMode() {
        assertEquals("dark_blue", resolveZeroConThemeId(ZERO_CON_THEME_SYSTEM, systemDark = true))
        assertEquals("paper", resolveZeroConThemeId(ZERO_CON_THEME_SYSTEM, systemDark = false))
    }

    @Test fun explicitThemeIgnoresTheDeviceDarkMode() {
        assertEquals("plum", resolveZeroConThemeId("plum", systemDark = false))
        assertEquals("sakura", resolveZeroConThemeId("sakura", systemDark = true))
        assertFalse(resolveZeroConTheme("sakura", systemDark = true).isDark)
    }

    @Test fun legacyDarkAndLightValuesStillResolve() {
        assertEquals("dark_blue", resolveZeroConThemeId("dark", systemDark = false))
        assertEquals("paper", resolveZeroConThemeId("light", systemDark = true))
    }

    @Test fun unknownValuesFallBackToTheDefaultDarkTheme() {
        assertEquals("dark_blue", resolveZeroConThemeId("midnight", systemDark = false))
        assertNull(zeroConThemeById("midnight"))
        assertNotNull(zeroConThemeById("graphite"))
    }

    @Test fun unsetValueBehavesLikeSystemMode() {
        assertEquals("paper", resolveZeroConThemeId("", systemDark = false))
        assertEquals("dark_blue", resolveZeroConThemeId("", systemDark = true))
    }

    @Test fun everyThemeKeepsReadableContrastAgainstItsOwnBackground() {
        ZeroConThemes.forEach { theme ->
            val palette = theme.palette
            assertTrue("${theme.id} text", contrast(palette.textPrimary, palette.canvas) >= 4.5)
            assertTrue("${theme.id} secondary", contrast(palette.textSecondary, palette.canvas) >= 3.0)
            assertTrue("${theme.id} accent", contrast(palette.primaryAccent, palette.canvas) >= 3.0)
            assertTrue("${theme.id} onAccent", contrast(palette.onAccent, palette.primaryAccent) >= 4.5)
            assertTrue("${theme.id} key", contrast(palette.textPrimary, palette.keyNormal) >= 4.5)
        }
    }

    @Test fun darkAndLightThemesStayOnTheirSideOfTheLuminanceScale() {
        ZeroConThemes.forEach { theme ->
            val canvas = luminance(theme.palette.canvas)
            if (theme.isDark) assertTrue("${theme.id} must be dark", canvas < 0.2)
            else assertTrue("${theme.id} must be light", canvas > 0.7)
        }
    }

    private fun channel(value: Float): Double {
        val v = value.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(color: androidx.compose.ui.graphics.Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
