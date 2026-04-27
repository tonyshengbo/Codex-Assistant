package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
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
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun AgentSettingsListPage(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.md),
    ) {
        Text(
            text = AuraCodeBundle.message("settings.agent.list.title"),
            color = p.textPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.h6,
        )
        Text(
            text = AuraCodeBundle.message("settings.agent.editor.subtitle"),
            color = p.textSecondary,
            style = MaterialTheme.typography.body2,
        )
        SettingsActionButton(
            p = p,
            text = AuraCodeBundle.message("settings.agent.new"),
            onClick = { onIntent(UiIntent.CreateNewAgentDraft) },
        )
        if (state.savedAgents.isEmpty()) {
            Text(
                text = AuraCodeBundle.message("settings.agent.empty"),
                color = p.textMuted,
                style = MaterialTheme.typography.body2,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                state.savedAgents.forEach { agent ->
                    AgentListRow(
                        p = p,
                        name = agent.name,
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
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .background(
                p.topBarBg.copy(alpha = 0.78f),
                RoundedCornerShape(10.dp),
            )
            .border(
                width = 1.dp,
                color = p.markdownDivider.copy(alpha = 0.46f),
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            painter = painterResource("/icons/agent-settings.svg"),
            contentDescription = null,
            tint = p.textSecondary,
            modifier = Modifier.size(t.controls.iconMd),
        )
        Spacer(Modifier.width(t.spacing.sm))
        Text(
            text = name,
            color = p.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.body1,
            fontWeight = FontWeight.Medium,
        )
    }
}
