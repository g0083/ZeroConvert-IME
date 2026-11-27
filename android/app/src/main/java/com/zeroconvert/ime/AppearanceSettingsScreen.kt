package com.zeroconvert.ime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.zeroconvert.ime.theme.BackgroundKind
import com.zeroconvert.ime.theme.KeyboardThemeCatalog
import com.zeroconvert.ime.theme.KeyboardThemeSpec
import com.zeroconvert.ime.theme.ThemeBackgroundCache
import com.zeroconvert.ime.theme.ThemeCategory
import com.zeroconvert.ime.theme.keyboardThemeBackground
import com.zeroconvert.ime.theme.rememberResolvedKeyboardSkin
import com.zeroconvert.ime.theme.toComposeColor
import com.zeroconvert.ime.theme.toZeroConPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun AppearanceSettingsTab(
    appThemeMode: String,
    onAppThemeModeChange: (String) -> Unit,
    keyboardThemeId: String,
    onKeyboardThemeIdChange: (String) -> Unit,
    appPalette: ZeroConPalette,
    customThemes: List<KeyboardThemeSpec> = emptyList(),
    customThemeRevision: Long = 0L,
    onCreateNewTheme: (KeyboardThemeSpec) -> Unit = {},
    onEditCustomTheme: (KeyboardThemeSpec) -> Unit = {},
    onDuplicateTheme: (KeyboardThemeSpec) -> Unit = {},
    onDeleteCustomTheme: (String) -> Unit = {},
    japaneseLayout: String,
    onJapaneseLayoutChange: (String) -> Unit,
    isFlickOnly: Boolean,
    isQwertyNumberRow: Boolean,
    onQwertyNumberRowChange: (Boolean) -> Unit,
    keyboardKeyHeight: Int,
    onKeyboardKeyHeightChange: (Int) -> Unit,
    keyboardBottomPaddingDp: Int,
    onKeyboardBottomPaddingChange: (Int) -> Unit,
    oneHandedMode: OneHandedMode,
    onOneHandedModeChange: (OneHandedMode) -> Unit,
    oneHandedWidthPercent: Int,
    onOneHandedWidthPercentChange: (Int) -> Unit,
    splitDuplicateKeys: Boolean,
    onSplitDuplicateKeysChange: (Boolean) -> Unit,
    splitGapPercent: Int,
    onSplitGapPercentChange: (Int) -> Unit,
    onSplitGapPercentCommit: () -> Unit,
    isKeyPreviewEnabled: Boolean,
    onKeyPreviewEnabledChange: (Boolean) -> Unit,
    isKeyBordersEnabled: Boolean,
    onKeyBordersEnabledChange: (Boolean) -> Unit,
    isDynamicTouchAssistEnabled: Boolean,
    flickSensitivityDp: Int
) {
    val systemDark = isSystemInDarkTheme()
    val resolvedSkin = rememberResolvedKeyboardSkin(keyboardThemeId, customThemeRevision)
    val keyboardTheme = resolvedSkin.spec
    val backgroundImage = resolvedSkin.backgroundImage
    val kbPalette = remember(keyboardTheme) { keyboardTheme.toZeroConPalette() }
    var pendingDeleteTheme by remember { mutableStateOf<KeyboardThemeSpec?>(null) }
    var previewLayoutName by remember(japaneseLayout) { mutableStateOf(japaneseLayout) }
    LaunchedEffect(japaneseLayout) {
        previewLayoutName = japaneseLayout
    }
    var selectedCategory by remember { mutableStateOf<ThemeCategory?>(null) }

    SettingsSection("キーボードテーマ", appPalette) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "キーボード専用の見た目を設定画面と独立して選べます。立体・iOS風・フラット・グラデーションなど16種類のプリセットに対応しています。",
                color = appPalette.textMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            // Live Keyboard Preview Header + Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = keyboardTheme.name,
                        color = appPalette.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    SettingsBadge(keyboardTheme.keySurface.preset.label, appPalette)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PreviewLayoutChip(
                        label = "12キー",
                        selected = previewLayoutName == JapaneseInputLayout.FLICK_12KEY.name,
                        palette = appPalette,
                        onClick = { previewLayoutName = JapaneseInputLayout.FLICK_12KEY.name }
                    )
                    PreviewLayoutChip(
                        label = "QWERTY",
                        selected = previewLayoutName == JapaneseInputLayout.QWERTY.name,
                        palette = appPalette,
                        onClick = { previewLayoutName = JapaneseInputLayout.QWERTY.name }
                    )
                }
            }

            // Scaled real IME keyboard preview
            KeyboardThemeLivePreview(
                keyboardTheme = keyboardTheme,
                kbPalette = kbPalette,
                backgroundImage = backgroundImage,
                previewLayoutName = previewLayoutName,
                isFlickOnly = isFlickOnly,
                isQwertyNumberRow = isQwertyNumberRow,
                keyboardKeyHeight = keyboardKeyHeight,
                keyboardBottomPaddingDp = keyboardBottomPaddingDp,
                oneHandedMode = oneHandedMode,
                oneHandedWidthPercent = oneHandedWidthPercent,
                isKeyPreviewEnabled = isKeyPreviewEnabled,
                isKeyBordersEnabled = isKeyBordersEnabled,
                isDynamicTouchAssistEnabled = isDynamicTouchAssistEnabled,
                flickSensitivityDp = flickSensitivityDp,
                onOneHandedModeChange = onOneHandedModeChange,
                borderColor = appPalette.borderSubtle
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsPrimaryButton(
                    text = "＋ 新規作成",
                    palette = appPalette,
                    onClick = { onCreateNewTheme(keyboardTheme) },
                    modifier = Modifier.weight(1f)
                )
                SettingsSecondaryButton(
                    text = if (!keyboardTheme.isBuiltIn) "選択中を編集" else "複製してカスタム",
                    palette = appPalette,
                    onClick = {
                        if (!keyboardTheme.isBuiltIn) {
                            onEditCustomTheme(keyboardTheme)
                        } else {
                            onCreateNewTheme(keyboardTheme)
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            val followsSystem = keyboardThemeId == ZERO_CON_THEME_SYSTEM
            SettingsSwitchRow(
                title = "システム連動",
                description = if (followsSystem) {
                    "端末のダークモードに合わせて、グラファイトまたはペーパーを使います。"
                } else {
                    "オフのときは下で選んだテーマを、端末設定に関係なくキーボードへ適用します。"
                },
                checked = followsSystem,
                palette = appPalette,
                onCheckedChange = { enabled ->
                    onKeyboardThemeIdChange(
                        if (enabled) ZERO_CON_THEME_SYSTEM
                        else KeyboardThemeCatalog.resolveId(ZERO_CON_THEME_SYSTEM, systemDark, customThemes)
                    )
                }
            )

            val allThemes = remember(customThemes) {
                customThemes + KeyboardThemeCatalog.presets
            }
            LaunchedEffect(customThemes.isEmpty(), selectedCategory) {
                if (customThemes.isEmpty() && selectedCategory == ThemeCategory.CUSTOM) {
                    selectedCategory = null
                }
            }

            // Category Filter Chips
            val categories = remember(customThemes.isNotEmpty()) {
                buildList<ThemeCategory?> {
                    add(null)
                    if (customThemes.isNotEmpty()) {
                        add(ThemeCategory.CUSTOM)
                    }
                    add(ThemeCategory.STANDARD)
                    add(ThemeCategory.OS_STYLE)
                    add(ThemeCategory.GRADIENT_GLASS)
                    add(ThemeCategory.POP_NEON)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                categories.forEach { category ->
                    val isSelected = selectedCategory == category
                    val chipText = when (category) {
                        null -> "すべて (${allThemes.size})"
                        ThemeCategory.CUSTOM -> "${category.label} (${customThemes.size})"
                        else -> category.label
                    }
                    Surface(
                        modifier = Modifier.clickable { selectedCategory = category },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) appPalette.primaryAccent else appPalette.surfaceElevated,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) appPalette.primaryAccent else appPalette.borderSubtle
                        )
                    ) {
                        Text(
                            text = chipText,
                            color = if (isSelected) appPalette.onAccent else appPalette.textSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            maxLines = 1
                        )
                    }
                }
            }

            // Theme Gallery (2-column visual mini-keyboard cards)
            val filteredThemes = remember(selectedCategory, allThemes) {
                if (selectedCategory == null) allThemes
                else allThemes.filter { it.category == selectedCategory }
            }
            val resolvedCurrentId = KeyboardThemeCatalog.resolveId(keyboardThemeId, systemDark, customThemes)
            filteredThemes.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { spec ->
                        KeyboardThemeCard(
                            spec = spec,
                            selected = spec.id == keyboardThemeId,
                            implied = followsSystem && spec.id == resolvedCurrentId,
                            appPalette = appPalette,
                            customThemeRevision = customThemeRevision,
                            onClick = { onKeyboardThemeIdChange(spec.id) },
                            onEdit = if (!spec.isBuiltIn) ({ onEditCustomTheme(spec) }) else null,
                            onDuplicate = if (!spec.isBuiltIn) ({ onDuplicateTheme(spec) }) else null,
                            onDelete = if (!spec.isBuiltIn) ({ pendingDeleteTheme = spec }) else null,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            pendingDeleteTheme?.let { target ->
                AlertDialog(
                    onDismissRequest = { pendingDeleteTheme = null },
                    title = { Text("カスタムテーマを削除") },
                    text = { Text("「${target.name}」を削除しますか？この操作は取り消せません。") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onDeleteCustomTheme(target.id)
                                pendingDeleteTheme = null
                            }
                        ) {
                            Text("削除", color = appPalette.danger)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDeleteTheme = null }) {
                            Text("キャンセル")
                        }
                    }
                )
            }
        }
    }

    SettingsSection("キーボード配列", appPalette) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "日本語入力キーボード配列",
                color = appPalette.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            SettingsSegmented(
                options = listOf(
                    JapaneseInputLayout.FLICK_12KEY.name to "12キー（フリック）",
                    JapaneseInputLayout.QWERTY.name to "QWERTY（ローマ字）"
                ),
                selected = japaneseLayout,
                palette = appPalette,
                onSelected = onJapaneseLayoutChange
            )
        }
    }

    SettingsSection("キーの大きさと配置", appPalette) {
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
                    color = appPalette.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${keyboardKeyHeight} dp",
                    color = appPalette.primaryAccentSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = keyboardKeyHeight.toFloat(),
                onValueChange = { value ->
                    onKeyboardKeyHeightChange(value.toInt().coerceIn(40, 64))
                },
                valueRange = 40f..64f,
                steps = 23,
                colors = SliderDefaults.colors(
                    thumbColor = appPalette.primaryAccent,
                    activeTrackColor = appPalette.primaryAccent,
                    inactiveTrackColor = appPalette.surfaceElevated
                )
            )
            Text(
                text = "QWERTYと12キーのキーボード全体の高さに反映されます。",
                color = appPalette.textMuted,
                fontSize = 12.sp
            )
        }
        SettingsDivider(appPalette)
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
                    color = appPalette.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${keyboardBottomPaddingDp} dp",
                    color = appPalette.primaryAccentSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = keyboardBottomPaddingDp.toFloat(),
                onValueChange = { value ->
                    onKeyboardBottomPaddingChange(value.toInt().coerceIn(0, 32))
                },
                valueRange = 0f..32f,
                steps = 31,
                colors = SliderDefaults.colors(
                    thumbColor = appPalette.primaryAccent,
                    activeTrackColor = appPalette.primaryAccent,
                    inactiveTrackColor = appPalette.surfaceElevated
                )
            )
            Text(
                text = "画面下端のベゼルやジェスチャーバーと最下段キーの間に余白を設け、指を届きやすくします（標準: 0 dp）。",
                color = appPalette.textMuted,
                fontSize = 12.sp
            )
        }
        SettingsDivider(appPalette)
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "片手モード",
                color = appPalette.textPrimary,
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
                palette = appPalette,
                onSelected = { modeName -> onOneHandedModeChange(OneHandedMode.fromName(modeName)) }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "片手モードの幅",
                    color = appPalette.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${oneHandedWidthPercent}%",
                    color = appPalette.primaryAccentSoft,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Slider(
                value = oneHandedWidthPercent.toFloat(),
                onValueChange = { value -> onOneHandedWidthPercentChange(value.toInt()) },
                valueRange = OneHandedLayout.MIN_WIDTH_PERCENT.toFloat()..OneHandedLayout.MAX_WIDTH_PERCENT.toFloat(),
                steps = OneHandedLayout.MAX_WIDTH_PERCENT - OneHandedLayout.MIN_WIDTH_PERCENT - 1,
                colors = SliderDefaults.colors(
                    thumbColor = appPalette.primaryAccent,
                    activeTrackColor = appPalette.primaryAccent,
                    inactiveTrackColor = appPalette.surfaceElevated
                )
            )
            Text(
                text = "キーボードを画面幅の60〜95%で左右どちらかに寄せます（標準: 82%）。狭くすると片手で届きやすくなります。ツールバーや側面ボタンからいつでも解除できます。",
                color = appPalette.textMuted,
                fontSize = 12.sp
            )
        }
        SettingsDivider(appPalette)
        SettingsSwitchRow(
            title = stringResource(R.string.split_keyboard_duplicate_keys),
            description = stringResource(R.string.split_keyboard_duplicate_keys_description),
            checked = splitDuplicateKeys,
            palette = appPalette,
            onCheckedChange = onSplitDuplicateKeysChange
        )
        SettingsDivider(appPalette)
        SplitKeyboardGapSetting(
            gapPercent = splitGapPercent,
            duplicateMiddleKeys = splitDuplicateKeys,
            keyHeight = keyboardKeyHeight.dp,
            showNumberRow = isQwertyNumberRow,
            keyBordersEnabled = isKeyBordersEnabled,
            palette = appPalette,
            keyboardTheme = keyboardTheme,
            backgroundImage = backgroundImage,
            onValueChange = onSplitGapPercentChange,
            onValueChangeFinished = onSplitGapPercentCommit
        )
    }

    SettingsSection("キーの表示", appPalette) {
        SettingsSwitchRow(
            title = "上部の数字キー",
            description = if (isQwertyNumberRow) {
                "QWERTY配列の最上段に 1〜0 の数字キーを表示します。"
            } else {
                "数字キーを隠し、最上段の q〜p を上フリックすると 1〜0 を入力します。"
            },
            checked = isQwertyNumberRow,
            palette = appPalette,
            onCheckedChange = onQwertyNumberRowChange
        )
        SettingsDivider(appPalette)
        SettingsSwitchRow(
            title = "キー拡大プレビュー",
            description = "QWERTYでキーに触れた瞬間に、押している文字をキー上に拡大表示します。",
            checked = isKeyPreviewEnabled,
            palette = appPalette,
            onCheckedChange = onKeyPreviewEnabledChange
        )
        SettingsDivider(appPalette)
        SettingsSwitchRow(
            title = "キー枠線と立体表示",
            description = "キーの境界線と立体的な縁取りを表示し、明暗テーマでキー位置を見分けやすくします。",
            checked = isKeyBordersEnabled,
            palette = appPalette,
            onCheckedChange = onKeyBordersEnabledChange
        )
    }

    SettingsSection("設定画面の配色", appPalette) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "キーボードの着せ替えとは分離し、設定画面を読みやすい標準配色に保ちます。",
                color = appPalette.textMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            SettingsThemePicker(
                selected = appThemeMode,
                palette = appPalette,
                onSelected = onAppThemeModeChange
            )
        }
    }
}

