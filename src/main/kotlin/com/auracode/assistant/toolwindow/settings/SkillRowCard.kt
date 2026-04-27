package com.auracode.assistant.toolwindow.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.settings.skills.ManagedSkillEntry
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/** Renders one compact skill card with title, scope chips, path, actions, and toggle. */
@Composable
internal fun SkillRowCard(
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
    val t = assistantUiTokens()
    val presentation = skill.toSkillRowPresentation(canUninstall = canUninstall)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(p.topBarBg.copy(alpha = 0.72f), RoundedCornerShape(t.spacing.md))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.42f), RoundedCornerShape(t.spacing.md))
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
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
                    text = presentation.title,
                    color = p.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.body1,
                )
                presentation.chips.forEach { chip ->
                    SkillScopeChip(label = chip, p = p)
                }
            }
            SkillPathText(
                p = p,
                path = presentation.secondaryText,
            )
        }
        SkillRowActionsMenu(
            p = p,
            canUninstall = canUninstall,
            expanded = menuExpanded,
            enabled = !busy,
            onExpandedChange = onMenuExpandedChange,
            onOpen = onOpen,
            onReveal = onReveal,
            onUninstall = onUninstall,
        )
        SettingsToggle(
            p = p,
            checked = skill.enabled,
            enabled = !busy,
            onCheckedChange = onToggle,
        )
    }
}

/** Renders the skill path in a single line and exposes the full value via hover tooltip. */
@Composable
internal fun SkillPathText(
    p: DesignPalette,
    path: String,
) {
    HoverTooltip(text = path) {
        Text(
            text = path,
            color = p.textSecondary,
            style = MaterialTheme.typography.body2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small chip showing one scope/source label (for example "local" or "built-in"). */
@Composable
internal fun SkillScopeChip(
    label: String,
    p: DesignPalette,
) {
    Text(
        text = label,
        color = p.textMuted,
        fontSize = 10.sp,
        modifier = Modifier
            .background(p.topStripBg.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Renders the overflow actions menu for one skill row. */
@Composable
internal fun SkillRowActionsMenu(
    p: DesignPalette,
    canUninstall: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onReveal: () -> Unit,
    onUninstall: () -> Unit,
) {
    Box(modifier = Modifier.wrapContentSize(Alignment.TopStart)) {
        IconButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled,
        ) {
            Icon(
                painter = painterResource("/icons/more-vert.svg"),
                contentDescription = AuraCodeBundle.message("common.more"),
                tint = if (enabled) p.textSecondary else p.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            DropdownMenuItem(
                onClick = {
                    onExpandedChange(false)
                    onOpen()
                },
            ) {
                Text(AuraCodeBundle.message("settings.skills.open"))
            }
            DropdownMenuItem(
                onClick = {
                    onExpandedChange(false)
                    onReveal()
                },
            ) {
                Text(AuraCodeBundle.message("settings.skills.reveal"))
            }
            if (canUninstall) {
                DropdownMenuItem(
                    onClick = {
                        onExpandedChange(false)
                        onUninstall()
                    },
                ) {
                    Text(AuraCodeBundle.message("settings.skills.uninstall"))
                }
            }
        }
    }
}
