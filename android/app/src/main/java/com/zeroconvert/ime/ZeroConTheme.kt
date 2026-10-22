package com.zeroconvert.ime

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class ZeroConPalette(
    val isDark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val keyNormal: Color,
    val keyModifier: Color,
    val primaryAccent: Color,
    val primaryAccentSoft: Color,
    val readyEmerald: Color,
    val warningAmber: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val borderSubtle: Color
)

val ZeroConDarkPalette = ZeroConPalette(
    isDark = true,
    canvas = Color(0xFF101218),
    surface = Color(0xFF161922),
    surfaceElevated = Color(0xFF1C202C),
    keyNormal = Color(0xFF242834),
    keyModifier = Color(0xFF1B1F2A),
    primaryAccent = Color(0xFF3B82F6),
    primaryAccentSoft = Color(0xFF60A5FA),
    readyEmerald = Color(0xFF34D399),
    warningAmber = Color(0xFFFBBF24),
    textPrimary = Color(0xFFF8FAFC),
    textSecondary = Color(0xFFCBD5E1),
    textMuted = Color(0xFF64748B),
    borderSubtle = Color(0xFF282E3D)
)

val ZeroConLightPalette = ZeroConPalette(
    isDark = false,
    canvas = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF1F5F9),
    keyNormal = Color(0xFFFFFFFF),
    keyModifier = Color(0xFFE2E8F0),
    primaryAccent = Color(0xFF2563EB),
    primaryAccentSoft = Color(0xFF3B82F6),
    readyEmerald = Color(0xFF059669),
    warningAmber = Color(0xFFD97706),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF94A3B8),
    borderSubtle = Color(0xFFE2E8F0)
)

@Composable
fun rememberZeroConPalette(themeMode: String = "system"): ZeroConPalette {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    return if (useDark) ZeroConDarkPalette else ZeroConLightPalette
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
