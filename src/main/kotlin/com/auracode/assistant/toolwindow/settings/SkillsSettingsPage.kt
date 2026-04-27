package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.settings.skills.LocalSkillInstallPolicy
import com.auracode.assistant.settings.skills.ManagedSkillEntry
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun SkillsSettingsPage(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val uninstallPolicy = remember { LocalSkillInstallPolicy() }
    var pendingUninstallSkill by remember { mutableStateOf<ManagedSkillEntry?>(null) }
    var expandedMenuPath by remember { mutableStateOf<String?>(null) }
    val showInitialLoading = state.skillsLoading && !state.skillsHasLoadedSnapshot
    val showNeutralShell = !state.skillsHasLoadedSnapshot && !state.skillsLoading

    pendingUninstallSkill?.let { skill ->
        AlertDialog(
            onDismissRequest = { pendingUninstallSkill = null },
            title = { Text(AuraCodeBundle.message("settings.skills.uninstall.confirm.title")) },
            text = {
                Text(
                    AuraCodeBundle.message("settings.skills.uninstall.confirm.body", skill.name),
                    color = p.textSecondary,
                    style = MaterialTheme.typography.body2,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        expandedMenuPath = null
                        pendingUninstallSkill = null
                        onIntent(UiIntent.UninstallSkill(name = skill.name, path = skill.path))
                    },
                ) {
                    Text(AuraCodeBundle.message("settings.skills.uninstall"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstallSkill = null }) {
                    Text(AuraCodeBundle.message("common.cancel"))
                }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.md),
    ) {
        SkillsPageHeader(p = p, state = state, onIntent = onIntent)

        if (!state.skillsRuntimeSupported && state.skillsEngineId == "codex") {
            Text(
                text = AuraCodeBundle.message("settings.skills.runtime.unsupported"),
                color = p.textMuted,
                style = MaterialTheme.typography.body2,
            )
        } else if (showInitialLoading) {
            repeat(4) { SkillRowSkeleton(p = p) }
        } else if (showNeutralShell) {
            repeat(3) { SkillNeutralShellRow(p = p) }
        }

        if (state.skillsHasLoadedSnapshot && state.skills.isEmpty()) {
            Text(
                text = AuraCodeBundle.message("settings.skills.empty"),
                color = p.textMuted,
                style = MaterialTheme.typography.body2,
            )
        } else {
            state.skills.forEach { skill ->
                SkillRow(
                    p = p,
                    skill = skill,
                    busy = state.skillsActiveTogglePath == skill.path,
                    canUninstall = uninstallPolicy.canUninstall(skill.path),
                    menuExpanded = expandedMenuPath == skill.path,
                    onMenuExpandedChange = { expanded ->
                        expandedMenuPath = if (expanded) skill.path else null
                    },
                    onOpen = { onIntent(UiIntent.OpenSkillPath(skill.path)) },
                    onReveal = { onIntent(UiIntent.RevealSkillPath(skill.path)) },
                    onUninstall = {
                        expandedMenuPath = null
                        pendingUninstallSkill = skill
                    },
                    onToggle = { enabled ->
                        expandedMenuPath = null
                        onIntent(
                            UiIntent.ToggleSkillEnabled(
                                name = skill.name,
                                path = skill.path,
                                enabled = enabled,
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** Adapts page state into the standalone row card component. */
@Composable
private fun SkillRow(
    p: DesignPalette,
    skill: ManagedSkillEntry,
    busy: Boolean,
    canUninstall: Boolean,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
    onUninstall: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    SkillRowCard(
        p = p,
        skill = skill,
        busy = busy,
        canUninstall = canUninstall,
        menuExpanded = menuExpanded,
        onMenuExpandedChange = onMenuExpandedChange,
        onOpen = onOpen,
        onReveal = onReveal,
        onUninstall = onUninstall,
        onToggle = onToggle,
    )
}

/** Header row with title, subtitle, and Refresh action button. */
@Composable
private fun SkillsPageHeader(
    p: DesignPalette,
    state: SidePanelAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
        ) {
            Text(
                text = AuraCodeBundle.message("settings.skills.group.title"),
                color = p.textPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.body1,
            )
            Text(
                text = AuraCodeBundle.message("settings.skills.group.subtitle"),
                color = p.textMuted,
                style = MaterialTheme.typography.caption,
            )
        }
        IconButton(
            onClick = { onIntent(UiIntent.RefreshSkills) },
            enabled = !state.skillsLoading,
        ) {
            Icon(
                painter = painterResource("/icons/swap-vert.svg"),
                contentDescription = AuraCodeBundle.message("settings.skills.refresh"),
                tint = if (!state.skillsLoading) p.textSecondary else p.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SkillRowSkeleton(p: DesignPalette) {
    val t = assistantUiTokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(p.topBarBg.copy(alpha = 0.56f), RoundedCornerShape(t.spacing.md))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.35f), RoundedCornerShape(t.spacing.md))
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.52f)
                .height(14.dp)
                .background(p.topStripBg.copy(alpha = 0.92f), RoundedCornerShape(999.dp)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .height(12.dp)
                .background(p.topStripBg.copy(alpha = 0.74f), RoundedCornerShape(999.dp)),
        )
    }
}

@Composable
private fun SkillNeutralShellRow(p: DesignPalette) {
    val t = assistantUiTokens()
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(p.topBarBg.copy(alpha = 0.28f), RoundedCornerShape(t.spacing.md))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.22f), RoundedCornerShape(t.spacing.md)),
    )
}
