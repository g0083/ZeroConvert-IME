package com.zeroconvert.ime.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.zeroconvert.ime.ZERO_CON_THEME_DEFAULT_DARK
import com.zeroconvert.ime.ZERO_CON_THEME_DEFAULT_LIGHT
import com.zeroconvert.ime.ZERO_CON_THEME_SYSTEM
import com.zeroconvert.ime.ZeroConTheme
import com.zeroconvert.ime.ZeroConThemes

object KeyboardThemeCatalog {

    private fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

    private fun fromZeroConTheme(theme: ZeroConTheme): KeyboardThemeSpec {
        val p = theme.palette
        return KeyboardThemeSpec(
            id = theme.id,
            name = theme.label,
            category = ThemeCategory.STANDARD,
            isBuiltIn = true,
            isDark = theme.isDark,
            background = BackgroundStyle(
                kind = BackgroundKind.SOLID,
                solidColor = p.canvas.toArgbLong(),
                gradientStartColor = p.canvas.toArgbLong(),
                gradientEndColor = p.surface.toArgbLong(),
                dimAlpha = 0f,
                applyToBars = false
            ),
            keySurface = KeySurfaceStyle(
                preset = KeyStylePreset.BEVEL_3D,
                cornerRadiusDp = 7f,
                enterCornerRadiusDp = 22f,
                horizontalGapDp = 2.5f,
                verticalGapDp = 3.0f,
                normalKeyFill = p.keyNormal.toArgbLong(),
                modifierKeyFill = p.keyModifier.toArgbLong(),
                accentKeyFill = p.primaryAccent.toArgbLong(),
                spaceKeyFill = p.keyNormal.toArgbLong(),
                keyFillAlpha = 1.0f,
                modifierKeyAlpha = 1.0f,
                borderWidthDp = 0.8f,
                borderColor = p.borderSubtle.toArgbLong(),
                borderAlpha = if (p.isDark) 0.68f else 0.90f,
                shadowElevationDp = 0f,
                bevelIntensity = 1.0f
            ),
            typography = KeyTypographyStyle(
                primaryTextColor = p.textPrimary.toArgbLong(),
                secondaryTextColor = p.textSecondary.toArgbLong(),
                accentTextColor = p.onAccent.toArgbLong(),
                hintTextColor = p.textMuted.toArgbLong(),
                textShadowEnabled = false
            ),
            bars = BarStyle(
                backgroundColor = p.surface.toArgbLong(),
                backgroundAlpha = 1.0f,
                dividerColor = p.borderSubtle.toArgbLong(),
                candidateTextColor = p.textPrimary.toArgbLong(),
                aiCandidateTextColor = p.primaryAccentSoft.toArgbLong(),
                mutedTextColor = p.textMuted.toArgbLong(),
                chipBackgroundColor = p.surfaceElevated.toArgbLong()
            ),
            popup = PopupStyle(
                backgroundColor = p.surfaceElevated.toArgbLong(),
                accentColor = p.primaryAccent.toArgbLong(),
                textColor = p.textPrimary.toArgbLong(),
                onAccentColor = p.onAccent.toArgbLong(),
                borderColor = p.borderSubtle.toArgbLong(),
                cornerRadiusDp = 10f
            )
        )
    }

    private val standardPresets: List<KeyboardThemeSpec> = ZeroConThemes.map(::fromZeroConTheme)

