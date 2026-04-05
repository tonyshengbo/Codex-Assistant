package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.toolwindow.drawer.formatConversationExportMarkdown
import com.auracode.assistant.toolwindow.drawer.suggestConversationExportFileName
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.timeline.TimelineNode
import com.auracode.assistant.toolwindow.timeline.TimelineNodeMapper
import com.auracode.assistant.toolwindow.timeline.TimelineNodeReducer

internal class ConversationHistoryHandler(
    private val context: ToolWindowCoordinatorContext,
    private val onResetPlanFlowState: () -> Unit,
) {
    fun loadHistoryConversations(reset: Boolean) {
        val drawerState = context.rightDrawerStore.state.value
        if (drawerState.historyLoading) return
        if (!reset && drawerState.historyNextCursor == null) return
        context.eventHub.publish(
            AppEvent.HistoryConversationsUpdated(
                conversations = if (reset) emptyList() else drawerState.historyConversations,
                nextCursor = drawerState.historyNextCursor,
                isLoading = true,
                append = !reset,
            ),
        )
        context.coroutineLauncher.launch("loadHistoryConversations(reset=$reset)") {
            val page = context.chatService.loadRemoteConversationSummaries(
                limit = context.historyPageSize,
                cursor = if (reset) null else drawerState.historyNextCursor,
                searchTerm = drawerState.historyQuery.trim().takeIf { it.isNotBlank() },
            )
            context.eventHub.publish(
                AppEvent.HistoryConversationsUpdated(
                    conversations = page.conversations,
                    nextCursor = page.nextCursor,
                    isLoading = false,
                    append = !reset,
                ),
            )
        }
    }

    fun openRemoteConversation(remoteConversationId: String, title: String) {
        context.chatService.openRemoteConversation(
            remoteConversationId = remoteConversationId,
            suggestedTitle = title,
        ) ?: return
        context.publishSessionSnapshot()
        restoreCurrentSessionHistory()
        context.eventHub.publishUiIntent(UiIntent.CloseRightDrawer)
        context.onSessionSnapshotPublished()
    }

    fun exportRemoteConversation(remoteConversationId: String, title: String) {
        val normalizedRemoteId = remoteConversationId.trim()
        if (normalizedRemoteId.isBlank()) return
        val suggestedFileName = suggestConversationExportFileName(title = title, remoteConversationId = normalizedRemoteId)
        val selectedPath = context.pickExportPath(suggestedFileName)?.trim().orEmpty()
        if (selectedPath.isBlank()) return
        context.coroutineLauncher.launch("exportRemoteConversation($normalizedRemoteId)") {
            runCatching {
                val history = context.chatService.loadFullRemoteConversationHistory(
                    remoteConversationId = normalizedRemoteId,
                    pageSize = context.historyPageSize.coerceAtLeast(1),
                )
                val summary = context.rightDrawerStore.state.value.historyConversations.firstOrNull {
                    it.remoteConversationId == normalizedRemoteId
                } ?: ConversationSummary(
                    remoteConversationId = normalizedRemoteId,
                    title = title,
                    createdAt = 0L,
                    updatedAt = 0L,
                    status = "idle",
                )
                val content = formatConversationExportMarkdown(summary = summary, events = history.events)
                context.writeExportFile(selectedPath, content)
                context.eventHub.publish(AppEvent.StatusTextUpdated(UiText.raw("Exported conversation to $selectedPath")))
            }.onFailure { error ->
                context.eventHub.publish(
                    AppEvent.StatusTextUpdated(UiText.raw(error.message ?: "Failed to export conversation.")),
                )
            }
        }
    }

    fun restoreCurrentSessionHistory() {
        val sessionId = context.activeSessionId()
        onResetPlanFlowState()
        context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.ConversationReset)
        context.coroutineLauncher.launch("restoreCurrentSessionHistory") {
            val page = context.chatService.loadCurrentConversationHistory(limit = context.historyPageSize)
            context.eventDispatcher.dispatchSessionEvent(
                sessionId,
                AppEvent.TimelineHistoryLoaded(
                    nodes = restoreNodes(page.events),
                    oldestCursor = page.olderCursor,
                    hasOlder = page.hasOlder,
                    prepend = false,
                ),
            )
        }
    }

    fun loadOlderMessages() {
        val sessionId = context.activeSessionId()
        val state = context.timelineStore.state.value
        if (!state.hasOlder || state.isLoadingOlder) return
        val beforeCursor = state.oldestCursor ?: return
        context.eventDispatcher.dispatchSessionEvent(sessionId, AppEvent.TimelineOlderLoadingChanged(loading = true))
        context.coroutineLauncher.launch("loadOlderMessages(cursor=$beforeCursor)") {
            val page = context.chatService.loadOlderConversationHistory(
                cursor = beforeCursor,
                limit = context.historyPageSize,
            )
            context.eventDispatcher.dispatchSessionEvent(
                sessionId,
                AppEvent.TimelineHistoryLoaded(
                    nodes = restoreNodes(page.events),
                    oldestCursor = page.olderCursor,
                    hasOlder = page.hasOlder,
                    prepend = true,
                ),
            )
        }
    }

    private fun restoreNodes(events: List<UnifiedEvent>): List<TimelineNode> {
        val reducer = TimelineNodeReducer()
        events.forEach { event ->
            TimelineNodeMapper.fromUnifiedEvent(event)?.let(reducer::accept)
        }
        return reducer.state.nodes.filterNot { it is TimelineNode.LoadMoreNode }
    }
}
