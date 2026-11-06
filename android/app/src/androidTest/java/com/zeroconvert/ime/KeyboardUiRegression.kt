package com.zeroconvert.ime

import android.app.Activity
import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zeroconvert.ime.assist.AiAssistResult
import com.zeroconvert.ime.assist.AiAssistType
import java.util.concurrent.atomic.AtomicInteger

/** UI-only device regressions: no model, input history, selected IME or preferences are touched. */
class KeyboardUiRegression(private val instrumentation: Instrumentation) {
    private data class Case(
        val reading: String = "",
        val assist: AiAssistResult? = null,
        val applied: Boolean = false,
        val expanded: Boolean = false,
        val flick: Boolean = false,
        val keyHeight: Int = 46,
        val fontScale: Float = 1f,
        val candidates: List<Candidate> = listOf(Candidate("通常候補", "")),
        val emoji: Boolean = false,
        val revision: Int = 0
    )
    private val state = mutableStateOf(Case())
    private val renderedRevision = AtomicInteger(-1)
    private var frame = Rect()
    private var body = Rect()
    private var density = 1f
    private var adopted = 0
    private var undone = 0
    private var typed = ""
    private var emojiTyped = ""
    private val emojiCategories: List<EmojiCategory> by lazy {
        instrumentation.targetContext.assets.open("emoji_catalog.tsv")
            .bufferedReader(Charsets.UTF_8).useLines { EmojiCatalog.parse(it) }
    }
    private var emojiRecent = listOf("😀", "👍🏽")
    private val longText = "非常に長い先読み候補を表示してもキーの位置を変えずに入力を続ける".repeat(8)
    private val prediction = AiAssistResult(AiAssistType.PREDICTION, longText,
        "先読み: $longText", continuation = longText)

