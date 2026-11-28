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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.*
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.zeroconvert.ime.azookey.input.ComposingText
import com.zeroconvert.ime.azookey.input.InputPiece
import com.zeroconvert.ime.azookey.input.InputStyle
import com.zeroconvert.ime.assist.AiAssistAdoption
import com.zeroconvert.ime.assist.AssistRequestGate
import com.zeroconvert.ime.assist.AutoTypoCorrectionStrength
import com.zeroconvert.ime.assist.CorrectionSession
import com.zeroconvert.ime.assist.InputPrivacy
import com.zeroconvert.ime.assist.SharedAssistPolicy
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
        // Read once; afterwards the terms are reachable from the settings screen only.
        const val KEY_TERMS_ACCEPTED = "terms_accepted"
        const val KEY_REALTIME_INLINE = "realtime_inline_conversion"
        const val KEY_AUTO_COMMIT_ENABLED = "auto_commit_enabled"
        const val KEY_AUTO_COMMIT_MODE = "auto_commit_mode" // off|standard|fast
        const val KEY_AUTO_COMMIT_REWIND_ON_BACKSPACE = "auto_commit_rewind_on_backspace"
        const val KEY_AI_PROFILE = "ai_profile"
        // "system" or a built-in/custom KeyboardThemeCatalog id.
        // Legacy KEY_THEME_MODE ("dark"/"light"/theme id) is migrated via ThemePreferences.
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_APP_THEME_MODE = "app_theme_mode"
        const val KEY_KEYBOARD_THEME_ID = "keyboard_theme_id"
        const val KEY_CUSTOM_THEME_REVISION = "custom_theme_revision"
        const val KEY_JAPANESE_LAYOUT = "japanese_keyboard_layout" // "FLICK_12KEY", "QWERTY"
        const val KEY_LAYOUT_PROFILE_FLICK = com.zeroconvert.ime.layout.LayoutProfilePreferences.KEY_LAYOUT_PROFILE_FLICK
        const val KEY_LAYOUT_PROFILE_QWERTY = com.zeroconvert.ime.layout.LayoutProfilePreferences.KEY_LAYOUT_PROFILE_QWERTY
        const val KEY_KEY_HEIGHT_DP = "keyboard_key_height_dp" // int dp (40..64), default 46
        const val KEY_FLICK_ONLY = "flick_only" // boolean, default true
        const val KEY_QWERTY_NUMBER_ROW = "qwerty_number_row" // boolean, default false
        const val KEY_FOLLOW_INPUT_TYPE_MODE = "follow_input_type_mode" // boolean, default false
        const val KEY_VIBRATION_ENABLED = "vibration_enabled" // boolean, default true
        const val KEY_VIBRATION_DURATION = "vibration_duration" // int ms (5..50), default 15
        const val KEY_KEY_PREVIEW_ENABLED = "key_preview_enabled" // boolean, default true
        const val KEY_FLICK_SENSITIVITY_DP = "flick_sensitivity_dp" // int dp (12..28), default 18
        const val KEY_KEY_BORDERS_ENABLED = "key_borders_enabled" // boolean, default true
        const val KEY_ONE_HANDED_MODE = "one_handed_mode" // String: OFF, LEFT, RIGHT
        const val KEY_ONE_HANDED_WIDTH_PERCENT = "one_handed_width_percent" // int % (60..95), default 82
        const val KEY_SPLIT_KEYBOARD = "split_keyboard"
        const val KEY_SPLIT_GAP_PERCENT = "split_keyboard_gap_percent"
        const val KEY_SPLIT_DUPLICATE_KEYS = "split_keyboard_duplicate_keys"
        const val KEY_KEYBOARD_BOTTOM_PADDING_DP = "keyboard_bottom_padding_dp" // int dp (0..32), default 0
        const val KEY_DYNAMIC_TOUCH_ASSIST = TouchAssistSettings.KEY_HIT_AREA // predictive hit area, default true
        const val KEY_TOUCH_TYPO_CORRECTION = TouchAssistSettings.KEY_CORRECTION // independent; inherits old setting once
        const val KEY_EXPERIMENTAL_AI_ASSIST = "experimental_ai_assist" // boolean, default true
        const val KEY_AUTO_TYPO_CORRECTION = "auto_typo_correction" // legacy boolean fallback
        const val KEY_AUTO_TYPO_CORRECTION_STRENGTH = "auto_typo_correction_strength" // off|conservative|standard|aggressive
        const val KEY_DICTIONARY_TYPO_CORRECTION = "experimental_typo_correction"
        const val DEFAULT_DICTIONARY_TYPO_CORRECTION = true
        const val KEY_PREDICTIVE_CONVERSION = "predictive_conversion" // boolean, Android default: true, Windows default: false
        const val KEY_POST_COMPOSITION_PREDICTION = "post_composition_prediction" // boolean, Android default: true
        const val DEFAULT_POST_COMPOSITION_PREDICTION = true
        const val KEY_ENGLISH_SPACE_ADOPTS_CANDIDATE = "english_space_adopts_candidate" // boolean, default false: insert space without adopting top candidate
        const val KEY_ALWAYS_HALFWIDTH_SPACE = "always_halfwidth_space" // boolean, default false: follow keyboard mode (fullwidth in Japanese, halfwidth in English/Numeric)
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

        private const val MAX_LEFT_CONTEXT_LEN = EditorContext.MAX_CODE_POINTS
        private const val DEFAULT_CONVERSION_DEBOUNCE_MS = 50L
        private const val CURSOR_MOVE_CONVERSION_DEBOUNCE_MS = 140L
        // Autorepeat moves every 50 ms and editor callbacks can trail the write,
        // so a self-issued caret is trusted for a little longer than one tick.
        private const val SELF_CURSOR_MOVE_WINDOW_MS = 400L

        fun hasTrailingIncompleteRomaji(readingHiragana: String): Boolean {
            return readingHiragana.hasPendingRomaji()
        }

        /**
         * Chars that commit the current composition immediately (shifted symbols).
         * Punctuation and digits are excluded: punctuation joins the reading like the
         * Windows client's default `punctuation_commit = false`, and digits follow the
         * Windows `UserAction::Number -> AppendText` path, so conversion includes them.
         */
        fun isImmediateCommitChar(ch: Char): Boolean = InputPolicy.isImmediateCommitChar(ch)

        /** Translation mode also reflects digits and punctuation into the reading as literals. */
        fun isTranslationLiteralChar(ch: Char): Boolean = InputPolicy.isTranslationLiteralChar(ch)

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

        fun recordInferenceSnapshot(history: MutableList<InferenceSnapshot>, snapshot: InferenceSnapshot) {
            val index = history.indexOfFirst { it.readingHiragana.length == snapshot.readingHiragana.length ||
                (snapshot.rawInputCount > 0 && it.rawInputCount == snapshot.rawInputCount) }
            if (index >= 0) history[index] = snapshot else history.add(snapshot)
            while (history.size > 128) history.removeAt(0)
        }
    }

    val isRealtimeInlineState = mutableStateOf(false)
    val autoCommitModeState = mutableStateOf(AutoCommitMode.STANDARD)
    val isAutoCommitEnabledState = mutableStateOf(true)
    val isAutoCommitRewindState = mutableStateOf(false)
    val aiProfileState = mutableStateOf("")
    val themeModeState = mutableStateOf("system")
    val keyboardThemeIdState = mutableStateOf("system")
    val customThemeRevisionState = androidx.compose.runtime.mutableLongStateOf(0L)
    val japaneseLayoutState = mutableStateOf(JapaneseInputLayout.FLICK_12KEY)
    val flickLayoutProfileState = mutableStateOf(com.zeroconvert.ime.layout.KeyboardLayoutProfile.ZEROCON)
    val qwertyLayoutProfileState = mutableStateOf(com.zeroconvert.ime.layout.KeyboardLayoutProfile.ZEROCON)
    val keyboardKeyHeightState = mutableStateOf(46)
    val isFlickOnlyState = mutableStateOf(true)
    val isQwertyNumberRowState = mutableStateOf(true)
    val isFollowInputTypeModeState = mutableStateOf(false)
    // Owned by the service so onStartInputView can seed the mode for the focused editor.
    val keyboardModeState = mutableStateOf(KeyboardMode.JAPANESE)
    // Bumped for every input view session so open panels do not hide the keys in a new one.
    val inputViewEpochState = mutableIntStateOf(0)
    // imeOptions of the focused editor, mirrored into state so the Enter key label follows
    // field changes inside one input view (onStartInput does not bump the epoch).
    val enterKeyImeOptionsState = mutableIntStateOf(0)
    val isVibrationEnabledState = mutableStateOf(true)
    val vibrationDurationState = mutableStateOf(15)
    val isKeyPreviewEnabledState = mutableStateOf(true)
    val flickSensitivityDpState = mutableStateOf(18)
    val isKeyBordersEnabledState = mutableStateOf(true)
    val oneHandedModeState = mutableStateOf(OneHandedMode.OFF)
    val oneHandedWidthPercentState = mutableIntStateOf(OneHandedLayout.DEFAULT_WIDTH_PERCENT)
    val isSplitKeyboardEnabledState = mutableStateOf(false)
    val splitGapPercentState = mutableIntStateOf(QwertySplitLayout.DEFAULT_GAP_PERCENT)
    val splitDuplicateKeysState = mutableStateOf(QwertySplitLayout.DEFAULT_DUPLICATE_MIDDLE_KEYS)
    val keyboardBottomPaddingDpState = mutableStateOf(0)
    private val floatingKeyboard by lazy {
        FloatingKeyboardState(getSharedPreferences("keyboard_placement", Context.MODE_PRIVATE))
    }
    private var floatingKeyboardHost: FloatingKeyboardHost? = null
    val clipboardEditSessionState = mutableStateOf<ClipboardEditSession?>(null)
    private var clipboardEditOverlayHeightPx = 0
    private var handlingClipboardEditSelection = false
    val isDynamicTouchAssistEnabledState = mutableStateOf(true)
    val isTouchTypoCorrectionEnabledState = mutableStateOf(true)
    val isAiAssistEnabledState = mutableStateOf(true)
    val autoTypoCorrectionStrengthState = mutableStateOf(AutoTypoCorrectionStrength.STANDARD)
    val isDictionaryTypoCorrectionState = mutableStateOf(DEFAULT_DICTIONARY_TYPO_CORRECTION)
    val isPredictiveConversionState = mutableStateOf(true)
    val isPostCompositionPredictionState = mutableStateOf(DEFAULT_POST_COMPOSITION_PREDICTION)
    val isEnglishSpaceAdoptsCandidateState = mutableStateOf(false)
    val isAlwaysHalfwidthSpaceState = mutableStateOf(false)
    val predictiveModelState = mutableStateOf("zerocon")
    val aiAssistResultState = mutableStateOf<com.zeroconvert.ime.assist.AiAssistResult?>(null)
    val aiAssistAdoptionState = mutableStateOf(AiAssistAdoption.None)
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
    private var translationReadyCheckJob: Job? = null
    private var committingTranslation = false

    fun isRealtimeInlineEnabled(): Boolean = isRealtimeInlineState.value
    fun isAiAssistEnabled(): Boolean = isAiAssistEnabledState.value
    fun isPredictiveConversionEnabled(): Boolean = isPredictiveConversionState.value
    fun isPostCompositionPredictionEnabled(): Boolean = isPostCompositionPredictionState.value
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
        aiAssistAdoptionState.value = AiAssistAdoption.None
        lastAutoCommitRewind = null
        canRewindOnNextBackspace = false
        undoHistory.clear()
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
        if (composingText.isEmpty) {
            val space = SpaceInputPolicy.spaceText(
                mode = keyboardModeState.value,
                alwaysHalfwidth = isAlwaysHalfwidthSpaceState.value
            )
            commitDirect(space)
        } else {
            confirmCurrent()
        }
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

    fun copySelectedText() {
        val connection = currentInputConnection ?: return
        val isPassword = currentInputEditorInfo?.let {
            InputPrivacy.isPasswordInput(it.inputType)
        } ?: false
        if (!isPassword) {
            val selected = connection.getSelectedText(0)?.toString()
            if (!selected.isNullOrEmpty()) {
                val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (manager != null) {
                    manager.setPrimaryClip(android.content.ClipData.newPlainText("selection", selected))
                    Toast.makeText(applicationContext, "コピーしました", Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
        if (connection.performContextMenuAction(android.R.id.copy)) {
            Toast.makeText(applicationContext, "コピーしました", Toast.LENGTH_SHORT).show()
        }
    }

    fun selectAllText() {
        val connection = currentInputConnection ?: return
        if (isTranslationMode()) {
            exitTranslationMode(revert = false)
        }
        if (!composingText.isEmpty || englishComposing.isNotEmpty()) {
            connection.finishComposingText()
            clearInput()
        }
        if (!connection.performContextMenuAction(android.R.id.selectAll)) {
            val meta = android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
            connection.sendKeyEvent(
                android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_A, 0, meta)
            )
            connection.sendKeyEvent(
                android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_A, 0, meta)
            )
        }
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
                    (results.firstOrNull { it.assist == null && it.coversReading(japaneseSource) }
                        ?: results.firstOrNull { it.assist == null }
                        ?: results.firstOrNull())
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
                translationToast("翻訳を更新しています。もう一度Enterを押してください")
            }
            state.status == TranslationStatus.TRANSLATING ||
                state.status == TranslationStatus.LOADING -> {
                translationToast("翻訳を待っています。翻訳ボタンで取消できます")
            }
            composingText.isEmpty && englishComposing.isEmpty() -> {
                exitTranslationMode(revert = false)
                sendKeyChar('\n')
            }
            else -> {
                translationToast(state.message ?: "翻訳を確定できません。入力をやり直してください")
            }
        }
    }

    private fun commitTranslation(text: String) {
        val connection = currentInputConnection ?: return
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
        englishCursorPosition = 0
        japaneseTouchBiases.clear()
        englishTouchBiases.clear()
        syncComposingDisplayState()
        candidatesList.clear()
        predictedCandidate.value = ""
        lastInferredSurface = ""
        lastInferredResult = ""
        inferenceHistory.clear()
        lastAutoCommitRewind = null
        canRewindOnNextBackspace = false
        corrections.clear()
        reconverting = false
        savedOriginalCandidates = emptyList()
        savedOriginalSurface = ""
        savedOriginalReading = ""
        savedOriginalResult = ""
        savedOriginalInferredReading = ""
        aiAssistAdoptionState.value = AiAssistAdoption.None
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
            val insertAt = ComposingEditorState.snapBoundary(
                englishComposing,
                englishCursorPosition.coerceIn(0, englishComposing.length)
            )
            englishComposing = englishComposing.substring(0, insertAt) + text + englishComposing.substring(insertAt)
            englishCursorPosition = ComposingEditorState.snapBoundary(englishComposing, insertAt + text.length)
            syncComposingDisplayState()
            setEnglishComposingText(connection)
        } else {
            composingText.insertAtCursorPosition(text, InputStyle.DIRECT)
            syncComposingDisplayState()
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
        if (isSensitiveInput() || isConfirming) return
        val enabled = when (assist.type) {
            com.zeroconvert.ime.assist.AiAssistType.TYPO_CORRECTION -> isDictionaryTypoCorrectionState.value
            com.zeroconvert.ime.assist.AiAssistType.PREDICTION -> isAiAssistEnabled()
        }
        if (!enabled) return
        val ticket = displayedAssistTicket ?: return
        if (assistInputConnection !== ic || !requestGate.isCurrent(ticket, requestKey()) || !editorContextIsCurrent()) {
            cancelPendingInference(edited = false)
            return
        }
        if (!explicit && (!assist.autoApply || !autoTypoCorrectionStrengthState.value.isEnabled || !composingText.isAtEndIndex ||
                hasSelectionInEditor || corrections.isApplied || assist.sourceReading != composingText.convertTargetBeforeCursor)) return
        when (SharedAssistPolicy.adoptionAction(assist, explicit, assist.autoApply, corrections.isApplied)) {
            "replaceComposition" -> {
                val suffix = composingText.convertTarget.drop(composingText.convertTargetCursorPosition)
                val preview = if (isRealtimeInlineEnabled()) {
                    predictedCandidate.value.ifEmpty { composingText.convertTargetBeforeCursor } + suffix
                } else composingText.convertTarget
                savedOriginalSurface = preview
                savedOriginalReading = composingText.convertTargetBeforeCursor
                savedOriginalCandidates = candidatesList.filter { it.assist == null }
                savedOriginalResult = lastInferredResult
                savedOriginalInferredReading = lastInferredSurface
                val currentBiases = if (isSensitiveInput()) emptyList() else japaneseTouchBiases.toList()
                cancelPendingInference(edited = false)
                if (!corrections.apply(composingText, assist.text, preview, explicit = explicit, currentBiases = currentBiases)) return
                if (!setComposingPreview(ic, assist.text + suffix)) {
                    corrections.revert(composingText)
                    return
                }
                japaneseTouchBiases.clear()
                if (composingText.isAtEndIndex && !isSensitiveInput()) {
                    val neutral = composingText.input.mapNotNull {
                        (it.piece as? InputPiece.CharPiece)?.char?.let(::TouchKeyBias)
                    }
                    if (neutral.size == composingText.input.size) {
                        japaneseTouchBiases.addAll(neutral)
                    }
                }
                syncComposingDisplayState()
                val readingOnly = CorrectionSession.isReadingOnlyCorrection(assist.text)
                if (readingOnly) {
                    predictedCandidate.value = ""
                    lastInferredSurface = ""
                    lastInferredResult = ""
                } else {
                    predictedCandidate.value = assist.text
                    lastInferredSurface = composingText.convertTargetBeforeCursor
                    lastInferredResult = assist.text
                }
                candidatesList.clear()
                candidatesList.add(Candidate(assist.text, savedOriginalReading))
                inferenceHistory.clear()
                aiAssistAdoptionState.value = if (explicit) AiAssistAdoption.Explicit else AiAssistAdoption.Automatic
                pendingConversionContinuation = null
                triggerDebouncedConversion(
                    composingText.convertTargetBeforeCursor,
                    debounceMs = if (readingOnly) 0L else DEFAULT_CONVERSION_DEBOUNCE_MS
                )
            }
            "commitText" -> {
                commitText(assist.text)
            }
        }
    }

    fun selectCandidate(candidate: Candidate) {
        if (candidate !in candidatesList) return
        if (candidate.isReadingCompletion &&
            displayedConversionTicket?.let(requestGate::isGenerationCurrent) != true) return
        if (candidate.enginePostCompositionId.isNotEmpty()) {
            selectPostCompositionCandidate(candidate)
            return
        }
        if (conversionRange.active && candidate !in ConversionRangeSession.candidatesForRange(composingText, candidatesList)) return
        if (candidate.assist != null) {
            aiAssistResultState.value = candidate.assist
            adoptAiAssist()
        } else commitText(candidate.text, isExplicitSelection = true, committedCandidate = candidate)
    }

    private fun selectPostCompositionCandidate(candidate: Candidate) {
        if (isConfirming || isTranslationMode() || !composingText.isEmpty || englishComposing.isNotEmpty()) {
            return
        }
        val connection = currentInputConnection ?: return
        val sensitive = isSensitiveInput()
        val expected = postCompositionExpectedSurface
        val beforeCommit = if (!sensitive) {
            readEditorLeftContext(connection, ComposingEditorState.PreviewCursor("", 0))
        } else null
        if (!sensitive && expected.isNotEmpty() && beforeCommit != null) {
            val expectedTail = expected.takeLast(minOf(expected.length, MAX_LEFT_CONTEXT_LEN))
            if (!beforeCommit.endsWith(expectedTail)) {
                clearPostCompositionDisplay()
                return
            }
        }
        val contextBeforeSelection = beforeCommit ?: expected
        if (!connection.commitText(candidate.text, 1)) return
        canRewindOnNextBackspace = false
        lastAutoCommitRewind = null

        val postCompEnabled = isPostCompositionPredictionEnabled()
        val nextExpected = (expected + candidate.text).takeLast(MAX_LEFT_CONTEXT_LEN)
        postCompositionJob?.cancel()
        candidatesList.clear()
        if (sensitive || !postCompEnabled) {
            clearPostCompositionDisplay()
            return
        }
        val generation = ++postCompositionGeneration
        postCompositionExpectedSurface = nextExpected
        postCompositionInputConnection = connection
        val predictionId = candidate.enginePostCompositionId
        val allowLearning = LearningPolicy.canWrite
        postCompositionJob = serviceScope.launch(Dispatchers.IO) {
            val result = com.zeroconvert.ime.engine.WindowsEngine.selectPostCompositionPrediction(
                predictionId = predictionId,
                context = contextBeforeSelection,
                allowLearning = allowLearning,
                postCompositionPrediction = postCompEnabled
            )
            withContext(Dispatchers.Main) {
                if (generation != postCompositionGeneration ||
                    connection !== currentInputConnection ||
                    !composingText.isEmpty ||
                    englishComposing.isNotEmpty() ||
                    isConfirming ||
                    isTranslationMode() ||
                    isSensitiveInput() ||
                    !isPostCompositionPredictionEnabled()
                ) {
                    return@withContext
                }
                if (!result.accepted || result.terminal || result.predictions.isEmpty()) {
                    clearPostCompositionDisplay()
                    return@withContext
                }
                val afterCommit = readEditorLeftContext(connection, ComposingEditorState.PreviewCursor("", 0))
                val tailCheck = result.leftSurface.takeLast(minOf(result.leftSurface.length, MAX_LEFT_CONTEXT_LEN))
                if (afterCommit != null && tailCheck.isNotEmpty() && !afterCommit.endsWith(tailCheck)) {
                    clearPostCompositionDisplay()
                    return@withContext
                }
                postCompositionExpectedSurface = result.leftSurface.ifEmpty { nextExpected }
                candidatesList.clear()
                candidatesList.addAll(result.predictions)
            }
        }
    }

    /**
     * Deletes the learning behind one candidate bar item.
     *
     * The engine owns both the dictionary entry and the selection override, so the
     * candidate id is all this needs. After deletion the reading is reconverted:
     * the ranking changed, and leaving the old list would offer the stale order.
     */
    fun forgetCandidateLearning(candidate: Candidate) {
        if (isSensitiveInput()) return
        val id = candidate.engineCandidateId.takeIf { it.isNotEmpty() } ?: return
        val context = applicationContext
        if (EnglishWordDictionary.parseEnglishCandidateId(id) != null) {
            val activePrefix = englishComposing
            serviceScope.launch(Dispatchers.IO) {
                val forgot = EnglishWordDictionary.forgetWord(candidate.text, candidate.reading)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (forgot) "学習を削除しました" else "この候補の学習は削除できませんでした",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (forgot && englishComposing == activePrefix && activePrefix.isNotEmpty()) {
                        candidatesList.removeAll { it.itemKey == candidate.itemKey }
                        triggerEnglishSuggestions(activePrefix)
                    }
                }
            }
            return
        }
        val reading = composingText.convertTargetBeforeCursor
        serviceScope.launch(Dispatchers.IO) {
            val forgot = com.zeroconvert.ime.engine.WindowsEngine.forgetCandidate(id)
            if (candidate.assist?.type == com.zeroconvert.ime.assist.AiAssistType.TYPO_CORRECTION) {
                com.zeroconvert.ime.assist.TypoLearning.forget(context, candidate.reading, candidate.text)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (forgot) "学習を削除しました" else "この候補の学習は削除できませんでした",
                    Toast.LENGTH_SHORT
                ).show()
                if (forgot && reading.isNotEmpty()) {
                    candidatesList.removeAll { it.itemKey == candidate.itemKey }
                    triggerDebouncedConversion(reading)
                }
            }
        }
    }

    private fun syncCorrectionRevertState() {
        corrections.reconcileWithInput(composingText)
        aiAssistAdoptionState.value = corrections.adoption
    }

    fun revertAiAssist() {
        performRevertAiAssist()
    }

    private fun performRevertAiAssist(): Boolean {
        if (!corrections.canRevert) return false
        clearPostCompositionDisplay()
        val ic = currentInputConnection ?: return false
        val immediate = corrections.isApplied && !corrections.isRefined
        val revertedTarget = corrections.previewRevertedTarget(composingText) ?: run {
            syncCorrectionRevertState()
            return false
        }
        cancelPendingInference(edited = false)
        // Only restore the internal snapshot after the editor accepted the undo.
        if (!setComposingPreview(ic, revertedTarget)) return false
        if (corrections.revert(composingText) == null) {
            syncCorrectionRevertState()
            return false
        }
        syncComposingDisplayState()
        if (immediate) {
            predictedCandidate.value = savedOriginalCandidates.firstOrNull()?.text.orEmpty()
            lastInferredSurface = savedOriginalInferredReading
            lastInferredResult = savedOriginalResult
            candidatesList.clear()
            candidatesList.addAll(savedOriginalCandidates)
            displayedConversionTicket = requestGate.capture(requestKey())
        } else {
            japaneseTouchBiases.clear()
            predictedCandidate.value = ""
            lastInferredSurface = ""
            lastInferredResult = ""
            candidatesList.clear()
            displayedConversionTicket = null
        }
        inferenceHistory.clear()
        aiAssistAdoptionState.value = AiAssistAdoption.None
        aiAssistResultState.value = null
        pendingConversionContinuation = null
        if (!immediate && composingText.convertTargetBeforeCursor.isNotEmpty()) {
            triggerDebouncedConversion(composingText.convertTargetBeforeCursor, debounceMs = 0L)
        }
        return true
    }

    private fun performAutoRevertAiAssist(expectedSourceReading: String): Boolean {
        if (isConfirming || isTranslationMode() || isSensitiveInput() || hasSelectionInEditor) return false
        val ic = currentInputConnection ?: return false
        if (!corrections.canRevert || corrections.isApplied || !composingText.isAtEndIndex) return false
        if (expectedSourceReading != composingText.convertTargetBeforeCursor) return false
        val biasesSnapshot = japaneseTouchBiases.toList()
        val previewCtx = corrections.buildAutoRefinementContext(composingText, biasesSnapshot) ?: return false
        cancelPendingInference(edited = false)
        if (!setComposingPreview(ic, previewCtx.originInput.convertTarget)) {
            syncCorrectionRevertState()
            return false
        }
        val (revertedReading, revertedBiases) = corrections.autoRevert(composingText, biasesSnapshot) ?: run {
            syncCorrectionRevertState()
            return false
        }
        syncComposingDisplayState()
        japaneseTouchBiases.clear()
        val charCount = composingText.input.count { it.piece is InputPiece.CharPiece }
        if (revertedBiases.size == charCount) {
            japaneseTouchBiases.addAll(revertedBiases)
        }
        predictedCandidate.value = ""
        lastInferredSurface = ""
        lastInferredResult = ""
        candidatesList.clear()
        displayedConversionTicket = null
        inferenceHistory.clear()
        aiAssistAdoptionState.value = AiAssistAdoption.None
        aiAssistResultState.value = null
        pendingConversionContinuation = null
        if (revertedReading.isNotEmpty()) {
            triggerDebouncedConversion(revertedReading, debounceMs = 0L, suppressCorrection = true)
        }
        return true
    }

    fun addClipboardEntry(entry: ClipboardEntry) {
        appendClipboardEntries(listOf(entry), triggerImageCleanup = entry.isImage)
    }

    private fun appendClipboardEntries(entries: List<ClipboardEntry>, triggerImageCleanup: Boolean = false) {
        if (isPrivateMode) return
        if (!ClipboardHistoryPolicy.upsertEntries(clipboardHistory, entries)) return
        val touchedImage = triggerImageCleanup || entries.any { it.isImage }
        trimClipboardHistory(cleanupImages = touchedImage)
        // Only the newly copied or promoted items become visible again; other dismissed items stay hidden.
        dismissedClipboardSuggestionIds.value = ClipboardHistoryPolicy.clearDismissedForEntries(
            history = clipboardHistory,
            upsertedEntries = entries,
            dismissedIds = dismissedClipboardSuggestionIds.value
        )
    }

    fun removeClipboardEntry(entry: ClipboardEntry) {
        val idx = clipboardHistory.indexOfFirst { it.id == entry.id }
        if (idx < 0) return
        val removed = clipboardHistory.removeAt(idx)
        dismissedClipboardSuggestionIds.value = dismissedClipboardSuggestionIds.value - removed.id
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
     * Routes IME typing into [ClipboardEditSession] directly inside the IME window so editing a
     * clipboard entry keeps the keyboard continuously visible without switching Activity focus.
     */
    fun beginClipboardEdit(entry: ClipboardEntry) {
        val text = entry.text ?: return
        if (isTranslationMode()) exitTranslationMode(revert = false)
        clearInput()
        super.getCurrentInputConnection()?.finishComposingText()
        undoHistory.clear()
        clipboardEditSessionState.value = ClipboardEditSession(
            entry = entry,
            initialText = text,
            onSelectionChanged = ::notifyClipboardEditSelection
        )
    }

    fun saveClipboardEdit() {
        val session = clipboardEditSessionState.value ?: return
        val newText = session.currentText
        closeClipboardEditSession()
        updateClipboardEntry(session.entry, newText)
    }

    fun cancelClipboardEdit() {
        if (clipboardEditSessionState.value == null) return
        closeClipboardEditSession()
    }

    private fun closeClipboardEditSession() {
        if (isTranslationMode()) exitTranslationMode(revert = false)
        clearInput()
        undoHistory.clear()
        clipboardEditOverlayHeightPx = 0
        clipboardEditSessionState.value = null
        floatingKeyboardHost?.requestLayout()
    }

    fun setClipboardEditOverlayHeightPx(heightPx: Int) {
        val normalized = heightPx.coerceAtLeast(0)
        if (clipboardEditOverlayHeightPx == normalized) return
        clipboardEditOverlayHeightPx = normalized
        floatingKeyboardHost?.requestLayout()
    }

    fun clearClipboardHistory(keepPinned: Boolean = true) {
        try {
            val remaining = if (keepPinned) clipboardHistory.filter { it.pinned } else emptyList()
            clipboardHistory.clear()
            clipboardHistory.addAll(remaining)
            val remainingIds = remaining.mapTo(HashSet(remaining.size)) { it.id }
            dismissedClipboardSuggestionIds.value = dismissedClipboardSuggestionIds.value.intersect(remainingIds)
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
    private var pendingScreenshotScan = false
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
        pendingScreenshotScan = false
    }

    private fun scheduleScreenshotCapture(immediate: Boolean = false, force: Boolean = false) {
        if (isPrivateMode) return
        if (!force && !isInputViewShown) {
            pendingScreenshotScan = true
            return
        }
        pendingScreenshotScan = false
        screenshotCaptureJob?.cancel()
        screenshotCaptureJob = serviceScope.launch {
            // Screenshot providers often emit several MediaStore changes for one capture.
            if (!immediate) delay(400)
            val cutoffSeconds = maxOf(lastScreenshotCheck / 1000 - 5, lastPrivateInputEndMillis / 1000 + 1)
            lastScreenshotCheck = System.currentTimeMillis()
            val entries = withContext(Dispatchers.IO) { collectRecentScreenshots(cutoffSeconds) }
            if (entries.isNotEmpty()) {
                appendClipboardEntries(entries, triggerImageCleanup = true)
            }
        }
    }

    private suspend fun collectRecentScreenshots(cutoffSeconds: Long): List<ClipboardEntry> {
        if (!getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).getBoolean(KEY_SCREENSHOT_CAPTURE, true)) {
            return emptyList()
        }
        val entries = mutableListOf<ClipboardEntry>()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val selectionArgs = arrayOf(cutoffSeconds.toString())
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
                    currentCoroutineContext().ensureActive()
                    val name = cursor.getString(nameCol) ?: continue
                    val relPath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val isScreenshot = name.contains("Screenshot", ignoreCase = true) ||
                        name.contains("スクリーンショット", ignoreCase = true) ||
                        (relPath != null && relPath.contains("creenshot", ignoreCase = true))
                    if (!isScreenshot) continue
                    val id = cursor.getLong(idCol)
                    if (!seenScreenshotIds.add(id)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    currentCoroutineContext().ensureActive()
                    val cached = cacheClipboardImage(uri)
                    entries += ClipboardEntry(imageUri = cached ?: uri)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot capture failed: ${e.message}")
        }
        return entries
    }

    private fun trimClipboardHistory(cleanupImages: Boolean = false) {
        var removedImage = false
        while (clipboardHistory.count { !it.pinned } > 20) {
            val idx = clipboardHistory.indexOfLast { !it.pinned }
            if (idx >= 0) {
                if (clipboardHistory[idx].isImage) removedImage = true
                clipboardHistory.removeAt(idx)
            } else break
        }
        if (cleanupImages || removedImage) cleanupClipboardImageCache()
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
        if (isSensitiveInput()) return
        val updated = EmojiCatalog.pushRecent(recentEmojiState.value, emoji)
        if (updated == recentEmojiState.value) return
        recentEmojiState.value = updated
        getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).edit()
            .putString(KEY_EMOJI_RECENT, updated.joinToString("\n")).apply()
    }

    fun pasteClipboard(entry: ClipboardEntry, fromSuggestion: Boolean = false) {
        if (isPrivateMode) return
        if (!composingText.isEmpty || englishComposing.isNotEmpty()) {
            confirmCurrent()
        }
        clearPostCompositionDisplay()
        val ic = currentInputConnection ?: return
        if (entry.text != null) {
            ic.commitText(entry.text, 1)
        } else if (entry.imageUri != null) {
            commitClipboardImage(ic, entry.imageUri)
        }
        if (fromSuggestion) {
            // Dismiss only the pasted suggestion so remaining clipboard suggestions stay visible.
            dismissedClipboardSuggestionIds.value = dismissedClipboardSuggestionIds.value + entry.id
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

    fun autoCommitMode(): AutoCommitMode = autoCommitModeState.value
    fun isAutoCommitEnabled(): Boolean = autoCommitModeState.value.isEnabled
    fun isAutoCommitRewindEnabled(): Boolean = isAutoCommitRewindState.value
    fun isFlickOnly(): Boolean = isFlickOnlyState.value

    fun setJapaneseLayout(layout: JapaneseInputLayout) {
        japaneseLayoutState.value = layout
        val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_JAPANESE_LAYOUT, layout.name).apply()
    }

    fun setOneHandedMode(mode: OneHandedMode) {
        oneHandedModeState.value = mode
        val prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ONE_HANDED_MODE, mode.name).apply()
    }

    internal fun setKeyboardPlacementMode(mode: KeyboardPlacementMode) {
        val oneHanded = if (mode == KeyboardPlacementMode.ONE_HANDED) OneHandedMode.RIGHT else OneHandedMode.OFF
        val split = mode == KeyboardPlacementMode.SPLIT
        oneHandedModeState.value = oneHanded
        isSplitKeyboardEnabledState.value = split
        getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ONE_HANDED_MODE, oneHanded.name)
            .putBoolean(KEY_SPLIT_KEYBOARD, split)
            .apply()
        val floating = mode == KeyboardPlacementMode.FLOATING
        if (floatingKeyboard.isFloating != floating) setFloatingKeyboard(floating)
    }

    fun nextKeyWeightsForCurrentInput(mode: KeyboardMode): Map<Char, Float> {
        if (!isDynamicTouchAssistEnabledState.value || isSensitiveInput() || isTranslationMode()) {
            return emptyMap()
        }
        return when (mode) {
            KeyboardMode.JAPANESE -> {
                val pending = NextKeyPredictor.trailingPendingRomaji(composingText.convertTargetBeforeCursor)
                NextKeyPredictor.japaneseRomajiWeights(pending)
            }
            KeyboardMode.ENGLISH -> {
                val prefix = if (englishComposing.isNotEmpty()) englishComposing else composingText.convertTargetBeforeCursor
                if (prefix.isEmpty()) emptyMap() else EnglishWordDictionary.nextCharWeights(prefix)
            }
            KeyboardMode.NUMERIC -> emptyMap()
        }
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings from IME", e)
        }
    }

    private var sharedPreferences: android.content.SharedPreferences? = null
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        val reload = {
            when (key) {
                KEY_ONE_HANDED_MODE, KEY_ONE_HANDED_WIDTH_PERCENT, KEY_SPLIT_KEYBOARD,
                KEY_SPLIT_GAP_PERCENT, KEY_SPLIT_DUPLICATE_KEYS ->
                    loadKeyboardPlacementPreferences(prefs)
                KEY_KEYBOARD_THEME_ID, KEY_THEME_MODE, KEY_CUSTOM_THEME_REVISION,
                KEY_LAYOUT_PROFILE_FLICK, KEY_LAYOUT_PROFILE_QWERTY,
                KEY_KEY_HEIGHT_DP, KEY_KEY_PREVIEW_ENABLED, KEY_KEY_BORDERS_ENABLED,
                KEY_KEYBOARD_BOTTOM_PADDING_DP ->
                    loadAppearancePreferences(prefs)
                else -> reloadPreferences()
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            reload()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                reload()
            }
        }
    }

    private fun loadAppearancePreferences(prefs: android.content.SharedPreferences) {
        val resolvedKeyboardTheme = com.zeroconvert.ime.theme.ThemePreferences.readKeyboardThemeId(prefs)
        keyboardThemeIdState.value = resolvedKeyboardTheme
        themeModeState.value = resolvedKeyboardTheme
        customThemeRevisionState.longValue = prefs.getLong(KEY_CUSTOM_THEME_REVISION, 0L)
        keyboardKeyHeightState.value = prefs.getInt(KEY_KEY_HEIGHT_DP, 46).coerceIn(40, 64)
        isKeyPreviewEnabledState.value = prefs.getBoolean(KEY_KEY_PREVIEW_ENABLED, true)
        isKeyBordersEnabledState.value = prefs.getBoolean(KEY_KEY_BORDERS_ENABLED, true)
        keyboardBottomPaddingDpState.value = prefs.getInt(KEY_KEYBOARD_BOTTOM_PADDING_DP, 0).coerceIn(0, 32)
        flickLayoutProfileState.value = com.zeroconvert.ime.layout.LayoutProfilePreferences.readFlickProfile(prefs)
        qwertyLayoutProfileState.value = com.zeroconvert.ime.layout.LayoutProfilePreferences.readQwertyProfile(prefs)
    }

    private fun loadKeyboardPlacementPreferences(prefs: android.content.SharedPreferences) {
        oneHandedModeState.value = OneHandedMode.fromName(prefs.getString(KEY_ONE_HANDED_MODE, OneHandedMode.OFF.name))
        oneHandedWidthPercentState.intValue = OneHandedLayout.clampWidthPercent(
            prefs.getInt(KEY_ONE_HANDED_WIDTH_PERCENT, OneHandedLayout.DEFAULT_WIDTH_PERCENT)
        )
        isSplitKeyboardEnabledState.value = prefs.getBoolean(KEY_SPLIT_KEYBOARD, false)
        splitGapPercentState.intValue = QwertySplitLayout.clampGapPercent(
            prefs.getInt(KEY_SPLIT_GAP_PERCENT, QwertySplitLayout.DEFAULT_GAP_PERCENT)
        )
        splitDuplicateKeysState.value = prefs.getBoolean(
            KEY_SPLIT_DUPLICATE_KEYS, QwertySplitLayout.DEFAULT_DUPLICATE_MIDDLE_KEYS
        )
    }

    private fun reloadPreferences() {
        cancelPendingInference(edited = false)
        val prefs = sharedPreferences ?: getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        isRealtimeInlineState.value = prefs.getBoolean(KEY_REALTIME_INLINE, true)
        val autoCommitMode = AutoCommitMode.readFrom(prefs, KEY_AUTO_COMMIT_MODE, KEY_AUTO_COMMIT_ENABLED)
        autoCommitModeState.value = autoCommitMode
        isAutoCommitEnabledState.value = autoCommitMode.isEnabled
        isAutoCommitRewindState.value = prefs.getBoolean(KEY_AUTO_COMMIT_REWIND_ON_BACKSPACE, false)
        aiProfileState.value = prefs.getString(KEY_AI_PROFILE, "") ?: ""
        loadAppearancePreferences(prefs)
        val layoutName = prefs.getString(KEY_JAPANESE_LAYOUT, JapaneseInputLayout.FLICK_12KEY.name)
        japaneseLayoutState.value = try {
            JapaneseInputLayout.valueOf(layoutName ?: JapaneseInputLayout.FLICK_12KEY.name)
        } catch (e: Exception) {
            JapaneseInputLayout.FLICK_12KEY
        }
        isFlickOnlyState.value = prefs.getBoolean(KEY_FLICK_ONLY, true)
        isQwertyNumberRowState.value = prefs.getBoolean(KEY_QWERTY_NUMBER_ROW, false)
        isFollowInputTypeModeState.value = prefs.getBoolean(KEY_FOLLOW_INPUT_TYPE_MODE, false)
        isVibrationEnabledState.value = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        vibrationDurationState.value = prefs.getInt(KEY_VIBRATION_DURATION, 15)
        flickSensitivityDpState.value = prefs.getInt(KEY_FLICK_SENSITIVITY_DP, 18).coerceIn(12, 28)
        loadKeyboardPlacementPreferences(prefs)
        val touchAssist = TouchAssistSettings.load(prefs)
        val touchCorrectionChanged = isTouchTypoCorrectionEnabledState.value != touchAssist.correctionEnabled
        isDynamicTouchAssistEnabledState.value = touchAssist.hitAreaEnabled
        isTouchTypoCorrectionEnabledState.value = touchAssist.correctionEnabled
        isAiAssistEnabledState.value = prefs.getBoolean(KEY_EXPERIMENTAL_AI_ASSIST, true)
        autoTypoCorrectionStrengthState.value = AutoTypoCorrectionStrength.readFrom(
            prefs,
            KEY_AUTO_TYPO_CORRECTION_STRENGTH,
            KEY_AUTO_TYPO_CORRECTION,
        )
        isDictionaryTypoCorrectionState.value = prefs.getBoolean(KEY_DICTIONARY_TYPO_CORRECTION, DEFAULT_DICTIONARY_TYPO_CORRECTION)
        isPredictiveConversionState.value = prefs.getBoolean(KEY_PREDICTIVE_CONVERSION, true)
        isPostCompositionPredictionState.value = prefs.getBoolean(
            KEY_POST_COMPOSITION_PREDICTION,
            DEFAULT_POST_COMPOSITION_PREDICTION
        )
        if (!isPostCompositionPredictionEnabled()) {
            clearPostCompositionDisplay()
        }
        isEnglishSpaceAdoptsCandidateState.value = prefs.getBoolean(KEY_ENGLISH_SPACE_ADOPTS_CANDIDATE, false)
        isAlwaysHalfwidthSpaceState.value = prefs.getBoolean(KEY_ALWAYS_HALFWIDTH_SPACE, false)
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
        translationReadyCheckJob?.cancel()
        if (!translationEnabledState.value) {
            translationModelReadyState.value = false
        } else {
            val appContext = applicationContext
            translationReadyCheckJob = serviceScope.launch {
                val ready = withContext(Dispatchers.IO) { TranslationModelManager.isReady(appContext) }
                if (translationEnabledState.value) {
                    translationModelReadyState.value = ready
                }
            }
        }
        if (!translationEnabledState.value && isTranslationMode()) {
            exitTranslationMode(revert = true)
        } else if (!translationKeepLoadedState.value && !isTranslationMode()) {
            maybeUnloadTranslation()
        }
        if (touchCorrectionChanged && !isTranslationMode()) {
            if (englishComposing.isNotEmpty()) {
                candidatesList.clear()
                predictedCandidate.value = ""
                triggerEnglishSuggestions(englishComposing)
            } else if (!composingText.isEmpty) {
                candidatesList.removeAll { it.assist != null }
                triggerDebouncedConversion(composingText.convertTargetBeforeCursor)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private data class PendingLearning(val reading: String, val surface: String, val english: Boolean,
                                      val selection: ConversionCommitLearning? = null,
                                      val historyRevision: Long = KanaKanjiConverter.learningHistoryRevision,
                                      val englishExplicit: Boolean = false,
                                      val privacyGeneration: Long)
    private val learningQueue = Channel<PendingLearning>(128)
    private val learningWorker = serviceScope.launch(Dispatchers.IO) {
        for (selection in learningQueue) {
            if (!mayWriteLearning(selection.privacyGeneration)) continue
            try {
                if (selection.english) {
                    if (selection.historyRevision == KanaKanjiConverter.learningHistoryRevision) {
                        EnglishWordDictionary.recordCommit(
                            applicationContext,
                            selection.reading,
                            selection.surface,
                            selection.englishExplicit,
                            selection.historyRevision
                        )
                    }
                } else {
                    // Conversion learning belongs to the shared engine: a queued commit
                    // is applied only when it has a live candidate identity and no
                    // history reset happened after the selection.
                    selection.selection?.let { candidate ->
                        com.zeroconvert.ime.engine.WindowsEngine.commit(
                            candidate.engineCandidateId, selection.historyRevision, candidate.explicitSelection
                        ) { mayWriteLearning(selection.privacyGeneration) }
                    }
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
    val currentComposingCursor = mutableStateOf(0)
    val predictedCandidate = mutableStateOf("")
    val candidatesList = mutableStateListOf<Candidate>()
    val selectionAvailableState = mutableStateOf(false)
    val selectedCharacterCountState = mutableStateOf<SelectionCharacterCount?>(null)
    private val selectionCountGate = SelectionCountGate()
    private var selectionCountJob: Job? = null
    private val selectionCountMutex = kotlinx.coroutines.sync.Mutex()
    private var reconverting = false
    val clipboardHistory = mutableStateListOf<ClipboardEntry>()
    val dismissedClipboardSuggestionIds = mutableStateOf<Set<Long>>(emptySet())
    val clipboardRetentionHoursState = mutableStateOf(1)
    val clipboardSuggestionMinutesState = mutableStateOf(ClipboardSuggestionPolicy.DEFAULT_MINUTES)
    val emojiCategoriesState = mutableStateOf<List<EmojiCategory>>(emptyList())
    val recentEmojiState = mutableStateOf<List<String>>(emptyList())

    // English (alphabet) composing buffer for predictive completion
    var englishComposing: String = ""
    // Caret offset inside [englishComposing], in UTF-16 code units. Kept in
    // sync by cursor moves, inserts and deletes so they edit at the caret
    // instead of always appending/deleting at the end.
    var englishCursorPosition: Int = 0

    private val converter = KanaKanjiConverter()
    private var clipboardManager: ClipboardManager? = null
    private var clipListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var inferenceJob: Job? = null
    // AI assist (💡 typo / 🔮 prediction) runs off the main thread: it performs
    // dictionary lookups through JNI, which must never block key handling.
    private var aiAssistJob: Job? = null
    private var cachedLeftContext: String = ""
    private var leftContextConnection: android.view.inputmethod.InputConnection? = null
    // Mirrors Windows `pending_conversion_continuation`: the reading and surface of the
    // prefix committed by a live auto-commit or a partial clause commit. The next
    // debounced/confirm conversion consumes it so the engine can rebuild the full
    // reading instead of converting only the remaining tail.
    private var pendingConversionContinuation: ConversionContinuation? = null
    private val composingEditor = ComposingEditorState()
    private val undoHistory = ImeUndoHistory()
    private val editorComposingPreview: String get() = composingEditor.preview
    // Hardware arrow keys held down: a repeat must not be treated as an external
    // move. On-screen arrows take the same path, so they register too.
    private val managedCursorKeys = mutableSetOf<Int>()
    // Set while this IME is moving the caret itself. Autorepeat fires every 50 ms
    // while the editor's selection callbacks can arrive later, so a short window
    // is more reliable than a per-write queue that overflows.
    private var selfIssuedCursorMoveAt = 0L
    private val requestGate = AssistRequestGate()
    private var displayedConversionTicket: AssistRequestGate.Ticket? = null
    private var displayedAssistTicket: AssistRequestGate.Ticket? = null
    private var assistInputConnection: android.view.inputmethod.InputConnection? = null
    private var postCompositionJob: Job? = null
    private var postCompositionGeneration: Long = 0L
    private var postCompositionExpectedSurface: String = ""
    private var postCompositionInputConnection: android.view.inputmethod.InputConnection? = null

    private fun syncComposingDisplayState() {
        when {
            !composingText.isEmpty -> {
                val target = composingText.convertTarget
                currentComposingSurface.value = target
                currentComposingCursor.value = ComposingEditorState.snapBoundary(
                    target,
                    composingText.convertTargetCursorPosition.coerceIn(0, target.length)
                )
            }
            englishComposing.isNotEmpty() -> {
                currentComposingSurface.value = englishComposing
                currentComposingCursor.value = ComposingEditorState.snapBoundary(
                    englishComposing,
                    englishCursorPosition.coerceIn(0, englishComposing.length)
                )
            }
            else -> {
                currentComposingSurface.value = ""
                currentComposingCursor.value = 0
            }
        }
    }

    private fun setManagedComposingText(connection: android.view.inputmethod.InputConnection?, text: CharSequence, cursor: Int): Boolean {
        if (connection == null || !connection.setComposingText(text, cursor)) return false
        composingEditor.recordPreview(text.toString(), expectTailCursor = true)
        return true
    }

    private fun setEnglishComposingText(connection: android.view.inputmethod.InputConnection?): Boolean {
        if (connection == null) return false
        val text = englishComposing
        val safeCursor = ComposingEditorState.snapBoundary(text, englishCursorPosition.coerceIn(0, text.length))
        if (safeCursor == text.length) return setManagedComposingText(connection, text, 1)
        val written = composingEditor.write(object : ComposingEditorState.Editor {
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
        }, text, safeCursor)
        if (!written) {
            // Without a readable selection the caret cannot be placed mid-word:
            // keep the editor caret at the tail (write already published the
            // text) and snap the internal caret to the same place.
            englishCursorPosition = text.length
            composingEditor.adoptCursor(text.length)
            syncComposingDisplayState()
            return false
        }
        return true
    }

    private fun setComposingPreview(connection: android.view.inputmethod.InputConnection?, text: String): Boolean {
        if (connection == null) return false
        val suffix = composingText.convertTarget.drop(composingText.convertTargetCursorPosition)
        if (!text.endsWith(suffix)) return false
        val position = text.length - suffix.length
        if (composingEditor.matchesPreview(text, position)) {
            return true
        }
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

    private fun readEditorLeftContext(
        connection: android.view.inputmethod.InputConnection? = currentInputConnection,
        previewCursor: ComposingEditorState.PreviewCursor = composingEditor.previewCursor
    ): String? {
        val ic = connection ?: return null
        val before = try {
            ic.getTextBeforeCursor(MAX_LEFT_CONTEXT_LEN * 2 + previewCursor.preview.length, 0)?.toString()
        } catch (_: RuntimeException) {
            null
        }
        return EditorContext.confirmedLeft(before, previewCursor.preview, previewCursor.cursor)
    }

    private fun editorContextIsCurrent(): Boolean = isSensitiveInput() || readEditorLeftContext() == cachedLeftContext

    private fun editorContextMatches(
        connection: android.view.inputmethod.InputConnection,
        expectedContext: String,
        sensitive: Boolean
    ): Boolean {
        if (sensitive) return true
        return readEditorLeftContext(connection, composingEditor.previewCursor) == expectedContext
    }

    private fun requestKey() = AssistRequestGate.Key(
        reading = composingText.convertTarget,
        cursor = composingText.convertTargetCursorPosition,
        context = cachedLeftContext,
        profile = aiProfileState.value,
        modelRevision = ZenzNative.modelRevision,
        assistEnabled = isAiAssistEnabled(),
        autoCorrectionEnabled = autoTypoCorrectionStrengthState.value.isEnabled,
        predictionModel = getPredictiveModel(),
        inputType = currentInputEditorInfo?.inputType ?: 0,
        imeOptions = currentInputEditorInfo?.imeOptions ?: 0,
        rawInput = composingText.input.map { it.copy() },
        vocabularyRevision = com.zeroconvert.ime.assist.AssistVocabulary.revision,
        conversionRange = conversionRange.active,
        learningMode = LearningPolicy.mode.name,
        dictionaryCorrectionEnabled = isDictionaryTypoCorrectionState.value,
        touchCorrectionEnabled = isTouchTypoCorrectionEnabledState.value,
        autoCorrectionStrength = autoTypoCorrectionStrengthState.value.storageValue
    )

    private fun clearPostCompositionDisplay(clearCandidates: Boolean = true) {
        val hadJob = postCompositionJob != null
        postCompositionJob?.cancel()
        postCompositionJob = null
        if (hadJob) {
            ZenzNative.abort()
        }
        postCompositionGeneration++
        postCompositionExpectedSurface = ""
        postCompositionInputConnection = null
        if (clearCandidates && candidatesList.any { it.enginePostCompositionId.isNotEmpty() }) {
            candidatesList.clear()
        }
    }

    private fun triggerPostCompositionPredictions(
        connection: android.view.inputmethod.InputConnection,
        candidateId: String,
        committedText: String,
        committedReading: String,
        context: String
    ) {
        clearPostCompositionDisplay()
        if (!KanaKanjiConverter.isInitialized ||
            isSensitiveInput() ||
            isTranslationMode() ||
            !isPostCompositionPredictionEnabled() ||
            committedText.isEmpty()
        ) {
            return
        }
        val generation = ++postCompositionGeneration
        postCompositionExpectedSurface = committedText
        postCompositionInputConnection = connection
        val postCompEnabled = isPostCompositionPredictionEnabled()
        postCompositionJob = serviceScope.launch(Dispatchers.IO) {
            val result = com.zeroconvert.ime.engine.WindowsEngine.postCompositionPredictions(
                candidateId = candidateId,
                committedText = committedText,
                committedReading = committedReading,
                context = context,
                postCompositionPrediction = postCompEnabled
            )
            withContext(Dispatchers.Main) {
                if (generation != postCompositionGeneration ||
                    connection !== currentInputConnection ||
                    !composingText.isEmpty ||
                    englishComposing.isNotEmpty() ||
                    isConfirming ||
                    isTranslationMode() ||
                    isSensitiveInput() ||
                    !isPostCompositionPredictionEnabled()
                ) {
                    return@withContext
                }
                if (result.predictions.isEmpty()) {
                    postCompositionExpectedSurface = ""
                    postCompositionInputConnection = null
                    return@withContext
                }
                val expectedSurface = result.leftSurface.ifEmpty { committedText }
                val beforeCursor = readEditorLeftContext(connection, ComposingEditorState.PreviewCursor("", 0))
                val tailCheck = expectedSurface.takeLast(minOf(expectedSurface.length, MAX_LEFT_CONTEXT_LEN))
                if (beforeCursor != null && tailCheck.isNotEmpty() && !beforeCursor.endsWith(tailCheck)) {
                    clearPostCompositionDisplay()
                    return@withContext
                }
                postCompositionExpectedSurface = expectedSurface
                candidatesList.clear()
                candidatesList.addAll(result.predictions)
            }
        }
    }

    private fun cancelPendingInference(edited: Boolean = true) {
        clearPostCompositionDisplay()
        requestGate.invalidate()
        displayedConversionTicket = null
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
            aiAssistAdoptionState.value = corrections.adoption
        }
    }

    private val privateModeState = mutableStateOf(false)
    internal val isPrivateMode: Boolean get() = privateModeState.value
    @Volatile private var clipboardPrivacyGeneration = 0L
    private var lastPrivateInputEndMillis = 0L

    private fun isSensitiveInput(): Boolean =
        isPrivateMode ||
            currentInputEditorInfo?.let { InputPrivacy.isSensitive(it.inputType, it.imeOptions) } ?: true

    private fun updateInputPrivacy(info: android.view.inputmethod.EditorInfo?) {
        val privateMode = info != null && InputPrivacy.isPrivateInput(info.inputType, info.imeOptions)
        if (privateMode == isPrivateMode) return
        privateModeState.value = privateMode
        clipboardPrivacyGeneration++
        if (privateMode) {
            screenshotCaptureJob?.cancel()
            screenshotCaptureJob = null
            pendingScreenshotScan = false
        } else {
            // MediaStore notifications and clipboard events can arrive after a focus change.
            lastPrivateInputEndMillis = System.currentTimeMillis()
        }
    }

    private fun mayWriteLearning(generation: Long): Boolean =
        LearningPolicy.canWrite && generation == clipboardPrivacyGeneration && !isPrivateMode

    private fun recordCommittedCorrections(text: String, reading: String = "") {
        if (isSensitiveInput()) return
        val accepted = corrections.committed(CorrectionSession.committedMatchTarget(text, reading))
        if (accepted.isEmpty() || !LearningPolicy.canWrite) return
        val generation = clipboardPrivacyGeneration
        val revision = KanaKanjiConverter.learningHistoryRevision
        val canRecord = { mayWriteLearning(generation) && revision == KanaKanjiConverter.learningHistoryRevision }
        serviceScope.launch(Dispatchers.IO) {
            ConversionCommitLearning.recordCorrections(accepted, canRecord) { typo, surface ->
                com.zeroconvert.ime.assist.TypoLearning.record(applicationContext, typo, surface, canRecord)
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

    /**
     * Moves the internal reading cursor to match a caret the editor reported.
     * Returns false when the offset cannot be mapped, in which case the caller
     * may end the composition.
     *
     * The mapping uses the preview length: a caret at preview offset N
     * corresponds to the reading offset whose converted prefix has preview
     * length N, which is where this IME itself last placed the caret.
     */
    private fun resyncReadingCursorToEditor(
        absoluteCaret: Int,
        candidatesStart: Int = -1,
        candidatesEnd: Int = -1
    ): Boolean {
        val preview = composingEditor.preview
        if (preview.isEmpty()) return false
        val previewOffset = when {
            candidatesStart >= 0 && candidatesEnd >= candidatesStart &&
                candidatesEnd - candidatesStart == preview.length -> {
                val offset = absoluteCaret - candidatesStart
                if (offset !in 0..preview.length) return false
                offset
            }
            composingEditor.composingStartHint != null -> {
                val hint = composingEditor.composingStartHint!!
                val offset = absoluteCaret - hint
                if (offset !in 0..preview.length) return false
                offset
            }
            else -> {
                val range = currentInputConnection?.getExtractedText(
                    android.view.inputmethod.ExtractedTextRequest().apply {
                        hintMaxChars = MAX_LEFT_CONTEXT_LEN * 2 + preview.length
                        hintMaxLines = 1
                    }, 0
                ) ?: return false
                if (range.startOffset < 0 || absoluteCaret < range.startOffset) return false
                val caretInSnippet = absoluteCaret - range.startOffset
                val snippet = range.text?.toString()
                if (snippet != null && !snippet.startsWith(preview)) {
                    // ExtractedText.startOffset is the start of the extracted snippet,
                    // not necessarily the composing span. Locate preview around the caret.
                    val searchStart = (caretInSnippet - preview.length).coerceAtLeast(0)
                    val candidateOrigin = snippet.indexOf(preview, searchStart)
                    if (candidateOrigin < 0 || caretInSnippet < candidateOrigin || caretInSnippet > candidateOrigin + preview.length) {
                        return false
                    }
                    caretInSnippet - candidateOrigin
                } else {
                    if (caretInSnippet !in 0..preview.length) return false
                    caretInSnippet
                }
            }
        }
        val snappedPreviewOffset = ComposingEditorState.snapBoundary(preview, previewOffset)
        val target = readingOffsetForPreviewOffset(snappedPreviewOffset)
        val safeCurrentCursor = ComposingEditorState.snapBoundary(
            composingText.convertTarget,
            composingText.convertTargetCursorPosition
        )
        if (target != composingText.convertTargetCursorPosition) {
            composingText.moveCursorFromCursorPosition(target - composingText.convertTargetCursorPosition)
        } else if (safeCurrentCursor != composingText.convertTargetCursorPosition) {
            composingText.moveCursorFromCursorPosition(safeCurrentCursor - composingText.convertTargetCursorPosition)
        }
        composingEditor.adoptCursor(snappedPreviewOffset)
        syncComposingDisplayState()
        return true
    }

    private fun resyncEnglishCursorToEditor(
        absoluteCaret: Int,
        candidatesStart: Int = -1,
        candidatesEnd: Int = -1
    ): Boolean {
        val preview = englishComposing
        if (preview.isEmpty()) return false
        val previewOffset = when {
            candidatesStart >= 0 && candidatesEnd >= candidatesStart &&
                candidatesEnd - candidatesStart == preview.length -> {
                val offset = absoluteCaret - candidatesStart
                if (offset !in 0..preview.length) return false
                offset
            }
            composingEditor.composingStartHint != null -> {
                val hint = composingEditor.composingStartHint!!
                val offset = absoluteCaret - hint
                if (offset !in 0..preview.length) return false
                offset
            }
            else -> {
                val range = currentInputConnection?.getExtractedText(
                    android.view.inputmethod.ExtractedTextRequest().apply {
                        hintMaxChars = MAX_LEFT_CONTEXT_LEN * 2 + preview.length
                        hintMaxLines = 1
                    }, 0
                ) ?: return false
                if (range.startOffset < 0 || absoluteCaret < range.startOffset) return false
                val caretInSnippet = absoluteCaret - range.startOffset
                val snippet = range.text?.toString()
                if (snippet != null && !snippet.startsWith(preview)) {
                    val searchStart = (caretInSnippet - preview.length).coerceAtLeast(0)
                    val candidateOrigin = snippet.indexOf(preview, searchStart)
                    if (candidateOrigin < 0 || caretInSnippet < candidateOrigin || caretInSnippet > candidateOrigin + preview.length) {
                        return false
                    }
                    caretInSnippet - candidateOrigin
                } else {
                    if (caretInSnippet !in 0..preview.length) return false
                    caretInSnippet
                }
            }
        }
        englishCursorPosition = ComposingEditorState.snapBoundary(preview, previewOffset)
        composingEditor.adoptCursor(englishCursorPosition)
        syncComposingDisplayState()
        return true
    }

    /** Maps a caret offset inside the preview to the reading cursor it represents. */
    private fun readingOffsetForPreviewOffset(previewOffset: Int): Int {
        val reading = composingText.convertTarget
        val preview = composingEditor.preview
        if (preview.isEmpty() || reading.isEmpty()) return 0
        val safePreviewOffset = ComposingEditorState.snapBoundary(preview, previewOffset)
        if (safePreviewOffset <= 0) return 0
        if (safePreviewOffset >= preview.length) return reading.length
        // The tail of the composition is still raw reading, so its length maps
        // one to one. Anything before it belongs to the converted prefix.
        val currentCursor = ComposingEditorState.snapBoundary(reading, composingText.convertTargetCursorPosition)
        val suffix = reading.drop(currentCursor)
        val convertedLength = preview.length - suffix.length
        if (convertedLength >= 0 && safePreviewOffset >= convertedLength) {
            return ComposingEditorState.snapBoundary(reading, currentCursor + (safePreviewOffset - convertedLength))
        }
        // Inside the converted region: fall back to proportional mapping, which
        // is exact for kana-only previews and conservative otherwise.
        val proportional = (safePreviewOffset.toLong() * reading.length / preview.length).toInt()
        return ComposingEditorState.snapBoundary(reading, proportional)
    }

    private fun updateSelectionCharacterCount(start: Int, end: Int) {        selectionCountJob?.cancel()
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

    override fun getCurrentInputConnection(): android.view.inputmethod.InputConnection? {
        clipboardEditSessionState.value?.let { return it }
        return super.getCurrentInputConnection()
    }

    override fun getCurrentInputEditorInfo(): android.view.inputmethod.EditorInfo? {
        clipboardEditSessionState.value?.let { return it.editorInfo }
        return super.getCurrentInputEditorInfo()
    }

    private fun notifyClipboardEditSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        handlingClipboardEditSelection = true
        try {
            onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        } finally {
            handlingClipboardEditSelection = false
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
        if (clipboardEditSessionState.value != null && !handlingClipboardEditSelection) return
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val selected = newSelStart != newSelEnd
        val pendingSelectionTranslation = selectionTranslationState.value
        if (pendingSelectionTranslation != null) {
            val selectedNow = if (selected) currentInputConnection?.getSelectedText(0)?.toString() else null
            if (selectedNow != pendingSelectionTranslation.source) dismissSelectionTranslation()
        }
        composingEditor.observeRange(candidatesStart, candidatesEnd)
        val ownedSelection = composingEditor.ownsSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // A caret we moved ourselves is not an external move, even when the
        // editor reports it after the write has been consumed. Autorepeat can
        // outrun the callbacks, so consult a short window as well as the queue.
        val selfMoveRecent = managedCursorKeys.isNotEmpty() ||
            System.currentTimeMillis() - selfIssuedCursorMoveAt <= SELF_CURSOR_MOVE_WINDOW_MS
        // An external cursor/selection move ends the managed composition. Never
        // apply an automatic correction at a cursor that no longer owns this input.
        val isInsideComposing = candidatesStart >= 0 && candidatesEnd >= candidatesStart &&
            newSelStart >= candidatesStart && newSelEnd <= candidatesEnd
        val isExternalMove = !ownedSelection && !selfMoveRecent && !isInsideComposing &&
            (candidatesStart == -1 || newSelStart < candidatesStart || newSelEnd > candidatesEnd)
        if (!composingText.isEmpty && isExternalMove && !committingTranslation) {
            // A composition must not be finished as raw kana just because a
            // selection callback could not be attributed to this IME. Resync the
            // reading cursor into the reported caret and keep the composition
            // alive; only a move out of the range is treated as external.
            if (isTranslationMode()) exitTranslationMode(revert = false)
            if (!resyncReadingCursorToEditor(newSelStart, candidatesStart, candidatesEnd)) {
                clearInput()
                currentInputConnection?.finishComposingText()
            }
        } else if (englishComposing.isNotEmpty() && composingText.isEmpty && !committingTranslation &&
            !ownedSelection && !selfMoveRecent && !selected
        ) {
            // English phase mirrors the Japanese path: a caret that still maps
            // into the composing word only moves the internal caret; a caret
            // that no longer maps ends the composition.
            if (isTranslationMode()) exitTranslationMode(revert = false)
            if (!resyncEnglishCursorToEditor(newSelStart, candidatesStart, candidatesEnd)) {
                clearInput()
                currentInputConnection?.finishComposingText()
            }
        } else if (selected) {
            cancelPendingInference(edited = false)
        } else if (composingText.isEmpty && englishComposing.isEmpty() && postCompositionExpectedSurface.isNotEmpty()) {
            val expectedTail = postCompositionExpectedSurface.takeLast(
                minOf(postCompositionExpectedSurface.length, MAX_LEFT_CONTEXT_LEN)
            )
            val beforeCursor = readEditorLeftContext(
                currentInputConnection,
                ComposingEditorState.PreviewCursor("", 0)
            )
            if (beforeCursor != null && expectedTail.isNotEmpty() && !beforeCursor.endsWith(expectedTail)) {
                clearPostCompositionDisplay()
            }
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
        serviceScope.launch(Dispatchers.IO) { EnglishWordDictionary.ensureLoaded(applicationContext) }

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
            if (isPrivateMode) return@OnPrimaryClipChangedListener
            val generation = clipboardPrivacyGeneration
            val clip = try { clipboardManager?.primaryClip } catch (_: RuntimeException) { null }
                ?: return@OnPrimaryClipChangedListener
            val clipTime = clip.description?.timestamp ?: 0L
            if (!InputPrivacy.shouldAcceptClipboardEvent(isPrivateMode, clipTime, lastPrivateInputEndMillis)) {
                return@OnPrimaryClipChangedListener
            }
            val itemCount = clip.itemCount
            if (itemCount <= 0) return@OnPrimaryClipChangedListener
            serviceScope.launch(Dispatchers.IO) {
                val clipTimestamp = System.currentTimeMillis()
                val newEntries = mutableListOf<ClipboardEntry>()
                for (i in 0 until itemCount) {
                    val item = clip.getItemAt(i) ?: continue
                    val uri = item.uri
                    val clipMime = clip.description?.getMimeType(i)
                    val resolvedMime = clipMime ?: uri?.let {
                        try { contentResolver.getType(it) } catch (_: Exception) { null }
                    }
                    val isImage = resolvedMime?.startsWith("image/") == true
                    if (isImage && uri != null) {
                        val cached = cacheClipboardImage(uri)
                        newEntries += ClipboardEntry(imageUri = cached ?: uri, timestamp = clipTimestamp)
                    } else {
                        val text = try {
                            item.coerceToText(this@ZeroConvertInputMethodService).toString()
                        } catch (_: Exception) {
                            ""
                        }
                        if (text.isNotBlank()) {
                            newEntries += ClipboardHistoryPolicy.buildTextEntries(text, now = clipTimestamp)
                        }
                    }
                }
                if (newEntries.isEmpty()) return@launch
                withContext(Dispatchers.Main) {
                    if (generation == clipboardPrivacyGeneration) appendClipboardEntries(newEntries)
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
        if (floatingKeyboard.isFloating && !isCandidatesOnly) {
            win.setLayout(MATCH_PARENT, MATCH_PARENT)
        } else {
            super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
        }
        win.decorView.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean =
        !floatingKeyboard.isFloating && super.onEvaluateFullscreenMode()

    override fun onComputeInsets(outInsets: Insets) {
        val host = floatingKeyboardHost
        if (floatingKeyboard.isFloating && host?.isShown == true) {
            host.computeFloatingInsets(outInsets)
        } else if (clipboardEditOverlayHeightPx > 0 && host?.isShown == true) {
            host.computeDockedOverlayInsets(outInsets, clipboardEditOverlayHeightPx)
        } else {
            super.onComputeInsets(outInsets)
        }
    }

    private fun setFloatingKeyboard(enabled: Boolean) {
        floatingKeyboardHost?.cancelDrag()
        floatingKeyboard.setEnabled(enabled)
        updateFullscreenMode()
        window?.window?.let { onConfigureWindow(it, isFullscreenMode, !isShowInputRequested) }
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
        }
        fun maxFloatingRows(): Int = if (
            isQwertyNumberRowState.value && japaneseLayoutState.value == JapaneseInputLayout.QWERTY
        ) 5 else 4
        val host = FloatingKeyboardHost(
            composeView,
            floatingKeyboard,
            naturalKeyboardHeightDp = {
                floatingKeyboardNaturalHeight(keyboardKeyHeightState.value.dp, maxFloatingRows()).value
            }
        ) { setFloatingKeyboard(false) }
        floatingKeyboardHost = host

        composeView.setContent {
            val floating = floatingKeyboard.isFloating
            val themeMode = keyboardThemeIdState.value
            val customThemeRevision = customThemeRevisionState.longValue
            FloatingKeyboardPanel(
                host = host,
                floating = floating,
                inputViewEpoch = inputViewEpochState.value,
                themeMode = themeMode,
                customThemeRevision = customThemeRevision,
                keyHeightDp = keyboardKeyHeightState.value,
                maxKeyRows = maxFloatingRows(),
                clipboardEditing = clipboardEditSessionState.value != null,
                onDock = { setFloatingKeyboard(false) }
            ) {
                KeyboardRootView(
                    imeService = this@ZeroConvertInputMethodService,
                    onAdoptAiAssist = { adoptAiAssist() },
                    themeMode = themeMode,
                    customThemeRevision = customThemeRevision,
                    onToggleRealtimeInline = {
                        setRealtimeInlineEnabled(!isRealtimeInlineState.value)
                    },
                    onCommitText = { text -> commitPanelText(text) },
                    onDeleteChar = { handleDelete() },
                    onEnter = { handleEnter() },
                    onClearInput = { clearInput() },
                    onClipboardPaste = { entry, fromSuggestion -> pasteClipboard(entry, fromSuggestion) },
                    isFloating = floating
                )
            }
        }
        return host
    }

    private var lastInferredSurface: String = ""
    private var lastInferredResult: String = ""
    private var isConfirming = false
    private val japaneseTouchBiases = ArrayList<TouchKeyBias>(64)
    private val englishTouchBiases = ArrayList<TouchKeyBias>(64)

    private fun tentativeInlineDisplay(surface: String, isRealtime: Boolean): String {
        if (!isRealtime) {
            lastInferredSurface = ""
            lastInferredResult = ""
            predictedCandidate.value = ""
            return composingText.convertTarget
        }
        val tentative = InlinePreviewPolicy.tentativePrefix(
            surface = surface,
            lastInferredSurface = lastInferredSurface,
            lastInferredResult = lastInferredResult,
            history = inferenceHistory
        )
        if (tentative.inferredSurface != lastInferredSurface || tentative.inferredResult != lastInferredResult) {
            lastInferredSurface = tentative.inferredSurface
            lastInferredResult = tentative.inferredResult
            predictedCandidate.value = ""
        }
        return tentative.text
    }

    fun handleChar(ch: Char, touchBias: TouchKeyBias? = null) {
        if (isConfirming) return
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
            if (SymbolCandidateCatalog.shouldComposeSymbol(ch, composingText.convertTargetBeforeCursor)) {
                handleKana(ch.toString(), touchBias)
                return
            }
            if (!composingText.isEmpty) {
                confirmAsync(trailingText = ch.toString())
            } else {
                commitDirect(ch.toString())
            }
            return
        }

        val readingPunctuation = PanelTextCommit.punctuationForReading(ch)
        if (readingPunctuation != null &&
            !PanelTextCommit.shouldComposePunctuation(ch, composingText.convertTargetBeforeCursor.hasPendingRomaji())
        ) {
            val punc = readingPunctuation.toString()
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
            leftContextConnection = ic
            inferenceHistory.clear()
            lastAutoCommitRewind = null
            japaneseTouchBiases.clear()
        }

        // Abort ongoing inference
        cancelPendingInference()

        // Insert into azooKey ComposingText state machine. ASCII punctuation is
        // normalized to the Japanese mark so the reading matches the key label.
        recordJapaneseComposingUndo()
        val prevInputCount = composingText.input.size
        val appendAtEnd = composingText.isAtEndIndex
        val effectiveChar = PanelTextCommit.punctuationForReading(ch) ?: ch
        composingText.insertAtCursorPosition(
            effectiveChar.toString(),
            InputStyle.ROMAN2KANA
        )
        if (!isSensitiveInput()) {
            val insertedCount = composingText.input.size - prevInputCount
            if (appendAtEnd && insertedCount == 1) {
                val lowered = effectiveChar.lowercaseChar()
                val biasEntry = if (touchBias != null && touchBias.typedChar.lowercaseChar() == lowered) {
                    touchBias
                } else {
                    TouchKeyBias(lowered)
                }
                japaneseTouchBiases.add(biasEntry)
            } else if (!appendAtEnd) {
                japaneseTouchBiases.clear()
            }
        }
        val surface = composingText.convertTargetBeforeCursor
        syncComposingDisplayState()

        // Handle inline display:
        // If realtime inline is ON: keep the converted prefix matching the current reading
        // If realtime inline is OFF (default): show raw kana directly without kanji replacement
        val isRealtime = isRealtimeInlineEnabled()
        val tentativeDisplay = tentativeInlineDisplay(surface, isRealtime)

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

    fun handleKana(kana: String, touchBias: TouchKeyBias? = null) {
        if (isConfirming) return
        canRewindOnNextBackspace = false

        if (isTranslationMode() && kana.singleOrNull()?.let { isTranslationLiteralChar(it) } == true) {
            appendTranslationLiteral(kana)
            return
        }

        val kanaPunctuation = kana.singleOrNull()?.let { PanelTextCommit.punctuationForReading(it) }
        if (kanaPunctuation != null &&
            !PanelTextCommit.shouldComposePunctuation(kanaPunctuation, composingText.convertTargetBeforeCursor.hasPendingRomaji())
        ) {
            if (!composingText.isEmpty) {
                confirmAsync(trailingText = kanaPunctuation.toString())
            } else {
                commitDirect(kanaPunctuation.toString())
            }
            return
        }

        // Capture real confirmed left context ONLY at the start of input session
        if (composingText.isEmpty) {
            val ic = currentInputConnection
            cachedLeftContext = EditorContext.bounded(ic?.getTextBeforeCursor(MAX_LEFT_CONTEXT_LEN * 2, 0)?.toString().orEmpty())
            leftContextConnection = ic
            inferenceHistory.clear()
            lastAutoCommitRewind = null
            japaneseTouchBiases.clear()
        }

        // Abort ongoing inference
        cancelPendingInference()

        // Insert direct kana into composingText
        recordJapaneseComposingUndo()
        val trackBias = !isSensitiveInput() && composingText.isAtEndIndex && kana.length == 1
        composingText.insertAtCursorPosition(kana, InputStyle.DIRECT)
        if (trackBias) {
            val resolved = touchBias ?: TouchKeyBias(kana[0])
            if (resolved.typedChar == kana[0]) {
                japaneseTouchBiases.add(resolved)
                val charCount = composingText.input.count { it.piece is InputPiece.CharPiece }
                while (japaneseTouchBiases.size > charCount) {
                    japaneseTouchBiases.removeAt(0)
                }
            } else {
                japaneseTouchBiases.clear()
            }
        } else {
            japaneseTouchBiases.clear()
        }
        val surface = composingText.convertTargetBeforeCursor
        syncComposingDisplayState()

        val isRealtime = isRealtimeInlineEnabled()
        val tentativeDisplay = tentativeInlineDisplay(surface, isRealtime)

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
        recordJapaneseComposingUndo()

        composingText.deleteBackwardFromCursorPosition(1)
        composingText.insertAtCursorPosition(modified.toString(), InputStyle.DIRECT)
        if (!isSensitiveInput() && composingText.isAtEndIndex) {
            TouchBiasBuffer.updateLastForModifier(
                biases = japaneseTouchBiases,
                charCount = composingText.input.count { it.piece is InputPiece.CharPiece },
                previousChar = lastChar,
                modifiedChar = modified,
                kind = kind
            )
        } else if (!composingText.isAtEndIndex) {
            japaneseTouchBiases.clear()
        }

        val newSurface = composingText.convertTargetBeforeCursor
        syncComposingDisplayState()

        val isRealtime = isRealtimeInlineEnabled()
        val tentativeDisplay = tentativeInlineDisplay(newSurface, isRealtime)
        inferenceHistory.removeAll { it.readingHiragana.length >= newSurface.length }

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

    fun replaceLastKana(nextKana: String, touchBias: TouchKeyBias? = null) {
        if (isConfirming) return
        if (composingText.isEmpty) return

        val cursor = composingText.convertTargetCursorPosition
        if (cursor <= 0) return

        canRewindOnNextBackspace = false
        cancelPendingInference()

        val prevLastChar = composingText.convertTargetBeforeCursor.lastOrNull()
        composingText.deleteBackwardFromCursorPosition(1)
        composingText.insertAtCursorPosition(nextKana, InputStyle.DIRECT)
        if (!isSensitiveInput() && composingText.isAtEndIndex && nextKana.length == 1) {
            TouchBiasBuffer.updateLastForReplacement(
                biases = japaneseTouchBiases,
                charCount = composingText.input.count { it.piece is InputPiece.CharPiece },
                previousChar = prevLastChar,
                replacementChar = nextKana[0],
                replacementBias = touchBias
            )
        } else if (!composingText.isAtEndIndex) {
            japaneseTouchBiases.clear()
        }

        val newSurface = composingText.convertTargetBeforeCursor
        syncComposingDisplayState()

        val isRealtime = isRealtimeInlineEnabled()
        val tentativeDisplay = tentativeInlineDisplay(newSurface, isRealtime)
        inferenceHistory.removeAll { it.readingHiragana.length >= newSurface.length }

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
        clearPostCompositionDisplay()
        val ic = currentInputConnection ?: return false
        val last = ic.getTextBeforeCursor(1, 0)?.toString() ?: return false
        return last.isNotEmpty() && last.first().isLetter()
    }

    fun toggleLastCharCase() {
        if (isConfirming) return
        if (englishComposing.isNotEmpty() && composingText.isEmpty) {
            val cursor = ComposingEditorState.snapBoundary(
                englishComposing,
                englishCursorPosition.coerceIn(0, englishComposing.length)
            )
            if (cursor <= 0) return
            val index = cursor - 1
            val ch = englishComposing[index]
            if (!ch.isLetter()) return
            if (!isSensitiveInput()) {
                undoHistory.push(
                    ImeUndoEntry.EnglishComposing(
                        previousText = englishComposing,
                        touchBiases = englishTouchBiases.toList()
                    )
                )
            }
            val flipped = if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()
            englishComposing = englishComposing.substring(0, index) + flipped + englishComposing.substring(cursor)
            syncComposingDisplayState()
            setEnglishComposingText(currentInputConnection)
            triggerEnglishSuggestions(englishComposing)
            return
        }
        val ic = currentInputConnection ?: return
        val last = ic.getTextBeforeCursor(1, 0)?.toString() ?: return
        if (last.isNotEmpty()) {
            val ch = last.first()
            if (ch.isLetter()) {
                val flipped = if (ch.isUpperCase()) ch.lowercaseChar() else ch.uppercaseChar()
                ic.deleteSurroundingText(1, 0)
                if (ic.commitText(flipped.toString(), 1) && !isSensitiveInput()) {
                    undoHistory.push(
                        ImeUndoEntry.ReplacedCommittedText(
                            previousText = last,
                            currentText = flipped.toString()
                        )
                    )
                }
            }
        }
    }

    fun replaceLastCommittedText(newText: String, oldLength: Int = 1, touchBias: TouchKeyBias? = null) {
        if (isConfirming) return
        val ic = currentInputConnection ?: return
        if (englishComposing.isNotEmpty() && oldLength > 0 && englishComposing.length >= oldLength) {
            englishCursorPosition = englishComposing.length
            val prevLen = englishComposing.length
            val remaining = englishComposing.dropLast(oldLength)
            englishComposing = remaining
            englishCursorPosition = remaining.length
            TouchBiasBuffer.trimForBackwardDelete(englishTouchBiases, prevLen, remaining.length)
            syncComposingDisplayState()
            if (remaining.isEmpty()) {
                setManagedComposingText(ic, "", 1)
                clearEnglishInput()
            } else {
                setManagedComposingText(ic, remaining, 1)
                triggerEnglishSuggestions(remaining)
            }
            handleEnglishText(newText, touchBias)
            return
        }
        ic.deleteSurroundingText(oldLength, 0)
        if (ic.commitText(newText, 1) && !isSensitiveInput()) {
            undoHistory.updateLatestCommittedSuffix(oldLength, newText)
        }
    }

    fun showInputMethodPicker() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showInputMethodPicker()
    }

    private fun triggerDebouncedConversion(
        surface: String,
        debounceMs: Long = DEFAULT_CONVERSION_DEBOUNCE_MS,
        suppressCorrection: Boolean = false,
    ) {
        if (isTranslationMode()) {
            scheduleTranslation()
            return
        }
        syncCorrectionRevertState()
        if (isSensitiveInput() || composingText.isEmpty) pendingConversionContinuation = null
        cancelPendingInference(edited = false)
        val connection = currentInputConnection ?: return
        val sensitive = isSensitiveInput()
        if (!sensitive && leftContextConnection !== connection) {
            readEditorLeftContext(connection)?.let {
                cachedLeftContext = it
                leftContextConnection = connection
            }
        }
        // Capture every mutable input on the UI thread, before dispatching work.
        val snapshot = composingText.clone()
        val ticket = requestGate.capture(requestKey())
        val context = cachedLeftContext
        val continuation = pendingConversionContinuation
        val profile = aiProfileState.value
        val reconversion = reconverting
        val rangeConversion = conversionRange.active
        val protectedPrefix = corrections.fixedPrefix(surface)
        val fixedPrefix = CorrectionSession.fixedSurfacePrefixForConversion(protectedPrefix)
        val dictionaryCorrection = !sensitive && !reconversion && !rangeConversion && isDictionaryTypoCorrectionState.value
        val touchCorrectionSnapshot = !sensitive && isTouchTypoCorrectionEnabledState.value
        val touchBiasesSnapshot = if (touchCorrectionSnapshot) japaneseTouchBiases.toList() else emptyList()
        inferenceJob = serviceScope.launch(Dispatchers.IO) {
            try {
                delay(debounceMs)
                if (!KanaKanjiConverter.isInitialized) {
                    KanaKanjiConverter.ensureInitialized(applicationContext)
                }
                val results = converter.requestCandidates(snapshot, context, enableAI = !sensitive && !reconversion && !rangeConversion,
                    profile = profile, personalize = !sensitive, fixedSurfacePrefix = fixedPrefix,
                    experimentalKeyboardCorrection = dictionaryCorrection,
                    predictiveConversion = !sensitive && !reconversion && !rangeConversion && isPredictiveConversionEnabled(),
                    continuation = continuation, touchBiases = emptyList()).let {
                    if (rangeConversion) ConversionRangeSession.candidatesForRange(snapshot, it) else it
                }
                ensureActive()
                if (!editorContextMatches(connection, context, sensitive)) return@launch
                withContext(Dispatchers.Main) {
                    if (connection !== currentInputConnection || !requestGate.isCurrent(ticket, requestKey())) {
                        return@withContext
                    }
                    // Reading completions require an explicit candidate tap.
                    // A candidate that consumes only part of the reading is padded with the
                    // leftover kana (安間 + り), so inline text prefers one that explains the
                    // whole reading and only falls back to a partial candidate when none does.
                    val inlineBase = InlinePreviewPolicy.select(results, surface)
                    val aiCandidate = results.firstOrNull {
                        it.isAI && !it.isReadingCompletion && it.coversReading(surface)
                    } ?: inlineBase
                    val aiCand = aiCandidate?.previewForReading(surface).orEmpty()
                    if (composingText.isAtEndIndex && surface.isNotEmpty() && aiCand.isNotEmpty() &&
                        !hasTrailingIncompleteRomaji(surface) && !corrections.holdsAutoCommit
                    ) {
                        recordInferenceSnapshot(inferenceHistory, InferenceSnapshot(surface, aiCand, snapshot.input.size))
                    }
                    val autoCommitMode = autoCommitMode()
                    if (!sensitive && !reconversion && !rangeConversion && autoCommitMode.isEnabled && composingText.isAtEndIndex &&
                        !corrections.holdsAutoCommit && !hasTrailingIncompleteRomaji(surface)
                    ) {
                        // Auto-commit is shared policy: the engine owns the rule and
                        // its unit conversions, so Android has no second implementation.
                        val topTokenMargins = if (aiCandidate != null && aiCandidate.text == aiCand) {
                            aiCandidate.engineTokenMargins
                        } else {
                            ""
                        }
                        val autoCommit = com.zeroconvert.ime.engine.WindowsEngine.autoCommit(
                            composingText.input.mapNotNull { (it.piece as? com.zeroconvert.ime.azookey.input.InputPiece.CharPiece)?.char }.joinToString(""),
                            surface,
                            inferenceHistory,
                            composingText::isIndependentInputBoundary,
                            mode = autoCommitMode,
                            topTokenMargins = topTokenMargins,
                        )
                        if (autoCommit != null && aiCand.startsWith(autoCommit.surfacePrefix)) {
                            performAutoCommit(autoCommit, aiCand)
                            return@withContext
                        }
                    }
                    val previousResult = lastInferredResult
                    val previousSurface = lastInferredSurface
                    val best = inlineBase?.previewForReading(surface).orEmpty()
                    val suppressDowngrade = (isRealtimeInlineEnabled() || rangeConversion) && best.isNotEmpty() &&
                        InlinePreviewPolicy.shouldSuppressPreviewDowngrade(
                            previousReading = previousSurface,
                            reading = surface,
                            previousPreview = previousResult,
                            preview = best
                        )
                    candidatesList.clear()
                    candidatesList.addAll(results)
                    displayedConversionTicket = ticket
                    if (!suppressDowngrade) {
                        predictedCandidate.value = best
                        lastInferredSurface = surface
                        lastInferredResult = best
                    }
                    if (!sensitive && !reconversion && !rangeConversion &&
                        (isDictionaryTypoCorrectionState.value || isAiAssistEnabled()) && !hasTrailingIncompleteRomaji(surface)) {
                        val allowCorrection = !suppressCorrection && isDictionaryTypoCorrectionState.value &&
                            !corrections.isApplied && fixedPrefix.isEmpty()
                        val allowPrediction = isAiAssistEnabled()
                        val baselineReading = inlineBase?.enginePredictionReading.orEmpty()
                        val refinement = if (allowCorrection) {
                            corrections.buildAutoRefinementContext(snapshot, touchBiasesSnapshot)
                        } else {
                            null
                        }
                        val inputMode = com.zeroconvert.ime.engine.engineInputMode(
                            refinement?.originInput ?: snapshot,
                            japaneseLayoutState.value,
                            isFlickOnlyState.value
                        )
                        val rawInput = if (allowCorrection && inputMode == "qwerty") {
                            com.zeroconvert.ime.engine.engineRawRomaji(snapshot)
                        } else {
                            ""
                        }
                        val touchHints = if (!allowCorrection) {
                            ""
                        } else if (com.zeroconvert.ime.engine.isFlickEngineMode(inputMode)) {
                            com.zeroconvert.ime.engine.engineFlickTouchHints(snapshot, touchBiasesSnapshot)
                        } else {
                            com.zeroconvert.ime.engine.engineTouchHints(snapshot, touchBiasesSnapshot)
                        }
                        val originReading = refinement?.originReading.orEmpty()
                        val appliedPrefix = refinement?.appliedPrefix.orEmpty()
                        val originRaw = if (refinement != null && inputMode == "qwerty") {
                            com.zeroconvert.ime.engine.engineRawRomaji(refinement.originInput)
                        } else {
                            ""
                        }
                        val originTouchHints = if (refinement == null) {
                            ""
                        } else if (com.zeroconvert.ime.engine.isFlickEngineMode(inputMode)) {
                            com.zeroconvert.ime.engine.engineFlickTouchHints(refinement.originInput, refinement.originBiases)
                        } else {
                            com.zeroconvert.ime.engine.engineTouchHints(refinement.originInput, refinement.originBiases)
                        }
                        val autoStrengthSnapshot = autoTypoCorrectionStrengthState.value.storageValue
                        val autoEnabledSnapshot = autoTypoCorrectionStrengthState.value.isEnabled
                        aiAssistJob = serviceScope.launch(Dispatchers.IO) {
                            com.zeroconvert.ime.assist.TypoLearning.ensureLoaded(applicationContext)
                            com.zeroconvert.ime.assist.AiAssistManager.streamAiAssist(
                                reading = surface,
                                baseline = best,
                                context = context,
                                allowCorrection = allowCorrection,
                                allowPrediction = allowPrediction,
                                baselineReading = baselineReading,
                                rawInput = rawInput,
                                touchHints = touchHints,
                                inputMode = inputMode,
                                autoCorrectionStrength = autoStrengthSnapshot,
                                originReading = originReading,
                                originRaw = originRaw,
                                originTouchHints = originTouchHints,
                                appliedPrefix = appliedPrefix,
                            ) { update ->
                                ensureActive()
                                if (editorContextMatches(connection, context, sensitive)) {
                                    withContext(Dispatchers.Main) assistUpdate@ {
                                        if (connection !== currentInputConnection ||
                                            !requestGate.isCurrent(ticket, requestKey()) || isSensitiveInput()) return@assistUpdate
                                        val suggestions = update.suggestions.filterNot {
                                            it.revertToOrigin || corrections.isRejected(surface, it.text)
                                        }
                                        val merged = ConversionCandidateMerge.withCorrection(candidatesList,
                                            suggestions.firstOrNull { it.type == com.zeroconvert.ime.assist.AiAssistType.TYPO_CORRECTION })
                                        candidatesList.clear()
                                        candidatesList.addAll(merged)
                                        val selected = update.selected?.takeUnless {
                                            it.revertToOrigin || corrections.isRejected(surface, it.text)
                                        }
                                        aiAssistResultState.value = selected
                                        displayedAssistTicket = if (suggestions.isNotEmpty()) ticket else null
                                        assistInputConnection = if (suggestions.isNotEmpty()) connection else null
                                        val automatic = update.automaticCorrection?.takeUnless {
                                            !it.revertToOrigin && corrections.isRejected(surface, it.text)
                                        }
                                        if (automatic != null && autoEnabledSnapshot) {
                                            if (automatic.revertToOrigin) {
                                                performAutoRevertAiAssist(automatic.sourceReading)
                                            } else {
                                                aiAssistResultState.value = automatic
                                                adoptAiAssist(explicit = false)
                                                // If automatic application is ineligible, retain the displayed tap target.
                                                if (requestGate.isCurrent(ticket, requestKey())) aiAssistResultState.value = selected
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if ((isRealtimeInlineEnabled() || rangeConversion) && best.isNotEmpty() && !suppressDowngrade) {
                        val suffix = composingText.convertTarget.drop(composingText.convertTargetCursorPosition)
                        if (!setComposingPreview(connection, best + suffix)) {
                            cancelPendingInference(edited = false)
                            clearCandidateList()
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
        connection.beginBatchEdit()
        try {
            if (!connection.commitText(prefixSurface, 1)) return
            composingEditor.finishComposition()
            recordCommittedCorrections(prefixSurface, committedReading)

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
            syncCorrectionRevertState()
            TouchBiasBuffer.trimToMaxCharCountFromStart(
                japaneseTouchBiases,
                composingText.input.count { it.piece is InputPiece.CharPiece }
            )
            val remainingReading = composingText.convertTargetBeforeCursor
            pendingConversionContinuation = if (remainingReading.isNotEmpty())
                ConversionContinuation.derive(fullReadingBeforeAutoCommit, remainingReading, prefixSurface) else null
            syncComposingDisplayState()

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
            }
        } finally { connection.endBatchEdit() }
    }

    private fun recordJapaneseComposingUndo() {
        if (isSensitiveInput()) return
        undoHistory.push(
            ImeUndoEntry.JapaneseComposing(
                previousComposing = composingText.clone(),
                touchBiases = japaneseTouchBiases.toList(),
                previousLeftContext = cachedLeftContext
            )
        )
    }

    fun handleUndo() {
        if (performUndo()) {
            vibrateOnKeyPress()
        }
    }

    private fun performUndo(): Boolean {
        if (isConfirming || isTranslationMode()) return false
        if (aiAssistAdoptionState.value != AiAssistAdoption.None && !hasSelectionInEditor) {
            return performRevertAiAssist()
        }
        if (isAutoCommitRewindEnabled() && canRewindOnNextBackspace && lastAutoCommitRewind != null) {
            return performAutoCommitRewind()
        }
        if (conversionRange.active) {
            cancelConversionRange()
            return true
        }
        val entry = undoHistory.pop() ?: return false
        return applyUndoEntry(entry)
    }

    private fun applyUndoEntry(entry: ImeUndoEntry): Boolean {
        if (isSensitiveInput()) {
            undoHistory.clear()
            return false
        }
        return when (entry) {
            is ImeUndoEntry.JapaneseComposing -> {
                if (englishComposing.isNotEmpty() || hasSelectionInEditor) return false
                cancelPendingInference()
                canRewindOnNextBackspace = false
                lastAutoCommitRewind = null
                corrections.clear()
                aiAssistAdoptionState.value = AiAssistAdoption.None
                composingText.restoreFrom(entry.previousComposing)
                japaneseTouchBiases.clear()
                japaneseTouchBiases.addAll(entry.touchBiases)
                cachedLeftContext = entry.previousLeftContext
                val surface = composingText.convertTargetBeforeCursor
                syncComposingDisplayState()
                if (composingText.isEmpty) {
                    inferenceHistory.clear()
                    setManagedComposingText(currentInputConnection, "", 0)
                    currentInputConnection?.finishComposingText()
                    clearInput()
                } else {
                    val inline = isRealtimeInlineEnabled()
                    val prefix = tentativeInlineDisplay(surface, inline)
                    inferenceHistory.removeAll { it.readingHiragana.length >= surface.length }
                    val preview = ConversionRangeSession.preview(composingText, prefix, inline)
                    if (setComposingPreview(currentInputConnection, preview) && surface.isNotEmpty()) {
                        triggerDebouncedConversion(surface)
                    } else {
                        clearCandidateList()
                    }
                }
                true
            }
            is ImeUndoEntry.EnglishComposing -> {
                if (!composingText.isEmpty || hasSelectionInEditor) return false
                englishComposing = entry.previousText
                englishCursorPosition = englishComposing.length
                englishTouchBiases.clear()
                englishTouchBiases.addAll(entry.touchBiases)
                syncComposingDisplayState()
                if (englishComposing.isNotEmpty()) {
                    setManagedComposingText(currentInputConnection, englishComposing, 1)
                    triggerEnglishSuggestions(englishComposing)
                } else {
                    setManagedComposingText(currentInputConnection, "", 0)
                    currentInputConnection?.finishComposingText()
                    candidatesList.clear()
                    predictedCandidate.value = ""
                }
                true
            }
            is ImeUndoEntry.CommittedText -> {
                if (hasSelectionInEditor || englishComposing.isNotEmpty()) return false
                val ic = currentInputConnection ?: return false
                if (composingText.isEmpty) {
                    val before = try {
                        ic.getTextBeforeCursor(entry.committedText.length, 0)?.toString()
                    } catch (_: RuntimeException) {
                        null
                    }
                    if (before != entry.committedText) return false
                    ic.beginBatchEdit()
                    try {
                        if (!ic.deleteSurroundingText(entry.committedText.length, 0)) return false
                        if (entry.previousComposing != null && !entry.previousComposing.isEmpty) {
                            composingText.restoreFrom(entry.previousComposing)
                            cachedLeftContext = entry.previousLeftContext
                            leftContextConnection = ic
                            japaneseTouchBiases.clear()
                            japaneseTouchBiases.addAll(entry.japaneseTouchBiases)
                            val surface = composingText.convertTargetBeforeCursor
                            syncComposingDisplayState()
                            setManagedComposingText(ic, composingText.convertTarget, 1)
                            if (surface.isNotEmpty()) {
                                triggerDebouncedConversion(surface)
                            }
                        } else if (entry.previousEnglish.isNotEmpty()) {
                            englishComposing = entry.previousEnglish
                            englishCursorPosition = englishComposing.length
                            cachedLeftContext = entry.previousLeftContext
                            leftContextConnection = ic
                            englishTouchBiases.clear()
                            englishTouchBiases.addAll(entry.englishTouchBiases)
                            syncComposingDisplayState()
                            setManagedComposingText(ic, englishComposing, 1)
                            triggerEnglishSuggestions(englishComposing)
                        }
                    } finally {
                        ic.endBatchEdit()
                    }
                    true
                } else {
                    val previousComposing = entry.previousComposing?.takeIf { !it.isEmpty } ?: return false
                    val confirmedBefore = readEditorLeftContext(ic) ?: return false
                    if (!confirmedBefore.endsWith(entry.committedText)) return false
                    cancelPendingInference()
                    ic.beginBatchEdit()
                    try {
                        if (!setManagedComposingText(ic, "", 0)) return false
                        if (!ic.deleteSurroundingText(entry.committedText.length, 0)) {
                            setManagedComposingText(ic, composingText.convertTarget, 1)
                            return false
                        }
                        composingText.restoreFrom(previousComposing)
                        cachedLeftContext = entry.previousLeftContext
                        leftContextConnection = ic
                        japaneseTouchBiases.clear()
                        japaneseTouchBiases.addAll(entry.japaneseTouchBiases)
                        val surface = composingText.convertTargetBeforeCursor
                        syncComposingDisplayState()
                        setManagedComposingText(ic, composingText.convertTarget, 1)
                        if (surface.isNotEmpty()) {
                            triggerDebouncedConversion(surface)
                        }
                    } finally {
                        ic.endBatchEdit()
                    }
                    true
                }
            }
            is ImeUndoEntry.DeletedText -> {
                if (!composingText.isEmpty || englishComposing.isNotEmpty() || hasSelectionInEditor) return false
                val ic = currentInputConnection ?: return false
                ic.commitText(entry.deletedText, 1)
            }
            is ImeUndoEntry.ReplacedCommittedText -> {
                if (!composingText.isEmpty || englishComposing.isNotEmpty() || hasSelectionInEditor) return false
                val ic = currentInputConnection ?: return false
                val before = try {
                    ic.getTextBeforeCursor(entry.currentText.length, 0)?.toString()
                } catch (_: RuntimeException) {
                    null
                }
                if (before != entry.currentText) return false
                ic.beginBatchEdit()
                try {
                    ic.deleteSurroundingText(entry.currentText.length, 0) &&
                        ic.commitText(entry.previousText, 1)
                } finally {
                    ic.endBatchEdit()
                }
            }
        }
    }

    private fun performAutoCommitRewind(): Boolean {
        val rewindRecord = lastAutoCommitRewind ?: return false
        canRewindOnNextBackspace = false
        lastAutoCommitRewind = null

        val connection = currentInputConnection ?: return false
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
        if (rewindResult == AutoCommitRewind.Result.COMMITTED_FALLBACK) { clearInput(); return false }
        if (rewindResult != AutoCommitRewind.Result.RESTORED && rewindResult != AutoCommitRewind.Result.RESTORE_PENDING) return false
        cachedLeftContext = rewindRecord.previousLeftContext
        composingText.restoreFrom(rewindRecord.originalInput ?: return false)
        japaneseTouchBiases.clear()
        pendingConversionContinuation = null

        val restoredSurface = composingText.convertTargetBeforeCursor
        syncComposingDisplayState()
        lastInferredSurface = ""
        lastInferredResult = ""
        inferenceHistory.clear()

        setManagedComposingText(currentInputConnection, composingText.convertTarget, 1)
        if (restoredSurface.isNotEmpty()) {
            triggerDebouncedConversion(restoredSurface)
        }
        return true
    }

    fun handleDelete() {
        if (performDelete()) {
            vibrateOnKeyPress()
        }
    }

    private fun performDelete(): Boolean {
        if (isConfirming) return false
        if (corrections.isImmediateBackspaceUndo && !hasSelectionInEditor) {
            return performRevertAiAssist()
        }
        if (englishComposing.isNotEmpty()) {
            return performEnglishDelete()
        }
        cancelPendingInference()

        // 1. If text is selected in the editor, delete the selection immediately
        val ic = currentInputConnection
        val selected = if (hasSelectionInEditor || composingText.isEmpty) ic?.getSelectedText(0) else null
        if (!selected.isNullOrEmpty() || hasSelectionInEditor) {
            canRewindOnNextBackspace = false
            lastAutoCommitRewind = null
            val selectedText = selected?.toString().orEmpty()
            clearInput()
            hasSelectionInEditor = false
            if (ic == null) return false
            val committed = ic.commitText("", 1)
            val stillSelected = ic.getSelectedText(0)
            if (!stillSelected.isNullOrEmpty()) {
                sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
            } else if (committed && selectedText.isNotEmpty() && selectedText.length <= 200 && !isSensitiveInput()) {
                undoHistory.push(ImeUndoEntry.DeletedText(selectedText))
            }
            return true
        }

        // Check optional Backspace rewind right after an auto-commit
        if (isAutoCommitRewindEnabled() && canRewindOnNextBackspace && lastAutoCommitRewind != null) {
            return performAutoCommitRewind()
        }

        canRewindOnNextBackspace = false

        if (!composingText.isEmpty) {
            if (composingText.isAtStartIndex) { clearCandidateList(); return false }
            val previous = ComposingEditorState.moveByCodePoint(composingText.convertTarget, composingText.convertTargetCursorPosition, -1)
            val deleteCount = composingText.convertTargetCursorPosition - previous
            if (deleteCount <= 0) { clearCandidateList(); return false }
            recordJapaneseComposingUndo()
            val prevCharCount = composingText.input.count { it.piece is InputPiece.CharPiece }
            composingText.deleteBackwardFromCursorPosition(deleteCount)
            syncCorrectionRevertState()
            if (composingText.isAtEndIndex) {
                val charCount = composingText.input.count { it.piece is InputPiece.CharPiece }
                TouchBiasBuffer.trimForBackwardDelete(japaneseTouchBiases, prevCharCount, charCount)
            } else {
                japaneseTouchBiases.clear()
            }
            val surface = composingText.convertTargetBeforeCursor
            syncComposingDisplayState()
            if (composingText.isEmpty) {
                inferenceHistory.clear()
                setManagedComposingText(currentInputConnection, "", 0)
                currentInputConnection?.finishComposingText()
                clearInput()
            } else {
                val inline = isRealtimeInlineEnabled()
                val prefix = tentativeInlineDisplay(surface, inline)
                inferenceHistory.removeAll { it.readingHiragana.length >= surface.length }
                val preview = ConversionRangeSession.preview(composingText, prefix, inline)
                if (setComposingPreview(currentInputConnection, preview) && surface.isNotEmpty()) {
                    triggerDebouncedConversion(surface)
                } else clearCandidateList()
            }
            return true
        } else {
            return deleteDirectFromEditor()
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
        performAdjustConversionRange(direction)
    }

    private fun performAdjustConversionRange(direction: Int): Boolean {
        if (isConfirming || !conversionRange.active) return false
        if (!conversionRange.adjust(composingText, direction)) return false
        refreshConversionRange()
        return true
    }

    fun cancelConversionRange() {
        if (!conversionRange.active || isConfirming) return
        val connection = currentInputConnection ?: return
        val preview = conversionRange.originalPreview
        if (!setManagedComposingText(connection, preview, 1)) return
        conversionRange.cancel(composingText)
        conversionRangeState.value = ""
        clearCandidateList()
        syncComposingDisplayState()
        triggerDebouncedConversion(composingText.convertTargetBeforeCursor)
    }

    private fun refreshConversionRange() {
        clearCandidateList()
        pendingConversionContinuation = null
        lastAutoCommitRewind = null
        canRewindOnNextBackspace = false
        val reading = composingText.convertTargetBeforeCursor
        conversionRangeState.value = reading + "｜" + composingText.convertTarget.drop(composingText.convertTargetCursorPosition)
        syncComposingDisplayState()
        triggerDebouncedConversion(reading)
    }

    fun handleCursorLeft() {
        if (performCursorMove(-1)) vibrateOnKeyPress()
    }

    fun handleCursorRight() {
        if (performCursorMove(1)) vibrateOnKeyPress()
    }

    private fun performCursorMove(direction: Int): Boolean {
        if (isTranslationMode()) return false
        if (conversionRange.active) return performAdjustConversionRange(direction)
        canRewindOnNextBackspace = false
        if (englishComposing.isNotEmpty() && composingText.isEmpty) {
            val target = ComposingEditorState.moveByCodePoint(
                englishComposing,
                englishCursorPosition.coerceIn(0, englishComposing.length),
                direction
            )
            if (target == englishCursorPosition) return false
            englishCursorPosition = target
            syncComposingDisplayState()
            selfIssuedCursorMoveAt = System.currentTimeMillis()
            setEnglishComposingText(currentInputConnection)
            return true
        }
        if (!composingText.isEmpty) {
            val target = ComposingEditorState.moveByCodePoint(
                composingText.convertTarget,
                composingText.convertTargetCursorPosition,
                direction
            )
            val moved = composingText.moveCursorFromCursorPosition(target - composingText.convertTargetCursorPosition)
            if (moved != 0) {
                selfIssuedCursorMoveAt = System.currentTimeMillis()
                updateComposingDisplayOnCursorMove()
                return true
            }
            return false
        }
        clearPostCompositionDisplay()
        val ic = currentInputConnection ?: return false
        val hasSelection = try {
            !ic.getSelectedText(0).isNullOrEmpty()
        } catch (_: RuntimeException) {
            false
        }
        val textInDirection = try {
            if (direction < 0) ic.getTextBeforeCursor(1, 0) else ic.getTextAfterCursor(1, 0)
        } catch (_: RuntimeException) {
            null
        }
        val keyCode = if (direction < 0) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT
        } else {
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        }
        sendDownUpKeyEvents(keyCode)
        return shouldVibrateOnEditorCursorMove(textInDirection, hasSelection)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if ((!composingText.isEmpty || englishComposing.isNotEmpty()) && event.hasNoModifiers() &&
            (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
            managedCursorKeys.add(keyCode)
            performCursorMove(if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
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
        syncComposingDisplayState()
        // Reuse the converted preview while its tail still matches the remaining
        // reading. When the caret has moved into a region the engine already
        // converted to kanji there is no such tail, and rewriting the editor with
        // the raw reading would destroy the conversion and invite a kana commit.
        // Leave the editor text alone in that case and let the debounced
        // conversion below publish a preview for the new prefix.
        val suffix = composingText.convertTarget.drop(composingText.convertTargetCursorPosition)
        val currentPreview = composingEditor.preview
        if (currentPreview.isNotEmpty() && currentPreview.endsWith(suffix)) {
            if (!setComposingPreview(currentInputConnection, currentPreview)) return
        }
        if (surfaceBefore.isNotEmpty()) triggerDebouncedConversion(surfaceBefore, debounceMs = CURSOR_MOVE_CONVERSION_DEBOUNCE_MS)
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
            confirmAsync()
        } else {
            sendEnterOrEditorAction()
        }
    }

    /**
     * Sends the editor action the Enter key is labelled with, falling back to a newline.
     * A multiline field (IME_FLAG_NO_ENTER_ACTION) or an editor that declares no action
     * keeps the newline it always got.
     */
    private fun sendEnterOrEditorAction() {
        val action = EnterKeyAction.forEditor(currentInputEditorInfo?.imeOptions ?: 0, isComposing = false)
        val handled = action != null && try {
            currentInputConnection?.performEditorAction(action.editorAction) == true
        } catch (_: RuntimeException) {
            false
        }
        if (!handled) sendKeyChar('\n')
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
                    syncComposingDisplayState()
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

    fun confirmAsync(trailingText: String = "") {
        if (isConfirming) return
        if (isTranslationMode()) {
            handleTranslationEnter()
            return
        }
        if (englishComposing.isNotEmpty()) {
            // A mode change accepts the spelling, not an English completion.
            commitText(englishComposing + trailingText, committedCandidate = null, trailingText = trailingText)
            return
        }
        if (composingText.isEmpty) {
            if (trailingText.isNotEmpty()) commitDirect(trailingText)
            return
        }
        val currentSurface = composingText.convertTargetBeforeCursor
        if (currentSurface == lastInferredSurface && predictedCandidate.value.isNotEmpty() && editorContextIsCurrent()) {
            val candidate = candidatesList.firstOrNull { it.text == predictedCandidate.value && it.assist == null }
            commitText(predictedCandidate.value + trailingText, committedCandidate = candidate, trailingText = trailingText)
            return
        }
        cancelPendingInference(edited = false)
        if (!isSensitiveInput()) {
            val actualContext = readEditorLeftContext()
            if (actualContext == null) { clearInput(); return }
            cachedLeftContext = actualContext
            leftContextConnection = currentInputConnection
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
        val fixedPrefix = CorrectionSession.fixedSurfacePrefixForConversion(corrections.fixedPrefix(currentSurface))
        val dictionaryCorrection = !sensitive && !reconversion && !rangeConversion && isDictionaryTypoCorrectionState.value
        isConfirming = true
        inferenceJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val results = converter.requestCandidates(snapshot, context, enableAI = !sensitive && !reconversion && !rangeConversion,
                    profile = profile, personalize = !sensitive, fixedSurfacePrefix = fixedPrefix,
                    experimentalKeyboardCorrection = dictionaryCorrection,
                    predictiveConversion = !sensitive && !reconversion && !rangeConversion && isPredictiveConversionEnabled(),
                    continuation = continuation).let {
                    if (rangeConversion) ConversionRangeSession.candidatesForRange(snapshot, it) else it
                }
                ensureActive()
                val contextCurrent = editorContextMatches(connection, context, sensitive)
                withContext(Dispatchers.Main) {
                    if (connection === currentInputConnection && requestGate.isCurrent(ticket, requestKey())) {
                        isConfirming = false
                        if (contextCurrent) {
                            val candidate = results.firstOrNull { !it.isReadingCompletion }
                            // Without a candidate the reading is only the fallback surface:
                            // commit it as the chosen text, never as a silent kana pass-through
                            // of a composition the engine may have failed to convert.
                            val surface = candidate?.text ?: currentSurface
                            commitText(surface + trailingText, committedCandidate = candidate, trailingText = trailingText)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    if (connection === currentInputConnection && requestGate.isCurrent(ticket, requestKey())) {
                        isConfirming = false
                        // Keep the composition instead of committing a surface the
                        // engine never produced; a retry can still convert it.
                        Log.e(TAG, "Confirmation failed; keeping the composition", error)
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    if (requestGate.isGenerationCurrent(ticket)) isConfirming = false
                }
            }
        }
    }

    fun confirmCurrent(trailingText: String = "") {
        confirmAsync(trailingText)
    }

    /**
     * Commits the reading bar text as typed: a Japanese reading stays hiragana instead of
     * waiting for a conversion, and an English buffer is committed as spelled. The tap is
     * an explicit choice of that text, so it records no conversion learning. A correction
     * applied automatically stays unconfirmed composition input; only the user's own
     * choice (typing on, Enter, or the bar's adopt button) may settle it.
     */
    fun commitReadingAsIs() {
        when (ReadingTap.decide(
            confirming = isConfirming,
            translationMode = isTranslationMode(),
            automaticCorrection = corrections.adoption == AiAssistAdoption.Automatic,
            japaneseReading = composingText.convertTarget,
            englishBuffer = englishComposing
        )) {
            ReadingTapCommit.Hiragana -> commitComposingReadingAsIs()
            ReadingTapCommit.English -> commitEnglishBuffer(useTopCandidate = false)
            ReadingTapCommit.Ignored -> Unit
        }
    }

    /**
     * The editor replaces the whole composition when text is committed, so the internal
     * caret moves to its end first; otherwise the uncommitted tail would remain as a
     * second composition behind the committed reading.
     */
    private fun commitComposingReadingAsIs() {
        if (currentInputConnection == null) return
        if (!composingText.isAtEndIndex) {
            composingText.moveCursorFromCursorPosition(composingText.convertTarget.length)
            syncComposingDisplayState()
        }
        commitText(composingText.convertTarget, committedCandidate = null)
    }

    fun handleNumericText(text: String) {
        if (isConfirming || text.isEmpty()) return
        if (isTranslationMode()) {
            appendTranslationLiteral(text)
            return
        }
        if (SymbolCandidateCatalog.shouldComposeSymbolText(text, composingText.convertTargetBeforeCursor)) {
            handleKana(text)
            return
        }
        if (!composingText.isEmpty) {
            confirmAsync(trailingText = text)
        } else {
            commitDirect(text)
        }
    }

    fun replaceLastNumericText(newText: String, oldLength: Int = 1) {
        if (isConfirming || newText.isEmpty()) return
        if (!composingText.isEmpty) {
            if (SymbolCandidateCatalog.isSymbolOnly(newText)) {
                replaceLastKana(newText)
                return
            }
            val ic = currentInputConnection
            if (ic != null) {
                composingText.deleteBackwardFromCursorPosition(count = oldLength.coerceAtLeast(1))
                val remaining = composingText.convertTarget
                if (remaining.isEmpty()) {
                    setManagedComposingText(ic, "", 1)
                    clearInput()
                    commitDirect(newText)
                } else {
                    confirmAsync(trailingText = newText)
                }
            }
            return
        }
        if (SymbolCandidateCatalog.isSymbolOnly(newText)) {
            val ic = currentInputConnection ?: return
            if (oldLength > 0) {
                ic.deleteSurroundingText(oldLength, 0)
            }
            handleKana(newText)
            return
        }
        replaceLastCommittedText(newText, oldLength)
    }

    fun commitDirect(text: String) {
        if (isTranslationMode() && !committingTranslation) {
            appendTranslationLiteral(text)
            return
        }
        val committed = currentInputConnection?.commitText(text, 1) == true
        clearInput()
        if (committed && text.isNotEmpty() && !isSensitiveInput()) {
            undoHistory.push(ImeUndoEntry.CommittedText(committedText = text))
        }
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
        // Known issue: this confirmation is asynchronous, so the emoji can be committed
        // ahead of the converted reading. Panel text uses the ordered commitPanelText path.
        val hadComposing = !composingText.isEmpty
        if (hadComposing) confirmCurrent()
        val committed = currentInputConnection?.commitText(emoji, 1) == true
        clearInput()
        if (committed && !hadComposing && !isSensitiveInput()) {
            undoHistory.push(ImeUndoEntry.CommittedText(committedText = emoji))
        }
        if (committed) recordRecentEmoji(emoji)
    }

    fun handleEnglishChar(ch: Char, touchBias: TouchKeyBias? = null) {
        if (isConfirming) return
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
        val isAtTrigger = ch == '@'
        val isDomainContinuation = englishComposing.startsWith("@") &&
            EnglishWordDictionary.isEmailDomainContinuationChar(ch) &&
            englishComposing.length < 64
        if (isAtTrigger) {
            if (!composingText.isEmpty) confirmCurrent()
            if (englishComposing.isNotEmpty()) commitEnglishBuffer(useTopCandidate = false)
        } else if (!ch.isLetter() && !isDomainContinuation) {
            if (englishComposing.isEmpty() &&
                SymbolCandidateCatalog.shouldComposeSymbol(ch, composingText.convertTargetBeforeCursor)
            ) {
                handleKana(ch.toString(), touchBias)
                return
            }
            if (!composingText.isEmpty) confirmCurrent()
            if (englishComposing.isNotEmpty()) commitEnglishBuffer(useTopCandidate = false)
            commitDirect(ch.toString())
            return
        } else if (!composingText.isEmpty) {
            confirmCurrent()
        }
        if (englishComposing.isEmpty()) {
            clearPostCompositionDisplay()
            val ic = currentInputConnection
            cachedLeftContext = EditorContext.bounded(ic?.getTextBeforeCursor(MAX_LEFT_CONTEXT_LEN * 2, 0)?.toString().orEmpty())
            englishTouchBiases.clear()
            englishCursorPosition = 0
        }
        if (!isSensitiveInput()) {
            undoHistory.push(
                ImeUndoEntry.EnglishComposing(
                    previousText = englishComposing,
                    touchBiases = englishTouchBiases.toList()
                )
            )
        }
        val insertAt = ComposingEditorState.snapBoundary(
            englishComposing,
            englishCursorPosition.coerceIn(0, englishComposing.length)
        )
        if (!isSensitiveInput()) {
            val lowered = ch.lowercaseChar()
            val biasEntry = if (touchBias != null && touchBias.typedChar.lowercaseChar() == lowered) {
                touchBias
            } else {
                TouchKeyBias(lowered)
            }
            englishTouchBiases.add(insertAt.coerceIn(0, englishTouchBiases.size), biasEntry)
        }
        englishComposing = englishComposing.substring(0, insertAt) + ch + englishComposing.substring(insertAt)
        englishCursorPosition = ComposingEditorState.snapBoundary(englishComposing, insertAt + 1)
        syncComposingDisplayState()
        setEnglishComposingText(currentInputConnection)
        triggerEnglishSuggestions(englishComposing)
    }

    fun handleEnglishText(text: String, touchBias: TouchKeyBias? = null) {
        if (text.length == 1) {
            handleEnglishChar(text[0], touchBias)
        } else {
            for (ch in text) handleEnglishChar(ch)
        }
    }

    fun handleEnglishDelete() {
        if (performEnglishDelete()) {
            vibrateOnKeyPress()
        }
    }

    private fun performEnglishDelete(): Boolean {
        if (isConfirming) return false
        if (isTranslationMode()) {
            if (englishComposing.isNotEmpty()) {
                val cursor = ComposingEditorState.snapBoundary(
                    englishComposing,
                    englishCursorPosition.coerceIn(0, englishComposing.length)
                )
                if (cursor <= 0) return false
                val previous = ComposingEditorState.moveByCodePoint(englishComposing, cursor, -1)
                englishComposing = englishComposing.substring(0, previous) + englishComposing.substring(cursor)
                englishCursorPosition = previous
                syncComposingDisplayState()
                if (englishComposing.isNotEmpty()) {
                    setEnglishComposingText(currentInputConnection)
                } else {
                    setManagedComposingText(currentInputConnection, "", 0)
                }
                scheduleTranslation()
                return true
            } else if (!composingText.isEmpty) {
                if (composingText.isAtStartIndex) return false
                val previous = ComposingEditorState.moveByCodePoint(composingText.convertTarget, composingText.convertTargetCursorPosition, -1)
                val deleteCount = composingText.convertTargetCursorPosition - previous
                if (deleteCount <= 0) return false
                composingText.deleteBackwardFromCursorPosition(deleteCount)
                syncCorrectionRevertState()
                syncComposingDisplayState()
                if (composingText.isEmpty) {
                    setManagedComposingText(currentInputConnection, "", 0)
                } else {
                    setManagedComposingText(currentInputConnection, composingText.convertTarget, 1)
                }
                scheduleTranslation()
                return true
            } else {
                return deleteDirectFromEditor(sendKeyEventFallback = false)
            }
        }
        if (englishComposing.isNotEmpty()) {
            val cursor = ComposingEditorState.snapBoundary(
                englishComposing,
                englishCursorPosition.coerceIn(0, englishComposing.length)
            )
            if (cursor <= 0) return false
            if (!isSensitiveInput()) {
                undoHistory.push(
                    ImeUndoEntry.EnglishComposing(
                        previousText = englishComposing,
                        touchBiases = englishTouchBiases.toList()
                    )
                )
            }
            val previous = ComposingEditorState.moveByCodePoint(englishComposing, cursor, -1)
            englishComposing = englishComposing.substring(0, previous) + englishComposing.substring(cursor)
            englishCursorPosition = previous
            if (englishTouchBiases.isNotEmpty()) {
                englishTouchBiases.removeAt(previous.coerceIn(0, englishTouchBiases.lastIndex))
            }
            syncComposingDisplayState()
            if (englishComposing.isNotEmpty()) {
                setEnglishComposingText(currentInputConnection)
                triggerEnglishSuggestions(englishComposing)
            } else {
                setManagedComposingText(currentInputConnection, "", 1)
                candidatesList.clear()
                predictedCandidate.value = ""
            }
            return true
        } else {
            return deleteDirectFromEditor(sendKeyEventFallback = false)
        }
    }

    private fun deleteDirectFromEditor(sendKeyEventFallback: Boolean = true): Boolean {
        clearPostCompositionDisplay()
        val ic = currentInputConnection ?: return false
        val textBefore = try {
            ic.getTextBeforeCursor(1, 0)
        } catch (_: RuntimeException) {
            null
        }
        val ok = ic.deleteSurroundingText(1, 0)
        if (ok && !textBefore.isNullOrEmpty() && !isSensitiveInput()) {
            undoHistory.push(ImeUndoEntry.DeletedText(textBefore.toString()))
        }
        if (!ok && sendKeyEventFallback) {
            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
        }
        return shouldVibrateOnEditorDelete(textBefore, ok)
    }

    fun handleEnglishSpace() {
        if (isTranslationMode()) {
            appendTranslationLiteral(" ")
            return
        }
        if (!composingText.isEmpty) {
            confirmCurrent()
            return
        }
        if (englishComposing.isNotEmpty()) {
            commitEnglishBuffer(useTopCandidate = isEnglishSpaceAdoptsCandidateState.value)
        }
        if (currentInputConnection?.commitText(" ", 1) == true && !isSensitiveInput()) {
            undoHistory.push(ImeUndoEntry.CommittedText(committedText = " "))
        }
    }

    fun commitEnglishBuffer(useTopCandidate: Boolean = true) {
        if (isTranslationMode()) {
            commitTranslationFromUi()
            return
        }
        if (englishComposing.isEmpty()) return
        val candidate = if (useTopCandidate) candidatesList.firstOrNull() else null
        val isRealSuggestion = candidate != null && (candidate.cost >= 0f || candidate.text != englishComposing)
        commitText(candidate?.text ?: englishComposing,
            isExplicitSelection = isRealSuggestion, committedCandidate = candidate)
    }

    private fun clearEnglishInput() {
        englishComposing = ""
        englishCursorPosition = 0
        japaneseTouchBiases.clear()
        englishTouchBiases.clear()
        syncComposingDisplayState()
        candidatesList.clear()
        predictedCandidate.value = ""
    }

    private fun triggerEnglishSuggestions(prefix: String) {
        if (isSensitiveInput()) {
            candidatesList.clear()
            predictedCandidate.value = ""
            return
        }
        val ticket = requestGate.capture(requestKey())
        val touchBiasesSnapshot = if (isTouchTypoCorrectionEnabledState.value) {
            englishTouchBiases.toList()
        } else {
            emptyList()
        }
        serviceScope.launch(Dispatchers.Default) {
            delay(30)
            if (englishComposing != prefix) return@launch
            val cands = if (touchBiasesSnapshot.isNotEmpty()) {
                EnglishWordDictionary.suggestWithTouchBias(applicationContext, prefix, touchBiasesSnapshot, 8)
            } else {
                EnglishWordDictionary.suggest(applicationContext, prefix, 8)
            }
            withContext(Dispatchers.Main) {
                if (englishComposing == prefix && !isSensitiveInput() && requestGate.isGenerationCurrent(ticket)) {
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
        if (keyboardModeState.value == KeyboardMode.ENGLISH &&
            !isTranslationMode() &&
            (text == "@" ||
                (englishComposing.startsWith("@") &&
                    text.all { EnglishWordDictionary.isEmailDomainContinuationChar(it) }))
        ) {
            handleEnglishText(text)
            return
        }
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
        trailingText: String = ""
    ) {
        if (isConfirming) return
        if (isTranslationMode() && !committingTranslation) {
            appendTranslationLiteral(text)
            return
        }
        val connection = currentInputConnection ?: return
        val wasEnglish = englishComposing.isNotEmpty()
        val englishReading = englishComposing
        val rawConsumed = committedCandidate?.engineConsumedReading
            ?.takeIf { it in 1..composingText.convertTargetCursorPosition }
            ?: composingText.convertTargetCursorPosition
        val consumed = ComposingEditorState.snapBoundary(composingText.convertTarget, rawConsumed)
        val readingBefore = composingText.convertTarget.take(consumed)
        val surfaceAfter = composingText.convertTarget.drop(consumed)
        val selection = if (!wasEnglish) ConversionCommitLearning.capture(
            readingBefore, text, committedCandidate, trailingText,
            sensitive = isSensitiveInput(), correctionApplied = corrections.isApplied,
            explicitSelection = isExplicitSelection
        ) else null
        val learnEnglish = wasEnglish && !isSensitiveInput() && LearningPolicy.canWrite
        val learningRevisionBefore = KanaKanjiConverter.learningHistoryRevision
        val learningPrivacyBefore = clipboardPrivacyGeneration
        val shouldPredictPostComposition = !wasEnglish &&
            surfaceAfter.isEmpty() &&
            !isSensitiveInput() &&
            !isTranslationMode() &&
            isPostCompositionPredictionEnabled() &&
            trailingText.isEmpty() &&
            (readingBefore.isNotEmpty() || committedCandidate != null)
        val postCompCandidateId = committedCandidate?.engineCandidateId.orEmpty()
        val postCompContext = if (shouldPredictPostComposition) {
            val baseContext = if (leftContextConnection === connection) {
                cachedLeftContext
            } else {
                readEditorLeftContext(connection).orEmpty()
            }
            if (baseContext.isNotEmpty()) EditorContext.bounded(baseContext + text) else text
        } else ""
        val undoComposingSnapshot = if (!wasEnglish && !composingText.isEmpty && !isSensitiveInput()) composingText.clone() else null
        val undoEnglish = if (wasEnglish && !isSensitiveInput()) englishReading else ""
        val undoLeftContext = cachedLeftContext
        val undoJpBiases = if (!wasEnglish && !isSensitiveInput()) japaneseTouchBiases.toList() else emptyList()
        val undoEnBiases = if (wasEnglish && !isSensitiveInput()) englishTouchBiases.toList() else emptyList()
        var committedTerminalComposition = false
        cancelPendingInference(edited = false)
        connection.beginBatchEdit()
        try {
            if (!connection.commitText(text, 1)) return
            if (text.isNotEmpty() && !isSensitiveInput()) {
                undoHistory.push(
                    ImeUndoEntry.CommittedText(
                        committedText = text,
                        previousComposing = undoComposingSnapshot,
                        previousEnglish = undoEnglish,
                        previousLeftContext = undoLeftContext,
                        japaneseTouchBiases = undoJpBiases,
                        englishTouchBiases = undoEnBiases
                    )
                )
            }
            composingEditor.finishComposition()
            conversionRange.clear()
            conversionRangeState.value = ""
            // A tap is not a training event. Only a successful editor commit is.
            recordCommittedCorrections(text, if (wasEnglish) "" else readingBefore)
            if (selection != null || learnEnglish) {
                val event = if (learnEnglish) {
                    val englishWord = if (trailingText.isNotEmpty() && text.endsWith(trailingText)) {
                        text.removeSuffix(trailingText)
                    } else {
                        text
                    }
                    PendingLearning(
                        reading = englishReading,
                        surface = englishWord,
                        english = true,
                        historyRevision = learningRevisionBefore,
                        englishExplicit = isExplicitSelection,
                        privacyGeneration = learningPrivacyBefore
                    )
                } else {
                    PendingLearning(checkNotNull(selection).reading, selection.surface, false, selection,
                        learningRevisionBefore, privacyGeneration = learningPrivacyBefore)
                }
                if (learningQueue.trySend(event).isFailure) {
                    Log.w(TAG, "Selection learning queue is full or closed")
                }
            }
            if (wasEnglish) {
                clearEnglishInput()
                return
            }
            if (surfaceAfter.isNotEmpty()) {
                val fullReadingBeforeCommit = composingText.convertTarget
                cachedLeftContext = EditorContext.bounded(cachedLeftContext + text)
                composingText.prefixComplete(readingBefore.length)
                TouchBiasBuffer.trimToMaxCharCountFromStart(
                    japaneseTouchBiases,
                    composingText.input.count { it.piece is InputPiece.CharPiece }
                )
                corrections.edited()
                syncCorrectionRevertState()
                val remainingSurface = composingText.convertTarget
                val remainingReading = composingText.convertTargetBeforeCursor
                syncComposingDisplayState()
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
                committedTerminalComposition = true
            }
        } finally { connection.endBatchEdit() }
        if (committedTerminalComposition && shouldPredictPostComposition) {
            triggerPostCompositionPredictions(
                connection = connection,
                candidateId = postCompCandidateId,
                committedText = text,
                committedReading = readingBefore,
                context = postCompContext
            )
        }
    }

    private fun clearCandidateList() {
        syncCorrectionRevertState()
        clearPostCompositionDisplay(clearCandidates = false)
        candidatesList.clear()
        predictedCandidate.value = ""
        lastInferredSurface = ""
        lastInferredResult = ""
        inferenceHistory.clear()
        aiAssistResultState.value = null
    }

    fun clearInput() {
        cancelPendingInference()
        corrections.clear()
        reconverting = false
        hasSelectionInEditor = false
        selectionAvailableState.value = false
        clearSelectionCharacterCount()
        aiAssistAdoptionState.value = AiAssistAdoption.None
        savedOriginalCandidates = emptyList()
        savedOriginalSurface = ""
        savedOriginalReading = ""
        composingText.stopComposition()
        englishComposing = ""
        englishCursorPosition = 0
        japaneseTouchBiases.clear()
        englishTouchBiases.clear()
        syncComposingDisplayState()
        predictedCandidate.value = ""
        lastInferredSurface = ""
        lastInferredResult = ""
        candidatesList.clear()
        cachedLeftContext = ""
        leftContextConnection = null
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
        cancelClipboardEdit()
        exitTranslationMode(revert = false)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        reloadPreferences()
        clearInput()
        undoHistory.clear()
        applyInputTypeMode(info)
        enterKeyImeOptionsState.intValue = info?.imeOptions ?: 0
        inputViewEpochState.value++
        hasSelectionInEditor = info != null && info.initialSelStart >= 0 && info.initialSelStart != info.initialSelEnd
        selectionAvailableState.value = hasSelectionInEditor
        updateSelectionCharacterCount(info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1)
        if (pendingScreenshotScan) {
            pendingScreenshotScan = false
            scheduleScreenshotCapture(immediate = true, force = true)
        }
    }

    override fun onStartInput(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        cancelClipboardEdit()
        updateInputPrivacy(info)
        enterKeyImeOptionsState.intValue = info?.imeOptions ?: 0
        exitTranslationMode(revert = false)
        clearInput()
        undoHistory.clear()
        hasSelectionInEditor = info != null && info.initialSelStart >= 0 && info.initialSelStart != info.initialSelEnd
        selectionAvailableState.value = hasSelectionInEditor
        updateSelectionCharacterCount(info?.initialSelStart ?: -1, info?.initialSelEnd ?: -1)
        if (pendingScreenshotScan) {
            pendingScreenshotScan = false
            scheduleScreenshotCapture(immediate = true, force = true)
        }
    }

    override fun onFinishInput() {
        cancelClipboardEdit()
        updateInputPrivacy(null)
        enterKeyImeOptionsState.intValue = 0
        exitTranslationMode(revert = false)
        clearInput()
        undoHistory.clear()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelClipboardEdit()
        floatingKeyboardHost?.cancelDrag()
        exitTranslationMode(revert = false)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        clearInput()
        undoHistory.clear()
    }

    override fun onDestroy() {
        learningQueue.close()
        translationJob?.cancel()
        selectionTranslationJob?.cancel()
        translationLoadJob?.cancel()
        translationReadyCheckJob?.cancel()
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
