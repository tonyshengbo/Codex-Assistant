package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantBodyTextStyle
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun PanelHeader(
    p: DesignPalette,
    title: String,
    subtitle: String,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 44.dp),
    ) {
        Text(
            text = title,
            color = p.textPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.h6,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(t.spacing.xs))
            Text(subtitle, color = p.textSecondary, style = MaterialTheme.typography.body2)
        }
    }
}

@Composable
internal fun OverlayCloseButton(
    p: DesignPalette,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    HoverTooltip(text = AuraCodeBundle.message("common.close")) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(t.spacing.sm))
                .background(p.topStripBg.copy(alpha = 0.96f))
                .border(1.dp, p.markdownDivider.copy(alpha = 0.5f), RoundedCornerShape(t.spacing.sm))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource("/icons/close.svg"),
                contentDescription = AuraCodeBundle.message("common.close"),
                tint = p.textSecondary,
                modifier = Modifier.size(t.controls.iconLg),
            )
        }
    }
}

@Composable
internal fun SettingsRailItem(
    p: DesignPalette,
    selected: Boolean,
    iconPath: String,
    description: String,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    HoverTooltip(text = description) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(t.spacing.sm))
                .background(if (selected) p.accent.copy(alpha = 0.14f) else Color.Transparent)
                .border(width = 1.dp, color = if (selected) p.accent.copy(alpha = 0.22f) else Color.Transparent, shape = RoundedCornerShape(t.spacing.sm))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconPath),
                contentDescription = description,
                tint = if (selected) p.accent else p.textSecondary,
                modifier = Modifier.size(t.controls.iconLg),
            )
        }
    }
}

@Composable
internal fun SettingsGroupHeader(
    p: DesignPalette,
    title: String,
    description: String,
) {
    val t = assistantUiTokens()
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, color = p.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.subtitle1)
        Spacer(Modifier.height(t.spacing.xs))
        Text(description, color = p.textMuted, fontStyle = FontStyle.Normal, style = MaterialTheme.typography.body2)
    }
}

@Composable
internal fun SettingsField(
    p: DesignPalette,
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.64f), RoundedCornerShape(t.spacing.lg))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.48f), RoundedCornerShape(t.spacing.lg))
            .padding(horizontal = t.spacing.md, vertical = t.spacing.md),
    ) {
        Text(title, color = p.textPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.body2)
        Spacer(Modifier.height(t.spacing.md))
        content()
        Spacer(Modifier.height(t.spacing.sm))
        Text(description, color = p.textMuted, style = MaterialTheme.typography.caption)
    }
}

@Composable
internal fun SettingsActionButton(
    p: DesignPalette,
    text: String,
    emphasized: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    val background = when {
        !enabled -> p.topStripBg
        emphasized -> p.accent.copy(alpha = 0.18f)
        else -> p.topBarBg
    }
    val border = when {
        !enabled -> p.markdownDivider.copy(alpha = 0.3f)
        emphasized -> p.accent.copy(alpha = 0.8f)
        else -> p.markdownDivider.copy(alpha = 0.55f)
    }
    val textColor = when {
        !enabled -> p.textMuted
        emphasized -> p.accent
        else -> p.textSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(t.spacing.sm))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(t.spacing.sm))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = textColor, style = MaterialTheme.typography.body2, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun SettingsInlineMessage(
    p: DesignPalette,
    text: String,
    isError: Boolean = false,
) {
    val color = if (isError) Color(0xFFE28A8A) else p.textMuted
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.caption,
    )
}

@Composable
internal fun SettingsSegmentTabs(
    p: DesignPalette,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.64f), RoundedCornerShape(t.spacing.lg))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.48f), RoundedCornerShape(t.spacing.lg))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(t.spacing.md))
                    .background(if (selected) p.accent.copy(alpha = 0.16f) else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (selected) p.accent.copy(alpha = 0.35f) else Color.Transparent,
                        shape = RoundedCornerShape(t.spacing.md),
                    )
                    .clickable(onClick = { onSelect(index) })
                    .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) p.accent else p.textSecondary,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
internal fun SettingsStatusBadge(
    p: DesignPalette,
    text: String,
    status: CodexEnvironmentStatus,
) {
    val t = assistantUiTokens()
    val background = when (status) {
        CodexEnvironmentStatus.CONFIGURED -> p.accent.copy(alpha = 0.16f)
        CodexEnvironmentStatus.DETECTED -> p.topStripBg
        CodexEnvironmentStatus.MISSING -> p.topBarBg
        CodexEnvironmentStatus.FAILED -> Color(0x66C84B4B)
    }
    val foreground = when (status) {
        CodexEnvironmentStatus.CONFIGURED -> p.accent
        CodexEnvironmentStatus.DETECTED -> p.textSecondary
        CodexEnvironmentStatus.MISSING -> p.textMuted
        CodexEnvironmentStatus.FAILED -> Color(0xFFFFB4AB)
    }
    Surface(
        color = background,
        shape = RoundedCornerShape(t.spacing.xs),
        modifier = Modifier.border(1.dp, foreground.copy(alpha = 0.28f), RoundedCornerShape(t.spacing.xs)),
    ) {
        Text(
            text = text,
            color = foreground,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.padding(horizontal = t.spacing.sm, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun SettingsSelectField(
    p: DesignPalette,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(t.spacing.sm))
            .background(p.topStripBg)
            .border(1.dp, p.markdownDivider.copy(alpha = 0.55f), RoundedCornerShape(t.spacing.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = p.textPrimary,
            style = MaterialTheme.typography.body2,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(t.spacing.sm))
        Icon(
            painter = painterResource("/icons/arrow-down.svg"),
            contentDescription = null,
            tint = p.textMuted,
            modifier = Modifier.size(t.controls.iconLg),
        )
    }
}

@Composable
internal fun SettingsTextInput(
    p: DesignPalette,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val t = assistantUiTokens()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = assistantBodyTextStyle(t).copy(color = p.textPrimary),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        shape = RoundedCornerShape(t.spacing.sm),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = p.textPrimary,
            backgroundColor = p.topStripBg,
            cursorColor = p.accent,
            focusedBorderColor = p.accent.copy(alpha = 0.8f),
            unfocusedBorderColor = p.markdownDivider.copy(alpha = 0.55f),
            focusedLabelColor = p.textSecondary,
            unfocusedLabelColor = p.textMuted,
        ),
    )
}

@Composable
internal fun rememberSettingsTextInputState(text: String): MutableState<TextFieldValue> {
    val inputState = remember { mutableStateOf(TextFieldValue(text = text, selection = TextRange(text.length))) }
    LaunchedEffect(text) {
        if (inputState.value.text != text) {
            inputState.value = TextFieldValue(
                text = text,
                selection = TextRange(text.length),
            )
        }
    }
    return inputState
}

@Composable
internal fun SettingsToggle(
    p: DesignPalette,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(
        checked = checked,
        enabled = enabled,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = p.accent,
            checkedTrackColor = p.accent.copy(alpha = 0.4f),
            uncheckedThumbColor = p.textMuted,
            uncheckedTrackColor = p.topStripBg,
        ),
    )
}
