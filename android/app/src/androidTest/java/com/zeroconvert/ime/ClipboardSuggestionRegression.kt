package com.zeroconvert.ime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicReference

/** Exercise the actual timer and settings controls using synthetic, isolated clipboard history. */
class ClipboardSuggestionRegression(private val instrumentation: Instrumentation) {
    private val history = mutableStateListOf<ClipboardEntry>()
    private val minutes = mutableStateOf(ClipboardSuggestionPolicy.DEFAULT_MINUTES)
    private val dismissedIds = mutableStateOf<Set<Long>>(emptySet())
    @Volatile private var displayed: ClipboardEntry? = null
    @Volatile private var displayedAll: List<ClipboardEntry> = emptyList()
    private val revision = mutableStateOf(0)
    @Volatile private var renderedRevision = -1

    @Composable private fun Fixture() {
        val currentRevision = revision.value
        val suggestions = rememberClipboardSuggestions(history, minutes.value, dismissedIds.value)
        val suggestion = suggestions.firstOrNull()
        Column {
            Text(suggestion?.preview ?: "候補なし")
            if (suggestions.isNotEmpty()) {
                ClipboardSuggestionStrip(
                    suggestions = suggestions,
                    chipColor = ZeroConDarkPalette.keyModifier,
                    textColor = ZeroConDarkPalette.textPrimary,
                    palette = ZeroConDarkPalette,
                    onSelect = { dismissedIds.value = dismissedIds.value + it.id },
                    modifier = Modifier.fillMaxWidth().height(36.dp)
                )
            }
            ClipboardSuggestionDurationSetting(minutes.value, ZeroConDarkPalette) { minutes.value = it }
        }
        SideEffect {
            displayed = suggestion
            displayedAll = suggestions
            renderedRevision = currentRevision
        }
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 4000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(25)
        }
    }

    private fun update(entries: List<ClipboardEntry>, duration: Int = 3) {
        var expectedRevision = -1
        instrumentation.runOnMainSync {
            history.clear(); history.addAll(entries); minutes.value = duration
            dismissedIds.value = emptySet()
            revision.value += 1
            expectedRevision = revision.value
        }
        waitFor("Fixture did not render") { renderedRevision == expectedRevision }
    }

    private fun click(label: String) {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        waitFor("Missing clickable control: $label") {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            var node = instrumentation.uiAutomation.windows.asSequence().mapNotNull { it.root }
                .mapNotNull { visit(it) }.firstOrNull()
            repeat(8) {
                val current = node ?: return@repeat
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@waitFor true
                node = current.parent
            }
            false
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
            catch (failure: Throwable) { failures++; report.append("FAIL $name: ${failure.message}\n") }
        }
        try {
            val info = instrumentation.uiAutomation.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            instrumentation.uiAutomation.serviceInfo = info
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
                PortTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PortTestActivity
            val editor = activity
            instrumentation.runOnMainSync { editor.setContent { Fixture() } }
            test("pinned_entries_are_never_suggestions") {
                update(listOf(ClipboardEntry(text = "固定検証", pinned = true)))
                check(displayed == null)
            }
            test("newest_unpinned_entry_is_shown") {
                val now = System.currentTimeMillis()
                update(listOf(ClipboardEntry(text = "固定検証", pinned = true),
                    ClipboardEntry(text = "新着検証", timestamp = now - 100),
                    ClipboardEntry(text = "古い検証", timestamp = now - 120_000)))
                check(displayed?.text == "新着検証")
            }
            test("expired_entries_stay_in_history_without_suggestions") {
                update(listOf(ClipboardEntry(text = "期限切れ検証", timestamp = System.currentTimeMillis() - 180_000)))
                check(displayed == null && history.size == 1)
            }
            test("suggestion_expires_without_typing") {
                update(listOf(ClipboardEntry(text = "タイマー検証", timestamp = System.currentTimeMillis() - 178_500)))
                check(displayed?.text == "タイマー検証")
                waitFor("Timer did not remove the suggestion") { displayed == null }
                check(history.size == 1)
            }
            test("pinning_a_visible_entry_removes_it_immediately") {
                val entry = ClipboardEntry(text = "ピン切替検証")
                update(listOf(entry))
                check(displayed?.id == entry.id)
                update(listOf(entry.copy(pinned = true)))
                check(displayed == null)
            }
            test("settings_control_changes_duration_and_disables_suggestions") {
                update(listOf(ClipboardEntry(text = "設定検証", timestamp = System.currentTimeMillis() - 120_000)))
                check(displayed?.text == "設定検証")
                click("クリップボード候補の表示時間"); click("1分")
                waitFor("Shorter duration did not hide the old entry") { minutes.value == 1 && displayed == null }
                click("クリップボード候補の表示時間"); click("5分")
                waitFor("Longer duration was not applied") { minutes.value == 5 && displayed?.text == "設定検証" }
                click("クリップボード候補の表示時間"); click("表示しない")
                waitFor("Disabled setting still showed a suggestion") { minutes.value == 0 && displayed == null }
            }
            test("images_use_the_same_deadline_and_pinned_filter") {
                val image = ClipboardEntry(imageUri = Uri.parse("content://example.test/synthetic-image"))
                update(listOf(image))
                check(displayed?.isImage == true)
                update(listOf(image.copy(pinned = true)))
                check(displayed == null)
            }
            test("multiple_suggestions_display_and_expire_individually") {
                val now = System.currentTimeMillis()
                update(
                    listOf(
                        ClipboardEntry(text = "新しい項目", timestamp = now - 10_000),
                        ClipboardEntry(text = "まもなく期限切れ", timestamp = now - 178_500)
                    )
                )
                check(displayedAll.map { it.text } == listOf("新しい項目", "まもなく期限切れ"))
                waitFor("Older suggestion did not expire independently") {
                    displayedAll.map { it.text } == listOf("新しい項目")
                }
            }
            test("pasting_one_suggestion_keeps_remaining_suggestions_visible") {
                val now = System.currentTimeMillis()
                update(
                    listOf(
                        ClipboardEntry(text = "1件目", timestamp = now - 1_000),
                        ClipboardEntry(text = "2件目", timestamp = now - 2_000)
                    )
                )
                check(displayedAll.map { it.text } == listOf("1件目", "2件目"))
                click("クリップボード候補 1件目")
                waitFor("Pasting one suggestion dismissed remaining suggestions") {
                    displayedAll.map { it.text } == listOf("2件目")
                }
            }
            test("editing_clipboard_entry_does_not_bring_main_activity_to_front") {
                fun lifecycleState(target: ComponentActivity): Lifecycle.State {
                    var state = Lifecycle.State.DESTROYED
                    instrumentation.runOnMainSync { state = target.lifecycle.currentState }
                    return state
                }
                fun findNodeText(targetText: String): Boolean {
                    if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
                    fun visit(node: AccessibilityNodeInfo): Boolean {
                        if (node.text?.toString() == targetText) return true
                        for (index in 0 until node.childCount) {
                            if (node.getChild(index)?.let(::visit) == true) return true
                        }
                        return false
                    }
                    return instrumentation.uiAutomation.windows.asSequence()
                        .mapNotNull { it.root }
                        .any { visit(it) }
                }
                var mainActivity: MainActivity? = null
                var foregroundEditor: PortTestActivity? = null
                try {
                    mainActivity = instrumentation.startActivitySync(
                        Intent(instrumentation.targetContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ) as MainActivity
                    foregroundEditor = instrumentation.startActivitySync(
                        Intent(instrumentation.targetContext, PortTestActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    ) as PortTestActivity
                    val main = mainActivity
                    val editorTask = foregroundEditor
                    check(main.taskId != editorTask.taskId) {
                        "MainActivity and foreground editor shared task ${main.taskId}"
                    }
                    waitFor("MainActivity did not move to background") {
                        lifecycleState(main) == Lifecycle.State.CREATED &&
                            lifecycleState(editorTask) == Lifecycle.State.RESUMED
                    }
                    val savedText = AtomicReference<String?>(null)
                    val activeSession = mutableStateOf<ClipboardEditSession?>(
                        ClipboardEditSession(ClipboardEntry(id = 42L, text = "編集テスト"), "編集テスト")
                    )
                    instrumentation.runOnMainSync {
                        editorTask.setContent {
                            activeSession.value?.let { current ->
                                ClipboardEditCard(
                                    session = current,
                                    palette = ZeroConDarkPalette,
                                    onSave = {
                                        savedText.set(current.currentText)
                                        activeSession.value = null
                                    },
                                    onCancel = {
                                        activeSession.value = null
                                    }
                                )
                            }
                        }
                    }
                    waitFor("ClipboardEditCard did not appear") {
                        findNodeText("クリップボードを編集")
                    }
                    check(lifecycleState(main) == Lifecycle.State.CREATED) {
                        "ClipboardEditCard brought MainActivity forward: ${lifecycleState(main)}"
                    }
                    check(lifecycleState(editorTask) == Lifecycle.State.RESUMED) {
                        "Foreground editor lost resumed focus while editing clipboard: ${lifecycleState(editorTask)}"
                    }
                    instrumentation.runOnMainSync {
                        val session = checkNotNull(activeSession.value)
                        session.setComposingText("追記", 1)
                        session.finishComposingText()
                    }
                    waitFor("ClipboardEditCard did not display edited text") {
                        findNodeText("編集テスト追記")
                    }
                    check(lifecycleState(main) == Lifecycle.State.CREATED) {
                        "MainActivity was brought forward during clipboard edit: ${lifecycleState(main)}"
                    }
                    check(lifecycleState(editorTask) == Lifecycle.State.RESUMED) {
                        "Foreground editor left RESUMED state during clipboard edit: ${lifecycleState(editorTask)}"
                    }
                    click("保存")
                    waitFor("ClipboardEditCard did not save updated text") {
                        savedText.get() == "編集テスト追記" && activeSession.value == null
                    }
                    check(lifecycleState(main) == Lifecycle.State.CREATED) {
                        "MainActivity was brought forward after saving clipboard edit: ${lifecycleState(main)}"
                    }
                    check(lifecycleState(editorTask) == Lifecycle.State.RESUMED) {
                        "Foreground editor did not stay RESUMED after saving clipboard edit: ${lifecycleState(editorTask)}"
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        foregroundEditor?.let { if (!it.isFinishing) it.finish() }
                        mainActivity?.let { if (!it.isFinishing) it.finish() }
                    }
                }
            }
        } finally {
            activity?.let { editor -> instrumentation.runOnMainSync { editor.finish() } }
        }
        return (if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED) to Bundle().apply {
            putInt("tests", tests); putInt("failures", failures)
            putString("stream", "\n$report\nTests: $tests, failures: $failures\n")
        }
    }
}
