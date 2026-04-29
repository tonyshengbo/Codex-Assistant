package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.settings.mcp.McpAuthState
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/** Maps auth state values to the existing localized labels used in the MCP list. */
internal fun McpAuthState.toMcpListStatusLabel(): String = when (this) {
    McpAuthState.UNSUPPORTED -> AuraCodeBundle.message("settings.mcp.auth.unsupported")
    McpAuthState.NOT_LOGGED_IN -> AuraCodeBundle.message("settings.mcp.auth.notLoggedIn")
    McpAuthState.BEARER_TOKEN -> AuraCodeBundle.message("settings.mcp.auth.bearerToken")
    McpAuthState.OAUTH -> AuraCodeBundle.message("settings.mcp.auth.oauth")
    McpAuthState.UNKNOWN -> AuraCodeBundle.message("settings.mcp.auth.unknown")
}

/** Renders the auth status badge inside the MCP server list row. */
@Composable
internal fun McpAuthStatusBadge(
    p: DesignPalette,
    authState: McpAuthState,
) {
    val t = assistantUiTokens()
    val (background, border, textColor) = when (authState) {
        McpAuthState.OAUTH,
        McpAuthState.BEARER_TOKEN,
        -> Triple(
            p.accent.copy(alpha = 0.12f),
            p.accent.copy(alpha = 0.28f),
            p.accent,
        )

        McpAuthState.NOT_LOGGED_IN -> Triple(
            p.danger.copy(alpha = 0.12f),
            p.danger.copy(alpha = 0.28f),
            p.danger,
        )

        McpAuthState.UNSUPPORTED,
        McpAuthState.UNKNOWN,
        -> Triple(
            p.topStripBg,
            p.markdownDivider.copy(alpha = 0.5f),
            p.textMuted,
        )
    }
    Box(
        modifier = Modifier
            .background(background, androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .border(
                1.dp,
                border,
                androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
            )
            .padding(horizontal = t.spacing.sm, vertical = 4.dp),
    ) {
        Text(
            text = authState.toMcpListStatusLabel(),
            color = textColor,
            style = MaterialTheme.typography.caption,
        )
    }
}
