package com.auracode.assistant.toolwindow.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.ComposerReasoning
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.eventing.localizedLabel
import com.auracode.assistant.toolwindow.drawer.settings.SettingsTextInput
import com.auracode.assistant.toolwindow.drawer.settings.rememberSettingsTextInputState
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun ComposerControlBar(
    p: DesignPalette,
    state: ComposerAreaState,
    running: Boolean,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val canSend = state.hasPromptContent()
    val sendModeWhileRunning = running && canSend
    val showStop = running && !canSend
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.62f), RoundedCornerShape(t.spacing.sm))
            .padding(start = t.spacing.sm, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropdownChip(
            label = state.selectedModel,
            expanded = state.modelMenuExpanded,
            onToggle = { onIntent(UiIntent.ToggleModelMenu) },
            onDismiss = { onIntent(UiIntent.ToggleModelMenu) },
            p = p,
        ) {
            state.modelOptions.forEach { option ->
                DropdownMenuItem(onClick = { onIntent(UiIntent.SelectModel(option.id)) }) {
                    DropdownOptionRow(
                        label = option.id,
                        selected = state.selectedModel == option.id,
                        isCustom = option.isCustom,
                        onDelete = if (option.isCustom) {
                            { onIntent(UiIntent.DeleteCustomModel(option.id)) }
                        } else {
                            null
                        },
                        p = p,
                    )
                }
            }
            DropdownMenuItem(onClick = { onIntent(UiIntent.StartAddingCustomModel) }) {
                Text(
                    text = AuraCodeBundle.message("composer.model.custom.add"),
                    color = p.accent,
                    style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
                )
            }
            if (state.addingCustomModel) {
                CustomModelInputSection(
                    p = p,
                    draft = state.customModelDraft,
                    onDraftChange = { onIntent(UiIntent.EditCustomModelDraft(it)) },
                    onSave = { onIntent(UiIntent.SaveCustomModel) },
                    onCancel = { onIntent(UiIntent.CancelAddingCustomModel) },
                )
            }
        }
        Spacer(Modifier.width(t.spacing.sm))
        DropdownChip(
            label = state.selectedReasoning.localizedLabel(),
            expanded = state.reasoningMenuExpanded,
            onToggle = { onIntent(UiIntent.ToggleReasoningMenu) },
            onDismiss = { onIntent(UiIntent.ToggleReasoningMenu) },
            p = p,
        ) {
            ComposerReasoning.entries.forEach { level ->
                DropdownMenuItem(onClick = { onIntent(UiIntent.SelectReasoning(level)) }) {
                    DropdownOptionRow(
                        label = level.localizedLabel(),
                        selected = state.selectedReasoning == level,
                        isCustom = false,
                        onDelete = null,
                        p = p,
                    )
                }
            }
        }
        if (state.planModeAvailable) {
            Spacer(Modifier.width(t.spacing.sm))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .background(p.markdownDivider.copy(alpha = 0.55f))
                    .padding(vertical = 8.dp),
            )
            Spacer(Modifier.width(t.spacing.sm))
            HoverTooltip(
                text = if (state.executionMode == com.auracode.assistant.toolwindow.eventing.ComposerMode.AUTO) {
                    AuraCodeBundle.message("composer.mode.auto.tooltip.enabled")
                } else {
                    AuraCodeBundle.message("composer.mode.auto.tooltip.disabled")
                },
            ) {
                ToggleChip(
                    label = AuraCodeBundle.message("composer.mode.auto"),
                    enabled = state.executionMode == com.auracode.assistant.toolwindow.eventing.ComposerMode.AUTO,
                    p = p,
                    onClick = { onIntent(UiIntent.ToggleExecutionMode) },
                )
            }
            Spacer(Modifier.width(t.spacing.sm))
            HoverTooltip(text = AuraCodeBundle.message("composer.mode.plan.tooltip")) {
                ToggleChip(
                    label = AuraCodeBundle.message("composer.mode.plan"),
                    enabled = state.planEnabled,
                    p = p,
                    onClick = { onIntent(UiIntent.TogglePlanMode) },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        HoverTooltip(
            text = when {
                showStop -> AuraCodeBundle.message("composer.stop")
                sendModeWhileRunning -> AuraCodeBundle.message("composer.pending.enqueue")
                else -> AuraCodeBundle.message("composer.send")
            },
        ) {
            TrailingActionChip(
                iconPath = if (showStop) "/icons/stop.svg" else "/icons/send.svg",
                contentDescription = when {
                    showStop -> AuraCodeBundle.message("composer.stop")
                    sendModeWhileRunning -> AuraCodeBundle.message("composer.pending.enqueue")
                    else -> AuraCodeBundle.message("composer.send")
                },
                enabled = showStop || canSend,
                running = showStop,
                p = p,
                onClick = {
                    if (showStop) {
                        onIntent(UiIntent.CancelRun)
                    } else {
                        onIntent(UiIntent.SendPrompt)
                    }
                },
            )
        }
    }
}

@Composable
private fun DropdownChip(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDismiss: () -> Unit,
    p: DesignPalette,
    content: @Composable () -> Unit,
) {
    val t = assistantUiTokens()
    Box {
        Row(
            modifier = Modifier
                .background(Color.Transparent, RoundedCornerShape(t.spacing.sm))
                .clickable(onClick = onToggle)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = p.textSecondary)
            Spacer(Modifier.width(t.spacing.xs))
            Icon(
                painter = painterResource("/icons/arrow-down.svg"),
                contentDescription = null,
                tint = p.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
            content()
        }
    }
}

@Composable
private fun TrailingActionChip(
    iconPath: String,
    contentDescription: String,
    enabled: Boolean,
    running: Boolean,
    p: DesignPalette,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    val shape = RoundedCornerShape(6.dp)
    val backgroundColor = when {
        running -> p.danger.copy(alpha = 0.22f)
        enabled -> p.accent.copy(alpha = 0.20f)
        else -> p.topStripBg.copy(alpha = 0.92f)
    }
    val borderColor = when {
        running -> p.danger.copy(alpha = 0.72f)
        enabled -> p.accent.copy(alpha = 0.88f)
        else -> p.markdownDivider.copy(alpha = 0.38f)
    }
    val iconTint = when {
        running -> p.danger
        enabled -> p.accent
        else -> p.textMuted
    }

    Box(
        modifier = Modifier
            .size(26.dp)
            .border(width = 1.dp, color = borderColor, shape = shape)
            .background(backgroundColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconPath),
            contentDescription = contentDescription,
            tint = iconTint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun DropdownOptionRow(
    label: String,
    selected: Boolean,
    isCustom: Boolean,
    onDelete: (() -> Unit)?,
    p: DesignPalette,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (selected) p.topStripBg else Color.Transparent,
                shape = RoundedCornerShape(t.spacing.sm),
            )
            .padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f, fill = false),
            color = if (selected) p.textPrimary else p.textSecondary,
            style = MaterialTheme.typography.body2.copy(
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
        )
        if (isCustom) {
            ModelBadge(
                text = AuraCodeBundle.message("composer.model.custom.badge"),
                textColor = p.accent,
                background = p.accent.copy(alpha = 0.12f),
            )
        }
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    painter = painterResource("/icons/delete.svg"),
                    contentDescription = AuraCodeBundle.message("composer.model.custom.delete"),
                    tint = p.textMuted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun ModelBadge(
    text: String,
    textColor: Color,
    background: Color,
) {
    val t = assistantUiTokens()
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(999.dp))
            .padding(horizontal = t.spacing.xs, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
        )
    }
}

@Composable
private fun CustomModelInputSection(
    p: DesignPalette,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val draftInputState = rememberSettingsTextInputState(draft)
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Column(
        modifier = Modifier
            .widthIn(min = 260.dp, max = 320.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsTextInput(
            p = p,
            value = draftInputState.value,
            onValueChange = {
                draftInputState.value = it
                onDraftChange(it.text)
            },
            modifier = Modifier.focusRequester(focusRequester),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = AuraCodeBundle.message("common.cancel"),
                color = p.textSecondary,
                modifier = Modifier.clickable(onClick = onCancel),
            )
            Text(
                text = AuraCodeBundle.message("common.save"),
                color = p.accent,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = onSave),
            )
        }
    }
}

@Composable
private fun ToggleChip(
    label: String,
    enabled: Boolean,
    p: DesignPalette,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    Text(
        text = label,
        color = if (enabled) p.accent else p.textSecondary,
        fontWeight = if (enabled) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .background(Color.Transparent, RoundedCornerShape(t.spacing.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
