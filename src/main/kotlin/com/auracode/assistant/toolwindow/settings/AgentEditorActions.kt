package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun AgentEditorActions(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.72f), RoundedCornerShape(t.spacing.lg))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.45f), RoundedCornerShape(t.spacing.lg))
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
    ) {
        if (!state.editingAgentId.isNullOrBlank()) {
            Row(modifier = Modifier.align(Alignment.CenterStart)) {
                SettingsActionButton(
                    p = p,
                    text = AuraCodeBundle.message("common.delete"),
                    emphasized = false,
                    onClick = { onIntent(UiIntent.DeleteSavedAgent(state.editingAgentId)) },
                )
            }
        }
        Row(modifier = Modifier.align(Alignment.CenterEnd)) {
            SettingsActionButton(
                p = p,
                text = AuraCodeBundle.message("common.save"),
                enabled = state.agentDraftName.trim().isNotBlank() && state.agentDraftPrompt.trim().isNotBlank(),
                onClick = { onIntent(UiIntent.SaveAgentDraft) },
            )
        }
    }
}
