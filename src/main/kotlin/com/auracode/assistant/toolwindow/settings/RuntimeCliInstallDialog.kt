package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shell.RuntimeCliInstallDialogState
import com.auracode.assistant.toolwindow.shared.AssistantDialogFrame
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/** Renders the package-manager selection dialog used before one runtime CLI installation. */
@Composable
internal fun RuntimeCliInstallDialog(
    p: DesignPalette,
    state: RuntimeCliInstallDialogState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val selectedOption = state.selectedOption()
    val canConfirm = selectedOption?.available == true
    AssistantDialogFrame(
        palette = p,
        onDismissRequest = { onIntent(UiIntent.DismissRuntimeCliInstallDialog) },
        modifier = Modifier
            .widthIn(min = 380.dp, max = 460.dp)
            .fillMaxWidth(0.92f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(t.spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
            ) {
                PanelHeader(
                    p = p,
                    title = AuraCodeBundle.message(
                        "settings.runtime.install.dialog.title",
                        runtimeEngineLabel(state.engineId),
                    ),
                    subtitle = AuraCodeBundle.message(
                        "settings.runtime.install.dialog.message",
                        runtimeEngineLabel(state.engineId),
                    ),
                )
                OverlayCloseButton(
                    p = p,
                    onClick = { onIntent(UiIntent.DismissRuntimeCliInstallDialog) },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                Text(
                    text = AuraCodeBundle.message("settings.runtime.install.dialog.packageManager"),
                    color = p.textPrimary,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                )
                state.options.forEach { option ->
                    RuntimeCliInstallOptionRow(
                        p = p,
                        label = option.packageManagerId,
                        selected = state.selectedPackageManagerId == option.packageManagerId,
                        available = option.available,
                        onClick = {
                            if (option.available) {
                                onIntent(UiIntent.SelectRuntimeCliInstallPackageManager(option.packageManagerId))
                            }
                        },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.xs)) {
                Text(
                    text = AuraCodeBundle.message("settings.runtime.install.dialog.command"),
                    color = p.textPrimary,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(t.spacing.md))
                        .background(p.topStripBg.copy(alpha = 0.46f))
                        .border(1.dp, p.markdownDivider.copy(alpha = 0.3f), RoundedCornerShape(t.spacing.md))
                        .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
                ) {
                    Text(
                        text = selectedOption?.commandPreview.orEmpty(),
                        color = p.textSecondary,
                        style = MaterialTheme.typography.body2,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                    SettingsActionButton(
                        p = p,
                        text = AuraCodeBundle.message("common.cancel"),
                        emphasized = false,
                        onClick = { onIntent(UiIntent.DismissRuntimeCliInstallDialog) },
                    )
                    SettingsActionButton(
                        p = p,
                        text = AuraCodeBundle.message("settings.runtime.version.action.install"),
                        emphasized = true,
                        enabled = canConfirm,
                        onClick = {
                            selectedOption?.takeIf { it.available }?.let { option ->
                                val intent = when (state.engineId.trim().lowercase()) {
                                    RuntimeSettingsTab.CLAUDE.engineId -> UiIntent.InstallClaudeCli(option.packageManagerId)
                                    else -> UiIntent.InstallCodexCli(option.packageManagerId)
                                }
                                onIntent(intent)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Renders one package-manager row inside the runtime install dialog. */
@Composable
private fun RuntimeCliInstallOptionRow(
    p: DesignPalette,
    label: String,
    selected: Boolean,
    available: Boolean,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    val background = when {
        selected && available -> p.success.copy(alpha = 0.14f)
        else -> p.topBarBg.copy(alpha = 0.56f)
    }
    val border = when {
        selected && available -> p.success.copy(alpha = 0.42f)
        else -> p.markdownDivider.copy(alpha = 0.3f)
    }
    val textColor = if (available) p.textPrimary else p.textMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(t.spacing.md))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(t.spacing.md))
            .clickable(enabled = available, onClick = onClick)
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        RuntimeCliInstallRadio(
            p = p,
            selected = selected && available,
            enabled = available,
        )
        Text(
            text = label,
            color = textColor,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (available) {
                AuraCodeBundle.message("settings.runtime.install.dialog.available")
            } else {
                AuraCodeBundle.message("settings.runtime.install.dialog.unavailable")
            },
            color = if (available) p.success else p.textMuted,
            style = MaterialTheme.typography.caption,
        )
    }
}

/** Draws the small selection indicator used by runtime install options. */
@Composable
private fun RuntimeCliInstallRadio(
    p: DesignPalette,
    selected: Boolean,
    enabled: Boolean,
) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .border(
                width = 1.dp,
                color = when {
                    selected -> p.success
                    enabled -> p.markdownDivider.copy(alpha = 0.64f)
                    else -> p.markdownDivider.copy(alpha = 0.28f)
                },
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(p.success),
            )
        } else if (!enabled) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color.Transparent),
            )
        }
    }
}

/** Resolves the localized runtime label rendered in install dialog copy. */
private fun runtimeEngineLabel(engineId: String): String {
    return when (engineId.trim().lowercase()) {
        RuntimeSettingsTab.CLAUDE.engineId -> AuraCodeBundle.message("settings.runtime.tab.claude")
        else -> AuraCodeBundle.message("settings.runtime.tab.codex")
    }
}

