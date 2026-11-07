package com.zeroconvert.ime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ZeroConPalette(
    val isDark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val keyNormal: Color,
    val keyModifier: Color,
    val primaryAccent: Color,
    val primaryAccentSoft: Color,
    val onAccent: Color,
    val readyEmerald: Color,
    val warningAmber: Color,
    val danger: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val borderSubtle: Color
)

/**
 * A selectable color theme. The ids and labels mirror
 * `windows/crates/shared/themes.json` so both platforms offer the same choices.
 */
data class ZeroConTheme(
    val id: String,
    val label: String,
    val palette: ZeroConPalette
) {
    val isDark: Boolean get() = palette.isDark
}

/**
 * Mirrors the Windows settings "dark_blue" palette so the Android settings
 * screen and keyboard share the same visual language.
 */
val ZeroConDarkBluePalette = ZeroConPalette(
    isDark = true,
    canvas = Color(0xFF101722),
    surface = Color(0xFF182334),
    surfaceElevated = Color(0xFF24344B),
    keyNormal = Color(0xFF24344B),
    keyModifier = Color(0xFF1B2A3F),
    primaryAccent = Color(0xFF74B5FF),
    primaryAccentSoft = Color(0xFF9CCBFF),
    onAccent = Color(0xFF10213A),
    readyEmerald = Color(0xFF34D399),
    warningAmber = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    textPrimary = Color(0xFFEDF3FC),
    textSecondary = Color(0xFFA5B6CC),
    textMuted = Color(0xFF91A5BD),
    borderSubtle = Color(0xFF344760)
)

val ZeroConGraphitePalette = ZeroConPalette(
    isDark = true,
    canvas = Color(0xFF18191C),
    surface = Color(0xFF232529),
    surfaceElevated = Color(0xFF34373D),
    keyNormal = Color(0xFF34373D),
    keyModifier = Color(0xFF292B30),
    primaryAccent = Color(0xFFC0C7D4),
    primaryAccentSoft = Color(0xFFD1D6DF),
    onAccent = Color(0xFF22252B),
    readyEmerald = Color(0xFF34D399),
    warningAmber = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    textPrimary = Color(0xFFF1F2F4),
    textSecondary = Color(0xFFB0B4BD),
    textMuted = Color(0xFF9EA1AA),
    borderSubtle = Color(0xFF454951)
)

val ZeroConForestPalette = ZeroConPalette(
    isDark = true,
    canvas = Color(0xFF101E19),
    surface = Color(0xFF182C23),
    surfaceElevated = Color(0xFF254234),
    keyNormal = Color(0xFF254234),
    keyModifier = Color(0xFF1D3429),
    primaryAccent = Color(0xFF80D5A5),
    primaryAccentSoft = Color(0xFFA4E0BE),
    onAccent = Color(0xFF102A1B),
    readyEmerald = Color(0xFF34D399),
    warningAmber = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    textPrimary = Color(0xFFE7F5ED),
    textSecondary = Color(0xFFA2C4B1),
    textMuted = Color(0xFF90B09F),
    borderSubtle = Color(0xFF355943)
)

val ZeroConPlumPalette = ZeroConPalette(
    isDark = true,
    canvas = Color(0xFF211727),
    surface = Color(0xFF302139),
    surfaceElevated = Color(0xFF47314F),
    keyNormal = Color(0xFF47314F),
    keyModifier = Color(0xFF382741),
    primaryAccent = Color(0xFFD6A0ED),
    primaryAccentSoft = Color(0xFFE1BAF2),
    onAccent = Color(0xFF30143D),
    readyEmerald = Color(0xFF34D399),
    warningAmber = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    textPrimary = Color(0xFFF5EAFA),
    textSecondary = Color(0xFFC5ABCF),
    textMuted = Color(0xFFB199BB),
    borderSubtle = Color(0xFF604269)
)