    private val iosLight = KeyboardThemeSpec(
        id = "ios_light",
        name = "iOS ライト風",
        category = ThemeCategory.OS_STYLE,
        isBuiltIn = true,
        isDark = false,
        background = BackgroundStyle(
            kind = BackgroundKind.SOLID,
            solidColor = 0xFFD1D5DBL,
            gradientStartColor = 0xFFD1D5DBL,
            gradientEndColor = 0xFFE5E7EBL,
            dimAlpha = 0f,
            applyToBars = false
        ),
        keySurface = KeySurfaceStyle(
            preset = KeyStylePreset.IOS_SHADOW,
            cornerRadiusDp = 5f,
            enterCornerRadiusDp = 5f,
            horizontalGapDp = 2.5f,
            verticalGapDp = 3.5f,
            normalKeyFill = 0xFFFFFFFFL,
            modifierKeyFill = 0xFFAEB4BFL,
            accentKeyFill = 0xFF0066CCL,
            spaceKeyFill = 0xFFFFFFFFL,
            keyFillAlpha = 1.0f,
            modifierKeyAlpha = 1.0f,
            borderWidthDp = 0f,
            borderColor = 0xFF9CA3AFL,
            borderAlpha = 0f,
            shadowElevationDp = 1.5f,
            shadowColor = 0xFF6B7280L,
            bevelIntensity = 0f
        ),
        typography = KeyTypographyStyle(
            primaryTextColor = 0xFF111827L,
            secondaryTextColor = 0xFF1F2937L,
            accentTextColor = 0xFFFFFFFFL,
            hintTextColor = 0xFF4B5563L
        ),
        bars = BarStyle(
            backgroundColor = 0xFFDDE1E7L,
            backgroundAlpha = 1.0f,
            dividerColor = 0xFFB8BFCBL,
            candidateTextColor = 0xFF111827L,
            aiCandidateTextColor = 0xFF0055B3L,
            mutedTextColor = 0xFF4B5563L,
            chipBackgroundColor = 0xFFFFFFFFL
        ),
        popup = PopupStyle(
            backgroundColor = 0xFFFFFFFFL,
            accentColor = 0xFF0066CCL,
            textColor = 0xFF111827L,
            onAccentColor = 0xFFFFFFFFL,
            borderColor = 0xFFCBD5E1L,
            cornerRadiusDp = 8f
        )
    )

    private val iosDark = KeyboardThemeSpec(
        id = "ios_dark",
        name = "iOS ダーク風",
        category = ThemeCategory.OS_STYLE,
        isBuiltIn = true,
        isDark = true,
        background = BackgroundStyle(
            kind = BackgroundKind.SOLID,
            solidColor = 0xFF1C1C1EL,
            gradientStartColor = 0xFF1C1C1EL,
            gradientEndColor = 0xFF2C2C2EL,
            dimAlpha = 0f,
            applyToBars = false
        ),
        keySurface = KeySurfaceStyle(
            preset = KeyStylePreset.IOS_SHADOW,
            cornerRadiusDp = 5f,
            enterCornerRadiusDp = 5f,
            horizontalGapDp = 2.5f,
            verticalGapDp = 3.5f,
            normalKeyFill = 0xFF48484AL,
            modifierKeyFill = 0xFF2C2C2EL,
            accentKeyFill = 0xFF006EE6L,
            spaceKeyFill = 0xFF48484AL,
            keyFillAlpha = 1.0f,
            modifierKeyAlpha = 1.0f,
            borderWidthDp = 0f,
            borderColor = 0xFF3A3A3CL,
            borderAlpha = 0f,
            shadowElevationDp = 1.5f,
            shadowColor = 0xFF000000L,
            bevelIntensity = 0f
        ),
        typography = KeyTypographyStyle(
            primaryTextColor = 0xFFFFFFFFL,
            secondaryTextColor = 0xFFE5E5EAL,
            accentTextColor = 0xFFFFFFFFL,
            hintTextColor = 0xFFA1A1AAL
        ),
        bars = BarStyle(
            backgroundColor = 0xFF242426L,
            backgroundAlpha = 1.0f,
            dividerColor = 0xFF3A3A3CL,
            candidateTextColor = 0xFFFFFFFFL,
            aiCandidateTextColor = 0xFF60A5FAL,
            mutedTextColor = 0xFFA1A1AAL,
            chipBackgroundColor = 0xFF3A3A3CL
        ),
        popup = PopupStyle(
            backgroundColor = 0xFF3A3A3CL,
            accentColor = 0xFF006EE6L,
            textColor = 0xFFFFFFFFL,
            onAccentColor = 0xFFFFFFFFL,
            borderColor = 0xFF52525BL,
            cornerRadiusDp = 8f
        )
    )

