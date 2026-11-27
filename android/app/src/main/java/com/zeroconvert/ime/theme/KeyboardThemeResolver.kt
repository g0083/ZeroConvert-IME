package com.zeroconvert.ime.theme

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.zeroconvert.ime.ZERO_CON_THEME_SYSTEM
import com.zeroconvert.ime.ZeroConPalette
import com.zeroconvert.ime.ZeroConvertInputMethodService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object ThemePreferences {
    fun resolveMigratedThemeValue(primary: String?, legacy: String?): String {
        val p = primary?.trim().orEmpty()
        if (p.isNotEmpty()) return p
        val l = legacy?.trim().orEmpty()
        if (l.isNotEmpty()) return l
        return ZERO_CON_THEME_SYSTEM
    }

    fun readAppThemeMode(prefs: SharedPreferences): String = resolveMigratedThemeValue(
        primary = prefs.getString(ZeroConvertInputMethodService.KEY_APP_THEME_MODE, null),
        legacy = prefs.getString(ZeroConvertInputMethodService.KEY_THEME_MODE, null)
    )

    fun readKeyboardThemeId(prefs: SharedPreferences): String = resolveMigratedThemeValue(
        primary = prefs.getString(ZeroConvertInputMethodService.KEY_KEYBOARD_THEME_ID, null),
        legacy = prefs.getString(ZeroConvertInputMethodService.KEY_THEME_MODE, null)
    )
}

data class ResolvedKeyboardSkin(
    val spec: KeyboardThemeSpec,
    val backgroundImage: ImageBitmap? = null
)

fun Long.toComposeColor(): Color = Color((this and 0xFFFFFFFFL).toInt())

fun KeyboardThemeSpec.toZeroConPalette(): ZeroConPalette {
    val canvasColor = when (background.kind) {
        BackgroundKind.LINEAR_GRADIENT -> background.gradientStartColor.toComposeColor()
        BackgroundKind.SOLID, BackgroundKind.IMAGE -> background.solidColor.toComposeColor()
    }
    return ZeroConPalette(
        isDark = isDark,
        canvas = canvasColor,
        surface = bars.backgroundColor.toComposeColor(),
        surfaceElevated = popup.backgroundColor.toComposeColor(),
        keyNormal = keySurface.normalKeyFill.toComposeColor(),
        keyModifier = keySurface.modifierKeyFill.toComposeColor(),
        primaryAccent = keySurface.accentKeyFill.toComposeColor(),
        primaryAccentSoft = bars.aiCandidateTextColor.toComposeColor(),
        onAccent = typography.accentTextColor.toComposeColor(),
        readyEmerald = if (isDark) Color(0xFF34D399) else Color(0xFF047857),
        warningAmber = if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309),
        danger = if (isDark) Color(0xFFF87171) else Color(0xFFB91C1C),
        textPrimary = typography.primaryTextColor.toComposeColor(),
        textSecondary = typography.secondaryTextColor.toComposeColor(),
        textMuted = typography.hintTextColor.toComposeColor(),
        borderSubtle = bars.dividerColor.toComposeColor()
    )
}

@Composable
fun rememberCustomThemes(customThemeRevision: Long = 0L): List<KeyboardThemeSpec> {
    val context = LocalContext.current
    val themes by produceState<List<KeyboardThemeSpec>>(
        initialValue = emptyList(),
        context,
        customThemeRevision
    ) {
        value = withContext(Dispatchers.IO) {
            CustomThemeRepository.forContext(context).listThemes()
        }
    }
    return themes
}

@Composable
fun rememberKeyboardTheme(
    themeId: String = ZERO_CON_THEME_SYSTEM,
    customThemes: List<KeyboardThemeSpec> = emptyList()
): KeyboardThemeSpec {
    val systemDark = isSystemInDarkTheme()
    return remember(themeId, systemDark, customThemes) {
        KeyboardThemeCatalog.resolve(themeId, systemDark, customThemes)
    }
}