val ZeroConSakuraPalette = ZeroConPalette(
    isDark = false,
    canvas = Color(0xFFFFF5F7),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF9E4EB),
    keyNormal = Color(0xFFFFFFFF),
    keyModifier = Color(0xFFF9E4EB),
    primaryAccent = Color(0xFFA72E5B),
    primaryAccentSoft = Color(0xFFB75479),
    onAccent = Color(0xFFFFFFFF),
    readyEmerald = Color(0xFF047857),
    warningAmber = Color(0xFFB45309),
    danger = Color(0xFFB91C1C),
    textPrimary = Color(0xFF442631),
    textSecondary = Color(0xFF815763),
    textMuted = Color(0xFF906A75),
    borderSubtle = Color(0xFFE2B6C5)
)

/**
 * Mirrors the Windows settings "paper" light palette.
 */
val ZeroConPaperPalette = ZeroConPalette(
    isDark = false,
    canvas = Color(0xFFF7F5EF),
    surface = Color(0xFFFFFEFA),
    surfaceElevated = Color(0xFFEAE6DB),
    keyNormal = Color(0xFFFFFEFA),
    keyModifier = Color(0xFFEAE6DB),
    primaryAccent = Color(0xFF716027),
    primaryAccentSoft = Color(0xFF8A7834),
    onAccent = Color(0xFFFFFFFF),
    readyEmerald = Color(0xFF047857),
    warningAmber = Color(0xFFB45309),
    danger = Color(0xFFB91C1C),
    textPrimary = Color(0xFF302E27),
    textSecondary = Color(0xFF6E6756),
    textMuted = Color(0xFF7A7260),
    borderSubtle = Color(0xFFD3CBB7)
)

/** Kept for callers that only need the default dark palette. */
val ZeroConDarkPalette = ZeroConGraphitePalette

/** Kept for callers that only need the default light palette. */
val ZeroConLightPalette = ZeroConPaperPalette

const val ZERO_CON_THEME_SYSTEM = "system"
const val ZERO_CON_THEME_DEFAULT_DARK = "graphite"
const val ZERO_CON_THEME_DEFAULT_LIGHT = "paper"

/** Selectable themes in display order: four dark palettes, then two light ones. */
val ZeroConThemes: List<ZeroConTheme> = listOf(
    ZeroConTheme("dark_blue", "ダークブルー", ZeroConDarkBluePalette),
    ZeroConTheme("graphite", "グラファイト", ZeroConGraphitePalette),
    ZeroConTheme("forest", "フォレスト", ZeroConForestPalette),
    ZeroConTheme("plum", "プラム", ZeroConPlumPalette),
    ZeroConTheme("sakura", "サクラ", ZeroConSakuraPalette),
    ZeroConTheme("paper", "ペーパー", ZeroConPaperPalette)
)

fun zeroConThemeById(id: String): ZeroConTheme? = ZeroConThemes.firstOrNull { it.id == id }

/**
 * Resolves the stored theme setting to a concrete theme id.
 * "system" follows the device dark mode; legacy "dark"/"light" values from
 * earlier builds map to the default dark and light palettes.
 */
fun resolveZeroConThemeId(themeMode: String, systemDark: Boolean): String = when (themeMode) {
    ZERO_CON_THEME_SYSTEM, "" -> if (systemDark) ZERO_CON_THEME_DEFAULT_DARK else ZERO_CON_THEME_DEFAULT_LIGHT
    "dark" -> ZERO_CON_THEME_DEFAULT_DARK
    "light" -> ZERO_CON_THEME_DEFAULT_LIGHT
    else -> if (zeroConThemeById(themeMode) != null) themeMode else ZERO_CON_THEME_DEFAULT_DARK
}

fun resolveZeroConTheme(themeMode: String, systemDark: Boolean): ZeroConTheme =
    zeroConThemeById(resolveZeroConThemeId(themeMode, systemDark)) ?: ZeroConThemes.first()

@Composable
fun rememberZeroConPalette(themeMode: String = ZERO_CON_THEME_SYSTEM): ZeroConPalette =
    resolveZeroConTheme(themeMode, isSystemInDarkTheme()).palette

fun ZeroConPalette.toColorScheme(): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = primaryAccent,
        onPrimary = onAccent,
        primaryContainer = primaryAccent.copy(alpha = 0.18f),
        onPrimaryContainer = textPrimary,
        secondary = surfaceElevated,
        onSecondary = textPrimary,
        background = canvas,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        outline = borderSubtle,
        error = danger,
        onError = Color(0xFF1F0A0A)
    )
} else {
    lightColorScheme(
        primary = primaryAccent,
        onPrimary = onAccent,
        primaryContainer = primaryAccent.copy(alpha = 0.12f),
        onPrimaryContainer = textPrimary,
        secondary = surfaceElevated,
        onSecondary = textPrimary,
        background = canvas,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceElevated,
        onSurfaceVariant = textSecondary,
        outline = borderSubtle,
        error = danger,
        onError = Color.White
    )
}