    private val materialLight = KeyboardThemeSpec(
        id = "material_light",
        name = "Material ライト風",
        category = ThemeCategory.OS_STYLE,
        isBuiltIn = true,
        isDark = false,
        background = BackgroundStyle(
            kind = BackgroundKind.SOLID,
            solidColor = 0xFFEEF2F6L,
            gradientStartColor = 0xFFEEF2F6L,
            gradientEndColor = 0xFFE2E8F0L,
            dimAlpha = 0f,
            applyToBars = false
        ),
        keySurface = KeySurfaceStyle(
            preset = KeyStylePreset.MATERIAL_FLAT,
            cornerRadiusDp = 8f,
            enterCornerRadiusDp = 22f,
            horizontalGapDp = 2.8f,
            verticalGapDp = 3.2f,
            normalKeyFill = 0xFFFFFFFFL,
            modifierKeyFill = 0xFFD9E2ECL,
            accentKeyFill = 0xFF1967D2L,
            spaceKeyFill = 0xFFFFFFFFL,
            keyFillAlpha = 1.0f,
            modifierKeyAlpha = 1.0f,
            borderWidthDp = 0f,
            borderColor = 0xFFCBD5E1L,
            borderAlpha = 0f,
            shadowElevationDp = 0f,
            bevelIntensity = 0f
        ),
        typography = KeyTypographyStyle(
            primaryTextColor = 0xFF1F2937L,
            secondaryTextColor = 0xFF374151L,
            accentTextColor = 0xFFFFFFFFL,
            hintTextColor = 0xFF52606DL
        ),
        bars = BarStyle(
            backgroundColor = 0xFFE5ECF3L,
            backgroundAlpha = 1.0f,
            dividerColor = 0xFFCBD5E1L,
            candidateTextColor = 0xFF1F2937L,
            aiCandidateTextColor = 0xFF1967D2L,
            mutedTextColor = 0xFF52606DL,
            chipBackgroundColor = 0xFFFFFFFFL
        ),
        popup = PopupStyle(
            backgroundColor = 0xFFFFFFFFL,
            accentColor = 0xFF1967D2L,
            textColor = 0xFF1F2937L,
            onAccentColor = 0xFFFFFFFFL,
            borderColor = 0xFFCBD5E1L,
            cornerRadiusDp = 12f
        )
    )

    private val materialDark = KeyboardThemeSpec(
        id = "material_dark",
        name = "Material ダーク風",
        category = ThemeCategory.OS_STYLE,
        isBuiltIn = true,
        isDark = true,
        background = BackgroundStyle(
            kind = BackgroundKind.SOLID,
            solidColor = 0xFF1B1F24L,
            gradientStartColor = 0xFF1B1F24L,
            gradientEndColor = 0xFF252B33L,
            dimAlpha = 0f,
            applyToBars = false
        ),
        keySurface = KeySurfaceStyle(
            preset = KeyStylePreset.MATERIAL_FLAT,
            cornerRadiusDp = 8f,
            enterCornerRadiusDp = 22f,
            horizontalGapDp = 2.8f,
            verticalGapDp = 3.2f,
            normalKeyFill = 0xFF2E343DL,
            modifierKeyFill = 0xFF232830L,
            accentKeyFill = 0xFF8AB4F8L,
            spaceKeyFill = 0xFF2E343DL,
            keyFillAlpha = 1.0f,
            modifierKeyAlpha = 1.0f,
            borderWidthDp = 0f,
            borderColor = 0xFF3E4551L,
            borderAlpha = 0f,
            shadowElevationDp = 0f,
            bevelIntensity = 0f
        ),
        typography = KeyTypographyStyle(
            primaryTextColor = 0xFFF1F5F9L,
            secondaryTextColor = 0xFFCBD5E1L,
            accentTextColor = 0xFF0B2545L,
            hintTextColor = 0xFF94A3B8L
        ),
        bars = BarStyle(
            backgroundColor = 0xFF20252CL,
            backgroundAlpha = 1.0f,
            dividerColor = 0xFF363D47L,
            candidateTextColor = 0xFFF1F5F9L,
            aiCandidateTextColor = 0xFF8AB4F8L,
            mutedTextColor = 0xFF94A3B8L,
            chipBackgroundColor = 0xFF2E343DL
        ),
        popup = PopupStyle(
            backgroundColor = 0xFF363D47L,
            accentColor = 0xFF8AB4F8L,
            textColor = 0xFFF1F5F9L,
            onAccentColor = 0xFF0B2545L,
            borderColor = 0xFF4B5563L,
            cornerRadiusDp = 12f
        )
    )

