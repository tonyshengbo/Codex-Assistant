package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun AgentEditorDialog(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Dialog(
        onDismissRequest = { onIntent(UiIntent.ShowAgentSettingsList) },
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = 640.dp, max = 860.dp)
                .fillMaxWidth(0.9f)
                .heightIn(max = 760.dp)
                .background(p.appBg, RoundedCornerShape(t.spacing.lg))
                .border(1.dp, p.markdownDivider.copy(alpha = 0.45f), RoundedCornerShape(t.spacing.lg))
                .padding(horizontal = t.spacing.lg, vertical = t.spacing.lg),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
                ) {
                    PanelHeader(
                        p = p,
                        title = state.agentDraftName.ifBlank { AuraCodeBundle.message("settings.agent.new") },
                        subtitle = AuraCodeBundle.message("settings.agent.editor.subtitle"),
                    )
                    OverlayCloseButton(
                        p = p,
                        onClick = { onIntent(UiIntent.ShowAgentSettingsList) },
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                ) {
                    AgentEditorForm(
                        p = p,
                        state = state,
                        onIntent = onIntent,
                    )
                }
                AgentEditorActions(
                    p = p,
                    state = state,
                    onIntent = onIntent,
                )
            }
        }
    }
}
