package com.zeroconvert.ime.theme

import kotlin.math.abs

enum class ThemeCategory(val label: String) {
    STANDARD("標準"),
    OS_STYLE("OS風"),
    GRADIENT_GLASS("グラデ＆ガラス"),
    POP_NEON("ポップ＆ネオン"),
    CUSTOM("マイテーマ")
}

enum class BackgroundKind {
    SOLID,
    LINEAR_GRADIENT,
    IMAGE
}

data class BackgroundStyle(
    val kind: BackgroundKind = BackgroundKind.SOLID,
    val solidColor: Long = 0xFF18191CL,
    val gradientStartColor: Long = 0xFF101722L,
    val gradientEndColor: Long = 0xFF24344BL,
    val gradientAngleDeg: Float = 135f,
    val imageFileName: String? = null,
    val cropCenterX: Float = 0.5f,
    val cropCenterY: Float = 0.5f,
    val cropScale: Float = 1.0f,
    val blurRadiusDp: Float = 0f,
    val dimAlpha: Float = 0.25f,
    val dimColor: Long = 0xFF000000L,
    val applyToBars: Boolean = false
) {
    fun sanitized(): BackgroundStyle {
        val rawAngle = if (gradientAngleDeg.isFinite()) gradientAngleDeg else 135f
        val normalizedAngle = ((rawAngle % 360f) + 360f) % 360f
        return copy(
            gradientAngleDeg = normalizedAngle,
            cropCenterX = safeFinite(cropCenterX, 0.5f).coerceIn(0f, 1f),
            cropCenterY = safeFinite(cropCenterY, 0.5f).coerceIn(0f, 1f),
            cropScale = safeFinite(cropScale, 1.0f).coerceIn(1f, 3f),
            blurRadiusDp = safeFinite(blurRadiusDp, 0f).coerceIn(0f, 24f),
            dimAlpha = safeFinite(dimAlpha, 0.25f).coerceIn(0f, 0.85f)
        )
    }
}

enum class KeyStylePreset(val label: String) {
    BEVEL_3D("立体ベベル"),
    IOS_SHADOW("iOSシャドウ"),
    MATERIAL_FLAT("フラット"),
    OUTLINED_GLASS("ガラス枠"),
    BORDERLESS("枠なし")
}

data class KeySurfaceStyle(
    val preset: KeyStylePreset = KeyStylePreset.BEVEL_3D,
    val cornerRadiusDp: Float = 7f,
    val enterCornerRadiusDp: Float = 22f,
    val horizontalGapDp: Float = 2.5f,
    val verticalGapDp: Float = 3.0f,
    val normalKeyFill: Long = 0xFF34373DL,
    val modifierKeyFill: Long = 0xFF292B30L,
    val accentKeyFill: Long = 0xFFC0C7D4L,
    val spaceKeyFill: Long = 0xFF34373DL,
    val pressedKeyFill: Long? = null,
    val keyFillAlpha: Float = 1.0f,
    val modifierKeyAlpha: Float = 1.0f,
    val borderWidthDp: Float = 0.8f,
    val borderColor: Long = 0xFF454951L,
    val borderAlpha: Float = 0.7f,
    val shadowElevationDp: Float = 0f,
    val shadowColor: Long = 0xFF000000L,
    val bevelIntensity: Float = 1.0f
) {
    fun sanitized(): KeySurfaceStyle = copy(
        cornerRadiusDp = safeFinite(cornerRadiusDp, 7f).coerceIn(0f, 22f),
        enterCornerRadiusDp = safeFinite(enterCornerRadiusDp, 22f).coerceIn(0f, 24f),
        horizontalGapDp = safeFinite(horizontalGapDp, 2.5f).coerceIn(0f, 6f),
        verticalGapDp = safeFinite(verticalGapDp, 3.0f).coerceIn(0f, 6f),
        keyFillAlpha = safeFinite(keyFillAlpha, 1.0f).coerceIn(0f, 1f),
        modifierKeyAlpha = safeFinite(modifierKeyAlpha, 1.0f).coerceIn(0f, 1f),
        borderWidthDp = safeFinite(borderWidthDp, 0.8f).coerceIn(0f, 2.5f),
        borderAlpha = safeFinite(borderAlpha, 0.7f).coerceIn(0f, 1f),
        shadowElevationDp = safeFinite(shadowElevationDp, 0f).coerceIn(0f, 6f),
        bevelIntensity = safeFinite(bevelIntensity, 1.0f).coerceIn(0f, 1.5f)
    )
}

data class KeyTypographyStyle(
    val primaryTextColor: Long = 0xFFF1F2F4L,
    val secondaryTextColor: Long = 0xFFB0B4BDL,
    val accentTextColor: Long = 0xFF22252BL,
    val hintTextColor: Long = 0xFF9EA1AAL,
    val textShadowEnabled: Boolean = false,
    val labelScale: Float = 1.0f,
    val showFlickDirectionHints: Boolean = true
) {
    fun sanitized(): KeyTypographyStyle = copy(
        labelScale = safeFinite(labelScale, 1.0f).coerceIn(0.85f, 1.20f)
    )
}

data class BarStyle(
    val backgroundColor: Long = 0xFF232529L,
    val backgroundAlpha: Float = 1.0f,
    val dividerColor: Long = 0xFF454951L,
    val candidateTextColor: Long = 0xFFF1F2F4L,
    val aiCandidateTextColor: Long = 0xFFD1D6DFL,
    val mutedTextColor: Long = 0xFF9EA1AAL,
    val chipBackgroundColor: Long = 0xFF34373DL
) {
    fun sanitized(): BarStyle = copy(
        backgroundAlpha = safeFinite(backgroundAlpha, 1.0f).coerceIn(0f, 1f)
    )
}

data class PopupStyle(
    val backgroundColor: Long = 0xFF34373DL,
    val accentColor: Long = 0xFFC0C7D4L,
    val textColor: Long = 0xFFF1F2F4L,
    val onAccentColor: Long = 0xFF22252BL,
    val borderColor: Long = 0xFF454951L,
    val cornerRadiusDp: Float = 10f
) {
    fun sanitized(): PopupStyle = copy(
        cornerRadiusDp = safeFinite(cornerRadiusDp, 10f).coerceIn(0f, 24f)
    )
}

data class KeyboardThemeSpec(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: String,
    val name: String,
    val category: ThemeCategory,
    val isBuiltIn: Boolean,
    val isDark: Boolean,
    val background: BackgroundStyle,
    val keySurface: KeySurfaceStyle,
    val typography: KeyTypographyStyle,
    val bars: BarStyle,
    val popup: PopupStyle,
    val fontFamilyId: String? = null,
    val tapEffectId: String? = null,
    val soundProfileId: String? = null
) {
    fun sanitized(): KeyboardThemeSpec = copy(
        schemaVersion = if (schemaVersion <= 0) CURRENT_SCHEMA_VERSION else schemaVersion,
        id = id.trim().ifEmpty { "custom_theme" },
        name = name.trim().ifEmpty { "カスタムテーマ" },
        background = background.sanitized(),
        keySurface = keySurface.sanitized(),
        typography = typography.sanitized(),
        bars = bars.sanitized(),
        popup = popup.sanitized(),
        fontFamilyId = fontFamilyId?.trim()?.takeIf { it.isNotEmpty() },
        tapEffectId = tapEffectId?.trim()?.takeIf { it.isNotEmpty() },
        soundProfileId = soundProfileId?.trim()?.takeIf { it.isNotEmpty() }
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

private fun safeFinite(value: Float, fallback: Float): Float =
    if (value.isFinite() && !abs(value).isNaN()) value else fallback
