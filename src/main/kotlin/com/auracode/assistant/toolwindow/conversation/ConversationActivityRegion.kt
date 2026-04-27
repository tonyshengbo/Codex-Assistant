package com.auracode.assistant.toolwindow.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.AttachmentPreviewOverlay
import com.auracode.assistant.toolwindow.shared.AttachmentPreviewPayload
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TIMELINE_QUICK_SCROLL_OVERLAY_DELAY_MS: Long = 900L
private const val TIMELINE_NEAR_BOTTOM_THRESHOLD_PX: Int = 40

internal data class ConversationQuickScrollVisibility(
    val showTop: Boolean,
    val showBottom: Boolean,
)

internal data class ConversationAutoFollowResolution(
    val autoFollowEnabled: Boolean,
    val hadProgrammaticScroll: Boolean,
)

internal data class ConversationBottomSnapshot(
    val totalItemsCount: Int,
    val lastVisibleItemIndex: Int?,
    val lastVisibleItemOffset: Int,
    val lastVisibleItemSize: Int,
    val viewportEndOffset: Int,
)

internal enum class ConversationBottomScrollStrategy {
    REVEAL_LAST_ITEM,
    ADJUST_VISIBLE_TAIL,
}

internal fun timelineBottomSnapshot(state: LazyListState): ConversationBottomSnapshot {
    val layoutInfo = state.layoutInfo
    val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
    return ConversationBottomSnapshot(
        totalItemsCount = layoutInfo.totalItemsCount,
        lastVisibleItemIndex = lastVisibleItem?.index,
        lastVisibleItemOffset = lastVisibleItem?.offset ?: 0,
        lastVisibleItemSize = lastVisibleItem?.size ?: 0,
        viewportEndOffset = layoutInfo.viewportEndOffset,
    )
}

internal fun conversationBottomOverflowPx(snapshot: ConversationBottomSnapshot): Int {
    val lastItemIndex = snapshot.totalItemsCount - 1
    if (lastItemIndex < 0 || snapshot.lastVisibleItemIndex != lastItemIndex) {
        return 0
    }
    val lastVisibleBottom = snapshot.lastVisibleItemOffset + snapshot.lastVisibleItemSize
    return (lastVisibleBottom - snapshot.viewportEndOffset).coerceAtLeast(0)
}

internal fun conversationIsNearBottom(
    snapshot: ConversationBottomSnapshot,
    thresholdPx: Int = TIMELINE_NEAR_BOTTOM_THRESHOLD_PX,
): Boolean {
    val lastItemIndex = snapshot.totalItemsCount - 1
    if (lastItemIndex <= 0) {
        return true
    }
    if (snapshot.lastVisibleItemIndex != lastItemIndex) {
        return false
    }
    return conversationBottomOverflowPx(snapshot) <= thresholdPx
}

internal fun conversationResolveAutoFollow(
    wasAutoFollowEnabled: Boolean,
    isScrollInProgress: Boolean,
    isNearBottom: Boolean,
    hadProgrammaticScroll: Boolean,
): ConversationAutoFollowResolution {
    if (isScrollInProgress) {
        return ConversationAutoFollowResolution(
            autoFollowEnabled = wasAutoFollowEnabled,
            hadProgrammaticScroll = hadProgrammaticScroll,
        )
    }
    if (hadProgrammaticScroll) {
        return ConversationAutoFollowResolution(
            autoFollowEnabled = wasAutoFollowEnabled,
            hadProgrammaticScroll = false,
        )
    }
    return ConversationAutoFollowResolution(
        autoFollowEnabled = isNearBottom,
        hadProgrammaticScroll = false,
    )
}

internal fun conversationHasPendingPromptScrollRequest(
    requestVersion: Long,
    handledVersion: Long,
): Boolean = requestVersion > handledVersion

internal fun conversationBottomScrollStrategy(
    snapshot: ConversationBottomSnapshot,
): ConversationBottomScrollStrategy {
    val lastItemIndex = snapshot.totalItemsCount - 1
    if (lastItemIndex < 0) {
        return ConversationBottomScrollStrategy.ADJUST_VISIBLE_TAIL
    }

    // When the last row is already visible, especially during streaming growth, avoid
    // restarting an index-based animation because it snaps the row back toward its top edge.
    return if (snapshot.lastVisibleItemIndex == lastItemIndex) {
        ConversationBottomScrollStrategy.ADJUST_VISIBLE_TAIL
    } else {
        ConversationBottomScrollStrategy.REVEAL_LAST_ITEM
    }
}

