package com.zeroconvert.ime

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class KeyboardMode {
    JAPANESE,
    ENGLISH,
    NUMERIC
}

enum class KeyboardTab {
    MAIN,
    CLIPBOARD,
    EMOJI,
    SYMBOLS
}

val LocalKeyboardKeyHeight = staticCompositionLocalOf { 46.dp }

@Composable
fun KeyboardRootView(
    imeService: ZeroConvertInputMethodService,
    currentSurface: String,
    predictedCandidate: String,
    candidates: List<Candidate>,
    clipboardHistory: List<ClipboardEntry>,
    isRealtimeInline: Boolean = false,
    aiAssistResult: com.zeroconvert.ime.assist.AiAssistResult? = null,
    onAdoptAiAssist: () -> Unit = {},
    themeMode: String = "system",
    onToggleRealtimeInline: () -> Unit = {},
    onCommitText: (String) -> Unit,
    onDeleteChar: () -> Unit,
    onEnter: () -> Unit,
    onClearInput: () -> Unit,
    onClipboardPaste: (ClipboardEntry, Boolean) -> Unit = { _, _ -> }
) {
    var activeTab by remember { mutableStateOf(KeyboardTab.MAIN) }
    var keyboardMode by remember { mutableStateOf(KeyboardMode.JAPANESE) }
    var shiftState by remember { mutableStateOf(KeyboardShiftState()) }
    val isShifted = shiftState.isShifted
    val doubleTapTimeoutMillis = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val onShiftToggle: () -> Unit = {
        shiftState = shiftState.tap(android.os.SystemClock.uptimeMillis(), doubleTapTimeoutMillis)
    }
    var isCandidatesExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(candidates.isEmpty()) {
        if (candidates.isEmpty()) isCandidatesExpanded = false
    }

    val palette = rememberZeroConPalette(themeMode)
    val bgColor = palette.canvas
    val surfaceColor = palette.surface
    val keyBgColor = palette.keyNormal
    val actionKeyBg = palette.keyModifier
    val accentColor = palette.primaryAccent
    val textColor = palette.textPrimary
    val selectionCount = imeService.selectedCharacterCountState.value

    // Read the composing state from the service so that the QWERTY emoji key can be
    // swapped for the "?" key while the user is typing.
    val isComposing = currentSurface.isNotEmpty() || imeService.currentComposingSurface.value.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .navigationBarsPadding()
    ) {
        KeyboardAssistBar(
            reading = currentSurface,
            assist = aiAssistResult.takeIf { keyboardMode != KeyboardMode.ENGLISH },
            correctionApplied = imeService.aiAssistAdoptedState.value,
            palette = palette,
            onAdopt = onAdoptAiAssist,
            onUndo = { imeService.revertAiAssist() }
        )

        // --- 1. Candidate Bar & Toolbar (with Clipboard Toggle & ∨ Expand) ---
        // The clipboard suggestion is shown in the candidate area while it is available.
        val suggestion = if (selectionCount == null && candidates.isEmpty() && clipboardHistory.isNotEmpty() && !imeService.isClipboardSuggestionDismissed.value) {
            clipboardHistory.first()
        } else {
            null
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(surfaceColor)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionCount != null) {
                    SelectionCountLabel(selectionCount, palette, Modifier.weight(1f).fillMaxHeight())
                } else if (candidates.isNotEmpty()) {
                    HorizontalCandidateStrip(
                        reading = currentSurface,
                        candidates = candidates,
                        palette = palette,
                        onSelect = { imeService.selectCandidate(it) },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                } else {
                    // Empty state: settings button (far left) + layout switcher (right).
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 4.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = actionKeyBg.copy(alpha = 0.6f),
                            modifier = Modifier.clickable { imeService.openSettings() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = palette.primaryAccentSoft,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "ZeroCon AI",
                                    color = palette.textSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // When NOT converting (candidates is empty), show layout switcher and clipboard button
                if (selectionCount != null) {
                    if (imeService.canReconvertSelection()) {
                        Box(
                            Modifier.fillMaxHeight().widthIn(min = 64.dp)
                                .clickable { imeService.reconvertSelection() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("再変換", color = palette.primaryAccentSoft, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                } else if (candidates.isEmpty()) {
                    if (imeService.canReconvertSelection()) {
                        Text("再変換", color = palette.primaryAccentSoft, fontSize = 12.sp,
                            modifier = Modifier.clickable { imeService.reconvertSelection() }.padding(horizontal = 8.dp))
                    }
                    // Japanese & English Layout Switcher (12キー <-> QWERTY)
                    if (keyboardMode == KeyboardMode.JAPANESE || keyboardMode == KeyboardMode.ENGLISH) {
                        val currentLayout = imeService.japaneseLayoutState.value
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = actionKeyBg,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clickable {
                                    val next = if (currentLayout == JapaneseInputLayout.FLICK_12KEY) {
                                        JapaneseInputLayout.QWERTY
                                    } else {
                                        JapaneseInputLayout.FLICK_12KEY
                                    }
                                    imeService.setJapaneseLayout(next)
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "Switch layout",
                                    tint = palette.primaryAccentSoft,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (currentLayout == JapaneseInputLayout.FLICK_12KEY) "QWERTY" else "12キー",
                                    color = palette.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    // Clipboard toggle button
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (activeTab == KeyboardTab.CLIPBOARD) accentColor.copy(alpha = 0.22f) else Color.Transparent)
                            .clickable {
                                activeTab = if (activeTab == KeyboardTab.CLIPBOARD) KeyboardTab.MAIN else KeyboardTab.CLIPBOARD
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                        painter = painterResource(R.drawable.ic_clipboard),
                        contentDescription = "Clipboard",
                        tint = if (activeTab == KeyboardTab.CLIPBOARD) accentColor else palette.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // While converting (candidates are displayed), hide layout switcher & clipboard to maximize candidate space!
                // Only show Down chevron expand button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = if (isCandidatesExpanded) "候補一覧を閉じる" else "候補一覧を開く" }
                        .clickable { isCandidatesExpanded = !isCandidatesExpanded },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isCandidatesExpanded) "∧" else "∨",
                        color = palette.textSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            }

            // Clipboard suggestion overlay.
            // It is drawn on top of the ZeroConAI settings button and the layout switcher,
            // exactly like conversion candidates take over the candidate area.
            if (suggestion != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp, end = 44.dp)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(surfaceColor),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = actionKeyBg.copy(alpha = 0.9f),
                        modifier = Modifier.clickable { onClipboardPaste(suggestion, true) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_clipboard),
                                contentDescription = "Clipboard",
                                tint = textColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            if (suggestion.isImage) {
                                ClipboardImageThumbnail(
                                    uri = suggestion.imageUri!!,
                                    contentDescription = "Image",
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = suggestion.preview,
                                color = textColor,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        val onLongPressSpace: () -> Unit = {
            imeService.showInputMethodPicker()
        }

        val onCycleMode: () -> Unit = {
            if (activeTab != KeyboardTab.MAIN) {
                activeTab = KeyboardTab.MAIN
            } else {
                keyboardMode = when (keyboardMode) {
                    KeyboardMode.JAPANESE -> KeyboardMode.ENGLISH
                    KeyboardMode.ENGLISH -> KeyboardMode.NUMERIC
                    KeyboardMode.NUMERIC -> KeyboardMode.JAPANESE
                }
            }
            if (currentSurface.isNotEmpty()) {
                imeService.confirmCurrent()
            }
        }

        // --- 2. Main Content (Tab Switch) ---
        val keyHeight = imeService.keyboardKeyHeightState.value.dp
        KeyboardContentArea(
            keyHeight = keyHeight,
            expanded = isCandidatesExpanded && candidates.isNotEmpty(),
            expandedContent = {
                ExpandedCandidatePanel(
                    candidates = candidates,
                    isRealtimeInline = isRealtimeInline,
                    palette = palette,
                    onToggleRealtimeInline = onToggleRealtimeInline,
                    onSelect = { item ->
                        imeService.selectCandidate(item)
                        isCandidatesExpanded = false
                    }
                )
            },
            keyboardContent = {
                when (activeTab) {
                KeyboardTab.MAIN -> {
                    when (keyboardMode) {
                        KeyboardMode.JAPANESE -> {
                            val layout = imeService.japaneseLayoutState.value
                            if (layout == JapaneseInputLayout.FLICK_12KEY) {
                                Japanese12KeyFlickLayout(
                                    keyBgColor = keyBgColor,
                                    actionKeyBg = actionKeyBg,
                                    textColor = textColor,
                                    palette = palette,
                                    rowHeight = keyHeight * (52f / 46f),
                                    isFlickOnly = imeService.isFlickOnlyState.value,
                                    onKanaInput = { kana ->
                                        imeService.handleKana(kana)
                                    },
                                    onReplaceLastKana = { nextKana ->
                                        imeService.replaceLastKana(nextKana)
                                    },
                                    onToggleDakuten = {
                                        imeService.handleDakutenToggle()
                                    },
                                    onDelete = onDeleteChar,
                                    onSpace = {
                                        if (currentSurface.isNotEmpty()) {
                                            imeService.confirmCurrent()
                                        } else {
                                            imeService.commitDirect(" ")
                                        }
                                    },
                                    onLongPressSpace = onLongPressSpace,
                                    onEnter = {
                                        if (currentSurface.isNotEmpty()) {
                                            imeService.confirmCurrent()
                                        } else {
                                            onEnter()
                                        }
                                    },
                                    onCycleMode = onCycleMode,
                                    onOpenSymbols = { activeTab = KeyboardTab.SYMBOLS },
                                    onOpenEmoji = { activeTab = KeyboardTab.EMOJI },
                                    onCursorLeft = { imeService.handleCursorLeft() },
                                    onCursorRight = { imeService.handleCursorRight() }
                                )
                            } else {
                                JapaneseQwertyLayout(
                                    isShifted = isShifted,
                                    isShiftLocked = shiftState.isLocked,
                                    keyBgColor = keyBgColor,
                                    actionKeyBg = actionKeyBg,
                                    textColor = textColor,
                                    onKeyPress = { ch ->
                                        imeService.handleChar(shiftState.characterFor(ch))
                                        shiftState = shiftState.afterCharacter()
                                    },
                                    onShiftToggle = onShiftToggle,
                                    onDelete = onDeleteChar,
                                    onSpace = {
                                        if (currentSurface.isNotEmpty()) {
                                            imeService.confirmCurrent()
                                        } else {
                                            imeService.commitDirect(" ")
                                            shiftState = shiftState.afterCharacter()
                                        }
                                    },
                                    onLongPressSpace = onLongPressSpace,
                                    onEnter = {
                                        if (currentSurface.isNotEmpty()) {
                                            imeService.confirmCurrent()
                                        } else {
                                            onEnter()
                                        }
                                    },
                                    onCycleMode = onCycleMode,
                                    onOpenEmoji = { activeTab = KeyboardTab.EMOJI },
                                    isComposing = isComposing,
                                    onCursorLeft = { imeService.handleCursorLeft() },
                                    onCursorRight = { imeService.handleCursorRight() }
                                )
                            }
                        }
                        KeyboardMode.ENGLISH -> {
                            val layout = imeService.japaneseLayoutState.value
                            if (layout == JapaneseInputLayout.FLICK_12KEY) {
                                English12KeyFlickLayout(
                                    keyBgColor = keyBgColor,
                                    actionKeyBg = actionKeyBg,
                                    textColor = textColor,
                                    palette = palette,
                                    rowHeight = keyHeight * (52f / 46f),
                                    isFlickOnly = imeService.isFlickOnlyState.value,
                                    onCommitText = { text ->
                                        imeService.handleEnglishText(text)
                                    },
                                    onReplaceLastCommittedText = { text, len ->
                                        imeService.replaceLastCommittedText(text, len)
                                    },
                                    onToggleLastCharCase = {
                                        imeService.toggleLastCharCase()
                                    },
                                    isLastCharLetter = {
                                        imeService.isLastCharLetter()
                                    },
                                    onDelete = onDeleteChar,
                                    onSpace = { imeService.handleEnglishSpace() },
                                    onLongPressSpace = onLongPressSpace,
                                    onEnter = onEnter,
                                    onCycleMode = onCycleMode,
                                    onOpenSymbols = { activeTab = KeyboardTab.SYMBOLS },
                                    onOpenEmoji = { activeTab = KeyboardTab.EMOJI },
                                    onCursorLeft = { imeService.handleCursorLeft() },
                                    onCursorRight = { imeService.handleCursorRight() }
                                )
                            } else {
                                EnglishQwertyLayout(
                                    isShifted = isShifted,
                                    isShiftLocked = shiftState.isLocked,
                                    keyBgColor = keyBgColor,
                                    actionKeyBg = actionKeyBg,
                                    textColor = textColor,
                                    onCommitChar = { ch ->
                                        imeService.handleEnglishChar(shiftState.characterFor(ch))
                                        shiftState = shiftState.afterCharacter()
                                    },
                                    onShiftToggle = onShiftToggle,
                                    onDelete = onDeleteChar,
                                    onSpace = {
                                        imeService.handleEnglishSpace()
                                        shiftState = shiftState.afterCharacter()
                                    },
                                    onLongPressSpace = onLongPressSpace,
                                    onEnter = onEnter,
                                    onCycleMode = onCycleMode,
                                    onOpenEmoji = { activeTab = KeyboardTab.EMOJI },
                                    isComposing = isComposing,
                                    onCursorLeft = { imeService.handleCursorLeft() },
                                    onCursorRight = { imeService.handleCursorRight() }
                                )
                            }
                        }
                        KeyboardMode.NUMERIC -> {
                            FlickNumericPadLayout(
                                keyBgColor = keyBgColor,
                                actionKeyBg = actionKeyBg,
                                textColor = textColor,
                                palette = palette,
                                rowHeight = keyHeight * (52f / 46f),
                                isFlickOnly = imeService.isFlickOnlyState.value,
                                onCommitText = { text ->
                                    imeService.commitDirect(text)
                                },
                                onReplaceLastCommittedText = { text, len ->
                                    imeService.replaceLastCommittedText(text, len)
                                },
                                onDelete = onDeleteChar,
                                onLongPressSpace = onLongPressSpace,
                                    onEnter = onEnter,
                                    onCycleMode = onCycleMode,
                                    onOpenSymbols = { activeTab = KeyboardTab.SYMBOLS },
                                    onCursorLeft = { imeService.handleCursorLeft() },
                                    onCursorRight = { imeService.handleCursorRight() }
                                )
                        }
                    }
                }
                KeyboardTab.CLIPBOARD -> {
                    ClipboardPanel(
                        history = clipboardHistory,
                        textColor = textColor,
                        itemBgColor = keyBgColor,
                        actionKeyBg = actionKeyBg,
                        onSelect = { entry ->
                            onClipboardPaste(entry, false)
                            activeTab = KeyboardTab.MAIN
                        },
                        onTogglePin = { entry -> imeService.toggleClipboardPin(entry) },
                        onDelete = { entry -> imeService.removeClipboardEntry(entry) },
                        onUpdate = { entry, text -> imeService.updateClipboardEntry(entry, text) },
                        onClearAll = { imeService.clearClipboardHistory(true) },
                        retentionHours = imeService.clipboardRetentionHoursState.value,
                        onRetentionChange = { imeService.setClipboardRetentionHours(it) },
                        onBackToQwerty = {
                            activeTab = KeyboardTab.MAIN
                        }
                    )
                }
                KeyboardTab.EMOJI -> {
                    EmojiPanel(
                        actionKeyBg = actionKeyBg,
                        textColor = textColor,
                        onSelect = { emoji ->
                            onCommitText(emoji)
                            activeTab = KeyboardTab.MAIN
                        },
                        onBackToQwerty = {
                            activeTab = KeyboardTab.MAIN
                        }
                    )
                }
                KeyboardTab.SYMBOLS -> {
                    SymbolsPanel(
                        keyBgColor = keyBgColor,
                        actionKeyBg = actionKeyBg,
                        textColor = textColor,
                        palette = palette,
                        onSelect = { sym ->
                            onCommitText(sym)
                        },
                        onBackToQwerty = {
                            activeTab = KeyboardTab.MAIN
                        },
                        onDelete = onDeleteChar,
                        onSpace = {
                            imeService.commitDirect(" ")
                        },
                        onLongPressSpace = onLongPressSpace,
                        onEnter = onEnter,
                        onCursorLeft = {
                            imeService.handleCursorLeft()
                        },
                        onCursorRight = {
                            imeService.handleCursorRight()
                        }
                    )
                }
                }
            }
        )
    }
}

@Composable
fun ShiftKeyButton(
    isShifted: Boolean,
    isLocked: Boolean,
    bgColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier.height(LocalKeyboardKeyHeight.current)
            .clip(RoundedCornerShape(6.dp)).background(bgColor)
            .semantics {
                stateDescription = when {
                    isLocked -> "大文字固定"
                    isShifted -> "次の1文字だけ大文字"
                    else -> "小文字"
                }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isLocked) Icons.Default.KeyboardCapslock else Icons.Default.ArrowUpward,
            contentDescription = "Shift",
            tint = if (isShifted) Color.White else Color(0xFFD0D3DE),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun RepeatingIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    initialDelayMs: Long = 350L,
    repeatIntervalMs: Long = 50L,
    content: @Composable () -> Unit
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val coroutineScope = rememberCoroutineScope()
    var isPressed by remember { mutableStateOf(false) }

    DisposableEffect(isPressed) {
        var job: Job? = null
        if (isPressed) {
            job = coroutineScope.launch {
                currentOnClick()
                delay(initialDelayMs)
                while (isActive) {
                    currentOnClick()
                    delay(repeatIntervalMs)
                }
            }
        }
        onDispose {
            job?.cancel()
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    isPressed = true
                    waitForUpOrCancellation()
                    isPressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun Aa1Button(
    mode: KeyboardMode,
    modifier: Modifier = Modifier,
    actionKeyBg: Color,
    fillAvailableHeight: Boolean = false,
    matchEnterStyle: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .then(if (fillAvailableHeight) Modifier.fillMaxHeight() else Modifier.height(LocalKeyboardKeyHeight.current))
            .then(
                if (fillAvailableHeight) Modifier.padding(horizontal = 3.dp, vertical = 2.5.dp)
                else Modifier
            )
            .clip(RoundedCornerShape(if (matchEnterStyle) 22.dp else if (fillAvailableHeight) 8.dp else 7.dp))
            .background(actionKeyBg)
            .semantics { contentDescription = "入力モード:" + when (mode) {
                KeyboardMode.JAPANESE -> "日本語"
                KeyboardMode.ENGLISH -> "英語"
                KeyboardMode.NUMERIC -> "数字"
            } }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val activeColor = Color.White
        val dimColor = Color(0xFF6E7282)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "あ",
                color = if (mode == KeyboardMode.JAPANESE) activeColor else dimColor,
                fontSize = 16.sp,
                fontWeight = if (mode == KeyboardMode.JAPANESE) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = "a",
                color = if (mode == KeyboardMode.ENGLISH) activeColor else dimColor,
                fontSize = 14.sp,
                fontWeight = if (mode == KeyboardMode.ENGLISH) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = "1",
                color = if (mode == KeyboardMode.NUMERIC) activeColor else dimColor,
                fontSize = 14.sp,
                fontWeight = if (mode == KeyboardMode.NUMERIC) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun JapaneseQwertyLayout(
    isShifted: Boolean,
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    onKeyPress: (Char) -> Unit,
    onShiftToggle: () -> Unit,
    onDelete: () -> Unit,
    onSpace: () -> Unit,
    onLongPressSpace: () -> Unit = {},
    onEnter: () -> Unit,
    onCycleMode: () -> Unit,
    onOpenEmoji: () -> Unit,
    isComposing: Boolean = false,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit,
    isShiftLocked: Boolean = false
) {
    val row0Normal = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    val row0Shift = listOf('!', '@', '#', '$', '%', '^', '&', '*', '(', ')')
    val row1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
    val row2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l', 'ー')
    val row3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Row 0: Numbers / Shift Symbols
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val list = if (isShifted) row0Shift else row0Normal
            for (ch in list) {
                KeyButton(
                    text = ch.toString(),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                    bgColor = keyBgColor,
                    textColor = textColor,
                    onClick = { onKeyPress(ch) }
                )
            }
        }

        // Row 1: q - p
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (ch in row1) {
                val displayChar = if (isShifted) ch.uppercaseChar() else ch
                KeyButton(
                    text = displayChar.toString(),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                    bgColor = keyBgColor,
                    textColor = textColor,
                    onClick = { onKeyPress(displayChar) }
                )
            }
        }

        // Row 2: a - l + ー (10 keys)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (ch in row2) {
                val displayChar = if (isShifted && ch != 'ー') ch.uppercaseChar() else ch
                KeyButton(
                    text = displayChar.toString(),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                    bgColor = keyBgColor,
                    textColor = textColor,
                    onClick = { onKeyPress(displayChar) }
                )
            }
        }

        // Row 3: Shift + z-m + Backspace
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ShiftKeyButton(
                isShifted = isShifted,
                isLocked = isShiftLocked,
                bgColor = actionKeyBg,
                modifier = Modifier.weight(1.35f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                onClick = onShiftToggle
            )

            for (ch in row3) {
                val displayChar = if (isShifted) ch.uppercaseChar() else ch
                KeyButton(
                    text = displayChar.toString(),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                    bgColor = keyBgColor,
                    textColor = textColor,
                    onClick = { onKeyPress(displayChar) }
                )
            }

            RepeatingIconButton(
                onClick = onDelete,
                modifier = Modifier
                    .weight(1.35f)
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .height(LocalKeyboardKeyHeight.current)
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg)
            ) {
                Icon(
                    imageVector = Icons.Default.Backspace,
                    contentDescription = "Backspace",
                    tint = textColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Row 4 (Image 1: [あa1] [、] [😀] [ 日本語 ] [。] [◀] [▶] [↩])
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Aa1Button(
                mode = KeyboardMode.JAPANESE,
                modifier = Modifier.weight(1.35f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                actionKeyBg = actionKeyBg,
                matchEnterStyle = true,
                onClick = onCycleMode
            )

            KeyButton(
                text = "、",
                modifier = Modifier.weight(0.85f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                bgColor = actionKeyBg,
                textColor = textColor,
                fontSize = 18.sp,
                onClick = { onKeyPress('、') }
            )

            if (isComposing) {
                KeyButton(
                    text = "？",
                    modifier = Modifier.weight(0.85f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                    bgColor = actionKeyBg,
                    textColor = textColor,
                    fontSize = 18.sp,
                    onClick = { onKeyPress('？') }
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(0.85f)
                        .padding(horizontal = 2.5.dp, vertical = 2.dp)
                        .height(LocalKeyboardKeyHeight.current)
                        .clip(RoundedCornerShape(6.dp))
                        .background(actionKeyBg)
                        .clickable(onClick = onOpenEmoji),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mood,
                        contentDescription = "Emoji",
                        tint = textColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            KeyButton(
                text = "日本語",
                modifier = Modifier.weight(2.4f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                bgColor = keyBgColor,
                textColor = textColor,
                fontSize = 15.sp,
                onClick = onSpace,
                onLongClick = onLongPressSpace
            )

            KeyButton(
                text = "。",
                modifier = Modifier.weight(0.85f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                bgColor = actionKeyBg,
                textColor = textColor,
                fontSize = 18.sp,
                onClick = { onKeyPress('。') }
            )

            // Repeating Arrow Left
            RepeatingIconButton(
                onClick = onCursorLeft,
                modifier = Modifier
                    .weight(0.85f)
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .height(LocalKeyboardKeyHeight.current)
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg),
                initialDelayMs = 300L,
                repeatIntervalMs = 50L
            ) {
                Text(text = "◀", color = textColor, fontSize = 13.sp)
            }

            // Repeating Arrow Right
            RepeatingIconButton(
                onClick = onCursorRight,
                modifier = Modifier
                    .weight(0.85f)
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .height(LocalKeyboardKeyHeight.current)
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg),
                initialDelayMs = 300L,
                repeatIntervalMs = 50L
            ) {
                Text(text = "▶", color = textColor, fontSize = 13.sp)
            }

            Box(
                modifier = Modifier
                    .weight(1.35f)
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .height(LocalKeyboardKeyHeight.current)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF3B82F6))
                    .clickable(onClick = onEnter),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardReturn,
                    contentDescription = "Enter",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun EnglishQwertyLayout(
    isShifted: Boolean,
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    onCommitChar: (Char) -> Unit,
    onShiftToggle: () -> Unit,
    onDelete: () -> Unit,
    onSpace: () -> Unit,
    onLongPressSpace: () -> Unit = {},
    onEnter: () -> Unit,
    onCycleMode: () -> Unit,
    onOpenEmoji: () -> Unit,
    isComposing: Boolean = false,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit,
    isShiftLocked: Boolean = false
) {
    val row0Normal = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '0')
    val row0Shift = listOf('!', '@', '#', '$', '%', '^', '&', '*', '(', ')')
    val row1 = listOf('q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p')
    val row2 = listOf('a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l')
    val row3 = listOf('z', 'x', 'c', 'v', 'b', 'n', 'm')

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Row 0
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val list = if (isShifted) row0Shift else row0Normal
            for (ch in list) {
                KeyButton(
                    text = ch.toString(),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                    bgColor = keyBgColor,
                    textColor = textColor,
                    onClick = { onCommitChar(ch) }
                )
            }
        }

        // Row 1
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (ch in row1) {
                val displayChar = if (isShifted) ch.uppercaseChar() else ch
                KeyButton(
                    text = displayChar.toString(),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                    bgColor = keyBgColor,
                    textColor = textColor,
                    onClick = { onCommitChar(displayChar) }
                )
            }
        }

        // Row 2: 9 keys centered with 0.5f spacer on left & right
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Spacer(modifier = Modifier.weight(0.5f))
            for (ch in row2) {
                val displayChar = if (isShifted) ch.uppercaseChar() else ch
                KeyButton(
                    text = displayChar.toString(),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                    bgColor = keyBgColor,
                    textColor = textColor,
                    onClick = { onCommitChar(displayChar) }
                )
            }
            Spacer(modifier = Modifier.weight(0.5f))
        }

        // Row 3: Shift + z-m + Backspace
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ShiftKeyButton(
                isShifted = isShifted,
                isLocked = isShiftLocked,
                bgColor = actionKeyBg,
                modifier = Modifier.weight(1.35f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                onClick = onShiftToggle
            )

            for (ch in row3) {
                val displayChar = if (isShifted) ch.uppercaseChar() else ch
                KeyButton(
                    text = displayChar.toString(),
                    modifier = Modifier.weight(1f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                    bgColor = keyBgColor,
                    textColor = textColor,
                    onClick = { onCommitChar(displayChar) }
                )
            }

            RepeatingIconButton(
                onClick = onDelete,
                modifier = Modifier
                    .weight(1.35f)
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .height(LocalKeyboardKeyHeight.current)
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg)
            ) {
                Icon(
                    imageVector = Icons.Default.Backspace,
                    contentDescription = "Backspace",
                    tint = textColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // Row 4 (Image 2: [あa1] [😀] [,] [ 日本語 ] [.] [◀] [▶] [↩])
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Aa1Button(
                mode = KeyboardMode.ENGLISH,
                modifier = Modifier.weight(1.35f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                actionKeyBg = actionKeyBg,
                matchEnterStyle = true,
                onClick = onCycleMode
            )

            if (isComposing) {
                KeyButton(
                    text = "?",
                    modifier = Modifier.weight(0.85f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                    bgColor = actionKeyBg,
                    textColor = textColor,
                    fontSize = 18.sp,
                    onClick = { onCommitChar('?') }
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(0.85f)
                        .padding(horizontal = 2.5.dp, vertical = 2.dp)
                        .height(LocalKeyboardKeyHeight.current)
                        .clip(RoundedCornerShape(6.dp))
                        .background(actionKeyBg)
                        .clickable(onClick = onOpenEmoji),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mood,
                        contentDescription = "Emoji",
                        tint = textColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            KeyButton(
                text = ",",
                modifier = Modifier.weight(0.85f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                bgColor = actionKeyBg,
                textColor = textColor,
                fontSize = 18.sp,
                onClick = { onCommitChar(',') }
            )

            KeyButton(
                text = "日本語",
                modifier = Modifier.weight(2.4f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                bgColor = keyBgColor,
                textColor = textColor,
                fontSize = 15.sp,
                onClick = onSpace,
                onLongClick = onLongPressSpace
            )

            KeyButton(
                text = ".",
                modifier = Modifier.weight(0.85f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                bgColor = actionKeyBg,
                textColor = textColor,
                fontSize = 18.sp,
                onClick = { onCommitChar('.') }
            )

            RepeatingIconButton(
                onClick = onCursorLeft,
                modifier = Modifier
                    .weight(0.85f)
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .height(LocalKeyboardKeyHeight.current)
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg),
                initialDelayMs = 300L,
                repeatIntervalMs = 50L
            ) {
                Text(text = "◀", color = textColor, fontSize = 13.sp)
            }

            RepeatingIconButton(
                onClick = onCursorRight,
                modifier = Modifier
                    .weight(0.85f)
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .height(LocalKeyboardKeyHeight.current)
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg),
                initialDelayMs = 300L,
                repeatIntervalMs = 50L
            ) {
                Text(text = "▶", color = textColor, fontSize = 13.sp)
            }

            Box(
                modifier = Modifier
                    .weight(1.35f)
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .height(LocalKeyboardKeyHeight.current)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF3B82F6))
                    .clickable(onClick = onEnter),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardReturn,
                    contentDescription = "Enter",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun NumericPadLayout(
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    onCommitText: (String) -> Unit,
    onDelete: () -> Unit,
    onEnter: () -> Unit,
    onCycleMode: () -> Unit,
    onOpenSymbols: () -> Unit,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit
) {
    // Exact 5x4 layout from Image 3
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Row 0: [ ↶ ] [ 1 (☆♪→) ] [ 2 (¥$€) ] [ 3 (%°#) ] [ ⌫ ]
        Row(modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            RepeatingIconButton(
                onClick = onDelete,
                modifier = Modifier.weight(1.1f).fillMaxHeight().padding(horizontal = 2.5.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(actionKeyBg)
            ) {
                Text(text = "↶", color = textColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            NumericKeyButton(mainText = "1", subText = "☆♪→", modifier = Modifier.weight(1.1f), bgColor = keyBgColor, textColor = textColor) { onCommitText("1") }
            NumericKeyButton(mainText = "2", subText = "¥$€", modifier = Modifier.weight(1.1f), bgColor = keyBgColor, textColor = textColor) { onCommitText("2") }
            NumericKeyButton(mainText = "3", subText = "%°#", modifier = Modifier.weight(1.1f), bgColor = keyBgColor, textColor = textColor) { onCommitText("3") }
            RepeatingIconButton(
                onClick = onDelete,
                modifier = Modifier.weight(1.1f).fillMaxHeight().padding(horizontal = 2.5.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(actionKeyBg)
            ) {
                Icon(imageVector = Icons.Default.Backspace, contentDescription = "Backspace", tint = textColor, modifier = Modifier.size(22.dp))
            }
        }

        // Row 1: [ ◀ ] [ 4 (○*・) ] [ 5 (+×÷) ] [ 6 (<=>) ] [ ▶ ]
        Row(modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            RepeatingIconButton(
                onClick = onCursorLeft,
                modifier = Modifier.weight(1.1f).fillMaxHeight().padding(horizontal = 2.5.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(actionKeyBg),
                initialDelayMs = 300L,
                repeatIntervalMs = 50L
            ) {
                Text(text = "◀", color = textColor, fontSize = 14.sp)
            }
            NumericKeyButton(mainText = "4", subText = "○*・", modifier = Modifier.weight(1.1f), bgColor = keyBgColor, textColor = textColor) { onCommitText("4") }
            NumericKeyButton(mainText = "5", subText = "+×÷", modifier = Modifier.weight(1.1f), bgColor = keyBgColor, textColor = textColor) { onCommitText("5") }
            NumericKeyButton(mainText = "6", subText = "<=>", modifier = Modifier.weight(1.1f), bgColor = keyBgColor, textColor = textColor) { onCommitText("6") }
            RepeatingIconButton(
                onClick = onCursorRight,
                modifier = Modifier.weight(1.1f).fillMaxHeight().padding(horizontal = 2.5.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(actionKeyBg),
                initialDelayMs = 300L,
                repeatIntervalMs = 50L
            ) {
                Text(text = "▶", color = textColor, fontSize = 14.sp)
            }
        }

        // Row 2: [ !?# ] [ 7 (「」:) ] [ 8 (〒々〆) ] [ 9 (^\\) ] [ ␣ ]
        Row(modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Box(
                modifier = Modifier.weight(1.1f).fillMaxHeight().padding(horizontal = 2.5.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(actionKeyBg).clickable(onClick = onOpenSymbols),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "!?#", color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            NumericKeyButton(mainText = "7", subText = "「」:", modifier = Modifier.weight(1.1f), bgColor = keyBgColor, textColor = textColor) { onCommitText("7") }
            NumericKeyButton(mainText = "8", subText = "〒々〆", modifier = Modifier.weight(1.1f), bgColor = keyBgColor, textColor = textColor) { onCommitText("8") }
            NumericKeyButton(mainText = "9", subText = "^\\", modifier = Modifier.weight(1.1f), bgColor = keyBgColor, textColor = textColor) { onCommitText("9") }
            Box(
                modifier = Modifier.weight(1.1f).fillMaxHeight().padding(horizontal = 2.5.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(actionKeyBg).clickable { onCommitText(" ") },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "␣", color = textColor, fontSize = 20.sp)
            }
        }

        // Row 3: [ あa1 ] [ ()[] ] [ 0 (~...@) ] [ .,-/ ] [ ↩ ]
        Row(modifier = Modifier.fillMaxWidth().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Aa1Button(
                mode = KeyboardMode.NUMERIC,
                modifier = Modifier.weight(1.1f),
                actionKeyBg = actionKeyBg,
                onClick = onCycleMode
            )
            Box(
                modifier = Modifier.weight(1.1f).fillMaxHeight().padding(horizontal = 2.5.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(keyBgColor).clickable { onCommitText("()") },
                contentAlignment = Alignment.Center
            ) {
                Text(text = "()[]", color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Normal)
            }
            NumericKeyButton(mainText = "0", subText = "~...@ ", modifier = Modifier.weight(1.1f), bgColor = keyBgColor, textColor = textColor) { onCommitText("0") }
            Box(
                modifier = Modifier.weight(1.1f).fillMaxHeight().padding(horizontal = 2.5.dp, vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(keyBgColor).clickable { onCommitText(".") },
                contentAlignment = Alignment.Center
            ) {
                Text(text = ".,-/", color = textColor, fontSize = 18.sp, fontWeight = FontWeight.Normal)
            }
            Box(
                modifier = Modifier.weight(1.1f).fillMaxHeight().padding(horizontal = 2.5.dp, vertical = 2.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF3B82F6)).clickable(onClick = onEnter),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Default.KeyboardReturn, contentDescription = "Enter", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun NumericKeyButton(
    mainText: String,
    subText: String = "",
    modifier: Modifier = Modifier,
    bgColor: Color,
    textColor: Color,
    subTextColor: Color = Color(0xFF8E95AA),
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 2.5.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(bgColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = mainText,
                color = textColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
            if (subText.isNotEmpty()) {
                Text(
                    text = subText,
                    color = subTextColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun KeyButton(
    text: String,
    modifier: Modifier = Modifier,
    bgColor: Color,
    textColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 18.sp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    Box(
        modifier = modifier
            .height(LocalKeyboardKeyHeight.current)
            .clip(RoundedCornerShape(7.dp))
            .background(bgColor)
            .semantics(mergeDescendants = true) {
                contentDescription = "${text}を入力"
                onClick { currentOnClick(); true }
            }
            .then(
                if (onLongClick != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { currentOnClick() },
                            onLongPress = { currentOnLongClick?.invoke() }
                        )
                    }
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ClipboardPanel(
    history: List<ClipboardEntry>,
    textColor: Color,
    itemBgColor: Color,
    actionKeyBg: Color,
    onSelect: (ClipboardEntry) -> Unit,
    onTogglePin: (ClipboardEntry) -> Unit = {},
    onDelete: (ClipboardEntry) -> Unit = {},
    onUpdate: (ClipboardEntry, String) -> Unit = { _, _ -> },
    onClearAll: () -> Unit = {},
    retentionHours: Int = 1,
    onRetentionChange: (Int) -> Unit = {},
    onBackToQwerty: () -> Unit
) {
    var editingEntry by remember { mutableStateOf<ClipboardEntry?>(null) }
    var editingText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        if (editingEntry != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = editingText,
                    onValueChange = { editingText = it },
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(actionKeyBg)
                        .clickable {
                            editingEntry?.let { onUpdate(it, editingText) }
                            editingEntry = null
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("保存", color = textColor, fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(actionKeyBg)
                        .clickable { editingEntry = null }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("×", color = textColor, fontSize = 13.sp)
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (history.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "クリップボードの履歴はありません", color = Color(0xFF64748B), fontSize = 14.sp)
                }
            } else {
                val pinned = history.filter { it.pinned }
                val recent = history.filter { !it.pinned }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (pinned.isNotEmpty()) {
                        item {
                            Text(text = "ピン留め", color = Color(0xFFFBBF24), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
                        }
                        items(pinned, key = { it.id }) { item ->
                            ClipboardRow(item, textColor, itemBgColor, actionKeyBg, onSelect, onTogglePin, onDelete, onUpdate, { e -> editingEntry = e; editingText = e.text ?: "" })
                        }
                    }
                    if (recent.isNotEmpty()) {
                        item {
                            Text(text = "最近", color = Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(4.dp))
                        }
                        items(recent, key = { it.id }) { item ->
                            ClipboardRow(item, textColor, itemBgColor, actionKeyBg, onSelect, onTogglePin, onDelete, onUpdate, { e -> editingEntry = e; editingText = e.text ?: "" })
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg)
                    .clickable(onClick = onBackToQwerty),
                contentAlignment = Alignment.Center
            ) {
                Text("← キーボード", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg)
                    .clickable { onClearAll() },
                contentAlignment = Alignment.Center
            ) {
                Text("すべて削除", color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun ClipboardRow(
    item: ClipboardEntry,
    textColor: Color,
    itemBgColor: Color,
    actionKeyBg: Color,
    onSelect: (ClipboardEntry) -> Unit,
    onTogglePin: (ClipboardEntry) -> Unit,
    onDelete: (ClipboardEntry) -> Unit,
    onUpdate: (ClipboardEntry, String) -> Unit,
    onEdit: (ClipboardEntry) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(itemBgColor)
            .clickable { onSelect(item) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.isImage) {
            ClipboardImageThumbnail(
                uri = item.imageUri!!,
                contentDescription = "Image",
                modifier = Modifier.size(40.dp)
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_clipboard),
                contentDescription = "Clipboard",
                tint = if (item.pinned) Color(0xFFFBBF24) else textColor,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.preview,
            color = textColor,
            fontSize = 14.sp,
            maxLines = 2,
            modifier = Modifier.weight(1f)
        )
        if (item.entityType != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Surface(shape = RoundedCornerShape(4.dp), color = actionKeyBg) {
                Text(text = item.entityType!!, color = Color(0xFF818CF8), fontSize = 10.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onTogglePin(item) }
                .padding(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = "Pin",
                tint = if (item.pinned) Color(0xFFFBBF24) else Color(0xFF94A3B8),
                modifier = Modifier.size(18.dp)
            )
        }
        if (!item.isImage) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onEdit(item) }
                    .padding(4.dp)
            ) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
            }
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onDelete(item) }
                .padding(4.dp)
        ) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun ClipboardImageThumbnail(
    uri: android.net.Uri,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                val resolver = context.contentResolver
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                val sample = if (bounds.outWidth > 0) {
                    var s = 1
                    while (bounds.outWidth / (s * 2) >= 96) s *= 2
                    s
                } else {
                    1
                }
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Exception) {
                null
            }
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(4.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF334155)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = contentDescription,
                tint = Color(0xFF94A3B8),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
fun EmojiPanel(
    actionKeyBg: Color,
    textColor: Color,
    onSelect: (String) -> Unit,
    onBackToQwerty: () -> Unit
) {
    val emojis = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
        "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
        "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩",
        "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "😣", "😖",
        "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤯", "😳",
        "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗", "🤔", "🤭",
        "👍", "👎", "👏", "🙌", "👐", "🤲", "🤝", "🙏", "✌️", "🤞",
        "✨", "🎉", "🔥", "❤️", "💖", "⭐", "🙇‍♂️", "🙇‍♀️", "💡", "💯"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 42.dp),
            modifier = Modifier.weight(1f).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(emojis) { emoji ->
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 22.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(2.dp),
            horizontalArrangement = Arrangement.Start
        ) {
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF4A4E62))
                    .clickable(onClick = onBackToQwerty),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("あ", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("a1", color = Color(0xFF8E95AA), fontSize = 12.sp, fontWeight = FontWeight.Normal)
                }
            }
        }
    }
}

data class SymbolCategory(
    val title: String,
    val symbols: List<String>
)

val symbolCategories = listOf(
    SymbolCategory(
        title = "1?#",
        symbols = listOf(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
            "！", "？", "～", "…", "・", "ー", "@", "#", "$", "%",
            "&", "*", "-", "+", "=", "/", "\\", "_", ":", ";",
            "\"", "'", "`", "~", "|", "^", "<", ">", ",", "."
        )
    ),
    SymbolCategory(
        title = "( )",
        symbols = listOf(
            "「", "」", "『", "』", "（", "）", "【", "】", "［", "］",
            "｛", "｝", "〈", "〉", "《", "》", "〔", "〕", "“", "”",
            "‘", "’", "«", "»", "(", ")", "[", "]", "{", "}"
        )
    ),
    SymbolCategory(
        title = "→",
        symbols = listOf(
            "→", "←", "↑", "↓", "⇒", "⇐", "⇑", "⇓", "⇔", "↔",
            "↕", "↗", "↘", "↖", "↙", "➔", "▶", "◀", "▲", "▼",
            "▷", "◁", "△", "▽", "↩", "↪", "↻", "↺", "⇄", "⇆"
        )
    ),
    SymbolCategory(
        title = "+ -",
        symbols = listOf(
            "＋", "－", "×", "÷", "±", "＝", "≠", "＜", "＞", "≦",
            "≧", "∞", "‰", "％", "°", "℃", "℉", "√", "∠", "∟",
            "⊥", "∂", "∇", "≡", "≒", "≪", "≫", "∽", "∝", "∴",
            "∵", "∈", "∋", "⊆", "⊇", "⊂", "⊃", "∪", "∩", "∀"
        )
    ),
    SymbolCategory(
        title = "①",
        symbols = listOf(
            "①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩",
            "⑪", "⑫", "⑬", "⑭", "⑮", "⑯", "⑰", "⑱", "⑲", "⑳",
            "❶", "❷", "❸", "❹", "❺", "❻", "❼", "❽", "❾", "❿",
            "Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ",
            "ⅰ", "ⅱ", "ⅲ", "ⅳ", "ⅴ", "ⅵ", "ⅶ", "ⅷ", "ⅸ", "ⅹ"
        )
    ),
    SymbolCategory(
        title = "◇",
        symbols = listOf(
            "○", "●", "◎", "◇", "◆", "□", "■", "△", "▲", "▽",
            "▼", "☆", "★", "♪", "♬", "♭", "♯", "※", "〒", "〆",
            "々", "〇", "♠", "♥", "♦", "♣", "♤", "♡", "♢", "♧",
            "☀", "☁", "☂", "☃", "☎", "✉", "✓", "✔", "✕", "✖"
        )
    ),
    SymbolCategory(
        title = "[全]",
        symbols = listOf(
            "！", "？", "＠", "＃", "＄", "％", "＆", "＊", "（", "）",
            "－", "＋", "＝", "；", "：", "／", "＼", "｜", "～", "＾",
            "＿", "＜", "＞", "［", "］", "｛", "｝", "”", "’", "‘",
            "１", "２", "３", "４", "５", "６", "７", "８", "９", "０"
        )
    ),
    SymbolCategory(
        title = "￥",
        symbols = listOf(
            "￥", "$", "€", "£", "¢", "₩", "₽", "฿", "₺", "₫",
            "元", "円", "銭", "㎝", "㎞", "㎜", "㎎", "㎏", "㏄", "㎖",
            "㎗", "㎘", "㎡", "㎥", "㏘", "㏂", "№", "℡", "㌍", "㌘"
        )
    )
)

@Composable
fun SymbolsPanel(
    keyBgColor: Color,
    actionKeyBg: Color,
    textColor: Color,
    palette: ZeroConPalette,
    onSelect: (String) -> Unit,
    onBackToQwerty: () -> Unit,
    onDelete: () -> Unit,
    onSpace: () -> Unit,
    onLongPressSpace: () -> Unit = {},
    onEnter: () -> Unit,
    onCursorLeft: () -> Unit,
    onCursorRight: () -> Unit
) {
    var selectedCategoryIndex by remember { mutableStateOf(0) }
    val currentSymbols = symbolCategories[selectedCategoryIndex].symbols

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Top Category Bar (Gboard-style)
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(symbolCategories.size) { idx ->
                val cat = symbolCategories[idx]
                val isSelected = (idx == selectedCategoryIndex)
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (isSelected) palette.primaryAccent else actionKeyBg)
                        .clickable { selectedCategoryIndex = idx }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = cat.title,
                        color = if (isSelected) Color.White else textColor,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        // 2. Symbols Grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(10),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(currentSymbols) { sym ->
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(keyBgColor)
                        .clickable { onSelect(sym) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = sym, color = textColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 3. Bottom control row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // 1. あa1 (Returns to previous mode)
            Box(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight()
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF4A4E62))
                    .clickable(onClick = onBackToQwerty),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "あ",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "a1",
                        color = Color(0xFF8E95AA),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            // 2. 、
            KeyButton(
                text = "、",
                modifier = Modifier.weight(0.85f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                bgColor = actionKeyBg,
                textColor = textColor,
                fontSize = 18.sp,
                onClick = { onSelect("、") }
            )

            // 3. ?
            KeyButton(
                text = "?",
                modifier = Modifier.weight(0.85f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                bgColor = actionKeyBg,
                textColor = textColor,
                fontSize = 18.sp,
                onClick = { onSelect("?") }
            )

            // 4. Space (Tap -> Space, LongPress -> Change IME!)
            KeyButton(
                text = "Space",
                modifier = Modifier.weight(2.3f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                bgColor = keyBgColor,
                textColor = textColor,
                fontSize = 15.sp,
                onClick = onSpace,
                onLongClick = onLongPressSpace
            )

            // 5. 。
            KeyButton(
                text = "。",
                modifier = Modifier.weight(0.85f).padding(horizontal = 2.5.dp, vertical = 2.dp),
                bgColor = actionKeyBg,
                textColor = textColor,
                fontSize = 18.sp,
                onClick = { onSelect("。") }
            )

            // 6. ◀
            RepeatingIconButton(
                onClick = onCursorLeft,
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxHeight()
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg),
                initialDelayMs = 300L,
                repeatIntervalMs = 50L
            ) {
                Text(text = "◀", color = textColor, fontSize = 13.sp)
            }

            // 7. ▶
            RepeatingIconButton(
                onClick = onCursorRight,
                modifier = Modifier
                    .weight(0.85f)
                    .fillMaxHeight()
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg),
                initialDelayMs = 300L,
                repeatIntervalMs = 50L
            ) {
                Text(text = "▶", color = textColor, fontSize = 13.sp)
            }

            // 8. Backspace with hold-to-repeat
            RepeatingIconButton(
                onClick = onDelete,
                modifier = Modifier
                    .weight(1.35f)
                    .fillMaxHeight()
                    .padding(horizontal = 2.5.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(actionKeyBg)
            ) {
                Icon(
                    imageVector = Icons.Default.Backspace,
                    contentDescription = "Backspace",
                    tint = textColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
