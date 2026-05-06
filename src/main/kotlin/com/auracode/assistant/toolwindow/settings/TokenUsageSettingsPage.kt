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

/**
 * Renders the Token Usage settings page with engine tabs, range tabs, and all read-only sections.
 */
@Composable
internal fun TokenUsageSettingsPage(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val snapshot = state.tokenUsageStatsByScope[
        tokenUsageScopeKey(
            engineId = state.tokenUsageSettingsTab.engineId,
            range = state.tokenUsageRange,
        ),
    ]
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
            selectedIndex = if (state.tokenUsageSettingsTab == TokenUsageSettingsTab.CODEX) 0 else 1,
            onSelect = { index ->
                onIntent(
                    UiIntent.SelectTokenUsageSettingsTab(
                        if (index == 0) TokenUsageSettingsTab.CODEX else TokenUsageSettingsTab.CLAUDE,
                    ),
                )
            },
        )
        SettingsSegmentTabs(
            p = p,
            options = listOf(
                AuraCodeBundle.message("settings.usage.range.last7"),
                AuraCodeBundle.message("settings.usage.range.last30"),
                AuraCodeBundle.message("settings.usage.range.all"),
            ),
            selectedIndex = when (state.tokenUsageRange) {
                TokenUsageRange.LAST_7_DAYS -> 0
                TokenUsageRange.LAST_30_DAYS -> 1
                TokenUsageRange.ALL -> 2
            },
            onSelect = { index ->
                onIntent(
                    UiIntent.SelectTokenUsageRange(
                        when (index) {
                            1 -> TokenUsageRange.LAST_30_DAYS
                            2 -> TokenUsageRange.ALL
                            else -> TokenUsageRange.LAST_7_DAYS
                        },
                    ),
                )
            },
        )
        TokenUsageInfoBanner(
            p = p,
            text = AuraCodeBundle.message("settings.usage.scope.note"),
        )
        if (state.tokenUsageLastError != null) {
            SettingsInlineMessage(
                p = p,
                text = state.tokenUsageLastError,
                isError = true,
            )
        } else if (state.tokenUsageLoading && snapshot == null) {
            SettingsInlineMessage(
                p = p,
                text = AuraCodeBundle.message("settings.usage.loading"),
            )
        }
        TokenUsageHistoricalOverviewSection(
            p = p,
            snapshot = snapshot,
        )
        TokenUsageModelBreakdownSection(
            p = p,
            snapshot = snapshot,
        )
        TokenUsageDailyTrendSection(
            p = p,
            snapshot = snapshot,
        )
    }
}
