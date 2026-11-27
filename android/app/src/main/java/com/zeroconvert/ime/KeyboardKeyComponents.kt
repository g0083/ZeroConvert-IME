package com.zeroconvert.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import com.zeroconvert.ime.theme.KeyStylePreset
import com.zeroconvert.ime.theme.KeyboardThemeCatalog
import com.zeroconvert.ime.theme.KeyboardThemeSpec
import com.zeroconvert.ime.theme.toComposeColor
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
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

/** Full keyboard skin specification exposed to keycaps, bars, and popups. */
val LocalKeyboardTheme = staticCompositionLocalOf { KeyboardThemeCatalog.defaultDark }

data class KeyCapColors(
    val fill: Color,
    val border: Color,
    val topHighlight: Color,
    val bottomRim: Color,
    val dropShadow: Color = Color.Transparent
)

/**
 * Resolves coherent unpressed/pressed fill, subtle border, top highlight, bottom rim, and
 * drop shadow colors for all [KeyStylePreset] and [ZeroConPalette] variants.
 */
fun resolveKeyCapColors(
    baseColor: Color,
    palette: ZeroConPalette,
    isPressed: Boolean,
    isAccent: Boolean = false,
    isModifier: Boolean = false,
    theme: KeyboardThemeSpec = KeyboardThemeCatalog.defaultDark
): KeyCapColors {
    val surface = theme.keySurface
    val fillAlpha = when {
        isAccent -> if (surface.preset == KeyStylePreset.BORDERLESS) maxOf(surface.keyFillAlpha, 0.90f) else 1.0f
        isModifier -> surface.modifierKeyAlpha
        else -> surface.keyFillAlpha
    }
    val effectiveBase = baseColor.copy(alpha = (baseColor.alpha * fillAlpha).coerceIn(0f, 1f))
    val isDarkSurface = palette.isDark && baseColor.luminance() < 0.45f
    val accentColor = palette.primaryAccent
    val customPressed = surface.pressedKeyFill?.toComposeColor()

    val fill = when {
        isPressed && customPressed != null && !isAccent -> customPressed
        isPressed && isAccent -> {
            if (isDarkSurface) lerp(baseColor, Color.White, 0.22f)
            else lerp(baseColor, Color.Black, 0.14f)
        }
        isPressed -> {
            when (surface.preset) {
                KeyStylePreset.OUTLINED_GLASS, KeyStylePreset.BORDERLESS -> {
                    val blended = lerp(baseColor, accentColor, if (isDarkSurface) 0.42f else 0.30f)
                    blended.copy(alpha = maxOf(fillAlpha + 0.25f, 0.52f).coerceAtMost(0.92f))
                }
                else -> {
                    val accented = lerp(baseColor, accentColor, if (isDarkSurface) 0.30f else 0.20f)
                    val adjusted = if (isDarkSurface) lerp(accented, Color.White, 0.08f) else lerp(accented, Color.Black, 0.05f)
                    adjusted.copy(alpha = maxOf(fillAlpha, 0.72f).coerceAtMost(1f))
                }
            }
        }
        else -> effectiveBase
    }

    val themeBorderColor = surface.borderColor.toComposeColor()
    val border = when (surface.preset) {
        KeyStylePreset.BEVEL_3D -> when {
            isPressed -> accentColor.copy(alpha = 0.72f)
            isAccent -> lerp(baseColor, if (isDarkSurface) Color.White else Color.Black, 0.16f).copy(alpha = 0.45f)
            isDarkSurface -> lerp(palette.borderSubtle, Color.White, 0.08f).copy(alpha = 0.55f)
            else -> palette.borderSubtle.copy(alpha = 0.78f)
        }
        KeyStylePreset.IOS_SHADOW, KeyStylePreset.MATERIAL_FLAT -> when {
            isPressed -> accentColor.copy(alpha = 0.65f)
            surface.borderWidthDp > 0f && surface.borderAlpha > 0f ->
                themeBorderColor.copy(alpha = surface.borderAlpha)
            else -> Color.Transparent
        }
        KeyStylePreset.OUTLINED_GLASS -> when {
            isPressed -> accentColor.copy(alpha = 0.90f)
            isAccent -> lerp(baseColor, Color.White, 0.2f).copy(alpha = 0.45f)
            else -> themeBorderColor.copy(alpha = surface.borderAlpha)
        }
        KeyStylePreset.BORDERLESS -> Color.Transparent
    }

    val bevelScale = surface.bevelIntensity.coerceIn(0f, 1.5f)
    val topHighlight = if (surface.preset == KeyStylePreset.BEVEL_3D && !isPressed && bevelScale > 0f) {
        val baseAlpha = when {
            isAccent -> 0.14f
            isDarkSurface -> 0.055f
            else -> 0.45f
        }
        Color.White.copy(alpha = (baseAlpha * bevelScale).coerceIn(0f, 1f))
    } else {
        Color.Transparent
    }

    val bottomRim = if (surface.preset == KeyStylePreset.BEVEL_3D && !isPressed && bevelScale > 0f) {
        when {
            isAccent -> lerp(baseColor, Color.Black, 0.30f).copy(alpha = (0.45f * bevelScale).coerceIn(0f, 1f))
            isDarkSurface -> Color.Black.copy(alpha = (0.30f * bevelScale).coerceIn(0f, 1f))
            else -> lerp(baseColor, Color.Black, 0.28f).copy(alpha = (0.18f * bevelScale).coerceIn(0f, 1f))
        }
    } else {
        Color.Transparent
    }

    val dropShadow = if (surface.preset == KeyStylePreset.IOS_SHADOW && !isPressed && surface.shadowElevationDp > 0f) {
        surface.shadowColor.toComposeColor().copy(alpha = if (palette.isDark) 0.45f else 0.28f)
    } else {
        Color.Transparent
    }

    return KeyCapColors(
        fill = fill,
        border = border,
        topHighlight = topHighlight,
        bottomRim = bottomRim,
        dropShadow = dropShadow
    )
}

