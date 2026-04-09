package com.auracode.assistant.toolwindow.timeline

import androidx.compose.runtime.Composable
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.DesignPalette

@Composable
internal fun TimelineReasoningItem(
    node: TimelineNode.ReasoningNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    TimelineMarkdownActivityBody(
        title = AuraCodeBundle.message("timeline.reasoning"),
        body = node.body,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        onOpenMarkdownFilePath = onOpenMarkdownFilePath,
    )
}

@Composable
internal fun TimelineToolCallItem(
    node: TimelineNode.ToolCallNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenTitleTarget: (String) -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        titleTargetLabel = node.titleTargetLabel,
        titleTargetPath = node.titleTargetPath,
        body = node.body,
        collapsedSummary = node.collapsedSummary,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        onOpenTitleTarget = onOpenTitleTarget,
        onOpenMarkdownFilePath = onOpenMarkdownFilePath,
    )
}

@Composable
internal fun TimelineCommandItem(
    node: TimelineNode.CommandNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenTitleTarget: (String) -> Unit,
) {
    TimelineExpandableCard(
        title = node.title,
        titleTargetLabel = node.titleTargetLabel,
        titleTargetPath = node.titleTargetPath,
        collapsedSummary = node.collapsedSummary,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        onOpenTitleTarget = onOpenTitleTarget,
        // Command nodes own their internal terminal scrolling so streaming output can
        // auto-follow without competing against the outer timeline activity container.
        expandedBodyMaxHeight = null,
        bodyBackground = palette.topStripBg.copy(alpha = 0.24f),
        copyText = timelineNodeCopyText(node),
    ) {
        TimelineCommandExecutionPanel(
            commandText = node.commandText,
            outputText = node.outputText,
            palette = palette,
        )
    }
}

@Composable
internal fun TimelineApprovalItem(
    node: TimelineNode.ApprovalNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        body = node.body,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        onOpenMarkdownFilePath = onOpenMarkdownFilePath,
    )
}

@Composable
internal fun TimelineContextCompactionItem(
    node: TimelineNode.ContextCompactionNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        body = node.body,
        collapsedSummary = null,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        onOpenMarkdownFilePath = onOpenMarkdownFilePath,
    )
}

@Composable
internal fun TimelinePlanItem(
    node: TimelineNode.PlanNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    TimelineMarkdownActivityBody(
        title = timelinePlanDisplayTitle(),
        headerBadge = timelinePlanBadgeText(node.body),
        body = node.body,
        collapsedSummary = timelinePlanCollapsedSummary(node.body),
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        expandedBodyMaxHeight = timelinePlanExpandedBodyMaxHeight(),
        accentColor = palette.accent,
        useBodyContainer = false,
        onOpenMarkdownFilePath = onOpenMarkdownFilePath,
    )
}

internal fun timelinePlanDisplayTitle(): String = "Plan"

internal fun timelinePlanBadgeText(body: String): String? {
    val count = body.lineSequence()
        .map { it.trim() }
        .count { it.startsWith("- [") }
    return count.takeIf { it > 0 }?.let { "$it steps" }
}

internal fun timelinePlanCollapsedSummary(body: String): String? {
    val lines = body.lineSequence().map { it.trim() }.toList()
    if (lines.isEmpty()) return null

    lines.firstMeaningfulChecklistItem()?.let { return it }
    lines.summarySectionLine()?.let { return it }
    return lines.firstMeaningfulContentLine()
}

private fun List<String>.firstMeaningfulChecklistItem(): String? = firstNotNullOfOrNull { line ->
    CHECKLIST_LINE.matchEntire(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
}

private fun List<String>.summarySectionLine(): String? {
    val index = indexOfFirst { it.equals("summary", ignoreCase = true) || it.startsWith("summary:", ignoreCase = true) }
    if (index == -1) return null

    val inline = get(index)
        .substringAfter(':', "")
        .trim()
        .takeIf { it.isNotEmpty() }
    if (inline != null) return inline

    return drop(index + 1).firstMeaningfulContentLine()
}

private fun List<String>.firstMeaningfulContentLine(): String? = firstNotNullOfOrNull { line ->
    line
        .takeIf { it.isNotBlank() }
        ?.takeUnless { it.startsWith("#") }
        ?.removeMarkdownListPrefix()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private fun String.removeMarkdownListPrefix(): String = replaceFirst(PLAIN_LIST_PREFIX, "").trim()

private val CHECKLIST_LINE = Regex("""[-*]\s+\[[^\]]*]\s+(.*)""")
private val PLAIN_LIST_PREFIX = Regex("""^[-*]\s+""")

@Composable
internal fun TimelineUserInputItem(
    node: TimelineNode.UserInputNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        body = node.body,
        collapsedSummary = node.collapsedSummary,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        onOpenMarkdownFilePath = onOpenMarkdownFilePath,
    )
}

@Composable
internal fun TimelineUnknownActivityItem(
    node: TimelineNode.UnknownActivityNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        body = node.body,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        onOpenMarkdownFilePath = onOpenMarkdownFilePath,
    )
}

@Composable
internal fun TimelineErrorItem(
    node: TimelineNode.ErrorNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        body = node.body,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        accentColor = palette.danger,
        onOpenMarkdownFilePath = onOpenMarkdownFilePath,
    )
}
