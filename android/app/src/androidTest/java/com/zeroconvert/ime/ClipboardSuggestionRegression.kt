package com.zeroconvert.ime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*

/** Exercise the actual timer and settings controls using synthetic, isolated clipboard history. */
class ClipboardSuggestionRegression(private val instrumentation: Instrumentation) {
    private val history = mutableStateListOf<ClipboardEntry>()
    private val minutes = mutableStateOf(ClipboardSuggestionPolicy.DEFAULT_MINUTES)
    @Volatile private var displayed: ClipboardEntry? = null
    private val revision = mutableStateOf(0)
    @Volatile private var renderedRevision = -1

    @Composable private fun Fixture() {
        val currentRevision = revision.value
        val suggestion = rememberClipboardSuggestion(history, minutes.value)
        Column {
            Text(suggestion?.preview ?: "候補なし")
            ClipboardSuggestionDurationSetting(minutes.value, ZeroConDarkPalette) { minutes.value = it }
        }
        SideEffect { displayed = suggestion; renderedRevision = currentRevision }
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
        } finally {
            activity?.let { editor -> instrumentation.runOnMainSync { editor.finish() } }
        }
        return (if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED) to Bundle().apply {
            putInt("tests", tests); putInt("failures", failures)
            putString("stream", "\n$report\nTests: $tests, failures: $failures\n")
        }
    }
}
