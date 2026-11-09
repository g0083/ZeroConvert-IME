package com.zeroconvert.ime

import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
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
import com.zeroconvert.ime.assist.AssistRequestGate
import com.zeroconvert.ime.assist.CorrectionSession
import com.zeroconvert.ime.assist.hasPendingRomaji
import com.zeroconvert.ime.translation.TranslationEngine
import com.zeroconvert.ime.translation.TranslationModelManager
import com.zeroconvert.ime.translation.TranslationSession
import com.zeroconvert.ime.translation.TranslationText
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withLock
import java.io.File

enum class TranslationStatus { IDLE, LOADING, TRANSLATING, READY, ERROR }

data class TranslationUiState(
    val active: Boolean = false,
    val status: TranslationStatus = TranslationStatus.IDLE,
    val request: String = "",
    val source: String = "",
    val output: String = "",
    val target: String = TranslationText.EN,
    val message: String? = null
)

data class SelectionTranslationState(
    val status: TranslationStatus = TranslationStatus.IDLE,
    val source: String = "",
    val output: String = "",
    val target: String = TranslationText.EN,
    val message: String? = null
)

data class InferenceSnapshot(
    val readingHiragana: String,
    val aiSurface: String,
    val rawInputCount: Int = 0
)

data class AutoCommitCandidate(
    val readingLength: Int,
    val committedReading: String,
    val surfacePrefix: String
)

