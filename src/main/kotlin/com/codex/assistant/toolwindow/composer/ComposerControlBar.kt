package com.codex.assistant.toolwindow.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.provider.CodexModelCatalog
import com.codex.assistant.toolwindow.eventing.ComposerMode
import com.codex.assistant.toolwindow.eventing.ComposerReasoning
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.eventing.localizedLabel
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.HoverTooltip
import com.codex.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun ComposerControlBar(
    p: DesignPalette,
    state: ComposerAreaState,
    running: Boolean,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.62f), RoundedCornerShape(t.spacing.sm))
            .padding(start = t.spacing.sm, end = 2.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropdownChip(
            iconPath = modeOption(state.selectedMode).iconPath,
            label = state.selectedMode.localizedLabel(),
            expanded = state.modeMenuExpanded,
            onToggle = { onIntent(UiIntent.ToggleModeMenu) },
            onDismiss = { onIntent(UiIntent.ToggleModeMenu) },
            p = p,
        ) {
            ComposerMode.entries.forEach { mode ->
                DropdownMenuItem(onClick = { onIntent(UiIntent.SelectMode(mode)) }) {
                    DropdownOptionRow(
                        option = modeOption(mode),
                        selected = state.selectedMode == mode,
                        p = p,
                    )
                }
            }
        }
        Spacer(Modifier.width(t.spacing.sm))
        DropdownChip(
            iconPath = if (state.selectedModel.contains("gpt")) "/icons/gpt.svg" else "/icons/codex.svg",
            label = state.selectedModel,
            expanded = state.modelMenuExpanded,
            onToggle = { onIntent(UiIntent.ToggleModelMenu) },
            onDismiss = { onIntent(UiIntent.ToggleModelMenu) },
            p = p,
        ) {
            CodexModelCatalog.ids().forEach { model ->
                DropdownMenuItem(onClick = { onIntent(UiIntent.SelectModel(model)) }) {
                    DropdownOptionRow(
                        option = modelOption(model),
                        selected = state.selectedModel == model,
                        p = p,
                    )
                }
            }
        }
        Spacer(Modifier.width(t.spacing.sm))
        DropdownChip(
            iconPath = reasoningIcon(state.selectedReasoning),
            label = state.selectedReasoning.localizedLabel(),
            expanded = state.reasoningMenuExpanded,
            onToggle = { onIntent(UiIntent.ToggleReasoningMenu) },
            onDismiss = { onIntent(UiIntent.ToggleReasoningMenu) },
            p = p,
        ) {
            ComposerReasoning.entries.forEach { level ->
                DropdownMenuItem(onClick = { onIntent(UiIntent.SelectReasoning(level)) }) {
                    DropdownOptionRow(
                        option = reasoningOption(level),
                        selected = state.selectedReasoning == level,
                        p = p,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val canSend = !running && state.hasPromptContent()
        HoverTooltip(text = if (running) CodexBundle.message("composer.stop") else CodexBundle.message("composer.send")) {
            TrailingActionChip(
                iconPath = if (running) "/icons/stop.svg" else "/icons/send.svg",
                contentDescription = if (running) CodexBundle.message("composer.stop") else CodexBundle.message("composer.send"),
                enabled = running || canSend,
                running = running,
                p = p,
                onClick = {
                    if (running) {
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
    iconPath: String,
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
            Icon(
                painter = painterResource(iconPath),
                contentDescription = null,
                tint = p.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(t.spacing.xs))
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

private fun reasoningIcon(reasoning: ComposerReasoning): String = when (reasoning) {
    ComposerReasoning.LOW -> "/icons/stat_0.svg"
    ComposerReasoning.MEDIUM -> "/icons/stat_1.svg"
    ComposerReasoning.HIGH -> "/icons/stat_2.svg"
    ComposerReasoning.MAX -> "/icons/stat_3.svg"
}

private data class DropdownOption(
    val iconPath: String,
    val label: String,
)

private fun modeOption(mode: ComposerMode): DropdownOption = when (mode) {
    ComposerMode.AUTO -> DropdownOption(iconPath = "/icons/auto-mode.svg", label = mode.localizedLabel())
    ComposerMode.APPROVAL -> DropdownOption(iconPath = "/icons/auto-mode-off.svg", label = mode.localizedLabel())
}

private fun modelOption(model: String): DropdownOption = DropdownOption(
    iconPath = if (model.contains("gpt", ignoreCase = true)) "/icons/gpt.svg" else "/icons/codex.svg",
    label = model,
)

private fun reasoningOption(reasoning: ComposerReasoning): DropdownOption = DropdownOption(
    iconPath = reasoningIcon(reasoning),
    label = reasoning.localizedLabel(),
)

@Composable
private fun DropdownOptionRow(
    option: DropdownOption,
    selected: Boolean,
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
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(option.iconPath),
            contentDescription = null,
            tint = p.textSecondary,
            modifier = Modifier.size(t.controls.iconMd),
        )
        Spacer(Modifier.width(t.spacing.sm))
        Text(
            text = option.label,
            color = if (selected) p.textPrimary else p.textSecondary,
            style = androidx.compose.material.MaterialTheme.typography.body2.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
        )
    }
}
