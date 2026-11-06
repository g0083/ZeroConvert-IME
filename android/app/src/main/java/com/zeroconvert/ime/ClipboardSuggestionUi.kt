package com.zeroconvert.ime

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    SettingsRow(
        title = "候補欄への表示時間",
        description = "追加してからの時間です。ピン留めした項目は候補欄に表示しません。",
        palette = palette
    ) {
        Box {
            SettingsSecondaryButton(
                text = label(minutes),
                palette = palette,
                onClick = { expanded = true },
                modifier = Modifier.semantics {
                    contentDescription = "クリップボード候補の表示時間"
                }
            )
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
}