/**
 * Clamps [requestedRadiusPx] to at most half the shorter edge so bevel path geometry matches
 * Compose's rendered [RoundedCornerShape] on circular/pill keys as well as rectangular keys.
 */
internal fun effectiveKeyCapCornerRadiusPx(
    widthPx: Float,
    heightPx: Float,
    requestedRadiusPx: Float
): Float = min(requestedRadiusPx.coerceAtLeast(0f), min(widthPx, heightPx).coerceAtLeast(0f) / 2f)

internal fun resolve12KeyEnterCornerRadiusDp(enterCornerRadiusDp: Float): Float =
    enterCornerRadiusDp.coerceAtMost(12f)

/**
 * Separates a key's outer zero-deadzone touch hitbox ([modifier]) from its visual keycap margins
 * ([visualPadding]), drawing the background, pressed highlight, subtle border, drop shadow, and 3D bottom rim.
 */
@Composable
fun KeyCapFrame(
    modifier: Modifier = Modifier,
    visualPadding: PaddingValues = PaddingValues(horizontal = 2.5.dp, vertical = 2.dp),
    cornerRadius: Dp? = null,
    baseColor: Color,
    isPressed: Boolean,
    isAccent: Boolean = false,
    isModifier: Boolean = false,
    bordersEnabled: Boolean = LocalKeyBordersEnabled.current,
    palette: ZeroConPalette = LocalZeroConPalette.current,
    theme: KeyboardThemeSpec = LocalKeyboardTheme.current,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val resolvedCornerRadius = cornerRadius ?: if (isAccent) {
        theme.keySurface.enterCornerRadiusDp.dp
    } else {
        theme.keySurface.cornerRadiusDp.dp
    }
    val colors = resolveKeyCapColors(
        baseColor = baseColor,
        palette = palette,
        isPressed = isPressed,
        isAccent = isAccent,
        isModifier = isModifier,
        theme = theme
    )
    val currentIsPressed by rememberUpdatedState(isPressed)
    val currentColors by rememberUpdatedState(colors)
    val currentCornerRadius by rememberUpdatedState(resolvedCornerRadius)
    val currentPreset by rememberUpdatedState(theme.keySurface.preset)
    val currentShadowDp by rememberUpdatedState(theme.keySurface.shadowElevationDp.dp)
    val shape = RoundedCornerShape(resolvedCornerRadius)
    val borderWidth = if (theme.keySurface.preset == KeyStylePreset.BEVEL_3D) {
        0.65.dp
    } else {
        theme.keySurface.borderWidthDp.dp
    }
    val showBorder = when (theme.keySurface.preset) {
        KeyStylePreset.OUTLINED_GLASS -> colors.border.alpha > 0f && borderWidth > 0.dp
        KeyStylePreset.BORDERLESS -> false
        else -> bordersEnabled && colors.border.alpha > 0f && borderWidth > 0.dp
    }
    val showBevel = bordersEnabled && theme.keySurface.preset == KeyStylePreset.BEVEL_3D
    val showShadow = bordersEnabled &&
        theme.keySurface.preset == KeyStylePreset.IOS_SHADOW &&
        theme.keySurface.shadowElevationDp > 0f

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(visualPadding)
                .then(
                    if (showShadow) {
                        Modifier.drawBehind {
                            val latestColors = currentColors
                            if (!currentIsPressed && latestColors.dropShadow.alpha > 0f &&
                                currentPreset == KeyStylePreset.IOS_SHADOW
                            ) {
                                val effectiveRadius = effectiveKeyCapCornerRadiusPx(
                                    widthPx = size.width,
                                    heightPx = size.height,
                                    requestedRadiusPx = currentCornerRadius.toPx()
                                )
                                drawRoundRect(
                                    color = latestColors.dropShadow,
                                    topLeft = Offset(0f, currentShadowDp.toPx()),
                                    size = size,
                                    cornerRadius = CornerRadius(effectiveRadius, effectiveRadius)
                                )
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .clip(shape)
                .background(colors.fill)
                .then(
                    if (showBevel) {
                        Modifier.drawWithCache {
                            if (size.width <= 0f || size.height <= 0f) {
                                onDrawWithContent { drawContent() }
                            } else {
                                val effectiveRadius = effectiveKeyCapCornerRadiusPx(
                                    widthPx = size.width,
                                    heightPx = size.height,
                                    requestedRadiusPx = currentCornerRadius.toPx()
                                )
                                val corner = CornerRadius(effectiveRadius, effectiveRadius)
                                val outerPath = Path().apply {
                                    addRoundRect(RoundRect(0f, 0f, size.width, size.height, corner))
                                }
                                val highlightH = 1.2.dp.toPx()
                                val shiftedDown = Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            left = 0f,
                                            top = highlightH,
                                            right = size.width,
                                            bottom = size.height + highlightH,
                                            cornerRadius = corner
                                        )
                                    )
                                }
                                val topBevelPath = Path.combine(PathOperation.Difference, outerPath, shiftedDown)
                                val rimH = 1.4.dp.toPx()
                                val shiftedUp = Path().apply {
                                    addRoundRect(
                                        RoundRect(
                                            left = 0f,
                                            top = -rimH,
                                            right = size.width,
                                            bottom = size.height - rimH,
                                            cornerRadius = corner
                                        )
                                    )
                                }
                                val bottomBevelPath = Path.combine(PathOperation.Difference, outerPath, shiftedUp)
                                onDrawWithContent {
                                    drawContent()
                                    val latestColors = currentColors
                                    if (!currentIsPressed && latestColors.topHighlight.alpha > 0f) {
                                        drawPath(path = topBevelPath, color = latestColors.topHighlight)
                                    }
                                    if (!currentIsPressed && latestColors.bottomRim.alpha > 0f) {
                                        drawPath(path = bottomBevelPath, color = latestColors.bottomRim)
                                    }
                                }
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (showBorder) {
                        Modifier.border(width = borderWidth, color = colors.border, shape = shape)
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
    slopForgivenessPx: Float,
    horizontalDrag: HorizontalCursorDragTracker? = null
): Boolean = awaitKeyRelease(
    pointerId,
    widthPx,
    heightPx,
    slopForgivenessPx,
    horizontalDrag = horizontalDrag
) == FlickDirection.CENTER

/**
 * Builds the drag tracker for one space-key press. Every space key shares this so that its
 * cursor steps match the arrow keys.
 */
internal fun Density.spaceCursorDragTracker(
    originX: Float,
    onStep: (Int) -> Unit,
    onDragStart: () -> Unit
): HorizontalCursorDragTracker = HorizontalCursorDragTracker(
    stepPx = 12.dp.toPx(),
    startSlopPx = 8.dp.toPx(),
    onStep = onStep,
    onDragStart = onDragStart
).also { it.begin(originX) }

private suspend fun AwaitPointerEventScope.awaitKeyRelease(
    pointerId: PointerId,
    widthPx: Float,
    heightPx: Float,
    slopForgivenessPx: Float,
    flickStart: Offset? = null,
    flickThresholdPx: Float = 0f,
    onFlickPreview: ((FlickDirection?) -> Unit)? = null,
    horizontalDrag: HorizontalCursorDragTracker? = null
): FlickDirection? {
    var direction = FlickDirection.CENTER
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return null
        if (change.isConsumed && (!change.pressed || flickStart != null)) return null
        val pos = change.position
        if (horizontalDrag != null && change.pressed) {
            horizontalDrag.move(pos.x)
            if (horizontalDrag.isDragging) change.consume()
        }
        if (flickStart != null) {
            val nextDirection = calculateFlickDirectionWithHysteresis(
                pos.x - flickStart.x,
                pos.y - flickStart.y,
                flickThresholdPx,
                direction
            )
            if (nextDirection != direction) {
                direction = nextDirection
                onFlickPreview?.invoke(direction)
            }
        }
        if (!change.pressed) {
            // A horizontal drag already moved the cursor; releasing it must not also act as a tap.
            if (horizontalDrag?.isDragging == true) {
                change.consume()
                return null
            }
            when (direction) {
                FlickDirection.RIGHT, FlickDirection.UP -> Unit
                FlickDirection.LEFT, FlickDirection.DOWN -> return null
                FlickDirection.CENTER -> {
                    if (pos.x < -slopForgivenessPx || pos.x > widthPx + slopForgivenessPx ||
                        pos.y < -slopForgivenessPx || pos.y > heightPx + slopForgivenessPx
                    ) return null
                }
            }
            change.consume()
            return direction
        }
        if (direction != FlickDirection.CENTER) {
            change.consume()
        }
    }
}

/**
 * Shared full-slot action button providing immediate pressed visual highlight, strict
 * pointer-down-only haptic feedback, optional long-press or right and up flicks,
 * and a forgiving tap hitbox.
 */
@Composable
fun KeyboardActionKey(
    modifier: Modifier = Modifier,
    visualPadding: PaddingValues = PaddingValues(horizontal = 2.5.dp, vertical = 2.dp),
    cornerRadius: Dp? = null,
    baseColor: Color,
    isAccent: Boolean = false,
    isModifier: Boolean = !isAccent,
    palette: ZeroConPalette = LocalZeroConPalette.current,
    theme: KeyboardThemeSpec = LocalKeyboardTheme.current,
    contentDescription: String? = null,
    onBeforePress: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onFlick: ((FlickDirection) -> Unit)? = null,
    onFlickPreview: ((FlickDirection?) -> Unit)? = null,
    onHorizontalStep: ((Int) -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(isPressed: Boolean) -> Unit
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentOnHorizontalStep by rememberUpdatedState(onHorizontalStep)
    val currentOnFlick by rememberUpdatedState(onFlick)
    val currentOnFlickPreview by rememberUpdatedState(onFlickPreview)
    val currentOnBeforePress by rememberUpdatedState(onBeforePress)
    val haptic by rememberUpdatedState(LocalOnKeyPressHaptic.current)
    val bordersEnabled = LocalKeyBordersEnabled.current
    val longPressTimeoutMs = LocalViewConfiguration.current.longPressTimeoutMillis
    val scope = rememberCoroutineScope()
    val flickThresholdDp by rememberUpdatedState(LocalFlickThresholdDp.current)
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
                val forgivenessPx = 12.dp.toPx()
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

                    // Horizontal drag steps cursor one character per step; a long press cancels the drag start.
                    val dragTracker = if (currentOnHorizontalStep != null) {
                        spaceCursorDragTracker(
                            originX = down.position.x,
                            onStep = { step -> if (!longPressTriggered) currentOnHorizontalStep?.invoke(step) },
                            onDragStart = { longPressJob?.cancel() }
                        )
                    } else {
                        null
                    }

                    val flickPreview = currentOnFlickPreview
                    val direction = try {
                        flickPreview?.invoke(FlickDirection.CENTER)
                        awaitKeyRelease(
                            pointerId = down.id,
                            widthPx = size.width.toFloat(),
                            heightPx = size.height.toFloat(),
                            slopForgivenessPx = forgivenessPx,
                            flickStart = down.position.takeIf { currentOnFlick != null },
                            flickThresholdPx = flickThresholdDp.toPx(),
                            onFlickPreview = flickPreview,
                            horizontalDrag = dragTracker
                        )
                    } finally {
                        longPressJob?.cancel()
                        isPressed = false
                        flickPreview?.invoke(null)
                    }

                    if (direction != null && !longPressTriggered) {
                        if (direction == FlickDirection.CENTER) {
                            currentOnClick()
                        } else {
                            currentOnFlick?.invoke(direction)
                        }
                    }
                }
            },
        visualPadding = visualPadding,
        cornerRadius = cornerRadius,
        baseColor = baseColor,
        isPressed = isPressed,
        isAccent = isAccent,
        isModifier = isModifier,
        bordersEnabled = bordersEnabled,
        palette = palette,
        theme = theme
    ) {
        content(isPressed)
    }
}

/**
 * Floating enlarged character preview bubble drawn in-window inside the keyboard Box
 * directly above the pressed QWERTY key. When [upLabel] is provided, unifies tap and up-flick
 * preview in the same anchored position with the secondary digit/symbol displayed above the
 * primary letter.
 */
@Composable
fun QwertyKeyPreviewBubble(
    label: String,
    targetRect: KeyRect,
    rootWidthPx: Float,
    rootHeightPx: Float,
    palette: ZeroConPalette,
    upLabel: String? = null,
    flickDirection: FlickDirection = FlickDirection.CENTER
) {
    val density = LocalDensity.current
    val resolvedUpLabel = upLabel?.takeIf { it.isNotEmpty() }
    val isUpSelected = resolvedUpLabel != null && flickDirection == FlickDirection.UP
    val bubbleWidthDp = with(density) { max(targetRect.width + 10.dp.toPx(), 46.dp.toPx()).toDp() }
    val bubbleHeightDp = if (resolvedUpLabel != null) 66.dp else 50.dp
    val gapPx = with(density) { 6.dp.toPx() }
    val marginPx = with(density) { 4.dp.toPx() }
    val bubbleWidthPx = with(density) { bubbleWidthDp.toPx() }
    val bubbleHeightPx = with(density) { bubbleHeightDp.toPx() }

    val bounds = computeQwertyPreviewBubbleBounds(
        targetRect = targetRect,
        bubbleWidthPx = bubbleWidthPx,
        bubbleHeightPx = bubbleHeightPx,
        gapPx = gapPx,
        marginPx = marginPx,
        rootWidthPx = rootWidthPx,
        rootHeightPx = rootHeightPx
    )

    val popupStyle = LocalKeyboardTheme.current.popup
    val popupBg = popupStyle.backgroundColor.toComposeColor()
    val popupAccent = popupStyle.accentColor.toComposeColor()
    val popupText = popupStyle.textColor.toComposeColor()
    val popupOnAccent = popupStyle.onAccentColor.toComposeColor()
    val popupBorder = popupStyle.borderColor.toComposeColor()
    val shape = RoundedCornerShape(popupStyle.cornerRadiusDp.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(98f)
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(bounds.leftPx.roundToInt(), bounds.topPx.roundToInt()) }
                .size(width = bubbleWidthDp, height = bubbleHeightDp)
                .shadow(elevation = 6.dp, shape = shape, clip = false)
                .clip(shape)
                .background(popupBg)
                .border(
                    width = if (isUpSelected) 1.5.dp else 1.dp,
                    color = (if (isUpSelected) popupAccent else popupBorder).copy(alpha = if (isUpSelected) 0.9f else 0.75f),
                    shape = shape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (resolvedUpLabel != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(25.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (isUpSelected) popupAccent
                                else palette.keyModifier.copy(alpha = 0.55f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = resolvedUpLabel,
                            color = if (isUpSelected) popupOnAccent else palette.textSecondary,
                            fontSize = if (isUpSelected) 15.sp else 13.sp,
                            fontWeight = if (isUpSelected) FontWeight.Bold else FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isUpSelected) palette.textMuted else popupText,
                            fontSize = if (isUpSelected) 18.sp else 21.sp,
                            fontWeight = if (isUpSelected) FontWeight.Medium else FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                }
            } else {
                Text(
                    text = label,
                    color = popupText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
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
    keyboardTheme: KeyboardThemeSpec = KeyboardThemeCatalog.defaultDark,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalZeroConPalette provides palette,
        LocalKeyboardTheme provides keyboardTheme,
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