private suspend fun scrollTimelineToBottom(
    listState: LazyListState,
    rowCount: Int,
    animateReveal: Boolean,
) {
    val lastIndex = rowCount - 1
    if (lastIndex < 0) {
        return
    }

    when (conversationBottomScrollStrategy(timelineBottomSnapshot(listState))) {
        ConversationBottomScrollStrategy.REVEAL_LAST_ITEM -> {
            // Reveal the newest row only when it is currently out of view.
            if (animateReveal) {
                listState.animateScrollToItem(lastIndex)
            } else {
                listState.scrollToItem(lastIndex)
            }
        }

        ConversationBottomScrollStrategy.ADJUST_VISIBLE_TAIL -> {
            // Preserve the current anchor when the newest row is already visible so
            // streaming height growth only adds the missing tail pixels.
        }
    }

    // Compensate for the common streaming case where the last row grows taller than
    // the viewport and index-based positioning no longer reaches the true bottom.
    val overflowPx = conversationBottomOverflowPx(timelineBottomSnapshot(listState))
    if (overflowPx > 0) {
        listState.scrollBy(overflowPx.toFloat())
    }
}

internal fun conversationQuickScrollVisibility(
    overlayVisible: Boolean,
    canScrollToTop: Boolean,
    canScrollToBottom: Boolean,
): ConversationQuickScrollVisibility {
    if (!overlayVisible) {
        return ConversationQuickScrollVisibility(showTop = false, showBottom = false)
    }
    return ConversationQuickScrollVisibility(
        showTop = canScrollToTop,
        showBottom = canScrollToBottom,
    )
}

