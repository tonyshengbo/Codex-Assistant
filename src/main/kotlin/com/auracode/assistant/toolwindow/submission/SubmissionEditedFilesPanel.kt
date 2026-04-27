package com.auracode.assistant.toolwindow.submission

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.FileTypeIcon
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun EditedFilesPanel(
    p: DesignPalette,
    state: SubmissionAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val uiModel = state.toEditedFilesPanelUiModel()
    AnimatedVisibility(
        visible = state.editedFilesExpanded && state.editedFiles.isNotEmpty(),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(p.topBarBg.copy(alpha = 0.96f), RoundedCornerShape(t.spacing.md))
                .border(1.dp, p.markdownDivider.copy(alpha = 0.48f), RoundedCornerShape(t.spacing.md))
                .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
        ) {
            EditedFilesHeader(p = p, uiModel = uiModel, onIntent = onIntent)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 224.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
            ) {
                if (uiModel.files.isEmpty()) {
                    Text(
                        text = AuraCodeBundle.message("composer.editedFiles.empty"),
                        color = p.textMuted,
                        style = androidx.compose.material.MaterialTheme.typography.caption,
                        modifier = Modifier.padding(horizontal = t.spacing.xs, vertical = t.spacing.sm),
                    )
                }
                uiModel.files.forEach { file ->
                    EditedFileRow(
                        item = file,
                        p = p,
                        onIntent = onIntent,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditedFilesHeader(
    p: DesignPalette,
    uiModel: EditedFilesPanelUiModel,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = AuraCodeBundle.message("composer.editedFiles.title"),
                color = p.textPrimary,
                fontWeight = FontWeight.SemiBold,
                style = androidx.compose.material.MaterialTheme.typography.body2,
            )
            Spacer(Modifier.size(t.spacing.xs))
            Text(
                text = AuraCodeBundle.message(
                    "composer.editedFiles.summary",
                    uiModel.summary.totalFiles.toString(),
                ),
                color = p.textMuted,
                style = androidx.compose.material.MaterialTheme.typography.caption,
            )
        }
        EditedFilesToolbarAction(
            iconPath = "/icons/check.svg",
            tooltip = AuraCodeBundle.message("composer.editedFiles.acceptAll"),
            p = p,
        ) { onIntent(UiIntent.AcceptAllEditedFiles) }
        Spacer(Modifier.width(t.spacing.xs))
        EditedFilesToolbarAction(
            iconPath = "/icons/undo.svg",
            tooltip = AuraCodeBundle.message("composer.editedFiles.revertAll"),
            p = p,
        ) { onIntent(UiIntent.RevertAllEditedFiles) }
        Spacer(Modifier.width(t.spacing.xs))
        EditedFilesToolbarAction(
            iconPath = "/icons/arrow-down.svg",
            tooltip = AuraCodeBundle.message("timeline.collapse"),
            p = p,
        ) { onIntent(UiIntent.ToggleEditedFilesExpanded) }
    }
}

@Composable
private fun EditedFileRow(
    item: EditedFileRowUiModel,
    p: DesignPalette,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (hovered) p.topStripBg.copy(alpha = 0.92f) else p.topStripBg.copy(alpha = 0.72f),
                RoundedCornerShape(t.spacing.sm),
            )
            .hoverable(interactionSource)
            .clickable { onIntent(UiIntent.OpenConversationFilePath(item.path)) }
            .padding(horizontal = t.spacing.sm, vertical = t.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        FileTypeIcon(fileName = item.displayName, modifier = Modifier.size(t.controls.iconMd))
        Column(modifier = Modifier.weight(1f)) {
            HoverTooltip(item.path) {
                Column {
                    Text(
                        text = item.displayName,
                        color = p.linkColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = androidx.compose.material.MaterialTheme.typography.body2,
                        fontWeight = FontWeight.Medium,
                    )
                    if (item.parentPath.isNotBlank()) {
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = item.parentPath,
                            color = p.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.material.MaterialTheme.typography.caption,
                        )
                    }
                }
            }
            Spacer(Modifier.size(2.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(t.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.latestAddedLines?.takeIf { it > 0 }?.let {
                    Text("+$it", color = p.success, style = androidx.compose.material.MaterialTheme.typography.caption)
                }
                item.latestDeletedLines?.takeIf { it > 0 }?.let {
                    Text("-$it", color = p.danger, style = androidx.compose.material.MaterialTheme.typography.caption)
                }
            }
        }
        EditedFilesToolbarAction(
            iconPath = "/icons/swap-horiz.svg",
            tooltip = AuraCodeBundle.message("composer.editedFiles.viewDiff"),
            p = p,
        ) { onIntent(UiIntent.OpenEditedFileDiff(item.path)) }
        Spacer(Modifier.width(t.spacing.xs))
        EditedFilesToolbarAction(
            iconPath = "/icons/undo.svg",
            tooltip = AuraCodeBundle.message("composer.editedFiles.revert"),
            p = p,
        ) { onIntent(UiIntent.RevertEditedFile(item.path)) }
    }
}

@Composable
private fun EditedFilesToolbarAction(
    iconPath: String,
    tooltip: String,
    p: DesignPalette,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    HoverTooltip(tooltip) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(p.appBg.copy(alpha = 0.4f), RoundedCornerShape(t.spacing.xs))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconPath),
                contentDescription = tooltip,
                tint = p.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
