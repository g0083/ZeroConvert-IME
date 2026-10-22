package com.zeroconvert.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputStyle
import kotlinx.coroutines.*

data class InferenceSnapshot(
    val readingHiragana: String,
    val aiSurface: String
)

data class AutoCommitCandidate(
    val readingLength: Int,
    val committedReading: String,
    val surfacePrefix: String
)

data class AutoCommitRewindRecord(
    val committedSurface: String,
    val committedReading: String,
    val previousLeftContext: String
)

class ZeroConvertInputMethodService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    companion object {
        private const val TAG = "ZeroConvertIME"
        const val PREFS_SETTINGS = "ime_settings"
        const val KEY_REALTIME_INLINE = "realtime_inline_conversion"
        const val KEY_AUTO_COMMIT_ENABLED = "auto_commit_enabled"
        const val KEY_AUTO_COMMIT_REWIND_ON_BACKSPACE = "auto_commit_rewind_on_backspace"
        const val KEY_AI_PROFILE = "ai_profile"
        const val KEY_THEME_MODE = "theme_mode" // "system", "dark", "light"
        const val KEY_JAPANESE_LAYOUT = "japanese_keyboard_layout" // "FLICK_12KEY", "QWERTY"
        const val KEY_FLICK_ONLY = "flick_only" // boolean, default true
        const val KEY_VIBRATION_ENABLED = "vibration_enabled" // boolean, default true
        const val KEY_VIBRATION_DURATION = "vibration_duration" // int ms (5..50), default 15
        const val KEY_EXPERIMENTAL_AI_ASSIST = "experimental_ai_assist" // boolean, default true
        const val KEY_PREDICTIVE_MODEL = "experimental_predictive_model" // "rule_based", "230m_q4km", "350m_q4km"

        private const val MIN_READING_LEN_FOR_AUTO_COMMIT = 16
        private const val MIN_TAIL_UNCOMMITTED_READING_LEN = 7
        private const val STABLE_INFERENCE_COUNT = 5
        private const val MAX_LEFT_CONTEXT_LEN = 40

        fun isKanjiOrKatakana(ch: Char): Boolean {
            val code = ch.code
            return (code in 0x4E00..0x9FFF) || // CJK Unified Ideographs
                (code in 0x3400..0x4DBF) ||    // CJK Extension A
                (code == 0x3005) ||            // 々
                (code in 0x30A1..0x30FA) ||    // Katakana
                (code == 0x30FC)               // Prolonged sound mark ー
        }

        fun isHiraganaChar(ch: Char): Boolean {
            return ch in '\u3041'..'\u3096'
        }

        fun hasTrailingIncompleteRomaji(readingHiragana: String): Boolean {
            if (readingHiragana.isEmpty()) return false
            val last = readingHiragana.last()
            return last in 'a'..'z' || last in 'A'..'Z'
        }

        fun isJapaneseClausePunctuation(ch: Char): Boolean {
            return ch in "、。，．！？!?,\n"
        }

        fun isNaturalAutoCommitPrefix(candidatePrefix: String, fullPreview: String): Boolean {
            if (!fullPreview.startsWith(candidatePrefix)) return false
            val remainder = fullPreview.substring(candidatePrefix.length)
            if (remainder.isEmpty() || candidatePrefix.length < 3) return false
            val lastChar = candidatePrefix.last()
            val nextChar = remainder.first()
            if (isJapaneseClausePunctuation(lastChar)) return true

            // Must contain at least one Kanji or Katakana, end in Hiragana (okurigana/particle),
            // and the next clause must start with Kanji, Katakana, or punctuation.
            // This strictly prevents cutting inside inflected verbs (e.g. 変えられる -> 変 + えられる).
            val hasKanjiOrKatakana = candidatePrefix.any { isKanjiOrKatakana(it) }
            val endsWithHiragana = isHiraganaChar(lastChar)
            val nextIsContentWord = isKanjiOrKatakana(nextChar) || isJapaneseClausePunctuation(nextChar)
            return hasKanjiOrKatakana && endsWithHiragana && nextIsContentWord
        }

