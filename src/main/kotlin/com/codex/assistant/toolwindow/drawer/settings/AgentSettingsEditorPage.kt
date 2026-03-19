package com.codex.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.toolwindow.drawer.RightDrawerAreaState
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.HoverTooltip
import com.codex.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun AgentSettingsEditorPage(
    p: DesignPalette,
    state: RightDrawerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = t.spacing.xl + t.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HoverTooltip(text = CodexBundle.message("common.back")) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(t.spacing.sm))
                            .background(p.topBarBg)
                            .border(1.dp, p.markdownDivider.copy(alpha = 0.45f), RoundedCornerShape(t.spacing.sm))
                            .clickable(onClick = { onIntent(UiIntent.ShowAgentSettingsList) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource("/icons/arrow-right.svg"),
                            contentDescription = CodexBundle.message("common.back"),
                            tint = p.textSecondary,
                            modifier = Modifier.size(t.controls.iconLg).rotate(180f),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.agentDraftName.ifBlank { CodexBundle.message("settings.agent.new") },
                        color = p.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.h6,
                    )
                    Spacer(Modifier.height(t.spacing.xs))
                    Text(
                        text = CodexBundle.message("settings.agent.editor.subtitle"),
                        color = p.textSecondary,
                        style = MaterialTheme.typography.body2,
                    )
                }
            }

            SettingsField(
                p = p,
                title = CodexBundle.message("settings.agent.name.label"),
                description = CodexBundle.message("settings.agent.name.hint"),
            ) {
                SettingsTextInput(
                    p = p,
                    value = state.agentDraftName,
                    onValueChange = { onIntent(UiIntent.EditAgentDraftName(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            SettingsField(
                p = p,
                title = CodexBundle.message("settings.agent.prompt.label"),
                description = CodexBundle.message("settings.agent.prompt.hint"),
            ) {
                SettingsTextInput(
                    p = p,
                    value = state.agentDraftPrompt,
                    onValueChange = { onIntent(UiIntent.EditAgentDraftPrompt(it)) },
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    singleLine = false,
                    minLines = 12,
                    maxLines = 12,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(p.topBarBg.copy(alpha = 0.72f), RoundedCornerShape(t.spacing.lg))
                .border(1.dp, p.markdownDivider.copy(alpha = 0.45f), RoundedCornerShape(t.spacing.lg))
                .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!state.editingAgentId.isNullOrBlank()) {
                SettingsActionButton(
                    p = p,
                    text = CodexBundle.message("common.delete"),
                    emphasized = false,
                    onClick = { onIntent(UiIntent.DeleteSavedAgent(state.editingAgentId)) },
                )
            }
            Spacer(Modifier.weight(1f))
            SettingsActionButton(
                p = p,
                text = CodexBundle.message("common.save"),
                enabled = state.agentDraftName.trim().isNotBlank() && state.agentDraftPrompt.trim().isNotBlank(),
                onClick = { onIntent(UiIntent.SaveAgentDraft) },
            )
        }
    }
}
