package com.auracode.assistant.toolwindow.submission

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.FileTypeIcon
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun ContextEntryStrip(
    p: DesignPalette,
    state: ComposerAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val scrollState = rememberScrollState()
    val trailingReservedWidth = remember(state.editedFiles.isNotEmpty()) {
        resolveContextEntryStripTrailingReservedWidth(hasEditedFiles = state.editedFiles.isNotEmpty())
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.72f), RoundedCornerShape(t.spacing.sm))
            .padding(horizontal = t.spacing.sm, vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HoverTooltip(text = AuraCodeBundle.message("composer.addAttachment")) {
                IconButton(
                    modifier = Modifier.size(26.dp),
                    onClick = { onIntent(UiIntent.OpenAttachmentPicker) },
                ) {
                    Icon(
                        painter = painterResource("/icons/attach-file.svg"),
                        contentDescription = AuraCodeBundle.message("composer.addAttachment"),
                        tint = p.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Spacer(Modifier.width(t.spacing.xs))
            Box(
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = trailingReservedWidth)
                        .horizontalScroll(scrollState)
                        .mouseHorizontalDragScroll(scrollState),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                if (state.editedFiles.isNotEmpty()) {
                    Box(
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        EditedFilesEntry(
                            p = p,
                            summary = state.editedFilesSummary,
                            expanded = state.editedFilesExpanded,
                            onClick = { onIntent(UiIntent.ToggleEditedFilesExpanded) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reserves space so the fixed edited-files entry does not overlap the chip scroller.
 */
internal fun resolveContextEntryStripTrailingReservedWidth(
    hasEditedFiles: Boolean,
) = if (hasEditedFiles) 96.dp else 0.dp

@Composable
private fun EditedFilesEntry(
    p: DesignPalette,
    summary: EditedFilesSummary,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    HoverTooltip(text = AuraCodeBundle.message("composer.editedFiles.entry.tooltip")) {
        Row(
            modifier = Modifier
                .background(p.topStripBg, RoundedCornerShape(999.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource("/icons/document.svg"),
                contentDescription = AuraCodeBundle.message("composer.editedFiles.entry.tooltip"),
                tint = p.textSecondary,
                modifier = Modifier.size(t.controls.iconMd),
            )
            Spacer(Modifier.width(t.spacing.xs))
            Text(
                text = AuraCodeBundle.message(
                    "composer.editedFiles.entry",
                    summary.total.toString(),
                ),
                color = p.textSecondary,
                style = androidx.compose.material.MaterialTheme.typography.body2,
            )
            Spacer(Modifier.width(t.spacing.xs))
            Icon(
                painter = painterResource(if (expanded) "/icons/arrow-down.svg" else "/icons/arrow-up.svg"),
                contentDescription = null,
                tint = p.textMuted,
                modifier = Modifier.size(14.dp),
            )
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
        FileTypeIcon(fileName = entry.iconFileName(), tint = p.textSecondary)
        Spacer(Modifier.width(t.spacing.xs))
        Text(
            text = entry.displayName,
            color = p.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(t.spacing.xs))
        HoverTooltip(text = AuraCodeBundle.message("composer.removeFile")) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource("/icons/close.svg"),
                    contentDescription = AuraCodeBundle.message("composer.removeFile"),
                    tint = p.textMuted,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}

private fun ContextEntry.iconFileName(): String {
    return path.substringAfterLast('/').substringAfterLast('\\').ifBlank { displayName }
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
        HoverTooltip(text = AuraCodeBundle.message("common.delete")) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource("/icons/close.svg"),
                    contentDescription = AuraCodeBundle.message("common.delete"),
                    tint = p.textMuted,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
    }
}