    private val auroraGlass = KeyboardThemeSpec(
        id = "aurora_glass",
        name = "オーロラグラス",
        category = ThemeCategory.GRADIENT_GLASS,
        isBuiltIn = true,
        isDark = true,
        background = BackgroundStyle(
            kind = BackgroundKind.LINEAR_GRADIENT,
            solidColor = 0xFF0F2A34L,
            gradientStartColor = 0xFF0F172AL,
            gradientEndColor = 0xFF0F3D3EL,
            gradientAngleDeg = 135f,
            dimAlpha = 0f,
            applyToBars = true
        ),
        keySurface = KeySurfaceStyle(
            preset = KeyStylePreset.OUTLINED_GLASS,
            cornerRadiusDp = 10f,
            enterCornerRadiusDp = 22f,
            horizontalGapDp = 2.5f,
            verticalGapDp = 3.0f,
            normalKeyFill = 0xFFFFFFFFL,
            modifierKeyFill = 0xFF0B1320L,
            accentKeyFill = 0xFF2DD4BFL,
            spaceKeyFill = 0xFFFFFFFFL,
            keyFillAlpha = 0.14f,
            modifierKeyAlpha = 0.42f,
            borderWidthDp = 1.0f,
            borderColor = 0xFFFFFFFFL,
            borderAlpha = 0.28f,
            shadowElevationDp = 0f,
            bevelIntensity = 0f
        ),
        typography = KeyTypographyStyle(
            primaryTextColor = 0xFFF0FDFAL,
            secondaryTextColor = 0xFFCCFBF1L,
            accentTextColor = 0xFF042F2EL,
            hintTextColor = 0xFF99F6E4L,
            textShadowEnabled = true
        ),
        bars = BarStyle(
            backgroundColor = 0xFF0B1320L,
            backgroundAlpha = 0.52f,
            dividerColor = 0xFF2DD4BFL,
            candidateTextColor = 0xFFF0FDFAL,
            aiCandidateTextColor = 0xFF5EEAD4L,
            mutedTextColor = 0xFF99F6E4L,
            chipBackgroundColor = 0xFF134E4AL
        ),
        popup = PopupStyle(
            backgroundColor = 0xFF112A36L,
            accentColor = 0xFF2DD4BFL,
            textColor = 0xFFF0FDFAL,
            onAccentColor = 0xFF042F2EL,
            borderColor = 0xFF5EEAD4L,
            cornerRadiusDp = 12f
        )
    )

    private val sunsetGlow = KeyboardThemeSpec(
        id = "sunset_glow",
        name = "サンセット",
        category = ThemeCategory.GRADIENT_GLASS,
        isBuiltIn = true,
        isDark = true,
        background = BackgroundStyle(
            kind = BackgroundKind.LINEAR_GRADIENT,
            solidColor = 0xFF5E1D33L,
            gradientStartColor = 0xFF3B1348L,
            gradientEndColor = 0xFF9A3412L,
            gradientAngleDeg = 140f,
            dimAlpha = 0f,
            applyToBars = true
        ),
        keySurface = KeySurfaceStyle(
            preset = KeyStylePreset.OUTLINED_GLASS,
            cornerRadiusDp = 10f,
            enterCornerRadiusDp = 22f,
            horizontalGapDp = 2.5f,
            verticalGapDp = 3.0f,
            normalKeyFill = 0xFF1F0A24L,
            modifierKeyFill = 0xFF140718L,
            accentKeyFill = 0xFFFDBA74L,
            spaceKeyFill = 0xFF1F0A24L,
            keyFillAlpha = 0.45f,
            modifierKeyAlpha = 0.58f,
            borderWidthDp = 0.9f,
            borderColor = 0xFFFED7AAL,
            borderAlpha = 0.30f,
            shadowElevationDp = 0f,
            bevelIntensity = 0f
        ),
        typography = KeyTypographyStyle(
            primaryTextColor = 0xFFFFF7EDL,
            secondaryTextColor = 0xFFFED7AAL,
            accentTextColor = 0xFF431407L,
            hintTextColor = 0xFFFDBA74L,
            textShadowEnabled = true
        ),
        bars = BarStyle(
            backgroundColor = 0xFF1C0922L,
            backgroundAlpha = 0.56f,
            dividerColor = 0xFFFB923CL,
            candidateTextColor = 0xFFFFF7EDL,
            aiCandidateTextColor = 0xFFFDBA74L,
            mutedTextColor = 0xFFFED7AAL,
            chipBackgroundColor = 0xFF3B1348L
        ),
        popup = PopupStyle(
            backgroundColor = 0xFF3A1539L,
            accentColor = 0xFFFDBA74L,
            textColor = 0xFFFFF7EDL,
            onAccentColor = 0xFF431407L,
            borderColor = 0xFFFDBA74L,
            cornerRadiusDp = 12f
        )
    )

