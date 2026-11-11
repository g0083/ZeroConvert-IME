package com.zeroconvert.ime

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import com.zeroconvert.ime.assist.*
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle

/** Both native assistance and real keys: pause before the vowel that completes terminal n. */
class PendingRomajiRegression(private val instrumentation: Instrumentation) {
    private fun <T> onUi(action: () -> T): T {
        var result: T? = null
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { result = action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun node(label: String): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        // Row 1 QWERTY keys append an up-flick digit to the accessibility label when the
        // number row is hidden, e.g. "iを入力、上フリックで8を入力". Accept only that exact
        // suffix so "aを入力" never matches an unrelated label.
        fun matches(node: AccessibilityNodeInfo): Boolean {
            val text = node.text?.toString()
            val description = node.contentDescription?.toString()
            return text == label || description == label ||
                text?.startsWith("$label、") == true || description?.startsWith("$label、") == true
        }
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (matches(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.windows.asSequence().mapNotNull { it.root }.mapNotNull { visit(it) }.firstOrNull()
    }

    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(50)
        }
    }

    private fun click(label: String) {
        waitFor("Missing key: $label") {
            var target = node(label)
            repeat(8) {
                val current = target ?: return@repeat
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@waitFor true
                target = current.parent
            }
            false
        }
    }

    private fun touch(label: String) {
        var target: AccessibilityNodeInfo? = null
        waitFor("Missing touch key: $label") { target = node(label); target != null }
        val bounds = android.graphics.Rect().also { checkNotNull(target).getBoundsInScreen(it) }
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == android.view.MotionEvent.ACTION_DOWN) SystemClock.sleep(50)
        }
        instrumentation.waitForIdleSync()
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
        KanaKanjiConverter.ensureInitialized(context)
        check(ZenzNative.init(context)) { "Native engine initialization failed" }
        for ((raw, unwanted) in listOf("nan" to "晩", "non" to "本", "man" to "間")) test("native_assist_rejects_pending_$raw") {
            var calls = 0
            val incorrectModel = object : AssistInference {
                override val revision = 0x6fL
                override fun complete(model: AssistModel, prompt: String, maxTokens: Int, timeoutMs: Int): ModelCompletion {
                    calls++
                    return ModelCompletion(unwanted, "eos")
                }
            }
            val input = ComposingText().apply { insertAtCursorPosition(raw, InputStyle.ROMAN2KANA) }
            val result = ModelCorrector(incorrectModel).suggest("", input.convertTarget, input.convertTarget)
            check(result == null) { "Unfinished $raw was corrected to ${result?.text}, automatic=${result?.autoApply}" }
            check(calls == 0) { "Unfinished $raw reached the correction model" }
        }
        test("completed_nasal_readings_still_have_normal_candidates") {
            val converter = KanaKanjiConverter()
            for ((raw, reading) in listOf("nann" to "なん", "nonn" to "のん", "mann" to "まん")) {
                val input = ComposingText().apply { raw.forEach { insertAtCursorPosition(it.toString(), InputStyle.ROMAN2KANA) } }
                check(input.convertTarget == reading)
                val candidates = kotlinx.coroutines.runBlocking {
                    converter.requestCandidates(input, "", enableAI = true, personalize = false)
                }
                check(candidates.isNotEmpty()) { "Normal conversion disappeared for completed $raw" }
            }
        }

        val prefs = context.getSharedPreferences(ZeroConvertInputMethodService.PREFS_SETTINGS, Context.MODE_PRIVATE)
        val keys = listOf(ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT, ZeroConvertInputMethodService.KEY_REALTIME_INLINE,
            ZeroConvertInputMethodService.KEY_EXPERIMENTAL_AI_ASSIST, ZeroConvertInputMethodService.KEY_AUTO_TYPO_CORRECTION,
            ZeroConvertInputMethodService.KEY_AUTO_COMMIT_ENABLED)
        val saved = prefs.all.filterKeys { it in keys }
        var activity: PortTestActivity? = null
        try {
            val info = instrumentation.uiAutomation.serviceInfo
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            instrumentation.uiAutomation.serviceInfo = info
            onUi { prefs.edit().putString(keys[0], JapaneseInputLayout.QWERTY.name)
                .putBoolean(keys[1], false).putBoolean(keys[2], true).putBoolean(keys[3], true).putBoolean(keys[4], true).apply() }
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
            waitFor("Japanese QWERTY unavailable") { node("入力モード:日本語") != null && node("nを入力") != null }
            for (live in listOf(false, true)) for ((raw, reading) in listOf("nan" to "な", "non" to "の", "man" to "ま")) test("real_keys_pause_delete_resume_${raw}_live_$live") {
                onUi {
                    prefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_REALTIME_INLINE, live).apply()
                    editor.field.setText("")
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor.field)
                }
                raw.forEach { click("${it}を入力") }
                SystemClock.sleep(1200)
                val pausedText = onUi { editor.field.text.toString() }
                if (!live) check(pausedText == reading + "n") { "Pending $raw was changed to '$pausedText' before the next vowel" }
                check(onUi { BaseInputConnection.getComposingSpanStart(editor.field.text) } == 0) { "Pending $raw was committed" }
                // Live conversion may preview 何/万. Reveal the canonical input by
                // continuing with inline preview off, without clearing composition.
                onUi { prefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_REALTIME_INLINE, false).apply() }
                click("iを入力")
                SystemClock.sleep(1200)
                check(onUi { editor.field.text.toString() } == reading + "に") { "Typing i directly after $raw lost the original reading" }
                touch("Backspace")
                check(onUi { editor.field.text.toString() } == reading) { "Deleting completed kana lost the prefix" }
                click("nを入力")
                touch("Backspace")
                check(onUi { editor.field.text.toString() } == reading) { "Deleting pending n lost the original reading" }
                click("nを入力"); click("iを入力")
                SystemClock.sleep(1200)
                check(onUi { editor.field.text.toString() } == reading + "に") { "Continuing $raw with i did not preserve ${reading}に" }
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
        }
        return (if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED) to Bundle().apply {
            putInt("tests", tests); putInt("failures", failures)
            putString("stream", "\n$report\nTests: $tests, failures: $failures\n")
        }
    }
}
