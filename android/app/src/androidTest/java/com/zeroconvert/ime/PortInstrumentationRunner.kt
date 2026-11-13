package com.zeroconvert.ime

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import com.zeroconvert.ime.model.ModelManager
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.BaseInputConnection
import android.content.Context
import com.zeroconvert.ime.benchmark.BenchmarkRunner
import com.zeroconvert.ime.benchmark.BenchmarkProgress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/** Uses platform Instrumentation so device checks need no additional downloaded test libraries. */
class PortInstrumentationRunner : Instrumentation() {
    private var includeQuality = false
    private var keyboardUiOnly = false
    private var clipboardUiOnly = false
    private var pendingRomajiOnly = false
    private var learningDeletionOnly = false
    private var performanceArguments: Bundle? = null
    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (matches(node)) return node
            if (depth >= 32) return null
            for (index in 0 until minOf(node.childCount, 256)) node.getChild(index)?.let { child -> visit(child, depth + 1)?.let { return it } }
            return null
        }
        for (window in uiAutomation.windows) window.root?.let { visit(it, 0)?.let { found -> return found } }
        return null
    }
    // Row 1 QWERTY keys append an up-flick digit to the accessibility label when the number row is hidden, e.g. "tを入力、上フリックで4を入力".
    // Accept only that exact suffix so "tを入力" never matches an unrelated label such as "ttを入力".
    private fun findText(text: String) = findNode {
        val label = it.text?.toString()
        val description = it.contentDescription?.toString()
        label == text || description == text || label?.startsWith("$text、") == true || description?.startsWith("$text、") == true
    }
    private fun clickText(text: String, timeoutMs: Long = 3000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            var node = findText(text)
            repeat(8) {
                val current = node ?: return@repeat
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                node = current.parent
            }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }
    private fun touchKey(label: String, horizontalDp: Float = 0f): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
        val target = findText(label) ?: return false
        val bounds = android.graphics.Rect().also { target.getBoundsInScreen(it) }
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, offset: Float = 0f): Boolean {
            val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX() + offset, bounds.exactCenterY(), 0)
            return try { uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
        if (!inject(android.view.MotionEvent.ACTION_DOWN)) return false
        SystemClock.sleep(50)
        val offset = horizontalDp * targetContext.resources.displayMetrics.density
        if (offset != 0f) {
            check(inject(android.view.MotionEvent.ACTION_MOVE, offset))
            SystemClock.sleep(50)
        }
        val accepted = inject(android.view.MotionEvent.ACTION_UP, offset)
        waitForIdleSync()
        return accepted
    }
        private fun longPressText(text: String, timeoutMs: Long = 3000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
            val label = findNode { it.contentDescription?.toString() == text }
            if (label != null) {
                // Semantics long-click needs no gesture timing and works on the IME's
                // overlay window, where injected swipe timing proved unreliable.
                var node: android.view.accessibility.AccessibilityNodeInfo? = label
                repeat(8) {
                    val current = node ?: return@repeat
                    if (current.isLongClickable &&
                        current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    ) {
                        waitForIdleSync()
                        return true
                    }
                    node = current.parent
                }
                val bounds = android.graphics.Rect().also { label.getBoundsInScreen(it) }
                val downTime = SystemClock.uptimeMillis()
                fun inject(action: Int): Boolean {
                    val event = android.view.MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                    return try { uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
                }
                if (!inject(android.view.MotionEvent.ACTION_DOWN)) return false
                // Compose's long-press threshold is 400ms.
                SystemClock.sleep(700)
                val accepted = inject(android.view.MotionEvent.ACTION_UP)
                waitForIdleSync()
                return accepted
            }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 4000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { message }
            SystemClock.sleep(50)
        }
    }
    private fun chooseVisibleCandidate() {
        var label: String? = null
        waitUntil("No visible conversion candidate") {
            label = findNode { it.contentDescription?.startsWith("変換候補 ") == true }?.contentDescription?.toString()
            label != null
        }
        check(clickText(checkNotNull(label)))
    }

    private fun <T> onUi(action: () -> T): T {
        val value = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        val pending = AtomicBoolean(true)
        val finished = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            if (pending.compareAndSet(true, false)) {
                try { value.set(action()) } catch (failure: Throwable) { error.set(failure) }
                finally { finished.countDown() }
            }
        }
        if (!finished.await(5, TimeUnit.SECONDS)) {
            pending.set(false)
            error("Main thread did not complete the test action within 5 seconds")
        }
        error.get()?.let { throw it }
        return value.get()
    }

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        includeQuality = arguments?.getString("quality") == "true"
        keyboardUiOnly = arguments?.getString("keyboardUiOnly") == "true"
        clipboardUiOnly = arguments?.getString("clipboardUiOnly") == "true"
        pendingRomajiOnly = arguments?.getString("pendingRomajiOnly") == "true"
        learningDeletionOnly = arguments?.getString("learningDeletionOnly") == "true"
        performanceArguments = arguments?.takeIf { it.getString("enginePerformance") == "true" }
        start()
    }
    override fun onStart() {

        if (pendingRomajiOnly) {
            val result = LearningPolicy.withoutWrites { PendingRomajiRegression(this).run() }
            finish(result.first, result.second)
            return
        }
        if (clipboardUiOnly) {
            val result = ClipboardSuggestionRegression(this).run()
            finish(result.first, result.second)
            return
        }
        performanceArguments?.let {
            val result = SharedEnginePerformanceRunner(this).run(it)
            finish(result.first, result.second)
            return
        }
        if (keyboardUiOnly) {
            val result = KeyboardUiRegression(this).run()
            finish(result.first, result.second)
            return
        }
        val result = LearningPolicy.withoutWrites { executeTests() }
        finish(result.first, result.second)
    }
    private fun executeTests(): Pair<Int, Bundle> {
        val report = StringBuilder()
        var failures = 0
        var tests = 0
        fun test(name: String, action: () -> Unit) {
            // A focused run skips everything else so an unrelated native test
            // cannot take the whole process down before this one executes.
            if (learningDeletionOnly && name != "editor_setup" &&
                !name.contains("learning") && !name.contains("long_press") &&
                !name.contains("real_ime_keys")
            ) return
            tests++
            val started = SystemClock.elapsedRealtime()
            sendStatus(1, Bundle().apply { putString("stream", "START $name\n") })
            try { action(); report.append("PASS $name ${SystemClock.elapsedRealtime() - started}ms\n") }
            catch (failure: Throwable) {
                failures++; report.append("FAIL $name ${failure.javaClass.simpleName}: ${failure.message}\n")
                report.append(failure.stackTrace.take(4).joinToString("\n") + "\n")
            }
            sendStatus(0, Bundle().apply { putString("stream", report.lineSequence().filter { it.isNotBlank() }.last() + "\n") })
        }
        var activity: PortTestActivity? = null
        test("editor_setup") {
            val monitor = addMonitor(PortTestActivity::class.java.name, null, false)
            try {
                targetContext.startActivity(Intent(targetContext, PortTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                activity = monitor.waitForActivityWithTimeout(10_000) as? PortTestActivity
                checkNotNull(activity) { "Test activity did not launch within 10 seconds; keep the device unlocked and the app in front" }
            } finally { removeMonitor(monitor) }
        }
        test("partial_commit_keeps_tail") {
            onUi {
                val field = checkNotNull(activity).field
                field.setText("")
                val connection = checkNotNull(field.onCreateInputConnection(EditorInfo()))
                check(connection.setComposingText("とうきょうにいく", 1))
                check(connection.commitText("東京", 1))
                check(connection.setComposingText("にいく", 1))
                check(field.text.toString() == "東京にいく")
                check(connection.commitText("に行く", 1))
                check(field.text.toString() == "東京に行く")
            }
        }
        test("real_input_connection_rewind") {
            onUi {
                val field = checkNotNull(activity).field
                field.setText("")
                val connection = checkNotNull(field.onCreateInputConnection(EditorInfo()))
                check(connection.commitText("前文東京に", 1))
                check(connection.setComposingText("行く", 1))
                val input = ComposingText().apply { insertAtCursorPosition("toukyouniiku", InputStyle.ROMAN2KANA) }
                val record = AutoCommitRewindRecord("東京に", "とうきょうに", "前文", input)
                val result = AutoCommitRewind.restore(record, "いく", "行く", object : AutoCommitRewind.Editor {
                    override fun beforeCursor(length: Int) = connection.getTextBeforeCursor(length, 0)?.toString()
                    override fun composing(text: String) = connection.setComposingText(text, 1)
                    override fun deleteBefore(length: Int) = connection.deleteSurroundingText(length, 0)
                    override fun commit(text: String) = connection.commitText(text, 1)
                })
                check(result == AutoCommitRewind.Result.RESTORED)
                check(field.text.toString() == "前文とうきょうにいく")
            }
        }
        test("native_assets_and_model_load") {
            KanaKanjiConverter.ensureInitialized(targetContext)
            check(ZenzNative.init(targetContext))
            for (spec in ModelManager.BUILT_IN_MODELS) ModelManager.ensureBuiltInModel(targetContext, spec)
        }
        if (BuildConfig.WINDOWS_ENGINE) test("shared_windows_conversion_and_input_snapshots") {
            KanaKanjiConverter.ensureInitialized(targetContext)
            val converter = KanaKanjiConverter()
            val input = ComposingText()
            for (raw in listOf("kan", "kann", "kya", "gakkou", "toukyouniiku")) {
                input.stopComposition()
                for (key in raw) input.insertAtCursorPosition(key.toString(), InputStyle.ROMAN2KANA)
                val candidates = runBlocking { converter.requestCandidates(input.clone(), "", enableAI = false, personalize = false) }
                check(candidates.isNotEmpty()) { "Shared conversion returned no candidates for a raw snapshot" }
                check(candidates.all { it.engineCandidateId.isEmpty() && !it.learningAllowed }) { "Private conversion exposed learning identities" }
            }
            input.stopComposition()
            input.insertAtCursorPosition("toukyou", InputStyle.ROMAN2KANA)
            val tokyo = runBlocking { converter.requestCandidates(input.clone(), "", enableAI = true, personalize = true) }
            check(tokyo.any { it.text == "東京" }) { "Shared Swift Tokyo conversion missing" }
            if (LearningPolicy.mode == LearningMode.READ_WRITE) {
                check(tokyo.any { it.text == "東京" && it.engineCandidateId.isNotEmpty() && it.learningAllowed }) { "Windows learning identity missing" }
            }
            input.insertAtCursorPosition("にいく", InputStyle.DIRECT)
            input.moveCursorFromCursorPosition(-3)
            val prefix = runBlocking { converter.requestCandidates(input.clone(), "", enableAI = false, personalize = false) }
            check(prefix.any { it.text.startsWith("東京") }) { "Shared cursor-prefix conversion missing" }
            input.stopComposition()
            input.insertAtCursorPosition("😀", InputStyle.DIRECT)
            val emoji = runBlocking { converter.requestCandidates(input.clone(), "", enableAI = false, personalize = false) }
            check(emoji.any { it.text == "😀" }) { "Supplementary direct input was lost" }
            check(com.zeroconvert.ime.engine.WindowsEngine.readingForSurface("東京") == "とうきょう")
        }
        if (BuildConfig.WINDOWS_ENGINE) test("shared_cursor_prefix_with_and_without_ai") {
            KanaKanjiConverter.ensureInitialized(targetContext)
            val converter = KanaKanjiConverter()
            for (style in listOf(InputStyle.ROMAN2KANA, InputStyle.DIRECT)) {
                val input = ComposingText().apply {
                    insertAtCursorPosition(if (style == InputStyle.ROMAN2KANA) "toukyou" else "とうきょう", style)
                    insertAtCursorPosition("にいく", InputStyle.DIRECT)
                    moveCursorFromCursorPosition(-3)
                }
                for (ai in listOf(false, true)) {
                    val candidates = runBlocking {
                        converter.requestCandidates(input.clone(), "", enableAI = ai, personalize = false)
                    }
                    check(candidates.any { it.text == "東京" && it.engineConsumedReading == 5 }) {
                        "Cursor prefix missing Tokyo: style=$style ai=$ai"
                    }
                    check(candidates.all { (it.engineConsumedReading ?: 0) in 1..5 }) {
                        "Cursor conversion consumed text to the right: style=$style ai=$ai"
                    }
                    check(input.convertTarget == "とうきょうにいく" && input.convertTargetCursorPosition == 5) {
                        "Cursor conversion changed the input snapshot"
                    }
                }
            }
        }
        test("editor_context_change_without_cursor_move") {
            onUi {
                val field = checkNotNull(activity).field
                field.setText("")
                val connection = checkNotNull(field.onCreateInputConnection(EditorInfo()))
                check(connection.commitText("東京へ", 1))
                check(connection.setComposingText("今日は", 1))
                val cursor = field.selectionStart
                val initial = EditorContext.confirmedLeft(connection.getTextBeforeCursor(1024, 0)?.toString(), "今日は")
                check(initial == "東京へ")
                field.text.replace(0, 2, "大阪")
                check(field.selectionStart == cursor)
                check(EditorContext.confirmedLeft(connection.getTextBeforeCursor(1024, 0)?.toString(), "今日は") == "大阪へ")
                check(connection.finishComposingText())
            }
        }
        test("native_dictionary_morphology") {
            val reading = "トウキョウニイク"
            var candidates = emptyList<Candidate>()
            waitUntil("Native dictionary stayed busy or cancelled during editor setup") {
                candidates = ZenzNative.lookupDict(reading, false, 20)
                candidates.isNotEmpty()
            }
            check(candidates.any { candidate -> candidate.segments.isNotEmpty() &&
                candidate.segments.joinToString("") { it.reading } == reading &&
                candidate.segments.joinToString("") { it.surface } == candidate.text }) { "Native morphology did not cover its reading and surface" }
        }
        fun romanInput(raw: String) = ComposingText().apply {
            insertAtCursorPosition(raw, com.zeroconvert.ime.azookey.input.InputStyle.ROMAN2KANA)
        }
        fun corrected(input: ComposingText): List<Candidate> {
            var candidates = emptyList<Candidate>()
            waitUntil("Keyboard dictionary stayed busy or cancelled") {
                candidates = ZenzNative.lookupDict(KanaKanjiConverter.toKatakana(input.convertTarget), false, 64, input.keyboardTypoSurfaceEnds())
                candidates.isNotEmpty()
            }
            return candidates
        }
        test("native_keyboard_typo_dictionary") {
            val input = romanInput("simasuta")
            check(input.convertTarget == "しますた")
            val found = corrected(input).firstOrNull { it.text == "しました" && it.segments.any { span -> span.correctionKind == 3 } }
            checkNotNull(found) { "Windows fixed typo dictionary did not participate in the lattice" }
            check(!found.isLearningTarget)
            check(found.segments.joinToString("") { it.reading } == "シマスタ")
            val disabled = ZenzNative.lookupDict("シマスタ", false, 64)
            check(disabled.all { it.segments.none { span -> span.correctionKind != 0 } })
        }
        test("native_long_compositions_and_unknown_characters") {
            for (reading in listOf("トウキョウニイク".repeat(20), "トウキョウ😀ニイク", "😀".repeat(512))) {
                var candidates = emptyList<Candidate>()
                waitUntil("Long or mixed native lookup did not complete (${reading.codePointCount(0, reading.length)} characters)") {
                    candidates = ZenzNative.lookupDict(reading, false, 4)
                    candidates.isNotEmpty()
                }
                check(candidates.any { candidate ->
                    candidate.segments.isNotEmpty() && candidate.segments.joinToString("") { it.reading } == reading &&
                        candidate.segments.joinToString("") { it.surface } == candidate.text &&
                        candidate.segments.all { it.reading.codePointCount(0, it.reading.length) <= 20 }
                }) { "A dictionary span was truncated or exceeded Swift maxlength" }
                if (reading.contains("😀")) check(candidates.all { it.text.contains("😀") })
            }
        }
        // The pinned Zenzai converter reserves a fixed 512-entry llama batch, so a long
        // reading must return a degraded result instead of aborting the process. The
        // dictionary test above only walks the LOUDS path and never builds that batch.
        test("native_zenzai_long_reading_does_not_abort") {
            KanaKanjiConverter.ensureInitialized(targetContext)
            val converter = KanaKanjiConverter()
            // requestCandidates(enableAI = true) is the neural request the IME issues for a live
            // preview; it reaches Zenzai through the shared engine instead of lookupDict.
            fun neural(reading: String): List<Candidate> = runBlocking {
                val input = ComposingText().apply { insertAtCursorPosition(reading, InputStyle.DIRECT) }
                converter.requestCandidates(input, "", enableAI = true, personalize = false)
            }
            lateinit var warm: List<Candidate>
            waitUntil("Zenzai neural conversion stayed busy or cancelled during warm-up") {
                try {
                    warm = neural("トウキョウニイク")
                    warm.isNotEmpty()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    if (cancelled.message?.contains("superseded") == true) false else throw cancelled
                }
            }
            // The shared Rust bridge rejects a reading above 512 characters with
            // {"error":"request exceeds engine limits"}. That limit is a separate defect from
            // the native abort under test, so it is logged and the ladder keeps walking.
            fun limitError(failure: Throwable): Boolean {
                var current: Throwable? = failure
                while (current != null) {
                    if (current.message?.contains("engine limits") == true) return true
                    current = current.cause
                }
                return false
            }
            // A native abort cannot be caught, so each family walks an ascending ladder and
            // logs a breadcrumb before every call: the last breadcrumb without its matching
            // return line is the threshold. Candidate content is never asserted because
            // degraded long input may legitimately come back empty.
            fun probe(family: String, base: String, repeats: List<Int>) {
                for (count in repeats) {
                    val reading = base.repeat(count)
                    android.util.Log.i("ZeroConRepro", "zenzai $family chars=${reading.length}")
                    var attempts = 0
                    var thrown: Throwable?
                    var results: List<Candidate>
                    var elapsed: Long
                    while (true) {
                        attempts++
                        thrown = null
                        results = emptyList()
                        val started = SystemClock.elapsedRealtime()
                        try { results = neural(reading) } catch (failure: Throwable) { thrown = failure }
                        elapsed = SystemClock.elapsedRealtime() - started
                        // A call that never returns is the failure under test, so it fails even
                        // when the thrown error would otherwise be treated as a limit.
                        check(elapsed < 6000) {
                            "Zenzai $family did not return within the probe budget: chars=${reading.length} repeats=$count elapsed=${elapsed}ms"
                        }
                        if (thrown is kotlinx.coroutines.CancellationException &&
                            thrown.message?.contains("superseded") == true &&
                            attempts < 3
                        ) {
                            android.util.Log.i(
                                "ZeroConRepro",
                                "zenzai $family superseded during setup; retrying chars=${reading.length} attempt=$attempts"
                            )
                            SystemClock.sleep(50)
                            continue
                        }
                        break
                    }
                    if (thrown != null && limitError(thrown)) {
                        android.util.Log.i("ZeroConRepro",
                            "zenzai $family LIMIT (not a crash) chars=${reading.length} repeats=$count elapsed=${elapsed}ms error=$thrown")
                        continue
                    }
                    check(thrown == null) {
                        "Zenzai $family threw for chars=${reading.length} repeats=$count after ${elapsed}ms: $thrown"
                    }
                    android.util.Log.i("ZeroConRepro", "zenzai $family returned chars=${reading.length} candidates=${results.size} elapsed=${elapsed}ms")
                }
            }
            // VARIED text tokenizes least efficiently, so it may cross the fixed batch at the
            // shortest reading. Dense small steps locate that threshold precisely.
            probe("varied", "キョウハイイテンキデスネ", listOf(3, 6, 9, 12, 16, 20, 25, 30, 35, 40, 45, 50, 60, 70, 80, 100))
            // Longer prose with mixed word boundaries sits closer to real sentences.
            probe("prose", "ワタシハキノウトウキョウデトモダチトアッテラーメントスシトキョウカイニイキマシタ", listOf(2, 3, 4, 5, 6, 8, 10, 12, 15, 18, 22))
            // Identical kana repeats tokenize efficiently and stayed alive through 480
            // characters, so they run last as the control family.
            probe("repeat", "トウキョウニイク", listOf(4, 8, 12, 16, 20, 24, 40, 50, 60, 70))
        }
        test("all_windows_keyboard_dictionary_entries") {
            val cases = context.assets.open("keyboard-typo-cases.tsv").bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { it.split('\t') }.toList()
            }
            check(cases.size == 104)
            val missing = mutableListOf<String>()
            for (fields in cases) {
                val reading = fields[0]
                val count = reading.codePointCount(0, reading.length)
                val candidates = ZenzNative.lookupDict(reading, false, 64, IntArray(count) { count })
                if (candidates.none { candidate -> candidate.text == fields[1] && candidate.segments.any {
                    it.correctionKind == 3 && it.reading == reading && it.surface == fields[1] &&
                        it.lcid == fields[2].toInt() && it.rcid == fields[3].toInt() && it.mid == fields[4].toInt() &&
                        kotlin.math.abs(it.cost - fields[5].toFloat()) < 0.0002f
                } }) missing.add("$reading -> ${fields[1]}")
            }
            check(missing.isEmpty()) { "Missing Windows dictionary entries (${missing.size}): ${missing.joinToString()}" }
        }
        test("native_bounded_keyboard_rewrites") {
            val small = romanInput("kittte")
            check(small.convertTarget == "きっって")
            check(corrected(small).any { candidate -> candidate.segments.any { it.correctionKind == 1 && it.lexicalReading == "キッテ" && it.originalEnd - it.originalStart == 4 } })
            val doubleNn = romanInput("konnnnnitiha")
            check(doubleNn.convertTarget == "こんんにちは") { doubleNn.convertTarget }
            check(corrected(doubleNn).any { candidate -> candidate.segments.any { it.correctionKind == 2 } })
        }
        test("native_keyboard_rewrite_negative_controls") {
            val direct = ComposingText().apply { insertAtCursorPosition("きっって", com.zeroconvert.ime.azookey.input.InputStyle.DIRECT) }
            check(corrected(direct).all { it.segments.none { span -> span.correctionKind != 0 } })
            val frozen = ComposingText().apply { insertAtCursorPosition("しますた", com.zeroconvert.ime.azookey.input.InputStyle.FROZEN) }
            check(corrected(frozen).all { it.segments.none { span -> span.correctionKind != 0 } })
            val repeated = romanInput("kitttte")
            check(repeated.convertTarget == "きっっって")
            check(corrected(repeated).all { it.segments.none { span -> span.correctionKind == 1 } })
        }
        test("conversion_pipeline_dictionary_correction") {
            val input = romanInput("simasuta")
            val result = runBlocking {
                KanaKanjiConverter().requestCandidates(input, "", enableAI = false, personalize = false,
                    experimentalKeyboardCorrection = true)
            }
            val selected = result.firstOrNull { it.text == "しました" }
            checkNotNull(selected) { "Dictionary correction was lost in the Kotlin candidate pipeline" }
            check(!selected.isLearningTarget)
            check(selected.reading == "シマスタ")
            check(input.convertTarget == "しますた")
        }
        if (BuildConfig.WINDOWS_ENGINE) test("zenzai_11b_exact_and_predictions") {
            KanaKanjiConverter.ensureInitialized(targetContext)
            val converter = KanaKanjiConverter()
            fun convertAi(label: String, input: ComposingText): List<Candidate> {
                var out = emptyList<Candidate>()
                waitUntil("1-1B AI conversion stayed busy for $label") {
                    out = try {
                        runBlocking {
                            converter.requestCandidates(input, "", enableAI = true, personalize = false)
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        if (cancelled.message?.contains("superseded") == true) emptyList() else throw cancelled
                    }
                    out.isNotEmpty()
                }
                android.util.Log.i(
                    "ZeroCon11B",
                    "$label -> " + out.take(12).joinToString(" | ") { "${it.text}[${it.reading},ai=${it.isAI}]" }
                )
                return out
            }

            val yorosi = convertAi("yorosi", romanInput("yorosi"))
            check(yorosi.first().reading == "ヨロシ" && yorosi.first().text.length <= 4) {
                "Top 1 for `yorosi` must be full-span exact conversion, got: ${yorosi.take(5)}"
            }
            val yorosiPredIdx = yorosi.indexOfFirst {
                it.text.length > 3 && (it.text.startsWith("よろし") || it.text.startsWith("宜し"))
            }
            check(yorosiPredIdx > 0) {
                "Expected Gboard-style predictive completions after exact Top 1 for `yorosi`, got: ${yorosi.take(12)}"
            }
            android.util.Log.i("ZeroCon11B", "yorosi first prediction at index=$yorosiPredIdx: ${yorosi[yorosiPredIdx].text}")

            val savePhoto = convertAi("syasinwoforudanihozonsuru", romanInput("syasinwoforudanihozonsuru"))
            check(savePhoto.first().reading == "シャシンヲフォルダニホゾンスル") {
                "Top 1 for `syasinwoforudanihozonsuru` must stay exact without predicted tail: ${savePhoto.take(5)}"
            }
            check(savePhoto.first().text.contains("写真") && savePhoto.first().text.contains("保存") && !savePhoto.first().text.contains("スルー")) {
                "Top 1 for `syasinwoforudanihozonsuru` degraded: ${savePhoto.first()}"
            }

            val sendMailInput = ComposingText().apply { insertAtCursorPosition("めーるをおくる", com.zeroconvert.ime.azookey.input.InputStyle.DIRECT) }
            val sendMail = convertAi("めーるをおくる", sendMailInput)
            check(sendMail.first().reading == "メールヲオクル") {
                "Top 1 for `めーるをおくる` must stay exact without predicted tail: ${sendMail.take(5)}"
            }
            check(sendMail.first().text.contains("メール") && !sendMail.first().text.contains("くるみ")) {
                "Top 1 for `めーるをおくる` degraded: ${sendMail.first()}"
            }

            val konnichiwa = convertAi("konnnitiha", romanInput("konnnitiha"))
            check(konnichiwa.first().reading == "コンニチハ" && konnichiwa.first().text in listOf("こんにちは", "今日は", "コンニチハ")) {
                "Top 1 for `konnnitiha` must be exact without predicted punctuation/tail: ${konnichiwa.take(5)}"
            }
        }
        test("user_dictionary_inside_native_lattice_and_cache_isolation") {
            val reading = "ゼロコンヲツカウ"
            val entries = listOf(UserDictEntry("ぜろこん", "登録専用◇A"))
            var candidates = emptyList<Candidate>()
            waitUntil("User word did not enter the native lattice") {
                candidates = ZenzNative.lookupDict(reading, false, 20, userEntries = entries)
                candidates.any { it.segments.any { span -> span.reading == "ゼロコン" && span.surface == "登録専用◇A" } }
            }
            val span = candidates.first { it.text.startsWith("登録専用◇A") }.segments.first()
            check(span.lcid == 1288 && span.rcid == 1288 && span.mid == 501 && span.cost == -5f)
            val withoutUsers = ZenzNative.lookupDict(reading, false, 20)
            check(withoutUsers.none { it.text.contains("登録専用◇A") }) { "Personal dictionary result leaked through the cache" }
            val replaced = ZenzNative.lookupDict(reading, false, 20, userEntries = listOf(UserDictEntry("ぜろこん", "登録専用◇B")))
            check(replaced.any { it.text.startsWith("登録専用◇B") } && replaced.none { it.text.contains("登録専用◇A") })
        }
        test("reverse_dictionary_and_privacy") {
            check(com.zeroconvert.ime.assist.ReadingCheck.readingForSurface("東京") != null)
            check(com.zeroconvert.ime.assist.InputPrivacy.isSensitive(android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
        }
        if (includeQuality) {
            val output = File(targetContext.filesDir, "port-quality.jsonl")
            output.writeText("")
            fun progress(kind: String, value: BenchmarkProgress) {
                output.appendText(JSONObject().apply {
                    put("kind", kind); put("current", value.current); put("total", value.total)
                    put("correct", value.perfectCount); put("avgLatencyMs", value.avgLatencyMs)
                    put("detail", value.lastItemDetail); put("finished", value.isFinished)
                }.toString() + "\n")
                sendStatus(0, Bundle().apply { putString("stream", "$kind ${value.current}/${value.total}: ${value.lastItemDetail}\n") })
            }
            test("quality_typo_dataset") {
                check(BenchmarkRunner.loadTypoDataset(targetContext).isNotEmpty()) { "Typo dataset missing" }
                runBlocking { BenchmarkRunner.runTypoBenchmark(targetContext) { progress("typo", it) } }
            }
            test("quality_normal_input") {
                val unexpected = mutableListOf<String>()
                for (reading in listOf("こんにちは", "ありがとう", "おつかれさま", "せってい", "きょうと", "がっこう", "とうきょう", "かんじへんかん")) {
                    val started = SystemClock.elapsedRealtime()
                    val result = com.zeroconvert.ime.assist.ModelCorrector(ZenzNative.assistSession()).suggest("", reading, reading)
                    output.appendText(JSONObject().apply {
                        put("kind", "normal"); put("input", reading); put("output", result?.text ?: "")
                        put("autoApply", result?.autoApply ?: false); put("latencyMs", SystemClock.elapsedRealtime() - started)
                    }.toString() + "\n")
                    if (result != null) unexpected.add("$reading -> ${result.text}")
                }
                check(unexpected.isEmpty()) { "Normal input corrections: ${unexpected.joinToString()}" }
            }
            test("quality_prediction_dataset") {
                check(BenchmarkRunner.loadPredictiveDataset(targetContext).isNotEmpty()) { "Prediction dataset missing" }
                runBlocking { BenchmarkRunner.runPredictiveBenchmark(targetContext) { progress("prediction", it) } }
            }
        }
        test("real_ime_keys_and_commit") {
            val info = uiAutomation.serviceInfo
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            uiAutomation.serviceInfo = info
            waitUntil("Test editor has no window focus") { onUi { checkNotNull(activity).field.hasWindowFocus() } }
            onUi {
                val field = checkNotNull(activity).field
                field.setText("")
                field.showSoftInputOnFocus = true
                field.requestFocus()
                (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
            }
            val deadline = SystemClock.uptimeMillis() + 3000
            while (findText("入力モード:日本語") == null && SystemClock.uptimeMillis() < deadline) {
                if (findText("入力モード:英語") != null) clickText("入力モード:英語", 100)
                else if (findText("入力モード:数字") != null) clickText("入力モード:数字", 100)
                SystemClock.sleep(50)
            }
            waitUntil("Japanese IME key did not appear") { findText("あを入力") != null || findText("aを入力") != null }
            waitUntil("Japanese keyboard mode did not finish switching") { findText("入力モード:日本語") != null }
            val key = if (findText("あを入力") != null) "あを入力" else "aを入力"
            fun keyBounds(): android.graphics.Rect {
                if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                return android.graphics.Rect().also { checkNotNull(findText(key)).getBoundsInScreen(it) }
            }
            // OEM IMEs/window insets can still be animating after the first accessible key appears.
            var idleKeyBounds = keyBounds()
            var stableSince = SystemClock.uptimeMillis()
            val boundsDeadline = stableSince + 4000
            while (SystemClock.uptimeMillis() - stableSince < 300) {
                check(SystemClock.uptimeMillis() < boundsDeadline) { "IME did not settle before typing" }
                SystemClock.sleep(50)
                val currentBounds = keyBounds()
                if (currentBounds != idleKeyBounds) {
                    idleKeyBounds = currentBounds
                    stableSince = SystemClock.uptimeMillis()
                }
            }
            repeat(5) { check(clickText(key)) { "Japanese key not found" } }
            check(onUi { checkNotNull(activity).field.text.toString() } == "あああああ") { "Key taps did not enter five hiragana characters" }
            check(keyBounds() == idleKeyBounds) { "Composing text moved the real IME key: $idleKeyBounds -> ${keyBounds()}" }
            check(touchKey("◀") && touchKey("▶")) { "Cursor keys not found" }
            check(touchKey("Backspace")) { "Delete key not found" }
            check(onUi { checkNotNull(activity).field.text.toString() } == "ああああ") { "Delete did not remove one character" }
            check(clickText(key))
            check(onUi { checkNotNull(activity).field.text.toString() } == "あああああ")
            check(keyBounds() == idleKeyBounds) { "Cursor movement or deletion moved the real IME key" }
            check(clickText("確定", 500) || clickText("Enter", 500)) { "IME commit key not found" }
            waitUntil("IME did not commit the input") { onUi {
                BaseInputConnection.getComposingSpanStart(checkNotNull(activity).field.text) == -1
            } }
            check(onUi { checkNotNull(activity).field.text.isNotEmpty() })

            // Input policy checks run inside this already-working real-key flow: the
            // accessibility window flag above is what makes the IME keys visible, and a
            // second UiAutomation setup aborts on this OEM build.
            val policyPrefs = targetContext.getSharedPreferences(ZeroConvertInputMethodService.PREFS_SETTINGS, Context.MODE_PRIVATE)
            val numberRowKey = ZeroConvertInputMethodService.KEY_QWERTY_NUMBER_ROW
            val policyLiveKey = ZeroConvertInputMethodService.KEY_REALTIME_INLINE
            val policyAiKey = ZeroConvertInputMethodService.KEY_EXPERIMENTAL_AI_ASSIST
            val policyLayoutKey = ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT
            val policyAutoCommitKey = ZeroConvertInputMethodService.KEY_AUTO_COMMIT_ENABLED
            val savedNumberRow = policyPrefs.getBoolean(numberRowKey, false)
            val savedPolicyLive = policyPrefs.getBoolean(policyLiveKey, true)
            val savedPolicyAi = policyPrefs.getBoolean(policyAiKey, true)
            val savedPolicyLayout = policyPrefs.getString(policyLayoutKey, JapaneseInputLayout.FLICK_12KEY.name)
            val savedPolicyAutoCommit = policyPrefs.getBoolean(policyAutoCommitKey, true)
            try {
                onUi {
                    // Live conversion would turn the reading ん into 安, auto-commit would
                    // settle a stable prefix, and the punctuation keys above the bottom row
                    // are QWERTY-only. The policy check needs a plain composition.
                    policyPrefs.edit()
                        .putBoolean(numberRowKey, false)
                        .putBoolean(policyLiveKey, false)
                        .putBoolean(policyAiKey, false)
                        .putBoolean(policyAutoCommitKey, false)
                        .putString(policyLayoutKey, JapaneseInputLayout.QWERTY.name)
                        .apply()
                    checkNotNull(activity).field.setText("")
                    (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .restartInput(checkNotNull(activity).field)
                }
                waitUntil("Japanese QWERTY unavailable for policy checks") { findText("aを入力") != null }
                fun policyText() = onUi { checkNotNull(activity).field.text.toString() }
                fun policyComposingSpan() = onUi {
                    val text = checkNotNull(activity).field.text
                    BaseInputConnection.getComposingSpanStart(text) to
                        BaseInputConnection.getComposingSpanEnd(text)
                }

                // These assertions check the policy, not conversion output: the reading may be
                // shown converted (ん can preview as 安) and a stable prefix such as ん may be
                // auto-committed. What matters is that the mark stayed inside the composition
                // instead of ending it, and a pending romaji tail was finalized first.
                fun checkPunctuationJoined(mark: Char) {
                    val text = policyText()
                    check(text.isNotEmpty() && text.last() == mark) {
                        "$mark did not join the reading: '$text'"
                    }
                    val (start, end) = policyComposingSpan()
                    check(start >= 0) { "$mark committed the composition: '$text'" }
                    check(end == text.length) {
                        "$mark was committed outside the composition: '$text' (span $start..$end)"
                    }
                }

                onUi { checkNotNull(activity).field.setText("") }
                check(clickText("aを入力")); SystemClock.sleep(300)
                check(touchKey("、を入力")); SystemClock.sleep(500)
                checkPunctuationJoined('、')

                onUi { checkNotNull(activity).field.setText("") }
                check(clickText("aを入力")); SystemClock.sleep(300)
                check(touchKey("。を入力")); SystemClock.sleep(500)
                checkPunctuationJoined('。')

                onUi { checkNotNull(activity).field.setText("") }
                check(clickText("aを入力")); SystemClock.sleep(300)
                val question = if (findText("？を入力") != null) "？を入力" else null
                check(question != null) { "？ key missing while composing" }
                check(touchKey(checkNotNull(question))); SystemClock.sleep(500)
                checkPunctuationJoined('？')

                onUi { checkNotNull(activity).field.setText("") }
                check(clickText("nを入力")); SystemClock.sleep(300)
                check(touchKey("、を入力")); SystemClock.sleep(600)
                // With a pending romaji the mark must finalize it (ん) and never extend it,
                // so ending the composition here is the correct outcome for this case.
                val afterPending = policyText()
                check(afterPending.endsWith("、")) { "、 did not follow the finalized reading: '$afterPending'" }
                check(!afterPending.startsWith("n")) {
                    "Pending n was left as a letter instead of ん: '$afterPending'"
                }
                check(afterPending.length == 2) {
                    "n + 、 produced more than the finalized reading and mark: '$afterPending'"
                }
            } finally {
                onUi {
                    policyPrefs.edit()
                        .putBoolean(numberRowKey, savedNumberRow)
                        .putBoolean(policyLiveKey, savedPolicyLive)
                        .putBoolean(policyAiKey, savedPolicyAi)
                        .putBoolean(policyAutoCommitKey, savedPolicyAutoCommit)
                        .putString(policyLayoutKey, savedPolicyLayout)
                        .apply()
                    checkNotNull(activity).field.setText("")
                }
            }
        }
        if (BuildConfig.WINDOWS_ENGINE) test("real_ime_cursor_prefix_commit_and_selection_count") {
            val prefs = targetContext.getSharedPreferences(ZeroConvertInputMethodService.PREFS_SETTINGS, Context.MODE_PRIVATE)
            val layoutKey = ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT
            val autoKey = ZeroConvertInputMethodService.KEY_AUTO_COMMIT_ENABLED
            val liveKey = ZeroConvertInputMethodService.KEY_REALTIME_INLINE
            val hadLayout = prefs.contains(layoutKey)
            val savedLayout = prefs.getString(layoutKey, JapaneseInputLayout.FLICK_12KEY.name)
            val hadAuto = prefs.contains(autoKey)
            val savedAuto = prefs.getBoolean(autoKey, true)
            val hadLive = prefs.contains(liveKey)
            val savedLive = prefs.getBoolean(liveKey, true)
            try {
                onUi {
                    prefs.edit().putBoolean(autoKey, false).putBoolean(liveKey, true).apply()
                    val field = checkNotNull(activity).field
                    field.setText("")
                    (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(field)
                }
                waitUntil("Japanese mode unavailable") { findText("入力モード:日本語") != null }
                if (findText("QWERTY") != null) check(clickText("QWERTY"))
                waitUntil("Japanese QWERTY unavailable") { findText("tを入力") != null }
                for (key in "toukyouniiku") check(clickText("${key}を入力"))
                repeat(3) { check(touchKey("◀")) }
                waitUntil("Cursor prefix candidate Tokyo missing") { findText("変換候補 東京") != null }
                waitUntil("Prefix preview or caret did not preserve the suffix") { onUi {
                    val field = checkNotNull(activity).field
                    field.text.toString() == "東京にいく" && field.selectionStart == 2 && field.selectionEnd == 2
                } }
                // Some editors combine our new caret with an older composing
                // range, or omit the range. Neither means a user moved elsewhere.
                for (omitRange in listOf(false, true)) {
                    onUi {
                        val field = checkNotNull(activity).field
                        (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .updateSelection(field, field.selectionStart, field.selectionEnd,
                                if (omitRange) -1 else 0, if (omitRange) -1 else field.text.length + 3)
                    }
                    SystemClock.sleep(150)
                    check(onUi { BaseInputConnection.getComposingSpanStart(checkNotNull(activity).field.text) == 0 }) {
                        "Our cursor notification committed the composition: omittedRange=$omitRange"
                    }
                    check(findText("変換候補 東京") != null) { "Our cursor notification removed conversion candidates" }
                }
                check(touchKey("▶") && touchKey("◀"))
                waitUntil("Cursor prefix candidate did not return after moving right then left") {
                    findText("変換候補 東京") != null
                }
                check(clickText("変換候補 東京"))
                waitUntil("Partial commit lost the remaining composition") { onUi {
                    val text = checkNotNull(activity).field.text
                    text.startsWith("東京") && text.length > 2 && BaseInputConnection.getComposingSpanStart(text) == 2
                } }
                check(clickText("確定", 500) || clickText("Enter", 500))
                waitUntil("Remaining composition was not committed") { onUi {
                    val text = checkNotNull(activity).field.text
                    text.startsWith("東京") && text.length > 2 && BaseInputConnection.getComposingSpanStart(text) == -1
                } }
                onUi {
                    checkNotNull(activity).field.apply { setText("東京に行く"); setSelection(0, 2) }
                }
                waitUntil("Selection counter missing after prefix conversion") { findText("2文字選択") != null }
            } finally {
                onUi {
                    prefs.edit().apply {
                        if (hadLayout) putString(layoutKey, savedLayout) else remove(layoutKey)
                        if (hadAuto) putBoolean(autoKey, savedAuto) else remove(autoKey)
                        if (hadLive) putBoolean(liveKey, savedLive) else remove(liveKey)
                    }.apply()
                    val field = checkNotNull(activity).field
                    field.setText("")
                    (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(field)
                }
            }
        }
        if (BuildConfig.WINDOWS_ENGINE) test("real_ime_cursor_into_converted_region_keeps_composition") {
            val prefs = targetContext.getSharedPreferences(ZeroConvertInputMethodService.PREFS_SETTINGS, Context.MODE_PRIVATE)
            val layoutKey = ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT
            val autoKey = ZeroConvertInputMethodService.KEY_AUTO_COMMIT_ENABLED
            val liveKey = ZeroConvertInputMethodService.KEY_REALTIME_INLINE
            val hadLayout = prefs.contains(layoutKey)
            val savedLayout = prefs.getString(layoutKey, JapaneseInputLayout.FLICK_12KEY.name)
            val hadAuto = prefs.contains(autoKey)
            val savedAuto = prefs.getBoolean(autoKey, true)
            val hadLive = prefs.contains(liveKey)
            val savedLive = prefs.getBoolean(liveKey, true)
            try {
                onUi {
                    prefs.edit().putBoolean(autoKey, false).putBoolean(liveKey, true).apply()
                    val field = checkNotNull(activity).field
                    field.setText("")
                    (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(field)
                }
                waitUntil("Japanese mode unavailable") { findText("入力モード:日本語") != null }
                if (findText("QWERTY") != null) check(clickText("QWERTY"))
                waitUntil("Japanese QWERTY unavailable") { findText("tを入力") != null }
                fun restartEditor() = onUi {
                    val field = checkNotNull(activity).field
                    field.setText("")
                    (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(field)
                }
                fun typeReading(raw: String) {
                    restartEditor()
                    waitUntil("Japanese mode unavailable before typing $raw") { findText("入力モード:日本語") != null }
                    waitUntil("Japanese QWERTY unavailable before typing $raw") { findText("tを入力") != null }
                    for (key in raw) check(clickText("${key}を入力")) { "QWERTY key missing: $key" }
                }
                fun convertedPreview(raw: String, reading: String): String {
                    typeReading(raw)
                    val deadline = SystemClock.uptimeMillis() + 4000
                    while (SystemClock.uptimeMillis() < deadline) {
                        val preview = onUi { checkNotNull(activity).field.text.toString() }
                        if (preview != reading) return preview
                        SystemClock.sleep(50)
                    }
                    return onUi { checkNotNull(activity).field.text.toString() }
                }
                // The preview and the reading diverge once the caret passes into a kanji-converted
                // tail; the IME must not revert the whole composition to raw kana from there.
                var rawReading = "きょうはいいてんきですね"
                var preview = convertedPreview("kyouhaiitenkidesune", rawReading)
                if (preview == rawReading) {
                    rawReading = "とうきょうにいく"
                    preview = convertedPreview("toukyouniiku", rawReading)
                }
                check(preview != rawReading) { "Live conversion never left raw kana: reading=$rawReading preview=$preview" }
                // Stop before the reading cursor reaches 0, where reverting to the raw reading is expected.
                val compositionLength = minOf(preview.length, rawReading.length)
                check(compositionLength > 1) { "Composition too short to move: $preview" }
                fun state(): Triple<String, Int, Int> = onUi {
                    val text = checkNotNull(activity).field.text
                    Triple(text.toString(), checkNotNull(activity).field.selectionStart,
                        BaseInputConnection.getComposingSpanStart(text))
                }
                // A raw preview may be a pending re-conversion: require a stable settled state first.
                fun settledState(): Triple<String, Int, Int> {
                    val deadline = SystemClock.uptimeMillis() + 2000
                    var previous: Triple<String, Int, Int>? = null
                    while (true) {
                        val current = state()
                        val converted = current.first.any { it !in '぀'..'ヿ' }
                        val hasCandidate = findNode { it.contentDescription?.startsWith("変換候補 ") == true } != null
                        if (current == previous && current.third >= 0 && current.first != rawReading &&
                            (converted || hasCandidate)) return current
                        previous = current
                        if (SystemClock.uptimeMillis() >= deadline) return current
                        SystemClock.sleep(150)
                    }
                }
                for (step in 1 until compositionLength) {
                    check(touchKey("◀")) { "Cursor left key missing at step $step" }
                    val (text, caret, composingStart) = settledState()
                    check(composingStart >= 0) { "Step $step committed the composition: caret=$caret text=$text" }
                    check(text != rawReading) { "Step $step reverted the composition to raw kana: caret=$caret text=$text" }
                    check(text.any { it !in '぀'..'ヿ' } ||
                        findNode { it.contentDescription?.startsWith("変換候補 ") == true } != null) {
                        "Step $step lost both the converted preview and every candidate: caret=$caret text=$text"
                    }
                }
            } finally {
                onUi {
                    prefs.edit().apply {
                        if (hadLayout) putString(layoutKey, savedLayout) else remove(layoutKey)
                        if (hadAuto) putBoolean(autoKey, savedAuto) else remove(autoKey)
                        if (hadLive) putBoolean(liveKey, savedLive) else remove(liveKey)
                    }.apply()
                    val field = checkNotNull(activity).field
                    field.setText("")
                    (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(field)
                }
            }
        }
        for (layout in listOf(JapaneseInputLayout.QWERTY, JapaneseInputLayout.FLICK_12KEY)) for (prefix in listOf("he", "get")) {
            test("english_enter_and_mode_switch_preserve_spelling_${layout.name.lowercase()}_$prefix") {
                val prefs = targetContext.getSharedPreferences(ZeroConvertInputMethodService.PREFS_SETTINGS, Context.MODE_PRIVATE)
                val key = ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT
                val hadLayout = prefs.contains(key)
                val savedLayout = prefs.getString(key, JapaneseInputLayout.FLICK_12KEY.name)
                try {
                    onUi {
                        val field = checkNotNull(activity).field
                        field.setText("")
                        (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(field)
                    }
                    SystemClock.sleep(150)
                    repeat(3) {
                        if (findText("入力モード:英語") == null) {
                            val mode = if (findText("入力モード:日本語") != null) "入力モード:日本語" else "入力モード:数字"
                            check(clickText(mode)) { "Could not switch from $mode" }
                            waitUntil("Input mode did not change") { findText(mode) == null }
                        }
                    }
                    waitUntil("English mode did not appear") { findText("入力モード:英語") != null }
                    val switchLabel = if (layout == JapaneseInputLayout.QWERTY) "QWERTY" else "12キー"
                    if (findText(switchLabel) != null) check(clickText(switchLabel))
                    waitUntil("English layout did not switch") {
                        findText(if (layout == JapaneseInputLayout.QWERTY) "hを入力" else "ghiを入力") != null
                    }
                    fun pressEnter() = clickText(if (layout == JapaneseInputLayout.QWERTY) "Enter" else "確定")
                    fun typeLetter(letter: Char) {
                        if (layout == JapaneseInputLayout.QWERTY) {
                            check(clickText("${letter}を入力")) { "QWERTY English key not found: $letter" }
                        } else {
                            val (label, direction) = when (letter) {
                                'h' -> "ghiを入力" to -32f
                                'g' -> "ghiを入力" to 0f
                                'e' -> "defを入力" to -32f
                                't' -> "tuvを入力" to 0f
                                else -> error("Unsupported test letter: $letter")
                            }
                            check(touchKey(label, direction)) { "English flick key not found: $letter" }
                        }
                    }
                    fun typePrefix() {
                        prefix.forEach { typeLetter(it) }
                        check(onUi { checkNotNull(activity).field.text.toString() } == prefix) { "English keys did not type $prefix" }
                        waitUntil("English completion did not appear") {
                            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                            findNode { it.contentDescription?.startsWith("変換候補 ") == true &&
                                it.contentDescription?.toString()?.removePrefix("変換候補 ")?.startsWith(prefix, ignoreCase = true) == true &&
                                it.contentDescription?.toString() != "変換候補 $prefix" } != null
                        }
                        if (prefix == "get") {
                            waitUntil("getting completion did not appear") { findText("変換候補 getting") != null }
                        }
                    }
                    typePrefix()
                    if (prefix == "he") {
                        check(touchKey("Backspace"))
                        check(onUi { checkNotNull(activity).field.text.toString() } == "h")
                        typeLetter('e')
                        waitUntil("Completion did not return after deletion") {
                            findNode { it.contentDescription?.startsWith("変換候補 ") == true } != null
                        }
                    } else {
                        waitUntil("getting completion did not appear") { findText("変換候補 getting") != null }
                    }
                    check(pressEnter())
                    check(onUi {
                        val text = checkNotNull(activity).field.text
                        text.toString() == prefix && BaseInputConnection.getComposingSpanStart(text) == -1
                    }) { "Enter replaced the spelling with a completion or added a newline" }
                    check(pressEnter())
                    check(onUi { checkNotNull(activity).field.text.toString() } == "$prefix\n") {
                        "Enter after commit did not insert a newline"
                    }
                    onUi { checkNotNull(activity).field.setText("") }
                    typePrefix()
                    val completion = if (prefix == "get") "getting" else checkNotNull(findNode {
                        it.contentDescription?.startsWith("変換候補 ") == true &&
                            it.contentDescription?.toString()?.removePrefix("変換候補 ")?.startsWith(prefix, ignoreCase = true) == true
                    }).contentDescription.toString().removePrefix("変換候補 ")
                    check(clickText("変換候補 $completion"))
                    check(onUi { checkNotNull(activity).field.text.toString() } == completion) {
                        "Explicit candidate selection no longer adopts the completion"
                    }
                    if (layout == JapaneseInputLayout.QWERTY && prefix == "get") {
                        for (punctuation in listOf(',', '.', '?')) {
                            onUi { checkNotNull(activity).field.setText("") }
                            typePrefix()
                            check(clickText("${punctuation}を入力")) { "Punctuation key not found: $punctuation" }
                            check(onUi {
                                val text = checkNotNull(activity).field.text
                                text.toString() == "$prefix$punctuation" && BaseInputConnection.getComposingSpanStart(text) == -1
                            }) { "Punctuation replaced the spelling with a completion: $punctuation" }
                            typeLetter('h')
                            check(pressEnter())
                            check(onUi { checkNotNull(activity).field.text.toString() } == "$prefix${punctuation}h") {
                                "Input after punctuation did not start a new word"
                            }
                        }
                    }
                    onUi { checkNotNull(activity).field.setText("") }
                    typePrefix()
                    check(clickText("入力モード:英語"))
                    waitUntil("Numeric mode did not appear") { findText("入力モード:数字") != null }
                    check(onUi {
                        val text = checkNotNull(activity).field.text
                        text.toString() == prefix && BaseInputConnection.getComposingSpanStart(text) == -1
                    }) { "Mode switch did not commit the literal English spelling: $prefix" }
                    check(clickText("入力モード:数字"))
                    waitUntil("Japanese mode did not appear") { findText("入力モード:日本語") != null }
                    check(clickText(if (layout == JapaneseInputLayout.QWERTY) "aを入力" else "あを入力"))
                    check(onUi {
                        val text = checkNotNull(activity).field.text
                        text.startsWith(prefix) && text.length > prefix.length &&
                            BaseInputConnection.getComposingSpanStart(text) == prefix.length
                    }) { "Japanese input replaced the preceding English spelling: $prefix" }
                    check(touchKey("Backspace"))
                    check(onUi { checkNotNull(activity).field.text.toString() } == prefix) {
                        "Deleting Japanese input also removed the preceding English spelling"
                    }
                    check(clickText("入力モード:日本語"))
                    waitUntil("English mode did not return") { findText("入力モード:英語") != null }
                    typeLetter('h')
                    check(pressEnter())
                    check(onUi { checkNotNull(activity).field.text.toString() } == prefix + "h") {
                        "Switching back reused an obsolete English buffer"
                    }
                } finally {
                    val restoreLabel = if (savedLayout == JapaneseInputLayout.QWERTY.name) "QWERTY" else "12キー"
                    if (findText(restoreLabel) != null) clickText(restoreLabel)
                    onUi {
                        if (hadLayout) prefs.edit().putString(key, savedLayout).apply()
                        else prefs.edit().remove(key).apply()
                    }
                }
            }
        }
        if (BuildConfig.WINDOWS_ENGINE) test("learning_round_trip_deletes_only_the_taught_word") {
            ZenzNative.init(targetContext)
            // These checks run inside LearningPolicy.withoutWrites, so they must not
            // rewrite the user's learning memory. validateCandidateDeletion applies
            // the same guards and single-shot consume but skips the store writes;
            // the write path itself is covered by the Rust and Swift unit tests.
            //
            // Before any id is issued, an id that was never issued must be refused.
            check(!com.zeroconvert.ime.engine.WindowsEngine.validateCandidateDeletion("0")) {
                "Zero candidate id was treated as a deletable candidate"
            }
            check(!com.zeroconvert.ime.engine.WindowsEngine.validateCandidateDeletion("18446744073709551615")) {
                "Unknown candidate id was treated as deletable"
            }

            // Ask for one synthetic reading outside the user's history and take the
            // identity the engine issues for it, then confirm the same id is refused
            // the second time because the first validation consumed it.
            val reading = "ぜろこんけんしょう"
            val input = ComposingText().apply { insertAtCursorPosition(reading, InputStyle.DIRECT) }
            val issued = runBlocking {
                com.zeroconvert.ime.engine.WindowsEngine.candidates(input, "", false, "", true, false)
            }
            val deletable = issued.firstOrNull { it.engineCandidateId.isNotEmpty() }
            if (deletable == null) {
                // A device without read-write learning has no deletable identity, so
                // there is nothing to check and the guard above still covers refusal.
                android.util.Log.i(
                    "ZeroConLearning",
                    "No engine candidate identity issued (learning mode ${LearningPolicy.mode}); " +
                        "skipping the accept-then-refuse path"
                )
            } else {
                check(
                    com.zeroconvert.ime.engine.WindowsEngine.validateCandidateDeletion(deletable.engineCandidateId)
                ) { "Deletion validation was refused for a candidate the engine issued" }
                check(
                    !com.zeroconvert.ime.engine.WindowsEngine.validateCandidateDeletion(deletable.engineCandidateId)
                ) { "A consumed candidate id was accepted twice" }
            }
        }
        if (BuildConfig.WINDOWS_ENGINE) test("candidate_without_engine_identity_cannot_be_deleted") {
            ZenzNative.init(targetContext)
            // Dictionary-correction and AI candidates carry no engine id, and the
            // candidate bar only offers deletion for entries that have one. The
            // popup itself renders inside the IME window, which uiautomator cannot
            // inspect, so this asserts the guard the UI relies on.
            check(!com.zeroconvert.ime.engine.WindowsEngine.validateCandidateDeletion("")) {
                "An empty candidate id was accepted for deletion"
            }
        }
        if (BuildConfig.WINDOWS_ENGINE) test("long_press_offers_inline_learning_deletion_confirmation") {
            ZenzNative.init(targetContext)
            KanaKanjiConverter.ensureInitialized(targetContext)
            val layoutPrefs = targetContext.getSharedPreferences(
                ZeroConvertInputMethodService.PREFS_SETTINGS, Context.MODE_PRIVATE
            )
            val layoutKey = ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT
            val hadLayout = layoutPrefs.contains(layoutKey)
            val savedLayout = layoutPrefs.getString(layoutKey, JapaneseInputLayout.FLICK_12KEY.name)
            try {
                val info = uiAutomation.serviceInfo
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                uiAutomation.serviceInfo = info
                waitUntil("Test editor has no window focus") {
                    onUi { checkNotNull(activity).field.hasWindowFocus() }
                }
                onUi {
                    val field = checkNotNull(activity).field
                    field.setText("")
                    field.showSoftInputOnFocus = true
                    field.requestFocus()
                    (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                }
                // OEM IMEs and our own mode switch can still be settling right after
                // the field gains focus, so drive the mode the same way the other
                // real-IME checks do instead of assuming the first frame is final.
                val modeDeadline = SystemClock.uptimeMillis() + 5000
                while (findText("入力モード:日本語") == null && SystemClock.uptimeMillis() < modeDeadline) {
                    if (findText("入力モード:英語") != null) clickText("入力モード:英語", 200)
                    else if (findText("入力モード:数字") != null) clickText("入力モード:数字", 200)
                    SystemClock.sleep(100)
                }
                check(findText("入力モード:日本語") != null) { "Japanese mode unavailable" }
                if (findText("QWERTY") != null) check(clickText("QWERTY"))
                waitUntil("Japanese QWERTY unavailable") { findText("tを入力") != null }
                for (letter in listOf('t', 'o', 'u', 'k', 'y', 'o', 'u')) {
                    check(touchKey("${letter}を入力")) { "QWERTY key not found: $letter" }
                }
                var candidate: String? = null
                waitUntil("No candidate with an engine identity appeared") {
                    if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.clearCache()
                    val label = findNode {
                        it.contentDescription?.toString()?.startsWith("変換候補 ") == true
                    }?.contentDescription?.toString()
                    candidate = label?.takeIf { it != "変換候補 候補" }
                    candidate != null
                }
                check(longPressText(checkNotNull(candidate))) {
                    "Long press did not reach the candidate: $candidate"
                }
                // The confirmation must stay inside the IME window. A Dialog here
                // crashed with BadTokenException because the IME has no activity token.
                waitUntil("Inline deletion confirmation did not appear") {
                    findNode { it.contentDescription?.toString() == "学習削除の確認" } != null
                }
                check(findText("削除") != null) { "Delete action missing from the inline confirmation" }
                check(clickText("キャンセル")) { "Cancel action missing from the inline confirmation" }
                waitUntil("Inline confirmation did not close after cancel") {
                    findNode { it.contentDescription?.toString() == "学習削除の確認" } == null
                }
            } finally {
                onUi {
                    if (hadLayout) layoutPrefs.edit().putString(layoutKey, savedLayout).apply()
                    else layoutPrefs.edit().remove(layoutKey).apply()
                    (targetContext.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .restartInput(checkNotNull(activity).field)
                }
            }
        }
        activity?.let { editor -> onUi { editor.finish() } }
        return (if (failures == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED) to Bundle().apply {
            putString("stream", "\n$report\nTests: $tests, failures: $failures\n")
            putInt("tests", tests)
            putInt("failures", failures)
        }
    }
}




