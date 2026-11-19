package com.zeroconvert.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroconvert.ime.assist.AiAssistResult
import com.zeroconvert.ime.assist.AiAssistType
import com.zeroconvert.ime.assist.CorrectionSession

/**
 * Height of the reading and candidate bars: 80% of the key height setting, so the bars stay
 * compact while still following the keys.
 */
val KeyboardBarHeight: Dp
    @Composable get() = LocalKeyboardKeyHeight.current * 0.8f

/**
 * Space the key area adds around the rows: the 10dp vertical padding plus the slack that
 * `Arrangement.SpaceEvenly` distributes between them. Shared by the IME and the settings preview.
 */
val KeyboardContentExtraHeight: Dp = 35.dp

/** Vertical padding inside the key area (4dp above the rows, 6dp below). */
val KeyboardContentPadding: Dp = 10.dp

/**
 * Height of the key area. Hiding the QWERTY number row makes four rows the baseline for the
 * whole keyboard, so the numeric and emoji modes shrink to it instead of growing past it.
 */
fun keyboardContentHeight(keyHeight: Dp, rowCount: Int): Dp =
    keyHeight * rowCount + KeyboardContentExtraHeight

/**
 * Row height that fits [rowCount] fixed-height rows into the key area sized for
 * [targetRowCount] rows. The 12-key layout needs this: it stacks five rows but has to keep
 * the four-row height of the QWERTY layout.
 */
