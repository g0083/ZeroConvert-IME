package com.zeroconvert.ime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Wake at expiry even when no key, selection, or clipboard event arrives. */
@Composable
fun rememberClipboardSuggestions(
    history: List<ClipboardEntry>,
    minutes: Int,
    dismissedIds: Set<Long> = emptySet(),
    enabled: Boolean = true,
): List<ClipboardEntry> {
    if (!enabled || minutes <= 0) return emptyList()
    val snapshot by remember(history) {
        derivedStateOf { if (history.isEmpty()) emptyList() else history.toList() }
    }
    if (snapshot.isEmpty()) return emptyList()
    var now by remember(snapshot, minutes, dismissedIds) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot, minutes, dismissedIds) {
        while (true) {
            val currentTime = System.currentTimeMillis()
            now = currentTime
            val active = ClipboardSuggestionPolicy.selectAll(snapshot, minutes, currentTime, dismissedIds)
            if (active.isEmpty()) break
            val nextExpiry = active.minOf {
                ClipboardSuggestionPolicy.remainingMillis(it, minutes, currentTime)
            }
            delay(nextExpiry.coerceAtLeast(1L))
        }
    }
    return ClipboardSuggestionPolicy.selectAll(snapshot, minutes, now, dismissedIds)
}

@Composable
fun rememberClipboardSuggestion(
    history: List<ClipboardEntry>,
    minutes: Int,
    dismissedIds: Set<Long> = emptySet(),
    enabled: Boolean = true,
): ClipboardEntry? = rememberClipboardSuggestions(history, minutes, dismissedIds, enabled).firstOrNull()

private fun clipboardEntityIcon(entityType: String?): ImageVector = when (entityType) {
    "phone" -> Icons.Default.Phone
    "email" -> Icons.Default.Email
    "url" -> Icons.Default.Link
    else -> Icons.Default.ContentCut
}

@Composable
fun ClipboardSuggestionStrip(
    suggestions: List<ClipboardEntry>,
    chipColor: Color,
    textColor: Color,
    palette: ZeroConPalette,
    onSelect: (ClipboardEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val topEntry = suggestions.firstOrNull()
    LaunchedEffect(topEntry?.id, topEntry?.timestamp) {
        if (suggestions.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val maxChipWidth = if (suggestions.size > 1) {
            (maxWidth * 0.65f).coerceAtLeast(96.dp).coerceAtMost(maxWidth)
        } else {
            maxWidth
        }
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "クリップボード候補一覧" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(
                items = suggestions,
                key = { it.id }
            ) { entry ->
                val entityLabel = ClipboardHistoryPolicy.entityBadgeLabel(entry.entityType)
                val isExtracted = entityLabel != null
                val chipBackground = if (isExtracted) {
                    palette.primaryAccent.copy(alpha = if (palette.isDark) 0.16f else 0.12f)
                } else {
                    chipColor
                }
                val chipBorder = if (isExtracted) {
                    BorderStroke(1.dp, palette.primaryAccentSoft.copy(alpha = 0.45f))
                } else {
                    null
                }
                val itemDescription = if (entityLabel != null) {
                    "クリップボード抜き出し候補 $entityLabel ${entry.preview}"
                } else {
                    "クリップボード候補 ${entry.preview}"
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = chipBackground,
                    border = chipBorder,
                    modifier = Modifier
                        .widthIn(max = maxChipWidth)
                        .semantics { contentDescription = itemDescription }
                        .pointerDownHaptic()
                        .clickable { onSelect(entry) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        if (entityLabel != null) {
                            Icon(
                                imageVector = clipboardEntityIcon(entry.entityType),
                                contentDescription = null,
                                tint = palette.primaryAccentSoft,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = palette.primaryAccent.copy(alpha = if (palette.isDark) 0.24f else 0.18f)
                            ) {
                                Text(
                                    text = entityLabel,
                                    color = palette.primaryAccentSoft,
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_clipboard),
                                contentDescription = null,
                                tint = textColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        if (entry.isImage && entry.imageUri != null) {
                            ClipboardImageThumbnail(
                                uri = entry.imageUri,
                                contentDescription = "Image",
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = entry.preview,
                            color = textColor,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
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
