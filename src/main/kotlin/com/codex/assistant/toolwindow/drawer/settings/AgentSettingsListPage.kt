package com.codex.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.toolwindow.drawer.RightDrawerAreaState
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun AgentSettingsListPage(
    p: DesignPalette,
    state: RightDrawerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.md),
    ) {
        Text(
            text = CodexBundle.message("settings.agent.list.title"),
            color = p.textPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.h6,
        )
        Text(
            text = CodexBundle.message("settings.agent.editor.subtitle"),
            color = p.textSecondary,
            style = MaterialTheme.typography.body2,
        )
        SettingsActionButton(
            p = p,
            text = CodexBundle.message("settings.agent.new"),
            onClick = { onIntent(UiIntent.CreateNewAgentDraft) },
        )
        if (state.savedAgents.isEmpty()) {
            Text(
                text = CodexBundle.message("settings.agent.empty"),
                color = p.textMuted,
                style = MaterialTheme.typography.body2,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                state.savedAgents.forEach { agent ->
                    AgentListRow(
                        p = p,
                        name = agent.name,
                        selected = false,
                        onClick = { onIntent(UiIntent.SelectSavedAgentForEdit(agent.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentListRow(
    p: DesignPalette,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) p.userBubbleBg.copy(alpha = 0.45f) else p.topBarBg.copy(alpha = 0.72f),
                RoundedCornerShape(t.spacing.md),
            )
            .border(
                width = 1.dp,
                color = if (selected) p.accent.copy(alpha = 0.35f) else p.markdownDivider.copy(alpha = 0.4f),
                shape = RoundedCornerShape(t.spacing.md),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            painter = painterResource("/icons/agent-settings.svg"),
            contentDescription = null,
            tint = if (selected) p.accent else p.textSecondary,
            modifier = Modifier.size(t.controls.iconMd),
        )
        Spacer(Modifier.width(t.spacing.sm))
        Text(
            text = name,
            color = if (selected) p.textPrimary else p.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.body2,
        )
    }
}