    private fun onUi(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 4000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(50)
        }
    }

    private fun update(value: Case) {
        val revision = state.value.revision + 1
        onUi { state.value = value.copy(revision = revision) }
        val deadline = SystemClock.uptimeMillis() + 4000
        while (renderedRevision.get() != revision) {
            check(SystemClock.uptimeMillis() < deadline) { "Compose did not render revision $revision" }
            SystemClock.sleep(20)
        }
        instrumentation.waitForIdleSync()
        // Compose batches accessibility updates separately from composition/layout.
        SystemClock.sleep(100)
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            }
            return null
        }
        for (window in instrumentation.uiAutomation.windows) {
            // A previously visible real IME may still have a window. Inspect only our fixture.
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive) continue
            window.root?.takeIf { it.packageName == instrumentation.targetContext.packageName }
                ?.let { visit(it)?.let { found -> return found } }
        }
        return null
    }

    /** BasicTextField exposes an editable child node, so the label alone is not enough. */
    private fun editableNode(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        for (window in instrumentation.uiAutomation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive) continue
            window.root?.takeIf { it.packageName == instrumentation.targetContext.packageName }
                ?.let { visit(it)?.let { found -> return found } }
        }
        return null
    }

    private fun readingNode(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            node.text?.toString()?.takeIf { it.startsWith("原文: ") || it.startsWith("補正済み: ") }
                ?.let { return node }
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        for (window in instrumentation.uiAutomation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive) continue
            window.root?.takeIf { it.packageName == instrumentation.targetContext.packageName }
                ?.let { visit(it)?.let { found -> return found } }
        }
        return null
    }

    private fun readingText(): String? = readingNode()?.text?.toString()

    private fun readingBounds(): Rect =
        Rect().also { checkNotNull(readingNode()) { "Assist bar did not render the reading" }.getBoundsInScreen(it) }

    /** The reading bar is the first row of the fixture, so it spans from the top to the candidate bar. */
    private fun readingBarHeight(): Int = bounds("変換候補 通常候補").top - frame.top

    private fun texts(): String {
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val found = ArrayList<String>()
        fun visit(node: AccessibilityNodeInfo) {
            node.text?.toString()?.let { found.add(it) }
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it) }
        }
        for (window in instrumentation.uiAutomation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive) continue
            window.root?.takeIf { it.packageName == instrumentation.targetContext.packageName }?.let { visit(it) }
        }
        return found.joinToString(" | ")
    }

    private fun bounds(label: String): Rect = Rect().also {
        val deadline = SystemClock.uptimeMillis() + 4000
        var found = node(label)
        while (found == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(50)
            found = node(label)
        }
        checkNotNull(found) { "Missing UI element: $label" }.getBoundsInScreen(it)
    }

    private fun touch(rect: Rect, action: Int, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
            rect.exactCenterX(), rect.exactCenterY(), 0)
        try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
        instrumentation.waitForIdleSync()
    }

    @Composable
    private fun Fixture() {
        val sample = state.value
        val actualDensity = LocalDensity.current
        density = actualDensity.density
        CompositionLocalProvider(
            LocalDensity provides Density(actualDensity.density, sample.fontScale),
            LocalKeyboardKeyHeight provides sample.keyHeight.dp
        ) {
            Column(Modifier.width(320.dp).onGloballyPositioned {
                val b = it.boundsInWindow()
                frame = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
            }) {
                // The emoji tab hides both bars, exactly like the real keyboard.
                if (!sample.emoji) {
                    KeyboardAssistBar(sample.reading, sample.assist, sample.applied, ZeroConDarkPalette,
                        onAdopt = { adopted++ },
                        onUndo = { undone++; state.value = state.value.copy(applied = false, assist = null) })
                    Row(Modifier.fillMaxWidth().height(KeyboardBarHeight)) {
                        HorizontalCandidateStrip(sample.reading, sample.candidates, ZeroConDarkPalette, {},
                            Modifier.weight(1f).fillMaxHeight())
                        Spacer(Modifier.width(48.dp))
                    }
                }
                Box(Modifier.onGloballyPositioned {
                    val b = it.boundsInWindow()
                    body = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                }) {
                    KeyboardContentArea(sample.keyHeight.dp, sample.expanded,
                        extraHeight = if (sample.emoji) KeyboardBarHeight * 2 else 0.dp,
                        expandedContent = {
                            ExpandedCandidatePanel(listOf(Candidate(longText, sample.reading)), true,
                                ZeroConDarkPalette, {}, {})
                        },
                        keyboardContent = {
                            if (sample.emoji) {
                                EmojiPanel(
                                    categories = emojiCategories,
                                    recent = emojiRecent,
                                    actionKeyBg = ZeroConDarkPalette.keyModifier,
                                    keyBgColor = ZeroConDarkPalette.keyNormal,
                                    textColor = ZeroConDarkPalette.textPrimary,
                                    palette = ZeroConDarkPalette,
                                    onSelect = { emojiTyped += it },
                                    onBackToQwerty = {},
                                    onDelete = {},
                                    onSpace = {},
                                    onEnter = {}
                                )
                            } else if (sample.flick) {
                                Japanese12KeyFlickLayout(
                                    keyBgColor = ZeroConDarkPalette.keyNormal,
                                    actionKeyBg = ZeroConDarkPalette.keyModifier,
                                    textColor = ZeroConDarkPalette.textPrimary,
                                    palette = ZeroConDarkPalette,
                                    rowHeight = sample.keyHeight.dp * (52f / 46f),
                                    onKanaInput = { typed += it }, onReplaceLastKana = {}, onToggleDakuten = {},
                                    onDelete = {}, onSpace = {}, onEnter = {}, onCycleMode = {},
                                    onOpenSymbols = {}, onOpenEmoji = {}, onCursorLeft = {}, onCursorRight = {}
                                )
                            } else {
                                JapaneseQwertyLayout(false, ZeroConDarkPalette.keyNormal,
                                    ZeroConDarkPalette.keyModifier, ZeroConDarkPalette.textPrimary,
                                    ZeroConDarkPalette,
                                    onKeyPress = { typed += it }, onShiftToggle = {}, onDelete = {},
                                    onSpace = {}, onEnter = {}, onCycleMode = {}, onOpenEmoji = {},
                                    isComposing = sample.reading.isNotEmpty(), onCursorLeft = {}, onCursorRight = {})
                            }
                        })
                }
            }
            SideEffect { renderedRevision.set(sample.revision) }
        }
    }

    fun run(): Pair<Int, Bundle> {
        var activity: PortTestActivity? = null
        val report = StringBuilder()
        var tests = 0
        var failures = 0
        fun test(name: String, action: () -> Unit) {
            tests++
            try { action(); report.append("PASS $name\n") }
            catch (failure: Throwable) {
                failures++; report.append("FAIL $name: ${failure.message}; frame=$frame body=$body\n")
                if (failures == 1) {
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "keyboard-ui-test.png")
                            .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    fun describe(node: AccessibilityNodeInfo, depth: Int) {
                        report.append("  ".repeat(depth) + "${node.className} ${node.text} ${node.contentDescription}\n")
                        if (depth < 5) for (index in 0 until node.childCount) node.getChild(index)?.let { describe(it, depth + 1) }
                    }
                    for (window in instrumentation.uiAutomation.windows) window.root?.let { describe(it, 0) }
                }
            }
        }
        try {
            val info = instrumentation.uiAutomation.serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            instrumentation.uiAutomation.serviceInfo = info
            val editor = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
                PortTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PortTestActivity
            activity = editor
            onUi { editor.setContent { Fixture() } }
            update(Case())
            for (flick in listOf(false, true)) {
                val label = if (flick) "あを入力" else "aを入力"
                test("stable_geometry_${if (flick) "flick" else "qwerty"}") {
                    for (height in listOf(40, 46, 64)) for (scale in listOf(1f, 2f)) {
                        val base = Case(flick = flick, keyHeight = height, fontScale = scale)
                        update(base)
                        val frameBefore = Rect(frame)
                        val bodyBefore = Rect(body)
                        val keyBefore = bounds(label)
                        val samples = listOf(
                            base.copy(reading = "あ"),
                            base.copy(reading = longText),
                            base.copy(reading = longText, assist = prediction),
                            base.copy(reading = longText, assist = prediction.copy(type = AiAssistType.TYPO_CORRECTION)),
                            base.copy(reading = longText, assist = prediction, applied = true),
                            base.copy(reading = longText, expanded = true),
                            base
                        )
                        for (sample in samples) {
                            update(sample)
                            check(frame == frameBefore && body == bodyBefore) { "Candidate update resized the IME" }
                            if (sample.expanded) check(node(label) == null) { "Expanded list exposes underlying keys" }
                            else check(bounds(label) == keyBefore) { "Candidate update moved the key" }
                        }
                    }
                }
                test("in_flight_touch_${if (flick) "flick" else "qwerty"}") {
                    update(Case(flick = flick, reading = "あ"))
                    typed = ""
                    val key = bounds(label)
                    val downTime = SystemClock.uptimeMillis()
                    touch(key, MotionEvent.ACTION_DOWN, downTime)
                    update(state.value.copy(assist = prediction))
                    touch(key, MotionEvent.ACTION_UP, downTime)
                    check(typed == if (flick) "あ" else "a") { "Touch lost or entered a different character: $typed" }
                }
            }
            val choices = List(12) { Candidate("候補${it}の表示", "かんじ", cost = it.toFloat()) }
            val candidateCase = Case(reading = "かんじ", candidates = choices)
            fun scrollAwayFromFirst() {
                check(checkNotNull(node("変換候補一覧"))
                    .performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) { "Candidate strip did not scroll" }
                waitFor("First candidate stayed visible after browsing") {
                    node("変換候補 ${choices.first().text}")?.isVisibleToUser != true
                }
                // Finish the accessibility scroll animation before comparing positions.
                SystemClock.sleep(350)
            }
            test("candidate_strip_new_reading_starts_at_first") {
                update(candidateCase)
                val initial = bounds("変換候補 ${choices.first().text}")
                scrollAwayFromFirst()
                // Until inference returns, the same old candidates may still be displayed.
                update(candidateCase.copy(reading = "かんじへ"))
                check(bounds("変換候補 ${choices.first().text}") == initial) { "New input retained an old scroll offset" }
            }
            test("candidate_strip_new_top_candidate_starts_at_first") {
                update(candidateCase)
                val initial = bounds("変換候補 ${choices.first().text}")
                scrollAwayFromFirst()
                val reranked = listOf(choices[5]) + choices.filterIndexed { index, _ -> index != 5 }
                update(candidateCase.copy(candidates = reranked))
                check(bounds("変換候補 ${reranked.first().text}").left == initial.left) {
                    "Reranking kept an old candidate anchored instead of showing the top result"
                }
            }
            test("candidate_strip_same_input_preserves_manual_browsing") {
                update(candidateCase)
                scrollAwayFromFirst()
                fun visiblePositions(): Map<String, Rect> = choices.mapNotNull { candidate ->
                    val label = "変換候補 ${candidate.text}"
                    node(label)?.takeIf { it.isVisibleToUser }?.let {
                        label to Rect().also { rect -> it.getBoundsInScreen(rect) }
                    }
                }.toMap()
                val before = visiblePositions()
                check(before.isNotEmpty())
                update(candidateCase.copy(candidates = choices.map { it.copy(cost = it.cost + 10f) }))
                check(visiblePositions() == before) { "Equivalent results reset manual browsing" }
                update(candidateCase.copy(candidates = choices + Candidate("追加候補", "かんじ")))
                check(visiblePositions() == before) { "Appending lower candidates reset manual browsing" }
            }
            test("assist_reading_keeps_newest_characters") {
                // The reading grows to the right, so an overflowing row must drop its oldest characters.
                val newest = "最新の入力です"
                update(Case(reading = longText + newest))
                val shown = checkNotNull(readingText()) { "Assist bar did not render the reading: " + texts() }
                check(shown.startsWith("原文: ")) { "Unexpected assist label: $shown" }
                check(shown.endsWith(newest)) { "Overflow dropped the newest characters: $shown" }
                check(shown.removePrefix("原文: ").startsWith("…")) { "Overflow must cut the oldest side: $shown" }
                check(shown.length < longText.length + newest.length) { "Reading was not truncated: $shown" }
                update(Case(reading = "あい"))
                check(readingText() == "原文: あい") { "Short reading must not be truncated" }
            }
            test("bars_follow_the_key_height_setting") {
                // The reading and candidate bars must scale with the key height, not stay at 48dp.
                val heights = listOf(40, 46, 64)
                val measured = heights.map { height ->
                    update(Case(keyHeight = height, reading = "あい"))
                    val reading = readingBarHeight()
                    val candidates = bounds("変換候補 通常候補").height()
                    check(reading == candidates) {
                        "Bars disagree at ${height}dp: reading $reading vs candidates $candidates"
                    }
                    height to reading
                }
                for ((keyHeight, barHeight) in measured) {
                    val expected = keyHeight * (48f / 46f) * density
                    check(kotlin.math.abs(barHeight - expected) <= 2f) {
                        "Bar height $barHeight does not follow ${keyHeight}dp keys (expected $expected)"
                    }
                }
                check(measured.map { it.second }.toSet().size == heights.size) {
                    "Bar height did not change with the key height: $measured"
                }
            }
            test("assist_reading_keeps_its_position") {
                // Assist results come and go while typing; the reading must not shift or resize.
                val reading = longText
                update(Case(reading = reading))
                val idle = readingBounds()
                check(idle.width() <= frame.width() / 2 + 1) { "Reading is not limited to the left half: $idle" }
                for (sample in listOf(
                    Case(reading = reading, assist = prediction),
                    Case(reading = reading, assist = prediction.copy(type = AiAssistType.TYPO_CORRECTION)),
                    Case(reading = reading, assist = prediction, applied = true),
                    Case(reading = reading)
                )) {
                    update(sample)
                    check(readingBounds() == idle) { "Assist result moved the reading: ${readingBounds()} vs $idle" }
                }
            }
            fun tap(label: String) {
                val rect = bounds(label)
                val downTime = SystemClock.uptimeMillis()
                touch(rect, MotionEvent.ACTION_DOWN, downTime)
                touch(rect, MotionEvent.ACTION_UP, downTime)
            }
            /** Clicks through the accessibility action, which does not depend on touch timing. */
            fun click(label: String) {
                var target = checkNotNull(node(label)) { "Missing UI element: $label" }
                repeat(4) {
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        instrumentation.waitForIdleSync()
                        SystemClock.sleep(100)
                        return
                    }
                    target = target.parent ?: return@repeat
                }
                error("$label rejected the click action")
            }
            /** Every category tab shares the row width, so it is always on screen. */
            fun selectCategory(categoryId: String) {
                val label = EmojiCatalog.labelFor(categoryId)
                check(node(label)?.isVisibleToUser == true) {
                    "Category tab $label is not visible: " + texts()
                }
                tap(label)
            }
            fun cellLabel(entry: EmojiEntry) =
                entry.keywords.firstOrNull()?.let { "$it ${entry.emoji}" } ?: entry.emoji
            test("emoji_panel_hides_the_reading_and_candidate_bars") {
                update(Case(reading = "あい", candidates = listOf(Candidate("愛", "あい"))))
                val qwertyFrame = Rect(frame)
                val qwertyBody = Rect(body)
                check(node("変換候補 愛") != null) { "Candidate strip missing before the emoji tab" }
                update(Case(emoji = true, reading = "あい", candidates = listOf(Candidate("愛", "あい"))))
                // The palette takes over the whole height, so the bars must be gone but the
                // IME must not resize when the user switches tabs.
                check(node("変換候補 愛") == null) { "Emoji tab kept the candidate strip: " + texts() }
                check(readingNode() == null) { "Emoji tab kept the reading bar: " + texts() }
                check(frame == qwertyFrame) { "Emoji tab resized the IME: $frame vs $qwertyFrame" }
                // Two 48dp bars at the current density moved into the palette.
                val reclaimed = (body.height() - qwertyBody.height()).toFloat()
                check(reclaimed >= 2 * 48 * density - 2) {
                    "Emoji grid did not reclaim the hidden bars: $body vs $qwertyBody"
                }
            }
            test("emoji_panel_categories_and_commit") {
                emojiTyped = ""
                update(Case(emoji = true))
                val frameBefore = Rect(frame)
                for (category in emojiCategories) {
                    selectCategory(category.id)
                    check(frame == frameBefore) { "Switching to ${category.id} resized the IME" }
                    val first = category.entries.first()
                    val cell = bounds(cellLabel(first))
                    check(cell.width() > 0 && cell.height() > 0) { "Empty cell for ${first.emoji}" }
                }
                // The recent tab is offered because the fixture has history.
                selectCategory(EmojiCatalog.RECENT_CATEGORY_ID)
                val recentLabel = cellLabel(emojiCategories.first { it.id == "smileys" }.entries.first())
                waitFor("Recent tab did not show the stored emoji: " + texts()) { node(recentLabel) != null }
                // Tapping a cell commits exactly that emoji.
                selectCategory("smileys")
                val target = emojiCategories.first { it.id == "smileys" }.entries.first()
                click(cellLabel(target))
                check(emojiTyped == target.emoji) { "Tapping committed '$emojiTyped' instead of '${target.emoji}'" }
                check(frame == frameBefore) { "Committing an emoji resized the IME" }
            }
            test("emoji_panel_search_filters_the_grid") {
                emojiTyped = ""
                update(Case(emoji = true))
                val field = checkNotNull(editableNode()) { "Search field missing: " + texts() }
                check(field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { "Search field did not focus" }
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "おやゆび")
                }
                check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    "Search field rejected text"
                }
                val thumbsUp = emojiCategories.first { it.id == "people" }.entries.first { it.emoji == "👍" }
                click(cellLabel(thumbsUp))
                check(emojiTyped == "👍") { "Search result committed '$emojiTyped'" }
            }
            test("emoji_panel_skin_tone_long_press") {
                emojiTyped = ""
                // Recreate the panel so a previous test's search query cannot hide the tabs.
                update(Case(emoji = false))
                update(Case(emoji = true))
                selectCategory("people")
                val thumbsUp = emojiCategories.first { it.id == "people" }.entries.first { it.emoji == "👍" }
                val cell = bounds(cellLabel(thumbsUp))
                val downTime = SystemClock.uptimeMillis()
                touch(cell, MotionEvent.ACTION_DOWN, downTime)
                SystemClock.sleep(700)
                touch(cell, MotionEvent.ACTION_UP, downTime)
                val tone = bounds(thumbsUp.skinTones.last())
                val tapTime = SystemClock.uptimeMillis()
                touch(tone, MotionEvent.ACTION_DOWN, tapTime)
                touch(tone, MotionEvent.ACTION_UP, tapTime)
                check(emojiTyped == thumbsUp.skinTones.last()) {
                    "Tone picker committed '$emojiTyped' instead of '${thumbsUp.skinTones.last()}'"
                }
            }
            test("assist_adopt_and_large_undo_target") {
                update(Case(reading = longText, assist = prediction, applied = true))
                val before = Rect(frame)
                val undo = bounds("補正を取り消す")
                check(undo.width() >= 48 * density - 1 && undo.height() >= 48 * density - 1)
                val adopt = bounds("先読み $longText を採用")
                var downTime = SystemClock.uptimeMillis()
                touch(adopt, MotionEvent.ACTION_DOWN, downTime)
                touch(adopt, MotionEvent.ACTION_UP, downTime)
                check(adopted == 1)
                downTime = SystemClock.uptimeMillis()
                touch(undo, MotionEvent.ACTION_DOWN, downTime)
                touch(undo, MotionEvent.ACTION_UP, downTime)
                check(undone == 1 && !state.value.applied)
                check(frame == before) { "Undo resized the IME" }
            }
        } catch (failure: Throwable) {
            failures++; report.append("FAIL setup: ${failure.message}\n")
        } finally { activity?.let { onUi { it.finish() } } }
        return (if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED) to Bundle().apply {
            putString("stream", "\n$report\nTests: $tests, failures: $failures\n")
            putInt("tests", tests)
            putInt("failures", failures)
        }
    }
}
