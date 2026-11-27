package com.zeroconvert.ime

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zeroconvert.ime.theme.keyboardThemeBackground
import com.zeroconvert.ime.theme.rememberResolvedKeyboardSkin
import com.zeroconvert.ime.theme.toZeroConPalette
import kotlinx.coroutines.delay

internal const val FloatingKeyboardHandleHeightDp = 20
internal const val FloatingKeyboardTopRailDp = 16
internal const val FloatingKeyboardCornerWidthDp = 48

internal fun floatingKeyboardWidthScale(widthDp: Float): Float =
    (0.86f * widthDp / FloatingKeyboardPlacement.DEFAULT_WIDTH_DP).coerceIn(0.01f, 1.15f)

internal fun floatingKeyboardNaturalHeight(keyHeight: Dp, rowCount: Int): Dp =
    keyboardContentHeight(keyHeight, rowCount) + keyHeight * 1.6f

@Composable
internal fun FloatingKeyboardPanel(
    host: FloatingKeyboardHost,
    floating: Boolean,
    inputViewEpoch: Int,
    themeMode: String,
    customThemeRevision: Long = 0L,
    keyHeightDp: Int,
    maxKeyRows: Int,
    clipboardEditing: Boolean,
    onDock: () -> Unit,
    keyboard: @Composable () -> Unit
) {
    val resolvedSkin = rememberResolvedKeyboardSkin(themeMode, customThemeRevision)
    val keyboardTheme = resolvedSkin.spec
    val backgroundImage = resolvedSkin.backgroundImage
    val palette = remember(keyboardTheme) { keyboardTheme.toZeroConPalette() }
    val barColor = if (keyboardTheme.background.kind == com.zeroconvert.ime.theme.BackgroundKind.IMAGE && keyboardTheme.background.applyToBars) {
        palette.surface.copy(alpha = keyboardTheme.bars.backgroundAlpha)
    } else {
        palette.surface.copy(alpha = if (keyboardTheme.background.applyToBars) keyboardTheme.bars.backgroundAlpha else 1f)
    }
    val shape = RoundedCornerShape(14.dp)
    var showCorners by remember(inputViewEpoch, floating) { mutableStateOf(false) }
    val activeCorner = host.activeResizeCorner
    val gestureActive = activeCorner != null || host.isMoving
    val revealCount = host.cornerRevealCount
    val initialRevealCount = remember(inputViewEpoch, floating) { revealCount }
    LaunchedEffect(gestureActive, revealCount, initialRevealCount) {
        if (revealCount != initialRevealCount) {
            showCorners = true
            if (!gestureActive) {
                delay(1500)
                showCorners = false
            }
        }
    }
    val cornerEmphasis by animateFloatAsState(if (showCorners || gestureActive) 1f else 0f, label = "resize corners")
    val borderColor = if (host.dockingPreview) palette.primaryAccent
        else lerp(palette.borderSubtle, palette.primaryAccent.copy(alpha = 0.58f), cornerEmphasis)
    Box(
        modifier = if (floating) {
            Modifier.fillMaxSize().clip(shape).keyboardThemeBackground(keyboardTheme.background, backgroundImage).border(1.dp, borderColor, shape)
        } else {
            Modifier
        }
    ) {
        Column {
            if (floating) {
                Spacer(Modifier.fillMaxWidth().height(FloatingKeyboardTopRailDp.dp).background(barColor))
            }
            BoxWithConstraints(Modifier.then(if (floating) Modifier.weight(1f).fillMaxWidth() else Modifier)) {
                val density = LocalDensity.current
                val scale = if (floating) {
                    val keyboardHeight = floatingKeyboardNaturalHeight(keyHeightDp.dp, maxKeyRows)
                    val editorHeight = if (clipboardEditing) 236.dp else 0.dp
                    minOf(floatingKeyboardWidthScale(maxWidth.value), maxHeight / (keyboardHeight + editorHeight))
                        .coerceAtLeast(0.01f)
                } else {
                    1f
                }
                CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
                    keyboard()
                }
            }
            if (floating) {
                FloatingKeyboardMoveHandle(host, palette, barColor, onDock)
            }
        }
        if (floating && cornerEmphasis > 0f) {
            for (corner in FloatingKeyboardCorner.entries) {
                val alignment = when (corner) {
                    FloatingKeyboardCorner.TOP_LEFT -> AbsoluteAlignment.TopLeft
                    FloatingKeyboardCorner.TOP_RIGHT -> AbsoluteAlignment.TopRight
                    FloatingKeyboardCorner.BOTTOM_LEFT -> AbsoluteAlignment.BottomLeft
                    FloatingKeyboardCorner.BOTTOM_RIGHT -> AbsoluteAlignment.BottomRight
                }
                FloatingKeyboardCornerMark(
                    corner,
                    cornerEmphasis,
                    activeCorner == corner,
                    palette,
                    Modifier.align(alignment)
                )
            }
        }
    }
}

@Composable
private fun FloatingKeyboardCornerMark(
    corner: FloatingKeyboardCorner,
    emphasis: Float,
    active: Boolean,
    palette: ZeroConPalette,
    modifier: Modifier
) {
    val color = palette.primaryAccent.copy(alpha = emphasis)
    Canvas(modifier.size(width = 28.dp, height = FloatingKeyboardTopRailDp.dp)) {
        fun x(distance: Float): Float = distance.dp.toPx().let { if (corner.left) it else size.width - it }
        fun y(distance: Float): Float = distance.dp.toPx().let { if (corner.top) it else size.height - it }
        val outline = Path().apply {
            moveTo(x(23f), y(4f))
            lineTo(x(13f), y(4f))
            cubicTo(x(8f), y(4f), x(4f), y(8f), x(4f), y(12f))
        }
        drawPath(outline, color, style = Stroke(width = (if (active) 3.5.dp else 2.dp).toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun FloatingKeyboardMoveHandle(
    host: FloatingKeyboardHost,
    palette: ZeroConPalette,
    barColor: Color,
    onDock: () -> Unit
) {
    Box(
        Modifier.fillMaxWidth().height(FloatingKeyboardHandleHeightDp.dp).background(barColor)
    ) {
        Box(
            modifier = Modifier.align(Alignment.Center).fillMaxHeight().width(128.dp)
                .semantics {
                    contentDescription = "キーボード移動ハンドル"
                    customActions = listOf(CustomAccessibilityAction("画面下部に固定") {
                        onDock()
                        true
                    })
                }
                .pointerInput(host) {
                    detectDragGestures(
                        onDragStart = { host.startMove() },
                        onDragEnd = { host.finishMove() },
                        onDragCancel = { host.cancelMove() }
                    ) { change, delta ->
                        change.consume()
                        host.moveBy(delta.x, delta.y)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(width = 96.dp, height = 4.dp).background(
                    color = if (host.dockingPreview) palette.primaryAccent else palette.textSecondary,
                    shape = RoundedCornerShape(percent = 50)
                )
            )
        }
    }
}