data class AutoCommitRewindRecord(
    val committedSurface: String,
    val committedReading: String,
    val previousLeftContext: String,
    val originalInput: ComposingText? = null
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
        // "system" or a ZeroConThemes id ("dark_blue", "graphite", "forest", "plum", "sakura", "paper").
        // Legacy "dark"/"light" values are still resolved by resolveZeroConThemeId.
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_JAPANESE_LAYOUT = "japanese_keyboard_layout" // "FLICK_12KEY", "QWERTY"
        const val KEY_KEY_HEIGHT_DP = "keyboard_key_height_dp" // int dp (40..64), default 46
        const val KEY_FLICK_ONLY = "flick_only" // boolean, default true
        const val KEY_QWERTY_NUMBER_ROW = "qwerty_number_row" // boolean, default false
        const val KEY_FOLLOW_INPUT_TYPE_MODE = "follow_input_type_mode" // boolean, default false
        const val KEY_VIBRATION_ENABLED = "vibration_enabled" // boolean, default true
        const val KEY_VIBRATION_DURATION = "vibration_duration" // int ms (5..50), default 15
        const val KEY_EXPERIMENTAL_AI_ASSIST = "experimental_ai_assist" // boolean, default true
        const val KEY_AUTO_TYPO_CORRECTION = "auto_typo_correction" // boolean, default true
        const val KEY_DICTIONARY_TYPO_CORRECTION = "experimental_typo_correction" // Windows default: false
        const val KEY_PREDICTIVE_CONVERSION = "predictive_conversion" // boolean, Android default: true, Windows default: false
        const val KEY_ENGLISH_SPACE_ADOPTS_CANDIDATE = "english_space_adopts_candidate" // boolean, default false: insert space without adopting top candidate
        const val KEY_PREDICTIVE_MODEL = "experimental_predictive_model" // retired; retained for compatibility
        const val KEY_TRANSLATION_ENABLED = "translation_enabled" // boolean, default false
        const val KEY_TRANSLATION_TARGET = "translation_target" // "auto", "en", "ja"
        const val KEY_TRANSLATION_KEEP_LOADED = "translation_keep_loaded" // boolean, default false
        const val KEY_CLIPBOARD_PINNED = "clipboard_pinned"
        const val KEY_CLIPBOARD_RETENTION_HOURS = "clipboard_retention_hours"
        const val KEY_CLIPBOARD_SUGGESTION_MINUTES = "clipboard_suggestion_minutes"
        const val KEY_SCREENSHOT_CAPTURE = "screenshot_capture_enabled"
        const val KEY_EMOJI_RECENT = "emoji_recent" // newline separated, most recent first
        private const val EMOJI_CATALOG_ASSET = "emoji_catalog.tsv"

        private const val MIN_READING_LEN_FOR_AUTO_COMMIT = 16
        private const val MIN_TAIL_UNCOMMITTED_READING_LEN = 7
        private const val STABLE_INFERENCE_COUNT = 5
        private const val MAX_LEFT_CONTEXT_LEN = EditorContext.MAX_CODE_POINTS

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
            return readingHiragana.hasPendingRomaji()
        }

        fun isJapaneseClausePunctuation(ch: Char): Boolean {
            return ch in "、。，．！？!?,\n"
        }

        /**
         * Chars that commit the current composition immediately (punctuation and
         * shifted symbols). Digits are excluded: like the Windows client's
         * UserAction::Number -> AppendText, they join the composition so the
         * reading keeps them and conversion includes them.
         */
        fun isImmediateCommitChar(ch: Char): Boolean {
            return ch == '、' || ch == '。' || ch == '.' || ch == ',' || ch == '?' || ch == '!' ||
                ch == '？' || ch == '！' || ch == '．' || ch == '，' || ch in "!@#$%^&*()"
        }

        /** Translation mode also reflects digits into the reading as literals. */
        fun isTranslationLiteralChar(ch: Char): Boolean {
            return isImmediateCommitChar(ch) || ch.isDigit()
        }

        /** Same Japanese set as the Windows client's translation.rs has_japanese. */
        fun hasJapaneseKana(source: String): Boolean {
            return source.any { ch -> ch in 'ぁ'..'ヿ' || ch in '一'..'鿿' }
        }

        /**
         * Whether a translation-mode reading may start a translation request.
         * Digits and symbols alone never translate, matching the Windows
         * client where kind=3 requires kana in the reading; English sources
         * take the englishComposing path and are always eligible.
         */
        fun translationSourceEligible(sourceIsEnglish: Boolean, source: String): Boolean {
            return sourceIsEnglish || hasJapaneseKana(source)
        }

        fun isNaturalAutoCommitPrefix(candidatePrefix: String, fullPreview: String): Boolean {
            if (!fullPreview.startsWith(candidatePrefix)) return false
            val remainder = fullPreview.substring(candidatePrefix.length)
            if (remainder.isEmpty() || candidatePrefix.length < 4) return false
            val lastChar = candidatePrefix.last()
            val nextChar = remainder.first()
            if (isJapaneseClausePunctuation(lastChar)) return true

            // Must contain at least one Kanji or Katakana, end in Hiragana (okurigana/particle),
            // and the next clause must start with Kanji, Katakana, or punctuation.
            // This strictly prevents cutting inside inflected verbs (e.g. 変えられる -> 変 + えられる).
            val hasKanjiOrKatakana = candidatePrefix.any { isKanjiOrKatakana(it) }
            val endsWithHiragana = isHiraganaChar(lastChar)
            val nextIsContentWord = isKanjiOrKatakana(nextChar)
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
            history: List<InferenceSnapshot>,
            safeRawBoundary: (Int) -> Boolean = { true }
        ): AutoCommitCandidate? {
            if (currentReading.length < MIN_READING_LEN_FOR_AUTO_COMMIT) return null
            if (hasTrailingIncompleteRomaji(currentReading)) return null
            if (history.size < STABLE_INFERENCE_COUNT) return null

            val currentAiSurface = history.last().aiSurface
            val maxCommitReadingLen = currentReading.length - MIN_TAIL_UNCOMMITTED_READING_LEN
            if (maxCommitReadingLen < 8) return null

            val distinctPreviews = mutableListOf<String>()
            for (entry in history) if (distinctPreviews.lastOrNull() != entry.aiSurface) distinctPreviews.add(entry.aiSurface)
            if (distinctPreviews.size < STABLE_INFERENCE_COUNT) return null
            val recent5 = distinctPreviews.takeLast(STABLE_INFERENCE_COUNT)
            var bestCandidate: AutoCommitCandidate? = null

            for (snap in history) {
                val l = snap.readingHiragana.length
                val p = snap.aiSurface
                if (l < 8 || l > maxCommitReadingLen) continue
                if (!safeRawBoundary(l)) continue
                if (!currentReading.startsWith(snap.readingHiragana)) continue
                if (!isNaturalAutoCommitPrefix(p, currentAiSurface)) continue

                // Check that across the last 5 consecutive AI inferences, the surface prefix P
                // remained strictly stable.
                val isStableAcrossRecent5 = recent5.all { recent -> recent.startsWith(p) && recent.length > p.length }

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

        fun recordInferenceSnapshot(history: MutableList<InferenceSnapshot>, snapshot: InferenceSnapshot) {
            val index = history.indexOfFirst { it.readingHiragana.length == snapshot.readingHiragana.length ||
                (snapshot.rawInputCount > 0 && it.rawInputCount == snapshot.rawInputCount) }
            if (index >= 0) history[index] = snapshot else history.add(snapshot)
            while (history.size > 128) history.removeAt(0)
        }
    }

    val isRealtimeInlineState = mutableStateOf(false)
    val isAutoCommitEnabledState = mutableStateOf(true)
    val isAutoCommitRewindState = mutableStateOf(false)
    val aiProfileState = mutableStateOf("")
    val themeModeState = mutableStateOf("system")
    val japaneseLayoutState = mutableStateOf(JapaneseInputLayout.FLICK_12KEY)
    val keyboardKeyHeightState = mutableStateOf(46)
    val isFlickOnlyState = mutableStateOf(true)
    val isQwertyNumberRowState = mutableStateOf(true)
    val isFollowInputTypeModeState = mutableStateOf(false)
    // Owned by the service so onStartInputView can seed the mode for the focused editor.
    val keyboardModeState = mutableStateOf(KeyboardMode.JAPANESE)
    // Bumped for every input view session so open panels do not hide the keys in a new one.
    val inputViewEpochState = mutableIntStateOf(0)
    val isVibrationEnabledState = mutableStateOf(true)
    val vibrationDurationState = mutableStateOf(15)
    val isAiAssistEnabledState = mutableStateOf(true)
    val isAutoTypoCorrectionState = mutableStateOf(true)
    val isDictionaryTypoCorrectionState = mutableStateOf(false)
    val isPredictiveConversionState = mutableStateOf(true)
    val isEnglishSpaceAdoptsCandidateState = mutableStateOf(false)
    val predictiveModelState = mutableStateOf("zerocon")
    val aiAssistResultState = mutableStateOf<com.zeroconvert.ime.assist.AiAssistResult?>(null)
    val aiAssistAdoptedState = mutableStateOf(false)
    var savedOriginalSurface: String = ""
    var savedOriginalReading: String = ""
    private val corrections = CorrectionSession()
    private val conversionRange = ConversionRangeSession()
    val conversionRangeState = mutableStateOf("")
    private var savedOriginalCandidates: List<Candidate> = emptyList()
    private var savedOriginalResult = ""
    private var savedOriginalInferredReading = ""

    val translationEnabledState = mutableStateOf(false)
    val translationModelReadyState = mutableStateOf(false)
    val translationKeepLoadedState = mutableStateOf(false)
    val translationTargetState = mutableStateOf(TranslationText.AUTO)
    val translationState = mutableStateOf(TranslationUiState())
    val selectionTranslationState = mutableStateOf<SelectionTranslationState?>(null)
    private val translationSession = TranslationSession()
    private val selectionTranslationSession = TranslationSession()
    private var translationJob: Job? = null
    private var selectionTranslationJob: Job? = null
    private var translationLoadJob: Job? = null
    private var committingTranslation = false

    fun isRealtimeInlineEnabled(): Boolean = isRealtimeInlineState.value
    fun isAiAssistEnabled(): Boolean = isAiAssistEnabledState.value
    fun isPredictiveConversionEnabled(): Boolean = isPredictiveConversionState.value
    fun getPredictiveModel(): String = predictiveModelState.value
    fun isTranslationMode(): Boolean = translationState.value.active

    fun toggleTranslationMode() {
        if (isTranslationMode()) exitTranslationMode(revert = true) else enterTranslationMode()
    }

    fun enterTranslationMode() {
        if (isConfirming || isTranslationMode() || reconverting || conversionRange.active) {
            if (reconverting || conversionRange.active) {
                translationToast("変換操作を終了してから翻訳を開始してください")
            }
            return
        }
        if (!translationEnabledState.value) {
            translationToast("設定画面で翻訳機能をONにしてください")
            return
        }
        if (isSensitiveInput()) {
            translationToast("この入力欄では翻訳できません")
            return
        }
        val file = TranslationModelManager.modelFile(applicationContext)
        if (TranslationModelManager.isReady(applicationContext)) {
            translationModelReadyState.value = true
            beginTranslationMode(file)
            return
        }
        if (file.isFile && file.length() == TranslationModelManager.EXPECTED_BYTES) {
            translationState.value = TranslationUiState(
                active = true,
                status = TranslationStatus.LOADING,
                message = "モデルを確認中…"
            )
            translationLoadJob = serviceScope.launch(Dispatchers.IO) {
                val verified = TranslationModelManager.verifyExisting(applicationContext)
                withContext(Dispatchers.Main) {
                    if (!isTranslationMode()) return@withContext
                    if (verified) {
                        translationModelReadyState.value = true
                        beginTranslationMode(file)
                    } else {
                        translationState.value = TranslationUiState()
                        translationToast("翻訳モデルを確認できません。設定画面からダウンロードしてください")
                    }
                }
            }
            return
        }
        translationToast("翻訳モデルがありません。設定画面からダウンロードしてください")
    }

    private fun beginTranslationMode(file: File) {
        translationState.value = TranslationUiState(
            active = true,
            status = TranslationStatus.LOADING,
            target = configuredTranslationTarget()
        )
        selectionTranslationState.value = null
        cancelPendingInference(edited = false)
        clearCandidateList()
        pendingConversionContinuation = null
        aiAssistAdoptedState.value = false
        lastAutoCommitRewind = null
        canRewindOnNextBackspace = false
        vibrateOnKeyPress()
        ensureTranslationLoaded(file) { loaded ->
            if (!loaded) {
                if (isTranslationMode()) {
                    translationState.value = translationState.value.copy(
                        status = TranslationStatus.ERROR,
                        message = "翻訳モデルを読み込めませんでした"
                    )
                }
                return@ensureTranslationLoaded
            }
            if (!isTranslationMode()) return@ensureTranslationLoaded
            if (translationState.value.status == TranslationStatus.LOADING) {
                translationState.value = translationState.value.copy(status = TranslationStatus.IDLE, message = null)
            }
            if (!composingText.isEmpty || englishComposing.isNotEmpty()) scheduleTranslation()
        }
    }

    fun exitTranslationMode(revert: Boolean) {
        if (!isTranslationMode()) return
        cancelPendingTranslation()
        translationLoadWaiters.clear()
        selectionTranslationSession.cancel()
        selectionTranslationJob?.cancel()
        selectionTranslationJob = null
        selectionTranslationState.value = null
        val source = if (englishComposing.isNotEmpty()) englishComposing else composingText.convertTarget
        translationState.value = TranslationUiState()
        if (revert && source.isNotEmpty()) {
            currentInputConnection?.let { setManagedComposingText(it, source, 1) }
        }
        maybeUnloadTranslation()
    }

    fun beforeKeyboardModeChange() {
        if (isTranslationMode()) {
            return
        }
        confirmCurrent()
    }

    fun handleSpace() {
        if (isTranslationMode()) {
            appendTranslationLiteral(" ")
            return
        }
        // A space ends the phrase: the next-word prediction must not survive it.
        if (composingText.isEmpty) commitDirect(" ") else confirmCurrent(showContinuations = false)
    }

    fun commitTranslationFromUi() {
        val state = translationState.value
        if (state.status != TranslationStatus.READY || state.output.isEmpty()) return
        val currentInput = if (englishComposing.isNotEmpty()) englishComposing else composingText.convertTarget
        if (state.request != currentInput) {
            scheduleTranslation()
            return
        }
        commitTranslation(state.output)
    }

    fun translateSelectedText() {
        if (!translationEnabledState.value) {
            translationToast("設定画面で翻訳機能をONにしてください")
            return
        }
        if (isSensitiveInput()) {
            translationToast("この入力欄では翻訳できません")
            return
        }
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        if (selected.isEmpty()) return
        if (!TranslationText.validSource(selected)) {
            translationToast("選択翻訳は512文字までです")
            return
        }
        val file = TranslationModelManager.modelFile(applicationContext)
        if (!TranslationModelManager.isReady(applicationContext)) {
            translationToast("翻訳モデルがありません。設定画面からダウンロードしてください")
            return
        }
        selectionTranslationState.value = SelectionTranslationState(
            status = TranslationStatus.LOADING,
            source = selected
        )
        ensureTranslationLoaded(file) { loaded ->
            if (!loaded) {
                selectionTranslationState.value = SelectionTranslationState(
                    status = TranslationStatus.ERROR,
                    source = selected,
                    message = "翻訳モデルを読み込めませんでした"
                )
                return@ensureTranslationLoaded
            }
            if (selectionTranslationState.value?.source != selected) return@ensureTranslationLoaded
            startSelectionTranslation(connection, selected)
        }
    }

    private fun startSelectionTranslation(
        connection: android.view.inputmethod.InputConnection,
        selected: String
    ) {
        selectionTranslationSession.cancel()
        val token = selectionTranslationSession.begin()
        selectionTranslationJob?.cancel()
        val target = TranslationText.targetFor(selected, configuredTranslationTarget())
        selectionTranslationState.value = SelectionTranslationState(
            status = TranslationStatus.TRANSLATING,
            source = selected,
            target = target
        )
        selectionTranslationJob = serviceScope.launch(Dispatchers.IO) {
            val completion = TranslationEngine.translate(
                TranslationText.prompt(selected, target),
                512,
                15_000
            )
            val output = if (completion.finished && TranslationText.validOutput(completion.text)) {
                completion.text.trim()
            } else {
                ""
            }
            withContext(Dispatchers.Main) {
                if (!selectionTranslationSession.isCurrent(token)) return@withContext
                if (connection !== currentInputConnection) return@withContext
                if (connection.getSelectedText(0)?.toString() != selected) return@withContext
                selectionTranslationState.value = if (output.isNotEmpty()) {
                    SelectionTranslationState(
                        status = TranslationStatus.READY,
                        source = selected,
                        output = output,
                        target = target
                    )
                } else {
                    SelectionTranslationState(
                        status = TranslationStatus.ERROR,
                        source = selected,
                        target = target,
                        message = translationErrorMessage(completion.stopReason)
                    )
                }
            }
        }
    }

    fun commitSelectionTranslation() {
        val state = selectionTranslationState.value ?: return
        if (state.status != TranslationStatus.READY || state.output.isEmpty()) return
        val connection = currentInputConnection ?: return
        if (connection.getSelectedText(0)?.toString() != state.source) {
            selectionTranslationState.value = state.copy(
                status = TranslationStatus.ERROR,
                message = "選択範囲が変わりました"
            )
            return
        }
        vibrateOnKeyPress()
        connection.commitText(state.output, 1)
        cachedLeftContext = EditorContext.bounded(
            connection.getTextBeforeCursor(MAX_LEFT_CONTEXT_LEN, 0)?.toString().orEmpty()
        )
        selectionTranslationState.value = null
        hasSelectionInEditor = false
        selectionAvailableState.value = false
        clearSelectionCharacterCount()
        updateSelectionCharacterCount(-1, -1)
    }

    fun copySelectionTranslation() {
        val output = selectionTranslationState.value?.output ?: return
        if (output.isEmpty()) return
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager.setPrimaryClip(android.content.ClipData.newPlainText("translation", output))
        translationToast("訳文をコピーしました")
    }

    fun dismissSelectionTranslation() {
        selectionTranslationSession.cancel()
        selectionTranslationJob?.cancel()
        selectionTranslationJob = null
        selectionTranslationState.value = null
    }

    private val translationLoadWaiters = mutableListOf<(Boolean) -> Unit>()

    private fun ensureTranslationLoaded(file: File, onReady: (Boolean) -> Unit) {
        if (TranslationEngine.isLoaded(file)) {
            onReady(true)
            return
        }
        translationLoadWaiters.add(onReady)
        if (translationLoadJob?.isActive == true) return
        translationState.value = translationState.value.copy(
            status = TranslationStatus.LOADING,
            message = "翻訳モデルを読み込み中…"
        )
        translationLoadJob = serviceScope.launch(Dispatchers.IO) {
            val loaded = TranslationEngine.load(file)
            withContext(Dispatchers.Main) {
                val waiters = translationLoadWaiters.toList()
                translationLoadWaiters.clear()
                if (loaded) {
                    translationModelReadyState.value = true
                    waiters.forEach { it(true) }
                } else {
                    if (isTranslationMode()) {
                        translationState.value = translationState.value.copy(
                            status = TranslationStatus.ERROR,
                            message = "翻訳モデルを読み込めませんでした"
                        )
                    }
                    waiters.forEach { it(false) }
                }
            }
        }
    }

    private fun configuredTranslationTarget(): String = when (translationTargetState.value) {
        TranslationText.EN, TranslationText.JA -> translationTargetState.value
        else -> TranslationText.AUTO
    }

    private fun scheduleTranslation() {
        if (!isTranslationMode() || isSensitiveInput()) return
        val englishSource = englishComposing
        val japaneseSource = composingText.convertTarget
        val sourceIsEnglish = englishSource.isNotEmpty()
        val rawSource = if (sourceIsEnglish) englishSource else japaneseSource
        if (rawSource.isBlank() || (!sourceIsEnglish && !composingText.isAtEndIndex)) {
            cancelPendingTranslation()
            translationState.value = translationState.value.copy(
                request = rawSource,
                source = rawSource,
                output = "",
                status = TranslationStatus.IDLE,
                message = null
            )
            return
        }
        // Digits/symbols alone are not translation targets: keep the bar idle
        // instead of rewriting "123" into "百二十三" like Windows.
        if (!translationSourceEligible(sourceIsEnglish, japaneseSource)) {
            cancelPendingTranslation()
            translationState.value = translationState.value.copy(
                request = "",
                source = "",
                output = "",
                status = TranslationStatus.IDLE,
                message = null
            )
            return
        }
        if (!sourceIsEnglish && hasTrailingIncompleteRomaji(japaneseSource)) {
            cancelPendingTranslation()
            translationState.value = translationState.value.copy(
                request = rawSource,
                source = rawSource,
                output = "",
                status = TranslationStatus.TRANSLATING,
                message = null
            )
            return
        }

        translationJob?.cancel()
        ZenzNative.abortTranslation()
        val token = translationSession.begin()
        translationState.value = translationState.value.copy(
            request = rawSource,
            source = rawSource,
            output = "",
            status = TranslationStatus.TRANSLATING,
            message = null
        )
        val connection = currentInputConnection ?: return
        val context = cachedLeftContext
        val profile = aiProfileState.value
        val snapshot = composingText.clone()
        val configuredTarget = configuredTranslationTarget()
        translationJob = serviceScope.launch(Dispatchers.IO) {
            try {
                delay(400)
                if (!translationSession.isCurrent(token)) return@launch
                val source = if (sourceIsEnglish) {
                    englishSource
                } else {
                    KanaKanjiConverter.ensureInitialized(applicationContext)
                    val results = converter.requestCandidates(
                        snapshot,
                        context,
                        enableAI = true,
                        profile = profile,
                        personalize = true,
                        predictiveConversion = false
                    )
                    (results.firstOrNull { it.assist == null } ?: results.firstOrNull())
                        ?.previewForReading(japaneseSource)
                        .orEmpty()
                        .ifEmpty { japaneseSource }
                }
                if (!TranslationText.validSource(source)) {
                    withContext(Dispatchers.Main) {
                        if (translationSession.isCurrent(token) && isTranslationMode()) {
                            translationState.value = translationState.value.copy(
                                status = TranslationStatus.ERROR,
                                message = "翻訳できる文字数は512文字までです"
                            )
                        }
                    }
                    return@launch
                }
                val target = TranslationText.targetFor(source, configuredTarget)
                val completion = TranslationEngine.translate(
                    TranslationText.prompt(source, target),
                    512,
                    15_000
                )
                if (!translationSession.isCurrent(token)) return@launch
                val output = if (completion.finished && TranslationText.validOutput(completion.text)) {
                    completion.text.trim()
                } else {
                    ""
                }
                withContext(Dispatchers.Main) {
                    if (!translationSession.isCurrent(token) || !isTranslationMode()) return@withContext
                    if (connection !== currentInputConnection) return@withContext
                    val currentSource = if (sourceIsEnglish) englishComposing else composingText.convertTarget
                    if (currentSource != rawSource) return@withContext
                    if (!connection.getSelectedText(0).isNullOrEmpty()) return@withContext
                    if (output.isEmpty()) {
                        translationState.value = translationState.value.copy(
                            source = source,
                            status = TranslationStatus.ERROR,
                            message = translationErrorMessage(completion.stopReason)
                        )
                    } else {
                        if (translationState.value.output != output) {
                            setManagedComposingText(connection, output, 1)
                        }
                        translationState.value = translationState.value.copy(
                            request = rawSource,
                            source = source,
                            output = output,
                            target = target,
                            status = TranslationStatus.READY,
                            message = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Translation failed", error)
                withContext(Dispatchers.Main) {
                    if (translationSession.isCurrent(token) && isTranslationMode()) {
                        translationState.value = translationState.value.copy(
                            status = TranslationStatus.ERROR,
                            message = "翻訳に失敗しました"
                        )
                    }
                }
            }
        }
    }

    private fun handleTranslationEnter() {
        val state = translationState.value
        val currentInput = if (englishComposing.isNotEmpty()) englishComposing else composingText.convertTarget
        val readyForCurrentInput = state.status == TranslationStatus.READY &&
            state.output.isNotEmpty() && state.request == currentInput
        when {
            readyForCurrentInput -> {
                commitTranslation(state.output)
            }
            state.status == TranslationStatus.READY && state.request != currentInput -> {
                scheduleTranslation()
                vibrateOnKeyPress()
                translationToast("翻訳を更新しています。もう一度Enterを押してください")
            }
            state.status == TranslationStatus.TRANSLATING ||
                state.status == TranslationStatus.LOADING -> {
                vibrateOnKeyPress()
                translationToast("翻訳を待っています。翻訳ボタンで取消できます")
            }
            composingText.isEmpty && englishComposing.isEmpty() -> {
                exitTranslationMode(revert = false)
                sendKeyChar('\n')
            }
            else -> {
                vibrateOnKeyPress()
                translationToast(state.message ?: "翻訳を確定できません。入力をやり直してください")
            }
        }
    }

    private fun commitTranslation(text: String) {
        val connection = currentInputConnection ?: return
        vibrateOnKeyPress()
        cancelPendingTranslation()
        translationLoadWaiters.clear()
        committingTranslation = true
        try {
            connection.beginBatchEdit()
            try {
                if (!connection.commitText(text, 1)) return
                composingEditor.finishComposition()
                cachedLeftContext = EditorContext.bounded(cachedLeftContext + text)
            } finally {
                connection.endBatchEdit()
            }
        } finally {
            committingTranslation = false
        }
        composingText.stopComposition()
        englishComposing = ""
        currentComposingSurface.value = ""
        candidatesList.clear()
        predictedCandidate.value = ""
        lastInferredSurface = ""
        lastInferredResult = ""
        inferenceHistory.clear()
        lastAutoCommitRewind = null
        canRewindOnNextBackspace = false
        previousLearningSegment = null
        corrections.clear()
        reconverting = false
        savedOriginalCandidates = emptyList()
        savedOriginalSurface = ""
        savedOriginalReading = ""
        savedOriginalResult = ""
        savedOriginalInferredReading = ""
        aiAssistAdoptedState.value = false
        aiAssistResultState.value = null
        conversionRange.clear()
        conversionRangeState.value = ""
        composingEditor.clear()
        hasSelectionInEditor = false
        selectionAvailableState.value = false
        clearSelectionCharacterCount()
        selectionTranslationState.value = null
        translationState.value = TranslationUiState()
        maybeUnloadTranslation()
    }

    private fun appendTranslationLiteral(text: String) {
        if (isConfirming || text.isEmpty()) return
        cancelPendingTranslation()
        val connection = currentInputConnection ?: return
        if (englishComposing.isNotEmpty()) {
            englishComposing += text
            currentComposingSurface.value = englishComposing
            setManagedComposingText(connection, englishComposing, 1)
        } else {
            composingText.insertAtCursorPosition(text, InputStyle.DIRECT)
            currentComposingSurface.value = composingText.convertTargetBeforeCursor
            setManagedComposingText(connection, composingText.convertTarget, 1)
        }
        scheduleTranslation()
    }

    private fun cancelPendingTranslation() {
        translationSession.cancel()
        translationJob?.cancel()
        translationJob = null
        ZenzNative.abortTranslation()
    }

    private fun maybeUnloadTranslation() {
        if (translationKeepLoadedState.value || isTranslationMode()) return
        val file = TranslationModelManager.modelFile(applicationContext)
        if (!TranslationEngine.isLoaded(file)) return
        translationLoadJob?.cancel()
        translationLoadJob = null
        ZenzNative.abortTranslation()
        serviceScope.launch(Dispatchers.IO) { TranslationEngine.close() }
    }

    private fun translationErrorMessage(reason: String): String = when (reason) {
        "timeout" -> "翻訳がタイムアウトしました。もう一度お試しください"
        "limit" -> "訳文が長すぎて完了できませんでした"
        "unavailable" -> "翻訳モデルが読み込まれていません"
        "busy" -> "翻訳処理中です"
        "cancelled" -> "翻訳を取り消しました"
        else -> "翻訳に失敗しました"
    }

    private fun translationToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    fun adoptAiAssist(explicit: Boolean = true) {
        if (isTranslationMode()) return
        val assist = aiAssistResultState.value ?: return
        val ic = currentInputConnection ?: return
        if (!isAiAssistEnabled() || isSensitiveInput() || isConfirming) return
        val ticket = displayedAssistTicket ?: return
        if (assistInputConnection !== ic || !requestGate.isCurrent(ticket, requestKey()) || !editorContextIsCurrent()) {
            cancelPendingInference(edited = false)
            return
        }
        if (!explicit && (!assist.autoApply || !isAutoTypoCorrectionState.value || !composingText.isAtEndIndex ||
                hasSelectionInEditor || corrections.isApplied || assist.sourceReading != composingText.convertTargetBeforeCursor)) return
        if (explicit) vibrateOnKeyPress()
        when (assist.type) {
            com.zeroconvert.ime.assist.AiAssistType.TYPO_CORRECTION -> {
                val suffix = composingText.convertTarget.drop(composingText.convertTargetCursorPosition)
                val preview = if (isRealtimeInlineEnabled()) {
                    predictedCandidate.value.ifEmpty { composingText.convertTargetBeforeCursor } + suffix
                } else composingText.convertTarget
                savedOriginalSurface = preview
                savedOriginalReading = composingText.convertTargetBeforeCursor
                savedOriginalCandidates = candidatesList.filter { it.assist == null }
                savedOriginalResult = lastInferredResult
                savedOriginalInferredReading = lastInferredSurface
                cancelPendingInference(edited = false)
                if (!corrections.apply(composingText, assist.text, preview, explicit = explicit)) return
                if (!setComposingPreview(ic, assist.text + suffix)) {
                    corrections.revert(composingText)
                    return
                }
                currentComposingSurface.value = composingText.convertTargetBeforeCursor
                predictedCandidate.value = assist.text
                lastInferredSurface = composingText.convertTargetBeforeCursor
                lastInferredResult = assist.text
                candidatesList.clear()
                candidatesList.add(Candidate(assist.text, savedOriginalReading))
                inferenceHistory.clear()
                aiAssistAdoptedState.value = true
                pendingConversionContinuation = null
                triggerDebouncedConversion(composingText.convertTargetBeforeCursor)
            }
            com.zeroconvert.ime.assist.AiAssistType.PREDICTION -> {
                commitText(assist.text)
            }
        }
    }

    fun selectCandidate(candidate: Candidate) {
        if (candidate !in candidatesList) return
        if (conversionRange.active && candidate !in ConversionRangeSession.candidatesForRange(composingText, candidatesList)) return
        if (candidate.assist != null) {
            aiAssistResultState.value = candidate.assist
            adoptAiAssist()
        } else commitText(candidate.text, isExplicitSelection = true, committedCandidate = candidate)
    }

    fun revertAiAssist() {
        if (!corrections.isApplied) return
        val ic = currentInputConnection ?: return
        vibrateOnKeyPress()
        cancelPendingInference(edited = false)
        // Only restore the internal snapshot after the editor accepted the undo.
        if (!setComposingPreview(ic, savedOriginalSurface)) return
        corrections.revert(composingText)
        currentComposingSurface.value = composingText.convertTargetBeforeCursor
        predictedCandidate.value = (savedOriginalCandidates.firstOrNull { !it.isLearningPrediction }
            ?: savedOriginalCandidates.firstOrNull())?.text.orEmpty()
        lastInferredSurface = savedOriginalInferredReading
        lastInferredResult = savedOriginalResult
        candidatesList.clear()
        candidatesList.addAll(savedOriginalCandidates)
        inferenceHistory.clear()
        aiAssistAdoptedState.value = false
        aiAssistResultState.value = null
        pendingConversionContinuation = null
    }

    private fun extractAndAddEntities(text: String) {
        val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        val urlRegex = Regex("https?://[^\\s]+")
        val phoneRegex = Regex("(?:(?:\\+?81[- ]?)?0\\d{1,4}[- ]?\\d{1,4}[- ]?\\d{3,4})")
        val found = mutableSetOf<String>()
        emailRegex.findAll(text).forEach { found.add(it.value) }
        urlRegex.findAll(text).forEach { found.add(it.value) }
        phoneRegex.findAll(text).forEach { found.add(it.value) }
        found.forEach { entity ->
            val type = when {
                emailRegex.matches(entity) -> "email"
                urlRegex.matches(entity) -> "url"
                phoneRegex.matches(entity) -> "phone"
                else -> "text"
            }
            addClipboardEntry(ClipboardEntry(text = entity, entityType = type, timestamp = System.currentTimeMillis()))
        }
    }

    fun addClipboardEntry(entry: ClipboardEntry) {
        val exists = clipboardHistory.any { it.text == entry.text && it.imageUri == entry.imageUri }
        if (exists) return
        clipboardHistory.add(0, entry)
        trimClipboardHistory()
        // A new clipboard item makes the suggestion visible again
        isClipboardSuggestionDismissed.value = false
    }

    fun removeClipboardEntry(entry: ClipboardEntry) {
        val idx = clipboardHistory.indexOfFirst { it.id == entry.id }
        if (idx < 0) return
        val removed = clipboardHistory.removeAt(idx)
        if (removed.pinned) persistPinnedClipboard()
    }

    fun updateClipboardEntry(entry: ClipboardEntry, newText: String) {
        val idx = clipboardHistory.indexOfFirst { it.id == entry.id }
        if (idx < 0) return
        // Editing is not a new clipboard addition; retain its suggestion deadline.
        clipboardHistory[idx] = clipboardHistory[idx].copy(text = newText)
        if (clipboardHistory[idx].pinned) persistPinnedClipboard()
    }

    /**
     * The IME cannot host an InputConnection of its own, so text editing happens in a small
     * editor activity: our keyboard then types into that activity's field through the normal
     * pipeline instead of committing to the app behind the keyboard.
     */
    fun beginClipboardEdit(entry: ClipboardEntry) {
        val text = entry.text ?: return
        ClipboardEditBridge.onSave = { newText -> updateClipboardEntry(entry, newText) }
        val intent = Intent(this, ClipboardEditActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ClipboardEditActivity.EXTRA_TEXT, text)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            ClipboardEditBridge.onSave = null
            Log.e(TAG, "beginClipboardEdit failed", e)
        }
    }

    fun clearClipboardHistory(keepPinned: Boolean = true) {
        try {
            val remaining = if (keepPinned) clipboardHistory.filter { it.pinned } else emptyList()
            clipboardHistory.clear()
            clipboardHistory.addAll(remaining)
            persistPinnedClipboard()
        } catch (e: Exception) {
            Log.e(TAG, "clearClipboardHistory failed", e)
        }
    }

    fun cleanupExpiredClipboard() {
        val retention = clipboardRetentionHoursState.value
        val expired = clipboardHistory.filter { it.isExpired(retention) }
        expired.forEach { clipboardHistory.remove(it) }
    }

    fun setClipboardRetentionHours(hours: Int) {
        clipboardRetentionHoursState.value = hours.coerceIn(1, 24)
        getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CLIPBOARD_RETENTION_HOURS, clipboardRetentionHoursState.value).apply()
        cleanupExpiredClipboard()
    }

    private var lastScreenshotCheck = System.currentTimeMillis()
    private val seenScreenshotIds = mutableSetOf<Long>()
    private var screenshotObserver: ContentObserver? = null
    private var screenshotCaptureJob: Job? = null
    private val maxClipboardImageCacheBytes = 64L * 1024L * 1024L

    fun setScreenshotCaptureEnabled(enabled: Boolean) {
        getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_SCREENSHOT_CAPTURE, enabled).apply()
        if (enabled) registerScreenshotObserver() else unregisterScreenshotObserver()
    }

    private fun registerScreenshotObserver() {
        if (screenshotObserver != null) return
        screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                scheduleScreenshotCapture()
            }
        }
        try {
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                screenshotObserver!!
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screenshot observer: ${e.message}")
        }
    }

    private fun unregisterScreenshotObserver() {
        screenshotObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
        }
        screenshotObserver = null
        screenshotCaptureJob?.cancel()
        screenshotCaptureJob = null
    }

    private fun scheduleScreenshotCapture() {
        screenshotCaptureJob?.cancel()
        screenshotCaptureJob = serviceScope.launch {
            // Screenshot providers often emit several MediaStore changes for one capture.
            delay(400)
            val entries = withContext(Dispatchers.IO) { collectRecentScreenshots() }
            entries.forEach(::addClipboardEntry)
            if (entries.isNotEmpty()) cleanupClipboardImageCache()
        }
    }

    private fun collectRecentScreenshots(): List<ClipboardEntry> {
        if (!getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).getBoolean(KEY_SCREENSHOT_CAPTURE, true)) {
            return emptyList()
        }
        val cutoff = lastScreenshotCheck
        lastScreenshotCheck = System.currentTimeMillis()
        val entries = mutableListOf<ClipboardEntry>()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf((cutoff / 1000 - 5).toString())
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    val relPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val isScreenshot = name.contains("Screenshot", ignoreCase = true) ||
                        name.contains("スクリーンショット", ignoreCase = true) ||
                        (relPath != null && relPath.contains("creenshot", ignoreCase = true))
                    if (!isScreenshot) continue
                    val id = cursor.getLong(idCol)
                    if (!seenScreenshotIds.add(id)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val cached = cacheClipboardImage(uri)
                    entries += ClipboardEntry(imageUri = cached ?: uri)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot capture failed: ${e.message}")
        }
        return entries
    }

    private fun trimClipboardHistory() {
        while (clipboardHistory.count { !it.pinned } > 20) {
            val idx = clipboardHistory.indexOfLast { !it.pinned }
            if (idx >= 0) clipboardHistory.removeAt(idx) else break
        }
        cleanupClipboardImageCache()
    }

    private fun cleanupClipboardImageCache() {
        val imageEntries = clipboardHistory.mapNotNull { entry ->
            entry.imageUri?.lastPathSegment?.substringAfterLast('/')?.let { fileName ->
                fileName to entry.pinned
            }
        }
        val activeFileNames = imageEntries.map { it.first }.toSet()
        val pinnedFileNames = imageEntries.filter { it.second }.map { it.first }.toSet()
        serviceScope.launch(Dispatchers.IO) {
            val directory = File(cacheDir, "clipboard_images")
            directory.listFiles()?.forEach { file ->
                if (file.name !in activeFileNames) file.delete()
            }
            var totalBytes = directory.listFiles()?.sumOf { it.length() } ?: 0L
            val removedFileNames = mutableSetOf<String>()
            directory.listFiles()
                ?.filter { it.name !in pinnedFileNames }
                ?.sortedBy { it.lastModified() }
                ?.forEach { file ->
                    val fileSize = file.length()
                    if (totalBytes > maxClipboardImageCacheBytes && file.delete()) {
                        totalBytes -= fileSize
                        removedFileNames += file.name
                    }
                }
            if (removedFileNames.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    clipboardHistory.removeAll { entry ->
                        entry.imageUri?.lastPathSegment?.substringAfterLast('/') in removedFileNames
                    }
                }
            }
        }
    }

    fun toggleClipboardPin(entry: ClipboardEntry) {
        val idx = clipboardHistory.indexOfFirst { it.id == entry.id }
        if (idx < 0) return
        val current = clipboardHistory[idx]
        val updated = current.copy(pinned = !current.pinned)
        clipboardHistory.removeAt(idx)
        clipboardHistory.add(0, updated)
        trimClipboardHistory()
        persistPinnedClipboard()
    }

    private fun persistPinnedClipboard() {
        val pinnedTexts = clipboardHistory.filter { it.pinned && it.text != null }.map { it.text!! }.toSet()
        getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).edit().putStringSet(KEY_CLIPBOARD_PINNED, pinnedTexts).apply()
    }

    /**
     * The palette catalog is ~1,900 rows, so it is parsed once off the main thread
     * and kept in a state object the keyboard reads.
     */
    private fun loadEmojiCatalog() {
        if (emojiCategoriesState.value.isNotEmpty()) return
        serviceScope.launch {
            val categories = withContext(Dispatchers.IO) {
                try {
                    assets.open(EMOJI_CATALOG_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                        EmojiCatalog.parse(lines)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load $EMOJI_CATALOG_ASSET", e)
                    emptyList()
                }
            }
            emojiCategoriesState.value = categories
        }
    }

    private fun loadRecentEmoji() {
        val stored = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getString(KEY_EMOJI_RECENT, null)
        recentEmojiState.value = stored
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(EmojiCatalog.RECENT_LIMIT)
            ?: emptyList()
    }

    /** Records a committed emoji so the palette can offer it under "最近". */
    fun recordRecentEmoji(emoji: String) {
        val updated = EmojiCatalog.pushRecent(recentEmojiState.value, emoji)
        if (updated == recentEmojiState.value) return
        recentEmojiState.value = updated
        getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EMOJI_RECENT, updated.joinToString("\n")).apply()
    }

    fun pasteClipboard(entry: ClipboardEntry, fromSuggestion: Boolean = false) {
        vibrateOnKeyPress()
        if (!composingText.isEmpty) {
            confirmCurrent()
        }
        val ic = currentInputConnection ?: return
        if (entry.text != null) {
            ic.commitText(entry.text, 1)
        } else if (entry.imageUri != null) {
            commitClipboardImage(ic, entry.imageUri)
        }
        if (fromSuggestion) {
            // The suggestion is shown only once; further pastes require opening the clipboard panel
            isClipboardSuggestionDismissed.value = true
        }
    }

    private fun commitClipboardImage(ic: android.view.inputmethod.InputConnection, uri: android.net.Uri) {
        try {
            val mime = contentResolver.getType(uri) ?: "image/*"
            val supportedMimeTypes = currentInputEditorInfo?.contentMimeTypes.orEmpty()
            if (supportedMimeTypes.none { android.content.ClipDescription.compareMimeTypes(mime, it) }) {
                showImagePasteUnsupportedMessage()
                return
            }

            val desc = android.content.ClipDescription("image", arrayOf(mime))
            val info = android.view.inputmethod.InputContentInfo(uri, desc, null)
            currentInputEditorInfo?.packageName?.let { packageName ->
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val accepted = ic.commitContent(
                info,
                android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
            if (!accepted) showImagePasteUnsupportedMessage()
        } catch (e: Exception) {
            Log.w(TAG, "Image paste failed", e)
            showImagePasteUnsupportedMessage()
        }
    }

    private fun showImagePasteUnsupportedMessage() {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "このアプリは画像貼り付けに対応していません", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Copies a clipboard image into the app cache so that it can be kept in the clipboard
     * history (Gboard-like behaviour) and re-used even after the source app revokes the URI.
     * Returns a content:// URI served by the app's FileProvider.
     */
    private fun cacheClipboardImage(uri: android.net.Uri): android.net.Uri? {
        return try {
            val dir = File(cacheDir, "clipboard_images")
            if (!dir.exists()) dir.mkdirs()
            val ext = when (contentResolver.getType(uri)) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                else -> "jpg"
            }
            val target = File(dir, "clip_${System.currentTimeMillis()}.$ext")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                target
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache clipboard image: ${e.message}")
            null
        }
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

    fun setQwertyNumberRow(enabled: Boolean) {
        isQwertyNumberRowState.value = enabled
        val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_QWERTY_NUMBER_ROW, enabled).apply()
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
        cancelPendingInference(edited = false)
        val prefs = sharedPreferences ?: getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        isRealtimeInlineState.value = prefs.getBoolean(KEY_REALTIME_INLINE, true)
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
        isQwertyNumberRowState.value = prefs.getBoolean(KEY_QWERTY_NUMBER_ROW, false)
        isFollowInputTypeModeState.value = prefs.getBoolean(KEY_FOLLOW_INPUT_TYPE_MODE, false)
        keyboardKeyHeightState.value = prefs.getInt(KEY_KEY_HEIGHT_DP, 46).coerceIn(40, 64)
        isVibrationEnabledState.value = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        vibrationDurationState.value = prefs.getInt(KEY_VIBRATION_DURATION, 15)
        isAiAssistEnabledState.value = prefs.getBoolean(KEY_EXPERIMENTAL_AI_ASSIST, true)
        isAutoTypoCorrectionState.value = prefs.getBoolean(KEY_AUTO_TYPO_CORRECTION, true)
        isDictionaryTypoCorrectionState.value = prefs.getBoolean(KEY_DICTIONARY_TYPO_CORRECTION, false)
        isPredictiveConversionState.value = prefs.getBoolean(KEY_PREDICTIVE_CONVERSION, true)
        isEnglishSpaceAdoptsCandidateState.value = prefs.getBoolean(KEY_ENGLISH_SPACE_ADOPTS_CANDIDATE, false)
        LearningPolicy.mode = LearningMode.from(prefs.getString(LearningPolicy.KEY, null))
        predictiveModelState.value = "zerocon"
        clipboardSuggestionMinutesState.value = ClipboardSuggestionPolicy.normalizeMinutes(
            prefs.getInt(KEY_CLIPBOARD_SUGGESTION_MINUTES, ClipboardSuggestionPolicy.DEFAULT_MINUTES))
        if (prefs.getBoolean(KEY_SCREENSHOT_CAPTURE, true)) {
            registerScreenshotObserver()
        } else {
            unregisterScreenshotObserver()
        }
        translationEnabledState.value = prefs.getBoolean(KEY_TRANSLATION_ENABLED, false)
        translationKeepLoadedState.value = prefs.getBoolean(KEY_TRANSLATION_KEEP_LOADED, false)
        translationTargetState.value = prefs.getString(KEY_TRANSLATION_TARGET, TranslationText.AUTO)
            ?: TranslationText.AUTO
        translationModelReadyState.value = TranslationModelManager.isReady(this)
        if (!translationEnabledState.value && isTranslationMode()) {
            exitTranslationMode(revert = true)
        } else if (!translationKeepLoadedState.value && !isTranslationMode()) {
            maybeUnloadTranslation()
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private data class PendingLearning(val reading: String, val surface: String, val english: Boolean,
                                      val selection: ConversionCommitLearning? = null,
                                      val previous: DictionarySegment? = null,
                                      val historyRevision: Long = KanaKanjiConverter.learningHistoryRevision)
    private var previousLearningSegment: DictionarySegment? = null
    private val learningQueue = Channel<PendingLearning>(128)
    private val learningWorker = serviceScope.launch(Dispatchers.IO) {
        for (selection in learningQueue) {
            if (!LearningPolicy.canWrite) continue
            try {
                if (selection.english) {
                    EnglishWordDictionary.recordSelection(applicationContext, selection.reading, selection.surface)
                } else {
                    selection.selection?.let { KanaKanjiConverter.recordCommittedSelection(applicationContext, it,
                        selection.previous, selection.historyRevision) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e(TAG, "Could not save committed selection", failure)
            }
        }
    }

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
    val selectionAvailableState = mutableStateOf(false)
    val selectedCharacterCountState = mutableStateOf<SelectionCharacterCount?>(null)
    private val selectionCountGate = SelectionCountGate()
    private var selectionCountJob: Job? = null
    private val selectionCountMutex = kotlinx.coroutines.sync.Mutex()
    private var reconverting = false
    val clipboardHistory = mutableStateListOf<ClipboardEntry>()
    val isClipboardSuggestionDismissed = mutableStateOf(false)
    val clipboardRetentionHoursState = mutableStateOf(1)
    val clipboardSuggestionMinutesState = mutableStateOf(ClipboardSuggestionPolicy.DEFAULT_MINUTES)
    val emojiCategoriesState = mutableStateOf<List<EmojiCategory>>(emptyList())
    val recentEmojiState = mutableStateOf<List<String>>(emptyList())

    // English (alphabet) composing buffer for predictive completion
    var englishComposing: String = ""

    private val converter = KanaKanjiConverter()
    private var clipboardManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var inferenceJob: Job? = null
    // AI assist (💡 typo / 🔮 prediction) runs off the main thread: it performs
    // dictionary lookups through JNI, which must never block key handling.
    private var aiAssistJob: Job? = null
    private var cachedLeftContext: String = ""
    // Mirrors Windows `pending_conversion_continuation`: the reading and surface of the
    // prefix committed by a live auto-commit or a partial clause commit. The next
    // debounced/confirm conversion consumes it so the engine can rebuild the full
    // reading instead of converting only the remaining tail.
    private var pendingConversionContinuation: ConversionContinuation? = null
    private val composingEditor = ComposingEditorState()
    private val editorComposingPreview: String get() = composingEditor.preview
    private val managedCursorKeys = mutableSetOf<Int>()
    private val requestGate = AssistRequestGate()
    private var displayedAssistTicket: AssistRequestGate.Ticket? = null
    private var assistInputConnection: android.view.inputmethod.InputConnection? = null

    private fun setManagedComposingText(connection: android.view.inputmethod.InputConnection?, text: CharSequence, cursor: Int): Boolean {
        if (connection == null || !connection.setComposingText(text, cursor)) return false
        composingEditor.recordPreview(text.toString())
        return true
    }

    private fun setComposingPreview(connection: android.view.inputmethod.InputConnection?, text: String): Boolean {
        if (connection == null) return false
        val suffix = composingText.convertTarget.drop(composingText.convertTargetCursorPosition)
        if (!text.endsWith(suffix)) return false
        val position = text.length - suffix.length
        if (position == text.length) return setManagedComposingText(connection, text, 1)
        return composingEditor.write(object : ComposingEditorState.Editor {
            override fun beginBatch() { connection.beginBatchEdit() }
            override fun composing(text: String) = connection.setComposingText(text, 1)
            override fun selectionEnd(): Int? {
                val request = android.view.inputmethod.ExtractedTextRequest().apply {
                    hintMaxChars = MAX_LEFT_CONTEXT_LEN * 2 + text.length
                    hintMaxLines = 1
                }
                val extracted = connection.getExtractedText(request, 0) ?: return null
                return ComposingEditorState.absoluteSelectionEnd(extracted.startOffset, extracted.selectionStart, extracted.selectionEnd)
            }
            override fun select(position: Int) = connection.setSelection(position, position)
            override fun endBatch() { connection.endBatchEdit() }
        }, text, position)
    }

    private fun readEditorLeftContext(): String? {
        val connection = currentInputConnection ?: return null
        val before = connection.getTextBeforeCursor(MAX_LEFT_CONTEXT_LEN * 2 + editorComposingPreview.length, 0)?.toString()
        return EditorContext.confirmedLeft(before, editorComposingPreview, composingEditor.cursor)
    }

    private fun editorContextIsCurrent(): Boolean = isSensitiveInput() || readEditorLeftContext() == cachedLeftContext

    private fun requestKey() = AssistRequestGate.Key(
        reading = composingText.convertTarget,
        cursor = composingText.convertTargetCursorPosition,
        context = cachedLeftContext,
        profile = aiProfileState.value,
        modelRevision = ZenzNative.modelRevision,
        assistEnabled = isAiAssistEnabled(),
        autoCorrectionEnabled = isAutoTypoCorrectionState.value,
        predictionModel = getPredictiveModel(),
        inputType = currentInputEditorInfo?.inputType ?: 0,
        imeOptions = currentInputEditorInfo?.imeOptions ?: 0,
        rawInput = composingText.input.map { it.copy() },
        vocabularyRevision = com.zeroconvert.ime.assist.AssistVocabulary.revision,
        conversionRange = conversionRange.active,
        learningMode = LearningPolicy.mode.name,
        dictionaryCorrectionEnabled = isDictionaryTypoCorrectionState.value
    )

    private fun cancelPendingInference(edited: Boolean = true) {
        requestGate.invalidate()
        ZenzNative.abort()
        inferenceJob?.cancel()
        aiAssistJob?.cancel()
        aiAssistResultState.value = null
        displayedAssistTicket = null
        assistInputConnection = null
        isConfirming = false
        if (edited) {
            if (conversionRange.active) clearCandidateList()
            conversionRange.clear()
            conversionRangeState.value = ""
            corrections.edited()
            aiAssistAdoptedState.value = false
        }
    }

    private fun isSensitiveInput(): Boolean {
        val info = currentInputEditorInfo ?: return true
        return com.zeroconvert.ime.assist.InputPrivacy.isSensitive(info.inputType, info.imeOptions)
    }

    private fun recordCommittedCorrections(text: String) {
        if (isSensitiveInput()) return
        val accepted = corrections.committed(text)
        if (accepted.isEmpty() || !LearningPolicy.canWrite) return
        serviceScope.launch(Dispatchers.IO) {
            for (entry in accepted) {
                com.zeroconvert.ime.assist.TypoLearning.record(applicationContext, entry.reading, entry.surface)
            }
        }
    }

    // Sliding-chunk auto-commit history & rewind state
    private val inferenceHistory = mutableListOf<InferenceSnapshot>()
    private var lastAutoCommitRewind: AutoCommitRewindRecord? = null
    private var canRewindOnNextBackspace: Boolean = false
    private var hasSelectionInEditor: Boolean = false

    private fun clearSelectionCharacterCount() {
        selectionCountJob?.cancel()
        selectionCountJob = null
        selectionCountGate.capture(-1, -1)
        selectedCharacterCountState.value = null
    }

    private fun updateSelectionCharacterCount(start: Int, end: Int) {
        selectionCountJob?.cancel()
        selectionCountJob = null
        val ticket = selectionCountGate.capture(start, end)
        selectedCharacterCountState.value = ticket?.count
        if (ticket == null || !ticket.count.canResolve || isSensitiveInput()) return
        val connection = currentInputConnection ?: return
        selectionCountJob = serviceScope.launch(Dispatchers.IO) {
            // Dragging selection handles must not cause an editor query for every frame.
            delay(75)
            val resolved = selectionCountMutex.withLock {
                ensureActive()
                val selected = try { connection.getSelectedText(0) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: RuntimeException) { null }
                ensureActive()
                val requestContext = currentCoroutineContext()
                ticket.count.resolve(selected) { !requestContext.isActive }
            } ?: return@launch
            withContext(Dispatchers.Main) {
                if (connection === currentInputConnection && selectionCountGate.isCurrent(ticket) && !isSensitiveInput()) {
                    selectedCharacterCountState.value = resolved
                }
            }
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val selected = newSelStart != newSelEnd
        val pendingSelectionTranslation = selectionTranslationState.value
        if (pendingSelectionTranslation != null) {
            val selectedNow = if (selected) currentInputConnection?.getSelectedText(0)?.toString() else null
            if (selectedNow != pendingSelectionTranslation.source) dismissSelectionTranslation()
        }
        composingEditor.observeRange(candidatesStart, candidatesEnd)
        val ownedSelection = composingEditor.ownsSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // An external cursor/selection move ends the managed composition. Never
        // apply an automatic correction at a cursor that no longer owns this input.
        val isInsideComposing = candidatesStart >= 0 && candidatesEnd >= candidatesStart &&
            newSelStart >= candidatesStart && newSelEnd <= candidatesEnd
        val isExternalMove = !ownedSelection && managedCursorKeys.isEmpty() && !isInsideComposing &&
            (candidatesStart == -1 || newSelStart < candidatesStart || newSelEnd > candidatesEnd)
        if (!composingText.isEmpty && isExternalMove && !committingTranslation) {
            if (isTranslationMode()) exitTranslationMode(revert = false)
            clearInput()
            currentInputConnection?.finishComposingText()
        } else if (selected) {
            cancelPendingInference(edited = false)
        }
        hasSelectionInEditor = selected
        selectionAvailableState.value = selected
        updateSelectionCharacterCount(newSelStart, newSelEnd)
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

        com.zeroconvert.ime.assist.TypoLearning.ensureLoaded(this)

        loadRecentEmoji()
        loadEmojiCatalog()

        // Init clipboard
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        // Restore pinned text entries
        val pinnedTexts = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getStringSet(KEY_CLIPBOARD_PINNED, emptySet()) ?: emptySet()
        pinnedTexts.forEach { t ->
            if (t.isNotBlank()) clipboardHistory.add(ClipboardEntry(text = t, pinned = true))
        }
        clipboardRetentionHoursState.value = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            .getInt(KEY_CLIPBOARD_RETENTION_HOURS, 1)
        cleanupExpiredClipboard()
        clipListener = ClipboardManager.OnPrimaryClipChangedListener {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i)
                    val uri = item.uri
                    val clipMime = clip.description?.getMimeType(i)
                    val resolvedMime = clipMime ?: uri?.let { contentResolver.getType(it) }
                    val isImage = resolvedMime?.startsWith("image/") == true
                    if (isImage && uri != null) {
                        val cached = cacheClipboardImage(uri)
                        addClipboardEntry(ClipboardEntry(imageUri = cached ?: uri))
                    } else {
                        val text = item.coerceToText(this).toString()
                        if (text.isNotBlank()) {
                            addClipboardEntry(ClipboardEntry(text = text))
                            extractAndAddEntities(text)
                        }
                    }
                }
            }
        }
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        // Auto-capture screenshots into the clipboard (Gboard-like), when enabled.
        if (getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).getBoolean(KEY_SCREENSHOT_CAPTURE, true)) {
            registerScreenshotObserver()
        }

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
                    onCommitText = { text -> commitPanelText(text) },
                    onDeleteChar = { handleDelete() },
                    onEnter = { handleEnter() },
                    onClearInput = { clearInput() },
                    onClipboardPaste = { entry, fromSuggestion -> pasteClipboard(entry, fromSuggestion) }
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
        clearPostCommitCandidates()
        canRewindOnNextBackspace = false

        if (isTranslationMode() && isTranslationLiteralChar(ch)) {
            val literal = when (ch) {
                '.' -> "。"
                ',' -> "、"
                else -> ch.toString()
            }
            appendTranslationLiteral(literal)
            return
        }

        if (isImmediateCommitChar(ch)) {
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
            cachedLeftContext = EditorContext.bounded(ic?.getTextBeforeCursor(MAX_LEFT_CONTEXT_LEN * 2, 0)?.toString().orEmpty())
            inferenceHistory.clear()
            lastAutoCommitRewind = null
        }

        // Abort ongoing inference
        cancelPendingInference()

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

        if (!setComposingPreview(currentInputConnection, ConversionRangeSession.preview(composingText, tentativeDisplay, isRealtime))) {
            clearCandidateList()
            return
        }

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
        clearPostCommitCandidates()
        canRewindOnNextBackspace = false

        if (isTranslationMode() && kana.singleOrNull()?.let { isTranslationLiteralChar(it) } == true) {
            appendTranslationLiteral(kana)
            return
        }

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
            cachedLeftContext = EditorContext.bounded(ic?.getTextBeforeCursor(MAX_LEFT_CONTEXT_LEN * 2, 0)?.toString().orEmpty())
            inferenceHistory.clear()
            lastAutoCommitRewind = null
        }

        // Abort ongoing inference
        cancelPendingInference()

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

        if (!setComposingPreview(currentInputConnection, ConversionRangeSession.preview(composingText, tentativeDisplay, isRealtime))) {
            clearCandidateList()
            return
        }

        if (surface.isNotEmpty()) {
            triggerDebouncedConversion(surface)
        } else {
            clearCandidateList()
        }
    }

    fun handleDakutenToggle() {
        handleKanaModifier(KanaModifierKind.TOGGLE)
    }

    fun handleKanaModifier(kind: KanaModifierKind) {
        vibrateOnKeyPress()
        if (isConfirming) return
        if (composingText.isEmpty) return

        val cursor = composingText.convertTargetCursorPosition
        if (cursor <= 0) return

        val surface = composingText.convertTargetBeforeCursor
        if (surface.isEmpty()) return
        val lastChar = surface.last()
        val modified = KanaModifier.modify(lastChar, kind)
        if (modified == lastChar) return

        canRewindOnNextBackspace = false
        cancelPendingInference()

        composingText.deleteBackwardFromCursorPosition(1)
        composingText.insertAtCursorPosition(modified.toString(), InputStyle.DIRECT)

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

        if (!setComposingPreview(currentInputConnection, ConversionRangeSession.preview(composingText, tentativeDisplay, isRealtime))) {
            clearCandidateList()
            return
        }

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
        cancelPendingInference()

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

        if (!setComposingPreview(currentInputConnection, ConversionRangeSession.preview(composingText, tentativeDisplay, isRealtime))) {
            clearCandidateList()
            return
        }

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
        if (isTranslationMode()) {
            scheduleTranslation()
            return
        }
        if (isSensitiveInput() || composingText.isEmpty) pendingConversionContinuation = null
        cancelPendingInference(edited = false)
        if (!isSensitiveInput()) readEditorLeftContext()?.let { cachedLeftContext = it }
        // Capture every mutable input on the UI thread, before dispatching work.
        val snapshot = composingText.clone()
        val ticket = requestGate.capture(requestKey())
        val connection = currentInputConnection ?: return
        val context = cachedLeftContext
        val continuation = pendingConversionContinuation
        val profile = aiProfileState.value
        val sensitive = isSensitiveInput()
        val reconversion = reconverting
        val rangeConversion = conversionRange.active
        val fixedPrefix = corrections.fixedPrefix(surface)
        val dictionaryCorrection = !sensitive && !reconversion && !rangeConversion && isDictionaryTypoCorrectionState.value
        inferenceJob = serviceScope.launch(Dispatchers.IO) {
            try {
                delay(50)
                KanaKanjiConverter.ensureInitialized(applicationContext)
                val results = converter.requestCandidates(snapshot, context, enableAI = !sensitive && !reconversion && !rangeConversion,
                    profile = profile, personalize = !sensitive, fixedSurfacePrefix = fixedPrefix,
                    experimentalKeyboardCorrection = dictionaryCorrection,
                    predictiveConversion = isPredictiveConversionEnabled(),
                    continuation = continuation).let {
                    if (rangeConversion) ConversionRangeSession.candidatesForRange(snapshot, it) else it
                }
                ensureActive()
                withContext(Dispatchers.Main) {
                    if (connection !== currentInputConnection || !requestGate.isCurrent(ticket, requestKey()) || !editorContextIsCurrent()) {
                        return@withContext
                    }
                    // Learning-history prefix predictions are candidate-bar only: never inline.
                    val inlineBase = { results.firstOrNull { !it.isLearningPrediction } ?: results.firstOrNull() }
                    val aiCand = (results.firstOrNull { it.isAI } ?: inlineBase())?.previewForReading(surface).orEmpty()
                    if (composingText.isAtEndIndex && surface.isNotEmpty() && aiCand.isNotEmpty() &&
                        !hasTrailingIncompleteRomaji(surface) && !corrections.holdsAutoCommit
                    ) {
                        recordInferenceSnapshot(inferenceHistory, InferenceSnapshot(surface, aiCand, snapshot.input.size))
                    }
                    if (!sensitive && !reconversion && !rangeConversion && isAutoCommitEnabled() && composingText.isAtEndIndex &&
                        !corrections.holdsAutoCommit && !hasTrailingIncompleteRomaji(surface)
                    ) {
                        val autoCommit = if (BuildConfig.WINDOWS_ENGINE) {
                            com.zeroconvert.ime.engine.WindowsEngine.autoCommit(
                                composingText.input.mapNotNull { (it.piece as? com.zeroconvert.ime.azookey.input.InputPiece.CharPiece)?.char }.joinToString(""),
                                surface, inferenceHistory, composingText::isIndependentInputBoundary)
                        } else findStableAutoCommitPrefix(surface, inferenceHistory, composingText::isIndependentInputBoundary)
                        if (autoCommit != null && aiCand.startsWith(autoCommit.surfacePrefix)) {
                            performAutoCommit(autoCommit, aiCand)
                            return@withContext
                        }
                    }
                    val previousResult = lastInferredResult
                    val best = inlineBase()?.previewForReading(surface).orEmpty()
                    candidatesList.clear()
                    candidatesList.addAll(results)
                    predictedCandidate.value = best
                    lastInferredSurface = surface
                    lastInferredResult = best
                    if (!sensitive && !reconversion && !rangeConversion && isAiAssistEnabled() && !hasTrailingIncompleteRomaji(surface)) {
                        val allowCorrection = !corrections.isApplied && fixedPrefix.isEmpty()
                        aiAssistJob = serviceScope.launch(Dispatchers.IO) {
                            com.zeroconvert.ime.assist.TypoLearning.ensureLoaded(applicationContext)
                            com.zeroconvert.ime.assist.AiAssistManager.streamAiAssist(
                                reading = surface,
                                baseline = best,
                                context = context,
                                allowCorrection = allowCorrection
                            ) { update ->
                                ensureActive()
                                withContext(Dispatchers.Main) assistUpdate@ {
                                    if (connection !== currentInputConnection ||
                                        !requestGate.isCurrent(ticket, requestKey()) || isSensitiveInput() || !editorContextIsCurrent()) return@assistUpdate
                                    val suggestions = update.suggestions.filter { it.text != corrections.rejectedSurface }
                                    val merged = ConversionCandidateMerge.withCorrection(candidatesList,
                                        suggestions.firstOrNull { it.type == com.zeroconvert.ime.assist.AiAssistType.TYPO_CORRECTION })
                                    candidatesList.clear()
                                    candidatesList.addAll(merged)
                                    val selected = update.selected?.takeIf { it.text != corrections.rejectedSurface }
                                    aiAssistResultState.value = selected
                                    displayedAssistTicket = if (suggestions.isNotEmpty()) ticket else null
                                    assistInputConnection = if (suggestions.isNotEmpty()) connection else null
                                    val automatic = update.automaticCorrection?.takeIf { it.text != corrections.rejectedSurface }
                                    if (automatic != null && isAutoTypoCorrectionState.value) {
                                        aiAssistResultState.value = automatic
                                        adoptAiAssist(explicit = false)
                                        // If automatic application is ineligible, retain the displayed tap target.
                                        if (requestGate.isCurrent(ticket, requestKey())) aiAssistResultState.value = selected
                                    }
                                }
                            }
                        }
                    }
                    if ((isRealtimeInlineEnabled() || rangeConversion) && best.isNotEmpty()) {
                        val hasKanjiInPrevious = previousResult.any { it in '\u3400'..'\u9FFF' }
                        val downgrade = best == surface && hasKanjiInPrevious &&
                            best.none { it in '\u3400'..'\u9FFF' }
                        if (!downgrade) {
                            val suffix = composingText.convertTarget.drop(composingText.convertTargetCursorPosition)
                            if (!setComposingPreview(connection, best + suffix)) {
                                cancelPendingInference(edited = false)
                                clearCandidateList()
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Conversion failed", error)
            }
        }
    }

    private fun performAutoCommit(
        autoCommit: AutoCommitCandidate,
        fullAiSurface: String
    ) {
        val prefixSurface = autoCommit.surfacePrefix
        val readingLen = autoCommit.readingLength
        val committedReading = autoCommit.committedReading
        if (!composingText.isIndependentInputBoundary(readingLen)) return
        val originalInput = composingText.clone()
        val fullReadingBeforeAutoCommit = originalInput.convertTarget

        val connection = currentInputConnection ?: return
        var showContinuations = false
        connection.beginBatchEdit()
        try {
            if (!connection.commitText(prefixSurface, 1)) return
            recordCommittedCorrections(prefixSurface)

            // 1. Save rewind state before modifying context
            lastAutoCommitRewind = AutoCommitRewindRecord(
                committedSurface = prefixSurface,
                committedReading = committedReading,
                previousLeftContext = cachedLeftContext,
                originalInput = originalInput
            )
            canRewindOnNextBackspace = true

            // 3. Remove the first L hiragana characters from composingText
            composingText.prefixComplete(readingLen)
            val remainingReading = composingText.convertTargetBeforeCursor
            pendingConversionContinuation = if (remainingReading.isNotEmpty())
                ConversionContinuation.derive(fullReadingBeforeAutoCommit, remainingReading, prefixSurface) else null
            currentComposingSurface.value = remainingReading

            // 4. Retain bounded confirmed left context for the remaining input.
            cachedLeftContext = EditorContext.bounded(cachedLeftContext + prefixSurface)

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
                setManagedComposingText(currentInputConnection, display, 1)

                // Trigger fresh conversion for the remaining tail with the updated left context
                triggerDebouncedConversion(remainingReading)
            } else {
                clearInput()
                showContinuations = true
            }
        } finally { connection.endBatchEdit() }
        if (showContinuations && connection === currentInputConnection) {
            showPostCommitContinuations(connection)
        }
    }

    fun handleDelete() {
        vibrateOnKeyPress()
        if (isConfirming) return
        clearPostCommitCandidates()
        if (aiAssistAdoptedState.value && !hasSelectionInEditor) {
            revertAiAssist()
            return
        }
        if (englishComposing.isNotEmpty()) {
            handleEnglishDelete()
            return
        }
        cancelPendingInference()

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

            val connection = currentInputConnection ?: return
            val tailPreview = if (isRealtimeInlineEnabled()) predictedCandidate.value.ifEmpty { composingText.convertTarget } else composingText.convertTarget
            connection.beginBatchEdit()
            val rewindResult = try {
                AutoCommitRewind.restore(rewindRecord, composingText.convertTarget, tailPreview, object : AutoCommitRewind.Editor {
                    override fun beforeCursor(length: Int) = connection.getTextBeforeCursor(length, 0)?.toString()
                    override fun composing(text: String) = setManagedComposingText(connection, text, 1)
                    override fun deleteBefore(length: Int) = connection.deleteSurroundingText(length, 0)
                    override fun commit(text: String) = connection.commitText(text, 1)
                })
            } finally { connection.endBatchEdit() }
            if (rewindResult == AutoCommitRewind.Result.COMMITTED_FALLBACK) { clearInput(); return }
            if (rewindResult != AutoCommitRewind.Result.RESTORED && rewindResult != AutoCommitRewind.Result.RESTORE_PENDING) return
            cachedLeftContext = rewindRecord.previousLeftContext
            composingText.restoreFrom(rewindRecord.originalInput ?: return)
            pendingConversionContinuation = null

            val restoredSurface = composingText.convertTargetBeforeCursor
            currentComposingSurface.value = restoredSurface
            lastInferredSurface = ""
            lastInferredResult = ""
            inferenceHistory.clear()

            setManagedComposingText(currentInputConnection, composingText.convertTarget, 1)
            if (restoredSurface.isNotEmpty()) {
                triggerDebouncedConversion(restoredSurface)
            }
            return
        }

        canRewindOnNextBackspace = false

        if (!composingText.isEmpty) {
            if (composingText.isAtStartIndex) { clearCandidateList(); return }
            val previous = ComposingEditorState.moveByCodePoint(composingText.convertTarget, composingText.convertTargetCursorPosition, -1)
            composingText.deleteBackwardFromCursorPosition(composingText.convertTargetCursorPosition - previous)
            val surface = composingText.convertTargetBeforeCursor
            currentComposingSurface.value = surface
            inferenceHistory.removeAll { it.readingHiragana.length >= surface.length }
            if (composingText.isEmpty) {
                setManagedComposingText(currentInputConnection, "", 0)
                currentInputConnection?.finishComposingText()
                clearInput()
            } else {
                val inline = isRealtimeInlineEnabled()
                val prefix = if (inline && lastInferredResult.isNotEmpty() && surface.startsWith(lastInferredSurface)) {
                    lastInferredResult + surface.substring(lastInferredSurface.length)
                } else {
                    lastInferredSurface = ""
                    lastInferredResult = ""
                    surface
                }
                val preview = ConversionRangeSession.preview(composingText, prefix, inline)
                if (setComposingPreview(currentInputConnection, preview) && surface.isNotEmpty()) {
                    triggerDebouncedConversion(surface)
                } else clearCandidateList()
            }
        } else {
            val ok = currentInputConnection?.deleteSurroundingText(1, 0) ?: false
            if (!ok) {
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
            }
        }
    }

    fun canBeginConversionRange(): Boolean = !isConfirming && !isSensitiveInput() && !reconverting &&
        !isTranslationMode() &&
        !corrections.holdsAutoCommit && !conversionRange.active && composingText.isAtEndIndex &&
        composingText.convertTarget.codePointCount(0, composingText.convertTarget.length) in 2..128 &&
        !hasTrailingIncompleteRomaji(composingText.convertTarget)

    fun beginConversionRange() {
        if (isTranslationMode() || !canBeginConversionRange()) return
        pendingConversionContinuation = null
        val connection = currentInputConnection ?: return
        val preview = if (isRealtimeInlineEnabled()) predictedCandidate.value.ifEmpty { composingText.convertTarget } else composingText.convertTarget
        if (!conversionRange.begin(composingText, preview)) return
        if (!setComposingPreview(connection, composingText.convertTarget)) {
            conversionRange.cancel(composingText)
            return
        }
        refreshConversionRange()
    }

    fun canAdjustConversionRange(direction: Int): Boolean = conversionRange.canAdjust(composingText, direction)

    fun adjustConversionRange(direction: Int) {
        if (isConfirming || !conversionRange.active) return
        if (conversionRange.adjust(composingText, direction)) refreshConversionRange()
    }

    fun cancelConversionRange() {
        if (!conversionRange.active || isConfirming) return
        val connection = currentInputConnection ?: return
        val preview = conversionRange.originalPreview
        if (!setManagedComposingText(connection, preview, 1)) return
        conversionRange.cancel(composingText)
        conversionRangeState.value = ""
        clearCandidateList()
        currentComposingSurface.value = composingText.convertTargetBeforeCursor
        triggerDebouncedConversion(composingText.convertTargetBeforeCursor)
    }

    private fun refreshConversionRange() {
        clearCandidateList()
        pendingConversionContinuation = null
        lastAutoCommitRewind = null
        canRewindOnNextBackspace = false
        val reading = composingText.convertTargetBeforeCursor
        conversionRangeState.value = reading + "｜" + composingText.convertTarget.drop(composingText.convertTargetCursorPosition)
        currentComposingSurface.value = reading
        triggerDebouncedConversion(reading)
    }

    fun handleCursorLeft() {
        if (isTranslationMode()) return
        if (conversionRange.active) { adjustConversionRange(-1); return }
        vibrateOnKeyPress()
        clearPostCommitCandidates()
        canRewindOnNextBackspace = false
        if (!composingText.isEmpty) {
            val target = ComposingEditorState.moveByCodePoint(composingText.convertTarget, composingText.convertTargetCursorPosition, -1)
            val moved = composingText.moveCursorFromCursorPosition(target - composingText.convertTargetCursorPosition)
            if (moved != 0) {
                updateComposingDisplayOnCursorMove()
            }
        } else {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        }
    }

    fun handleCursorRight() {
        if (isTranslationMode()) return
        if (conversionRange.active) { adjustConversionRange(1); return }
        vibrateOnKeyPress()
        clearPostCommitCandidates()
        canRewindOnNextBackspace = false
        if (!composingText.isEmpty) {
            val target = ComposingEditorState.moveByCodePoint(composingText.convertTarget, composingText.convertTargetCursorPosition, 1)
            val moved = composingText.moveCursorFromCursorPosition(target - composingText.convertTargetCursorPosition)
            if (moved != 0) {
                updateComposingDisplayOnCursorMove()
            }
        } else {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (!composingText.isEmpty && event.hasNoModifiers() &&
            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
            managedCursorKeys.add(keyCode)
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) handleCursorLeft() else handleCursorRight()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (managedCursorKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun updateComposingDisplayOnCursorMove() {
        cancelPendingInference()
        clearCandidateList()
        pendingConversionContinuation = null
        val surfaceBefore = composingText.convertTargetBeforeCursor
        currentComposingSurface.value = surfaceBefore
        // Keep the current preview if its suffix matches the remaining reading suffix,
        // rather than reverting the entire text to raw hiragana.
        val suffix = composingText.convertTarget.drop(composingText.convertTargetCursorPosition)
        val currentPreview = composingEditor.preview
        val previewToSet = if (currentPreview.isNotEmpty() && currentPreview.endsWith(suffix)) {
            currentPreview
        } else {
            composingText.convertTarget
        }
        if (!setComposingPreview(currentInputConnection, previewToSet)) return
        if (surfaceBefore.isNotEmpty()) triggerDebouncedConversion(surfaceBefore)
    }

    fun handleEnter() {
        if (isConfirming) return
        if (isTranslationMode()) {
            handleTranslationEnter()
            return
        }
        if (englishComposing.isNotEmpty()) {
            commitEnglishBuffer(useTopCandidate = false)
        } else if (!composingText.isEmpty) {
            confirmAsync(showContinuations = false)
        } else {
            // A newline ends the phrase: drop the next-word prediction before it is inserted.
            clearPostCommitCandidates()
            sendKeyChar('\n')
        }
    }

    fun canReconvertSelection(): Boolean = selectionAvailableState.value && composingText.isEmpty &&
        !isSensitiveInput() && !isTranslationMode()

    fun reconvertSelection() {
        if (isTranslationMode() || !canReconvertSelection() || isConfirming) return
        val connection = currentInputConnection ?: return
        val selected = connection.getSelectedText(0)?.toString() ?: return
        cancelPendingInference(edited = false)
        pendingConversionContinuation = null
        val ticket = requestGate.capture(requestKey())
        inferenceJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val plan = ReconversionPlan.create(selected) { input ->
                    converter.requestCandidates(input, "", enableAI = false, personalize = true)
                }
                ensureActive()
                withContext(Dispatchers.Main) {
                    if (connection !== currentInputConnection || !requestGate.isCurrent(ticket, requestKey()) ||
                        isSensitiveInput() || connection.getSelectedText(0)?.toString() != selected) return@withContext
                    if (plan == null) {
                        Toast.makeText(applicationContext, "選択した文字の読みを復元できませんでした", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    if (!setManagedComposingText(connection, plan.original, 1)) return@withContext
                    composingText.stopComposition()
                    composingText.insertAtCursorPosition(plan.reading, InputStyle.DIRECT)
                    reconverting = true
                    hasSelectionInEditor = false
                    selectionAvailableState.value = false
                    clearSelectionCharacterCount()
                    currentComposingSurface.value = plan.reading
                    candidatesList.clear()
                    candidatesList.addAll(plan.candidates)
                    predictedCandidate.value = plan.original
                    lastInferredSurface = plan.reading
                    lastInferredResult = plan.original
                    inferenceHistory.clear()
                    requestGate.invalidate()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Reconversion failed", error)
            }
        }
    }

    fun confirmAsync(trailingText: String = "", showContinuations: Boolean = true) {
        if (isConfirming) return
        if (isTranslationMode()) {
            handleTranslationEnter()
            return
        }
        if (englishComposing.isNotEmpty()) {
            // A mode change accepts the spelling, not an English completion.
            commitText(englishComposing + trailingText, committedCandidate = null, trailingText = trailingText,
                offerContinuations = showContinuations)
            return
        }
        if (composingText.isEmpty) {
            if (trailingText.isNotEmpty()) commitDirect(trailingText)
            return
        }
        val currentSurface = composingText.convertTargetBeforeCursor
        if (currentSurface == lastInferredSurface && predictedCandidate.value.isNotEmpty() && editorContextIsCurrent()) {
            val candidate = candidatesList.firstOrNull { it.text == predictedCandidate.value && it.assist == null }
            commitText(predictedCandidate.value + trailingText, committedCandidate = candidate, trailingText = trailingText,
                offerContinuations = showContinuations)
            return
        }
        cancelPendingInference(edited = false)
        if (!isSensitiveInput()) {
            val actualContext = readEditorLeftContext()
            if (actualContext == null) { clearInput(); return }
            cachedLeftContext = actualContext
        }
        val snapshot = composingText.clone()
        val connection = currentInputConnection ?: return
        val ticket = requestGate.capture(requestKey())
        val context = cachedLeftContext
        val continuation = pendingConversionContinuation
        val profile = aiProfileState.value
        val sensitive = isSensitiveInput()
        val reconversion = reconverting
        val rangeConversion = conversionRange.active
        val fixedPrefix = corrections.fixedPrefix(currentSurface)
        val dictionaryCorrection = !sensitive && !reconversion && !rangeConversion && isDictionaryTypoCorrectionState.value
        isConfirming = true
        inferenceJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val results = converter.requestCandidates(snapshot, context, enableAI = !sensitive && !reconversion && !rangeConversion,
                    profile = profile, personalize = !sensitive, fixedSurfacePrefix = fixedPrefix,
                    experimentalKeyboardCorrection = dictionaryCorrection,
                    predictiveConversion = isPredictiveConversionEnabled(),
                    continuation = continuation).let {
                    if (rangeConversion) ConversionRangeSession.candidatesForRange(snapshot, it) else it
                }
                ensureActive()
                withContext(Dispatchers.Main) {
                    if (connection === currentInputConnection && requestGate.isCurrent(ticket, requestKey()) && editorContextIsCurrent()) {
                        isConfirming = false
                        // Explicit conversion adopts the regular top candidate, not a learning-history prediction.
                        val candidate = results.firstOrNull { !it.isLearningPrediction } ?: results.firstOrNull()
                        commitText((candidate?.text ?: currentSurface) + trailingText, committedCandidate = candidate,
                            trailingText = trailingText, offerContinuations = showContinuations)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    if (connection === currentInputConnection && requestGate.isCurrent(ticket, requestKey()) && editorContextIsCurrent()) {
                        isConfirming = false
                        commitText(currentSurface + trailingText, offerContinuations = showContinuations)
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (requestGate.isGenerationCurrent(ticket)) isConfirming = false
                }
            }
        }
    }

    fun confirmCurrent(trailingText: String = "", showContinuations: Boolean = true) {
        confirmAsync(trailingText, showContinuations)
    }

    fun commitDirect(text: String) {
        if (isTranslationMode() && !committingTranslation) {
            appendTranslationLiteral(text)
            return
        }
        vibrateOnKeyPress()
        currentInputConnection?.commitText(text, 1)
        clearInput()
    }

    /**
     * Emoji are committed as literal text: they must not enter the reading-based
     * conversion or learning paths, but they do feed the palette's "最近" row.
     */
    fun commitEmoji(emoji: String) {
        if (emoji.isEmpty()) return
        if (isTranslationMode() && !committingTranslation) {
            appendTranslationLiteral(emoji)
            recordRecentEmoji(emoji)
            return
        }
        vibrateOnKeyPress()
        // Known issue: this confirmation is asynchronous, so the emoji can be committed
        // ahead of the converted reading. Panel text uses the ordered commitPanelText path.
        if (!composingText.isEmpty) confirmCurrent()
        currentInputConnection?.commitText(emoji, 1)
        clearInput()
        recordRecentEmoji(emoji)
    }

    fun handleEnglishChar(ch: Char) {
        vibrateOnKeyPress()
        if (isConfirming) return
        clearPostCommitCandidates()
        if (isTranslationMode()) {
            if (ch == ' ') {
                handleEnglishSpace()
                return
            }
            if (ch.isISOControl()) return
            appendTranslationLiteral(ch.toString())
            return
        }
        if (ch == ' ') { handleEnglishSpace(); return }
        if (!ch.isLetter()) {
            if (englishComposing.isNotEmpty()) commitEnglishBuffer(useTopCandidate = false)
            commitDirect(ch.toString())
            return
        }
        if (englishComposing.isEmpty()) {
            val ic = currentInputConnection
            cachedLeftContext = EditorContext.bounded(ic?.getTextBeforeCursor(MAX_LEFT_CONTEXT_LEN * 2, 0)?.toString().orEmpty())
        }
        englishComposing += ch
        currentComposingSurface.value = englishComposing
        setManagedComposingText(currentInputConnection, englishComposing, 1)
        triggerEnglishSuggestions(englishComposing)
    }

    fun handleEnglishText(text: String) {
        for (ch in text) handleEnglishChar(ch)
    }

    fun handleEnglishDelete() {
        if (isConfirming) return
        if (isTranslationMode()) {
            if (englishComposing.isNotEmpty()) {
                englishComposing = englishComposing.dropLast(1)
                currentComposingSurface.value = englishComposing
                if (englishComposing.isNotEmpty()) {
                    setManagedComposingText(currentInputConnection, englishComposing, 1)
                } else {
                    setManagedComposingText(currentInputConnection, "", 0)
                }
                scheduleTranslation()
            } else if (!composingText.isEmpty) {
                val previous = ComposingEditorState.moveByCodePoint(composingText.convertTarget, composingText.convertTargetCursorPosition, -1)
                composingText.deleteBackwardFromCursorPosition(composingText.convertTargetCursorPosition - previous)
                currentComposingSurface.value = composingText.convertTargetBeforeCursor
                if (composingText.isEmpty) {
                    setManagedComposingText(currentInputConnection, "", 0)
                } else {
                    setManagedComposingText(currentInputConnection, composingText.convertTarget, 1)
                }
                scheduleTranslation()
            } else {
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
            return
        }
        if (englishComposing.isNotEmpty()) {
            englishComposing = englishComposing.dropLast(1)
            currentComposingSurface.value = englishComposing
            if (englishComposing.isNotEmpty()) {
                setManagedComposingText(currentInputConnection, englishComposing, 1)
                triggerEnglishSuggestions(englishComposing)
            } else {
                setManagedComposingText(currentInputConnection, "", 1)
                candidatesList.clear()
                predictedCandidate.value = ""
            }
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    fun handleEnglishSpace() {
        vibrateOnKeyPress()
        if (isTranslationMode()) {
            appendTranslationLiteral(" ")
            return
        }
        // A space ends the phrase: the next-word prediction must not survive it.
        clearPostCommitCandidates()
        if (englishComposing.isNotEmpty()) {
            commitEnglishBuffer(useTopCandidate = isEnglishSpaceAdoptsCandidateState.value)
        }
        currentInputConnection?.commitText(" ", 1)
    }

    fun commitEnglishBuffer(useTopCandidate: Boolean = true) {
        if (isTranslationMode()) {
            commitTranslationFromUi()
            return
        }
        if (englishComposing.isEmpty()) return
        val candidate = if (useTopCandidate) candidatesList.firstOrNull() else null
        commitText(candidate?.text ?: englishComposing,
            isExplicitSelection = candidate != null, committedCandidate = candidate)
    }

    private fun clearEnglishInput() {
        englishComposing = ""
        currentComposingSurface.value = ""
        candidatesList.clear()
        predictedCandidate.value = ""
    }

    private fun triggerEnglishSuggestions(prefix: String) {
        if (isSensitiveInput()) {
            candidatesList.clear()
            predictedCandidate.value = ""
            return
        }
        serviceScope.launch(Dispatchers.Default) {
            delay(30)
            if (englishComposing != prefix) return@launch
            val cands = EnglishWordDictionary.suggest(applicationContext, prefix, 8)
            withContext(Dispatchers.Main) {
                if (englishComposing == prefix && !isSensitiveInput()) {
                    candidatesList.clear()
                    candidatesList.addAll(cands)
                    predictedCandidate.value = cands.firstOrNull()?.text ?: ""
                }
            }
        }
    }

    /**
     * Entry point for the symbols/number panel. A symbol that is not a conversion
     * candidate must not discard the pending reading, so confirm the reading first
     * and append the symbol as trailing text; learning then records only the
     * candidate surface. Emoji keep their separate [commitEmoji] path.
     */
    fun commitPanelText(text: String) {
        if (text.isEmpty()) return
        val decision = PanelTextCommit.decide(
            translationMode = isTranslationMode(),
            committingTranslation = committingTranslation,
            compositionEmpty = composingText.isEmpty,
            englishBufferNonEmpty = englishComposing.isNotEmpty(),
            candidateMatches = candidatesList.any { it.text == text }
        )
        when (decision) {
            PanelTextCommitDecision.Direct, PanelTextCommitDecision.AdoptCandidate ->
                commitText(text, isExplicitSelection = true)
            PanelTextCommitDecision.ConfirmThenAppend -> confirmAsync(trailingText = text)
        }
    }

    fun commitText(
        text: String,
        isExplicitSelection: Boolean = false,
        committedCandidate: Candidate? = candidatesList.firstOrNull { it.text == text },
        trailingText: String = "",
        offerContinuations: Boolean = true
    ) {
        if (isConfirming) return
        if (isTranslationMode() && !committingTranslation) {
            appendTranslationLiteral(text)
            return
        }
        val connection = currentInputConnection ?: return
        vibrateOnKeyPress()
        val wasEnglish = englishComposing.isNotEmpty()
        val englishReading = englishComposing
        val consumed = committedCandidate?.engineConsumedReading
            ?.takeIf { it in 1..composingText.convertTargetCursorPosition }
            ?: composingText.convertTargetCursorPosition
        val readingBefore = composingText.convertTarget.take(consumed)
        val surfaceAfter = composingText.convertTarget.drop(consumed)
        val selection = if (!wasEnglish) ConversionCommitLearning.capture(
            readingBefore, text, committedCandidate, trailingText,
            sensitive = isSensitiveInput(), correctionApplied = corrections.isApplied
        ) else null
        val learnEnglish = wasEnglish && isExplicitSelection && !isSensitiveInput() && LearningPolicy.canWrite
        val previousLearningBefore = previousLearningSegment
        val learningRevisionBefore = KanaKanjiConverter.learningHistoryRevision
        cancelPendingInference(edited = false)
        var showContinuations = false
        connection.beginBatchEdit()
        try {
            if (!connection.commitText(text, 1)) return
            composingEditor.finishComposition()
            conversionRange.clear()
            conversionRangeState.value = ""
            // A tap is not a training event. Only a successful editor commit is.
            recordCommittedCorrections(text)
            if (selection != null || learnEnglish) {
                val event = if (learnEnglish) PendingLearning(englishReading, text, true)
                    else PendingLearning(checkNotNull(selection).reading, selection.surface, false, selection,
                        previousLearningBefore, learningRevisionBefore)
                if (learningQueue.trySend(event).isFailure) {
                    Log.w(TAG, "Selection learning queue is full or closed")
                }
            }
            // Swift retains only the final lexical item until this composition ends.
            previousLearningSegment = selection?.segments?.lastOrNull()
            if (wasEnglish) {
                clearEnglishInput()
                return
            }
            if (surfaceAfter.isNotEmpty()) {
                val fullReadingBeforeCommit = composingText.convertTarget
                cachedLeftContext = EditorContext.bounded(cachedLeftContext + text)
                composingText.prefixComplete(readingBefore.length)
                corrections.edited()
                aiAssistAdoptedState.value = false
                val remainingSurface = composingText.convertTarget
                val remainingReading = composingText.convertTargetBeforeCursor
                currentComposingSurface.value = remainingReading
                lastInferredSurface = ""
                lastInferredResult = ""
                inferenceHistory.clear()
                canRewindOnNextBackspace = false
                pendingConversionContinuation = if (trailingText.isEmpty())
                    ConversionContinuation.derive(fullReadingBeforeCommit, composingText.convertTarget, text) else null
                if (setComposingPreview(connection, remainingSurface) && remainingReading.isNotEmpty()) {
                    triggerDebouncedConversion(remainingReading)
                } else clearCandidateList()
            } else {
                clearInput()
                showContinuations = offerContinuations
            }
        } finally { connection.endBatchEdit() }
        if (showContinuations && connection === currentInputConnection) {
            showPostCommitContinuations(connection)
        }
    }

    /**
     * Gboard-style next-word prediction: after a full commit, offer the learned chunk that
     * follows the committed text. Tapping one inserts it directly (no reading needed).
     */
    private fun showPostCommitContinuations(connection: android.view.inputmethod.InputConnection) {
        if (isTranslationMode() || isSensitiveInput() || !isPredictiveConversionEnabled()) return
        if (!LearningPolicy.canRead) return
        // A space or newline ends the phrase, so the learned chunk no longer follows it.
        val before = connection.getTextBeforeCursor(128, 0)?.toString().orEmpty()
        if (before.isEmpty() || before.last().isWhitespace()) return
        val continuations = KanaKanjiConverter.postCommitContinuations(before)
        if (continuations.isEmpty()) return
        candidatesList.clear()
        candidatesList.addAll(continuations)
        predictedCandidate.value = ""
        lastInferredSurface = ""
        lastInferredResult = ""
    }

    private fun clearPostCommitCandidates() {
        if (composingText.isEmpty && englishComposing.isEmpty() && candidatesList.isNotEmpty()) {
            candidatesList.clear()
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
        previousLearningSegment = null
        cancelPendingInference()
        corrections.clear()
        reconverting = false
        hasSelectionInEditor = false
        selectionAvailableState.value = false
        clearSelectionCharacterCount()
        aiAssistAdoptedState.value = false
        savedOriginalCandidates = emptyList()
        savedOriginalSurface = ""
        savedOriginalReading = ""
        composingText.stopComposition()
        englishComposing = ""
        currentComposingSurface.value = ""
        predictedCandidate.value = ""
        lastInferredSurface = ""
        lastInferredResult = ""
        candidatesList.clear()
        cachedLeftContext = ""
        pendingConversionContinuation = null
        composingEditor.clear()
        inferenceHistory.clear()
        lastAutoCommitRewind = null
        canRewindOnNextBackspace = false
        aiAssistResultState.value = null
        if (isTranslationMode()) {
            cancelPendingTranslation()
            translationState.value = translationState.value.copy(
                request = "",
                source = "",
                output = "",
                status = TranslationStatus.IDLE,
                message = null
            )
        }
        selectionTranslationSession.cancel()
        selectionTranslationJob?.cancel()
        selectionTranslationJob = null
        selectionTranslationState.value = null
    }

    /**
     * Seeds the keyboard mode from the focused editor's declared input type.
     *
     * Runs only when the input view starts, so a mode the user picks with the あ/A/1 key
     * survives until the next editor. `beforeKeyboardModeChange()` is deliberately not
     * called: the view is starting with no pending composition, and confirming here would
     * commit text the user never asked to commit.
     */
    private fun applyInputTypeMode(info: android.view.inputmethod.EditorInfo?) {
        if (!isFollowInputTypeModeState.value) return
        if (info == null) return
        val mode = InputTypeMode.modeFor(info.inputType, info.imeOptions) ?: return
        if (keyboardModeState.value == mode) return
        keyboardModeState.value = mode
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        exitTranslationMode(revert = false)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        reloadPreferences()
        clearInput()
        applyInputTypeMode(info)
        inputViewEpochState.value++
        hasSelectionInEditor = info != null && info.initialSelStart >= 0 && info.initialSelStart != info.initialSelEnd
        selectionAvailableState.value = hasSelectionInEditor
        updateSelectionCharacterCount(info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1)
    }

    override fun onStartInput(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        exitTranslationMode(revert = false)
        clearInput()
        hasSelectionInEditor = info != null && info.initialSelStart >= 0 && info.initialSelStart != info.initialSelEnd
        selectionAvailableState.value = hasSelectionInEditor
        updateSelectionCharacterCount(info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1)
    }

    override fun onFinishInput() {
        exitTranslationMode(revert = false)
        clearInput()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        exitTranslationMode(revert = false)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        clearInput()
    }

    override fun onDestroy() {
        learningQueue.close()
        translationJob?.cancel()
        selectionTranslationJob?.cancel()
        translationLoadJob?.cancel()
        translationLoadWaiters.clear()
        super.onDestroy()
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        clipListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        unregisterScreenshotObserver()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        serviceScope.cancel()
        ZenzNative.close()
    }
}
