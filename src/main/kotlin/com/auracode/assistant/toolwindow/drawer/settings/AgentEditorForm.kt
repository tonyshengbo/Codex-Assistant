package com.auracode.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.drawer.RightDrawerAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun AgentEditorForm(
    p: DesignPalette,
    state: RightDrawerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val nameInputState = rememberSettingsTextInputState(state.agentDraftName)
    val promptInputState = rememberSettingsTextInputState(state.agentDraftPrompt)
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.agent.name.label"),
            description = AuraCodeBundle.message("settings.agent.name.hint"),
        ) {
            SettingsTextInput(
                p = p,
                value = nameInputState.value,
                onValueChange = {
                    nameInputState.value = it
                    onIntent(UiIntent.EditAgentDraftName(it.text))
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        SettingsField(
            p = p,
            title = AuraCodeBundle.message("settings.agent.prompt.label"),
            description = AuraCodeBundle.message("settings.agent.prompt.hint"),
        ) {
            // Keep prompt editing tall enough for reuse instructions without
            // forcing the whole settings page to become a dedicated editor view.
            SettingsTextInput(
                p = p,
                value = promptInputState.value,
                onValueChange = {
                    promptInputState.value = it
                    onIntent(UiIntent.EditAgentDraftPrompt(it.text))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                singleLine = false,
                minLines = 12,
                maxLines = 12,
            )
        }
    }
}
