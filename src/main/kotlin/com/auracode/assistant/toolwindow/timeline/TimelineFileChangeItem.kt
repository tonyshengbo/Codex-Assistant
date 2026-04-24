package com.auracode.assistant.toolwindow.timeline

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
internal fun TimelineFileChangeItem(
    node: TimelineNode.FileChangeNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenPath: (String) -> Unit,
) {
    val change = node.changes.singleOrNull()
    TimelineExpandableCard(
        title = node.title,
        titleTargetLabel = node.titleTargetLabel,
        titleTargetPath = node.titleTargetPath,
        collapsedSummary = node.collapsedSummary,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        onOpenTitleTarget = onOpenPath,
        expandedBodyMaxHeight = timelineFileChangeExpandedBodyMaxHeight(),
        cardBackground = palette.timelineCardBg.copy(alpha = 0.94f),
        bodyBackground = palette.topStripBg.copy(alpha = 0.48f),
    ) {
        change?.let {
            TimelineInlineDiff(
                diff = timelineInlineDiff(change = it),
                palette = palette,
                status = node.status,
            )
        }
    }
}

@Composable
private fun TimelineInlineDiff(
    diff: String,
    palette: DesignPalette,
    status: ItemStatus,
) {
    val t = assistantUiTokens()
    val selectionColors = rememberTimelineMarkdownSelectionColors(palette)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(filePatchBackground(palette), RoundedCornerShape(10.dp))
            .border(1.dp, filePatchBorder(palette), RoundedCornerShape(10.dp))
            .padding(vertical = 6.dp),
    ) {
        if (status == ItemStatus.FAILED) {
            TimelineSelectableText(selectionColors = selectionColors) {
                Text(
                    text = diff.ifBlank { "No diff preview available." },
                    color = palette.textSecondary,
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(horizontal = t.spacing.sm),
                )
            }
            return@Box
        }

        TimelineSelectableText(selectionColors = selectionColors) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                diff.lineSequence().forEach { line ->
                    val kind = timelineDiffLineKind(line)
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
                            fontWeight = if (kind == TimelineDiffLineKind.HUNK) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

internal fun timelineInlineDiff(change: TimelineFileChange): String {
    if (!change.unifiedDiff.isNullOrBlank()) {
        val parsed = TimelineFileChangePreview.parseTurnDiff(change.unifiedDiff)[change.path]
        return parsed?.unifiedDiff ?: change.unifiedDiff
    }

    val resolved = TimelineFileChangePreview.resolve(change)
    val oldContent = resolved.oldContent.orEmpty()
    val newContent = resolved.newContent.orEmpty()
    val oldHeader = when (change.kind) {
        TimelineFileChangeKind.CREATE -> "/dev/null"
        else -> "a/${change.path}"
    }
    val newHeader = when (change.kind) {
        TimelineFileChangeKind.DELETE -> "/dev/null"
        else -> "b/${change.path}"
    }
    val bodyLines = buildList {
        when (change.kind) {
            TimelineFileChangeKind.CREATE -> {
                newContent.lineSequence().forEach { add("+$it") }
            }
            TimelineFileChangeKind.DELETE -> {
                oldContent.lineSequence().forEach { add("-$it") }
            }
            TimelineFileChangeKind.UPDATE,
            TimelineFileChangeKind.UNKNOWN,
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
    kind: TimelineDiffLineKind,
    palette: DesignPalette,
): Color {
    return when (kind) {
        TimelineDiffLineKind.META -> palette.topBarBg.copy(alpha = 0.18f)
        TimelineDiffLineKind.HUNK -> palette.accent.copy(alpha = 0.09f)
        TimelineDiffLineKind.ADDITION -> palette.success.copy(alpha = 0.08f)
        TimelineDiffLineKind.DELETION -> palette.danger.copy(alpha = 0.08f)
        TimelineDiffLineKind.CONTEXT -> Color.Transparent
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
    kind: TimelineDiffLineKind,
    palette: DesignPalette,
): Color {
    return when (kind) {
        TimelineDiffLineKind.META -> palette.textSecondary
        TimelineDiffLineKind.HUNK -> palette.accent
        TimelineDiffLineKind.ADDITION -> palette.success
        TimelineDiffLineKind.DELETION -> palette.danger
        TimelineDiffLineKind.CONTEXT -> palette.timelinePlainText
    }
}

internal fun timelineFileChangeExpandedBodyMaxHeight() = 260.dp
