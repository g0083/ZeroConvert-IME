package com.zeroconvert.ime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
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
                "● ${ZenzNative.currentModelName} — 準備完了"
            } else {
                "エラー: モデルのロードに失敗しました"
            }
        }

        setContent {
            MaterialTheme {
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
}

@Composable
fun MainScreen(
    modelStatus: String,
    onModelStatusChange: (String) -> Unit = {},
    onEnableKeyboard: () -> Unit,
    onSelectKeyboard: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val imePrefs = remember {
        context.getSharedPreferences(ZeroConvertInputMethodService.PREFS_SETTINGS, Context.MODE_PRIVATE)
    }

    var themeMode by remember {
        mutableStateOf(imePrefs.getString(ZeroConvertInputMethodService.KEY_THEME_MODE, "system") ?: "system")
    }
    val palette = rememberZeroConPalette(themeMode)

    var testText by remember { mutableStateOf("") }
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
    var keyboardKeyHeight by remember {
        mutableStateOf(imePrefs.getInt(ZeroConvertInputMethodService.KEY_KEY_HEIGHT_DP, 46).coerceIn(40, 64))
    }
    var isVibrationEnabled by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_VIBRATION_ENABLED, true))
    }
    var vibrationDuration by remember {
        mutableStateOf(imePrefs.getInt(ZeroConvertInputMethodService.KEY_VIBRATION_DURATION, 15))
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
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_DICTIONARY_TYPO_CORRECTION, false))
    }
    var isPredictiveConversion by remember {
        mutableStateOf(imePrefs.getBoolean(ZeroConvertInputMethodService.KEY_PREDICTIVE_CONVERSION, true))
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
        mutableStateOf(ClipboardSuggestionPolicy.normalizeMinutes(imePrefs.getInt(
            ZeroConvertInputMethodService.KEY_CLIPBOARD_SUGGESTION_MINUTES, ClipboardSuggestionPolicy.DEFAULT_MINUTES)))
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

    var userDictList by remember { mutableStateOf<List<UserDictEntry>>(emptyList()) }
    var newDictReading by remember { mutableStateOf("") }
    var newDictWord by remember { mutableStateOf("") }
    var learningCount by remember { mutableStateOf(0) }
    LaunchedEffect(context) {
        val loaded = withContext(Dispatchers.IO) {
            KanaKanjiConverter.getUserDictionary(context) to KanaKanjiConverter.getLearningCount(context)
        }
        userDictList = loaded.first
        learningCount = loaded.second
    }

    var benchmarkProgress by remember {
        mutableStateOf<BenchmarkProgress?>(null)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas)
            .verticalScroll(scrollState)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // --- Header: ZeroCon Logo & Brand Identity ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ZeroConLogo(size = 54.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ZeroCon Keyboard",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary
                )
                Text(
                    text = "ZeroCon AI 搭載 — オンデバイスニューラル日本語入力",
                    fontSize = 12.sp,
                    color = palette.textSecondary,
                    lineHeight = 17.sp
                )
            }
        }

        // --- Status Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.borderSubtle),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = modelStatus,
                    color = if (modelStatus.contains("準備完了")) palette.readyEmerald else palette.warningAmber,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // --- Setup Action Buttons ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onEnableKeyboard,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primaryAccent)
            ) {
                Text("1. キーボードを有効化", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 2)
            }

            if (keyboardEnabled) {
                Button(
                    onClick = onSelectKeyboard,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.keyNormal)
                ) {
                    Text("2. 入力方法を選択", fontSize = 13.sp, color = palette.textPrimary, fontWeight = FontWeight.Medium, maxLines = 2)
                }
            }
        }

        if (keyboardSelected) {
        // --- Input Test Area ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.borderSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "入力テストエリア",
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    placeholder = { Text("ここをタップして ZeroCon Keyboard をテスト...", color = palette.textMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary,
                        focusedBorderColor = palette.primaryAccent,
                        unfocusedBorderColor = palette.borderSubtle
                    )
                )
            }
        }

        // --- ZeroCon AI Conversion & Auto-Commit Settings Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.borderSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "ZeroCon AI 変換・入力設定",
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary,
                    fontSize = 15.sp
                )

                // 0. Japanese Keyboard Layout (12-Key Flick vs QWERTY)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "日本語入力キーボード配列",
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        fontSize = 13.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val is12Key = (japaneseLayout == JapaneseInputLayout.FLICK_12KEY.name)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (is12Key) palette.primaryAccent else palette.keyModifier,
                            border = BorderStroke(1.dp, if (is12Key) palette.primaryAccentSoft else palette.borderSubtle),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    japaneseLayout = JapaneseInputLayout.FLICK_12KEY.name
                                    imePrefs.edit().putString(ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT, japaneseLayout).apply()
                                }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "12キー（フリック）",
                                    color = if (is12Key) Color.White else palette.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (is12Key) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }

                        val isQwerty = (japaneseLayout == JapaneseInputLayout.QWERTY.name)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isQwerty) palette.primaryAccent else palette.keyModifier,
                            border = BorderStroke(1.dp, if (isQwerty) palette.primaryAccentSoft else palette.borderSubtle),
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    japaneseLayout = JapaneseInputLayout.QWERTY.name
                                    imePrefs.edit().putString(ZeroConvertInputMethodService.KEY_JAPANESE_LAYOUT, japaneseLayout).apply()
                                }
                        ) {
                            Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "QWERTY（ローマ字）",
                                    color = if (isQwerty) Color.White else palette.textPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = if (isQwerty) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                // Render at the device keyboard's real width, then scale the entire
                // result uniformly. Reflowing it into this narrower settings card would
                // otherwise change key proportions and clip the flick helper text.
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val realKeyboardWidth = LocalConfiguration.current.screenWidthDp.dp
                    val previewScale = (maxWidth / realKeyboardWidth).coerceAtMost(1f)
                    val realKeyboardHeight = (keyboardKeyHeight * 5 + 35).dp
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
                                CompositionLocalProvider(LocalKeyboardKeyHeight provides keyboardKeyHeight.dp) {
                                    Box(
                                        modifier = Modifier
                                            .requiredWidth(realKeyboardWidth)
                                            .height(realKeyboardHeight)
                                            .padding(top = 4.dp, bottom = 6.dp, start = 3.dp, end = 3.dp)
                                    ) {
                                if (japaneseLayout == JapaneseInputLayout.FLICK_12KEY.name) {
                                    Japanese12KeyFlickLayout(
                                        keyBgColor = palette.keyNormal,
                                        actionKeyBg = palette.keyModifier,
                                        textColor = palette.textPrimary,
                                        palette = palette,
                                        rowHeight = (keyboardKeyHeight * (52f / 46f)).dp,
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
                                        onKeyPress = {},
                                        onShiftToggle = {},
                                        onDelete = {},
                                        onSpace = {},
                                        onEnter = {},
                                        onCycleMode = {},
                                        onOpenEmoji = {},
                                        isComposing = false,
                                        onCursorLeft = {},
                                        onCursorRight = {}
                                    )
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

                // 0b. Flick Only Switch
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "キーの高さ",
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${keyboardKeyHeight} dp",
                            color = palette.primaryAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
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
                            inactiveTrackColor = palette.keyModifier
                        )
                    )
                    Text(
                        text = "QWERTYと12キーのキーボード全体の高さに反映されます。",
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "フリックのみ（連続同一タップを即時入力）",
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isFlickOnly) {
                                "【ON (推奨)】「ああ」と連続タップしても文字が進まず即時入力されます。フリックで各段を入力します。"
                            } else {
                                "【OFF】連続タップで あ→い→う→え→お とケータイ打ち（トグル入力）します。"
                            },
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = isFlickOnly,
                        onCheckedChange = { checked ->
                            isFlickOnly = checked
                            imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_FLICK_ONLY, checked).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = palette.primaryAccent,
                            uncheckedThumbColor = palette.textSecondary,
                            uncheckedTrackColor = palette.keyModifier
                        )
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.borderSubtle))

                // Tap Vibration (Haptic Feedback) Settings
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "キー操作バイブレーション（タップ時の振動）",
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "キー入力やフリック、デリート操作時に心地よい触覚振動フィードバックを発生させます。",
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = isVibrationEnabled,
                        onCheckedChange = { checked ->
                            isVibrationEnabled = checked
                            imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_VIBRATION_ENABLED, checked).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = palette.primaryAccent,
                            uncheckedThumbColor = palette.textSecondary,
                            uncheckedTrackColor = palette.keyModifier
                        )
                    )
                }

                if (isVibrationEnabled) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "振動の長さ: ${vibrationDuration}ms",
                                fontSize = 12.sp,
                                color = palette.textSecondary,
                                fontWeight = FontWeight.Medium
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = palette.primaryAccent.copy(alpha = 0.15f),
                                modifier = Modifier.clickable {
                                    try {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                                            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                                            vm?.defaultVibrator?.vibrate(android.os.VibrationEffect.createOneShot(vibrationDuration.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                        } else {
                                            @Suppress("DEPRECATION")
                                            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
                                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                v?.vibrate(android.os.VibrationEffect.createOneShot(vibrationDuration.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                            } else {
                                                @Suppress("DEPRECATION")
                                                v?.vibrate(vibrationDuration.toLong())
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // ignore
                                    }
                                }
                            ) {
                                Text(
                                    text = "⚡ テスト振動",
                                    color = palette.primaryAccentSoft,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Slider(
                            value = vibrationDuration.toFloat(),
                            onValueChange = { value ->
                                vibrationDuration = value.toInt()
                                imePrefs.edit().putInt(ZeroConvertInputMethodService.KEY_VIBRATION_DURATION, vibrationDuration).apply()
                            },
                            valueRange = 5f..50f,
                            steps = 8,
                            colors = SliderDefaults.colors(
                                thumbColor = palette.primaryAccent,
                                activeTrackColor = palette.primaryAccent,
                                inactiveTrackColor = palette.keyModifier
                            )
                        )
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.borderSubtle))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("学習と履歴の使用", color = palette.textPrimary, fontSize = 13.sp)
                    Box {
                        TextButton(onClick = { learningMenuExpanded = true }) { Text(learningMode.label) }
                        DropdownMenu(expanded = learningMenuExpanded, onDismissRequest = { learningMenuExpanded = false }) {
                            LearningMode.entries.forEach { mode ->
                                DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                                    learningMode = mode
                                    learningMenuExpanded = false
                                    imePrefs.edit().putString(LearningPolicy.KEY, mode.storageValue).apply()
                                })
                            }
                        }
                    }
                }

                // 1. Streaming Auto-Commit Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "ストリーミング自動確定（長文入力時に固まった文節を自動確定）",
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "16文字以上の入力中、直近5回のAI推論で変化しなかった先頭の漢字・カタカナ文節を自動確定し、文脈を維持します。",
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = isAutoCommitEnabled,
                        onCheckedChange = { checked ->
                            isAutoCommitEnabled = checked
                            imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_AUTO_COMMIT_ENABLED, checked).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = palette.primaryAccent,
                            uncheckedThumbColor = palette.textSecondary,
                            uncheckedTrackColor = palette.keyModifier
                        )
                    )
                }

                // 2. Auto-Commit Backspace Rewind Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "自動確定直後のBackspaceで未確定に戻す（OFF時は通常通り1文字ずつ削除）",
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "自動確定された直後にBackspaceを押した際、確定済みテキストを未確定のひらがなに戻して再変換できるようにします。",
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = isAutoCommitRewind,
                        onCheckedChange = { checked ->
                            isAutoCommitRewind = checked
                            imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_AUTO_COMMIT_REWIND_ON_BACKSPACE, checked).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = palette.primaryAccent,
                            uncheckedThumbColor = palette.textSecondary,
                            uncheckedTrackColor = palette.keyModifier
                        )
                    )
                }

                // 3. Realtime Inline Conversion Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "入力中のリアルタイムAI変換反映",
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isRealtimeInline) {
                                "【ON】エディタ上の文字をZeroCon AI推論結果でリアルタイム置換し、上部に「原文: 〜」を表示します。"
                            } else {
                                "【OFF (推奨)】エディタ上はひらがなを表示し、候補バーからAI変換を選択・スペースで確定します。"
                            },
                            color = if (isRealtimeInline) palette.primaryAccentSoft else palette.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = isRealtimeInline,
                        onCheckedChange = { checked ->
                            isRealtimeInline = checked
                            imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_REALTIME_INLINE, checked).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = palette.primaryAccent,
                            uncheckedThumbColor = palette.textSecondary,
                            uncheckedTrackColor = palette.keyModifier
                        )
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.borderSubtle))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("ローマ字入力の辞書補正（実験的）", color = palette.textPrimary, fontSize = 13.sp)
                        Text("促音・撥音の重複や登録済みの誤入力を通常候補に加えます。フリック入力は対象外です。",
                            color = palette.textSecondary, fontSize = 11.sp)
                    }
                    Switch(checked = isDictionaryTypoCorrection, onCheckedChange = { checked ->
                        isDictionaryTypoCorrection = checked
                        imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_DICTIONARY_TYPO_CORRECTION, checked).apply()
                    })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("予測変換", color = palette.textPrimary, fontSize = 13.sp)
                        Text("入力途中の読みから単語・フレーズの候補を提示します（デフォルト: オン）。",
                            color = palette.textSecondary, fontSize = 11.sp)
                    }
                    Switch(checked = isPredictiveConversion, onCheckedChange = { checked ->
                        isPredictiveConversion = checked
                        imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_PREDICTIVE_CONVERSION, checked).apply()
                    })
                }

                // 3b. Experimental AI Assist (Typo Correction & Predictive Assist) Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "AIアシスト（誤入力補正💡・先読み🔮）",
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary,
                                fontSize = 13.sp
                            )
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = palette.primaryAccent.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "実験的",
                                    color = palette.primaryAccentSoft,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAiAssistEnabled) {
                                "【ON】誤入力の補正や文脈に応じた先読みを表示します。補正はタップで採用し、確定前ならBackspaceで元に戻せます。先読みはタップで確定します。"
                            } else {
                                "【OFF】補正・先読み候補を表示しません。通常のかな漢字変換は引き続き使用できます。"
                            },
                            color = if (isAiAssistEnabled) palette.primaryAccentSoft else palette.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = isAiAssistEnabled,
                        onCheckedChange = { checked ->
                            isAiAssistEnabled = checked
                            imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_EXPERIMENTAL_AI_ASSIST, checked).apply()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = palette.primaryAccent,
                            uncheckedThumbColor = palette.textSecondary,
                            uncheckedTrackColor = palette.keyModifier
                        )
                    )
                }

                if (isAiAssistEnabled) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("根拠のある誤入力を自動補正", color = palette.textPrimary, fontSize = 13.sp)
                            Text("採用・確定の実績や一意な打鍵修正を使用します。確定前なら元に戻せます。",
                                color = palette.textSecondary, fontSize = 11.sp)
                        }
                        Switch(checked = isAutoTypoCorrection, onCheckedChange = { enabled ->
                            isAutoTypoCorrection = enabled
                            imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_AUTO_TYPO_CORRECTION, enabled).apply()
                        })
                    }
                    Text(
                        text = "先読みは選択中のZeroCon変換モデルを使用します。誤入力補正は専用Correctorモデルを使用します。",
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.borderSubtle))

                // 4. ZeroCon AI Profile (\uEE03) Setting
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ZeroCon AI プロファイル (最大25文字)",
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textPrimary,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "${aiProfile.length} / 25",
                            color = palette.textMuted,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = "専門分野や書き手のペルソナ（例:「ソフトウェアエンジニア」「医療従事者」）を指定すると、ZeroCon AI が文脈に合わせて同音異義語を優先変換します。",
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                    OutlinedTextField(
                        value = aiProfile,
                        onValueChange = { v ->
                            val capped = v.take(25)
                            aiProfile = capped
                            imePrefs.edit().putString(ZeroConvertInputMethodService.KEY_AI_PROFILE, capped).apply()
                        },
                        placeholder = { Text("例: ソフトウェアエンジニア", color = palette.textMuted, fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            focusedBorderColor = palette.primaryAccent,
                            unfocusedBorderColor = palette.borderSubtle
                        )
                    )
                }

                // 5. Theme Mode Selector (Obsidian Dark / Platinum Light / System)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "カラーテーマ (Obsidian & Platinum Slate)",
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        fontSize = 13.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val modes = listOf(
                            "system" to "システム連動",
                            "dark" to "ダーク",
                            "light" to "ライト"
                        )
                        for ((modeKey, label) in modes) {
                            val selected = (themeMode == modeKey)
                            Button(
                                onClick = {
                                    themeMode = modeKey
                                    imePrefs.edit().putString(ZeroConvertInputMethodService.KEY_THEME_MODE, modeKey).apply()
                                },
                                modifier = Modifier.weight(1f).height(38.dp),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) palette.primaryAccent else palette.keyModifier
                                )
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    color = if (selected) Color.White else palette.textPrimary,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }
        }

        // --- Clipboard Settings Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.borderSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text = "クリップボード設定", fontWeight = FontWeight.Bold, color = palette.textPrimary, fontSize = 15.sp)
                ClipboardSuggestionDurationSetting(clipboardSuggestionMinutes, palette) { minutes ->
                    clipboardSuggestionMinutes = minutes
                    imePrefs.edit().putInt(ZeroConvertInputMethodService.KEY_CLIPBOARD_SUGGESTION_MINUTES, minutes).apply()
                }
                Text(text = "履歴の保持時間: ${clipboardRetention}時間", color = palette.textSecondary, fontSize = 12.sp)
                Slider(
                    value = clipboardRetention.toFloat(),
                    onValueChange = { v ->
                        clipboardRetention = v.toInt().coerceIn(1, 24)
                        imePrefs.edit().putInt(ZeroConvertInputMethodService.KEY_CLIPBOARD_RETENTION_HOURS, clipboardRetention).apply()
                    },
                    valueRange = 1f..24f,
                    steps = 22,
                    colors = SliderDefaults.colors(thumbColor = palette.primaryAccent, activeTrackColor = palette.primaryAccent, inactiveTrackColor = palette.keyModifier)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "スクリーンショットを自動保存", fontWeight = FontWeight.SemiBold, color = palette.textPrimary, fontSize = 13.sp)
                        Text(text = "新しいスクリーンショットをクリップボード履歴に自動追加します。", color = palette.textSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = screenshotCapture,
                        onCheckedChange = { checked ->
                            if (checked && context.checkSelfPermission(screenshotPermission) != PackageManager.PERMISSION_GRANTED) {
                                showScreenshotPermissionRationale = true
                            }
                            screenshotCapture = checked
                            imePrefs.edit().putBoolean(ZeroConvertInputMethodService.KEY_SCREENSHOT_CAPTURE, checked).apply()
                        },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = palette.primaryAccent, uncheckedThumbColor = palette.textSecondary, uncheckedTrackColor = palette.keyModifier)
                    )
                }
            }
        }

        // --- User Dictionary & Learning Memory Card ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.borderSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
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
                        text = "ユーザー辞書 & 変換学習メモリ",
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "登録数: ${userDictList.size}件",
                        color = palette.primaryAccentSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "よく使う固有名詞や専門用語を登録すると、候補バーの最上位に表示され、ZeroCon AI の推論ヒントとしても自動活用されます。",
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newDictReading,
                        onValueChange = { newDictReading = it },
                        placeholder = { Text("よみ (ひらがな)", color = palette.textMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            focusedBorderColor = palette.primaryAccent,
                            unfocusedBorderColor = palette.borderSubtle
                        )
                    )
                    OutlinedTextField(
                        value = newDictWord,
                        onValueChange = { newDictWord = it },
                        placeholder = { Text("単語 (表記)", color = palette.textMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            focusedBorderColor = palette.primaryAccent,
                            unfocusedBorderColor = palette.borderSubtle
                        )
                    )
                    Button(
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
                        },
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primaryAccent),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) {
                        Text("追加", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }

                if (userDictList.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (entry in userDictList) {
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
                                Text(
                                    text = "削除",
                                    color = palette.textSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable {
                                            coroutineScope.launch {
                                                userDictList = withContext(Dispatchers.IO) {
                                                    KanaKanjiConverter.removeUserDictEntry(context, entry)
                                                    KanaKanjiConverter.getUserDictionary(context)
                                                }
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.borderSubtle))

                // Learning Memory Status & Reset
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "変換学習メモリ (${learningCount}件 学習済み)",
                            color = palette.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "候補バーから選択した変換結果を記憶し、次回同じ読みを入力した際に第1候補へ優先表示します。",
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                learningCount = withContext(Dispatchers.IO) {
                                    KanaKanjiConverter.clearLearningMemory(context)
                                    KanaKanjiConverter.getLearningCount(context)
                                }
                            }
                        },
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.keyModifier),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                    ) {
                        Text("履歴リセット", fontSize = 12.sp, color = palette.textPrimary)
                    }
                }
            }
        }

        // --- Translation input (experimental) ---
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

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.borderSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "翻訳入力（試験）",
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary,
                    fontSize = 15.sp
                )
                Text(
                    text = "日本語入力の未確定文を翻訳し、Enterで訳文を確定します。選択文字列の翻訳・挿入にも対応します。翻訳は端末内で実行され、入力文は送信されません。",
                    color = palette.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "翻訳機能を有効にする",
                            color = palette.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (translationModelReady) {
                                "モデル: ダウンロード済み"
                            } else {
                                "モデル: 未ダウンロード"
                            },
                            color = if (translationModelReady) palette.readyEmerald else palette.textMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = translationEnabled,
                        onCheckedChange = { applyTranslationEnabled(it) }
                    )
                }

                if (translationEnabled && !translationModelReady) {
                    Text(
                        text = "翻訳モデルがありません。下のボタンからダウンロードしてください。",
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                }

                val downloading = translationProgress
                if (downloading != null) {
                    LinearProgressIndicator(
                        progress = downloading.fraction,
                        modifier = Modifier.fillMaxWidth()
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
                            fontSize = 11.sp
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
                translationDownloadError?.let { message ->
                    Text(text = message, color = palette.textSecondary, fontSize = 11.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (translationModelReady) {
                                startTranslationDownload()
                            } else {
                                showTranslationDownloadDialog = true
                            }
                        },
                        enabled = downloading == null,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primaryAccent)
                    ) {
                        Text(
                            text = if (translationModelReady) "再ダウンロード" else "翻訳モデルをダウンロード",
                            fontSize = 12.sp,
                            color = Color.White
                        )
                    }
                    if (translationModelReady) {
                        Button(
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
                            enabled = !translationEnabled && downloading == null,
                            modifier = Modifier.height(42.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.keyNormal)
                        ) {
                            Text("削除", fontSize = 12.sp, color = palette.textPrimary)
                        }
                    }
                }

                Text(
                    text = "翻訳方向",
                    color = palette.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        TranslationText.AUTO to "自動",
                        TranslationText.EN to "英語へ",
                        TranslationText.JA to "日本語へ"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = translationTarget == value,
                            onClick = {
                                translationTarget = value
                                imePrefs.edit()
                                    .putString(ZeroConvertInputMethodService.KEY_TRANSLATION_TARGET, value)
                                    .apply()
                            },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "翻訳モデルを常時読み込む",
                            color = palette.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "OFFでは翻訳モード終了時に約573MBのモデルを解放します。",
                            color = palette.textMuted,
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = translationKeepLoaded,
                        onCheckedChange = { enabled ->
                            translationKeepLoaded = enabled
                            imePrefs.edit()
                                .putBoolean(ZeroConvertInputMethodService.KEY_TRANSLATION_KEEP_LOADED, enabled)
                                .apply()
                        }
                    )
                }

                Text(
                    text = "翻訳テスト",
                    color = palette.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = translationTestInput,
                    onValueChange = { translationTestInput = it.take(512) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("原文", fontSize = 11.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                )
                Button(
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
                    },
                    enabled = translationModelReady && !translationTestRunning && downloading == null &&
                        translationTestInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.keyNormal)
                ) {
                    Text(
                        text = if (translationTestRunning) "翻訳中…" else "翻訳を実行",
                        fontSize = 12.sp,
                        color = palette.textPrimary
                    )
                }
                Text(
                    text = "結果: $translationTestResult",
                    color = palette.primaryAccentSoft,
                    fontSize = 12.sp
                )
            }
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

        // --- Model Management & Benchmark Section (ZeroCon Model & 評価ベンチマーク) ---
        var isDebugExpanded by remember { mutableStateOf(false) }

        var availableModels by remember {
            mutableStateOf<List<ModelInfo>>(ModelManager.getAvailableModels(context))
        }
        var isImporting by remember { mutableStateOf(false) }

        val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                coroutineScope.launch {
                    isImporting = true
                    val res = ModelManager.importModelFromUri(context, uri)
                    res.onSuccess { newModel: ModelInfo ->
                        availableModels = ModelManager.getAvailableModels(context)
                        ModelManager.selectModel(context, newModel)
                        availableModels = ModelManager.getAvailableModels(context)
                        onModelStatusChange("● ${ZenzNative.currentModelName} — 準備完了")
                    }
                    isImporting = false
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.borderSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isDebugExpanded = !isDebugExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "ZeroCon Model 管理 & 推論ベンチマーク",
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "量子化モデルの切替・外部GGUFの追加・230問評価ベンチマーク",
                            color = palette.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = if (isDebugExpanded) "▲ 閉じる" else "▼ 展開",
                        color = palette.primaryAccentSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isDebugExpanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(palette.borderSubtle)
                    )

                    // 1. Model Management & Switching Section
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = palette.surfaceElevated),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ZeroCon Model 切替",
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textPrimary,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "現在: ${ZenzNative.currentModelName}",
                                    color = palette.readyEmerald,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            for (model in availableModels) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (model.isSelected) palette.primaryAccent.copy(alpha = 0.14f) else palette.surface
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (model.isSelected) palette.primaryAccent.copy(alpha = 0.5f) else palette.borderSubtle
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.name,
                                                color = if (model.isSelected) palette.primaryAccentSoft else palette.textPrimary,
                                                fontSize = 13.sp,
                                                fontWeight = if (model.isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
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
                                            if (model.isSelected) {
                                                Text(
                                                    text = "✓ 使用中",
                                                    color = palette.readyEmerald,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp)
                                                )
                                            } else {
                                                Button(
                                                    onClick = {
                                                        coroutineScope.launch {
                                                            ModelManager.selectModel(context, model)
                                                            availableModels = ModelManager.getAvailableModels(context)
                                                            onModelStatusChange("● ${ZenzNative.currentModelName} — 準備完了")
                                                        }
                                                    },
                                                    modifier = Modifier.height(34.dp),
                                                    shape = RoundedCornerShape(6.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = palette.primaryAccent),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                                ) {
                                                    Text("切替", fontSize = 12.sp, color = Color.White)
                                                }
                                            }

                                            if (!model.isDefault) {
                                                IconButton(
                                                    onClick = {
                                                        ModelManager.deleteModel(context, model)
                                                        availableModels = ModelManager.getAvailableModels(context)
                                                        onModelStatusChange("● ${ZenzNative.currentModelName} — 準備完了")
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Text("✕", fontSize = 14.sp, color = palette.textSecondary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Button(
                                onClick = {
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                },
                                enabled = !isImporting,
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.keyNormal)
                            ) {
                                Text(
                                    text = if (isImporting) "モデルをコピー中…" else "＋ GGUFモデルを端末から追加",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary
                                )
                            }
                        }
                    }

                    // 2. Direct Inference Quick Test
                    var directTestResult by remember { mutableStateOf("未実行") }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = palette.surfaceElevated),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "ZeroCon AI 単体推論テスト",
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary,
                                fontSize = 14.sp
                            )
                            Button(
                                onClick = {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val t0 = System.currentTimeMillis()
                                        val res = ZenzNative.convert("", "ワタシ", aiProfile)
                                        val dt = System.currentTimeMillis() - t0
                                        directTestResult = "「ワタシ」→「$res」 (${dt}ms)"
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.keyNormal)
                            ) {
                                Text("単体推論を実行 (ワタシ → ?)", fontSize = 13.sp, color = palette.textPrimary)
                            }
                            Text(text = "結果: $directTestResult", color = palette.primaryAccentSoft, fontSize = 13.sp)
                        }
                    }

                    // 3. Benchmark Section
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = palette.surfaceElevated),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "230問 評価データセット・ベンチマーク",
                                fontWeight = FontWeight.Bold,
                                color = palette.textPrimary,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "同音異義語・文脈依存等 230問を端末上で一括推論し、正解率と平均レイテンシを測定します。",
                                color = palette.textSecondary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )

                            Button(
                                onClick = {
                                    coroutineScope.launch {
                                        BenchmarkRunner.runBenchmark(context) { p ->
                                            benchmarkProgress = p
                                        }
                                    }
                                },
                                enabled = benchmarkProgress?.isRunning != true,
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = palette.primaryAccent)
                            ) {
                                Text(
                                    text = if (benchmarkProgress?.isRunning == true) "ベンチマーク実行中…" else "全230問ベンチマークを実行",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            BenchmarkRunner.runTypoBenchmark(context) { p ->
                                                benchmarkProgress = p
                                            }
                                        }
                                    },
                                    enabled = benchmarkProgress?.isRunning != true,
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.keyNormal)
                                ) {
                                    Text("💡 誤入力補正テスト (35問)", fontSize = 11.sp, color = palette.textPrimary, fontWeight = FontWeight.SemiBold)
                                }

                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            BenchmarkRunner.runPredictiveBenchmark(context) { p ->
                                                benchmarkProgress = p
                                            }
                                        }
                                    },
                                    enabled = benchmarkProgress?.isRunning != true,
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.keyNormal)
                                ) {
                                    Text("🔮 先読みテスト (30問)", fontSize = 11.sp, color = palette.textPrimary, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            benchmarkProgress?.let { prog ->
                                val fraction = if (prog.total > 0) prog.current.toFloat() / prog.total else 0f
                                val acc = if (prog.current > 0) (prog.perfectCount.toFloat() / prog.current * 100) else 0f
                                val homophoneAcc = if (prog.homophoneTotal > 0) (prog.homophonePerfect.toFloat() / prog.homophoneTotal * 100) else 0f

                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth().height(8.dp),
                                    color = if (prog.isFinished) palette.readyEmerald else palette.primaryAccent,
                                    trackColor = palette.keyModifier
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "進捗: ${prog.current} / ${prog.total} 問 (${(fraction * 100).toInt()}%)",
                                        color = palette.textPrimary,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "平均: ${String.format("%.1f", prog.avgLatencyMs)} ms/問",
                                        color = palette.warningAmber,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "総合正解率: ${String.format("%.1f", acc)}% (${prog.perfectCount}/${prog.current})",
                                        color = palette.readyEmerald,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "同音異義: ${String.format("%.1f", homophoneAcc)}%",
                                        color = palette.primaryAccentSoft,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = palette.canvas),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = prog.lastItemDetail,
                                        color = palette.textSecondary,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- About & Third-Party Open Source Licenses Section ---
        var isLicenseExpanded by remember { mutableStateOf(false) }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = palette.surface),
            border = BorderStroke(1.dp, palette.borderSubtle),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isLicenseExpanded = !isLicenseExpanded },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ZeroCon について & オープンソースライセンス",
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (isLicenseExpanded) "▲ 閉じる" else "▼ 表示",
                        color = palette.primaryAccentSoft,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (isLicenseExpanded) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(palette.borderSubtle))
                    LicenseItem(
                        title = "azooKey / AzooKeyKanaKanjiConverter",
                        license = "MIT License — Copyright (c) 2023-2026 Miwa (ensan-hcl)",
                        palette = palette
                    )
                    LicenseItem(
                        title = "Zenz / zenz-v3.2 Neural Models",
                        license = "Apache License 2.0 — Copyright (c) Miwa Keita (ensan-hcl)",
                        palette = palette
                    )
                    LicenseItem(
                        title = "LOUDS System Dictionary & Emoji Dictionary",
                        license = "Apache License 2.0 — Miwa (ensan-hcl) / Mozc & Unicode",
                        palette = palette
                    )
                    LicenseItem(
                        title = "PLaMo 3 Translate (Translation Model)",
                        license = "PLaMo Community License — Copyright (c) Preferred Networks, Inc.",
                        palette = palette
                    )
                    LicenseItem(
                        title = "llama.cpp",
                        license = "MIT License — Copyright (c) 2023-2026 Georgi Gerganov and contributors",
                        palette = palette
                    )
                }
            }
        }

        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun LicenseItem(
    title: String,
    license: String,
    palette: ZeroConPalette
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surfaceElevated)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = license,
            color = palette.textSecondary,
            fontSize = 11.sp
        )
    }
}
