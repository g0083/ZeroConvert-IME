package com.zeroconvert.ime

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroconvert.ime.theme.BackgroundKind
import com.zeroconvert.ime.theme.CustomThemeRepository
import com.zeroconvert.ime.theme.KeyStylePreset
import com.zeroconvert.ime.theme.KeyboardThemeSpec
import com.zeroconvert.ime.theme.ThemeBackgroundProcessor
import com.zeroconvert.ime.theme.ThemeContrastChecker
import com.zeroconvert.ime.theme.toComposeColor
import com.zeroconvert.ime.theme.toZeroConPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class ThemeEditorTab(val label: String) {
    BACKGROUND("背景"),
    KEY_STYLE("キー造形"),
    COLORS("カラー"),
    TYPOGRAPHY("文字・管理")
}

@Composable
fun ThemeEditorScreen(
    initialSpec: KeyboardThemeSpec,
    isNewTheme: Boolean,
    appPalette: ZeroConPalette,
    initialLayoutName: String,
    keyboardKeyHeight: Int,
    isQwertyNumberRow: Boolean,
    isFlickOnly: Boolean,
    isKeyBordersEnabled: Boolean,
    onCancel: () -> Unit,
    onSave: (spec: KeyboardThemeSpec, sourceImageBytes: ByteArray?, renderedImageBytes: ByteArray?, clearImages: Boolean) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    BackHandler(onBack = onCancel)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(initialSpec.id, isNewTheme) { mutableStateOf(initialSpec) }
    var editorTab by remember { mutableStateOf(ThemeEditorTab.BACKGROUND) }
    var previewLayoutName by remember(initialLayoutName) { mutableStateOf(initialLayoutName) }
    var sourceBitmap by remember(initialSpec.id, isNewTheme) { mutableStateOf<Bitmap?>(null) }
    var previewImageBitmap by remember(initialSpec.id, isNewTheme) { mutableStateOf<ImageBitmap?>(null) }
    var sourceImageDirty by remember(initialSpec.id, isNewTheme) { mutableStateOf(false) }
    var sampledImageColor by remember(initialSpec.id, isNewTheme) { mutableStateOf<Long?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var photoStatusMessage by remember { mutableStateOf<String?>(null) }

    // Load existing background source bitmap when editing a saved photo theme.
    LaunchedEffect(initialSpec.id, isNewTheme) {
        if (initialSpec.background.kind == BackgroundKind.IMAGE && !initialSpec.isBuiltIn) {
            val loaded = withContext(Dispatchers.IO) {
                val repo = CustomThemeRepository.forContext(context)
                val srcFile = repo.sourceImageFile(initialSpec.id)
                val targetFile = if (srcFile.isFile) srcFile else {
                    repo.renderedImageFile(
                        initialSpec.id,
                        initialSpec.background.imageFileName ?: CustomThemeRepository.RENDERED_IMAGE_FILE
                    )
                }
                ThemeBackgroundProcessor.decodeFileToBitmap(targetFile)
            }
            if (loaded != null) {
                sourceBitmap = loaded
                sampledImageColor = withContext(Dispatchers.IO) {
                    ThemeBackgroundProcessor.suggestPaletteFromBitmap(loaded).normalKeyFill
                }
            }
        }
    }

    // Re-bake preview bitmap when crop or blur changes.
    LaunchedEffect(
        sourceBitmap,
        draft.background.kind,
        draft.background.cropCenterX,
        draft.background.cropCenterY,
        draft.background.cropScale,
        draft.background.blurRadiusDp
    ) {
        val src = sourceBitmap
        if (draft.background.kind == BackgroundKind.IMAGE && src != null) {
            delay(65L)
            val baked = withContext(Dispatchers.IO) {
                ThemeBackgroundProcessor.renderBakedBackground(
                    source = src,
                    cropCenterX = draft.background.cropCenterX,
                    cropCenterY = draft.background.cropCenterY,
                    cropScale = draft.background.cropScale,
                    blurRadiusDp = draft.background.blurRadiusDp,
                    targetWidthPx = 720
                )
            }
            previewImageBitmap = baked.asImageBitmap()
        } else if (draft.background.kind != BackgroundKind.IMAGE) {
            previewImageBitmap = null
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        photoStatusMessage = "画像を読み込み中…"
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                ThemeBackgroundProcessor.decodeAndNormalizeSource(context, uri)
            }
            if (decoded == null) {
                photoStatusMessage = "画像を読み込めませんでした"
                return@launch
            }
            val suggestion = withContext(Dispatchers.IO) {
                ThemeBackgroundProcessor.suggestPaletteFromBitmap(decoded)
            }
            sourceBitmap = decoded
            sourceImageDirty = true
            sampledImageColor = suggestion.normalKeyFill
            photoStatusMessage = null
            draft = draft.copy(
                isDark = suggestion.isDark,
                background = draft.background.copy(
                    kind = BackgroundKind.IMAGE,
                    imageFileName = CustomThemeRepository.RENDERED_IMAGE_FILE,
                    cropCenterX = 0.5f,
                    cropCenterY = 0.5f,
                    cropScale = 1.0f,
                    dimColor = suggestion.dimColor,
                    dimAlpha = suggestion.dimAlpha,
                    applyToBars = true
                ),
                keySurface = draft.keySurface.copy(
                    preset = if (draft.keySurface.preset == KeyStylePreset.BEVEL_3D) {
                        KeyStylePreset.OUTLINED_GLASS
                    } else {
                        draft.keySurface.preset
                    },
                    keyFillAlpha = minOf(draft.keySurface.keyFillAlpha, 0.52f),
                    modifierKeyAlpha = minOf(draft.keySurface.modifierKeyAlpha, 0.65f),
                    normalKeyFill = suggestion.normalKeyFill,
                    modifierKeyFill = suggestion.modifierKeyFill,
                    spaceKeyFill = suggestion.normalKeyFill,
                    accentKeyFill = suggestion.accentKeyFill,
                    borderColor = suggestion.borderColor,
                    borderAlpha = 0.30f,
                    borderWidthDp = if (draft.keySurface.borderWidthDp == 0f) 0.9f else draft.keySurface.borderWidthDp
                ),
                typography = draft.typography.copy(
                    primaryTextColor = suggestion.primaryTextColor,
                    secondaryTextColor = suggestion.secondaryTextColor,
                    accentTextColor = suggestion.accentTextColor,
                    hintTextColor = suggestion.secondaryTextColor,
                    textShadowEnabled = suggestion.isDark
                ),
                bars = draft.bars.copy(
                    backgroundColor = suggestion.barBgColor,
                    backgroundAlpha = 0.45f,
                    candidateTextColor = suggestion.primaryTextColor,
                    aiCandidateTextColor = suggestion.accentKeyFill,
                    mutedTextColor = suggestion.secondaryTextColor
                )
            )
        }
    }

    fun applySuggestedPaletteFromPhoto() {
        val src = sourceBitmap ?: return
        scope.launch {
            val s = withContext(Dispatchers.IO) { ThemeBackgroundProcessor.suggestPaletteFromBitmap(src) }
            sampledImageColor = s.normalKeyFill
            draft = draft.copy(
                isDark = s.isDark,
                background = draft.background.copy(dimColor = s.dimColor, dimAlpha = s.dimAlpha),
                keySurface = draft.keySurface.copy(
                    normalKeyFill = s.normalKeyFill,
                    modifierKeyFill = s.modifierKeyFill,
                    spaceKeyFill = s.normalKeyFill,
                    accentKeyFill = s.accentKeyFill,
                    borderColor = s.borderColor
                ),
                typography = draft.typography.copy(
                    primaryTextColor = s.primaryTextColor,
                    secondaryTextColor = s.secondaryTextColor,
                    accentTextColor = s.accentTextColor,
                    hintTextColor = s.secondaryTextColor,
                    textShadowEnabled = s.isDark
                ),
                bars = draft.bars.copy(
                    backgroundColor = s.barBgColor,
                    candidateTextColor = s.primaryTextColor,
                    aiCandidateTextColor = s.accentKeyFill,
                    mutedTextColor = s.secondaryTextColor
                )
            )
        }
    }

    fun handleSave() {
        if (isSaving) return
        isSaving = true
        scope.launch {
            val sanitized = draft.sanitized()
            val isImage = sanitized.background.kind == BackgroundKind.IMAGE
            val src = sourceBitmap
            var sourceBytes: ByteArray? = null
            var renderedBytes: ByteArray? = null
            var clearImages = false

            if (isImage && src != null) {
                withContext(Dispatchers.IO) {
                    if (sourceImageDirty || isNewTheme) {
                        sourceBytes = ThemeBackgroundProcessor.encodeBitmapToWebpBytes(src, quality = 90)
                    }
                    val fullBaked = ThemeBackgroundProcessor.renderBakedBackground(
                        source = src,
                        cropCenterX = sanitized.background.cropCenterX,
                        cropCenterY = sanitized.background.cropCenterY,
                        cropScale = sanitized.background.cropScale,
                        blurRadiusDp = sanitized.background.blurRadiusDp,
                        targetWidthPx = 1080
                    )
                    renderedBytes = ThemeBackgroundProcessor.encodeBitmapToWebpBytes(fullBaked, quality = 86)
                    if (fullBaked !== src) {
                        fullBaked.recycle()
                    }
                }
            } else if (!isImage) {
                clearImages = true
            }

            val finalSpec = if (isImage) {
                val resolvedImageFileName = when {
                    src != null -> CustomThemeRepository.RENDERED_IMAGE_FILE
                    !isNewTheme -> sanitized.background.imageFileName
                    else -> null
                }
                sanitized.copy(
                    background = sanitized.background.copy(
                        imageFileName = resolvedImageFileName
                    )
                )
            } else {
                sanitized.copy(background = sanitized.background.copy(imageFileName = null))
            }
            onSave(finalSpec, sourceBytes, renderedBytes, clearImages)
            isSaving = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(appPalette.canvas)
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(appPalette.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.clickable(onClick = onCancel),
                shape = RoundedCornerShape(8.dp),
                color = appPalette.surfaceElevated,
                border = BorderStroke(1.dp, appPalette.borderSubtle)
            ) {
                Box(
                    modifier = Modifier.padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "戻る",
                        tint = appPalette.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isNewTheme) "テーマを作成" else "テーマを編集",
                    color = appPalette.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = draft.name,
                    color = appPalette.textMuted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SettingsPrimaryButton(
                text = if (isSaving) "保存中…" else "保存して適用",
                palette = appPalette,
                enabled = !isSaving,
                onClick = ::handleSave
            )
        }
        SettingsDivider(appPalette)

        // Pinned Live Keyboard Preview
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(appPalette.surface)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ライブプレビュー",
                    color = appPalette.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EditorChip(
                        label = "12キー",
                        selected = previewLayoutName == JapaneseInputLayout.FLICK_12KEY.name,
                        palette = appPalette,
                        onClick = { previewLayoutName = JapaneseInputLayout.FLICK_12KEY.name }
                    )
                    EditorChip(
                        label = "QWERTY",
                        selected = previewLayoutName == JapaneseInputLayout.QWERTY.name,
                        palette = appPalette,
                        onClick = { previewLayoutName = JapaneseInputLayout.QWERTY.name }
                    )
                }
            }

            KeyboardThemeLivePreview(
                keyboardTheme = draft,
                kbPalette = draft.toZeroConPalette(),
                previewLayoutName = previewLayoutName,
                isFlickOnly = isFlickOnly,
                isQwertyNumberRow = isQwertyNumberRow,
                keyboardKeyHeight = keyboardKeyHeight,
                keyboardBottomPaddingDp = 0,
                oneHandedMode = OneHandedMode.OFF,
                oneHandedWidthPercent = OneHandedLayout.DEFAULT_WIDTH_PERCENT,
                isKeyPreviewEnabled = false,
                isKeyBordersEnabled = isKeyBordersEnabled,
                isDynamicTouchAssistEnabled = false,
                flickSensitivityDp = 18,
                onOneHandedModeChange = {},
                borderColor = appPalette.borderSubtle,
                backgroundImage = previewImageBitmap
            )

            // Contrast Guard Hint
            val contrast = remember(draft, sampledImageColor) {
                ThemeContrastChecker.effectiveKeyContrast(draft, sampledImageColor)
            }
            if (contrast < 4.0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(appPalette.warningAmber.copy(alpha = 0.14f))
                        .border(1.dp, appPalette.warningAmber.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = appPalette.warningAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "文字が見えにくい可能性があります",
                        color = appPalette.textPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (!draft.typography.textShadowEnabled) {
                        EditorChip(
                            label = "文字影ON",
                            selected = false,
                            palette = appPalette,
                            onClick = {
                                draft = draft.copy(
                                    typography = draft.typography.copy(textShadowEnabled = true)
                                )
                            }
                        )
                    }
                    if (draft.background.kind == BackgroundKind.IMAGE && draft.background.dimAlpha < 0.70f) {
                        EditorChip(
                            label = "暗幕+15%",
                            selected = false,
                            palette = appPalette,
                            onClick = {
                                draft = draft.copy(
                                    background = draft.background.copy(
                                        dimAlpha = (draft.background.dimAlpha + 0.15f).coerceAtMost(0.85f)
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }

        // Editor Sub-Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(appPalette.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ThemeEditorTab.entries.forEach { tab ->
                val selected = tab == editorTab
                val icon = when (tab) {
                    ThemeEditorTab.BACKGROUND -> Icons.Outlined.Image
                    ThemeEditorTab.KEY_STYLE -> Icons.Outlined.Keyboard
                    ThemeEditorTab.COLORS -> Icons.Outlined.ColorLens
                    ThemeEditorTab.TYPOGRAPHY -> Icons.Outlined.TextFields
                }
                Surface(
                    modifier = Modifier.clickable { editorTab = tab },
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) appPalette.primaryAccent else appPalette.surfaceElevated,
                    border = BorderStroke(1.dp, if (selected) appPalette.primaryAccent else appPalette.borderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (selected) appPalette.onAccent else appPalette.textSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = tab.label,
                            color = if (selected) appPalette.onAccent else appPalette.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    }
                }
            }
        }
        SettingsDivider(appPalette)

        // Scrollable Tab Body
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (editorTab) {
                ThemeEditorTab.BACKGROUND -> {
                    SettingsSection("背景タイプ", appPalette) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SettingsSegmented(
                                options = listOf(
                                    BackgroundKind.SOLID to "単色",
                                    BackgroundKind.LINEAR_GRADIENT to "グラデ",
                                    BackgroundKind.IMAGE to "写真"
                                ),
                                selected = draft.background.kind,
                                palette = appPalette,
                                onSelected = { kind ->
                                    draft = draft.copy(
                                        background = draft.background.copy(
                                            kind = kind,
                                            applyToBars = if (kind != BackgroundKind.SOLID) true else draft.background.applyToBars
                                        )
                                    )
                                }
                            )
                        }
                        SettingsDivider(appPalette)
                        when (draft.background.kind) {
                            BackgroundKind.SOLID -> {
                                ColorSettingRow(
                                    label = "背景色",
                                    colorLong = draft.background.solidColor,
                                    palette = appPalette,
                                    onColorSelected = { c ->
                                        draft = draft.copy(
                                            background = draft.background.copy(solidColor = c)
                                        )
                                    }
                                )
                            }
                            BackgroundKind.LINEAR_GRADIENT -> {
                                ColorSettingRow(
                                    label = "開始色",
                                    colorLong = draft.background.gradientStartColor,
                                    palette = appPalette,
                                    onColorSelected = { c ->
                                        draft = draft.copy(
                                            background = draft.background.copy(gradientStartColor = c)
                                        )
                                    }
                                )
                                SettingsDivider(appPalette)
                                ColorSettingRow(
                                    label = "終了色",
                                    colorLong = draft.background.gradientEndColor,
                                    palette = appPalette,
                                    onColorSelected = { c ->
                                        draft = draft.copy(
                                            background = draft.background.copy(gradientEndColor = c)
                                        )
                                    }
                                )
                                SettingsDivider(appPalette)
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("グラデーション角度", color = appPalette.textPrimary, fontSize = 13.5.sp)
                                        Text("${draft.background.gradientAngleDeg.roundToInt()}°", color = appPalette.primaryAccentSoft, fontSize = 13.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(0f, 45f, 90f, 135f, 180f).forEach { angle ->
                                            EditorChip(
                                                label = "${angle.toInt()}°",
                                                selected = kotlin.math.abs(draft.background.gradientAngleDeg - angle) < 2f,
                                                palette = appPalette,
                                                onClick = {
                                                    draft = draft.copy(
                                                        background = draft.background.copy(gradientAngleDeg = angle)
                                                    )
                                                }
                                            )
                                        }
                                    }
                                    Slider(
                                        value = draft.background.gradientAngleDeg,
                                        onValueChange = { deg ->
                                            draft = draft.copy(
                                                background = draft.background.copy(gradientAngleDeg = deg)
                                            )
                                        },
                                        valueRange = 0f..359f,
                                        colors = editorSliderColors(appPalette)
                                    )
                                }
                            }
                            BackgroundKind.IMAGE -> {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        SettingsPrimaryButton(
                                            text = if (sourceBitmap == null) "写真を選ぶ" else "写真を変更",
                                            palette = appPalette,
                                            onClick = {
                                                photoPickerLauncher.launch(
                                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                                )
                                            },
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (sourceBitmap != null) {
                                            SettingsSecondaryButton(
                                                text = "自動配色",
                                                palette = appPalette,
                                                onClick = ::applySuggestedPaletteFromPhoto
                                            )
                                        }
                                    }
                                    if (photoStatusMessage != null) {
                                        Text(
                                            text = photoStatusMessage!!,
                                            color = appPalette.primaryAccentSoft,
                                            fontSize = 12.sp
                                        )
                                    }

                                    val currentPreview = previewImageBitmap
                                    if (currentPreview != null) {
                                        Text(
                                            text = "ドラッグで位置調整・ピンチで拡大縮小できます",
                                            color = appPalette.textMuted,
                                            fontSize = 11.5.sp
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1.65f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(1.dp, appPalette.borderSubtle, RoundedCornerShape(10.dp))
                                                .pointerInput(Unit) {
                                                    detectTransformGestures { _, pan, zoom, _ ->
                                                        val bg = draft.background
                                                        val newScale = (bg.cropScale * zoom).coerceIn(1f, 3f)
                                                        val sens = 1f / (size.width.toFloat().coerceAtLeast(1f) * newScale)
                                                        val newX = (bg.cropCenterX - pan.x * sens).coerceIn(0f, 1f)
                                                        val newY = (bg.cropCenterY - pan.y * sens).coerceIn(0f, 1f)
                                                        draft = draft.copy(
                                                            background = bg.copy(
                                                                cropScale = newScale,
                                                                cropCenterX = newX,
                                                                cropCenterY = newY
                                                            )
                                                        )
                                                    }
                                                }
                                        ) {
                                            Image(
                                                bitmap = currentPreview,
                                                contentDescription = "背景画像トリミング",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            if (draft.background.dimAlpha > 0f) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(
                                                            draft.background.dimColor.toComposeColor()
                                                                .copy(alpha = draft.background.dimAlpha)
                                                        )
                                                )
                                            }
                                        }

                                        EditorSliderRow(
                                            title = "拡大率",
                                            valueText = String.format("%.1fx", draft.background.cropScale),
                                            value = draft.background.cropScale,
                                            valueRange = 1.0f..3.0f,
                                            palette = appPalette,
                                            onValueChange = { scale ->
                                                draft = draft.copy(
                                                    background = draft.background.copy(cropScale = scale)
                                                )
                                            }
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            EditorChip(
                                                label = "位置・倍率をリセット",
                                                selected = false,
                                                palette = appPalette,
                                                onClick = {
                                                    draft = draft.copy(
                                                        background = draft.background.copy(
                                                            cropCenterX = 0.5f,
                                                            cropCenterY = 0.5f,
                                                            cropScale = 1.0f
                                                        )
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    EditorSliderRow(
                                        title = "ぼかし",
                                        valueText = "${draft.background.blurRadiusDp.roundToInt()} dp",
                                        value = draft.background.blurRadiusDp,
                                        valueRange = 0f..24f,
                                        palette = appPalette,
                                        onValueChange = { blur ->
                                            draft = draft.copy(
                                                background = draft.background.copy(blurRadiusDp = blur)
                                            )
                                        }
                                    )
                                    EditorSliderRow(
                                        title = "暗幕 / 明幕の濃さ",
                                        valueText = "${(draft.background.dimAlpha * 100f).roundToInt()}%",
                                        value = draft.background.dimAlpha,
                                        valueRange = 0f..0.85f,
                                        palette = appPalette,
                                        onValueChange = { alpha ->
                                            draft = draft.copy(
                                                background = draft.background.copy(dimAlpha = alpha)
                                            )
                                        }
                                    )
                                    SettingsSegmented(
                                        options = listOf(
                                            0xFF000000L to "黒オーバーレイ",
                                            0xFFFFFFFFL to "白オーバーレイ"
                                        ),
                                        selected = if ((draft.background.dimColor and 0xFFFFFFL) == 0xFFFFFFL) {
                                            0xFFFFFFFFL
                                        } else {
                                            0xFF000000L
                                        },
                                        palette = appPalette,
                                        onSelected = { color ->
                                            draft = draft.copy(
                                                background = draft.background.copy(dimColor = color)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        if (draft.background.kind != BackgroundKind.SOLID) {
                            SettingsDivider(appPalette)
                            SettingsSwitchRow(
                                title = "上部バーまで背景を伸ばす",
                                description = "オフにすると候補・ツールバー背景を不透明で塗り分けます。",
                                checked = draft.background.applyToBars,
                                palette = appPalette,
                                onCheckedChange = { checked ->
                                    draft = draft.copy(
                                        background = draft.background.copy(applyToBars = checked)
                                    )
                                }
                            )
                        }
                    }
                }

                ThemeEditorTab.KEY_STYLE -> {
                    SettingsSection("キーの質感・スタイル", appPalette) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                KeyStylePreset.entries.forEach { preset ->
                                    EditorChip(
                                        label = preset.label,
                                        selected = draft.keySurface.preset == preset,
                                        palette = appPalette,
                                        onClick = {
                                            val ks = draft.keySurface
                                            draft = draft.copy(
                                                keySurface = when (preset) {
                                                    KeyStylePreset.BEVEL_3D -> ks.copy(
                                                        preset = preset,
                                                        borderWidthDp = 0.65f,
                                                        borderAlpha = 0.65f,
                                                        shadowElevationDp = 0f,
                                                        bevelIntensity = 1.0f
                                                    )
                                                    KeyStylePreset.IOS_SHADOW -> ks.copy(
                                                        preset = preset,
                                                        borderWidthDp = 0f,
                                                        borderAlpha = 0f,
                                                        shadowElevationDp = maxOf(ks.shadowElevationDp, 1.5f),
                                                        bevelIntensity = 0f
                                                    )
                                                    KeyStylePreset.MATERIAL_FLAT -> ks.copy(
                                                        preset = preset,
                                                        borderWidthDp = 0f,
                                                        borderAlpha = 0f,
                                                        shadowElevationDp = 0f,
                                                        bevelIntensity = 0f
                                                    )
                                                    KeyStylePreset.OUTLINED_GLASS -> ks.copy(
                                                        preset = preset,
                                                        borderWidthDp = maxOf(ks.borderWidthDp, 1.0f),
                                                        borderAlpha = maxOf(ks.borderAlpha, 0.30f),
                                                        shadowElevationDp = 0f,
                                                        bevelIntensity = 0f
                                                    )
                                                    KeyStylePreset.BORDERLESS -> ks.copy(
                                                        preset = preset,
                                                        borderWidthDp = 0f,
                                                        borderAlpha = 0f,
                                                        shadowElevationDp = 0f,
                                                        bevelIntensity = 0f
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                            EditorSliderRow(
                                title = "キー角丸",
                                valueText = "${draft.keySurface.cornerRadiusDp.roundToInt()} dp",
                                value = draft.keySurface.cornerRadiusDp,
                                valueRange = 0f..22f,
                                palette = appPalette,
                                onValueChange = { r ->
                                    draft = draft.copy(keySurface = draft.keySurface.copy(cornerRadiusDp = r))
                                }
                            )
                            EditorSliderRow(
                                title = "Enterキー角丸",
                                valueText = "${draft.keySurface.enterCornerRadiusDp.roundToInt()} dp",
                                value = draft.keySurface.enterCornerRadiusDp,
                                valueRange = 0f..24f,
                                palette = appPalette,
                                onValueChange = { r ->
                                    draft = draft.copy(keySurface = draft.keySurface.copy(enterCornerRadiusDp = r))
                                }
                            )
                            EditorSliderRow(
                                title = "通常キー不透明度",
                                valueText = "${(draft.keySurface.keyFillAlpha * 100f).roundToInt()}%",
                                value = draft.keySurface.keyFillAlpha,
                                valueRange = 0f..1f,
                                palette = appPalette,
                                onValueChange = { a ->
                                    draft = draft.copy(keySurface = draft.keySurface.copy(keyFillAlpha = a))
                                }
                            )
                            EditorSliderRow(
                                title = "機能キー不透明度",
                                valueText = "${(draft.keySurface.modifierKeyAlpha * 100f).roundToInt()}%",
                                value = draft.keySurface.modifierKeyAlpha,
                                valueRange = 0f..1f,
                                palette = appPalette,
                                onValueChange = { a ->
                                    draft = draft.copy(keySurface = draft.keySurface.copy(modifierKeyAlpha = a))
                                }
                            )
                            EditorSliderRow(
                                title = "キー横間隔",
                                valueText = String.format("%.1f dp", draft.keySurface.horizontalGapDp),
                                value = draft.keySurface.horizontalGapDp,
                                valueRange = 0f..6f,
                                palette = appPalette,
                                onValueChange = { g ->
                                    draft = draft.copy(keySurface = draft.keySurface.copy(horizontalGapDp = g))
                                }
                            )
                            EditorSliderRow(
                                title = "キー縦間隔",
                                valueText = String.format("%.1f dp", draft.keySurface.verticalGapDp),
                                value = draft.keySurface.verticalGapDp,
                                valueRange = 0f..6f,
                                palette = appPalette,
                                onValueChange = { g ->
                                    draft = draft.copy(keySurface = draft.keySurface.copy(verticalGapDp = g))
                                }
                            )
                            if (draft.keySurface.preset != KeyStylePreset.BORDERLESS) {
                                EditorSliderRow(
                                    title = "枠線の太さ",
                                    valueText = String.format("%.1f dp", draft.keySurface.borderWidthDp),
                                    value = draft.keySurface.borderWidthDp,
                                    valueRange = 0f..2.5f,
                                    palette = appPalette,
                                    onValueChange = { w ->
                                        draft = draft.copy(keySurface = draft.keySurface.copy(borderWidthDp = w))
                                    }
                                )
                                EditorSliderRow(
                                    title = "枠線の濃さ",
                                    valueText = "${(draft.keySurface.borderAlpha * 100f).roundToInt()}%",
                                    value = draft.keySurface.borderAlpha,
                                    valueRange = 0f..1f,
                                    palette = appPalette,
                                    onValueChange = { a ->
                                        draft = draft.copy(keySurface = draft.keySurface.copy(borderAlpha = a))
                                    }
                                )
                            }
                            if (draft.keySurface.preset == KeyStylePreset.IOS_SHADOW) {
                                EditorSliderRow(
                                    title = "影の強さ",
                                    valueText = String.format("%.1f dp", draft.keySurface.shadowElevationDp),
                                    value = draft.keySurface.shadowElevationDp,
                                    valueRange = 0f..6f,
                                    palette = appPalette,
                                    onValueChange = { s ->
                                        draft = draft.copy(keySurface = draft.keySurface.copy(shadowElevationDp = s))
                                    }
                                )
                            }
                            if (draft.keySurface.preset == KeyStylePreset.BEVEL_3D) {
                                EditorSliderRow(
                                    title = "立体ベベル強度",
                                    valueText = String.format("%.1fx", draft.keySurface.bevelIntensity),
                                    value = draft.keySurface.bevelIntensity,
                                    valueRange = 0f..1.5f,
                                    palette = appPalette,
                                    onValueChange = { b ->
                                        draft = draft.copy(keySurface = draft.keySurface.copy(bevelIntensity = b))
                                    }
                                )
                            }
                        }
                    }
                }

                ThemeEditorTab.COLORS -> {
                    SettingsSection("配色カスタマイズ", appPalette) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("全体の明暗ベース", color = appPalette.textSecondary, fontSize = 12.sp)
                            SettingsSegmented(
                                options = listOf(true to "暗色ベース", false to "明色ベース"),
                                selected = draft.isDark,
                                palette = appPalette,
                                onSelected = { dark -> draft = draft.copy(isDark = dark) }
                            )
                        }
                        SettingsDivider(appPalette)
                        ColorSettingRow(
                            label = "通常キー色",
                            colorLong = draft.keySurface.normalKeyFill,
                            palette = appPalette,
                            onColorSelected = { c ->
                                draft = draft.copy(keySurface = draft.keySurface.copy(normalKeyFill = c))
                            }
                        )
                        SettingsDivider(appPalette)
                        ColorSettingRow(
                            label = "機能キー色",
                            colorLong = draft.keySurface.modifierKeyFill,
                            palette = appPalette,
                            onColorSelected = { c ->
                                draft = draft.copy(keySurface = draft.keySurface.copy(modifierKeyFill = c))
                            }
                        )
                        SettingsDivider(appPalette)
                        ColorSettingRow(
                            label = "スペースキー色",
                            colorLong = draft.keySurface.spaceKeyFill,
                            palette = appPalette,
                            onColorSelected = { c ->
                                draft = draft.copy(keySurface = draft.keySurface.copy(spaceKeyFill = c))
                            }
                        )
                        SettingsDivider(appPalette)
                        ColorSettingRow(
                            label = "確定 / アクセント色",
                            colorLong = draft.keySurface.accentKeyFill,
                            palette = appPalette,
                            onColorSelected = { c ->
                                draft = draft.copy(
                                    keySurface = draft.keySurface.copy(accentKeyFill = c),
                                    popup = draft.popup.copy(accentColor = c),
                                    bars = draft.bars.copy(aiCandidateTextColor = c)
                                )
                            }
                        )
                        SettingsDivider(appPalette)
                        ColorSettingRow(
                            label = "文字色",
                            colorLong = draft.typography.primaryTextColor,
                            palette = appPalette,
                            onColorSelected = { c ->
                                draft = draft.copy(
                                    typography = draft.typography.copy(primaryTextColor = c),
                                    bars = draft.bars.copy(candidateTextColor = c)
                                )
                            }
                        )
                        SettingsDivider(appPalette)
                        ColorSettingRow(
                            label = "サブ文字・ヒント色",
                            colorLong = draft.typography.secondaryTextColor,
                            palette = appPalette,
                            onColorSelected = { c ->
                                draft = draft.copy(
                                    typography = draft.typography.copy(secondaryTextColor = c, hintTextColor = c),
                                    bars = draft.bars.copy(mutedTextColor = c)
                                )
                            }
                        )
                        SettingsDivider(appPalette)
                        ColorSettingRow(
                            label = "確定キー上の文字色",
                            colorLong = draft.typography.accentTextColor,
                            palette = appPalette,
                            onColorSelected = { c ->
                                draft = draft.copy(
                                    typography = draft.typography.copy(accentTextColor = c),
                                    popup = draft.popup.copy(onAccentColor = c)
                                )
                            }
                        )
                        SettingsDivider(appPalette)
                        ColorSettingRow(
                            label = "枠線色",
                            colorLong = draft.keySurface.borderColor,
                            palette = appPalette,
                            onColorSelected = { c ->
                                draft = draft.copy(keySurface = draft.keySurface.copy(borderColor = c))
                            }
                        )
                        SettingsDivider(appPalette)
                        ColorSettingRow(
                            label = "上部バー背景色",
                            colorLong = draft.bars.backgroundColor,
                            palette = appPalette,
                            onColorSelected = { c ->
                                draft = draft.copy(bars = draft.bars.copy(backgroundColor = c))
                            }
                        )
                        SettingsDivider(appPalette)
                        Column(modifier = Modifier.padding(16.dp)) {
                            EditorSliderRow(
                                title = "上部バー不透明度",
                                valueText = "${(draft.bars.backgroundAlpha * 100f).roundToInt()}%",
                                value = draft.bars.backgroundAlpha,
                                valueRange = 0f..1f,
                                palette = appPalette,
                                onValueChange = { a ->
                                    draft = draft.copy(bars = draft.bars.copy(backgroundAlpha = a))
                                }
                            )
                        }
                    }
                }

                ThemeEditorTab.TYPOGRAPHY -> {
                    SettingsSection("文字表示・テーマ名", appPalette) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = draft.name,
                                onValueChange = { name -> draft = draft.copy(name = name) },
                                label = { Text("テーマ名", fontSize = 12.sp) },
                                singleLine = true,
                                colors = settingsTextFieldColors(appPalette),
                                modifier = Modifier.fillMaxWidth()
                            )
                            EditorSliderRow(
                                title = "文字サイズ倍率",
                                valueText = "${(draft.typography.labelScale * 100f).roundToInt()}%",
                                value = draft.typography.labelScale,
                                valueRange = 0.85f..1.20f,
                                palette = appPalette,
                                onValueChange = { scale ->
                                    draft = draft.copy(typography = draft.typography.copy(labelScale = scale))
                                }
                            )
                        }
                        SettingsDivider(appPalette)
                        SettingsSwitchRow(
                            title = "文字に影をつける",
                            description = "写真背景や半透明キーの上で文字の輪郭を読みやすくします。",
                            checked = draft.typography.textShadowEnabled,
                            palette = appPalette,
                            onCheckedChange = { enabled ->
                                draft = draft.copy(
                                    typography = draft.typography.copy(textShadowEnabled = enabled)
                                )
                            }
                        )
                        SettingsDivider(appPalette)
                        SettingsSwitchRow(
                            title = "12キーの方向ヒントを表示",
                            description = "「あ」キー周囲の「い・う・え・お」ヒント表示を切り替えます。",
                            checked = draft.typography.showFlickDirectionHints,
                            palette = appPalette,
                            onCheckedChange = { enabled ->
                                draft = draft.copy(
                                    typography = draft.typography.copy(showFlickDirectionHints = enabled)
                                )
                            }
                        )
                        SettingsDivider(appPalette)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SettingsSecondaryButton(
                                text = "編集をリセット",
                                palette = appPalette,
                                onClick = {
                                    draft = initialSpec
                                    sourceImageDirty = false
                                },
                                modifier = Modifier.weight(1f)
                            )
                            if (onDelete != null) {
                                SettingsSecondaryButton(
                                    text = if (confirmDelete) "本当に削除" else "テーマを削除",
                                    palette = appPalette,
                                    onClick = {
                                        if (!confirmDelete) {
                                            confirmDelete = true
                                        } else {
                                            onDelete(initialSpec.id)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EditorChip(
    label: String,
    selected: Boolean,
    palette: ZeroConPalette,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) palette.primaryAccent else palette.surfaceElevated,
        border = BorderStroke(1.dp, if (selected) palette.primaryAccent else palette.borderSubtle)
    ) {
        Text(
            text = label,
            color = if (selected) palette.onAccent else palette.textSecondary,
            fontSize = 11.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun EditorSliderRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    palette: ZeroConPalette,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = valueText,
                color = palette.primaryAccentSoft,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = editorSliderColors(palette)
        )
    }
}

@Composable
private fun editorSliderColors(palette: ZeroConPalette) = SliderDefaults.colors(
    thumbColor = palette.primaryAccent,
    activeTrackColor = palette.primaryAccent,
    inactiveTrackColor = palette.surfaceElevated
)
