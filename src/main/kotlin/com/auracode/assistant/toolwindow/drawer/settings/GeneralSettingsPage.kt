package com.auracode.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.settings.UiLanguageMode
import com.auracode.assistant.settings.UiScaleMode
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
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.fontScale.label"),
            description = AuraCodeBundle.message("settings.fontScale.hint"),
        ) {
            UiScaleModeDropdown(
                p = p,
                value = state.uiScaleMode,
                onSelect = { onIntent(UiIntent.EditSettingsUiScaleMode(it)) },
                modifier = Modifier.width(220.dp),
            )
        }
        SettingsGroupHeader(
            p = p,
            title = AuraCodeBundle.message("settings.group.environment"),
            description = AuraCodeBundle.message("settings.group.environment.subtitle"),
        )
        GeneralEnvironmentSettingsSection(p = p, state = state, onIntent = onIntent)
        CodexCliVersionSettingsSection(p = p, state = state, onIntent = onIntent)
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
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.backgroundNotifications.label"),
            description = AuraCodeBundle.message("settings.backgroundNotifications.hint"),
        ) {
            SettingsToggle(
                p = p,
                checked = state.backgroundCompletionNotificationsEnabled,
                onCheckedChange = {
                    onIntent(UiIntent.EditSettingsBackgroundCompletionNotificationsEnabled(it))
                },
            )
        }
    }
}

@Composable
private fun UiScaleModeDropdown(
    p: DesignPalette,
    value: UiScaleMode,
    onSelect: (UiScaleMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsSelectField(
        p = p,
        text = uiScaleModeLabel(value),
        modifier = modifier,
        onClick = { expanded = true },
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiScaleMode.P80) }) {
            Text(AuraCodeBundle.message("settings.fontScale.80"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiScaleMode.P90) }) {
            Text(AuraCodeBundle.message("settings.fontScale.90"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiScaleMode.P100) }) {
            Text(AuraCodeBundle.message("settings.fontScale.100"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiScaleMode.P110) }) {
            Text(AuraCodeBundle.message("settings.fontScale.110"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiScaleMode.P120) }) {
            Text(AuraCodeBundle.message("settings.fontScale.120"))
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
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiLanguageMode.JA) }) {
            Text(AuraCodeBundle.message("settings.language.ja"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiLanguageMode.KO) }) {
            Text(AuraCodeBundle.message("settings.language.ko"))
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
    UiLanguageMode.JA -> AuraCodeBundle.message("settings.language.ja")
    UiLanguageMode.KO -> AuraCodeBundle.message("settings.language.ko")
}

private fun themeModeLabel(mode: UiThemeMode): String = when (mode) {
    UiThemeMode.FOLLOW_IDE -> AuraCodeBundle.message("settings.theme.followIde")
    UiThemeMode.LIGHT -> AuraCodeBundle.message("settings.theme.light")
    UiThemeMode.DARK -> AuraCodeBundle.message("settings.theme.dark")
}

private fun uiScaleModeLabel(mode: UiScaleMode): String = when (mode) {
    UiScaleMode.P80 -> AuraCodeBundle.message("settings.fontScale.80")
    UiScaleMode.P90 -> AuraCodeBundle.message("settings.fontScale.90")
    UiScaleMode.P100 -> AuraCodeBundle.message("settings.fontScale.100")
    UiScaleMode.P110 -> AuraCodeBundle.message("settings.fontScale.110")
    UiScaleMode.P120 -> AuraCodeBundle.message("settings.fontScale.120")
}
