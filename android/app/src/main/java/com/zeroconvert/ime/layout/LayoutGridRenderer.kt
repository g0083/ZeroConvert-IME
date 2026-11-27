package com.zeroconvert.ime.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroconvert.ime.Aa1Button
import com.zeroconvert.ime.FlickDirection
import com.zeroconvert.ime.KeyboardActionKey
import com.zeroconvert.ime.KeyboardMode
import com.zeroconvert.ime.LocalEnterKeyAction
import com.zeroconvert.ime.LocalKeyboardTheme
import com.zeroconvert.ime.RepeatingIconButton
import com.zeroconvert.ime.ZeroConPalette
import com.zeroconvert.ime.resolve12KeyEnterCornerRadiusDp

/**
 * Callbacks invoked by declarative layout slots rendered through [LayoutGridRenderer].
 */
data class LayoutActionCallbacks(
    val onUndo: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onCursorLeft: () -> Unit = {},
    val onCursorRight: () -> Unit = {},
    val onOpenEmoji: () -> Unit = {},
    val onOpenSymbols: () -> Unit = {},
    val onOpenClipboard: () -> Unit = {},
    val onToggleJpEn: () -> Unit = {},
    val onSpace: () -> Unit = {},
    val onLongPressSpace: () -> Unit = {},
    val onSwitchMode: (FlickDirection) -> Unit = {},
    val onEnter: () -> Unit = {},
    val onShowImePicker: () -> Unit = onLongPressSpace,
    val onBeforeAction: () -> Unit = {}
)

/**
 * Renders any declarative [KeySlotSpec] action key for both 12-key side columns and
 * QWERTY bottom rows so layout profiles share identical touch and theme behavior.
 */
object LayoutGridRenderer {

