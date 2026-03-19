package com.codex.assistant.toolwindow.drawer.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.toolwindow.drawer.AgentSettingsPage
import com.codex.assistant.toolwindow.drawer.RightDrawerAreaState
import com.codex.assistant.toolwindow.drawer.SettingsSection
import com.codex.assistant.toolwindow.drawer.presentation
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun SettingsOverlay(
    p: DesignPalette,
    state: RightDrawerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val presentation = state.settingsSection.presentation()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(p.appBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(68.dp)
                .background(p.topBarBg.copy(alpha = 0.88f))
                .border(1.dp, p.markdownDivider.copy(alpha = 0.42f))
                .padding(vertical = t.spacing.md, horizontal = t.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.GENERAL,
                iconPath = "/icons/settings.svg",
                description = CodexBundle.message("settings.section.general"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.GENERAL)) }
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.AGENTS,
                iconPath = "/icons/agent-settings.svg",
                description = CodexBundle.message("settings.section.agents"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.AGENTS)) }
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.TOKEN_USAGE,
                iconPath = "/icons/usage.svg",
                description = CodexBundle.message("settings.section.usage"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.TOKEN_USAGE)) }
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.MODEL_PROVIDERS,
                iconPath = "/icons/providers.svg",
                description = CodexBundle.message("settings.section.providers"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.MODEL_PROVIDERS)) }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(p.appBg)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = t.spacing.md, end = t.spacing.md),
            ) {
                OverlayCloseButton(
                    p = p,
                    onClick = { onIntent(UiIntent.CloseRightDrawer) },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = t.spacing.lg, vertical = t.spacing.md),
            ) {
                DrawerHeader(
                    p = p,
                    title = CodexBundle.message(presentation.titleKey),
                    subtitle = CodexBundle.message(presentation.subtitleKey),
                )
                Spacer(Modifier.height(t.spacing.lg))
                when (state.settingsSection) {
                    SettingsSection.GENERAL -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            GeneralSettingsPage(p = p, state = state, onIntent = onIntent)
                        }
                    }

                    SettingsSection.AGENTS -> {
                        if (state.agentSettingsPage == AgentSettingsPage.LIST) {
                            AgentSettingsListPage(p = p, state = state, onIntent = onIntent)
                        } else {
                            AgentSettingsEditorPage(p = p, state = state, onIntent = onIntent)
                        }
                    }

                    SettingsSection.TOKEN_USAGE -> PlaceholderSettingsPage(
                        p = p,
                        title = CodexBundle.message("settings.placeholder.usage.title"),
                        body = CodexBundle.message("settings.placeholder.usage.body"),
                    )

                    SettingsSection.MODEL_PROVIDERS -> PlaceholderSettingsPage(
                        p = p,
                        title = CodexBundle.message("settings.placeholder.providers.title"),
                        body = CodexBundle.message("settings.placeholder.providers.body"),
                    )
                }
            }
        }
    }
}
