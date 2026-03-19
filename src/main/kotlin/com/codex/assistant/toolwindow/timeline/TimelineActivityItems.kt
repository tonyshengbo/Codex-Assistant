package com.codex.assistant.toolwindow.timeline

import androidx.compose.runtime.Composable
import com.codex.assistant.toolwindow.shared.DesignPalette

@Composable
internal fun TimelineToolCallItem(
    node: TimelineNode.ToolCallNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        body = node.body,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
    )
}

@Composable
internal fun TimelineCommandItem(
    node: TimelineNode.CommandNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        body = node.body,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
    )
}

@Composable
internal fun TimelineApprovalItem(
    node: TimelineNode.ApprovalNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        body = node.body,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
    )
}

@Composable
internal fun TimelinePlanItem(
    node: TimelineNode.PlanNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        body = node.body,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
    )
}

@Composable
internal fun TimelineUnknownActivityItem(
    node: TimelineNode.UnknownActivityNode,
    palette: DesignPalette,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    TimelineMarkdownActivityBody(
        title = node.title,
        body = node.body,
        status = node.status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
    )
}
