package com.zeroconvert.ime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

data class ClipboardEditUiState(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val composingStart: Int = -1,
    val composingEnd: Int = -1
)

/**
 * Pure Kotlin [InputConnection] backed by Compose state so clipboard editing runs directly inside
 * the IME window without switching focus to a separate Activity.
 */
class ClipboardEditSession(
    val entry: ClipboardEntry,
    initialText: String,
    private val onSelectionChanged: (
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) -> Unit = { _, _, _, _, _, _ -> }
) : InputConnection {

    val uiState = mutableStateOf(
        ClipboardEditUiState(
            text = initialText,
            selectionStart = initialText.length,
            selectionEnd = initialText.length
        )
    )

    val currentText: String
        get() = uiState.value.text

    val editorInfo: EditorInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
    }

    private var batchDepth = 0
    private var pendingBatchNotify = false
    private var batchOldSelStart = initialText.length
    private var batchOldSelEnd = initialText.length

    private fun applyState(oldState: ClipboardEditUiState, newState: ClipboardEditUiState) {
        uiState.value = newState
        if (batchDepth > 0) {
            if (!pendingBatchNotify) {
                pendingBatchNotify = true
                batchOldSelStart = oldState.selectionStart
                batchOldSelEnd = oldState.selectionEnd
            }
        } else {
            onSelectionChanged(
                oldState.selectionStart,
                oldState.selectionEnd,
                newState.selectionStart,
                newState.selectionEnd,
                newState.composingStart,
                newState.composingEnd
            )
        }
    }

    private fun replaceComposingOrSelection(
        insert: String,
        newCursorPosition: Int,
        keepComposing: Boolean
    ): Boolean {
        val old = uiState.value
        val replaceStart: Int
        val replaceEnd: Int
        if (old.composingStart >= 0 &&
            old.composingEnd >= old.composingStart &&
            old.composingEnd <= old.text.length
        ) {
            replaceStart = old.composingStart
            replaceEnd = old.composingEnd
        } else {
            replaceStart = minOf(old.selectionStart, old.selectionEnd).coerceIn(0, old.text.length)
            replaceEnd = maxOf(old.selectionStart, old.selectionEnd).coerceIn(0, old.text.length)
        }
        val updatedText = old.text.substring(0, replaceStart) + insert + old.text.substring(replaceEnd)
        val rawCursor = if (newCursorPosition > 0) {
            replaceStart + insert.length + (newCursorPosition - 1)
        } else {
            replaceStart + newCursorPosition
        }
        val cursor = rawCursor.coerceIn(0, updatedText.length)
        val composingStart = if (keepComposing && insert.isNotEmpty()) replaceStart else -1
        val composingEnd = if (keepComposing && insert.isNotEmpty()) replaceStart + insert.length else -1
        applyState(
            old,
            ClipboardEditUiState(
                text = updatedText,
                selectionStart = cursor,
                selectionEnd = cursor,
                composingStart = composingStart,
                composingEnd = composingEnd
            )
        )
        return true
    }

    fun setUserSelection(start: Int, end: Int): Boolean = setSelection(start, end)

    fun setDirectText(newText: String) {
        val old = uiState.value
        applyState(
            old,
            ClipboardEditUiState(
                text = newText,
                selectionStart = newText.length,
                selectionEnd = newText.length
            )
        )
    }

    fun dispatchKeyCode(action: Int, keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    val state = uiState.value
                    val selMin = minOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
                    val selMax = maxOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
                    if (selMax > selMin) {
                        val old = state
                        val updatedText = old.text.substring(0, selMin) + old.text.substring(selMax)
                        val composingStart = if (old.composingStart >= 0 && old.composingEnd > old.composingStart) {
                            shiftDeletedRange(old.composingStart, selMin, selMax)
                        } else -1
                        val composingEnd = if (old.composingStart >= 0 && old.composingEnd > old.composingStart) {
                            shiftDeletedRange(old.composingEnd, selMin, selMax)
                        } else -1
                        val validComposing = composingStart >= 0 && composingEnd > composingStart
                        applyState(
                            old,
                            ClipboardEditUiState(
                                text = updatedText,
                                selectionStart = selMin,
                                selectionEnd = selMin,
                                composingStart = if (validComposing) composingStart else -1,
                                composingEnd = if (validComposing) composingEnd else -1
                            )
                        )
                    } else if (selMin > 0) {
                        val prev = Character.offsetByCodePoints(state.text, selMin, -1)
                        deleteSurroundingText(selMin - prev, 0)
                    }
                    true
                } else {
                    action == KeyEvent.ACTION_UP
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    val state = uiState.value
                    val selMin = minOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
                    val selMax = maxOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
                    val target = if (selMax > selMin) {
                        selMin
                    } else if (selMin > 0) {
                        Character.offsetByCodePoints(state.text, selMin, -1)
                    } else {
                        0
                    }
                    setSelection(target, target)
                    true
                } else {
                    action == KeyEvent.ACTION_UP
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    val state = uiState.value
                    val selMin = minOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
                    val selMax = maxOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
                    val target = if (selMax > selMin) {
                        selMax
                    } else if (selMax < state.text.length) {
                        Character.offsetByCodePoints(state.text, selMax, 1)
                    } else {
                        state.text.length
                    }
                    setSelection(target, target)
                    true
                } else {
                    action == KeyEvent.ACTION_UP
                }
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    commitText("\n", 1)
                } else {
                    action == KeyEvent.ACTION_UP
                }
            }
            else -> false
        }
    }

    private fun shiftDeletedRange(index: Int, delStart: Int, delEnd: Int): Int = when {
        index <= delStart -> index
        index <= delEnd -> delStart
        else -> index - (delEnd - delStart)
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        if (n < 0) return null
        val state = uiState.value
        val selMin = minOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
        val start = (selMin - n).coerceAtLeast(0)
        return state.text.substring(start, selMin)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        if (n < 0) return null
        val state = uiState.value
        val selMax = maxOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
        val end = (selMax + n).coerceAtMost(state.text.length)
        return state.text.substring(selMax, end)
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val state = uiState.value
        val selMin = minOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
        val selMax = maxOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
        if (selMin == selMax) return null
        return state.text.substring(selMin, selMax)
    }

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
        val state = uiState.value
        return ExtractedText().apply {
            text = state.text
            startOffset = 0
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = state.selectionStart
            selectionEnd = state.selectionEnd
            this.flags = 0
        }
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength < 0 || afterLength < 0) return false
        val old = uiState.value
        val selMin = minOf(old.selectionStart, old.selectionEnd).coerceIn(0, old.text.length)
        val selMax = maxOf(old.selectionStart, old.selectionEnd).coerceIn(0, old.text.length)
        val delBefore = beforeLength.coerceAtMost(selMin)
        val delAfter = afterLength.coerceAtMost(old.text.length - selMax)
        if (delBefore == 0 && delAfter == 0) return true

        val delBeforeStart = selMin - delBefore
        val delAfterStart = selMax - delBefore
        val updatedText = old.text.substring(0, delBeforeStart) +
            old.text.substring(selMin, selMax) +
            old.text.substring(selMax + delAfter)

        val newSelMin = delBeforeStart
        val newSelMax = delBeforeStart + (selMax - selMin)
        val newSelStart = if (old.selectionStart <= old.selectionEnd) newSelMin else newSelMax
        val newSelEnd = if (old.selectionStart <= old.selectionEnd) newSelMax else newSelMin

        var composingStart = -1
        var composingEnd = -1
        if (old.composingStart >= 0 && old.composingEnd > old.composingStart && old.composingEnd <= old.text.length) {
            var cs = shiftDeletedRange(old.composingStart, delBeforeStart, selMin)
            var ce = shiftDeletedRange(old.composingEnd, delBeforeStart, selMin)
            cs = shiftDeletedRange(cs, delAfterStart, delAfterStart + delAfter)
            ce = shiftDeletedRange(ce, delAfterStart, delAfterStart + delAfter)
            if (ce > cs) {
                composingStart = cs
                composingEnd = ce
            }
        }

        applyState(
            old,
            ClipboardEditUiState(
                text = updatedText,
                selectionStart = newSelStart,
                selectionEnd = newSelEnd,
                composingStart = composingStart,
                composingEnd = composingEnd
            )
        )
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength < 0 || afterLength < 0) return false
        val state = uiState.value
        val selMin = minOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
        val selMax = maxOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
        var beforeChars = 0
        var pos = selMin
        repeat(beforeLength) {
            if (pos <= 0) return@repeat
            val prev = Character.offsetByCodePoints(state.text, pos, -1)
            beforeChars += pos - prev
            pos = prev
        }
        var afterChars = 0
        pos = selMax
        repeat(afterLength) {
            if (pos >= state.text.length) return@repeat
            val next = Character.offsetByCodePoints(state.text, pos, 1)
            afterChars += next - pos
            pos = next
        }
        return deleteSurroundingText(beforeChars, afterChars)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        replaceComposingOrSelection(text?.toString().orEmpty(), newCursorPosition, keepComposing = true)

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val old = uiState.value
        val clampedStart = start.coerceIn(0, old.text.length)
        val clampedEnd = end.coerceIn(0, old.text.length)
        val min = minOf(clampedStart, clampedEnd)
        val max = maxOf(clampedStart, clampedEnd)
        val cs = if (min < max) min else -1
        val ce = if (min < max) max else -1
        if (old.composingStart == cs && old.composingEnd == ce) return true
        applyState(old, old.copy(composingStart = cs, composingEnd = ce))
        return true
    }

    override fun finishComposingText(): Boolean {
        val old = uiState.value
        if (old.composingStart == -1 && old.composingEnd == -1) return true
        applyState(old, old.copy(composingStart = -1, composingEnd = -1))
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        replaceComposingOrSelection(text?.toString().orEmpty(), newCursorPosition, keepComposing = false)

    override fun commitCompletion(text: CompletionInfo?): Boolean = false

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false

    override fun setSelection(start: Int, end: Int): Boolean {
        val old = uiState.value
        val s = start.coerceIn(0, old.text.length)
        val e = end.coerceIn(0, old.text.length)
        if (old.selectionStart == s && old.selectionEnd == e) return true
        applyState(old, old.copy(selectionStart = s, selectionEnd = e))
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean = commitText("\n", 1)

    fun selectAll(): Boolean = setSelection(0, uiState.value.text.length)

    override fun performContextMenuAction(id: Int): Boolean = when (id) {
        android.R.id.selectAll -> selectAll()
        else -> false
    }

    override fun beginBatchEdit(): Boolean {
        if (batchDepth == 0) {
            pendingBatchNotify = false
            val current = uiState.value
            batchOldSelStart = current.selectionStart
            batchOldSelEnd = current.selectionEnd
        }
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (batchDepth <= 0) return false
        batchDepth--
        if (batchDepth == 0 && pendingBatchNotify) {
            pendingBatchNotify = false
            val current = uiState.value
            onSelectionChanged(
                batchOldSelStart,
                batchOldSelEnd,
                current.selectionStart,
                current.selectionEnd,
                current.composingStart,
                current.composingEnd
            )
        }
        return batchDepth > 0
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        val nonNull = event ?: return false
        return dispatchKeyCode(nonNull.action, nonNull.keyCode)
    }

    override fun clearMetaKeyStates(states: Int): Boolean = true

    override fun reportFullscreenMode(enabled: Boolean): Boolean = false

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

    override fun getHandler(): Handler? = null

    override fun closeConnection() {}

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
}

/**
 * In-IME floating clipboard editor card rendered above the keyboard so editing never leaves the
 * host app or hides and re-shows the IME window.
 */
@Composable
fun ClipboardEditCard(
    session: ClipboardEditSession,
    palette: ZeroConPalette,
    isFloating: Boolean = false,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val state = session.uiState.value
    val scrollState = rememberScrollState()
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewportHeightPx by remember { mutableIntStateOf(0) }
    var cursorVisible by remember { mutableStateOf(true) }

    LaunchedEffect(state.text, state.selectionStart, state.selectionEnd) {
        cursorVisible = true
        while (true) {
            delay(530)
            cursorVisible = !cursorVisible
        }
    }

    LaunchedEffect(state.text, state.selectionEnd, textLayoutResult, viewportHeightPx) {
        val layout = textLayoutResult ?: return@LaunchedEffect
        if (viewportHeightPx <= 0) return@LaunchedEffect
        val offset = state.selectionEnd.coerceIn(0, layout.layoutInput.text.length)
        val cursorRect = layout.getCursorRect(offset)
        val currentScroll = scrollState.value
        if (cursorRect.bottom > currentScroll + viewportHeightPx) {
            scrollState.scrollTo((cursorRect.bottom - viewportHeightPx).roundToInt().coerceAtLeast(0))
        } else if (cursorRect.top < currentScroll) {
            scrollState.scrollTo(cursorRect.top.roundToInt().coerceAtLeast(0))
        }
    }

    val annotatedText = remember(state, palette) {
        buildAnnotatedString {
            append(state.text)
            val selMin = minOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
            val selMax = maxOf(state.selectionStart, state.selectionEnd).coerceIn(0, state.text.length)
            if (selMax > selMin) {
                addStyle(
                    SpanStyle(background = palette.primaryAccent.copy(alpha = 0.28f)),
                    start = selMin,
                    end = selMax
                )
            }
            if (state.composingStart >= 0 &&
                state.composingEnd > state.composingStart &&
                state.composingEnd <= state.text.length
            ) {
                addStyle(
                    SpanStyle(textDecoration = TextDecoration.Underline),
                    start = state.composingStart,
                    end = state.composingEnd
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.canvas)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "クリップボードを編集",
                color = palette.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "キャンセル",
                color = palette.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .height(if (isFloating) 110.dp else 140.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.surface)
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportHeightPx = it.height }
                    .pointerInput(session, scrollState) {
                        detectTapGestures { tapOffset ->
                            val layout = textLayoutResult ?: return@detectTapGestures
                            val contentOffset = Offset(tapOffset.x, tapOffset.y + scrollState.value)
                            val charOffset = layout.getOffsetForPosition(contentOffset)
                            session.setUserSelection(charOffset, charOffset)
                        }
                    }
                    .verticalScroll(scrollState)
            ) {
                if (state.text.isEmpty()) {
                    Text("内容を入力", color = palette.textMuted, fontSize = 16.sp)
                }
                Text(
                    text = annotatedText,
                    style = TextStyle(color = palette.textPrimary, fontSize = 16.sp),
                    onTextLayout = { textLayoutResult = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawWithContent {
                            drawContent()
                            val layout = textLayoutResult ?: return@drawWithContent
                            if (cursorVisible && state.selectionStart == state.selectionEnd) {
                                val offset = state.selectionEnd.coerceIn(0, layout.layoutInput.text.length)
                                val rect = layout.getCursorRect(offset)
                                val strokePx = 2.dp.toPx()
                                val left = rect.left.coerceIn(0f, (size.width - strokePx).coerceAtLeast(0f))
                                drawRect(
                                    color = palette.primaryAccent,
                                    topLeft = Offset(left, rect.top),
                                    size = Size(strokePx, (rect.bottom - rect.top).coerceAtLeast(1f))
                                )
                            }
                        }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.primaryAccent)
                .clickable(onClick = onSave),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "保存",
                color = palette.onAccent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Result channel for the clipboard editor. The IME service sets [onSave] before launching
 * [ClipboardEditActivity] and the activity clears it when it closes.
 */
object ClipboardEditBridge {
    @Volatile
    var onSave: ((String) -> Unit)? = null
}

/**
 * Text editor for one clipboard entry. The IME cannot type into its own view, so the edit
 * takes place in an app window where the active IME (this keyboard) works normally. The
 * window is transparent and the editor is a small floating card anchored above the keyboard
 * instead of a full-screen page.
 */
class ClipboardEditActivity : ComponentActivity() {

    companion object {
        const val EXTRA_TEXT = "com.zeroconvert.ime.extra.CLIPBOARD_EDIT_TEXT"
        private const val STATE_SESSION_ID = "clipboard_edit_session_id"

        fun createLaunchIntent(context: Context, text: String): Intent =
            Intent(context, ClipboardEditActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_TEXT, text)
            }
    }

    private val initialTextState = mutableStateOf("")
    private val sessionState = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        initialTextState.value = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        sessionState.intValue = savedInstanceState?.getInt(STATE_SESSION_ID, 0) ?: 0
        setContent {
            ClipboardEditScreen(
                sessionId = sessionState.intValue,
                initialText = initialTextState.value,
                onSave = { text ->
                    ClipboardEditBridge.onSave?.invoke(text)
                    closeEditor()
                },
                onCancel = {
                    closeEditor()
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initialTextState.value = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        sessionState.intValue += 1
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SESSION_ID, sessionState.intValue)
        super.onSaveInstanceState(outState)
    }

    private fun closeEditor() {
        ClipboardEditBridge.onSave = null
        finishAndRemoveTask()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        // Rotation recreates the activity without finishing, so only a real close drops the
        // pending result.
        if (isFinishing) ClipboardEditBridge.onSave = null
        super.onDestroy()
    }
}

@Composable
private fun ClipboardEditScreen(
    sessionId: Int,
    initialText: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    BackHandler(onBack = onCancel)
    val context = LocalContext.current
    val themeMode = remember {
        com.zeroconvert.ime.theme.ThemePreferences.readAppThemeMode(
            context.getSharedPreferences(
                ZeroConvertInputMethodService.PREFS_SETTINGS,
                Context.MODE_PRIVATE
            )
        )
    }
    val palette = rememberZeroConPalette(themeMode)
    var text by rememberSaveable(sessionId, initialText) { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(sessionId) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCancel
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .navigationBarsPadding()
                .padding(12.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(palette.canvas)
                .border(1.dp, palette.borderSubtle, RoundedCornerShape(16.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "クリップボードを編集",
                    color = palette.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "キャンセル",
                    color = palette.textSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .height(200.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.surface)
                    .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = palette.textPrimary, fontSize = 16.sp),
                    cursorBrush = SolidColor(palette.primaryAccent),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .verticalScroll(rememberScrollState()),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text("内容を入力", color = palette.textMuted, fontSize = 16.sp)
                        }
                        inner()
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.primaryAccent)
                    .clickable { onSave(text) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "保存",
                    color = palette.onAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
