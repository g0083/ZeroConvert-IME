package com.zeroconvert.ime

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Real-IME regression for the history continuation chain:
 * after 今日 is committed, typing は must offer はとても (segment chain of a learned phrase).
 * Seeds the learning memory in process only; it never writes the user's history.
 */
class LearningChainRegression(private val instrumentation: Instrumentation) {
    private fun <T> onUi(action: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { result = action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            if (depth >= 32) return null
            for (i in 0 until minOf(node.childCount, 256)) node.getChild(i)?.let { visit(it, depth + 1)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.windows.asSequence().mapNotNull { it.root }.mapNotNull { visit(it, 0) }.firstOrNull()
    }

    private fun waitFor(message: String, timeoutMs: Long = 4000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(50)
        }
    }

    private fun click(label: String, timeoutMs: Long = 3000): Boolean {
        waitFor("Missing key: $label", timeoutMs) {
            var target = node(label)
            repeat(8) {
                val current = target ?: return@repeat
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@waitFor true
                target = current.parent
            }
            false
        }
        return true
    }

    private fun typeLetters(letters: String) {
        for (letter in letters) check(click("${letter}を入力")) { "QWERTY key not found: $letter" }
    }

    fun run(): Pair<Int, Bundle> {
        val report = StringBuilder()
        var tests = 0
        var failures = 0
        fun test(name: String, action: () -> Unit) {
            tests++
            try { action(); report.append("PASS $name\n") }
            catch (failure: Throwable) { failures++; report.append("FAIL $name: ${failure.message}\n") }
        }
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences(ZeroConvertInputMethodService.PREFS_SETTINGS, Context.MODE_PRIVATE)
        val keys = listOf(
            ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT,
            ZeroConvertInputMethodService.KEY_REALTIME_INLINE,
            ZeroConvertInputMethodService.KEY_AUTO_COMMIT_ENABLED,
            ZeroConvertInputMethodService.KEY_FLICK_ONLY,
            ZeroConvertInputMethodService.KEY_PREDICTIVE_CONVERSION
        )
        val saved = prefs.all.filterKeys { it in keys }
        var activity: PortTestActivity? = null
        try {
            KanaKanjiConverter.ensureInitialized(context)
            val info = instrumentation.uiAutomation.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            instrumentation.uiAutomation.serviceInfo = info
            onUi {
                prefs.edit()
                    .putString(ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT, JapaneseInputLayout.QWERTY.name)
                    .putBoolean(ZeroConvertInputMethodService.KEY_REALTIME_INLINE, false)
                    .putBoolean(ZeroConvertInputMethodService.KEY_AUTO_COMMIT_ENABLED, false)
                    .putBoolean(ZeroConvertInputMethodService.KEY_FLICK_ONLY, false)
                    .putBoolean(ZeroConvertInputMethodService.KEY_PREDICTIVE_CONVERSION, true)
                    .apply()
            }
            activity = instrumentation.startActivitySync(Intent(context, PortTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PortTestActivity
            val editor = activity
            onUi {
                editor.field.showSoftInputOnFocus = true
                editor.field.requestFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(editor.field, InputMethodManager.SHOW_IMPLICIT)
            }
            waitFor("IME mode control unavailable") {
                node("入力モード:日本語") != null || node("入力モード:英語") != null || node("入力モード:数字") != null
            }
            repeat(3) {
                if (node("入力モード:日本語") == null) {
                    click(if (node("入力モード:英語") != null) "入力モード:英語" else "入力モード:数字")
                    SystemClock.sleep(100)
                }
            }
            waitFor("Japanese QWERTY unavailable") { node("入力モード:日本語") != null && node("kを入力") != null }

            // In-memory only: the service shares this process, and writes stay suppressed by the runner.
            KanaKanjiConverter.recordSelectionForTest("きょうはとてもいいてんきです", "今日はとてもいい天気です", listOf(
                DictionarySegment("キョウハ", "今日は", 100, 101, 5, 0f),
                DictionarySegment("トテモ", "とても", 700, 701, 5, 0f),
                DictionarySegment("イイ", "いい", 562, 563, 5, 0f),
                DictionarySegment("テンキデス", "天気です", 900, 901, 5, 0f)
            ))
            KanaKanjiConverter.recordSelectionForTest("きょう", "今日",
                listOf(DictionarySegment("キョウ", "今日", 100, 101, 5, 0f)))

            onUi {
                editor.field.setText("")
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor.field)
            }
            SystemClock.sleep(150)

            test("learned_phrase_predicts_leading_segment") {
                typeLetters("kyou")
                waitFor("Leading prediction 今日は did not appear") { node("変換候補 今日は") != null || node("変換候補 今日") != null }
                waitFor("Typed text changed before commit") {
                    onUi { editor.field.text.toString() } == "きょう"
                }
            }
            test("commit_then_continue_with_segment_chain") {
                check(click("変換候補 今日")) { "Learned 今日 candidate not found" }
                waitFor("Committing 今日 failed") {
                    onUi { editor.field.text.toString() } == "今日" &&
                        onUi { BaseInputConnection.getComposingSpanStart(editor.field.text) } == -1
                }
                typeLetters("ha")
                waitFor("Continuation はとても did not appear after committing 今日") {
                    node("変換候補 はとても") != null
                }
                check(click("変換候補 はとても")) { "Continuation はとても was not clickable" }
                waitFor("Continuation commit did not produce 今日はとても") {
                    onUi { editor.field.text.toString() } == "今日はとても"
                }
            }
            test("post_commit_chunks_appear_without_typing") {
                onUi {
                    editor.field.setText("")
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor.field)
                }
                SystemClock.sleep(200)
                typeLetters("kyou")
                waitFor("Learned 今日 candidate missing for post-commit test") { node("変換候補 今日") != null }
                check(click("変換候補 今日"))
                waitFor("Committing 今日 failed for post-commit test") {
                    onUi { editor.field.text.toString() } == "今日"
                }
                waitFor("Next chunk は did not appear right after committing 今日") { node("変換候補 は") != null }
                check(click("変換候補 は"))
                waitFor("Post-commit commit did not produce 今日は") {
                    onUi { editor.field.text.toString() } == "今日は"
                }
                waitFor("Next chunk とても did not appear after 今日は") { node("変換候補 とても") != null }
                check(click("変換候補 とても"))
                waitFor("Post-commit commit did not produce 今日はとても") {
                    onUi { editor.field.text.toString() } == "今日はとても"
                }
                waitFor("Next chunk いい did not appear after 今日はとても") { node("変換候補 いい") != null }
            }
        } finally {
            onUi {
                prefs.edit().apply {
                    keys.forEach { key -> when (val value = saved[key]) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        else -> remove(key)
                    } }
                }.apply()
                activity?.field?.setText("")
                activity?.finish()
            }
            KanaKanjiConverter.clearLearningMemoryForTest()
            KanaKanjiConverter.reloadUserDataForTest(context)
        }
        return (if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED) to Bundle().apply {
            putInt("tests", tests)
            putInt("failures", failures)
            putString("stream", "\n$report\nTests: $tests, failures: $failures\n")
        }
    }
}
