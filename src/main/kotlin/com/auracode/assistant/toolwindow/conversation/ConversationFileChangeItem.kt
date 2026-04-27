package com.auracode.assistant.toolwindow.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun ConversationFileChangeItem(
    node: ConversationActivityItem.FileChangeNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenPath: (String) -> Unit,
) {
    val change = node.changes.singleOrNull()
    ConversationExpandableCard(
        title = node.title,
        titleTargetLabel = node.titleTargetLabel,
        titleTargetPath = node.titleTargetPath,
        collapsedSummary = node.collapsedSummary,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        onOpenTitleTarget = onOpenPath,
        expandedBodyMaxHeight = conversationFileChangeExpandedBodyMaxHeight(),
        cardBackground = palette.timelineCardBg.copy(alpha = 0.94f),
        bodyBackground = palette.topStripBg.copy(alpha = 0.48f),
    ) {
        change?.let {
            ConversationInlineDiff(
                diff = conversationInlineDiff(change = it),
                palette = palette,
                status = node.status,
            )
        }
    }
}

@Composable
private fun ConversationInlineDiff(
    diff: String,
    palette: DesignPalette,
    status: ItemStatus,
) {
    val t = assistantUiTokens()
    val selectionColors = rememberConversationMarkdownSelectionColors(palette)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(filePatchBackground(palette), RoundedCornerShape(10.dp))
            .border(1.dp, filePatchBorder(palette), RoundedCornerShape(10.dp))
            .padding(vertical = 6.dp),
    ) {
        if (status == ItemStatus.FAILED) {
            ConversationSelectableText(selectionColors = selectionColors) {
                Text(
                    text = diff.ifBlank { "No diff preview available." },
                    color = palette.textSecondary,
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(horizontal = t.spacing.sm),
                )
            }
            return@Box
        }

        ConversationSelectableText(selectionColors = selectionColors) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                diff.lineSequence().forEach { line ->
                    val kind = conversationDiffLineKind(line)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(diffLineBackground(kind, palette))
                            .padding(horizontal = t.spacing.sm, vertical = 3.dp),
                    ) {
                        Text(
                            text = line.ifBlank { " " },
                            color = diffLineText(kind, palette),
                            style = MaterialTheme.typography.caption,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (kind == ConversationDiffLineKind.HUNK) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

internal fun conversationInlineDiff(change: ConversationFileChange): String {
    if (!change.unifiedDiff.isNullOrBlank()) {
        val parsed = ConversationFileChangePreview.parseTurnDiff(change.unifiedDiff)[change.path]
        return parsed?.unifiedDiff ?: change.unifiedDiff
    }

    val resolved = ConversationFileChangePreview.resolve(change)
    val oldContent = resolved.oldContent.orEmpty()
    val newContent = resolved.newContent.orEmpty()
    val oldHeader = when (change.kind) {
        ConversationFileChangeKind.CREATE -> "/dev/null"
        else -> "a/${change.path}"
    }
    val newHeader = when (change.kind) {
        ConversationFileChangeKind.DELETE -> "/dev/null"
        else -> "b/${change.path}"
    }
    val bodyLines = buildList {
        when (change.kind) {
            ConversationFileChangeKind.CREATE -> {
                newContent.lineSequence().forEach { add("+$it") }
            }
            ConversationFileChangeKind.DELETE -> {
                oldContent.lineSequence().forEach { add("-$it") }
            }
            ConversationFileChangeKind.UPDATE,
            ConversationFileChangeKind.UNKNOWN,
            -> {
                oldContent.lineSequence().forEach { add("-$it") }
                newContent.lineSequence().forEach { add("+$it") }
            }
        }
    }
    return buildList {
        add("--- $oldHeader")
        add("+++ $newHeader")
        add("@@")
        addAll(bodyLines)
    }.joinToString("\n")
}

private fun diffLineBackground(
    kind: ConversationDiffLineKind,
    palette: DesignPalette,
): Color {
    return when (kind) {
        ConversationDiffLineKind.META -> palette.topBarBg.copy(alpha = 0.18f)
        ConversationDiffLineKind.HUNK -> palette.accent.copy(alpha = 0.09f)
        ConversationDiffLineKind.ADDITION -> palette.success.copy(alpha = 0.08f)
        ConversationDiffLineKind.DELETION -> palette.danger.copy(alpha = 0.08f)
        ConversationDiffLineKind.CONTEXT -> Color.Transparent
    }
}

private fun filePatchBackground(
    palette: DesignPalette,
): Color {
    return palette.topBarBg.copy(alpha = 0.34f)
}

private fun filePatchBorder(
    palette: DesignPalette,
): Color {
    return palette.markdownDivider.copy(alpha = 0.2f)
}

private fun diffLineText(
    kind: ConversationDiffLineKind,
    palette: DesignPalette,
): Color {
    return when (kind) {
        ConversationDiffLineKind.META -> palette.textSecondary
        ConversationDiffLineKind.HUNK -> palette.accent
        ConversationDiffLineKind.ADDITION -> palette.success
        ConversationDiffLineKind.DELETION -> palette.danger
        ConversationDiffLineKind.CONTEXT -> palette.timelinePlainText
    }
}

internal fun conversationFileChangeExpandedBodyMaxHeight() = 260.dp