    @Composable
    fun FlickSideActionKey(
        slot: KeySlotSpec,
        mode: KeyboardMode,
        modifier: Modifier,
        visualPadding: PaddingValues,
        actionKeyBg: Color,
        textColor: Color,
        palette: ZeroConPalette,
        callbacks: LayoutActionCallbacks
    ) {
        when (slot.action) {
            KeyAction.Undo -> {
                KeyboardActionKey(
                    modifier = modifier,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    contentDescription = slot.label,
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onUndo
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Undo,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            KeyAction.Backspace -> {
                RepeatingIconButton(
                    onClick = callbacks.onDelete,
                    modifier = modifier,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    onBeforePress = callbacks.onBeforeAction,
                    hapticOnPress = false
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = "Backspace",
                        tint = textColor,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
            KeyAction.CursorLeft -> {
                RepeatingIconButton(
                    onClick = callbacks.onCursorLeft,
                    modifier = modifier,
                    initialDelayMs = 300L,
                    repeatIntervalMs = 50L,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    onBeforePress = callbacks.onBeforeAction,
                    hapticOnPress = false,
                    contentDescription = "◀"
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            KeyAction.CursorRight -> {
                RepeatingIconButton(
                    onClick = callbacks.onCursorRight,
                    modifier = modifier,
                    initialDelayMs = 300L,
                    repeatIntervalMs = 50L,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    onBeforePress = callbacks.onBeforeAction,
                    hapticOnPress = false,
                    contentDescription = "▶"
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            KeyAction.ToggleJpEn -> {
                val dimColor = palette.textMuted.copy(alpha = 0.72f)
                KeyboardActionKey(
                    modifier = modifier,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    contentDescription = "あ/a",
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onToggleJpEn
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "あ",
                            color = if (mode == KeyboardMode.JAPANESE) textColor else dimColor,
                            fontSize = 15.sp,
                            fontWeight = if (mode == KeyboardMode.JAPANESE) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            text = "/",
                            color = dimColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                        Text(
                            text = "a",
                            color = if (mode == KeyboardMode.ENGLISH) textColor else dimColor,
                            fontSize = 14.sp,
                            fontWeight = if (mode == KeyboardMode.ENGLISH) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
            KeyAction.SwitchToSymbols -> {
                KeyboardActionKey(
                    modifier = modifier,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    contentDescription = slot.label,
                    onBeforePress = callbacks.onBeforeAction,
                    onFlick = { direction ->
                        when (direction) {
                            FlickDirection.UP, FlickDirection.RIGHT -> callbacks.onOpenEmoji()
                            FlickDirection.CENTER -> callbacks.onOpenSymbols()
                            FlickDirection.LEFT, FlickDirection.DOWN -> Unit
                        }
                    },
                    onClick = callbacks.onOpenSymbols
                ) {
                    if (slot.id == "flick_l2_emoji_sym") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.EmojiEmotions,
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = slot.label,
                                color = textColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    } else {
                        Text(
                            text = slot.label,
                            color = textColor,
                            fontSize = if (slot.label.length >= 4) 13.sp else 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }
            KeyAction.SwitchToEmoji -> {
                KeyboardActionKey(
                    modifier = modifier,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    contentDescription = slot.label,
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onOpenEmoji,
                    onLongClick = callbacks.onShowImePicker
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEmotions,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
            KeyAction.SwitchToClipboard -> {
                KeyboardActionKey(
                    modifier = modifier,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    contentDescription = slot.label,
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onOpenClipboard,
                    onLongClick = callbacks.onOpenEmoji
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            KeyAction.GlobeOrImePicker -> {
                KeyboardActionKey(
                    modifier = modifier,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    contentDescription = "入力切替",
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onToggleJpEn,
                    onLongClick = callbacks.onShowImePicker
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
            KeyAction.ModeCycleAa1 -> {
                Aa1Button(
                    mode = mode,
                    modifier = modifier,
                    actionKeyBg = actionKeyBg,
                    fillAvailableHeight = true,
                    visualPadding = visualPadding,
                    palette = palette,
                    onBeforePress = callbacks.onBeforeAction,
                    onSwitchMode = callbacks.onSwitchMode
                )
            }
            KeyAction.Space -> {
                KeyboardActionKey(
                    modifier = modifier,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = false,
                    contentDescription = "空白",
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onSpace,
                    onLongClick = callbacks.onLongPressSpace,
                    onHorizontalStep = { step ->
                        if (step < 0) callbacks.onCursorLeft() else callbacks.onCursorRight()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.SpaceBar,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            KeyAction.Enter -> {
                val enterAction = LocalEnterKeyAction.current
                val enterRadius = resolve12KeyEnterCornerRadiusDp(
                    LocalKeyboardTheme.current.keySurface.enterCornerRadiusDp
                ).dp
                KeyboardActionKey(
                    modifier = modifier,
                    visualPadding = visualPadding,
                    cornerRadius = enterRadius,
                    baseColor = palette.primaryAccent,
                    isAccent = true,
                    contentDescription = enterAction?.label ?: slot.label,
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onEnter
                ) {
                    Text(
                        text = enterAction?.label ?: slot.label,
                        color = palette.onAccent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            else -> Unit
        }
    }

    @Composable
    fun QwertyBottomActionKey(
        slot: KeySlotSpec,
        isJapanese: Boolean,
        modifier: Modifier,
        visualPadding: PaddingValues,
        actionKeyBg: Color,
        textColor: Color,
        palette: ZeroConPalette,
        callbacks: LayoutActionCallbacks
    ) {
        when (slot.action) {
            KeyAction.ModeCycleAa1 -> {
                Aa1Button(
                    mode = if (isJapanese) KeyboardMode.JAPANESE else KeyboardMode.ENGLISH,
                    modifier = modifier,
                    actionKeyBg = actionKeyBg,
                    fillAvailableHeight = true,
                    matchEnterStyle = true,
                    visualPadding = visualPadding,
                    palette = palette,
                    onBeforePress = callbacks.onBeforeAction,
                    onSwitchMode = callbacks.onSwitchMode
                )
            }
            KeyAction.GlobeOrImePicker -> {
                KeyboardActionKey(
                    modifier = modifier.fillMaxHeight(),
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    contentDescription = "入力切替",
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onToggleJpEn,
                    onLongClick = callbacks.onShowImePicker
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            KeyAction.SwitchToEmoji -> {
                KeyboardActionKey(
                    modifier = modifier.fillMaxHeight(),
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    contentDescription = slot.label,
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onOpenEmoji,
                    onLongClick = callbacks.onShowImePicker
                ) {
                    Icon(
                        imageVector = Icons.Default.EmojiEmotions,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            KeyAction.SwitchToSymbols -> {
                KeyboardActionKey(
                    modifier = modifier.fillMaxHeight(),
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    contentDescription = slot.label,
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onOpenSymbols
                ) {
                    Text(
                        text = slot.label,
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
            KeyAction.CursorLeft -> {
                RepeatingIconButton(
                    onClick = callbacks.onCursorLeft,
                    modifier = modifier.fillMaxHeight(),
                    initialDelayMs = 300L,
                    repeatIntervalMs = 50L,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    onBeforePress = callbacks.onBeforeAction,
                    hapticOnPress = false,
                    contentDescription = "◀"
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            KeyAction.CursorRight -> {
                RepeatingIconButton(
                    onClick = callbacks.onCursorRight,
                    modifier = modifier.fillMaxHeight(),
                    initialDelayMs = 300L,
                    repeatIntervalMs = 50L,
                    visualPadding = visualPadding,
                    baseColor = actionKeyBg,
                    isModifier = true,
                    palette = palette,
                    onBeforePress = callbacks.onBeforeAction,
                    hapticOnPress = false,
                    contentDescription = "▶"
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = textColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            KeyAction.Enter -> {
                val enterAction = LocalEnterKeyAction.current
                KeyboardActionKey(
                    modifier = modifier.fillMaxHeight(),
                    visualPadding = visualPadding,
                    baseColor = palette.primaryAccent,
                    isAccent = true,
                    contentDescription = enterAction?.label ?: slot.label,
                    onBeforePress = callbacks.onBeforeAction,
                    onClick = callbacks.onEnter
                ) {
                    if (enterAction != null) {
                        Text(
                            text = enterAction.label,
                            color = palette.onAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardReturn,
                            contentDescription = null,
                            tint = palette.onAccent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            else -> Unit
        }
    }
}
