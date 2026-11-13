package com.zeroconvert.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/** Invoked strictly on initial pointer-down for press-only haptic feedback. */
val LocalOnKeyPressHaptic = staticCompositionLocalOf<() -> Unit> { {} }

/** Controls whether QWERTY character keys display a floating enlarged key preview. */
val LocalKeyPreviewEnabled = staticCompositionLocalOf { true }

/** Flick distance threshold in dp shared across 12-key flicks and QWERTY top-row up-flicks. */
val LocalFlickThresholdDp = staticCompositionLocalOf { 18.dp }

/** Controls subtle keycap borders and bottom depth rims across keyboard themes. */
val LocalKeyBordersEnabled = staticCompositionLocalOf { true }

/** Controls dynamic next-key hit expansion and spatial touch-coordinate typo correction. */
val LocalDynamicTouchAssistEnabled = staticCompositionLocalOf { true }

/** Current theme palette exposed to shared keyboard controls without forcing parameter churn. */
val LocalZeroConPalette = staticCompositionLocalOf { ZeroConGraphitePalette }

data class KeyCapColors(
    val fill: Color,
    val border: Color,
    val topHighlight: Color,
    val bottomRim: Color
)

/**
 * Resolves coherent unpressed/pressed fill, subtle border, top highlight, and bottom rim colors
 * for both dark and light [ZeroConPalette] variants.
 */
fun resolveKeyCapColors(
    baseColor: Color,
    palette: ZeroConPalette,
    isPressed: Boolean,
    isAccent: Boolean = false
): KeyCapColors {
    val isDarkSurface = palette.isDark && baseColor.luminance() < 0.45f
    val fill = when {
        isPressed && isAccent -> {
            if (isDarkSurface) lerp(baseColor, Color.White, 0.22f)
            else lerp(baseColor, Color.Black, 0.14f)
        }
        isPressed -> {
            val accented = lerp(baseColor, palette.primaryAccent, if (isDarkSurface) 0.30f else 0.20f)
            if (isDarkSurface) lerp(accented, Color.White, 0.08f) else lerp(accented, Color.Black, 0.05f)
        }
        else -> baseColor
    }
    val border = when {
        isPressed -> palette.primaryAccent.copy(alpha = 0.72f)
        isAccent -> lerp(baseColor, if (isDarkSurface) Color.White else Color.Black, 0.16f).copy(alpha = 0.55f)
        isDarkSurface -> lerp(palette.borderSubtle, Color.White, 0.10f).copy(alpha = 0.68f)
        else -> palette.borderSubtle.copy(alpha = 0.90f)
    }
    val topHighlight = when {
        isPressed -> Color.Transparent
        isAccent -> Color.White.copy(alpha = 0.18f)
        isDarkSurface -> Color.White.copy(alpha = 0.07f)
        else -> Color.White.copy(alpha = 0.55f)
    }
    val bottomRim = when {
        isPressed -> Color.Transparent
        isAccent -> lerp(baseColor, Color.Black, 0.30f).copy(alpha = 0.55f)
        isDarkSurface -> Color.Black.copy(alpha = 0.36f)
        else -> lerp(baseColor, Color.Black, 0.28f).copy(alpha = 0.22f)
    }
    return KeyCapColors(
        fill = fill,
        border = border,
        topHighlight = topHighlight,
        bottomRim = bottomRim
    )
}

/**
 * Separates a key's outer zero-deadzone touch hitbox ([modifier]) from its visual keycap margins
 * ([visualPadding]), drawing the background, pressed highlight, subtle border, and 3D bottom rim.
 */
