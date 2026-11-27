package com.zeroconvert.ime.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardThemeJsonTest {

    @Test fun roundTripPreservesAllBuiltInPresets() {
        KeyboardThemeCatalog.presets.forEach { original ->
            val encoded = KeyboardThemeJson.encode(original)
            val decoded = KeyboardThemeJson.decode(encoded)
            assertNotNull("Failed to decode ${original.id}", decoded)
            assertEquals(original, decoded)
        }
    }

    @Test fun roundTripPreservesCustomImageThemeAndReservationFields() {
        val custom = KeyboardThemeSpec(
            id = "custom_photo_01",
            name = "マイ写真テーマ",
            category = ThemeCategory.CUSTOM,
            isBuiltIn = false,
            isDark = true,
            background = BackgroundStyle(
                kind = BackgroundKind.IMAGE,
                solidColor = 0xFF121212L,
                gradientStartColor = 0xFF111111L,
                gradientEndColor = 0xFF222222L,
                gradientAngleDeg = 90f,
                imageFileName = "bg_rendered.webp",
                cropCenterX = 0.42f,
                cropCenterY = 0.61f,
                cropScale = 1.75f,
                blurRadiusDp = 12f,
                dimAlpha = 0.35f,
                dimColor = 0xFF000000L,
                applyToBars = true
            ),
            keySurface = KeySurfaceStyle(
                preset = KeyStylePreset.OUTLINED_GLASS,
                cornerRadiusDp = 11f,
                enterCornerRadiusDp = 22f,
                horizontalGapDp = 3f,
                verticalGapDp = 3.5f,
                normalKeyFill = 0xFF1E293BL,
                modifierKeyFill = 0xFF0F172AL,
                accentKeyFill = 0xFF38BDF8L,
                spaceKeyFill = 0xFF1E293BL,
                pressedKeyFill = 0xFF0284C7L,
                keyFillAlpha = 0.45f,
                modifierKeyAlpha = 0.6f,
                borderWidthDp = 1.2f,
                borderColor = 0xFFFFFFFFL,
                borderAlpha = 0.35f,
                shadowElevationDp = 2f,
                shadowColor = 0xFF000000L,
                bevelIntensity = 0f
            ),
            typography = KeyTypographyStyle(
                primaryTextColor = 0xFFF8FAFC,
                secondaryTextColor = 0xFFCBD5E1,
                accentTextColor = 0xFF082F49,
                hintTextColor = 0xFF94A3B8,
                textShadowEnabled = true,
                labelScale = 1.05f,
                showFlickDirectionHints = false
            ),
            bars = BarStyle(
                backgroundColor = 0xFF0F172AL,
                backgroundAlpha = 0.55f,
                dividerColor = 0xFF38BDF8L,
                candidateTextColor = 0xFFF8FAFC,
                aiCandidateTextColor = 0xFF7DD3FC,
                mutedTextColor = 0xFF94A3B8,
                chipBackgroundColor = 0xFF1E293BL
            ),
            popup = PopupStyle(
                backgroundColor = 0xFF1E293BL,
                accentColor = 0xFF38BDF8L,
                textColor = 0xFFF8FAFC,
                onAccentColor = 0xFF082F49,
                borderColor = 0xFF38BDF8L,
                cornerRadiusDp = 12f
            ),
            fontFamilyId = "rounded_jp",
            tapEffectId = "ripple_glow",
            soundProfileId = "soft_pop"
        )

        val encoded = KeyboardThemeJson.encode(custom)
        val decoded = KeyboardThemeJson.decode(encoded)
        assertEquals(custom, decoded)
    }

    @Test fun decodeClampsOutOfRangeValuesAndIgnoresUnknownFields() {
        val raw = """
            {
              "schemaVersion": 1,
              "id": "clamped_theme",
              "name": "クランプ確認",
              "unknownTopLevel": "ignored",
              "background": {
                "kind": "LINEAR_GRADIENT",
                "gradientAngleDeg": 450,
                "cropScale": 10.0,
                "blurRadiusDp": 99.0,
                "dimAlpha": -0.5,
                "extraBgField": 123
              },
              "keySurface": {
                "preset": "IOS_SHADOW",
                "cornerRadiusDp": 999.0,
                "keyFillAlpha": 2.5,
                "modifierKeyAlpha": -0.2,
                "borderWidthDp": 12.0,
                "shadowElevationDp": 30.0
              },
              "typography": {
                "labelScale": 5.0
              }
            }
        """.trimIndent()

        val decoded = KeyboardThemeJson.decode(raw)
        assertNotNull(decoded)
        decoded!!
        assertEquals(90f, decoded.background.gradientAngleDeg, 0.001f)
        assertEquals(3f, decoded.background.cropScale, 0.001f)
        assertEquals(24f, decoded.background.blurRadiusDp, 0.001f)
        assertEquals(0f, decoded.background.dimAlpha, 0.001f)
        assertEquals(22f, decoded.keySurface.cornerRadiusDp, 0.001f)
        assertEquals(1f, decoded.keySurface.keyFillAlpha, 0.001f)
        assertEquals(0f, decoded.keySurface.modifierKeyAlpha, 0.001f)
        assertEquals(2.5f, decoded.keySurface.borderWidthDp, 0.001f)
        assertEquals(6f, decoded.keySurface.shadowElevationDp, 0.001f)
        assertEquals(1.20f, decoded.typography.labelScale, 0.001f)
        assertTrue(decoded.typography.showFlickDirectionHints)
    }

    @Test fun decodeReturnsNullForMalformedOrIncompleteJson() {
        assertNull(KeyboardThemeJson.decode(""))
        assertNull(KeyboardThemeJson.decode("   "))
        assertNull(KeyboardThemeJson.decode("{not valid json"))
        assertNull(KeyboardThemeJson.decode("""{"name":"missing id"}"""))
    }
}
