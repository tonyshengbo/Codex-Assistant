package com.auracode.assistant.toolwindow.conversation

import androidx.compose.runtime.Composable
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.DesignPalette

@Composable
internal fun ConversationReasoningItem(
    node: ConversationActivityItem.ReasoningNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    ConversationMarkdownActivityBody(
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
internal fun ConversationToolCallItem(
    node: ConversationActivityItem.ToolCallNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenTitleTarget: (String) -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    ConversationMarkdownActivityBody(
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
internal fun ConversationCommandItem(
    node: ConversationActivityItem.CommandNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenTitleTarget: (String) -> Unit,
) {
    ConversationExpandableCard(
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
        copyText = conversationActivityCopyText(node),
    ) {
        ConversationCommandExecutionPanel(
            commandText = node.commandText,
            outputText = node.outputText,
            palette = palette,
        )
    }
}

@Composable
internal fun ConversationApprovalItem(
    node: ConversationActivityItem.ApprovalNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    ConversationMarkdownActivityBody(
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
internal fun ConversationContextCompactionItem(
    node: ConversationActivityItem.ContextCompactionNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    ConversationMarkdownActivityBody(
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
internal fun ConversationPlanItem(
    node: ConversationActivityItem.PlanNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    ConversationMarkdownActivityBody(
        title = conversationPlanDisplayTitle(),
        headerBadge = conversationPlanBadgeText(node.body),
        body = node.body,
        collapsedSummary = conversationPlanCollapsedSummary(node.body),
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        expandedBodyMaxHeight = conversationPlanExpandedBodyMaxHeight(),
        accentColor = palette.accent,
        useBodyContainer = false,
        onOpenMarkdownFilePath = onOpenMarkdownFilePath,
    )
}

internal fun conversationPlanDisplayTitle(): String = "Plan"

internal fun conversationPlanBadgeText(body: String): String? {
    val count = body.lineSequence()
        .map { it.trim() }
        .count { it.startsWith("- [") }
    return count.takeIf { it > 0 }?.let { "$it steps" }
}

internal fun conversationPlanCollapsedSummary(body: String): String? {
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
internal fun ConversationUserInputItem(
    node: ConversationActivityItem.UserInputNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    ConversationMarkdownActivityBody(
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
internal fun ConversationUnknownActivityItem(
    node: ConversationActivityItem.UnknownActivityNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    ConversationMarkdownActivityBody(
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
internal fun ConversationErrorItem(
    node: ConversationActivityItem.ErrorNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    ConversationMarkdownActivityBody(
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

@Composable
internal fun ConversationEngineSwitchItem(
    node: ConversationActivityItem.EngineSwitchedNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenMarkdownFilePath: ((String) -> Unit)? = null,
) {
    ConversationMarkdownActivityBody(
        title = node.title,
        titleIconPath = node.iconPath,
        headerBadge = node.targetEngineLabel,
        body = node.body,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
        accentColor = palette.accent,
        onOpenMarkdownFilePath = onOpenMarkdownFilePath,
    )
}
