package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
import com.auracode.assistant.toolwindow.settings.RuntimeSettingsTab
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/** Renders the lightweight Runtime settings surface with strict single-tab visibility. */
@Composable
internal fun RuntimeSettingsPage(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        RuntimeHeaderActions(
            p = p,
            isSaveVisible = state.isEnvironmentSaveVisible,
            onDiscard = { onIntent(UiIntent.DiscardRuntimeSettingsChanges) },
            onSave = { onIntent(UiIntent.SaveSettings) },
        )
        SettingsSegmentTabs(
            p = p,
            options = listOf(
                AuraCodeBundle.message("settings.runtime.tab.codex"),
                AuraCodeBundle.message("settings.runtime.tab.claude"),
            ),
            selectedIndex = if (state.runtimeSettingsTab == RuntimeSettingsTab.CODEX) 0 else 1,
            onSelect = { index ->
                onIntent(
                    UiIntent.SelectRuntimeSettingsTab(
                        if (index == 0) RuntimeSettingsTab.CODEX else RuntimeSettingsTab.CLAUDE,
                    ),
                )
            },
        )
        when (state.runtimeSettingsTab) {
            RuntimeSettingsTab.CODEX -> CodexRuntimeTabContent(
                p = p,
                state = state,
                onIntent = onIntent,
            )
            RuntimeSettingsTab.CLAUDE -> ClaudeRuntimeTabContent(
                p = p,
                state = state,
                onIntent = onIntent,
            )
        }
    }
}

/** Renders the shared header action row for the Runtime page. */
@Composable
private fun RuntimeHeaderActions(
    p: DesignPalette,
    isSaveVisible: Boolean,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!isSaveVisible) {
            Text(
                text = AuraCodeBundle.message("settings.runtime.header.saved"),
                color = p.textMuted,
                style = MaterialTheme.typography.caption,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsActionButton(
                    p = p,
                    text = AuraCodeBundle.message("common.discard"),
                    emphasized = false,
                    onClick = onDiscard,
                )
                SettingsActionButton(
                    p = p,
                    text = AuraCodeBundle.message("settings.runtime.header.save"),
                    onClick = onSave,
                )
            }
        }
    }
}

/** Renders the Codex-specific Runtime content without leaking Claude content. */
@Composable
private fun CodexRuntimeTabContent(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val result = state.environmentCheckResult
    val isReady = result != null &&
        result.codexStatus != CodexEnvironmentStatus.FAILED &&
        result.codexStatus != CodexEnvironmentStatus.MISSING &&
        (state.nodePath.isBlank() || result.nodeStatus != CodexEnvironmentStatus.FAILED)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(assistantUiTokens().spacing.lg),
    ) {
        RuntimeExecutablePathsSection(
            p = p,
            model = RuntimeExecutablePathsUiModel(
                cliPathLabel = AuraCodeBundle.message("settings.runtime.codexPath.label"),
                cliPathHint = AuraCodeBundle.message("settings.runtime.cliPath.hint"),
                cliPathValue = state.codexCliPath,
                cliPathError = codexCliPathError(state),
                nodePathLabel = AuraCodeBundle.message("settings.runtime.nodePath.label"),
                nodePathHint = AuraCodeBundle.message("settings.runtime.nodePath.hint"),
                nodePathValue = state.nodePath,
                nodePathError = nodePathError(
                    nodePath = state.nodePath,
                    status = result?.nodeStatus,
                ),
                readinessText = if (isReady) AuraCodeBundle.message("settings.runtime.paths.ready") else null,
            ),
            onCliPathChange = { onIntent(UiIntent.EditSettingsCodexCliPath(it)) },
            onNodePathChange = { onIntent(UiIntent.EditSettingsNodePath(it)) },
        )
        RuntimeCliVersionSection(
            p = p,
            model = buildCodexRuntimeCliVersionPanelModel(state.codexCliVersionSnapshot),
            onIntent = onIntent,
        )
    }
}

/** Renders the Claude-specific Runtime content without leaking Codex content. */
@Composable
private fun ClaudeRuntimeTabContent(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val result = state.claudeRuntimeCheckResult
    val isReady = result != null &&
        result.cliStatus != CodexEnvironmentStatus.FAILED &&
        result.cliStatus != CodexEnvironmentStatus.MISSING &&
        (state.nodePath.isBlank() || result.nodeStatus != CodexEnvironmentStatus.FAILED)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(assistantUiTokens().spacing.lg),
    ) {
        RuntimeExecutablePathsSection(
            p = p,
            model = RuntimeExecutablePathsUiModel(
                cliPathLabel = AuraCodeBundle.message("settings.runtime.claudePath.label"),
                cliPathHint = AuraCodeBundle.message("settings.runtime.cliPath.hint"),
                cliPathValue = state.claudeCliPath,
                cliPathError = claudeCliPathError(state),
                nodePathLabel = AuraCodeBundle.message("settings.runtime.nodePath.label"),
                nodePathHint = AuraCodeBundle.message("settings.runtime.nodePath.hint"),
                nodePathValue = state.nodePath,
                nodePathError = nodePathError(
                    nodePath = state.nodePath,
                    status = result?.nodeStatus,
                ),
                readinessText = if (isReady) AuraCodeBundle.message("settings.runtime.paths.ready") else null,
            ),
            onCliPathChange = { onIntent(UiIntent.EditSettingsClaudeCliPath(it)) },
            onNodePathChange = { onIntent(UiIntent.EditSettingsNodePath(it)) },
        )
        RuntimeCliVersionSection(
            p = p,
            model = buildClaudeRuntimeCliVersionPanelModel(state.claudeCliVersionSnapshot),
            onIntent = onIntent,
        )
    }
}

/** Builds the inline error text for the active Codex CLI path field. */
private fun codexCliPathError(state: SidePanelAreaState): String? {
    return when (state.environmentCheckResult?.codexStatus) {
        CodexEnvironmentStatus.FAILED -> AuraCodeBundle.message("settings.runtime.codexPath.error.invalid")
        CodexEnvironmentStatus.MISSING -> AuraCodeBundle.message("settings.runtime.codexPath.error.missing")
        else -> null
    }
}

/** Builds the inline error text for the active Claude CLI path field. */
private fun claudeCliPathError(state: SidePanelAreaState): String? {
    return when (state.claudeRuntimeCheckResult?.cliStatus) {
        CodexEnvironmentStatus.FAILED -> AuraCodeBundle.message("settings.runtime.claudePath.error.invalid")
        CodexEnvironmentStatus.MISSING -> AuraCodeBundle.message("settings.runtime.claudePath.error.missing")
        else -> null
    }
}

/** Builds the inline error text for the shared optional Node path field. */
private fun nodePathError(
    nodePath: String,
    status: CodexEnvironmentStatus?,
): String? {
    if (nodePath.isBlank()) return null
    return when (status) {
        CodexEnvironmentStatus.FAILED -> AuraCodeBundle.message("settings.runtime.nodePath.error.invalid")
        CodexEnvironmentStatus.MISSING -> AuraCodeBundle.message("settings.runtime.nodePath.error.missing")
        else -> null
    }
}
