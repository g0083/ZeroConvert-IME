package com.zeroconvert.ime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroconvert.ime.theme.toComposeColor
import kotlin.math.roundToInt

private val CuratedColorSwatches: List<Long> = listOf(
    0xFFFFFFFFL, 0xFFE2E8F0L, 0xFF64748BL, 0xFF1E293BL,
    0xFF000000L, 0xFF18191CL, 0xFF38BDF8L, 0xFF2563EBL,
    0xFF2DD4BFL, 0xFF10B981L, 0xFFA78BFAL, 0xFF9333EAL,
    0xFFF472B6L, 0xFFEF4444L, 0xFFFB923CL, 0xFFFACC15L
)

@Composable
fun ColorSettingRow(
    label: String,
    colorLong: Long,
    palette: ZeroConPalette,
    onColorSelected: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val hexText = remember(colorLong) { formatRgbHex(colorLong) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = palette.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = hexText,
                color = palette.textMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(colorLong.toComposeColor().copy(alpha = 1f))
                    .border(1.5.dp, palette.borderSubtle, CircleShape)
            )
        }
        AnimatedVisibility(visible = expanded) {
            ThemeColorPickerPanel(
                colorLong = colorLong,
                palette = palette,
                onColorChanged = onColorSelected,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
            )
        }
    }
}

@Composable
fun ThemeColorPickerPanel(
    colorLong: Long,
    palette: ZeroConPalette,
    onColorChanged: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var hsv by remember(colorLong) { mutableStateOf(argbLongToHsv(colorLong)) }
    var hexInput by remember(colorLong) { mutableStateOf(formatRgbHex(colorLong)) }
    LaunchedEffect(colorLong) {
        val incomingHsv = argbLongToHsv(colorLong)
        // Preserve hue/saturation when value is 0 or saturation is 0 so sliders do not jump.
        hsv = floatArrayOf(
            if (incomingHsv[1] > 0.001f) incomingHsv[0] else hsv[0],
            if (incomingHsv[2] > 0.001f) incomingHsv[1] else hsv[1],
            incomingHsv[2]
        )
        hexInput = formatRgbHex(colorLong)
    }

    val currentOnColorChanged by rememberUpdatedState(onColorChanged)
    val currentHsv by rememberUpdatedState(hsv)

    fun emitHsv(h: Float, s: Float, v: Float) {
        val next = floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
        hsv = next
        val argb = hsvToArgbLong(next[0], next[1], next[2])
        hexInput = formatRgbHex(argb)
        currentOnColorChanged(argb)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surfaceElevated)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 2D Saturation x Value Canvas
        val huePureColor = remember(hsv[0]) { Color.hsv(hsv[0].coerceIn(0f, 359.99f), 1f, 1f) }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val updateAt: (Offset) -> Unit = { pos ->
                            val s = (pos.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            val v = 1f - (pos.y / size.height.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                            emitHsv(currentHsv[0], s, v)
                        }
                        updateAt(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            updateAt(change.position)
                        }
                    }
                }
        ) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.White, huePureColor))
            )
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
            )
            val cx = hsv[1].coerceIn(0f, 1f) * size.width
            val cy = (1f - hsv[2].coerceIn(0f, 1f)) * size.height
            drawCircle(
                color = Color.Black.copy(alpha = 0.65f),
                radius = 9.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = 7.5.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Hue Spectrum Bar
        val hueSpectrum = remember {
            listOf(
                Color.Red,
                Color.Yellow,
                Color.Green,
                Color.Cyan,
                Color.Blue,
                Color.Magenta,
                Color.Red
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(11.dp))
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(11.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val updateHue: (Offset) -> Unit = { pos ->
                            val h = (pos.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) * 359.5f
                            emitHsv(h, currentHsv[1], currentHsv[2])
                        }
                        updateHue(down.position)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            updateHue(change.position)
                        }
                    }
                }
        ) {
            drawRect(brush = Brush.horizontalGradient(hueSpectrum))
            val hx = (hsv[0].coerceIn(0f, 360f) / 360f) * size.width
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = Offset(hx, size.height / 2f),
                style = Stroke(width = 2.5.dp.toPx())
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.55f),
                radius = 9.5.dp.toPx(),
                center = Offset(hx, size.height / 2f),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Curated Swatches (2 rows x 8)
        CuratedColorSwatches.chunked(8).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                row.forEach { swatch ->
                    val isSelected = (swatch and 0xFFFFFFL) == (colorLong and 0xFFFFFFL)
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(swatch.toComposeColor())
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) palette.primaryAccent else palette.borderSubtle,
                                shape = CircleShape
                            )
                            .clickable {
                                val nextHsv = argbLongToHsv(swatch)
                                hsv = nextHsv
                                hexInput = formatRgbHex(swatch)
                                onColorChanged(swatch)
                            }
                    )
                }
            }
        }

        // Hex input
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(hsvToArgbLong(hsv[0], hsv[1], hsv[2]).toComposeColor())
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(8.dp))
            )
            OutlinedTextField(
                value = hexInput,
                onValueChange = { raw ->
                    val filtered = raw.uppercase().take(7)
                    hexInput = filtered
                    parseRgbHex(filtered)?.let { parsed ->
                        hsv = argbLongToHsv(parsed)
                        onColorChanged(parsed)
                    }
                },
                label = { Text("HEX (#RRGGBB)", fontSize = 11.sp) },
                singleLine = true,
                colors = settingsTextFieldColors(palette),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun formatRgbHex(argb: Long): String {
    val rgb = argb and 0xFFFFFFL
    return "#" + rgb.toString(16).uppercase().padStart(6, '0')
}

private fun parseRgbHex(raw: String): Long? {
    val cleaned = raw.trim().removePrefix("#")
    if (cleaned.length != 6) return null
    val rgb = cleaned.toLongOrNull(16) ?: return null
    return 0xFF000000L or rgb
}

private fun argbLongToHsv(argb: Long): FloatArray {
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()
    val out = FloatArray(3)
    android.graphics.Color.RGBToHSV(r, g, b, out)
    return out
}

private fun hsvToArgbLong(h: Float, s: Float, v: Float): Long {
    val intColor = android.graphics.Color.HSVToColor(floatArrayOf(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))
    return (intColor.toLong() and 0xFFFFFFFFL) or 0xFF000000L
}
