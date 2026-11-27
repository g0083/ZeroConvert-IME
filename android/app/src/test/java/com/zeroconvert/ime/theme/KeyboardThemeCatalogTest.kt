package com.zeroconvert.ime.theme

import com.zeroconvert.ime.ZERO_CON_THEME_SYSTEM
import com.zeroconvert.ime.ZeroConThemes
import com.zeroconvert.ime.resolve12KeyEnterCornerRadiusDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class KeyboardThemeCatalogTest {

    @Test fun catalogContainsSixteenDistinctPresetsAcrossAllBuiltInCategories() {
        val presets = KeyboardThemeCatalog.presets
        assertEquals(16, presets.size)
        assertEquals(16, presets.map { it.id }.distinct().size)
        assertTrue(presets.all { it.isBuiltIn && it.name.isNotBlank() })

        val categories = presets.map { it.category }.toSet()
        assertEquals(
            setOf(
                ThemeCategory.STANDARD,
                ThemeCategory.OS_STYLE,
                ThemeCategory.GRADIENT_GLASS,
                ThemeCategory.POP_NEON
            ),
            categories
        )
    }

    @Test fun standardSixThemesMirrorZeroConThemesParity() {
        val standard = KeyboardThemeCatalog.presets.filter { it.category == ThemeCategory.STANDARD }
        assertEquals(ZeroConThemes.map { it.id }, standard.map { it.id })
        assertEquals(ZeroConThemes.map { it.label }, standard.map { it.name })
        assertEquals(ZeroConThemes.map { it.isDark }, standard.map { it.isDark })
        standard.forEach { spec ->
            assertEquals(KeyStylePreset.BEVEL_3D, spec.keySurface.preset)
            assertEquals(BackgroundKind.SOLID, spec.background.kind)
        }
    }

    @Test fun resolvesSystemLegacyAndNewPresetIds() {
        assertEquals("graphite", KeyboardThemeCatalog.resolveId(ZERO_CON_THEME_SYSTEM, systemDark = true))
        assertEquals("paper", KeyboardThemeCatalog.resolveId(ZERO_CON_THEME_SYSTEM, systemDark = false))
        assertEquals("graphite", KeyboardThemeCatalog.resolveId("", systemDark = true))
        assertEquals("paper", KeyboardThemeCatalog.resolveId("", systemDark = false))
        assertEquals("graphite", KeyboardThemeCatalog.resolveId("dark", systemDark = false))
        assertEquals("paper", KeyboardThemeCatalog.resolveId("light", systemDark = true))
        assertEquals("ios_light", KeyboardThemeCatalog.resolveId("ios_light", systemDark = true))
        assertEquals("aurora_glass", KeyboardThemeCatalog.resolveId("aurora_glass", systemDark = false))
        assertEquals("cyber_neon", KeyboardThemeCatalog.resolveId("cyber_neon", systemDark = false))
        assertEquals("graphite", KeyboardThemeCatalog.resolveId("nonexistent_theme", systemDark = false))
        assertNull(KeyboardThemeCatalog.findById("nonexistent_theme"))
        assertNotNull(KeyboardThemeCatalog.findById("ios_dark"))
    }

    @Test fun everyPresetMaintainsAccessibleContrastOnKeysAndAccent() {
        KeyboardThemeCatalog.presets.forEach { spec ->
            val bg = representativeBackground(spec.background)
            val effectiveKeyBg = compositeOver(
                fg = spec.keySurface.normalKeyFill,
                alpha = spec.keySurface.keyFillAlpha,
                bg = bg
            )
            val keyContrast = contrastRatio(spec.typography.primaryTextColor, effectiveKeyBg)
            assertTrue(
                "${spec.id} key text contrast was $keyContrast (< 4.5)",
                keyContrast >= 4.5
            )

            val accentContrast = contrastRatio(spec.typography.accentTextColor, spec.keySurface.accentKeyFill)
            assertTrue(
                "${spec.id} accent text contrast was $accentContrast (< 4.5)",
                accentContrast >= 4.5
            )
        }
    }

    @Test fun toZeroConPaletteProducesValidOpaqueColorsForAllPresets() {
        KeyboardThemeCatalog.presets.forEach { spec ->
            val palette = spec.toZeroConPalette()
            assertEquals(spec.isDark, palette.isDark)
            assertFalse(palette.textPrimary.alpha == 0f)
            assertFalse(palette.primaryAccent.alpha == 0f)
            assertFalse(palette.onAccent.alpha == 0f)
        }
    }

    @Test fun preferenceMigrationFallsBackToLegacyThemeModeOnlyWhenPrimaryIsUnset() {
        assertEquals("ios_dark", ThemePreferences.resolveMigratedThemeValue("ios_dark", "sakura"))
        assertEquals("sakura", ThemePreferences.resolveMigratedThemeValue(null, "sakura"))
        assertEquals("paper", ThemePreferences.resolveMigratedThemeValue("", "paper"))
        assertEquals(ZERO_CON_THEME_SYSTEM, ThemePreferences.resolveMigratedThemeValue(null, null))
        assertEquals(ZERO_CON_THEME_SYSTEM, ThemePreferences.resolveMigratedThemeValue("  ", " "))
    }

    @Test fun twelveKeyEnterCornerRadiusCapsPillValuesWithoutAlteringCompactOnes() {
        assertEquals(12f, resolve12KeyEnterCornerRadiusDp(22f), 0.001f)
        assertEquals(12f, resolve12KeyEnterCornerRadiusDp(12f), 0.001f)
        assertEquals(8f, resolve12KeyEnterCornerRadiusDp(8f), 0.001f)
        assertEquals(5f, resolve12KeyEnterCornerRadiusDp(5f), 0.001f)
    }

    private fun representativeBackground(bg: BackgroundStyle): Long {
        if (bg.kind != BackgroundKind.LINEAR_GRADIENT) return bg.solidColor
        val s = bg.gradientStartColor
        val e = bg.gradientEndColor
        val r = (((s shr 16) and 0xFF) + ((e shr 16) and 0xFF)) / 2
        val g = (((s shr 8) and 0xFF) + ((e shr 8) and 0xFF)) / 2
        val b = ((s and 0xFF) + (e and 0xFF)) / 2
        return (0xFFL shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun compositeOver(fg: Long, alpha: Float, bg: Long): Long {
        val a = alpha.coerceIn(0f, 1f)
        val fr = ((fg shr 16) and 0xFF).toFloat()
        val fgG = ((fg shr 8) and 0xFF).toFloat()
        val fb = (fg and 0xFF).toFloat()
        val br = ((bg shr 16) and 0xFF).toFloat()
        val bgG = ((bg shr 8) and 0xFF).toFloat()
        val bb = (bg and 0xFF).toFloat()
        val r = (fr * a + br * (1f - a)).toLong().coerceIn(0, 255)
        val g = (fgG * a + bgG * (1f - a)).toLong().coerceIn(0, 255)
        val b = (fb * a + bb * (1f - a)).toLong().coerceIn(0, 255)
        return (0xFFL shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun channel(byteVal: Long): Double {
        val v = byteVal.toDouble() / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(argb: Long): Double =
        0.2126 * channel((argb shr 16) and 0xFF) +
            0.7152 * channel((argb shr 8) and 0xFF) +
            0.0722 * channel(argb and 0xFF)

    private fun contrastRatio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
