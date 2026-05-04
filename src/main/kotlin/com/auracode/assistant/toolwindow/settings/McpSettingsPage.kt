package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/** Renders the MCP settings surface with engine-specific tabs. */
@Composable
internal fun McpSettingsPage(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.lg),
    ) {
        SettingsSegmentTabs(
            p = p,
            options = listOf(
                AuraCodeBundle.message("settings.runtime.tab.codex"),
                AuraCodeBundle.message("settings.runtime.tab.claude"),
            ),
            selectedIndex = if (state.mcpSettingsTab == McpSettingsTab.CODEX) 0 else 1,
            onSelect = { index ->
                onIntent(
                    UiIntent.SelectMcpSettingsTab(
                        if (index == 0) McpSettingsTab.CODEX else McpSettingsTab.CLAUDE,
                    ),
                )
            },
        )
        McpSettingsListPage(p = p, state = state, onIntent = onIntent)
    }
}
