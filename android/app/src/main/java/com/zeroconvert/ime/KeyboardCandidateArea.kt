package com.zeroconvert.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroconvert.ime.assist.AiAssistResult
import com.zeroconvert.ime.assist.AiAssistType

@Composable
fun SelectionCountLabel(count: SelectionCharacterCount, palette: ZeroConPalette, modifier: Modifier = Modifier) {
    Box(modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text = count.label,
            color = palette.primaryAccentSoft,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A new reading or top result starts at the highest-ranked candidate, not an old key's position. */
@Composable
fun HorizontalCandidateStrip(
    reading: String,
    candidates: List<Candidate>,
    palette: ZeroConPalette,
    onSelect: (Candidate) -> Unit,
    modifier: Modifier = Modifier
) {
    // LazyRow otherwise retains its visible item by itemKey when conversion reranks the list.
    // Recreate only this viewport; ordinary updates with the same top result preserve browsing.
    key(reading, candidates.firstOrNull()?.itemKey) {
        LazyRow(
            modifier = modifier.semantics { contentDescription = "変換候補一覧" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(candidates.size, key = { candidates[it].itemKey }) { index ->
                val item = candidates[index]
                Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.fillMaxHeight()
                            .semantics { contentDescription = "変換候補 ${item.text}" }
                            .clickable { onSelect(item) }.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = (if (item.assist != null) "補正: " else "") + item.text,
                            color = if (index == 0 && item.isAI) palette.primaryAccentSoft else palette.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (index < candidates.size - 1) {
                        Box(Modifier.width(1.dp).height(18.dp).background(palette.borderSubtle))
                    }
                }
            }
        }
    }
}

/** Reserve this row even while idle: asynchronous results must never move the keys. */
@Composable
fun KeyboardAssistBar(
    reading: String,
    assist: AiAssistResult?,
    correctionApplied: Boolean,
    palette: ZeroConPalette,
    onAdopt: () -> Unit,
    onUndo: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).background(palette.surface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when {
                reading.isEmpty() -> "入力待ち"
                correctionApplied -> "補正済み: $reading"
                else -> "原文: $reading"
            },
            color = if (correctionApplied) palette.warningAmber else palette.textSecondary,
            fontSize = 13.sp,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        if (reading.isNotEmpty() && assist != null) {
            val isTypo = assist.type == AiAssistType.TYPO_CORRECTION
            val title = if (isTypo) "補正" else "先読み"
            val color = if (isTypo) palette.warningAmber else palette.primaryAccentSoft
            Surface(
                color = color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 2.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$title ${assist.text} を採用"
                    }
                    .clickable(onClick = onAdopt)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(text = title, color = color, fontSize = 12.sp, maxLines = 1, softWrap = false)
                    Text(
                        text = if (isTypo) assist.text else assist.continuation.ifEmpty { assist.text },
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (reading.isNotEmpty() && correctionApplied) {
            Row(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .semantics(mergeDescendants = true) { contentDescription = "補正を取り消す" }
                    .clickable(onClick = onUndo),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Undo, contentDescription = null, tint = palette.warningAmber,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text("戻す", color = palette.warningAmber, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** Expanded candidates occupy the key area, so opening/closing them cannot resize the IME. */
@Composable
fun KeyboardContentArea(
    keyHeight: Dp,
    expanded: Boolean,
    expandedContent: @Composable () -> Unit,
    keyboardContent: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(keyHeight * 5 + 35.dp)
            .padding(top = 4.dp, bottom = 6.dp, start = 3.dp, end = 3.dp)
    ) {
        CompositionLocalProvider(LocalKeyboardKeyHeight provides keyHeight) {
            if (expanded) expandedContent() else keyboardContent()
        }
    }
}

@Composable
fun ExpandedCandidatePanel(
    candidates: List<Candidate>,
    isRealtimeInline: Boolean,
    palette: ZeroConPalette,
    onToggleRealtimeInline: () -> Unit,
    onSelect: (Candidate) -> Unit
) {
    Column(Modifier.fillMaxSize().background(palette.surface).padding(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("候補一覧 (${candidates.size}件)", color = palette.textSecondary, fontSize = 12.sp,
                maxLines = 1, modifier = Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isRealtimeInline) palette.readyEmerald.copy(alpha = 0.2f) else palette.keyModifier,
                modifier = Modifier.fillMaxHeight().clickable(onClick = onToggleRealtimeInline)
            ) {
                Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isRealtimeInline) "リアルタイム反映: ON" else "リアルタイム反映: OFF",
                        color = if (isRealtimeInline) palette.readyEmerald else palette.textSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 100.dp),
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            items(candidates, key = { it.itemKey }) { item ->
                Box(
                    modifier = Modifier.padding(2.dp).height(48.dp)
                        .clip(RoundedCornerShape(6.dp)).background(palette.keyNormal)
                        .semantics { contentDescription = "変換候補 ${item.text}" }
                        .clickable { onSelect(item) }.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(item.text, color = palette.textPrimary, fontSize = 15.sp, maxLines = 1,
                        softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
