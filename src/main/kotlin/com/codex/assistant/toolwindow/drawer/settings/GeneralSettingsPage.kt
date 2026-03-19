package com.codex.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.settings.UiLanguageMode
import com.codex.assistant.settings.UiThemeMode
import com.codex.assistant.toolwindow.drawer.RightDrawerAreaState
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.assistantUiTokens

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
            title = CodexBundle.message("settings.group.appearance"),
            description = CodexBundle.message("settings.group.appearance.subtitle"),
        )
        SettingsField(
            p = p,
            title = CodexBundle.message("settings.theme.label"),
            description = CodexBundle.message("settings.theme.hint"),
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
            title = CodexBundle.message("settings.language.label"),
            description = CodexBundle.message("settings.language.hint"),
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
            title = CodexBundle.message("settings.group.environment"),
            description = CodexBundle.message("settings.group.environment.subtitle"),
        )
        SettingsField(
            p = p,
            title = CodexBundle.message("settings.codexPath.label"),
            description = CodexBundle.message("settings.codexPath.hint"),
        ) {
            SettingsTextInput(
                p = p,
                value = state.codexCliPath,
                onValueChange = { onIntent(UiIntent.EditSettingsCodexCliPath(it)) },
            )
        }
        Spacer(Modifier.height(t.spacing.sm))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SettingsActionButton(
                p = p,
                text = CodexBundle.message("common.save"),
                onClick = { onIntent(UiIntent.SaveSettings) },
            )
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
            Text(CodexBundle.message("settings.language.followIde"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiLanguageMode.ZH) }) {
            Text(CodexBundle.message("settings.language.zh"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiLanguageMode.EN) }) {
            Text(CodexBundle.message("settings.language.en"))
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
            Text(CodexBundle.message("settings.theme.followIde"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiThemeMode.LIGHT) }) {
            Text(CodexBundle.message("settings.theme.light"))
        }
        DropdownMenuItem(onClick = { expanded = false; onSelect(UiThemeMode.DARK) }) {
            Text(CodexBundle.message("settings.theme.dark"))
        }
    }
}

private fun languageModeLabel(mode: UiLanguageMode): String = when (mode) {
    UiLanguageMode.FOLLOW_IDE -> CodexBundle.message("settings.language.followIde")
    UiLanguageMode.ZH -> CodexBundle.message("settings.language.zh")
    UiLanguageMode.EN -> CodexBundle.message("settings.language.en")
}

private fun themeModeLabel(mode: UiThemeMode): String = when (mode) {
    UiThemeMode.FOLLOW_IDE -> CodexBundle.message("settings.theme.followIde")
    UiThemeMode.LIGHT -> CodexBundle.message("settings.theme.light")
    UiThemeMode.DARK -> CodexBundle.message("settings.theme.dark")
}
