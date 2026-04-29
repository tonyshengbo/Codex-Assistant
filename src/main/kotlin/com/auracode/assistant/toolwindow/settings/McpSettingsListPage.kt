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
import com.auracode.assistant.settings.mcp.McpAuthState
import com.auracode.assistant.settings.mcp.McpServerSummary
import com.auracode.assistant.settings.mcp.McpTransportType
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
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
                        isAuthenticating = state.mcpBusyState.authenticatingName == server.name,
                        authState = state.mcpStatusByName[server.name]?.authState ?: server.authState,
                        onOpen = { onIntent(UiIntent.SelectMcpServerForEdit(server.name)) },
                        onToggleEnabled = { enabled ->
                            onIntent(UiIntent.ToggleMcpServerEnabled(server.name, enabled))
                        },
                        onAuthenticate = { login ->
                            onIntent(
                                if (login) {
                                    UiIntent.LoginMcpServer(server.name)
                                } else {
                                    UiIntent.LogoutMcpServer(server.name)
                                },
                            )
                        },
                        onCancelAuthentication = {
                            onIntent(UiIntent.CancelMcpLogin(server.name))
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
    isAuthenticating: Boolean,
    authState: McpAuthState,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onAuthenticate: (Boolean) -> Unit,
    onCancelAuthentication: () -> Unit,
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
                McpAuthStatusBadge(p = p, authState = authState)
            }
            Text(
                text = server.displayTarget,
                color = p.textSecondary,
                style = MaterialTheme.typography.caption,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsToggle(
                p = p,
                checked = server.enabled,
                enabled = !isUpdating && !isDeleting && !isAuthenticating,
                onCheckedChange = onToggleEnabled,
            )
            AuthActionButton(
                p = p,
                authState = authState,
                isAuthenticating = isAuthenticating,
                enabled = !isUpdating && !isDeleting,
                onAuthenticate = onAuthenticate,
                onCancelAuthentication = onCancelAuthentication,
            )
            SettingsActionButton(
                p = p,
                text = AuraCodeBundle.message("common.delete"),
                emphasized = false,
                enabled = !isDeleting && !isAuthenticating,
                onClick = onDelete,
            )
        }
    }
}

/** Renders the list-level login or logout action for the current MCP server. */
@Composable
private fun AuthActionButton(
    p: DesignPalette,
    authState: McpAuthState,
    isAuthenticating: Boolean,
    enabled: Boolean,
    onAuthenticate: (Boolean) -> Unit,
    onCancelAuthentication: () -> Unit,
) {
    if (isAuthenticating) {
        SettingsActionButton(
            p = p,
            text = AuraCodeBundle.message("common.cancel"),
            emphasized = false,
            enabled = enabled,
            onClick = onCancelAuthentication,
        )
        return
    }
    when (authState) {
        McpAuthState.NOT_LOGGED_IN -> {
            SettingsActionButton(
                p = p,
                text = AuraCodeBundle.message("settings.mcp.auth.login"),
                enabled = enabled,
                onClick = { onAuthenticate(true) },
            )
        }

        McpAuthState.OAUTH,
        McpAuthState.BEARER_TOKEN,
        -> {
            SettingsActionButton(
                p = p,
                text = AuraCodeBundle.message("settings.mcp.auth.logout"),
                emphasized = false,
                enabled = enabled,
                onClick = { onAuthenticate(false) },
            )
        }

        else -> Unit
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
