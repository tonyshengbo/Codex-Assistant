package com.auracode.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.provider.CodexEnvironmentCheckResult
import com.auracode.assistant.provider.CodexEnvironmentStatus
import com.auracode.assistant.settings.UiLanguageMode
import com.auracode.assistant.settings.UiThemeMode
import com.auracode.assistant.toolwindow.drawer.RightDrawerAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun GeneralSettingsPage(
    p: DesignPalette,
    state: RightDrawerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val codexPathInputState = rememberSettingsTextInputState(state.codexCliPath)
    val nodePathInputState = rememberSettingsTextInputState(state.nodePath)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        SettingsGroupHeader(
            p = p,
            title = AuraCodeBundle.message("settings.group.appearance"),
            description = AuraCodeBundle.message("settings.group.appearance.subtitle"),
        )
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.theme.label"),
            description = AuraCodeBundle.message("settings.theme.hint"),
        ) {
            ThemeModeDropdown(
                p = p,
                value = state.themeMode,
                onSelect = { onIntent(UiIntent.EditSettingsThemeMode(it)) },
                modifier = Modifier.width(220.dp),
            )
        }
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.language.label"),
            description = AuraCodeBundle.message("settings.language.hint"),
        ) {
            LanguageModeDropdown(
                p = p,
                value = state.languageMode,
                onSelect = { onIntent(UiIntent.EditSettingsLanguageMode(it)) },
                modifier = Modifier.width(220.dp),
            )
        }
        SettingsGroupHeader(
            p = p,
            title = AuraCodeBundle.message("settings.group.environment"),
            description = AuraCodeBundle.message("settings.group.environment.subtitle"),
        )
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.codexPath.label"),
            description = AuraCodeBundle.message("settings.codexPath.hint"),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                SettingsTextInput(
                    p = p,
                    value = codexPathInputState.value,
                    onValueChange = {
                        codexPathInputState.value = it
                        onIntent(UiIntent.EditSettingsCodexCliPath(it.text))
                    },
                )
                EnvironmentFieldBadge(
                    p = p,
                    label = fieldStatusLabel(
                        configuredValue = state.codexCliPath,
                        detectedValue = state.environmentCheckResult?.codexPath,
                    ),
                    status = fieldStatus(
                        configuredValue = state.codexCliPath,
                        detectedValue = state.environmentCheckResult?.codexPath,
                    ),
                )
            }
        }
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.nodePath.label"),
            description = AuraCodeBundle.message("settings.nodePath.hint"),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                SettingsTextInput(
                    p = p,
                    value = nodePathInputState.value,
                    onValueChange = {
                        nodePathInputState.value = it
                        onIntent(UiIntent.EditSettingsNodePath(it.text))
                    },
                )
                EnvironmentFieldBadge(
                    p = p,
                    label = fieldStatusLabel(
                        configuredValue = state.nodePath,
                        detectedValue = state.environmentCheckResult?.nodePath,
                    ),
                    status = fieldStatus(
                        configuredValue = state.nodePath,
                        detectedValue = state.environmentCheckResult?.nodePath,
                    ),
                )
            }
        }
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.autoContext.label"),
            description = AuraCodeBundle.message("settings.autoContext.hint"),
        ) {
            SettingsToggle(
                p = p,
                checked = state.autoContextEnabled,
                onCheckedChange = { onIntent(UiIntent.EditSettingsAutoContextEnabled(it)) },
            )
        }
        Spacer(Modifier.height(t.spacing.sm))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.spacing.sm, alignment = androidx.compose.ui.Alignment.End)) {
            SettingsActionButton(
                p = p,
                text = AuraCodeBundle.message("settings.environment.detect"),
                emphasized = false,
                enabled = !state.environmentCheckRunning,
                onClick = { onIntent(UiIntent.DetectCodexEnvironment) },
            )
            SettingsActionButton(
                p = p,
                text = AuraCodeBundle.message("settings.environment.test"),
                emphasized = false,
                enabled = !state.environmentCheckRunning,
                onClick = { onIntent(UiIntent.TestCodexEnvironment) },
            )
            SettingsActionButton(
                p = p,
                text = AuraCodeBundle.message("common.save"),
                onClick = { onIntent(UiIntent.SaveSettings) },
            )
        }
        state.environmentCheckResult?.let { result ->
            SettingsField(
                p = p,
                title = AuraCodeBundle.message("settings.environment.result.label"),
                description = AuraCodeBundle.message("settings.environment.result.hint"),
            ) {
                EnvironmentResultPanel(
                    p = p,
                    result = result,
                )
            }
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

@Composable
private fun LanguageModeDropdown(
    p: DesignPalette,
    value: UiLanguageMode,
    onSelect: (UiLanguageMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsSelectField(
        p = p,
        text = languageModeLabel(value),
        modifier = modifier,
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiLanguageMode.FOLLOW_IDE) }) {
            Text(AuraCodeBundle.message("settings.language.followIde"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiLanguageMode.ZH) }) {
            Text(AuraCodeBundle.message("settings.language.zh"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiLanguageMode.EN) }) {
            Text(AuraCodeBundle.message("settings.language.en"))
        }
    }
}

@Composable
private fun ThemeModeDropdown(
    p: DesignPalette,
    value: UiThemeMode,
    onSelect: (UiThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsSelectField(
        p = p,
        text = themeModeLabel(value),
        modifier = modifier,
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiThemeMode.FOLLOW_IDE) }) {
            Text(AuraCodeBundle.message("settings.theme.followIde"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiThemeMode.LIGHT) }) {
            Text(AuraCodeBundle.message("settings.theme.light"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiThemeMode.DARK) }) {
            Text(AuraCodeBundle.message("settings.theme.dark"))
        }
    }
}

private fun languageModeLabel(mode: UiLanguageMode): String = when (mode) {
    UiLanguageMode.FOLLOW_IDE -> AuraCodeBundle.message("settings.language.followIde")
    UiLanguageMode.ZH -> AuraCodeBundle.message("settings.language.zh")
    UiLanguageMode.EN -> AuraCodeBundle.message("settings.language.en")
}

private fun themeModeLabel(mode: UiThemeMode): String = when (mode) {
    UiThemeMode.FOLLOW_IDE -> AuraCodeBundle.message("settings.theme.followIde")
    UiThemeMode.LIGHT -> AuraCodeBundle.message("settings.theme.light")
    UiThemeMode.DARK -> AuraCodeBundle.message("settings.theme.dark")
}

private fun fieldStatus(
    configuredValue: String,
    detectedValue: String?,
): CodexEnvironmentStatus {
    return when {
        configuredValue.trim().isNotBlank() -> CodexEnvironmentStatus.CONFIGURED
        !detectedValue.isNullOrBlank() -> CodexEnvironmentStatus.DETECTED
        else -> CodexEnvironmentStatus.MISSING
    }
}

private fun fieldStatusLabel(
    configuredValue: String,
    detectedValue: String?,
): String = statusLabel(fieldStatus(configuredValue, detectedValue))

private fun statusLabel(status: CodexEnvironmentStatus): String = when (status) {
    CodexEnvironmentStatus.CONFIGURED -> AuraCodeBundle.message("settings.environment.status.configured")
    CodexEnvironmentStatus.DETECTED -> AuraCodeBundle.message("settings.environment.status.detected")
    CodexEnvironmentStatus.MISSING -> AuraCodeBundle.message("settings.environment.status.missing")
    CodexEnvironmentStatus.FAILED -> AuraCodeBundle.message("settings.environment.status.failed")
}
