package com.zeroconvert.ime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.zeroconvert.ime.benchmark.BenchmarkKind
import com.zeroconvert.ime.benchmark.BenchmarkProgress
import com.zeroconvert.ime.benchmark.BenchmarkRunner
import com.zeroconvert.ime.model.ModelInfo
import com.zeroconvert.ime.model.ModelManager
import com.zeroconvert.ime.translation.TranslationEngine
import com.zeroconvert.ime.translation.TranslationModelManager
import com.zeroconvert.ime.translation.TranslationText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val modelStatus = mutableStateOf("ZeroCon Model をロード中…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CoroutineScope(Dispatchers.IO).launch {
            KanaKanjiConverter.ensureInitialized(applicationContext)
            val ok = ZenzNative.init(applicationContext)
            modelStatus.value = if (ok) {
                "${ZenzNative.currentModelName} — 準備完了"
            } else {
                "エラー: モデルのロードに失敗しました"
            }
        }

        setContent {
            MainScreen(
                modelStatus = modelStatus.value,
                onModelStatusChange = { modelStatus.value = it },
                onEnableKeyboard = {
                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                    startActivity(intent)
                },
                onSelectKeyboard = {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.showInputMethodPicker()
                }
            )
        }
    }
}

@Composable
fun MainScreen(
    modelStatus: String,
    onModelStatusChange: (String) -> Unit = {},
    onEnableKeyboard: () -> Unit,
    onSelectKeyboard: () -> Unit
) {
    val context = LocalContext.current
    val imePrefs = remember {
        context.getSharedPreferences(ZeroConvertInputMethodService.PREFS_SETTINGS, Context.MODE_PRIVATE)
    }

    var themeMode by remember {
        mutableStateOf(imePrefs.getString(ZeroConvertInputMethodService.KEY_THEME_MODE, "system") ?: "system")
    }
    val palette = rememberZeroConPalette(themeMode)

    var testText by remember { mutableStateOf("") }
    var selectedTab by rememberSaveable { mutableStateOf("input") }
    val scrollState = rememberScrollState()

    fun isKeyboardEnabled(): Boolean =
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
    fun isKeyboardSelected(): Boolean =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.contains(context.packageName) == true

    var keyboardEnabled by remember { mutableStateOf(isKeyboardEnabled()) }
    var keyboardSelected by remember { mutableStateOf(isKeyboardSelected()) }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                keyboardEnabled = isKeyboardEnabled()
                keyboardSelected = isKeyboardSelected()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (!keyboardSelected) {
            delay(500)
            keyboardEnabled = isKeyboardEnabled()
            keyboardSelected = isKeyboardSelected()
        }
    }
    LaunchedEffect(selectedTab) {
        scrollState.scrollTo(0)
    }
    val coroutineScope = rememberCoroutineScope()

    var isRealtimeInline by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_REALTIME_INLINE, true))
    }
    var japaneseLayout by remember {
        mutableStateOf(
            imePrefs.getString(
                ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT,
                JapaneseInputLayout.FLICK_12KEY.name
            ) ?: JapaneseInputLayout.FLICK_12KEY.name
        )
    }
    var isFlickOnly by remember {
        mutableStateOf(
            imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_FLICK_ONLY, true)
        )
    }
    var isQwertyNumberRow by remember {
        mutableStateOf(
            imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_QWERTY_NUMBER_ROW, false)
        )
    }
    var isFollowInputTypeMode by remember {
        mutableStateOf(
            imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_FOLLOW_INPUT_TYPE_MODE, false)
        )
    }
    var keyboardKeyHeight by remember {
        mutableStateOf(imePrefs.getInt(ZeroConvertInputMethodService.KEY_KEY_HEIGHT_DP, 46).coerceIn(40, 64))
    }
    var isVibrationEnabled by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_VIBRATION_ENABLED, true))
    }
    var vibrationDuration by remember {
        mutableStateOf(imePrefs.getInt(ZeroConvertInputMethodService.KEY_VIBRATION_DURATION, 15))
    }
    var isKeyPreviewEnabled by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_KEY_PREVIEW_ENABLED, true))
    }
    var flickSensitivityDp by remember {
        mutableStateOf(imePrefs.getInt(ZeroConvertInputMethodService.KEY_FLICK_SENSITIVITY_DP, 18).coerceIn(12, 28))
    }
    var isKeyBordersEnabled by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_KEY_BORDERS_ENABLED, true))
    }
    var oneHandedMode by remember {
        mutableStateOf(
            OneHandedMode.fromName(
                imePrefs.getString(ZeroConvertInputMethodService.KEY_ONE_HANDED_MODE, OneHandedMode.OFF.name)
            )
        )
    }
    var keyboardBottomPaddingDp by remember {
        mutableStateOf(imePrefs.getInt(ZeroConvertInputMethodService.KEY_KEYBOARD_BOTTOM_PADDING_DP, 0).coerceIn(0, 32))
    }
    val initialTouchAssistSettings = remember { TouchAssistSettings.load(imePrefs) }
    var isDynamicTouchAssistEnabled by remember {
        mutableStateOf(initialTouchAssistSettings.hitAreaEnabled)
    }
    var isTouchTypoCorrectionEnabled by remember {
        mutableStateOf(initialTouchAssistSettings.correctionEnabled)
    }
    var isAutoCommitEnabled by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_AUTO_COMMIT_ENABLED, true))
    }
    var isAutoCommitRewind by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_AUTO_COMMIT_REWIND_ON_BACKSPACE, false))
    }
    var isAiAssistEnabled by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_EXPERIMENTAL_AI_ASSIST, true))
    }
    var isDictionaryTypoCorrection by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_DICTIONARY_TYPO_CORRECTION,
            ZeroConvertInputMethodService.DEFAULT_DICTIONARY_TYPO_CORRECTION))
    }
    var isPredictiveConversion by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_PREDICTIVE_CONVERSION, true))
    }
    var isEnglishSpaceAdoptsCandidate by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_ENGLISH_SPACE_ADOPTS_CANDIDATE, false))
    }
    var learningMode by remember { mutableStateOf(LearningMode.from(imePrefs.getString(LearningPolicy.KEY, null))) }
    var learningMenuExpanded by remember { mutableStateOf(false) }
    var isAutoTypoCorrection by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_AUTO_TYPO_CORRECTION, true))
    }

    var aiProfile by remember {
        mutableStateOf(imePrefs.getString(ZeroConvertInputMethodService.KEY_AI_PROFILE, "") ?: "")
    }
    var clipboardRetention by remember {
        mutableStateOf(imePrefs.getInt(ZeroConvertInputMethodService.KEY_CLIPBOARD_RETENTION_HOURS, 1))
    }
    var clipboardSuggestionMinutes by remember {
        mutableStateOf(ClipboardSuggestionPolicy.normalizeMinutes(
            imePrefs.getInt(
                ZeroConvertInputMethodService.KEY_CLIPBOARD_SUGGESTION_MINUTES,
                ClipboardSuggestionPolicy.DEFAULT_MINUTES
            )
        ))
    }
    var screenshotCapture by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_SCREENSHOT_CAPTURE, true))
    }
    var translationEnabled by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_TRANSLATION_ENABLED, false))
    }
    var translationKeepLoaded by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_TRANSLATION_KEEP_LOADED, false))
    }
    var translationTarget by remember {
        mutableStateOf(
            imePrefs.getString(ZeroConvertInputMethodService.KEY_TRANSLATION_TARGET, TranslationText.AUTO)
                ?: TranslationText.AUTO
        )
    }
    var translationModelReady by remember { mutableStateOf(TranslationModelManager.isReady(context)) }
    var translationProgress by remember { mutableStateOf<TranslationModelManager.Progress?>(null) }
    var translationDownloadError by remember { mutableStateOf<String?>(null) }
    var showTranslationDownloadDialog by remember { mutableStateOf(false) }
    var translationDownloadJob by remember { mutableStateOf<Job?>(null) }
    var translationTestInput by remember { mutableStateOf("会議は明日です。") }
    var translationTestResult by remember { mutableStateOf("未実行") }
    var translationTestRunning by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val screenshotPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        android.Manifest.permission.READ_MEDIA_IMAGES
    } else {
        android.Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val screenshotPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.widget.Toast.makeText(
                context,
                "スクリーンショットの自動保存には写真へのアクセスを許可してください",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    var showScreenshotPermissionRationale by remember { mutableStateOf(false) }
    LaunchedEffect(screenshotCapture) {
        if (screenshotCapture && context.checkSelfPermission(screenshotPermission) != PackageManager.PERMISSION_GRANTED) {
            showScreenshotPermissionRationale = true
        }
    }

    var userDictList by remember { mutableStateOf<List<UserDictEntry>>(emptyList()) }
    var newDictReading by remember { mutableStateOf("") }
    var newDictWord by remember { mutableStateOf("") }
    var learningCount by remember { mutableStateOf(0) }
    var typoLearningCount by remember { mutableStateOf(0) }
    var showLearningResetDialog by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        val loaded = withContext(Dispatchers.IO) {
            // The learned counts live in the shared engine, so settings must wait for
            // the same initialization the IME performs instead of showing a stale zero.
            ZenzNative.init(context)
            Triple(
                KanaKanjiConverter.getUserDictionary(context),
                KanaKanjiConverter.getLearningCount(context),
                KanaKanjiConverter.getTypoLearningCount(context)
            )
        }
        userDictList = loaded.first
        learningCount = loaded.second
        typoLearningCount = loaded.third
    }

    var benchmarkProgress by remember {
        mutableStateOf<BenchmarkProgress?>(null)
    }

    var availableModels by remember {
        mutableStateOf<List<ModelInfo>>(emptyList())
    }
    var isImporting by remember { mutableStateOf(false) }
    var directTestResult by remember { mutableStateOf("未実行") }
    LaunchedEffect(context) {
        availableModels = withContext(Dispatchers.IO) {
            ModelManager.getAvailableModels(context)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isImporting = true
                val res = ModelManager.importModelFromUri(context, uri)
                res.onSuccess { newModel: ModelInfo ->
                    availableModels = ModelManager.getAvailableModels(context)
                    ModelManager.selectModel(context, newModel)
                    availableModels = ModelManager.getAvailableModels(context)
                    onModelStatusChange("${ZenzNative.currentModelName} — 準備完了")
                }
                isImporting = false
            }
        }
    }

    fun applyTranslationEnabled(on: Boolean) {
        if (!on) {
            translationEnabled = false
            imePrefs.edit()
                .putBoolean(ZeroConvertInputMethodService.KEY_TRANSLATION_ENABLED, false)
                .apply()
            return
        }
        if (translationModelReady || TranslationModelManager.isReady(context)) {
            translationModelReady = true
            translationEnabled = true
            imePrefs.edit()
                .putBoolean(ZeroConvertInputMethodService.KEY_TRANSLATION_ENABLED, true)
                .apply()
        } else if (translationDownloadJob?.isActive != true) {
            showTranslationDownloadDialog = true
        }
    }

    fun startTranslationDownload() {
        if (translationDownloadJob?.isActive == true) return
        translationDownloadError = null
        translationProgress = TranslationModelManager.Progress(0)
        translationDownloadJob = coroutineScope.launch {
            val result = TranslationModelManager.download(context) { progress ->
                coroutineScope.launch(Dispatchers.Main) { translationProgress = progress }
            }
            translationProgress = null
            result.onSuccess {
                translationDownloadError = null
                translationModelReady = true
                translationEnabled = true
                imePrefs.edit()
                    .putBoolean(ZeroConvertInputMethodService.KEY_TRANSLATION_ENABLED, true)
                    .apply()
                android.widget.Toast.makeText(context, "翻訳モデルを保存しました", android.widget.Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                translationDownloadError = error.message ?: "ダウンロードに失敗しました"
                translationModelReady = TranslationModelManager.isReady(context)
                if (!translationModelReady) {
                    translationEnabled = false
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_TRANSLATION_ENABLED, false)
                        .apply()
                }
            }
        }
    }

    @Composable
    fun SetupSectionContent() {
        SettingsSection("キーボードの準備", palette) {
            SettingsRow(
                title = "キーボードを有効化",
                description = "Androidの設定でZeroCon Keyboardを有効にします。",
                palette = palette
            ) {
                SettingsPrimaryButton(
                    text = "開く",
                    palette = palette,
                    onClick = onEnableKeyboard
                )
            }
            SettingsDivider(palette)
            SettingsRow(
                title = "入力方法を選択",
                description = if (keyboardEnabled) {
                    "現在の入力方法をZeroCon Keyboardに切り替えます。"
                } else {
                    "先にキーボードを有効化してください。"
                },
                palette = palette
            ) {
                SettingsSecondaryButton(
                    text = "選択",
                    palette = palette,
                    onClick = onSelectKeyboard,
                    enabled = keyboardEnabled
                )
            }
        }
        Text(
            text = "キーボードを有効化してZeroCon Keyboardを選択すると、入力テストと各設定が利用できます。",
            color = palette.textMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }

    @Composable
    fun InputTabContent() {
        SettingsSection("入力テスト", palette) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "入力テストエリア",
                    color = palette.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    placeholder = { Text("ここをタップして ZeroCon Keyboard をテスト", color = palette.textMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    colors = settingsTextFieldColors(palette)
                )
            }
        }

        SettingsSection("キーボード", palette) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "日本語入力キーボード配列",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                SettingsSegmented(
                    options = listOf(
                        JapaneseInputLayout.FLICK_12KEY.name to "12キー（フリック）",
                        JapaneseInputLayout.QWERTY.name to "QWERTY（ローマ字）"
                    ),
                    selected = japaneseLayout,
                    palette = palette,
                    onSelected = { layout ->
                        japaneseLayout = layout
                        imePrefs.edit()
                            .putString(ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT, layout)
                            .apply()
                    }
                )
            }

            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val realKeyboardWidth = LocalConfiguration.current.screenWidthDp.dp
                val previewScale = (maxWidth / realKeyboardWidth).coerceAtMost(1f)
                // Mirror the IME: the two bars above the keys plus the key area, which grows to
                // five rows only for the QWERTY layout with the number row shown. Reserving the
                // bars keeps the bottom row inside the frame instead of clipping it.
                val previewRowCount = if (
                    japaneseLayout == JapaneseInputLayout.QWERTY.name && isQwertyNumberRow
                ) 5 else 4
                val previewBarHeight = (keyboardKeyHeight * 0.8f).dp
                val realKeyboardHeight = previewBarHeight * 2 +
                    keyboardContentHeight(keyboardKeyHeight.dp, previewRowCount) +
                    keyboardBottomPaddingDp.dp
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(realKeyboardHeight * previewScale)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.canvas)
                ) {
                    Layout(
                        content = {
                            ProvideKeyboardErgonomicsLocals(
                                palette = palette,
                                onKeyPressHaptic = {},
                                keyPreviewEnabled = isKeyPreviewEnabled,
                                flickThresholdDp = flickSensitivityDp.dp,
                                keyBordersEnabled = isKeyBordersEnabled,
                                dynamicTouchAssistEnabled = isDynamicTouchAssistEnabled
                            ) {
                            CompositionLocalProvider(LocalKeyboardKeyHeight provides keyboardKeyHeight.dp) {
                                Column(Modifier.requiredWidth(realKeyboardWidth).height(realKeyboardHeight)) {
                                    KeyboardAssistBar(
                                        reading = "",
                                        assist = null,
                                        correctionApplied = false,
                                        palette = palette,
                                        onAdopt = {},
                                        onUndo = {}
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(KeyboardBarHeight)
                                            .background(palette.surface)
                                    ) {
                                        HorizontalCandidateStrip(
                                            reading = "",
                                            candidates = listOf(Candidate("候補", "")),
                                            palette = palette,
                                            onSelect = {},
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    val previewKeyboardBody: @Composable () -> Unit = {
                                        if (japaneseLayout == JapaneseInputLayout.FLICK_12KEY.name) {
                                            Japanese12KeyFlickLayout(
                                                keyBgColor = palette.keyNormal,
                                                actionKeyBg = palette.keyModifier,
                                                textColor = palette.textPrimary,
                                                palette = palette,
                                                rowHeight = keyboardRowHeight(
                                                    keyboardKeyHeight.dp,
                                                    rowCount = 5,
                                                    targetRowCount = 4
                                                ),
                                                isFlickOnly = isFlickOnly,
                                                onKanaInput = {},
                                                onReplaceLastKana = {},
                                                onToggleDakuten = {},
                                                onDelete = {},
                                                onSpace = {},
                                                onEnter = {},
                                                onCycleMode = {},
                                                onOpenSymbols = {},
                                                onOpenEmoji = {},
                                                onCursorLeft = {},
                                                onCursorRight = {}
                                            )
                                        } else {
                                            JapaneseQwertyLayout(
                                                isShifted = false,
                                                keyBgColor = palette.keyNormal,
                                                actionKeyBg = palette.keyModifier,
                                                textColor = palette.textPrimary,
                                                palette = palette,
                                                onKeyPress = {},
                                                onShiftToggle = {},
                                                onDelete = {},
                                                onSpace = {},
                                                onEnter = {},
                                                onCycleMode = {},
                                                onOpenEmoji = {},
                                                isComposing = false,
                                                onCursorLeft = {},
                                                onCursorRight = {},
                                                showNumberRow = isQwertyNumberRow
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                    ) {
                                        if (oneHandedMode == OneHandedMode.OFF) {
                                            previewKeyboardBody()
                                        } else {
                                            val saveOneHanded: (OneHandedMode) -> Unit = { next ->
                                                oneHandedMode = next
                                                imePrefs.edit()
                                                    .putString(ZeroConvertInputMethodService.KEY_ONE_HANDED_MODE, next.name)
                                                    .apply()
                                            }
                                            Row(modifier = Modifier.fillMaxSize()) {
                                                if (oneHandedMode == OneHandedMode.RIGHT) {
                                                    OneHandedSideRail(
                                                        modifier = Modifier.weight(0.18f).fillMaxHeight(),
                                                        actionKeyBg = palette.keyModifier,
                                                        textColor = palette.textPrimary,
                                                        palette = palette,
                                                        isLeftAligned = false,
                                                        onFlipSide = { saveOneHanded(OneHandedMode.LEFT) },
                                                        onExitOneHanded = { saveOneHanded(OneHandedMode.OFF) }
                                                    )
                                                    Box(modifier = Modifier.weight(0.82f).fillMaxHeight()) {
                                                        previewKeyboardBody()
                                                    }
                                                } else {
                                                    Box(modifier = Modifier.weight(0.82f).fillMaxHeight()) {
                                                        previewKeyboardBody()
                                                    }
                                                    OneHandedSideRail(
                                                        modifier = Modifier.weight(0.18f).fillMaxHeight(),
                                                        actionKeyBg = palette.keyModifier,
                                                        textColor = palette.textPrimary,
                                                        palette = palette,
                                                        isLeftAligned = true,
                                                        onFlipSide = { saveOneHanded(OneHandedMode.RIGHT) },
                                                        onExitOneHanded = { saveOneHanded(OneHandedMode.OFF) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (keyboardBottomPaddingDp > 0) {
                                        Spacer(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(keyboardBottomPaddingDp.dp)
                                                .background(palette.canvas)
                                        )
                                    }
                                }
                            }
                            }
                        }
                    ) { measurables, constraints ->
                        val realWidthPx = with(density) { realKeyboardWidth.roundToPx() }
                        val realHeightPx = with(density) { realKeyboardHeight.roundToPx() }
                        val keyboard = measurables.single().measure(Constraints.fixed(realWidthPx, realHeightPx))
                        val previewHeightPx = (realHeightPx * previewScale).roundToInt()
                            .coerceIn(constraints.minHeight, constraints.maxHeight)
                        layout(constraints.maxWidth, previewHeightPx) {
                            keyboard.placeWithLayer(0, 0) {
                                scaleX = previewScale
                                scaleY = previewScale
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                            }
                        }
                    }
                }
            }

            SettingsDivider(palette)
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "キーの高さ",
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${keyboardKeyHeight} dp",
                        color = palette.primaryAccentSoft,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = keyboardKeyHeight.toFloat(),
                    onValueChange = { value ->
                        keyboardKeyHeight = value.toInt().coerceIn(40, 64)
                        imePrefs.edit()
                            .putInt(ZeroConvertInputMethodService.KEY_KEY_HEIGHT_DP, keyboardKeyHeight)
                            .apply()
                    },
                    valueRange = 40f..64f,
                    steps = 23,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.primaryAccent,
                        activeTrackColor = palette.primaryAccent,
                        inactiveTrackColor = palette.surfaceElevated
                    )
                )
                Text(
                    text = "QWERTYと12キーのキーボード全体の高さに反映されます。",
                    color = palette.textMuted,
                    fontSize = 12.sp
                )
            }
            SettingsDivider(palette)
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "片手モード",
                    color = palette.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                SettingsSegmented(
                    options = listOf(
                        OneHandedMode.OFF.name to "オフ",
                        OneHandedMode.LEFT.name to "左寄せ",
                        OneHandedMode.RIGHT.name to "右寄せ"
                    ),
                    selected = oneHandedMode.name,
                    palette = palette,
                    onSelected = { modeName ->
                        val next = OneHandedMode.fromName(modeName)
                        oneHandedMode = next
                        imePrefs.edit()
                            .putString(ZeroConvertInputMethodService.KEY_ONE_HANDED_MODE, next.name)
                            .apply()
                    }
                )
                Text(
                    text = "キーボードの幅を約82%にして左右どちらかに寄せます。ツールバーや側面ボタンからいつでも解除できます。",
                    color = palette.textMuted,
                    fontSize = 12.sp
                )
            }
            SettingsDivider(palette)
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "下部余白（底上げ）",
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${keyboardBottomPaddingDp} dp",
                        color = palette.primaryAccentSoft,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = keyboardBottomPaddingDp.toFloat(),
                    onValueChange = { value ->
                        keyboardBottomPaddingDp = value.toInt().coerceIn(0, 32)
                        imePrefs.edit()
                            .putInt(ZeroConvertInputMethodService.KEY_KEYBOARD_BOTTOM_PADDING_DP, keyboardBottomPaddingDp)
                            .apply()
                    },
                    valueRange = 0f..32f,
                    steps = 31,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.primaryAccent,
                        activeTrackColor = palette.primaryAccent,
                        inactiveTrackColor = palette.surfaceElevated
                    )
                )
                Text(
                    text = "画面下端のベゼルやジェスチャーバーと最下段キーの間に余白を設け、指を届きやすくします（標準: 0 dp）。",
                    color = palette.textMuted,
                    fontSize = 12.sp
                )
            }
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "上部の数字キー",
                description = if (isQwertyNumberRow) {
                    "QWERTY配列の最上段に 1〜0 の数字キーを表示します。"
                } else {
                    "数字キーを隠し、最上段の q〜p を上フリックすると 1〜0 を入力します。"
                },
                checked = isQwertyNumberRow,
                palette = palette,
                onCheckedChange = { checked ->
                    isQwertyNumberRow = checked
                    imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_QWERTY_NUMBER_ROW, checked).apply()
                }
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "フリックのみ",
                description = if (isFlickOnly) {
                    "連続タップしても文字が進まず、フリックで各段を入力します。"
                } else {
                    "連続タップで あ→い→う→え→お とトグル入力します。"
                },
                checked = isFlickOnly,
                palette = palette,
                onCheckedChange = { checked ->
                    isFlickOnly = checked
                    imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_FLICK_ONLY, checked).apply()
                }
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "入力欄に合わせてモードを切替",
                description = "数値・電話・URL・メール等の入力欄では、その入力欄に合ったモードで"
                    + "キーボードを開きます。開いた後に切り替えたモードはそのまま使えます。",
                checked = isFollowInputTypeMode,
                palette = palette,
                onCheckedChange = { checked ->
                    isFollowInputTypeMode = checked
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_FOLLOW_INPUT_TYPE_MODE, checked)
                        .apply()
                }
            )
            SettingsDivider(palette)
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "フリック感度",
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${flickSensitivityDp} dp",
                        color = palette.primaryAccentSoft,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Slider(
                    value = flickSensitivityDp.toFloat(),
                    onValueChange = { value ->
                        flickSensitivityDp = value.toInt().coerceIn(12, 28)
                        imePrefs.edit()
                            .putInt(ZeroConvertInputMethodService.KEY_FLICK_SENSITIVITY_DP, flickSensitivityDp)
                            .apply()
                    },
                    valueRange = 12f..28f,
                    steps = 15,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.primaryAccent,
                        activeTrackColor = palette.primaryAccent,
                        inactiveTrackColor = palette.surfaceElevated
                    )
                )
                Text(
                    text = "値が小さいほど短い指の動きでフリックが反応し、大きいほど誤フリックを抑えます（標準: 18 dp）。",
                    color = palette.textMuted,
                    fontSize = 12.sp
                )
            }
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "キー拡大プレビュー",
                description = "QWERTYでキーに触れた瞬間に、押している文字をキー上に拡大表示します。",
                checked = isKeyPreviewEnabled,
                palette = palette,
                onCheckedChange = { checked ->
                    isKeyPreviewEnabled = checked
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_KEY_PREVIEW_ENABLED, checked)
                        .apply()
                }
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "キー枠線と立体表示",
                description = "キーの境界線と立体的な縁取りを表示し、明暗テーマでキー位置を見分けやすくします。",
                checked = isKeyBordersEnabled,
                palette = palette,
                onCheckedChange = { checked ->
                    isKeyBordersEnabled = checked
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_KEY_BORDERS_ENABLED, checked)
                        .apply()
                }
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "キーの当たり判定を調整",
                description = "QWERTYで次に押しそうなキーの判定範囲を広げ、押しやすくします。",
                checked = isDynamicTouchAssistEnabled,
                palette = palette,
                onCheckedChange = { checked ->
                    isDynamicTouchAssistEnabled = checked
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_DYNAMIC_TOUCH_ASSIST, checked)
                        .apply()
                }
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "タッチ位置による候補の重み付け",
                description = "「タイポ補正」がONのとき、日本語QWERTYの押下位置を使って近接キーの補正候補を重み付けします。英語では押下位置を使って補正候補を並べます。",
                checked = isTouchTypoCorrectionEnabled,
                palette = palette,
                onCheckedChange = { checked ->
                    isTouchTypoCorrectionEnabled = checked
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_TOUCH_TYPO_CORRECTION, checked)
                        .apply()
                }
            )
        }

        SettingsSection("操作フィードバック", palette) {
            SettingsSwitchRow(
                title = "キー操作バイブレーション",
                description = "キーやボタンに触れた瞬間（押下時のみ）に触覚フィードバックを発生させます。",
                checked = isVibrationEnabled,
                palette = palette,
                onCheckedChange = { checked ->
                    isVibrationEnabled = checked
                    imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_VIBRATION_ENABLED, checked).apply()
                }
            )
            if (isVibrationEnabled) {
                SettingsDivider(palette)
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "振動の長さ: ${vibrationDuration}ms",
                            color = palette.textSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        SettingsSecondaryButton(
                            text = "テスト",
                            palette = palette,
                            modifier = Modifier.height(32.dp),
                            onClick = {
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                                        vm?.defaultVibrator?.vibrate(
                                            android.os.VibrationEffect.createOneShot(
                                                vibrationDuration.toLong(),
                                                android.os.VibrationEffect.DEFAULT_AMPLITUDE
                                            )
                                        )
                                    } else {
                                        @Suppress("DEPRECATION")
                                        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            v?.vibrate(
                                                android.os.VibrationEffect.createOneShot(
                                                    vibrationDuration.toLong(),
                                                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                                                )
                                            )
                                        } else {
                                            @Suppress("DEPRECATION")
                                            v?.vibrate(vibrationDuration.toLong())
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        )
                    }
                    Slider(
                        value = vibrationDuration.toFloat(),
                        onValueChange = { value ->
                            vibrationDuration = value.toInt()
                            imePrefs.edit()
                                .putInt(ZeroConvertInputMethodService.KEY_VIBRATION_DURATION, vibrationDuration)
                                .apply()
                        },
                        valueRange = 5f..50f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = palette.primaryAccent,
                            activeTrackColor = palette.primaryAccent,
                            inactiveTrackColor = palette.surfaceElevated
                        )
                    )
                }
            }
        }

        SettingsSection("入力方法", palette) {
            SettingsRow(
                title = "キーボードを有効化",
                description = "Androidの設定でZeroCon Keyboardを有効にします。",
                palette = palette
            ) {
                SettingsSecondaryButton(
                    text = "開く",
                    palette = palette,
                    onClick = onEnableKeyboard
                )
            }
            SettingsDivider(palette)
            SettingsRow(
                title = "入力方法を選択",
                description = "現在の入力方法をZeroCon Keyboardに切り替えます。",
                palette = palette
            ) {
                SettingsSecondaryButton(
                    text = "選択",
                    palette = palette,
                    onClick = onSelectKeyboard,
                    enabled = keyboardEnabled
                )
            }
        }
    }

    @Composable
    fun ConversionTabContent() {
        SettingsSection("変換動作", palette) {
            SettingsSwitchRow(
                title = "入力中のリアルタイムAI変換",
                description = if (isRealtimeInline) {
                    "エディタ上の文字をZeroCon AIの推論結果で置換し、上部に原文を表示します。"
                } else {
                    "エディタ上はひらがなを表示し、候補バーから変換を選択します。"
                },
                checked = isRealtimeInline,
                palette = palette,
                onCheckedChange = { checked ->
                    isRealtimeInline = checked
                    imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_REALTIME_INLINE, checked).apply()
                }
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "ストリーミング自動確定",
                description = "16文字以上の入力中、直近5回のAI推論で変化しなかった先頭の文節を自動確定し、文脈を維持します。",
                checked = isAutoCommitEnabled,
                palette = palette,
                onCheckedChange = { checked ->
                    isAutoCommitEnabled = checked
                    imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_AUTO_COMMIT_ENABLED, checked).apply()
                }
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "自動確定直後のBackspaceで未確定に戻す",
                description = "自動確定された直後にBackspaceを押した際、確定済みテキストを未確定のひらがなに戻して再変換できるようにします。",
                checked = isAutoCommitRewind,
                palette = palette,
                onCheckedChange = { checked ->
                    isAutoCommitRewind = checked
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_AUTO_COMMIT_REWIND_ON_BACKSPACE, checked)
                        .apply()
                }
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "予測変換（単語・フレーズ）",
                description = "入力中の読みをもとに、続きの単語・フレーズを通常候補に表示します。文の続きの提案は「文の先読み」で設定します。",
                checked = isPredictiveConversion,
                palette = palette,
                onCheckedChange = { checked ->
                    isPredictiveConversion = checked
                    imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_PREDICTIVE_CONVERSION, checked).apply()
                }
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "英字入力のスペースで先頭候補を採用",
                description = if (isEnglishSpaceAdoptsCandidate) {
                    "英字モードでスペースを押すと、変換候補の先頭を確定してからスペースを入力します。"
                } else {
                    "英字モードでスペースを押すと、入力したままの文字を確定してスペースを入力します。"
                },
                checked = isEnglishSpaceAdoptsCandidate,
                palette = palette,
                onCheckedChange = { checked ->
                    isEnglishSpaceAdoptsCandidate = checked
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_ENGLISH_SPACE_ADOPTS_CANDIDATE, checked)
                        .apply()
                }
            )
        }

        SettingsSection("タイポ補正と先読み", palette) {
            SettingsSwitchRow(
                title = "タイポ補正",
                description = "辞書・打鍵の補正規則とZeroCon Modelで誤入力を補正します。通常変換で補正できなかった入力は、モデルによる補正候補を提案します。補正候補はタップで反映できます。",
                checked = isDictionaryTypoCorrection,
                palette = palette,
                onCheckedChange = { checked ->
                    isDictionaryTypoCorrection = checked
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_DICTIONARY_TYPO_CORRECTION, checked)
                        .apply()
                }
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "文の先読み",
                badge = "試験",
                description = "変換結果と文脈をもとに、ZeroCon Modelが文の続きを提案します。候補をタップすると採用・確定します。",
                checked = isAiAssistEnabled,
                palette = palette,
                onCheckedChange = { checked ->
                    isAiAssistEnabled = checked
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_EXPERIMENTAL_AI_ASSIST, checked)
                        .apply()
                }
            )
            if (isDictionaryTypoCorrection) {
                SettingsDivider(palette)
                SettingsSwitchRow(
                    title = "補正候補を自動で反映",
                    description = "通常変換の後に生成した候補のうち、採用・確定の実績や一意な打鍵修正で裏付けられた補正を自動で反映します。確定前ならBackspaceで元に戻せます。",
                    checked = isAutoTypoCorrection,
                    palette = palette,
                    onCheckedChange = { enabled ->
                        isAutoTypoCorrection = enabled
                        imePrefs.edit()
                            .putBoolean(ZeroConvertInputMethodService.KEY_AUTO_TYPO_CORRECTION, enabled)
                            .apply()
                    }
                )
            }
        }

        SettingsSection("ZeroCon AI プロファイル", palette) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "プロファイル",
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${aiProfile.length} / 25",
                        color = palette.textMuted,
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = "専門分野や書き手のペルソナを指定すると、ZeroCon AIが文脈に合わせて同音異義語を優先変換します。",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                OutlinedTextField(
                    value = aiProfile,
                    onValueChange = { value ->
                        val capped = value.take(25)
                        aiProfile = capped
                        imePrefs.edit().putString(ZeroConvertInputMethodService.KEY_AI_PROFILE, capped).apply()
                    },
                    placeholder = { Text("例: ソフトウェアエンジニア", color = palette.textMuted, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = settingsTextFieldColors(palette)
                )
            }
        }
    }

    @Composable
    fun DataTabContent() {
        SettingsSection("学習と履歴", palette) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "学習と履歴の使用",
                    color = palette.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "選択した変換結果を学習し、次回同じ読みを入力した際の候補順位に反映します。",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Box {
                    SettingsSecondaryButton(
                        text = learningMode.label,
                        palette = palette,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { learningMenuExpanded = true }
                    )
                    DropdownMenu(
                        expanded = learningMenuExpanded,
                        onDismissRequest = { learningMenuExpanded = false }
                    ) {
                        LearningMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = {
                                    learningMode = mode
                                    learningMenuExpanded = false
                                    imePrefs.edit().putString(LearningPolicy.KEY, mode.storageValue).apply()
                                }
                            )
                        }
                    }
                }
            }
            SettingsDivider(palette)
            SettingsRow(
                title = "学習データ",
                description = "変換学習 ${learningCount}件 / 補正学習 ${typoLearningCount}件。候補バーで選択した結果を記憶し、次回同じ読みで優先表示します。",
                palette = palette
            ) {
                SettingsSecondaryButton(
                    text = "学習を削除",
                    palette = palette,
                    onClick = { showLearningResetDialog = true }
                )
            }
            if (showLearningResetDialog) {
                LearningResetDialog(
                    palette = palette,
                    onDismiss = { showLearningResetDialog = false },
                    onConfirm = { conversion, typo ->
                        showLearningResetDialog = false
                        coroutineScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                val ok = KanaKanjiConverter.clearAllLearning(context, conversion, typo)
                                Triple(
                                    ok,
                                    KanaKanjiConverter.getLearningCount(context),
                                    KanaKanjiConverter.getTypoLearningCount(context)
                                )
                            }
                            learningCount = result.second
                            typoLearningCount = result.third
                            Toast.makeText(
                                context,
                                if (result.first) "学習を削除しました" else "一部の学習を削除できませんでした",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }

        SettingsSection("ユーザー辞書", palette) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "登録数: ${userDictList.size}件",
                        color = palette.primaryAccentSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "よく使う固有名詞や専門用語を登録すると、候補バーの上位に表示されます。",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newDictReading,
                        onValueChange = { newDictReading = it },
                        placeholder = { Text("よみ（ひらがな）", color = palette.textMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = settingsTextFieldColors(palette)
                    )
                    OutlinedTextField(
                        value = newDictWord,
                        onValueChange = { newDictWord = it },
                        placeholder = { Text("単語（表記）", color = palette.textMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = settingsTextFieldColors(palette)
                    )
                }
                SettingsPrimaryButton(
                    text = "辞書に追加",
                    palette = palette,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    onClick = {
                        val reading = newDictReading
                        val word = newDictWord
                        coroutineScope.launch {
                            val updated = withContext(Dispatchers.IO) {
                                if (KanaKanjiConverter.addUserDictEntry(context, reading, word))
                                    KanaKanjiConverter.getUserDictionary(context) else null
                            }
                            if (updated != null) {
                                userDictList = updated
                                if (newDictReading == reading && newDictWord == word) {
                                    newDictReading = ""
                                    newDictWord = ""
                                }
                            }
                        }
                    }
                )
                if (userDictList.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        userDictList.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(palette.surfaceElevated)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = entry.reading,
                                        color = palette.primaryAccentSoft,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "→",
                                        color = palette.textMuted,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = entry.word,
                                        color = palette.textPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            userDictList = withContext(Dispatchers.IO) {
                                                KanaKanjiConverter.removeUserDictEntry(context, entry)
                                                KanaKanjiConverter.getUserDictionary(context)
                                            }
                                        }
                                    }
                                ) {
                                    Text("削除", color = palette.danger, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        SettingsSection("クリップボード", palette) {
            ClipboardSuggestionDurationSetting(clipboardSuggestionMinutes, palette) { minutes ->
                clipboardSuggestionMinutes = minutes
                imePrefs.edit()
                    .putInt(ZeroConvertInputMethodService.KEY_CLIPBOARD_SUGGESTION_MINUTES, minutes)
                    .apply()
            }
            SettingsDivider(palette)
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "履歴の保持時間: ${clipboardRetention}時間",
                    color = palette.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Slider(
                    value = clipboardRetention.toFloat(),
                    onValueChange = { value ->
                        clipboardRetention = value.toInt().coerceIn(1, 24)
                        imePrefs.edit()
                            .putInt(ZeroConvertInputMethodService.KEY_CLIPBOARD_RETENTION_HOURS, clipboardRetention)
                            .apply()
                    },
                    valueRange = 1f..24f,
                    steps = 22,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.primaryAccent,
                        activeTrackColor = palette.primaryAccent,
                        inactiveTrackColor = palette.surfaceElevated
                    )
                )
            }
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "スクリーンショットを自動保存",
                description = "新しいスクリーンショットをクリップボード履歴に自動追加します。",
                checked = screenshotCapture,
                palette = palette,
                onCheckedChange = { checked ->
                    if (checked && context.checkSelfPermission(screenshotPermission) != PackageManager.PERMISSION_GRANTED) {
                        showScreenshotPermissionRationale = true
                    }
                    screenshotCapture = checked
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_SCREENSHOT_CAPTURE, checked)
                        .apply()
                }
            )
        }
    }

    @Composable
    fun TranslationTabContent() {
        SettingsSection("翻訳入力", palette) {
            SettingsNote(
                text = "日本語入力の未確定文を翻訳し、Enterで訳文を確定します。選択文字列の翻訳・挿入にも対応します。翻訳は端末内で実行され、入力文は送信されません。",
                palette = palette
            )
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "翻訳機能を有効にする",
                description = if (translationModelReady) {
                    "モデル: ダウンロード済み"
                } else {
                    "モデル: 未ダウンロード"
                },
                checked = translationEnabled,
                palette = palette,
                onCheckedChange = { applyTranslationEnabled(it) }
            )
            if (translationEnabled && !translationModelReady) {
                SettingsNote(
                    text = "翻訳モデルがありません。下のボタンからダウンロードしてください。",
                    palette = palette
                )
            }
            val downloading = translationProgress
            if (downloading != null) {
                SettingsDivider(palette)
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { downloading.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = palette.primaryAccent,
                        trackColor = palette.surfaceElevated
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (downloading.downloaded >= downloading.total) {
                                "保存・検証中…（SHA-256）"
                            } else {
                                "${downloading.downloaded / (1024 * 1024)} / ${downloading.total / (1024 * 1024)} MB"
                            },
                            color = palette.textSecondary,
                            fontSize = 12.sp
                        )
                        TextButton(onClick = {
                            translationDownloadJob?.cancel()
                            translationDownloadJob = null
                            translationProgress = null
                            translationDownloadError = "ダウンロードを中断しました（次回再開できます）"
                        }) {
                            Text("中断", fontSize = 12.sp)
                        }
                    }
                }
            }
            translationDownloadError?.let { message ->
                SettingsNote(text = message, palette = palette)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsPrimaryButton(
                    text = if (translationModelReady) "再ダウンロード" else "モデルをダウンロード",
                    palette = palette,
                    onClick = {
                        if (translationModelReady) {
                            startTranslationDownload()
                        } else {
                            showTranslationDownloadDialog = true
                        }
                    },
                    modifier = Modifier.weight(1f).height(42.dp),
                    enabled = downloading == null
                )
                if (translationModelReady) {
                    SettingsSecondaryButton(
                        text = "削除",
                        palette = palette,
                        onClick = {
                            translationEnabled = false
                            translationModelReady = false
                            translationTestResult = "未実行"
                            imePrefs.edit()
                                .putBoolean(ZeroConvertInputMethodService.KEY_TRANSLATION_ENABLED, false)
                                .apply()
                            coroutineScope.launch(Dispatchers.IO) {
                                TranslationModelManager.delete(context)
                            }
                        },
                        modifier = Modifier.height(42.dp),
                        enabled = !translationEnabled && downloading == null
                    )
                }
            }
            SettingsDivider(palette)
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "翻訳方向",
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                SettingsSegmented(
                    options = listOf(
                        TranslationText.AUTO to "自動",
                        TranslationText.EN to "英語へ",
                        TranslationText.JA to "日本語へ"
                    ),
                    selected = translationTarget,
                    palette = palette,
                    onSelected = { value ->
                        translationTarget = value
                        imePrefs.edit()
                            .putString(ZeroConvertInputMethodService.KEY_TRANSLATION_TARGET, value)
                            .apply()
                    }
                )
            }
            SettingsDivider(palette)
            SettingsSwitchRow(
                title = "翻訳モデルを常時読み込む",
                description = "OFFでは翻訳モード終了時に約573MBのモデルを解放します。",
                checked = translationKeepLoaded,
                palette = palette,
                onCheckedChange = { enabled ->
                    translationKeepLoaded = enabled
                    imePrefs.edit()
                        .putBoolean(ZeroConvertInputMethodService.KEY_TRANSLATION_KEEP_LOADED, enabled)
                        .apply()
                }
            )
            SettingsDivider(palette)
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "翻訳テスト",
                    color = palette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                OutlinedTextField(
                    value = translationTestInput,
                    onValueChange = { translationTestInput = it.take(512) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("原文", fontSize = 11.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    colors = settingsTextFieldColors(palette)
                )
                SettingsSecondaryButton(
                    text = if (translationTestRunning) "翻訳中…" else "翻訳を実行",
                    palette = palette,
                    enabled = translationModelReady && !translationTestRunning && downloading == null &&
                        translationTestInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    onClick = {
                        if (!translationTestRunning) {
                            translationTestRunning = true
                            translationTestResult = "翻訳中…"
                            coroutineScope.launch {
                                val input = translationTestInput.trim()
                                val result = withContext(Dispatchers.IO) {
                                    val file = TranslationModelManager.modelFile(context)
                                    if (!TranslationModelManager.isReady(context) || !TranslationEngine.load(file)) {
                                        "モデルを読み込めません"
                                    } else {
                                        val target = TranslationText.targetFor(input, translationTarget)
                                        val completion = TranslationEngine.translate(
                                            TranslationText.prompt(input, target),
                                            512,
                                            15_000
                                        )
                                        if (completion.finished && TranslationText.validOutput(completion.text)) {
                                            completion.text.trim()
                                        } else {
                                            "失敗: ${completion.stopReason}"
                                        }
                                    }
                                }
                                if (!translationKeepLoaded) {
                                    withContext(Dispatchers.IO) { TranslationEngine.close() }
                                }
                                translationTestResult = result
                                translationTestRunning = false
                            }
                        }
                    }
                )
                Text(
                    text = "結果: $translationTestResult",
                    color = palette.primaryAccentSoft,
                    fontSize = 12.sp
                )
            }
        }
    }

    @Composable
    fun BenchmarkTabContent() {
        SettingsSection("ZeroCon Model", palette) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "現在のモデル",
                            color = palette.textMuted,
                            fontSize = 11.sp
                        )
                        Text(
                            text = ZenzNative.currentModelName,
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    SettingsBadge("使用中", palette)
                }
                availableModels.forEach { model ->
                    SettingsInnerCard(palette) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = model.name,
                                        color = if (model.isSelected) palette.primaryAccentSoft else palette.textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (model.isSelected) FontWeight.SemiBold else FontWeight.Medium
                                    )
                                    if (model.isSelected) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = "使用中",
                                            tint = palette.readyEmerald,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = model.sizeMb,
                                    color = palette.textMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!model.isSelected) {
                                    SettingsPrimaryButton(
                                        text = "切替",
                                        palette = palette,
                                        modifier = Modifier.height(32.dp),
                                        onClick = {
                                            coroutineScope.launch {
                                                ModelManager.selectModel(context, model)
                                                availableModels = ModelManager.getAvailableModels(context)
                                                onModelStatusChange("${ZenzNative.currentModelName} — 準備完了")
                                            }
                                        }
                                    )
                                }
                                if (!model.isDefault) {
                                    IconButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                val models = withContext(Dispatchers.IO) {
                                                    ModelManager.deleteModel(context, model)
                                                    ModelManager.getAvailableModels(context)
                                                }
                                                availableModels = models
                                                onModelStatusChange("${ZenzNative.currentModelName} — 準備完了")
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Delete,
                                            contentDescription = "モデルを削除",
                                            tint = palette.textSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                SettingsSecondaryButton(
                    text = if (isImporting) "モデルをコピー中…" else "GGUFモデルを追加",
                    palette = palette,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    enabled = !isImporting,
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
                )
            }
        }

        SettingsSection("単体推論テスト", palette) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ZeroCon AIがモデル単体で正しく推論できるかを確認します。",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                SettingsSecondaryButton(
                    text = "単体推論を実行（ワタシ → ?）",
                    palette = palette,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    onClick = {
                        coroutineScope.launch {
                            val (result, elapsed) = withContext(Dispatchers.IO) {
                                val t0 = System.currentTimeMillis()
                                val res = ZenzNative.convert("", "ワタシ", aiProfile)
                                res to (System.currentTimeMillis() - t0)
                            }
                            directTestResult = "「ワタシ」→「$result」 (${elapsed}ms)"
                        }
                    }
                )
                Text(
                    text = "結果: $directTestResult",
                    color = palette.primaryAccentSoft,
                    fontSize = 13.sp
                )
            }
        }

        SettingsSection("評価ベンチマーク", palette) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "端末上で評価データセットを一括推論し、正解率と平均レイテンシを測定します。",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "評価データセット",
                        color = palette.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    SettingsBadge("最新", palette)
                }
                Text(
                    text = "${BenchmarkRunner.CONVERSION_DATASET_NAME}（基本230・高難度250） / " +
                        "${BenchmarkRunner.TYPO_DATASET_NAME}（タイポ255・正常入力45） / " +
                        BenchmarkRunner.PREDICTIVE_DATASET_NAME,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                SettingsPrimaryButton(
                    text = if (benchmarkProgress?.isRunning == true) {
                        "ベンチマーク実行中…"
                    } else {
                        "かな漢字変換ベンチマークを実行"
                    },
                    palette = palette,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    enabled = benchmarkProgress?.isRunning != true,
                    onClick = {
                        coroutineScope.launch {
                            BenchmarkRunner.runBenchmark(context) { progress ->
                                benchmarkProgress = progress
                            }
                        }
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsSecondaryButton(
                        text = BenchmarkRunner.TYPO_DATASET_NAME,
                        palette = palette,
                        modifier = Modifier.weight(1f).height(38.dp),
                        enabled = benchmarkProgress?.isRunning != true,
                        onClick = {
                            coroutineScope.launch {
                                BenchmarkRunner.runTypoBenchmark(context) { progress ->
                                    benchmarkProgress = progress
                                }
                            }
                        }
                    )
                    SettingsSecondaryButton(
                        text = BenchmarkRunner.PREDICTIVE_DATASET_NAME,
                        palette = palette,
                        modifier = Modifier.weight(1f).height(38.dp),
                        enabled = benchmarkProgress?.isRunning != true,
                        onClick = {
                            coroutineScope.launch {
                                BenchmarkRunner.runPredictiveBenchmark(context) { progress ->
                                    benchmarkProgress = progress
                                }
                            }
                        }
                    )
                }
                benchmarkProgress?.let { progress ->
                    val fraction = if (progress.total > 0) {
                        progress.current.toFloat() / progress.total
                    } else {
                        0f
                    }
                    val accuracy = if (progress.current > 0) {
                        progress.perfectCount.toFloat() / progress.current * 100f
                    } else {
                        0f
                    }
                    SettingsDivider(palette)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = if (progress.isFinished) palette.readyEmerald else palette.primaryAccent,
                        trackColor = palette.surfaceElevated
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "進捗: ${progress.current} / ${progress.total} 問（${(fraction * 100).toInt()}%）",
                            color = palette.textPrimary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "平均: ${String.format("%.1f", progress.avgLatencyMs)} ms/問",
                            color = palette.warningAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "正解率: ${String.format("%.1f", accuracy)}%（${progress.perfectCount}/${progress.current}）",
                            color = palette.readyEmerald,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        when (progress.kind) {
                            BenchmarkKind.CONVERSION -> Text(
                                text = "同音異義: ${if (progress.homophoneTotal > 0) String.format("%.1f", progress.homophonePerfect.toFloat() / progress.homophoneTotal * 100f) else "0.0"}%",
                                color = palette.primaryAccentSoft,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            BenchmarkKind.TYPO -> Text(
                                text = "誤補正: ${progress.falsePositiveCount}件",
                                color = if (progress.falsePositiveCount > 0) palette.danger else palette.readyEmerald,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            BenchmarkKind.PREDICTIVE -> Unit
                        }
                    }
                    if (progress.kind == BenchmarkKind.TYPO) {
                        Text(
                            text = "正常入力保持: ${progress.homophonePerfect} / ${progress.homophoneTotal}",
                            color = palette.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                    SettingsInnerCard(palette) {
                        Text(
                            text = progress.lastItemDetail,
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun AppearanceTabContent() {
        SettingsSection("カラーテーマ", palette) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Windows版設定画面と同じ6種類の配色を、設定画面とIMEキーボードに適用します。",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                SettingsThemePicker(
                    selected = themeMode,
                    palette = palette,
                    onSelected = { mode ->
                        themeMode = mode
                        imePrefs.edit()
                            .putString(ZeroConvertInputMethodService.KEY_THEME_MODE, mode)
                            .apply()
                    }
                )
                SettingsInnerCard(palette) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(palette.primaryAccent, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "あ",
                                color = palette.onAccent,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Column {
                            Text(
                                text = "プレビュー — ${resolveZeroConTheme(themeMode, isSystemInDarkTheme()).label}",
                                color = palette.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "候補: 日本語 / にほんご",
                                color = palette.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AboutTabContent() {
        SettingsSection("ZeroCon Keyboard", palette) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ZeroConLogo(size = 44.dp)
                    Column {
                        Text(
                            text = "ZeroCon Keyboard",
                            color = palette.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "バージョン ${BuildConfig.VERSION_NAME}",
                            color = palette.textMuted,
                            fontSize = 12.sp
                        )
                    }
                }
                Text(
                    text = "ZeroCon Model Nano v4 によるかな漢字変換と、メインモデル統合のタイポ補正を搭載しています。",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            SettingsDivider(palette)
            SettingsRow(
                title = "変換モデル",
                description = "かな漢字変換・タイポ補正・先読みで共用します。",
                palette = palette
            ) {
                Text(
                    text = ZenzNative.currentModelName,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    maxLines = 2
                )
            }
            SettingsDivider(palette)
            SettingsRow(
                title = "推論",
                description = "入力文は端末内で処理されます。",
                palette = palette
            ) {
                Text(
                    text = "オンデバイス",
                    color = palette.textSecondary,
                    fontSize = 12.sp
                )
            }
        }

        SettingsSection("オープンソースライセンス", palette) {
            LicenseRow(
                title = "azooKey / AzooKeyKanaKanjiConverter",
                license = "MIT License — Copyright (c) 2023-2026 Miwa (ensan-hcl)",
                palette = palette
            )
            SettingsDivider(palette)
            LicenseRow(
                title = "Zenz / zenz-v3.2 Neural Models",
                license = "Apache License 2.0 — Copyright (c) Miwa Keita (ensan-hcl)",
                palette = palette
            )
            SettingsDivider(palette)
            LicenseRow(
                title = "LOUDS System Dictionary & Emoji Dictionary",
                license = "Apache License 2.0 — Miwa (ensan-hcl) / Mozc & Unicode",
                palette = palette
            )
            SettingsDivider(palette)
            LicenseRow(
                title = "PLaMo 3 Translate",
                license = "PLaMo Community License — Copyright (c) Preferred Networks, Inc.",
                palette = palette
            )
            SettingsDivider(palette)
            LicenseRow(
                title = "llama.cpp",
                license = "MIT License — Copyright (c) 2023-2026 Georgi Gerganov and contributors",
                palette = palette
            )
        }
    }

    val colorScheme = remember(palette) { palette.toColorScheme() }
    val typography = remember { zeroConTypography() }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.canvas)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    ZeroConLogo(size = 48.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ZeroCon Keyboard",
                            color = palette.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp
                        )
                        Text(
                            text = "ZeroCon AI 搭載 — オンデバイス日本語入力",
                            color = palette.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
                val statusText = modelStatus.removePrefix("● ").trim()
                SettingsStatusPill(
                    text = statusText,
                    ready = statusText.contains("準備完了"),
                    palette = palette
                )
            }

            if (keyboardSelected) {
                SettingsTabBar(
                    tabs = SettingsTabs,
                    selectedId = selectedTab,
                    palette = palette,
                    onSelected = { selectedTab = it }
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    when (selectedTab) {
                        "input" -> InputTabContent()
                        "conversion" -> ConversionTabContent()
                        "data" -> DataTabContent()
                        "translation" -> TranslationTabContent()
                        "benchmark" -> BenchmarkTabContent()
                        "appearance" -> AppearanceTabContent()
                        "about" -> AboutTabContent()
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    SetupSectionContent()
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }

        if (showScreenshotPermissionRationale) {
            AlertDialog(
                onDismissRequest = { showScreenshotPermissionRationale = false },
                title = { Text("写真へのアクセスを許可") },
                text = {
                    Text(
                        "スクリーンショットをキーボードのクリップボード履歴へ自動保存するため、写真と動画へのアクセスが必要です。保存対象はスクリーンショットだけです。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showScreenshotPermissionRationale = false
                        screenshotPermissionLauncher.launch(screenshotPermission)
                    }) { Text("許可する") }
                },
                dismissButton = {
                    TextButton(onClick = { showScreenshotPermissionRationale = false }) { Text("後で") }
                }
            )
        }

        if (showTranslationDownloadDialog) {
            AlertDialog(
                onDismissRequest = { showTranslationDownloadDialog = false },
                title = { Text("翻訳モデルをダウンロード") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "PLaMo 3 Translate 0.8B Q4_K_M（約547MB）をHugging Faceからダウンロードします。",
                            fontSize = 13.sp
                        )
                        Text(
                            text = "モバイル回線では通信量に注意してください。モデルはPLaMo Community Licenseで提供されます。読み込み中は数百MBのメモリを使用します。",
                            fontSize = 12.sp
                        )
                        TextButton(onClick = {
                            uriHandler.openUri(TranslationModelManager.LICENSE_URL)
                        }) {
                            Text("PLaMo Community License を開く", fontSize = 12.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showTranslationDownloadDialog = false
                        startTranslationDownload()
                    }) {
                        Text("同意してダウンロード")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showTranslationDownloadDialog = false }) {
                        Text("キャンセル")
                    }
                }
            )
        }
    }
}

@Composable
private fun LearningResetDialog(
    palette: ZeroConPalette,
    onDismiss: () -> Unit,
    onConfirm: (conversion: Boolean, typo: Boolean) -> Unit
) {
    var conversion by remember { mutableStateOf(true) }
    var typo by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("学習データを削除") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "削除する学習を選んでください。ユーザー辞書と設定は削除されません。",
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                LearningScopeRow("変換学習", "選んだ変換結果と読みの対応", conversion, palette) { conversion = it }
                LearningScopeRow("補正学習", "誤入力の学習履歴", typo, palette) { typo = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = conversion || typo,
                onClick = { onConfirm(conversion, typo) }
            ) { Text("削除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@Composable
private fun LearningScopeRow(
    title: String,
    description: String,
    checked: Boolean,
    palette: ZeroConPalette,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(description, color = palette.textMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LicenseRow(
    title: String,
    license: String,
    palette: ZeroConPalette
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = license,
            color = palette.textMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}
