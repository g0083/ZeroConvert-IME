package com.zeroconvert.ime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SettingsTab(
    val id: String,
    val label: String,
    val icon: ImageVector
)

val SettingsTabs = listOf(
    SettingsTab("input", "入力", Icons.Outlined.Keyboard),
    SettingsTab("conversion", "変換", Icons.Outlined.Tune),
    SettingsTab("data", "学習とデータ", Icons.Outlined.Storage),
    SettingsTab("translation", "翻訳", Icons.Outlined.Translate),
    SettingsTab("benchmark", "モデルと評価", Icons.Outlined.Speed),
    SettingsTab("appearance", "外観", Icons.Outlined.Palette),
    SettingsTab("about", "情報", Icons.Outlined.Info)
)

@Composable
fun SettingsSection(
    title: String,
    palette: ZeroConPalette,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = palette.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp
        )
        SettingsCard(palette = palette, content = content)
    }
}

@Composable
fun SettingsCard(
    palette: ZeroConPalette,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(palette.surface)
            .border(1.dp, palette.borderSubtle, RoundedCornerShape(10.dp)),
        content = content
    )
}

@Composable
fun SettingsInnerCard(
    palette: ZeroConPalette,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(palette.surfaceElevated),
        content = content
    )
}

@Composable
fun SettingsDivider(palette: ZeroConPalette) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(palette.borderSubtle)
    )
}

@Composable
fun SettingsRow(
    title: String,
    palette: ZeroConPalette,
    description: String? = null,
    badge: String? = null,
    control: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    color = palette.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp
                )
                if (badge != null) {
                    SettingsBadge(badge, palette)
                }
            }
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = palette.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
        control()
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    palette: ZeroConPalette,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    badge: String? = null,
    enabled: Boolean = true
) {
    SettingsRow(
        title = title,
        palette = palette,
        description = description,
        badge = badge
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.primaryAccent,
                uncheckedThumbColor = palette.textSecondary,
                uncheckedTrackColor = palette.surfaceElevated,
                uncheckedBorderColor = palette.borderSubtle
            )
        )
    }
}

@Composable
fun SettingsNote(
    text: String,
    palette: ZeroConPalette,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        color = palette.textMuted,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
fun SettingsBadge(text: String, palette: ZeroConPalette) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = palette.primaryAccent.copy(alpha = 0.14f)
    ) {
        Text(
            text = text,
            color = palette.primaryAccent,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SettingsStatusPill(
    text: String,
    ready: Boolean,
    palette: ZeroConPalette
) {
    val color = if (ready) palette.readyEmerald else palette.warningAmber
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Text(
                text = text,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun <T> SettingsSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    palette: ZeroConPalette,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surfaceElevated, RoundedCornerShape(8.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelected(value) },
                shape = RoundedCornerShape(6.dp),
                color = if (isSelected) palette.surface else Color.Transparent,
                border = if (isSelected) BorderStroke(1.dp, palette.borderSubtle) else null
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) palette.textPrimary else palette.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsTabBar(
    tabs: List<SettingsTab>,
    selectedId: String,
    palette: ZeroConPalette,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tabs.forEach { tab ->
            val selected = tab.id == selectedId
            Surface(
                modifier = Modifier.clickable { onSelected(tab.id) },
                shape = RoundedCornerShape(8.dp),
                color = if (selected) palette.primaryAccent else palette.surface,
                border = BorderStroke(1.dp, if (selected) palette.primaryAccent else palette.borderSubtle)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        tint = if (selected) palette.onAccent else palette.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = tab.label,
                        color = if (selected) palette.onAccent else palette.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsPrimaryButton(
    text: String,
    palette: ZeroConPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.primaryAccent,
            contentColor = palette.onAccent,
            disabledContainerColor = palette.surfaceElevated,
            disabledContentColor = palette.textMuted
        )
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
fun SettingsSecondaryButton(
    text: String,
    palette: ZeroConPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, palette.borderSubtle),
        colors = ButtonDefaults.buttonColors(
            containerColor = palette.surfaceElevated,
            contentColor = palette.textPrimary,
            disabledContainerColor = palette.surface,
            disabledContentColor = palette.textMuted
        )
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun settingsTextFieldColors(palette: ZeroConPalette): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = palette.textPrimary,
        unfocusedTextColor = palette.textPrimary,
        disabledTextColor = palette.textMuted,
        focusedBorderColor = palette.primaryAccent,
        unfocusedBorderColor = palette.borderSubtle,
        disabledBorderColor = palette.borderSubtle,
        cursorColor = palette.primaryAccent,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        focusedPlaceholderColor = palette.textMuted,
        unfocusedPlaceholderColor = palette.textMuted
    )