@Composable
private fun PreviewLayoutChip(
    label: String,
    selected: Boolean,
    palette: ZeroConPalette,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) palette.primaryAccent else palette.surfaceElevated,
        border = BorderStroke(1.dp, if (selected) palette.primaryAccent else palette.borderSubtle)
    ) {
        Text(
            text = label,
            color = if (selected) palette.onAccent else palette.textSecondary,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun KeyboardThemeCard(
    spec: KeyboardThemeSpec,
    selected: Boolean,
    implied: Boolean,
    appPalette: ZeroConPalette,
    customThemeRevision: Long = 0L,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cardPalette = remember(spec) { spec.toZeroConPalette() }
    val cardImageFileName = spec.background.imageFileName
    val cachedCardImage = remember(spec.id, cardImageFileName, customThemeRevision) {
        if (spec.background.kind == BackgroundKind.IMAGE && cardImageFileName != null) {
            ThemeBackgroundCache.get(
                ThemeBackgroundCache.key(spec.id, cardImageFileName, customThemeRevision)
            )
        } else {
            null
        }
    }
    val cardBgImage by produceState<ImageBitmap?>(
        initialValue = cachedCardImage,
        context,
        spec.id,
        spec.background.kind,
        cardImageFileName,
        customThemeRevision
    ) {
        value = if (spec.background.kind == BackgroundKind.IMAGE && cardImageFileName != null) {
            ThemeBackgroundCache.get(
                ThemeBackgroundCache.key(spec.id, cardImageFileName, customThemeRevision)
            ) ?: withContext(Dispatchers.IO) {
                ThemeBackgroundCache.loadRenderedImage(context, spec.id, cardImageFileName, customThemeRevision)
            }
        } else {
            null
        }
    }
    val borderColor = when {
        selected -> appPalette.primaryAccent
        implied -> appPalette.primaryAccent.copy(alpha = 0.60f)
        else -> appPalette.borderSubtle
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(appPalette.surfaceElevated)
            .border(width = if (selected) 2.dp else 1.dp, color = borderColor, shape = RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        // Mini-keyboard visual thumbnail using the real theme tokens
        val barColor = spec.bars.backgroundColor.toComposeColor().copy(
            alpha = if (spec.background.applyToBars) spec.bars.backgroundAlpha else 1f
        )
        CompositionLocalProvider(
            LocalZeroConPalette provides cardPalette,
            LocalKeyboardTheme provides spec,
            LocalKeyBordersEnabled provides true
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .keyboardThemeBackground(spec.background, cardBgImage)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .background(barColor)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "候補  日本語",
                        color = spec.bars.candidateTextColor.toComposeColor(),
                        fontSize = 7.5.sp,
                        maxLines = 1
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 5.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        listOf("あ", "か", "さ").forEach { label ->
                            KeyCapFrame(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                visualPadding = PaddingValues(0.dp),
                                cornerRadius = (spec.keySurface.cornerRadiusDp * 0.55f).dp,
                                baseColor = cardPalette.keyNormal,
                                isPressed = false,
                                isModifier = false,
                                palette = cardPalette,
                                theme = spec
                            ) {
                                Text(
                                    text = label,
                                    color = cardPalette.textPrimary,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        KeyCapFrame(
                            modifier = Modifier.weight(0.95f).fillMaxHeight(),
                            visualPadding = PaddingValues(0.dp),
                            cornerRadius = (spec.keySurface.cornerRadiusDp * 0.55f).dp,
                            baseColor = cardPalette.keyModifier,
                            isPressed = false,
                            isModifier = true,
                            palette = cardPalette,
                            theme = spec
                        ) {
                            Text(
                                text = "⌫",
                                color = cardPalette.textSecondary,
                                fontSize = 8.sp
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        KeyCapFrame(
                            modifier = Modifier.weight(0.95f).fillMaxHeight(),
                            visualPadding = PaddingValues(0.dp),
                            cornerRadius = (spec.keySurface.cornerRadiusDp * 0.55f).dp,
                            baseColor = cardPalette.keyModifier,
                            isPressed = false,
                            isModifier = true,
                            palette = cardPalette,
                            theme = spec
                        ) {
                            Text(
                                text = "あa1",
                                color = cardPalette.textSecondary,
                                fontSize = 7.sp
                            )
                        }
                        KeyCapFrame(
                            modifier = Modifier.weight(1.9f).fillMaxHeight(),
                            visualPadding = PaddingValues(0.dp),
                            cornerRadius = (spec.keySurface.cornerRadiusDp * 0.55f).dp,
                            baseColor = spec.keySurface.spaceKeyFill.toComposeColor(),
                            isPressed = false,
                            isModifier = false,
                            palette = cardPalette,
                            theme = spec
                        ) {
                            Text(
                                text = "空白",
                                color = cardPalette.textSecondary,
                                fontSize = 7.5.sp
                            )
                        }
                        KeyCapFrame(
                            modifier = Modifier.weight(1.1f).fillMaxHeight(),
                            visualPadding = PaddingValues(0.dp),
                            cornerRadius = (spec.keySurface.enterCornerRadiusDp * 0.55f).dp,
                            baseColor = cardPalette.primaryAccent,
                            isPressed = false,
                            isAccent = true,
                            palette = cardPalette,
                            theme = spec
                        ) {
                            Text(
                                text = "確定",
                                color = cardPalette.onAccent,
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(appPalette.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = spec.name,
                color = appPalette.textPrimary,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${spec.keySurface.preset.label} / ${if (spec.isDark) "暗色" else "明色"}",
                color = appPalette.textMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!spec.isBuiltIn) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onEdit != null) {
                        Text(
                            text = "編集",
                            color = appPalette.primaryAccentSoft,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(onClick = onEdit)
                        )
                    }
                    if (onDuplicate != null) {
                        Text(
                            text = "複製",
                            color = appPalette.textSecondary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable(onClick = onDuplicate)
                        )
                    }
                    if (onDelete != null) {
                        Text(
                            text = "削除",
                            color = appPalette.danger,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.clickable(onClick = onDelete)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun KeyboardThemeLivePreview(
    keyboardTheme: KeyboardThemeSpec,
    kbPalette: ZeroConPalette,
    previewLayoutName: String,
    isFlickOnly: Boolean,
    isQwertyNumberRow: Boolean,
    keyboardKeyHeight: Int,
    keyboardBottomPaddingDp: Int,
    oneHandedMode: OneHandedMode,
    oneHandedWidthPercent: Int,
    isKeyPreviewEnabled: Boolean,
    isKeyBordersEnabled: Boolean,
    isDynamicTouchAssistEnabled: Boolean,
    flickSensitivityDp: Int,
    onOneHandedModeChange: (OneHandedMode) -> Unit,
    borderColor: Color,
    backgroundImage: ImageBitmap? = null
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val realKeyboardWidth = LocalConfiguration.current.screenWidthDp.dp
        val previewScale = (maxWidth / realKeyboardWidth).coerceAtMost(1f)
        val previewRowCount = if (
            previewLayoutName == JapaneseInputLayout.QWERTY.name && isQwertyNumberRow
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
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .keyboardThemeBackground(keyboardTheme.background, backgroundImage)
        ) {
            Layout(
                content = {
                    ProvideKeyboardErgonomicsLocals(
                        palette = kbPalette,
                        onKeyPressHaptic = {},
                        keyPreviewEnabled = isKeyPreviewEnabled,
                        flickThresholdDp = flickSensitivityDp.dp,
                        keyBordersEnabled = isKeyBordersEnabled,
                        dynamicTouchAssistEnabled = isDynamicTouchAssistEnabled,
                        keyboardTheme = keyboardTheme
                    ) {
                        CompositionLocalProvider(LocalKeyboardKeyHeight provides keyboardKeyHeight.dp) {
                            Column(
                                Modifier
                                    .requiredWidth(realKeyboardWidth)
                                    .height(realKeyboardHeight)
                                    .keyboardThemeBackground(keyboardTheme.background, backgroundImage)
                            ) {
                                KeyboardAssistBar(
                                    reading = "",
                                    assist = null,
                                    correctionApplied = false,
                                    palette = kbPalette,
                                    onAdopt = {},
                                    onUndo = {},
                                    onOpenEmoji = {},
                                    onSelectAll = {},
                                    onOpenClipboard = {}
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(KeyboardBarHeight)
                                ) {
                                    HorizontalCandidateStrip(
                                        reading = "",
                                        candidates = listOf(Candidate("候補", "")),
                                        palette = kbPalette,
                                        onSelect = {},
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                val previewKeyboardBody: @Composable () -> Unit = {
                                    if (previewLayoutName == JapaneseInputLayout.FLICK_12KEY.name) {
                                        Japanese12KeyFlickLayout(
                                            keyBgColor = kbPalette.keyNormal,
                                            actionKeyBg = kbPalette.keyModifier,
                                            textColor = kbPalette.textPrimary,
                                            palette = kbPalette,
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
                                            onSwitchMode = {},
                                            onOpenSymbols = {},
                                            onOpenEmoji = {},
                                            onCursorLeft = {},
                                            onCursorRight = {}
                                        )
                                    } else {
                                        JapaneseQwertyLayout(
                                            isShifted = false,
                                            keyBgColor = kbPalette.keyNormal,
                                            actionKeyBg = kbPalette.keyModifier,
                                            textColor = kbPalette.textPrimary,
                                            palette = kbPalette,
                                            onKeyPress = {},
                                            onShiftToggle = {},
                                            onDelete = {},
                                            onSpace = {},
                                            onEnter = {},
                                            onSwitchMode = {},
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
                                        val keyboardWeight = OneHandedLayout.keyboardWeight(oneHandedWidthPercent)
                                        val railWeight = OneHandedLayout.railWeight(oneHandedWidthPercent)
                                        Row(modifier = Modifier.fillMaxSize()) {
                                            if (oneHandedMode == OneHandedMode.RIGHT) {
                                                OneHandedSideRail(
                                                    modifier = Modifier.weight(railWeight).fillMaxHeight(),
                                                    actionKeyBg = kbPalette.keyModifier,
                                                    textColor = kbPalette.textPrimary,
                                                    palette = kbPalette,
                                                    isLeftAligned = false,
                                                    onFlipSide = { onOneHandedModeChange(OneHandedMode.LEFT) },
                                                    onExitOneHanded = { onOneHandedModeChange(OneHandedMode.OFF) }
                                                )
                                                Box(modifier = Modifier.weight(keyboardWeight).fillMaxHeight()) {
                                                    previewKeyboardBody()
                                                }
                                            } else {
                                                Box(modifier = Modifier.weight(keyboardWeight).fillMaxHeight()) {
                                                    previewKeyboardBody()
                                                }
                                                OneHandedSideRail(
                                                    modifier = Modifier.weight(railWeight).fillMaxHeight(),
                                                    actionKeyBg = kbPalette.keyModifier,
                                                    textColor = kbPalette.textPrimary,
                                                    palette = kbPalette,
                                                    isLeftAligned = true,
                                                    onFlipSide = { onOneHandedModeChange(OneHandedMode.RIGHT) },
                                                    onExitOneHanded = { onOneHandedModeChange(OneHandedMode.OFF) }
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
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                }
            }
        }
    }
}