    private val oceanMist = KeyboardThemeSpec(
        id = "ocean_mist",
        name = "オーシャンミスト",
        category = ThemeCategory.GRADIENT_GLASS,
        isBuiltIn = true,
        isDark = false,
        background = BackgroundStyle(
            kind = BackgroundKind.LINEAR_GRADIENT,
            solidColor = 0xFFDDF6FAL,
            gradientStartColor = 0xFFE0F2FEL,
            gradientEndColor = 0xFFCCFBF1L,
            gradientAngleDeg = 135f,
            dimAlpha = 0f,
            applyToBars = true
        ),
        keySurface = KeySurfaceStyle(
            preset = KeyStylePreset.OUTLINED_GLASS,
            cornerRadiusDp = 10f,
            enterCornerRadiusDp = 22f,
            horizontalGapDp = 2.5f,
            verticalGapDp = 3.0f,
            normalKeyFill = 0xFFFFFFFFL,
            modifierKeyFill = 0xFFE0F2FEL,
            accentKeyFill = 0xFF0369A1L,
            spaceKeyFill = 0xFFFFFFFFL,
            keyFillAlpha = 0.78f,
            modifierKeyAlpha = 0.85f,
            borderWidthDp = 1.0f,
            borderColor = 0xFF0369A1L,
            borderAlpha = 0.22f,
            shadowElevationDp = 0f,
            bevelIntensity = 0f
        ),
        typography = KeyTypographyStyle(
            primaryTextColor = 0xFF0C4A6EL,
            secondaryTextColor = 0xFF0369A1L,
            accentTextColor = 0xFFFFFFFFL,
            hintTextColor = 0xFF075985L
        ),
        bars = BarStyle(
            backgroundColor = 0xFFFFFFFFL,
            backgroundAlpha = 0.65f,
            dividerColor = 0xFFBAE6FDL,
            candidateTextColor = 0xFF0C4A6EL,
            aiCandidateTextColor = 0xFF0369A1L,
            mutedTextColor = 0xFF075985L,
            chipBackgroundColor = 0xFFE0F2FEL
        ),
        popup = PopupStyle(
            backgroundColor = 0xFFFFFFFFL,
            accentColor = 0xFF0369A1L,
            textColor = 0xFF0C4A6EL,
            onAccentColor = 0xFFFFFFFFL,
            borderColor = 0xFF7DD3FCL,
            cornerRadiusDp = 12f
        )
    )