@Composable
fun rememberResolvedKeyboardSkin(
    themeId: String = ZERO_CON_THEME_SYSTEM,
    customThemeRevision: Long = 0L
): ResolvedKeyboardSkin {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val customThemes = rememberCustomThemes(customThemeRevision)
    val spec = remember(themeId, systemDark, customThemes) {
        KeyboardThemeCatalog.resolve(themeId, systemDark, customThemes)
    }
    val fileName = spec.background.imageFileName
    val imageKey = if (spec.background.kind == BackgroundKind.IMAGE && fileName != null) {
        ThemeBackgroundCache.key(spec.id, fileName, customThemeRevision)
    } else {
        null
    }
    val cachedImmediate = remember(imageKey) {
        imageKey?.let { ThemeBackgroundCache.get(it) }
    }
    val loadedImage by produceState<ImageBitmap?>(
        initialValue = cachedImmediate,
        context,
        spec.id,
        spec.background.kind,
        fileName,
        customThemeRevision
    ) {
        value = if (spec.background.kind == BackgroundKind.IMAGE && fileName != null) {
            ThemeBackgroundCache.get(ThemeBackgroundCache.key(spec.id, fileName, customThemeRevision))
                ?: withContext(Dispatchers.IO) {
                    ThemeBackgroundCache.loadRenderedImage(context, spec.id, fileName, customThemeRevision)
                }
        } else {
            null
        }
    }
    return remember(spec, loadedImage) {
        ResolvedKeyboardSkin(spec = spec, backgroundImage = loadedImage)
    }
}

fun Modifier.keyboardThemeBackground(
    background: BackgroundStyle,
    backgroundImage: ImageBitmap? = null
): Modifier = when (background.kind) {
    BackgroundKind.SOLID ->
        this.background(background.solidColor.toComposeColor())
    BackgroundKind.LINEAR_GRADIENT -> {
        val startColor = background.gradientStartColor.toComposeColor()
        val endColor = background.gradientEndColor.toComposeColor()
        val angleRad = (background.gradientAngleDeg * PI / 180.0)
        val dx = cos(angleRad).toFloat()
        val dy = sin(angleRad).toFloat()
        this.drawWithCache {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val halfSpanX = size.width / 2f
            val halfSpanY = size.height / 2f
            val start = Offset(cx - dx * halfSpanX, cy - dy * halfSpanY)
            val end = Offset(cx + dx * halfSpanX, cy + dy * halfSpanY)
            val brush = Brush.linearGradient(
                colors = listOf(startColor, endColor),
                start = start,
                end = end
            )
            onDrawBehind {
                drawRect(brush = brush)
            }
        }
    }
    BackgroundKind.IMAGE -> {
        val fallbackColor = background.solidColor.toComposeColor()
        val scrimColor = background.dimColor.toComposeColor().copy(alpha = background.dimAlpha)
        if (backgroundImage == null) {
            this.background(fallbackColor).drawWithCache {
                onDrawBehind {
                    if (scrimColor.alpha > 0f) drawRect(color = scrimColor)
                }
            }
        } else {
            this.drawWithCache {
                val dstW = size.width.roundToInt().coerceAtLeast(1)
                val dstH = size.height.roundToInt().coerceAtLeast(1)
                val crop = ThemeBackgroundProcessor.computeCenterCropSourceRect(
                    imgWidth = backgroundImage.width,
                    imgHeight = backgroundImage.height,
                    dstWidth = dstW,
                    dstHeight = dstH
                )
                onDrawBehind {
                    drawRect(color = fallbackColor)
                    drawImage(
                        image = backgroundImage,
                        srcOffset = IntOffset(crop.left, crop.top),
                        srcSize = IntSize(crop.width, crop.height),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(dstW, dstH)
                    )
                    if (scrimColor.alpha > 0f) {
                        drawRect(color = scrimColor)
                    }
                }
            }
        }
    }
}

fun KeyTypographyStyle.textShadowOrNull(): Shadow? = if (textShadowEnabled) {
    Shadow(
        color = Color.Black.copy(alpha = 0.55f),
        offset = Offset(0f, 1.5f),
        blurRadius = 3f
    )
} else {
    null
}