        /**
         * Finds the longest natural, stable clause prefix (L, P) matching Windows IME rules:
         * - currentReading.length >= 16 and no trailing incomplete romaji consonant
         * - L <= currentReading.length - 7 (at least 7 hiragana chars remain uncommitted)
         * - P has remained unchanged across the last 5 consecutive AI inferences
         * - P is a natural clause boundary (ends in Hiragana okurigana/particle, next char is Kanji/Katakana)
         */
        fun findStableAutoCommitPrefix(
            currentReading: String,
            history: List<InferenceSnapshot>
        ): AutoCommitCandidate? {
            if (currentReading.length < MIN_READING_LEN_FOR_AUTO_COMMIT) return null
            if (hasTrailingIncompleteRomaji(currentReading)) return null
            if (history.size < STABLE_INFERENCE_COUNT) return null

            val currentAiSurface = history.last().aiSurface
            val maxCommitReadingLen = currentReading.length - MIN_TAIL_UNCOMMITTED_READING_LEN
            if (maxCommitReadingLen < 3) return null

            val recent5 = history.takeLast(STABLE_INFERENCE_COUNT)
            var bestCandidate: AutoCommitCandidate? = null

            for (snap in history) {
                val l = snap.readingHiragana.length
                val p = snap.aiSurface
                if (l < 3 || l > maxCommitReadingLen) continue
                if (!currentReading.startsWith(snap.readingHiragana)) continue
                if (!isNaturalAutoCommitPrefix(p, currentAiSurface)) continue

                // Check that across the last 5 consecutive AI inferences, the surface prefix P
                // remained strictly stable.
                val isStableAcrossRecent5 = recent5.all { recent ->
                    recent.readingHiragana.startsWith(snap.readingHiragana) &&
                        recent.aiSurface.startsWith(p) &&
                        recent.aiSurface.length > p.length
                }

                if (isStableAcrossRecent5) {
                    if (bestCandidate == null || l > bestCandidate.readingLength) {
                        bestCandidate = AutoCommitCandidate(
                            readingLength = l,
                            committedReading = snap.readingHiragana,
                            surfacePrefix = p
                        )
                    }
                }
            }

            return bestCandidate
        }
    }

    val isRealtimeInlineState = mutableStateOf(false)
    val isAutoCommitEnabledState = mutableStateOf(true)
    val isAutoCommitRewindState = mutableStateOf(false)
    val aiProfileState = mutableStateOf("")
    val themeModeState = mutableStateOf("system")
    val japaneseLayoutState = mutableStateOf(JapaneseInputLayout.FLICK_12KEY)
    val isFlickOnlyState = mutableStateOf(true)
    val isVibrationEnabledState = mutableStateOf(true)
    val vibrationDurationState = mutableStateOf(15)
    val isAiAssistEnabledState = mutableStateOf(true)
    val predictiveModelState = mutableStateOf("rule_based")
    val aiAssistResultState = mutableStateOf<com.zeroconvert.ime.assist.AiAssistResult?>(null)

    fun isRealtimeInlineEnabled(): Boolean = isRealtimeInlineState.value
    fun isAiAssistEnabled(): Boolean = isAiAssistEnabledState.value
    fun getPredictiveModel(): String = predictiveModelState.value

    fun adoptAiAssist() {
        val assist = aiAssistResultState.value ?: return
        vibrateOnKeyPress()
        val textToCommit = assist.text
        currentInputConnection?.commitText(textToCommit, 1)
        clearInput()
    }

    fun setRealtimeInlineEnabled(enabled: Boolean) {
        isRealtimeInlineState.value = enabled
        val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_REALTIME_INLINE, enabled).apply()
    }

    fun isAutoCommitEnabled(): Boolean = isAutoCommitEnabledState.value
    fun isAutoCommitRewindEnabled(): Boolean = isAutoCommitRewindState.value
    fun isFlickOnly(): Boolean = isFlickOnlyState.value

    fun setJapaneseLayout(layout: JapaneseInputLayout) {
        japaneseLayoutState.value = layout
        val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_JAPANESE_LAYOUT, layout.name).apply()
    }

    fun setFlickOnly(enabled: Boolean) {
        isFlickOnlyState.value = enabled
        val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FLICK_ONLY, enabled).apply()
    }

    fun vibrateOnKeyPress() {
        if (!isVibrationEnabledState.value) return
        val duration = vibrationDurationState.value.toLong().coerceIn(5L, 60L)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(duration)
                }
            }
        } catch (e: Exception) {
            // Ignore vibration errors gracefully
        }
    }

    fun openSettings() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings from IME", e)
        }
    }

    private var sharedPreferences: android.content.SharedPreferences? = null
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            reloadPreferences()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                reloadPreferences()
            }
        }
    }

    private fun reloadPreferences() {
        val prefs = sharedPreferences ?: getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        isRealtimeInlineState.value = prefs.getBoolean(KEY_REALTIME_INLINE, false)
        isAutoCommitEnabledState.value = prefs.getBoolean(KEY_AUTO_COMMIT_ENABLED, true)
        isAutoCommitRewindState.value = prefs.getBoolean(KEY_AUTO_COMMIT_REWIND_ON_BACKSPACE, false)
        aiProfileState.value = prefs.getString(KEY_AI_PROFILE, "") ?: ""
        themeModeState.value = prefs.getString(KEY_THEME_MODE, "system") ?: "system"
        val layoutName = prefs.getString(KEY_JAPANESE_LAYOUT, JapaneseInputLayout.FLICK_12KEY.name)
        japaneseLayoutState.value = try {
            JapaneseInputLayout.valueOf(layoutName ?: JapaneseInputLayout.FLICK_12KEY.name)
        } catch (e: Exception) {
            JapaneseInputLayout.FLICK_12KEY
        }
        isFlickOnlyState.value = prefs.getBoolean(KEY_FLICK_ONLY, true)
        isVibrationEnabledState.value = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        vibrationDurationState.value = prefs.getInt(KEY_VIBRATION_DURATION, 15)
        isAiAssistEnabledState.value = prefs.getBoolean(KEY_EXPERIMENTAL_AI_ASSIST, true)
        predictiveModelState.value = prefs.getString(KEY_PREDICTIVE_MODEL, "rule_based") ?: "rule_based"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Lifecycle components for Jetpack Compose inside InputMethodService
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    // azooKey IME State Machine
    val composingText = ComposingText()
    val currentComposingSurface = mutableStateOf("")
    val predictedCandidate = mutableStateOf("")
    val candidatesList = mutableStateListOf<Candidate>()
    val clipboardHistory = mutableStateListOf<String>()

    private val converter = KanaKanjiConverter()
    private var clipboardManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var inferenceJob: Job? = null
    private var cachedLeftContext: String = ""

    // Sliding-chunk auto-commit history & rewind state
    private val inferenceHistory = mutableListOf<InferenceSnapshot>()
    private var lastAutoCommitRewind: AutoCommitRewindRecord? = null
    private var canRewindOnNextBackspace: Boolean = false
    private var hasSelectionInEditor: Boolean = false

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        hasSelectionInEditor = (newSelStart != newSelEnd)
        if (hasSelectionInEditor && !composingText.isEmpty && (candidatesStart < 0 || candidatesEnd < 0)) {
            clearInput()
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        sharedPreferences = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsListener)
        reloadPreferences()

        // Init clipboard
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(this).toString()
                if (text.isNotBlank() && !clipboardHistory.contains(text)) {
                    clipboardHistory.add(0, text)
                    if (clipboardHistory.size > 20) clipboardHistory.removeLast()
                }
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        // Init ZeroCon AI Native model and dictionaries asynchronously
        serviceScope.launch(Dispatchers.IO) {
            Log.i(TAG, "Initializing ZenzNative & dictionaries in background...")
            KanaKanjiConverter.ensureInitialized(applicationContext)
            val success = ZenzNative.init(applicationContext)
            Log.i(TAG, "Initialization result: $success")
        }
    }

    override fun onConfigureWindow(win: android.view.Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
        super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        win.decorView.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
    }

    override fun onCreateInputView(): View {
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ZeroConvertInputMethodService)
            setViewTreeViewModelStoreOwner(this@ZeroConvertInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@ZeroConvertInputMethodService)

            setContent {
                KeyboardRootView(
                    imeService = this@ZeroConvertInputMethodService,
                    currentSurface = currentComposingSurface.value,
                    predictedCandidate = predictedCandidate.value,
                    candidates = candidatesList,
                    clipboardHistory = clipboardHistory,
                    isRealtimeInline = isRealtimeInlineState.value,
                    aiAssistResult = aiAssistResultState.value,
                    onAdoptAiAssist = { adoptAiAssist() },
                    themeMode = themeModeState.value,
                    onToggleRealtimeInline = {
                        setRealtimeInlineEnabled(!isRealtimeInlineState.value)
                    },
                    onCommitText = { text -> commitText(text, isExplicitSelection = true) },
                    onDeleteChar = { handleDelete() },
                    onEnter = { handleEnter() },
                    onClearInput = { clearInput() }
                )
            }
        }
        return composeView
    }

    private var lastInferredSurface: String = ""
    private var lastInferredResult: String = ""
    private var isConfirming = false

    fun handleChar(ch: Char) {
        vibrateOnKeyPress()
        if (isConfirming) return
        canRewindOnNextBackspace = false

        if (ch == '、' || ch == '。' || ch == '.' || ch == ',' || ch == '?' || ch == '!' ||
            ch.isDigit() || ch in "!@#$%^&*()"
        ) {
            val punc = when (ch) {
                '.' -> "。"
                ',' -> "、"
                else -> ch.toString()
            }
            if (!composingText.isEmpty) {
                confirmAsync(trailingText = punc)
            } else {
                commitDirect(punc)
            }
            return
        }

        // Capture real confirmed left context ONLY at the start of input session
        if (composingText.isEmpty) {
            val ic = currentInputConnection
            cachedLeftContext = (ic?.getTextBeforeCursor(MAX_LEFT_CONTEXT_LEN, 0)?.toString() ?: "")
                .takeLast(MAX_LEFT_CONTEXT_LEN)
            inferenceHistory.clear()
            lastAutoCommitRewind = null
        }

        // Abort ongoing inference
        ZenzNative.abort()
        inferenceJob?.cancel()

        // Insert into azooKey ComposingText state machine
        composingText.insertAtCursorPosition(ch.toString(), InputStyle.ROMAN2KANA)
        val surface = composingText.convertTargetBeforeCursor
        currentComposingSurface.value = surface

        // Handle inline display:
        // If realtime inline is ON: append diff to previous AI result
        // If realtime inline is OFF (default): show raw kana directly without kanji replacement
        val isRealtime = isRealtimeInlineEnabled()
        val tentativeDisplay = if (isRealtime) {
            if (lastInferredResult.isNotEmpty() &&
                surface.startsWith(lastInferredSurface) &&
                surface.length > lastInferredSurface.length
            ) {
                val diff = surface.substring(lastInferredSurface.length)
                lastInferredResult + diff
            } else {
                surface
            }
        } else {
            composingText.convertTarget
        }

        currentInputConnection?.setComposingText(tentativeDisplay, 1)

        // Trigger conversion query
        if (surface.isNotEmpty()) {
            triggerDebouncedConversion(surface)
        } else {
            clearCandidateList()
        }
    }

    fun handleKana(kana: String) {
        vibrateOnKeyPress()
        if (isConfirming) return
        canRewindOnNextBackspace = false

        if (kana == "、" || kana == "。" || kana == "？" || kana == "！") {
            if (!composingText.isEmpty) {
                confirmAsync(trailingText = kana)
            } else {
                commitDirect(kana)
            }
            return
        }

        // Capture real confirmed left context ONLY at the start of input session
        if (composingText.isEmpty) {
            val ic = currentInputConnection
            cachedLeftContext = (ic?.getTextBeforeCursor(MAX_LEFT_CONTEXT_LEN, 0)?.toString() ?: "")
                .takeLast(MAX_LEFT_CONTEXT_LEN)
            inferenceHistory.clear()
            lastAutoCommitRewind = null
        }

        // Abort ongoing inference
        ZenzNative.abort()
        inferenceJob?.cancel()

        // Insert direct kana into composingText
        composingText.insertAtCursorPosition(kana, InputStyle.DIRECT)
        val surface = composingText.convertTargetBeforeCursor
        currentComposingSurface.value = surface

        val isRealtime = isRealtimeInlineEnabled()
        val tentativeDisplay = if (isRealtime) {
            if (lastInferredResult.isNotEmpty() &&
                surface.startsWith(lastInferredSurface) &&
                surface.length > lastInferredSurface.length
            ) {
                val diff = surface.substring(lastInferredSurface.length)
                lastInferredResult + diff
            } else {
                surface
            }
        } else {
            composingText.convertTarget
        }

        currentInputConnection?.setComposingText(tentativeDisplay, 1)

        if (surface.isNotEmpty()) {
            triggerDebouncedConversion(surface)
        } else {
            clearCandidateList()
        }
    }

    fun handleDakutenToggle() {
        vibrateOnKeyPress()
        if (isConfirming) return
        if (composingText.isEmpty) return

        val cursor = composingText.convertTargetCursorPosition
        if (cursor <= 0) return

        val surface = composingText.convertTargetBeforeCursor
        if (surface.isEmpty()) return
        val lastChar = surface.last()
        val toggled = KanaModifier.toggle(lastChar)
        if (toggled == lastChar) return

        canRewindOnNextBackspace = false
        ZenzNative.abort()
        inferenceJob?.cancel()

        composingText.deleteBackwardFromCursorPosition(1)
        composingText.insertAtCursorPosition(toggled.toString(), InputStyle.DIRECT)

        val newSurface = composingText.convertTargetBeforeCursor
        currentComposingSurface.value = newSurface

        val isRealtime = isRealtimeInlineEnabled()
        val tentativeDisplay = if (isRealtime) {
            if (lastInferredResult.isNotEmpty() &&
                newSurface.startsWith(lastInferredSurface) &&
                newSurface.length > lastInferredSurface.length
            ) {
                val diff = newSurface.substring(lastInferredSurface.length)
                lastInferredResult + diff
            } else {
                newSurface
            }
        } else {
            composingText.convertTarget
        }

        currentInputConnection?.setComposingText(tentativeDisplay, 1)

        if (newSurface.isNotEmpty()) {
            triggerDebouncedConversion(newSurface)
        } else {
            clearCandidateList()
        }
    }

    fun replaceLastKana(nextKana: String) {
        vibrateOnKeyPress()
        if (isConfirming) return
        if (composingText.isEmpty) return

        val cursor = composingText.convertTargetCursorPosition
        if (cursor <= 0) return

        canRewindOnNextBackspace = false
        ZenzNative.abort()
        inferenceJob?.cancel()

        composingText.deleteBackwardFromCursorPosition(1)
        composingText.insertAtCursorPosition(nextKana, InputStyle.DIRECT)

        val newSurface = composingText.convertTargetBeforeCursor
        currentComposingSurface.value = newSurface

        val isRealtime = isRealtimeInlineEnabled()
        val tentativeDisplay = if (isRealtime) {
            if (lastInferredResult.isNotEmpty() &&
                newSurface.startsWith(lastInferredSurface) &&
                newSurface.length > lastInferredSurface.length
            ) {
                val diff = newSurface.substring(lastInferredSurface.length)
                lastInferredResult + diff
            } else {
                newSurface
            }
        } else {
            composingText.convertTarget
        }

        currentInputConnection?.setComposingText(tentativeDisplay, 1)

        if (newSurface.isNotEmpty()) {
            triggerDebouncedConversion(newSurface)
        } else {
            clearCandidateList()
        }
    }

    fun isLastCharLetter(): Boolean {
        val ic = currentInputConnection ?: return false
        val last = ic.getTextBeforeCursor(1, 0)?.toString() ?: return false
        return last.isNotEmpty() && last.first().isLetter()
    }

    fun toggleLastCharCase() {
        if (isConfirming) return
        val ic = currentInputConnection ?: return
        val last = ic.getTextBeforeCursor(1, 0)?.toString() ?: return
        if (last.isNotEmpty()) {
            val ch = last.first()
            if (ch.isLetter()) {
                val flipped = if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(flipped.toString(), 1)
            }
        }
    }

    fun replaceLastCommittedText(newText: String, oldLength: Int = 1) {
        if (isConfirming) return
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(oldLength, 0)
        ic.commitText(newText, 1)
    }

    fun showInputMethodPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun triggerDebouncedConversion(surface: String) {
        inferenceJob = serviceScope.launch(Dispatchers.IO) {
            try {
                // Debounce pause 50ms
                delay(50)
                if (!isActive) return@launch

                KanaKanjiConverter.ensureInitialized(applicationContext)
                val snapshot = composingText.clone()
                val results = converter.requestCandidates(
                    composingText = snapshot,
                    leftContext = cachedLeftContext,
                    enableAI = true,
                    profile = aiProfileState.value
                )
                if (!isActive) return@launch

                withContext(Dispatchers.Main) {
                    if (!composingText.isEmpty) {
                        val aiCand = results.firstOrNull { it.isAI }?.text ?: results.firstOrNull()?.text ?: ""

                        // Record inference step in history for sliding-chunk auto-commit
                        if (composingText.isAtEndIndex && surface.isNotEmpty() && aiCand.isNotEmpty() && !hasTrailingIncompleteRomaji(surface)) {
                            val lastSnap = inferenceHistory.lastOrNull()
                            if (lastSnap != null && lastSnap.readingHiragana == surface) {
                                inferenceHistory[inferenceHistory.lastIndex] = InferenceSnapshot(surface, aiCand)
                            } else {
                                inferenceHistory.add(InferenceSnapshot(surface, aiCand))
                            }
                        }

                        // Check Sliding-Chunk Auto-Commit if enabled
                        val currentSurfaceNow = composingText.convertTargetBeforeCursor
                        if (isAutoCommitEnabled() &&
                            composingText.isAtEndIndex &&
                            currentSurfaceNow == surface
                        ) {
                            val autoCommit = findStableAutoCommitPrefix(currentSurfaceNow, inferenceHistory)
                            if (autoCommit != null && aiCand.startsWith(autoCommit.surfacePrefix)) {
                                performAutoCommit(autoCommit, aiCand, results)
                                return@withContext
                            }
                        }

                        candidatesList.clear()
                        candidatesList.addAll(results)

                        val best = results.firstOrNull()?.text ?: ""
                        predictedCandidate.value = best
                        lastInferredSurface = surface
                        lastInferredResult = best

                        // Asynchronous AI Assist evaluation (💡 Typo / 🔮 Prediction)
                        if (isAiAssistEnabled()) {
                            val assist = com.zeroconvert.ime.assist.AiAssistManager.requestAiAssist(
                                rawInput = surface,
                                hiragana = surface,
                                firstSurface = best,
                                leftContext = cachedLeftContext,
                                predictiveModelType = getPredictiveModel()
                            )
                            aiAssistResultState.value = assist
                        } else {
                            aiAssistResultState.value = null
                        }

                        // Only reflect into editor if user enabled real-time inline conversion
                        if (isRealtimeInlineEnabled() && best.isNotEmpty()) {
                            val currentSurface = composingText.convertTargetBeforeCursor
                            if (currentSurface.startsWith(surface)) {
                                val diff = currentSurface.substring(surface.length)

                                val hasKanjiInBest = best.any { (it.code in 0x4E00..0x9FFF) || (it.code in 0x3400..0x4DBF) }
                                val hasKanjiInPrev = lastInferredResult.any { (it.code in 0x4E00..0x9FFF) || (it.code in 0x3400..0x4DBF) }
                                val isDowngradeToHiragana = (best == surface && hasKanjiInPrev && !hasKanjiInBest)

                                if (!isDowngradeToHiragana) {
                                    val display = best + diff
                                    currentInputConnection?.setComposingText(display, 1)
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error in triggerDebouncedConversion for '$surface'", t)
            }
        }
    }

    private fun performAutoCommit(
        autoCommit: AutoCommitCandidate,
        fullAiSurface: String,
        fullResults: List<Candidate>
    ) {
        val prefixSurface = autoCommit.surfacePrefix
        val readingLen = autoCommit.readingLength
        val committedReading = autoCommit.committedReading

        // 1. Save rewind state before modifying context
        lastAutoCommitRewind = AutoCommitRewindRecord(
            committedSurface = prefixSurface,
            committedReading = committedReading,
            previousLeftContext = cachedLeftContext
        )
        canRewindOnNextBackspace = true

        // 2. Commit P to currentInputConnection
        currentInputConnection?.commitText(prefixSurface, 1)

        // 3. Remove the first L hiragana characters from composingText
        composingText.prefixComplete(readingLen)
        val remainingReading = composingText.convertTargetBeforeCursor
        currentComposingSurface.value = remainingReading

        // 4. Append P to currentLeftContext (retaining up to the last 40 characters)
        cachedLeftContext = (cachedLeftContext + prefixSurface).takeLast(MAX_LEFT_CONTEXT_LEN)

        // 5. Reset inference history so subsequent auto-commit waits for 5 new stable inferences
        inferenceHistory.clear()

        // 6. Update remaining composing text & candidate bar smoothly
        val remainingAiSurface = fullAiSurface.removePrefix(prefixSurface)
        if (remainingReading.isNotEmpty()) {
            val remainingKatakana = KanaKanjiConverter.toKatakana(remainingReading)
            inferenceHistory.add(InferenceSnapshot(remainingReading, remainingAiSurface))

            lastInferredSurface = remainingReading
            lastInferredResult = remainingAiSurface
            predictedCandidate.value = remainingAiSurface

            candidatesList.clear()
            if (remainingAiSurface.isNotEmpty()) {
                candidatesList.add(Candidate(text = remainingAiSurface, reading = remainingKatakana, cost = 1000f, isAI = true))
            }
            if (candidatesList.none { it.text == remainingReading }) {
                candidatesList.add(Candidate(text = remainingReading, reading = remainingKatakana, cost = -100f, isAI = false))
            }

            val display = if (isRealtimeInlineEnabled() && remainingAiSurface.isNotEmpty()) {
                remainingAiSurface
            } else {
                composingText.convertTarget
            }
            currentInputConnection?.setComposingText(display, 1)

            // Trigger fresh conversion for the remaining tail with the updated left context
            triggerDebouncedConversion(remainingReading)
        } else {
            clearInput()
        }
    }

    fun handleDelete() {
        vibrateOnKeyPress()
        if (isConfirming) return
        ZenzNative.abort()
        inferenceJob?.cancel()

        // 1. If text is selected in the editor, delete the selection immediately
        val ic = currentInputConnection
        val selected = ic?.getSelectedText(0)
        if (!selected.isNullOrEmpty() || hasSelectionInEditor) {
            canRewindOnNextBackspace = false
            lastAutoCommitRewind = null
            clearInput()
            hasSelectionInEditor = false
            ic?.commitText("", 1)
            val stillSelected = ic?.getSelectedText(0)
            if (!stillSelected.isNullOrEmpty()) {
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
            }
            return
        }

        // Check optional Backspace rewind right after an auto-commit
        val rewindRecord = lastAutoCommitRewind
        if (isAutoCommitRewindEnabled() && canRewindOnNextBackspace && rewindRecord != null) {
            canRewindOnNextBackspace = false
            lastAutoCommitRewind = null

            // Delete committed surface P from editor
            currentInputConnection?.deleteSurroundingText(rewindRecord.committedSurface.length, 0)

            // Restore left context
            cachedLeftContext = rewindRecord.previousLeftContext

            // Prepend committed reading back to composingText
            val currentTail = composingText.convertTarget
            composingText.stopComposition()
            composingText.insertAtCursorPosition(rewindRecord.committedReading + currentTail, InputStyle.FROZEN)

            val restoredSurface = composingText.convertTargetBeforeCursor
            currentComposingSurface.value = restoredSurface
            lastInferredSurface = ""
            lastInferredResult = ""
            inferenceHistory.clear()

            currentInputConnection?.setComposingText(composingText.convertTarget, 1)
            if (restoredSurface.isNotEmpty()) {
                triggerDebouncedConversion(restoredSurface)
            }
            return
        }

        canRewindOnNextBackspace = false

        if (!composingText.isEmpty) {
            composingText.deleteBackwardFromCursorPosition(1)
            val surface = composingText.convertTargetBeforeCursor
            currentComposingSurface.value = surface
            // Remove snapshots longer than current surface
            inferenceHistory.removeAll { it.readingHiragana.length >= surface.length }

            if (surface.isNotEmpty()) {
                if (isRealtimeInlineEnabled() && lastInferredResult.isNotEmpty() && surface.startsWith(lastInferredSurface)) {
                    val diff = surface.substring(lastInferredSurface.length)
                    currentInputConnection?.setComposingText(lastInferredResult + diff, 1)
                } else {
                    lastInferredSurface = ""
                    lastInferredResult = ""
                    currentInputConnection?.setComposingText(composingText.convertTarget, 1)
                }
                triggerDebouncedConversion(surface)
            } else {
                currentInputConnection?.setComposingText("", 0)
                currentInputConnection?.finishComposingText()
                clearInput()
            }
        } else {
            val ok = currentInputConnection?.deleteSurroundingText(1, 0) ?: false
            if (!ok) {
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
            }
        }
    }

    fun handleCursorLeft() {
        vibrateOnKeyPress()
        canRewindOnNextBackspace = false
        if (!composingText.isEmpty) {
            val moved = composingText.moveCursorFromCursorPosition(-1)
            if (moved != 0) {
                updateComposingDisplayOnCursorMove()
            }
        } else {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        }
    }

    fun handleCursorRight() {
        vibrateOnKeyPress()
        canRewindOnNextBackspace = false
        if (!composingText.isEmpty) {
            val moved = composingText.moveCursorFromCursorPosition(1)
            if (moved != 0) {
                updateComposingDisplayOnCursorMove()
            }
        } else {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        }
    }

    private fun updateComposingDisplayOnCursorMove() {
        ZenzNative.abort()
        inferenceJob?.cancel()

        val surfaceBefore = composingText.convertTargetBeforeCursor
        val fullSurface = composingText.convertTarget
        currentComposingSurface.value = surfaceBefore

        lastInferredSurface = ""
        lastInferredResult = ""
        inferenceHistory.clear()

        // Preserve full surface text in composing region
        currentInputConnection?.setComposingText(fullSurface, 1)

        // Trigger conversion query ONLY for the range before cursor!
        if (surfaceBefore.isNotEmpty()) {
            triggerDebouncedConversion(surfaceBefore)
        } else {
            clearCandidateList()
        }
    }

    fun handleEnter() {
        if (isConfirming) return
        if (!composingText.isEmpty) {
            confirmAsync()
        } else {
            sendKeyChar('\n')
        }
    }

    fun confirmAsync(trailingText: String = "") {
        vibrateOnKeyPress()
        if (composingText.isEmpty) {
            if (trailingText.isNotEmpty()) {
                commitDirect(trailingText)
            }
            return
        }

        if (isConfirming) return

        val currentSurface = composingText.convertTargetBeforeCursor

        // If the AI has already finished inference for the exact current surface, commit immediately (0ms latency)
        if (currentSurface == lastInferredSurface && predictedCandidate.value.isNotEmpty()) {
            val textToCommit = predictedCandidate.value + trailingText
            currentInputConnection?.commitText(textToCommit, 1)
            clearInput()
            return
        }

        // Otherwise, wait for the AI conversion to complete before committing so trailing characters are never lost
        isConfirming = true
        val snapshot = composingText.clone()
        val leftContext = cachedLeftContext
        val profile = aiProfileState.value
        inferenceJob?.cancel()

        serviceScope.launch(Dispatchers.IO) {
            try {
                val results = converter.requestCandidates(snapshot, leftContext, enableAI = true, profile = profile)
                val bestCandidate = results.firstOrNull()?.text ?: currentSurface
                val textToCommit = bestCandidate + trailingText

                withContext(Dispatchers.Main) {
                    currentInputConnection?.commitText(textToCommit, 1)
                    clearInput()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val fallback = currentSurface + trailingText
                    currentInputConnection?.commitText(fallback, 1)
                    clearInput()
                }
            } finally {
                isConfirming = false
            }
        }
    }

    fun confirmCurrent(trailingText: String = "") {
        confirmAsync(trailingText)
    }

    fun commitDirect(text: String) {
        vibrateOnKeyPress()
        currentInputConnection?.commitText(text, 1)
        clearInput()
    }

    fun commitText(text: String, isExplicitSelection: Boolean = false) {
        vibrateOnKeyPress()
        if (isConfirming) return
        val surfaceBefore = composingText.convertTargetBeforeCursor
        val surfaceAfter = composingText.convertTarget.drop(composingText.convertTargetCursorPosition)

        if (isExplicitSelection && surfaceBefore.isNotEmpty()) {
            KanaKanjiConverter.recordSelection(applicationContext, surfaceBefore, text)
        }

        if (surfaceAfter.isNotEmpty()) {
            // Commit only the range before cursor with the selected candidate!
            currentInputConnection?.commitText(text, 1)
            cachedLeftContext = (cachedLeftContext + text).takeLast(MAX_LEFT_CONTEXT_LEN)

            // Complete prefix of length surfaceBefore.length so that the suffix is preserved
            composingText.prefixComplete(surfaceBefore.length)

            val remainingSurface = composingText.convertTarget
            currentComposingSurface.value = remainingSurface
            lastInferredSurface = ""
            lastInferredResult = ""
            inferenceHistory.clear()
            canRewindOnNextBackspace = false

            if (remainingSurface.isNotEmpty()) {
                currentInputConnection?.setComposingText(remainingSurface, 1)
                triggerDebouncedConversion(remainingSurface)
            } else {
                clearInput()
            }
        } else {
            currentInputConnection?.commitText(text, 1)
            clearInput()
        }
    }

    private fun clearCandidateList() {
        candidatesList.clear()
        predictedCandidate.value = ""
        lastInferredSurface = ""
        lastInferredResult = ""
        inferenceHistory.clear()
        aiAssistResultState.value = null
    }

    fun clearInput() {
        ZenzNative.abort()
        inferenceJob?.cancel()
        composingText.stopComposition()
        currentComposingSurface.value = ""
        predictedCandidate.value = ""
        lastInferredSurface = ""
        lastInferredResult = ""
        candidatesList.clear()
        cachedLeftContext = ""
        inferenceHistory.clear()
        lastAutoCommitRewind = null
        canRewindOnNextBackspace = false
        aiAssistResultState.value = null
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        reloadPreferences()
        clearInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        clearInput()
    }

    override fun onDestroy() {
        super.onDestroy()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        clipListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        ZenzNative.close()
    }
}