@Composable
fun KeyCapFrame(
    modifier: Modifier = Modifier,
    visualPadding: PaddingValues = PaddingValues(horizontal = 2.5.dp, vertical = 2.dp),
    cornerRadius: Dp = 7.dp,
    baseColor: Color,
    isPressed: Boolean,
    isAccent: Boolean = false,
    bordersEnabled: Boolean = LocalKeyBordersEnabled.current,
    palette: ZeroConPalette = LocalZeroConPalette.current,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = resolveKeyCapColors(
        baseColor = baseColor,
        palette = palette,
        isPressed = isPressed,
        isAccent = isAccent
    )
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(visualPadding)
                .clip(shape)
                .background(colors.fill)
                .then(
                    if (bordersEnabled) {
                        Modifier
                            .drawWithContent {
                                drawContent()
                                val radiusPx = cornerRadius.toPx()
                                if (!isPressed && colors.topHighlight.alpha > 0f) {
                                    val highlightH = 0.75.dp.toPx()
                                    drawRoundRect(
                                        color = colors.topHighlight,
                                        topLeft = Offset.Zero,
                                        size = Size(size.width, highlightH + radiusPx * 0.2f),
                                        cornerRadius = CornerRadius(radiusPx, radiusPx)
                                    )
                                }
                                if (!isPressed && colors.bottomRim.alpha > 0f) {
                                    val rimH = 1.6.dp.toPx()
                                    drawRect(
                                        color = colors.bottomRim,
                                        topLeft = Offset(0f, size.height - rimH),
                                        size = Size(size.width, rimH)
                                    )
                                }
                            }
                            .border(width = 0.8.dp, color = colors.border, shape = shape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = contentAlignment,
            content = content
        )
    }
}

/**
 * Awaits release or cancellation of [pointerId] without cancelling prematurely on minor
 * finger jitter inside the button bounds + [slopForgivenessPx].
 */
suspend fun AwaitPointerEventScope.awaitPointerReleaseInBounds(
    pointerId: PointerId,
    widthPx: Float,
    heightPx: Float,
    slopForgivenessPx: Float
): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return false
        if (!change.pressed) {
            if (change.isConsumed) return false
            val pos = change.position
            val inside = pos.x >= -slopForgivenessPx &&
                pos.x <= widthPx + slopForgivenessPx &&
                pos.y >= -slopForgivenessPx &&
                pos.y <= heightPx + slopForgivenessPx
            if (inside) {
                change.consume()
            }
            return inside
        }
    }
}

/**
 * Shared full-slot action button providing immediate pressed visual highlight, strict
 * pointer-down-only haptic feedback, optional long-press, and a forgiving release hitbox.
 */
@Composable
fun KeyboardActionKey(
    modifier: Modifier = Modifier,
    visualPadding: PaddingValues = PaddingValues(horizontal = 2.5.dp, vertical = 2.dp),
    cornerRadius: Dp = 7.dp,
    baseColor: Color,
    isAccent: Boolean = false,
    palette: ZeroConPalette = LocalZeroConPalette.current,
    contentDescription: String? = null,
    onBeforePress: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(isPressed: Boolean) -> Unit
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentOnBeforePress by rememberUpdatedState(onBeforePress)
    val haptic by rememberUpdatedState(LocalOnKeyPressHaptic.current)
    val bordersEnabled = LocalKeyBordersEnabled.current
    val longPressTimeoutMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var isPressed by remember { mutableStateOf(false) }

    val semanticsModifier = Modifier.semantics(mergeDescendants = true) {
        role = Role.Button
        if (contentDescription != null) {
            this.contentDescription = contentDescription
        }
        onClick {
            currentOnBeforePress()
            currentOnClick()
            true
        }
        currentOnLongClick?.let { longHandler ->
            onLongClick {
                currentOnBeforePress()
                longHandler()
                true
            }
        }
    }

    KeyCapFrame(
        modifier = modifier
            .then(semanticsModifier)
            .pointerInput(Unit) {
                val forgivenessPx = with(density) { 12.dp.toPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    currentOnBeforePress()
                    haptic()
                    isPressed = true

                    var longPressTriggered = false
                    var longPressJob: Job? = null
                    val longHandler = currentOnLongClick
                    if (longHandler != null) {
                        longPressJob = scope.launch {
                            delay(longPressTimeoutMs)
                            longPressTriggered = true
                            longHandler()
                        }
                    }

                    val releasedInside = try {
                        awaitPointerReleaseInBounds(
                            pointerId = down.id,
                            widthPx = size.width.toFloat(),
                            heightPx = size.height.toFloat(),
                            slopForgivenessPx = forgivenessPx
                        )
                    } finally {
                        longPressJob?.cancel()
                        isPressed = false
                    }

                    if (releasedInside && !longPressTriggered) {
                        currentOnClick()
                    }
                }
            },
        visualPadding = visualPadding,
        cornerRadius = cornerRadius,
        baseColor = baseColor,
        isPressed = isPressed,
        isAccent = isAccent,
        bordersEnabled = bordersEnabled,
        palette = palette
    ) {
        content(isPressed)
    }
}

