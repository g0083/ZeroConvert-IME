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
        CompositionLocalProvider(LocalDensity provides Density(actualDensity.density, sample.fontScale)) {
            Column(Modifier.width(320.dp).onGloballyPositioned {
                val b = it.boundsInWindow()
                frame = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
            }) {
                KeyboardAssistBar(sample.reading, sample.assist, sample.applied, ZeroConDarkPalette,
                    onAdopt = { adopted++ },
                    onUndo = { undone++; state.value = state.value.copy(applied = false, assist = null) })
                Row(Modifier.fillMaxWidth().height(48.dp)) {
                    HorizontalCandidateStrip(sample.reading, sample.candidates, ZeroConDarkPalette, {},
                        Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(48.dp))
                }
                Box(Modifier.onGloballyPositioned {
                    val b = it.boundsInWindow()
                    body = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                }) {
                    KeyboardContentArea(sample.keyHeight.dp, sample.expanded,
                        expandedContent = {
                            ExpandedCandidatePanel(listOf(Candidate(longText, sample.reading)), true,
                                ZeroConDarkPalette, {}, {})
                        },
                        keyboardContent = {
                            if (sample.flick) {
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
