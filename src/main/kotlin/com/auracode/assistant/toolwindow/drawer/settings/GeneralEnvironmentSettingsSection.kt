package com.auracode.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.provider.codex.CodexEnvironmentCheckResult
import com.auracode.assistant.provider.codex.CodexEnvironmentStatus
import com.auracode.assistant.toolwindow.drawer.RightDrawerAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/** Renders the self-contained environment editing and validation flow for the general settings page. */
@Composable
internal fun GeneralEnvironmentSettingsSection(
    p: DesignPalette,
    state: RightDrawerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val codexPathInputState = rememberSettingsTextInputState(state.codexCliPath)
    val nodePathInputState = rememberSettingsTextInputState(state.nodePath)

    SettingsField(
        p = p,
        title = AuraCodeBundle.message("settings.environment.result.label"),
        description = AuraCodeBundle.message("settings.environment.result.hint"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(t.spacing.md)) {
            EnvironmentPathInput(
                p = p,
                title = AuraCodeBundle.message("settings.codexPath.label"),
                description = AuraCodeBundle.message("settings.codexPath.hint"),
                value = codexPathInputState.value,
                onValueChange = {
                    codexPathInputState.value = it
                    onIntent(UiIntent.EditSettingsCodexCliPath(it.text))
                },
                status = fieldStatus(
                    configuredValue = state.codexCliPath,
                    detectedValue = state.environmentCheckResult?.codexPath,
                    resolvedStatus = state.environmentCheckResult?.codexStatus,
                ),
            )
            EnvironmentPathInput(
                p = p,
                title = AuraCodeBundle.message("settings.nodePath.label"),
                description = AuraCodeBundle.message("settings.nodePath.hint"),
                value = nodePathInputState.value,
                onValueChange = {
                    nodePathInputState.value = it
                    onIntent(UiIntent.EditSettingsNodePath(it.text))
                },
                status = fieldStatus(
                    configuredValue = state.nodePath,
                    detectedValue = state.environmentCheckResult?.nodePath,
                    resolvedStatus = state.environmentCheckResult?.nodeStatus,
                ),
            )
            EnvironmentActivityPanel(
                p = p,
                result = state.environmentCheckResult,
                running = state.environmentCheckRunning,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(t.spacing.sm, Alignment.End),
            ) {
                SettingsActionButton(
                    p = p,
                    text = AuraCodeBundle.message("settings.environment.test"),
                    emphasized = false,
                    enabled = !state.environmentCheckRunning,
                    onClick = { onIntent(UiIntent.TestCodexEnvironment) },
                )
                if (state.isEnvironmentSaveVisible) {
                    SettingsActionButton(
                        p = p,
                        text = AuraCodeBundle.message("common.save"),
                        enabled = !state.environmentCheckRunning,
                        onClick = { onIntent(UiIntent.SaveSettings) },
                    )
                }
            }
            if (state.isEnvironmentSaveVisible) {
                Text(
                    text = AuraCodeBundle.message("settings.environment.unsaved"),
                    color = p.textMuted,
                    style = MaterialTheme.typography.caption,
                )
            }
        }
    }
}

/** Draws one environment path editor with its hint and the latest field-level status badge. */
@Composable
private fun EnvironmentPathInput(
    p: DesignPalette,
    title: String,
    description: String,
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    status: CodexEnvironmentStatus,
) {
    val t = assistantUiTokens()
    Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
        Text(
            text = title,
            color = p.textPrimary,
            style = MaterialTheme.typography.body2,
        )
        SettingsTextInput(
            p = p,
            value = value,
            onValueChange = onValueChange,
        )
        EnvironmentFieldBadge(
            p = p,
            label = statusLabel(status),
            status = status,
        )
        Text(
            text = description,
            color = p.textMuted,
            style = MaterialTheme.typography.caption,
        )
    }
}

/** Shows either the active auto-detect progress or the latest compact validation summary. */
@Composable
private fun EnvironmentActivityPanel(
    p: DesignPalette,
    result: CodexEnvironmentCheckResult?,
    running: Boolean,
) {
    if (!running && result == null) return
    val t = assistantUiTokens()
    Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
        if (running) {
            Text(
                text = AuraCodeBundle.message("settings.environment.checking"),
                color = p.textSecondary,
                style = MaterialTheme.typography.body2,
            )
        }
        result?.let {
            EnvironmentResultPanel(
                p = p,
                result = it,
            )
        }
    }
}

@Composable
private fun EnvironmentFieldBadge(
    p: DesignPalette,
    label: String,
    status: CodexEnvironmentStatus,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        SettingsStatusBadge(
            p = p,
            text = label,
            status = status,
        )
    }
}

@Composable
private fun EnvironmentResultPanel(
    p: DesignPalette,
    result: CodexEnvironmentCheckResult,
) {
    val t = assistantUiTokens()
    Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
        EnvironmentResultRow(p, AuraCodeBundle.message("settings.codexPath.label"), statusLabel(result.codexStatus), result.codexStatus)
        EnvironmentResultRow(p, AuraCodeBundle.message("settings.nodePath.label"), statusLabel(result.nodeStatus), result.nodeStatus)
        EnvironmentResultRow(p, AuraCodeBundle.message("settings.environment.appServer"), statusLabel(result.appServerStatus), result.appServerStatus)
        Text(
            text = result.message,
            color = p.textSecondary,
            style = MaterialTheme.typography.body2,
        )
    }
}

@Composable
private fun EnvironmentResultRow(
    p: DesignPalette,
    title: String,
    label: String,
    status: CodexEnvironmentStatus,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            color = p.textPrimary,
            style = MaterialTheme.typography.body2,
        )
        Row(modifier = Modifier.wrapContentWidth(), horizontalArrangement = Arrangement.End) {
            SettingsStatusBadge(p = p, text = label, status = status)
        }
    }
}

internal fun fieldStatus(
    configuredValue: String,
    detectedValue: String?,
    resolvedStatus: CodexEnvironmentStatus?,
): CodexEnvironmentStatus {
    return when {
        resolvedStatus == CodexEnvironmentStatus.FAILED && configuredValue.trim().isNotBlank() -> CodexEnvironmentStatus.FAILED
        configuredValue.trim().isNotBlank() -> CodexEnvironmentStatus.CONFIGURED
        !detectedValue.isNullOrBlank() -> CodexEnvironmentStatus.DETECTED
        else -> CodexEnvironmentStatus.MISSING
    }
}

internal fun statusLabel(status: CodexEnvironmentStatus): String = when (status) {
    CodexEnvironmentStatus.CONFIGURED -> AuraCodeBundle.message("settings.environment.status.configured")
    CodexEnvironmentStatus.DETECTED -> AuraCodeBundle.message("settings.environment.status.detected")
    CodexEnvironmentStatus.MISSING -> AuraCodeBundle.message("settings.environment.status.missing")
    CodexEnvironmentStatus.FAILED -> AuraCodeBundle.message("settings.environment.status.failed")
}
