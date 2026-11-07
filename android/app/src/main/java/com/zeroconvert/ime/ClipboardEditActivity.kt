package com.zeroconvert.ime

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        setContent {
            ClipboardEditScreen(
                initialText = initialText,
                onSave = { text ->
                    ClipboardEditBridge.onSave?.invoke(text)
                    ClipboardEditBridge.onSave = null
                    finish()
                },
                onCancel = {
                    ClipboardEditBridge.onSave = null
                    finish()
                }
            )
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
    initialText: String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val themeMode = remember {
        context.getSharedPreferences(
            ZeroConvertInputMethodService.PREFS_SETTINGS,
            Context.MODE_PRIVATE
        ).getString(ZeroConvertInputMethodService.KEY_THEME_MODE, "system") ?: "system"
    }
    val palette = rememberZeroConPalette(themeMode)
    var text by rememberSaveable { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