fun zeroConTypography(): Typography {
    val sans = FontFamily.SansSerif
    return Typography(
        titleLarge = TextStyle(
            fontFamily = sans,
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.2).sp
        ),
        titleMedium = TextStyle(
            fontFamily = sans,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold
        ),
        bodyLarge = TextStyle(
            fontFamily = sans,
            fontSize = 14.sp,
            lineHeight = 20.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = sans,
            fontSize = 13.sp,
            lineHeight = 18.sp
        ),
        bodySmall = TextStyle(
            fontFamily = sans,
            fontSize = 12.sp,
            lineHeight = 17.sp
        ),
        labelLarge = TextStyle(
            fontFamily = sans,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium
        ),
        labelMedium = TextStyle(
            fontFamily = sans,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium
        ),
        labelSmall = TextStyle(
            fontFamily = sans,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium
        )
    )
}

/**
 * Renders the unified ZeroCon Obsidian & Platinum Slate Logo:
 * - Obsidian squircle (#10121A -> #1C202C) with subtle rim
 * - Satin platinum "0" capsule (#FFFFFF -> #E2E8F0) with 45-degree conversion slash clipped strictly inside
 */
@Composable
fun ZeroConLogo(
    size: Dp = 48.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val margin = s * 0.03f
        val sqSize = s - margin * 2f
        val sqRadius = s * 0.22f

        // 1. Obsidian Squircle Background (#10121A -> #1C202C)
        val bgBrush = Brush.verticalGradient(
            colors = listOf(Color(0xFF10121A), Color(0xFF1C202C)),
            startY = margin,
            endY = s - margin
        )
        drawRoundRect(
            brush = bgBrush,
            topLeft = Offset(margin, margin),
            size = Size(sqSize, sqSize),
            cornerRadius = CornerRadius(sqRadius, sqRadius)
        )

        // Subtle rim
        drawRoundRect(
            color = Color.White.copy(alpha = 0.16f),
            topLeft = Offset(margin, margin),
            size = Size(sqSize, sqSize),
            cornerRadius = CornerRadius(sqRadius, sqRadius),
            style = Stroke(width = s * 0.025f)
        )

        // 2. Platinum "0" Capsule + 45° Conversion Slash
        val cx = s * 0.5f
        val cy = s * 0.5f
        val ow = s * 0.26f
        val oh = s * 0.34f
        val strokeW = s * 0.115f
        val rCapsule = ow

        val outerRect = Rect(
            left = cx - ow,
            top = cy - oh,
            right = cx + ow,
            bottom = cy + oh
        )
        val outerClipPath = Path().apply {
            addRoundRect(RoundRect(outerRect, CornerRadius(rCapsule, rCapsule)))
        }

        val platinumBrush = Brush.verticalGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFE2E8F0)),
            startY = cy - oh,
            endY = cy + oh
        )

        // Draw "0" ring (inset by half stroke so outer edge matches outerClipPath)
        val ringHalfW = ow - strokeW * 0.5f
        val ringHalfH = oh - strokeW * 0.5f
        val ringRadius = ringHalfW
        drawRoundRect(
            brush = platinumBrush,
            topLeft = Offset(cx - ringHalfW, cy - ringHalfH),
            size = Size(ringHalfW * 2f, ringHalfH * 2f),
            cornerRadius = CornerRadius(ringRadius, ringRadius),
            style = Stroke(width = strokeW)
        )

        // Clip 45-degree conversion slash strictly within the outer capsule
        clipPath(outerClipPath) {
            val span = s * 0.36f
            drawLine(
                brush = platinumBrush,
                start = Offset(cx - span, cy + span),
                end = Offset(cx + span, cy - span),
                strokeWidth = strokeW * 0.9f,
                cap = StrokeCap.Butt
            )
        }
    }
}