fun keyboardRowHeight(keyHeight: Dp, rowCount: Int, targetRowCount: Int): Dp =
    (keyboardContentHeight(keyHeight, targetRowCount) - KeyboardContentPadding) / rowCount

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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HorizontalCandidateStrip(
    reading: String,
    candidates: List<Candidate>,
    palette: ZeroConPalette,
    onSelect: (Candidate) -> Unit,
    modifier: Modifier = Modifier,
    onForget: ((Candidate) -> Unit)? = null
) {
    var pendingForget by remember { mutableStateOf<Candidate?>(null) }
    val candidate = pendingForget
    // The IME owns no activity window token, so a Dialog (and any Popup) fails with
    // BadTokenException. Confirmation is drawn inline in the candidate row instead.
    if (candidate != null) {
        Row(
            modifier = modifier.semantics { contentDescription = "学習削除の確認" }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "「${candidate.text}」の学習を削除",
                color = palette.textPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            TextButton(onClick = {
                pendingForget = null
                onForget?.invoke(candidate)
            }) { Text("削除", fontSize = 13.sp) }
            TextButton(onClick = { pendingForget = null }) { Text("キャンセル", fontSize = 13.sp) }
        }
        return
    }
    // LazyRow otherwise retains its visible item by itemKey when conversion reranks the list.
    // Recreate only this viewport; ordinary updates with the same top result preserve browsing.
    key(reading, candidates.firstOrNull()?.itemKey) {
        LazyRow(
            modifier = modifier.semantics { contentDescription = "変換候補一覧" },
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Index into the snapshot the composition was built from. Deletion
            // removes an entry asynchronously, so a stale index would throw.
            itemsIndexed(
                items = candidates,
                key = { index, item -> "$index:${item.itemKey}" }
            ) { index, item ->
                Row(Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    val canForget = onForget != null && item.engineCandidateId.isNotEmpty()
                    Box(
                        modifier = Modifier.fillMaxHeight()
                            .semantics {
                                contentDescription = "変換候補 ${item.text}"
                                // combinedClickable does not publish a long-click action,
                                // so assistive tech and device tests could not reach it.
                                if (canForget) {
                                    onLongClick(label = "学習を削除") {
                                        pendingForget = item
                                        true
                                    }
                                }
                            }
                            .then(
                                if (canForget) {
                                    Modifier.combinedClickable(
                                        onClick = { onSelect(item) },
                                        onLongClick = { pendingForget = item }
                                    )
                                } else {
                                    Modifier.clickable { onSelect(item) }
                                }
                            ).padding(horizontal = 10.dp),
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
        modifier = Modifier.fillMaxWidth().height(KeyboardBarHeight).background(palette.surface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val label = when {
            reading.isEmpty() -> "入力待ち"
            correctionApplied -> "補正済み: "
            else -> "原文: "
        }
        val fontSize = 13.sp
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        // Fixed left half: an assist result appearing or disappearing must not resize the reading.
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(0.5f).fillMaxHeight().padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // The reading grows to the right, so overflow drops the oldest characters instead of the newest.
            val availableWidth = if (constraints.hasBoundedWidth) with(density) { maxWidth.roundToPx() } else -1
            val textStyle = LocalTextStyle.current.copy(fontSize = fontSize)
            val text = remember(reading, label, availableWidth, textStyle, density.density, density.fontScale) {
                if (reading.isEmpty() || availableWidth < 0) {
                    label + reading
                } else {
                    label + TrailingTextFit.fit(reading) { candidate ->
                        measurer.measure(label + candidate, textStyle, softWrap = false, maxLines = 1)
                            .size.width <= availableWidth
                    }
                }
            }
            Text(
                text = text,
                color = if (correctionApplied) palette.warningAmber else palette.textSecondary,
                fontSize = fontSize,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip
            )
        }
        // The assist slot keeps its width while idle so results never reflow the reading.
        Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 2.dp)) {
            if (reading.isNotEmpty() && assist != null) {
                val isTypo = assist.type == AiAssistType.TYPO_CORRECTION
                val title = if (isTypo) "補正" else "先読み"
                val color = if (isTypo) palette.warningAmber else palette.primaryAccentSoft
                Surface(
                    color = color.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics(mergeDescendants = true) {
                            contentDescription = "$title ${assist.text} を採用"
                        }
                        .pointerDownHaptic()
                        .clickable(onClick = onAdopt)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(text = title, color = color, fontSize = 12.sp, maxLines = 1, softWrap = false)
                        if (isTypo) {
                            val sourceText = if (CorrectionSession.isReadingOnlyCorrection(assist.text)) {
                                assist.sourceReading.ifEmpty { reading }
                            } else {
                                assist.sourceSurface.ifEmpty {
                                    assist.sourceReading.ifEmpty { reading }
                                }
                            }
                            val textFontSize = 14.sp
                            val baseStyle = LocalTextStyle.current.copy(fontSize = textFontSize)
                            val highlightBg = color.copy(alpha = 0.24f)
                            val originalColor = palette.textPrimary.copy(alpha = 0.72f)
                            BoxWithConstraints(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                val availableWidth = if (constraints.hasBoundedWidth) {
                                    with(density) { maxWidth.roundToPx() }
                                } else {
                                    -1
                                }
                                val rendered = remember(
                                    sourceText,
                                    assist.text,
                                    availableWidth,
                                    baseStyle,
                                    density.density,
                                    density.fontScale,
                                    originalColor,
                                    palette.textPrimary,
                                    highlightBg
                                ) {
                                    val toAnnotated: (FocusedCorrectionParts) -> AnnotatedString = { parts ->
                                        parts.toAnnotatedString(
                                            originalColor = originalColor,
                                            targetColor = palette.textPrimary,
                                            highlightBackground = highlightBg
                                        )
                                    }
                                    val parts = if (availableWidth < 0) {
                                        FocusedCorrectionFormat.fit(sourceText, assist.text) { true }
                                    } else {
                                        FocusedCorrectionFormat.fit(sourceText, assist.text) { candidate ->
                                            measurer.measure(
                                                toAnnotated(candidate),
                                                baseStyle,
                                                softWrap = false,
                                                maxLines = 1
                                            ).size.width <= availableWidth
                                        }
                                    }
                                    toAnnotated(parts)
                                }
                                Text(
                                    text = rendered,
                                    fontSize = textFontSize,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Clip
                                )
                            }
                        } else {
                            Text(
                                text = assist.continuation.ifEmpty { assist.text },
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
            }
        }
        if (reading.isNotEmpty() && correctionApplied) {
            Row(
                modifier = Modifier
                    .width(80.dp)
                    .fillMaxHeight()
                    .semantics(mergeDescendants = true) { contentDescription = "補正を取り消す" }
                    .pointerDownHaptic()
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

/**
 * Expanded candidates occupy the key area, so opening/closing them cannot resize the IME.
 * `extraHeight` reclaims the space of hidden bars (the emoji tab drops the reading and
 * candidate bars) so the panel grows instead of leaving a gap.
 */
@Composable
fun KeyboardContentArea(
    keyHeight: Dp,
    expanded: Boolean,
    rowCount: Int = 5,
    extraHeight: Dp = 0.dp,
    expandedContent: @Composable () -> Unit,
    keyboardContent: @Composable () -> Unit
) {
    // Keep outer height strictly equal to keyboardContentHeight(keyHeight, rowCount) + extraHeight,
    // while avoiding outer horizontal/vertical dead-zone padding around interactive key layouts.
    Box(
        modifier = Modifier.fillMaxWidth().height(keyboardContentHeight(keyHeight, rowCount) + extraHeight)
    ) {
        CompositionLocalProvider(LocalKeyboardKeyHeight provides keyHeight) {
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 4.dp, bottom = 6.dp, start = 3.dp, end = 3.dp)
                ) {
                    expandedContent()
                }
            } else {
                ModeSwitchGuideOverlay(keyboardContent)
            }
        }
    }
}

internal data class PackedCandidate(
    val index: Int,
    val candidate: Candidate
)

internal object ExpandedCandidateLayout {
    fun packRows(
        candidates: List<Candidate>,
        itemWidthsPx: List<Int>,
        maxRowWidthPx: Int,
        horizontalGapPx: Int
    ): List<List<PackedCandidate>> {
        if (candidates.isEmpty()) return emptyList()
        val safeMaxWidth = maxRowWidthPx.coerceAtLeast(1)
        val safeGap = horizontalGapPx.coerceAtLeast(0)
        val rows = ArrayList<List<PackedCandidate>>()
        var currentRow = ArrayList<PackedCandidate>()
        var currentWidth = 0
        for (index in candidates.indices) {
            val rawWidth = itemWidthsPx.getOrElse(index) { safeMaxWidth }
            val itemWidth = rawWidth.coerceIn(1, safeMaxWidth)
            val neededWidth = if (currentRow.isEmpty()) itemWidth else currentWidth + safeGap + itemWidth
            if (currentRow.isNotEmpty() && neededWidth > safeMaxWidth) {
                rows.add(currentRow)
                currentRow = ArrayList()
                currentRow.add(PackedCandidate(index, candidates[index]))
                currentWidth = itemWidth
            } else {
                currentRow.add(PackedCandidate(index, candidates[index]))
                currentWidth = neededWidth
            }
        }
        if (currentRow.isNotEmpty()) {
            rows.add(currentRow)
        }
        return rows
    }
}

internal fun candidateDisplayLabel(candidate: Candidate): String =
    (if (candidate.assist != null) "補正: " else "") + candidate.text

internal enum class CandidateBarContentMode {
    TRANSLATION,
    SELECTION_TRANSLATION,
    SELECTION_ACTIONS,
    CANDIDATE_STRIP,
    IDLE_TOOLBAR
}

internal data class CandidateBarDisplayPolicy(
    val mode: CandidateBarContentMode,
    val showClipboardSuggestion: Boolean,
    val showKeyboardPlacementMenu: Boolean,
    val showCandidateToggle: Boolean,
    val isCandidateToggleExpanded: Boolean
) {
    companion object {
        fun resolve(
            hasCandidates: Boolean,
            isCandidatesExpanded: Boolean,
            hasSelectionCount: Boolean,
            translationActive: Boolean,
            selectionTranslationActive: Boolean,
            clipboardSuggestionDismissed: Boolean = false
        ): CandidateBarDisplayPolicy {
            val mode = when {
                translationActive -> CandidateBarContentMode.TRANSLATION
                selectionTranslationActive -> CandidateBarContentMode.SELECTION_TRANSLATION
                hasSelectionCount -> CandidateBarContentMode.SELECTION_ACTIONS
                hasCandidates && !isCandidatesExpanded -> CandidateBarContentMode.CANDIDATE_STRIP
                else -> CandidateBarContentMode.IDLE_TOOLBAR
            }
            val showClipboardSuggestion = mode == CandidateBarContentMode.IDLE_TOOLBAR &&
                !clipboardSuggestionDismissed
            val showCandidateToggle = hasCandidates &&
                (mode == CandidateBarContentMode.CANDIDATE_STRIP || mode == CandidateBarContentMode.IDLE_TOOLBAR)
            return CandidateBarDisplayPolicy(
                mode = mode,
                showClipboardSuggestion = showClipboardSuggestion,
                showKeyboardPlacementMenu = mode == CandidateBarContentMode.IDLE_TOOLBAR,
                showCandidateToggle = showCandidateToggle,
                isCandidateToggleExpanded = showCandidateToggle && isCandidatesExpanded
            )
        }
    }
}

@Composable
fun ExpandedCandidatePanel(
    candidates: List<Candidate>,
    palette: ZeroConPalette,
    onSelect: (Candidate) -> Unit
) {
    val listState = rememberLazyListState()
    val topCandidateKey = candidates.firstOrNull()?.itemKey
    LaunchedEffect(topCandidateKey) {
        listState.scrollToItem(0)
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
        val firstTextStyle = textStyle.copy(fontWeight = FontWeight.Medium)
        val maxRowWidthDp = maxWidth
        val maxRowWidthPx = if (constraints.hasBoundedWidth) {
            constraints.maxWidth.coerceAtLeast(1)
        } else {
            with(density) { 320.dp.roundToPx() }
        }
        val horizontalGapPx = with(density) { 4.dp.roundToPx() }
        val chipHorizontalPaddingPx = with(density) { 16.dp.roundToPx() }
        val minChipWidthPx = with(density) { 44.dp.roundToPx() }
        val rows = remember(
            candidates,
            maxRowWidthPx,
            textStyle,
            firstTextStyle,
            density.density,
            density.fontScale
        ) {
            val widths = candidates.mapIndexed { index, item ->
                val textWidth = measurer.measure(
                    text = candidateDisplayLabel(item),
                    style = if (index == 0) firstTextStyle else textStyle,
                    softWrap = false,
                    maxLines = 1
                ).size.width
                // 2px buffer avoids 1px rounding wraps inside the Row.
                (textWidth + chipHorizontalPaddingPx + 2).coerceAtLeast(minChipWidthPx)
            }
            ExpandedCandidateLayout.packRows(
                candidates = candidates,
                itemWidthsPx = widths,
                maxRowWidthPx = maxRowWidthPx,
                horizontalGapPx = horizontalGapPx
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(
                items = rows,
                key = { rowIndex, row -> "${rowIndex}_${row.first().candidate.itemKey}" }
            ) { _, row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (entry in row) {
                        val item = entry.candidate
                        Box(
                            modifier = Modifier
                                .widthIn(min = 44.dp, max = maxRowWidthDp)
                                .height(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(palette.keyNormal)
                                .semantics { contentDescription = "変換候補 ${item.text}" }
                                .clickable { onSelect(item) }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = candidateDisplayLabel(item),
                                color = if (entry.index == 0 && item.isAI) {
                                    palette.primaryAccentSoft
                                } else {
                                    palette.textPrimary
                                },
                                fontSize = 15.sp,
                                fontWeight = if (entry.index == 0) FontWeight.Medium else FontWeight.Normal,
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
}
