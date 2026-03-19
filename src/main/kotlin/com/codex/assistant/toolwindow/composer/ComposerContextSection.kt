package com.codex.assistant.toolwindow.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.FileTypeIcon
import com.codex.assistant.toolwindow.shared.HoverTooltip
import com.codex.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun ContextEntryStrip(
    p: DesignPalette,
    state: ComposerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.72f), RoundedCornerShape(t.spacing.sm))
            .padding(horizontal = t.spacing.sm, vertical = 3.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HoverTooltip(text = CodexBundle.message("composer.addAttachment")) {
            IconButton(
                modifier = Modifier.size(26.dp),
                onClick = { onIntent(UiIntent.OpenAttachmentPicker) },
            ) {
                Icon(
                    painter = painterResource("/icons/attach-file.svg"),
                    contentDescription = CodexBundle.message("composer.addAttachment"),
                    tint = p.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.width(t.spacing.xs))
        state.contextEntries.forEach { entry ->
            ContextEntryChip(
                entry = entry,
                p = p,
                onRemove = { onIntent(UiIntent.RemoveContextFile(entry.path)) },
            )
            Spacer(Modifier.width(t.spacing.xs))
        }
        state.agentEntries.forEach { entry ->
            AgentEntryChip(
                entry = entry,
                p = p,
                onRemove = { onIntent(UiIntent.RemoveSelectedAgent(entry.id)) },
            )
            Spacer(Modifier.width(t.spacing.xs))
        }
    }
}

@Composable
private fun ContextEntryChip(
    entry: ContextEntry,
    p: DesignPalette,
    onRemove: () -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier.background(p.topStripBg, RoundedCornerShape(999.dp)).padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(fileName = entry.displayName, tint = p.textSecondary)
        Spacer(Modifier.width(t.spacing.xs))
        Text(
            text = entry.displayName,
            color = p.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(t.spacing.xs))
        HoverTooltip(text = CodexBundle.message("composer.removeFile")) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource("/icons/close.svg"),
                    contentDescription = CodexBundle.message("composer.removeFile"),
                    tint = p.textMuted,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

@Composable
private fun AgentEntryChip(
    entry: AgentContextEntry,
    p: DesignPalette,
    onRemove: () -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier.background(p.topStripBg, RoundedCornerShape(999.dp)).padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource("/icons/agent-settings.svg"),
            contentDescription = null,
            tint = p.textSecondary,
            modifier = Modifier.size(t.controls.iconMd),
        )
        Spacer(Modifier.width(t.spacing.xs))
        Text(
            text = entry.name,
            color = p.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(t.spacing.xs))
        HoverTooltip(text = CodexBundle.message("common.delete")) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource("/icons/close.svg"),
                    contentDescription = CodexBundle.message("common.delete"),
                    tint = p.textMuted,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
