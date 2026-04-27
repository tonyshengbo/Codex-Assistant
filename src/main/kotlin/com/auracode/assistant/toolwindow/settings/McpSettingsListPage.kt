package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.settings.mcp.McpServerSummary
import com.auracode.assistant.settings.mcp.McpTransportType
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun McpSettingsListPage(
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
            text = AuraCodeBundle.message("settings.mcp.list.title"),
            color = p.textPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.h6,
        )
        Text(
            text = AuraCodeBundle.message("settings.mcp.list.subtitle"),
            color = p.textSecondary,
            style = MaterialTheme.typography.body2,
        )
        SettingsActionButton(
            p = p,
            text = AuraCodeBundle.message("settings.mcp.new"),
            onClick = { onIntent(UiIntent.CreateNewMcpDraft) },
        )
        if (state.mcpServers.isEmpty()) {
            Text(
                text = AuraCodeBundle.message("settings.mcp.empty"),
                color = p.textMuted,
                style = MaterialTheme.typography.body2,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(t.spacing.sm)) {
                state.mcpServers.forEach { server ->
                    McpServerRow(
                        p = p,
                        server = server,
                        isUpdating = state.mcpBusyState.loading,
                        isDeleting = state.mcpBusyState.deletingName == server.name,
                        onOpen = { onIntent(UiIntent.SelectMcpServerForEdit(server.name)) },
                        onToggleEnabled = { enabled ->
                            onIntent(UiIntent.ToggleMcpServerEnabled(server.name, enabled))
                        },
                        onDelete = { onIntent(UiIntent.DeleteMcpServer(server.name)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun McpServerRow(
    p: DesignPalette,
    server: McpServerSummary,
    isUpdating: Boolean,
    isDeleting: Boolean,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.72f), RoundedCornerShape(t.spacing.md))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.42f), RoundedCornerShape(t.spacing.md))
            .clickable(onClick = onOpen)
            .padding(horizontal = t.spacing.md, vertical = t.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = server.name,
                    color = p.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.body1,
                )
                TransportBadge(p = p, transportType = server.transportType)
                EnabledBadge(p = p, enabled = server.enabled)
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsToggle(
                p = p,
                checked = server.enabled,
                enabled = !isUpdating && !isDeleting,
                onCheckedChange = onToggleEnabled,
            )
            SettingsActionButton(
                p = p,
                text = AuraCodeBundle.message("common.delete"),
                emphasized = false,
                enabled = !isDeleting,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun TransportBadge(
    p: DesignPalette,
    transportType: McpTransportType,
) {
    val t = assistantUiTokens()
    Box(
        modifier = Modifier
            .background(p.accent.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .border(1.dp, p.accent.copy(alpha = 0.28f), RoundedCornerShape(999.dp))
            .padding(horizontal = t.spacing.sm, vertical = 4.dp),
    ) {
        Text(
            text = when (transportType) {
                McpTransportType.STDIO -> AuraCodeBundle.message("settings.mcp.transport.stdio")
                McpTransportType.STREAMABLE_HTTP -> AuraCodeBundle.message("settings.mcp.transport.http")
            },
            color = p.accent,
            style = MaterialTheme.typography.caption,
        )
    }
}

@Composable
private fun EnabledBadge(
    p: DesignPalette,
    enabled: Boolean,
) {
    val t = assistantUiTokens()
    val background = if (enabled) p.accent.copy(alpha = 0.12f) else p.topStripBg
    val border = if (enabled) p.accent.copy(alpha = 0.28f) else p.markdownDivider.copy(alpha = 0.5f)
    val textColor = if (enabled) p.accent else p.textMuted
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(999.dp))
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = t.spacing.sm, vertical = 4.dp),
    ) {
        Text(
            text = AuraCodeBundle.message(
                if (enabled) {
                    "settings.mcp.status.enabled"
                } else {
                    "settings.mcp.status.disabled"
                },
            ),
            color = textColor,
            style = MaterialTheme.typography.caption,
        )
    }
}