    private val pastelMint = KeyboardThemeSpec(
        id = "pastel_mint",
        name = "パステルミント",
        category = ThemeCategory.POP_NEON,
        isBuiltIn = true,
        isDark = false,
        background = BackgroundStyle(
            kind = BackgroundKind.SOLID,
            solidColor = 0xFFE6F7F0L,
            gradientStartColor = 0xFFE6F7F0L,
            gradientEndColor = 0xFFD5F2E6L,
            dimAlpha = 0f,
            applyToBars = false
        ),
        keySurface = KeySurfaceStyle(
            preset = KeyStylePreset.MATERIAL_FLAT,
            cornerRadiusDp = 12f,
            enterCornerRadiusDp = 22f,
            horizontalGapDp = 2.8f,
            verticalGapDp = 3.2f,
            normalKeyFill = 0xFFFFFDF9L,
            modifierKeyFill = 0xFFCDEEE0L,
            accentKeyFill = 0xFF0F766EL,
            spaceKeyFill = 0xFFFFFDF9L,
            keyFillAlpha = 1.0f,
            modifierKeyAlpha = 1.0f,
            borderWidthDp = 0.8f,
            borderColor = 0xFF99D5BEL,
            borderAlpha = 0.70f,
            shadowElevationDp = 0f,
            bevelIntensity = 0f
        ),
        typography = KeyTypographyStyle(
            primaryTextColor = 0xFF134E4AL,
            secondaryTextColor = 0xFF115E59L,
            accentTextColor = 0xFFFFFFFFL,
            hintTextColor = 0xFF0F766EL
        ),
        bars = BarStyle(
            backgroundColor = 0xFFF2FBF7L,
            backgroundAlpha = 1.0f,
            dividerColor = 0xFFA7DFC9L,
            candidateTextColor = 0xFF134E4AL,
            aiCandidateTextColor = 0xFF0F766EL,
            mutedTextColor = 0xFF115E59L,
            chipBackgroundColor = 0xFFDDF5EBL
        ),
        popup = PopupStyle(
            backgroundColor = 0xFFFFFDF9L,
            accentColor = 0xFF0F766EL,
            textColor = 0xFF134E4AL,
            onAccentColor = 0xFFFFFFFFL,
            borderColor = 0xFF99D5BEL,
            cornerRadiusDp = 14f
        )
    )

    private val cyberNeon = KeyboardThemeSpec(
        id = "cyber_neon",
        name = "サイバーネオン",
        category = ThemeCategory.POP_NEON,
        isBuiltIn = true,
        isDark = true,
        background = BackgroundStyle(
            kind = BackgroundKind.SOLID,
            solidColor = 0xFF090B12L,
            gradientStartColor = 0xFF090B12L,
            gradientEndColor = 0xFF121024L,
            dimAlpha = 0f,
            applyToBars = false
        ),
        keySurface = KeySurfaceStyle(
            preset = KeyStylePreset.OUTLINED_GLASS,
            cornerRadiusDp = 6f,
            enterCornerRadiusDp = 6f,
            horizontalGapDp = 2.5f,
            verticalGapDp = 3.0f,
            normalKeyFill = 0xFF111827L,
            modifierKeyFill = 0xFF161326L,
            accentKeyFill = 0xFF22D3EEL,
            spaceKeyFill = 0xFF111827L,
            keyFillAlpha = 0.88f,
            modifierKeyAlpha = 0.92f,
            borderWidthDp = 1.1f,
            borderColor = 0xFF22D3EEL,
            borderAlpha = 0.55f,
            shadowElevationDp = 0f,
            bevelIntensity = 0f
        ),
        typography = KeyTypographyStyle(
            primaryTextColor = 0xFFECFEFFL,
            secondaryTextColor = 0xFF67E8F9L,
            accentTextColor = 0xFF04141AL,
            hintTextColor = 0xFFA5F3FCL
        ),
        bars = BarStyle(
            backgroundColor = 0xFF0E121DL,
            backgroundAlpha = 1.0f,
            dividerColor = 0xFF164E63L,
            candidateTextColor = 0xFFECFEFFL,
            aiCandidateTextColor = 0xFF22D3EEL,
            mutedTextColor = 0xFF67E8F9L,
            chipBackgroundColor = 0xFF162033L
        ),
        popup = PopupStyle(
            backgroundColor = 0xFF111827L,
            accentColor = 0xFF22D3EEL,
            textColor = 0xFFECFEFFL,
            onAccentColor = 0xFF04141AL,
            borderColor = 0xFF22D3EEL,
            cornerRadiusDp = 8f
        )
    )