/**
 * Floating enlarged character preview bubble drawn in-window inside the keyboard Box
 * strictly above the pressed QWERTY key. Never renders secondary up-flick labels so it stays
 * distinct from [FlickPopupGuide].
 */
@Composable
fun QwertyKeyPreviewBubble(
    label: String,
    targetRect: KeyRect,
    rootWidthPx: Float,
    rootHeightPx: Float,
    palette: ZeroConPalette
) {
    val density = LocalDensity.current
    val bubbleWidthDp = with(density) { max(targetRect.width + 10.dp.toPx(), 46.dp.toPx()).toDp() }
    val bubbleHeightDp = 50.dp
    val gapPx = with(density) { 6.dp.toPx() }
    val marginPx = with(density) { 4.dp.toPx() }
    val bubbleWidthPx = with(density) { bubbleWidthDp.toPx() }
    val bubbleHeightPx = with(density) { bubbleHeightDp.toPx() }

    val x = if (rootWidthPx > 0f) {
        (targetRect.centerX - bubbleWidthPx / 2f)
            .coerceIn(marginPx, (rootWidthPx - bubbleWidthPx - marginPx).coerceAtLeast(marginPx))
    } else {
        targetRect.centerX - bubbleWidthPx / 2f
    }
    // Never clamp against top edge (0f): Row 0 previews intentionally float above the key area.
    val rawY = targetRect.top - bubbleHeightPx - gapPx
    val y = if (rootHeightPx > 0f) {
        rawY.coerceAtMost(rootHeightPx - bubbleHeightPx - marginPx)
    } else {
        rawY
    }

    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(98f)
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(width = bubbleWidthDp, height = bubbleHeightDp)
                .shadow(elevation = 6.dp, shape = shape, clip = false)
                .clip(shape)
                .background(palette.surfaceElevated)
                .border(1.dp, palette.primaryAccent.copy(alpha = 0.55f), shape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = palette.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/**
 * Adjusts outer horizontal visual padding of edge keys in a row so 3dp outer visual margins
 * stay pleasing without leaving screen-edge dead zones outside the touch hitboxes.
 */
fun keyRowVisualPadding(
    isFirstInRow: Boolean = false,
    isLastInRow: Boolean = false,
    baseHorizontal: Dp = 2.5.dp,
    edgeExtraHorizontal: Dp = 3.dp,
    top: Dp = 3.dp,
    bottom: Dp = 3.dp
): PaddingValues = PaddingValues(
    start = if (isFirstInRow) baseHorizontal + edgeExtraHorizontal else baseHorizontal,
    end = if (isLastInRow) baseHorizontal + edgeExtraHorizontal else baseHorizontal,
    top = top,
    bottom = bottom
)

/** Publishes current keyboard visual and haptic ergonomics preferences via CompositionLocals. */
@Composable
fun ProvideKeyboardErgonomicsLocals(
    palette: ZeroConPalette,
    onKeyPressHaptic: () -> Unit,
    keyPreviewEnabled: Boolean,
    flickThresholdDp: Dp,
    keyBordersEnabled: Boolean,
    dynamicTouchAssistEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalZeroConPalette provides palette,
        LocalOnKeyPressHaptic provides onKeyPressHaptic,
        LocalKeyPreviewEnabled provides keyPreviewEnabled,
        LocalFlickThresholdDp provides flickThresholdDp,
        LocalKeyBordersEnabled provides keyBordersEnabled,
        LocalDynamicTouchAssistEnabled provides dynamicTouchAssistEnabled,
        content = content
    )
}

/**
 * Fires press-only haptic feedback in [PointerEventPass.Initial] on pointer down without
 * consuming events, suitable for non-scrolling toolbar/assist tap targets.
 */
@Composable
fun Modifier.pointerDownHaptic(): Modifier {
    val haptic by rememberUpdatedState(LocalOnKeyPressHaptic.current)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            haptic()
        }
    }
}
