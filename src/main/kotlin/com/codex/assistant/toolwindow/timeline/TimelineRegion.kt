package com.codex.assistant.toolwindow.timeline

import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.assistantUiTokens
import java.io.FileInputStream

@Composable
internal fun TimelineRegion(
    modifier: Modifier,
    p: DesignPalette,
    state: TimelineAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    var previewAttachment by remember { mutableStateOf<TimelineMessageAttachment?>(null) }
    val listState = rememberLazyListState()
    val rowCount = state.nodes.size
    val nearBottom = remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total <= 1) {
                true
            } else {
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }
    }

    LaunchedEffect(state.renderVersion) {
        when (state.renderCause) {
            TimelineRenderCause.HISTORY_PREPEND -> {
                val delta = state.prependedCount
                if (delta > 0) {
                    listState.scrollToItem(
                        index = (listState.firstVisibleItemIndex + delta).coerceAtLeast(0),
                        scrollOffset = listState.firstVisibleItemScrollOffset,
                    )
                }
            }

            TimelineRenderCause.HISTORY_RESET,
            TimelineRenderCause.LIVE_UPDATE,
            -> {
                val lastIndex = rowCount - 1
                if (lastIndex >= 0 && nearBottom.value) {
                    listState.animateScrollToItem(lastIndex)
                }
            }

            TimelineRenderCause.IDLE -> Unit
        }
    }

    TimelineAttachmentPreviewDialog(
        attachment = previewAttachment,
        palette = p,
        onDismiss = { previewAttachment = null },
    )

    Box(modifier = modifier.background(p.appBg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = t.spacing.md, vertical = t.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
        ) {
            items(
                items = state.nodes,
                key = TimelineNode::id,
            ) { node ->
                when (node) {
                    is TimelineNode.LoadMoreNode -> LoadOlderHistoryButton(
                        loading = node.isLoading,
                        p = p,
                        onClick = { onIntent(UiIntent.LoadOlderMessages) },
                    )

                    is TimelineNode.MessageNode -> TimelineMessageItem(
                        node = node,
                        palette = p,
                        onPreviewAttachment = { previewAttachment = it },
                    )

                    is TimelineNode.FileChangeNode -> TimelineFileChangeItem(
                        node = node,
                        palette = p,
                        expanded = state.expandedNodeIds.contains(node.id),
                        onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                        onOpenChange = { change -> onIntent(UiIntent.OpenTimelineFileChange(change)) },
                    )

                    is TimelineNode.ToolCallNode -> TimelineToolCallItem(
                        node = node,
                        palette = p,
                        expanded = state.expandedNodeIds.contains(node.id),
                        onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                    )

                    is TimelineNode.CommandNode -> TimelineCommandItem(
                        node = node,
                        palette = p,
                        expanded = state.expandedNodeIds.contains(node.id),
                        onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                    )

                    is TimelineNode.ApprovalNode -> TimelineApprovalItem(
                        node = node,
                        palette = p,
                        expanded = state.expandedNodeIds.contains(node.id),
                        onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                    )

                    is TimelineNode.PlanNode -> TimelinePlanItem(
                        node = node,
                        palette = p,
                        expanded = state.expandedNodeIds.contains(node.id),
                        onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                    )

                    is TimelineNode.UnknownActivityNode -> TimelineUnknownActivityItem(
                        node = node,
                        palette = p,
                        expanded = state.expandedNodeIds.contains(node.id),
                        onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                    )
                }
            }
        }

        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState = listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(top = t.spacing.sm, bottom = t.spacing.sm, end = 2.dp),
        )
    }
}

@Composable
private fun TimelineAttachmentPreviewDialog(
    attachment: TimelineMessageAttachment?,
    palette: DesignPalette,
    onDismiss: () -> Unit,
) {
    val t = assistantUiTokens()
    attachment ?: return
    val bitmap = rememberTimelineAttachmentBitmap(attachment.assetPath)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(attachment.displayName) },
        text = {
            Box(
                modifier = Modifier
                    .size(420.dp)
                    .background(palette.topBarBg, RoundedCornerShape(t.spacing.sm)),
                contentAlignment = Alignment.Center,
            ) {
                if (attachment.kind == TimelineAttachmentKind.IMAGE && bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(400.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Icon(
                        painter = painterResource("/icons/attach-file.svg"),
                        contentDescription = null,
                        tint = palette.textMuted,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(CodexBundle.message("common.close"))
            }
        },
    )
}

@Composable
internal fun rememberTimelineAttachmentBitmap(path: String): ImageBitmap? {
    return remember(path) {
        runCatching {
            FileInputStream(path).use { loadImageBitmap(it) }
        }.getOrNull()
    }
}
