package com.zeroconvert.ime

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Wake at expiry even when no key, selection, or clipboard event arrives. */
@Composable
fun rememberClipboardSuggestion(history: List<ClipboardEntry>, minutes: Int): ClipboardEntry? {
    val snapshot = history.toList()
    var now by remember(snapshot, minutes) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot, minutes) {
        while (true) {
            val currentTime = System.currentTimeMillis()
            now = currentTime
            val entry = ClipboardSuggestionPolicy.select(snapshot, minutes, currentTime) ?: break
            delay(ClipboardSuggestionPolicy.remainingMillis(entry, minutes, currentTime))
        }
    }
    return ClipboardSuggestionPolicy.select(snapshot, minutes, now)
}

@Composable
fun ClipboardSuggestionDurationSetting(minutes: Int, palette: ZeroConPalette, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    fun label(value: Int) = if (value == 0) "表示しない" else "${value}分"
    Column {
        Text("候補欄への表示時間", color = palette.textPrimary, fontSize = 13.sp)
        Text("追加してからの時間です。ピン留めした項目は候補欄に表示しません。",
            color = palette.textSecondary, fontSize = 11.sp)
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics {
            contentDescription = "クリップボード候補の表示時間"
        }) { Text(label(minutes), color = palette.primaryAccent) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ClipboardSuggestionPolicy.minuteOptions.forEach { value ->
                DropdownMenuItem(text = { Text(label(value)) }, onClick = {
                    expanded = false
                    onChange(value)
                })
            }
        }
    }
}