@Composable
internal fun ConversationActivityRegion(
    modifier: Modifier,
    p: DesignPalette,
    state: ConversationAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    var previewAttachment by remember { mutableStateOf<ConversationMessageAttachment?>(null) }
    var quickScrollOverlayVisible by remember { mutableStateOf(false) }
    var autoFollowEnabled by remember { mutableStateOf(true) }
    var hadProgrammaticScroll by remember { mutableStateOf(false) }
    var handledPromptScrollVersion by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val rowCount = state.nodes.size
    val nearTop = remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    val nearBottom = remember {
        derivedStateOf {
            conversationIsNearBottom(timelineBottomSnapshot(listState))
        }
    }
    val quickScrollVisibility = conversationQuickScrollVisibility(
        overlayVisible = quickScrollOverlayVisible,
        canScrollToTop = !nearTop.value,
        canScrollToBottom = !nearBottom.value,
    )

    LaunchedEffect(state.renderVersion) {
        when (state.renderCause) {
            ConversationRenderCause.HISTORY_PREPEND -> {
                val delta = state.prependedCount
                if (delta > 0) {
                    listState.scrollToItem(
                        index = (listState.firstVisibleItemIndex + delta).coerceAtLeast(0),
                        scrollOffset = listState.firstVisibleItemScrollOffset,
                    )
                }
            }

            ConversationRenderCause.HISTORY_RESET,
            ConversationRenderCause.LIVE_UPDATE,
            -> {
                if (autoFollowEnabled) {
                    hadProgrammaticScroll = true
                    scrollTimelineToBottom(
                        listState = listState,
                        rowCount = rowCount,
                        animateReveal = state.renderCause == ConversationRenderCause.HISTORY_RESET,
                    )
                }
            }

            ConversationRenderCause.IDLE -> Unit
        }
    }

    LaunchedEffect(state.promptScrollRequestVersion, state.renderVersion, rowCount) {
        if (!conversationHasPendingPromptScrollRequest(state.promptScrollRequestVersion, handledPromptScrollVersion)) {
            return@LaunchedEffect
        }
        if (rowCount <= 0) {
            return@LaunchedEffect
        }
        autoFollowEnabled = true
        hadProgrammaticScroll = true
        withFrameNanos { }
        scrollTimelineToBottom(
            listState = listState,
            rowCount = rowCount,
            animateReveal = false,
        )
        handledPromptScrollVersion = state.promptScrollRequestVersion
    }

    LaunchedEffect(listState.isScrollInProgress, nearTop.value, nearBottom.value) {
        if (listState.isScrollInProgress) {
            quickScrollOverlayVisible = !nearTop.value || !nearBottom.value
        } else if (quickScrollOverlayVisible) {
            delay(TIMELINE_QUICK_SCROLL_OVERLAY_DELAY_MS)
            if (!listState.isScrollInProgress) {
                quickScrollOverlayVisible = false
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        val resolution = conversationResolveAutoFollow(
            wasAutoFollowEnabled = autoFollowEnabled,
            isScrollInProgress = listState.isScrollInProgress,
            isNearBottom = nearBottom.value,
            hadProgrammaticScroll = hadProgrammaticScroll,
        )
        autoFollowEnabled = resolution.autoFollowEnabled
        hadProgrammaticScroll = resolution.hadProgrammaticScroll
    }

    AttachmentPreviewOverlay(
        palette = p,
        preview = previewAttachment?.let { attachment ->
            AttachmentPreviewPayload(
                assetPath = attachment.assetPath,
                isImage = attachment.kind == ConversationAttachmentKind.IMAGE,
            )
        },
        onDismiss = { previewAttachment = null },
    )

    Box(modifier = modifier.background(p.appBg)) {
        TimelineTextInteractionHost(palette = p) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = t.spacing.md, vertical = t.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
            ) {
                items(
                    items = state.nodes,
                    key = ConversationActivityItem::id,
                ) { node ->
                    when (node) {
                        is ConversationActivityItem.LoadMoreNode -> LoadOlderHistoryButton(
                            loading = node.isLoading,
                            p = p,
                            onClick = { onIntent(UiIntent.LoadOlderMessages) },
                        )

                        is ConversationActivityItem.MessageNode -> ConversationMessageItem(
                            node = node,
                            palette = p,
                            onPreviewAttachment = { previewAttachment = it },
                            onOpenMarkdownFilePath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.ReasoningNode -> ConversationReasoningItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenMarkdownFilePath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.FileChangeNode -> ConversationFileChangeItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenPath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.ToolCallNode -> ConversationToolCallItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenTitleTarget = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                            onOpenMarkdownFilePath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.CommandNode -> ConversationCommandItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenTitleTarget = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.ApprovalNode -> ConversationApprovalItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenMarkdownFilePath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.ContextCompactionNode -> ConversationContextCompactionItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenMarkdownFilePath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.PlanNode -> ConversationPlanItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenMarkdownFilePath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.UserInputNode -> ConversationUserInputItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenMarkdownFilePath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.UnknownActivityNode -> ConversationUnknownActivityItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenMarkdownFilePath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.ErrorNode -> ConversationErrorItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenMarkdownFilePath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )

                        is ConversationActivityItem.EngineSwitchedNode -> ConversationEngineSwitchItem(
                            node = node,
                            palette = p,
                            expanded = state.expandedNodeIds.contains(node.id),
                            onToggleExpanded = { onIntent(UiIntent.ToggleNodeExpanded(node.id)) },
                            onOpenMarkdownFilePath = { path -> onIntent(UiIntent.OpenConversationFilePath(path)) },
                        )
                    }
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

        AnimatedVisibility(
            visible = quickScrollVisibility.showTop || quickScrollVisibility.showBottom,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 18.dp, bottom = t.spacing.md),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
                horizontalAlignment = Alignment.End,
            ) {
                if (quickScrollVisibility.showTop) {
                    TimelineQuickScrollButton(
                        iconPath = "/icons/arrow-up.svg",
                        contentDescription = AuraCodeBundle.message("timeline.quickScroll.top"),
                        palette = p,
                        onClick = {
                            if (rowCount > 0) {
                                previewAttachment = null
                                quickScrollOverlayVisible = false
                                autoFollowEnabled = false
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        },
                    )
                }
                if (quickScrollVisibility.showBottom) {
                    TimelineQuickScrollButton(
                        iconPath = "/icons/arrow-down.svg",
                        contentDescription = AuraCodeBundle.message("timeline.quickScroll.bottom"),
                        palette = p,
                        onClick = {
                            if (rowCount > 0) {
                                previewAttachment = null
                                quickScrollOverlayVisible = false
                                autoFollowEnabled = true
                                scope.launch {
                                    hadProgrammaticScroll = true
                                    scrollTimelineToBottom(
                                        listState = listState,
                                        rowCount = rowCount,
                                        animateReveal = true,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineQuickScrollButton(
    iconPath: String,
    contentDescription: String,
    palette: DesignPalette,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    Box(
        modifier = Modifier
            .background(palette.topBarBg.copy(alpha = 0.96f), RoundedCornerShape(999.dp))
            .padding(0.dp),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .border(1.dp, palette.markdownDivider.copy(alpha = 0.72f), RoundedCornerShape(999.dp))
                .size(32.dp),
        ) {
            Icon(
                painter = painterResource(iconPath),
                contentDescription = contentDescription,
                tint = palette.textSecondary,
                modifier = Modifier.size(t.controls.iconMd),
            )
        }
    }
}
