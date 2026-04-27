package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
import com.auracode.assistant.toolwindow.settings.SettingsSection
import com.auracode.assistant.toolwindow.settings.presentation
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun SettingsOverlay(
    p: DesignPalette,
    state: SidePanelAreaState,
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
                .width(56.dp)
                .background(p.topBarBg.copy(alpha = 0.88f))
                .padding(vertical = t.spacing.md, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.BASIC,
                iconPath = "/icons/settings.svg",
                description = AuraCodeBundle.message("settings.section.basic"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.BASIC)) }
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.RUNTIME,
                iconPath = "/icons/runtime-terminal.svg",
                description = AuraCodeBundle.message("settings.section.runtime"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.RUNTIME)) }
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.AGENTS,
                iconPath = "/icons/agent-settings.svg",
                description = AuraCodeBundle.message("settings.section.agents"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.AGENTS)) }
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.SKILLS,
                iconPath = "/icons/document.svg",
                description = AuraCodeBundle.message("settings.section.skills"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.SKILLS)) }
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.MCP,
                iconPath = "/icons/mcp.svg",
                description = AuraCodeBundle.message("settings.section.mcp"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.MCP)) }
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.TOKEN_USAGE,
                iconPath = "/icons/usage.svg",
                description = AuraCodeBundle.message("settings.section.usage"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.TOKEN_USAGE)) }
            SettingsRailItem(
                p = p,
                selected = state.settingsSection == SettingsSection.ABOUT,
                iconPath = "/icons/about.svg",
                description = AuraCodeBundle.message("settings.section.about"),
            ) { onIntent(UiIntent.SelectSettingsSection(SettingsSection.ABOUT)) }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(p.markdownDivider.copy(alpha = 0.24f)),
        )

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
                    onClick = { onIntent(UiIntent.CloseSidePanel) },
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = t.spacing.lg, vertical = t.spacing.md),
            ) {
                PanelHeader(
                    p = p,
                    title = AuraCodeBundle.message(presentation.titleKey),
                    subtitle = AuraCodeBundle.message(presentation.subtitleKey),
                )
                Spacer(Modifier.height(t.spacing.lg))
                when (state.settingsSection) {
                    SettingsSection.BASIC -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            BasicSettingsPage(p = p, state = state, onIntent = onIntent)
                        }
                    }

                    SettingsSection.RUNTIME -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            RuntimeSettingsPage(p = p, state = state, onIntent = onIntent)
                        }
                    }

                    SettingsSection.AGENTS -> {
                        AgentSettingsListPage(p = p, state = state, onIntent = onIntent)
                    }

                    SettingsSection.SKILLS -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            SkillsSettingsPage(p = p, state = state, onIntent = onIntent)
                        }
                    }

                    SettingsSection.MCP -> {
                        McpSettingsListPage(p = p, state = state, onIntent = onIntent)
                    }

                    SettingsSection.TOKEN_USAGE -> PlaceholderSettingsPage(
                        p = p,
                        title = AuraCodeBundle.message("settings.placeholder.usage.title"),
                        body = AuraCodeBundle.message("settings.placeholder.usage.body"),
                    )
                    SettingsSection.ABOUT -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            AboutSettingsPage(p = p)
                        }
                    }
                }
            }
        }
    }

    if (state.isMcpEditorDialogVisible) {
        McpEditorDialog(
            p = p,
            state = state,
            onIntent = onIntent,
        )
    }

    if (state.isAgentEditorDialogVisible) {
        AgentEditorDialog(
            p = p,
            state = state,
            onIntent = onIntent,
        )
    }
}

@Composable
private fun McpEditorDialog(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Dialog(
        onDismissRequest = { onIntent(UiIntent.ShowMcpSettingsList) },
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
                .heightIn(max = 720.dp)
                .background(p.appBg, RoundedCornerShape(t.spacing.lg))
                .border(1.dp, p.markdownDivider.copy(alpha = 0.45f), RoundedCornerShape(t.spacing.lg))
                .padding(horizontal = t.spacing.lg, vertical = t.spacing.lg),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(t.spacing.md),
                ) {
                    PanelHeader(
                        p = p,
                        title = AuraCodeBundle.message("settings.mcp.editor.title"),
                        subtitle = AuraCodeBundle.message("settings.mcp.editor.subtitle"),
                    )
                    OverlayCloseButton(
                        p = p,
                        onClick = { onIntent(UiIntent.ShowMcpSettingsList) },
                    )
                }
                Spacer(Modifier.height(t.spacing.lg))
                Box(modifier = Modifier.weight(1f)) {
                    McpSettingsEditorPage(p = p, state = state, onIntent = onIntent)
                }
            }
        }
    }
}
