package com.zeroconvert.ime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

private data class ModeSwitchGuide(
    val mode: KeyboardMode,
    val selected: FlickDirection,
    val keyTopCenter: Offset,
    val availableWidth: Int
)

private class ModeSwitchGuideHost {
    var coordinates: LayoutCoordinates? = null
    var guide by mutableStateOf<ModeSwitchGuide?>(null)
}

private val LocalModeSwitchGuideHost = staticCompositionLocalOf<ModeSwitchGuideHost?> { null }

@Composable
internal fun ModeSwitchGuideOverlay(content: @Composable () -> Unit) {
    val host = remember { ModeSwitchGuideHost() }
    Box(Modifier.fillMaxSize().onGloballyPositioned { host.coordinates = it }) {
        CompositionLocalProvider(LocalModeSwitchGuideHost provides host, content = content)
        host.guide?.let { ModeSwitchPopup(it, LocalZeroConPalette.current) }
    }
}

@Composable
internal fun ModeSwitchActionKey(
    mode: KeyboardMode,
    modifier: Modifier,
    visualPadding: PaddingValues,
    cornerRadius: Dp? = null,
    baseColor: Color,
    palette: ZeroConPalette,
    contentDescription: String,
    onBeforePress: () -> Unit = {},
    onFlick: ((FlickDirection) -> Unit)?,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val host = LocalModeSwitchGuideHost.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    KeyboardActionKey(
        modifier = modifier
            .onGloballyPositioned { coordinates = it }
            .semantics {
                if (onFlick != null) {
                    customActions = listOf(FlickDirection.UP, FlickDirection.RIGHT).map { direction ->
                        val destination = mode.afterSwitch(direction).label
                        CustomAccessibilityAction("${destination}入力に切り替え") {
                            onBeforePress()
                            onFlick(direction)
                            true
                        }
                    }
                }
            },
        visualPadding = visualPadding,
        cornerRadius = cornerRadius,
        baseColor = baseColor,
        isModifier = true,
        palette = palette,
        contentDescription = contentDescription,
        onBeforePress = onBeforePress,
        onFlick = onFlick,
        onFlickPreview = { direction ->
            if (host != null && onFlick != null) {
                val root = host.coordinates
                val key = coordinates
                host.guide = if (direction != null && root?.isAttached == true && key?.isAttached == true) {
                    ModeSwitchGuide(
                        mode = mode,
                        selected = direction,
                        keyTopCenter = root.localPositionOf(key, Offset(key.size.width / 2f, 0f)),
                        availableWidth = root.size.width
                    )
                } else {
                    null
                }
            }
        },
        onClick = onClick
    ) {
        content()
    }
}

@Composable
private fun ModeSwitchPopup(guide: ModeSwitchGuide, palette: ZeroConPalette) {
    val density = LocalDensity.current
    val popupWidth = with(density) { guide.availableWidth.toDp() }.coerceAtMost(180.dp)
    Row(
        modifier = Modifier
            .zIndex(100f)
            .absoluteOffset {
                val width = popupWidth.roundToPx()
                val x = (guide.keyTopCenter.x - width / 2f).roundToInt()
                val y = (guide.keyTopCenter.y - 46.dp.toPx()).roundToInt()
                IntOffset(x.coerceIn(0, (guide.availableWidth - width).coerceAtLeast(0)), y.coerceAtLeast(0))
            }
            .width(popupWidth),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        for (direction in listOf(FlickDirection.UP, FlickDirection.RIGHT)) {
            val arrow = if (direction == FlickDirection.UP) "↑" else "→"
            val destination = guide.mode.afterSwitch(direction).shortLabel
            val selected = guide.selected == direction
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(9.dp),
                color = if (selected) palette.primaryAccent else palette.surfaceElevated,
                contentColor = if (selected) palette.onAccent else palette.textPrimary,
                shadowElevation = 4.dp
            ) {
                Box(
                    Modifier.padding(horizontal = 2.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "$arrow $destination",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