    private val monoMinimal = KeyboardThemeSpec(
        id = "mono_minimal",
        name = "モノクロミニマル",
        category = ThemeCategory.POP_NEON,
        isBuiltIn = true,
        isDark = true,
        background = BackgroundStyle(
            kind = BackgroundKind.SOLID,
            solidColor = 0xFF000000L,
            gradientStartColor = 0xFF000000L,
            gradientEndColor = 0xFF0A0A0AL,
            dimAlpha = 0f,
            applyToBars = false
        ),
        keySurface = KeySurfaceStyle(
            preset = KeyStylePreset.BORDERLESS,
            cornerRadiusDp = 6f,
            enterCornerRadiusDp = 18f,
            horizontalGapDp = 2.5f,
            verticalGapDp = 3.0f,
            normalKeyFill = 0xFF141414L,
            modifierKeyFill = 0xFF0A0A0AL,
            accentKeyFill = 0xFFFFFFFFL,
            spaceKeyFill = 0xFF141414L,
            keyFillAlpha = 0.85f,
            modifierKeyAlpha = 0.85f,
            borderWidthDp = 0f,
            borderColor = 0xFF333333L,
            borderAlpha = 0f,
            shadowElevationDp = 0f,
            bevelIntensity = 0f
        ),
        typography = KeyTypographyStyle(
            primaryTextColor = 0xFFF5F5F5L,
            secondaryTextColor = 0xFFB5B5B5L,
            accentTextColor = 0xFF000000L,
            hintTextColor = 0xFF8C8C8CL
        ),
        bars = BarStyle(
            backgroundColor = 0xFF080808L,
            backgroundAlpha = 1.0f,
            dividerColor = 0xFF262626L,
            candidateTextColor = 0xFFF5F5F5L,
            aiCandidateTextColor = 0xFFFFFFFFL,
            mutedTextColor = 0xFF9CA3AFL,
            chipBackgroundColor = 0xFF1A1A1AL
        ),
        popup = PopupStyle(
            backgroundColor = 0xFF1F1F1FL,
            accentColor = 0xFFFFFFFFL,
            textColor = 0xFFF5F5F5L,
            onAccentColor = 0xFF000000L,
            borderColor = 0xFF404040L,
            cornerRadiusDp = 8f
        )
    )

    val presets: List<KeyboardThemeSpec> = standardPresets + listOf(
        iosLight,
        iosDark,
        materialLight,
        materialDark,
        auroraGlass,
        sunsetGlow,
        oceanMist,
        pastelMint,
        cyberNeon,
        monoMinimal
    )

    val defaultDark: KeyboardThemeSpec by lazy {
        findById(ZERO_CON_THEME_DEFAULT_DARK) ?: presets.first()
    }

    val defaultLight: KeyboardThemeSpec by lazy {
        findById(ZERO_CON_THEME_DEFAULT_LIGHT) ?: presets.first()
    }

    fun findById(id: String): KeyboardThemeSpec? = presets.firstOrNull { it.id == id }

    fun resolveId(
        themeId: String,
        systemDark: Boolean,
        customThemes: List<KeyboardThemeSpec> = emptyList()
    ): String = when (themeId) {
        ZERO_CON_THEME_SYSTEM, "" ->
            if (systemDark) ZERO_CON_THEME_DEFAULT_DARK else ZERO_CON_THEME_DEFAULT_LIGHT
        "dark" -> ZERO_CON_THEME_DEFAULT_DARK
        "light" -> ZERO_CON_THEME_DEFAULT_LIGHT
        else -> when {
            findById(themeId) != null -> themeId
            customThemes.any { it.id == themeId } -> themeId
            else -> ZERO_CON_THEME_DEFAULT_DARK
        }
    }

    fun resolve(
        themeId: String,
        systemDark: Boolean,
        customThemes: List<KeyboardThemeSpec> = emptyList()
    ): KeyboardThemeSpec {
        val resolvedId = resolveId(themeId, systemDark, customThemes)
        return customThemes.firstOrNull { it.id == resolvedId }
            ?: findById(resolvedId)
            ?: defaultDark
    }
}
