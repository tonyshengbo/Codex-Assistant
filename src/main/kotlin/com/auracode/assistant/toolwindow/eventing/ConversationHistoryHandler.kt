package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.toolwindow.history.formatConversationExportMarkdown
import com.auracode.assistant.toolwindow.history.suggestConversationExportFileName
import com.auracode.assistant.toolwindow.shared.UiText

internal class ConversationHistoryHandler(
    private val context: ToolWindowCoordinatorContext,
    private val onResetPlanFlowState: () -> Unit,
) {
    fun loadHistoryConversations(reset: Boolean) {
        val sidePanelState = context.sidePanelStore.state.value
        if (sidePanelState.historyLoading) return
        if (!reset && sidePanelState.historyNextCursor == null) return
        context.eventHub.publish(
            AppEvent.HistoryConversationsUpdated(
                conversations = if (reset) emptyList() else sidePanelState.historyConversations,
                nextCursor = sidePanelState.historyNextCursor,
                isLoading = true,
                append = !reset,
            ),
        )
        context.coroutineLauncher.launch("loadHistoryConversations(reset=$reset)") {
            val page = context.chatService.loadRemoteConversationSummaries(
                limit = context.historyPageSize,
                cursor = if (reset) null else sidePanelState.historyNextCursor,
                searchTerm = sidePanelState.historyQuery.trim().takeIf { it.isNotBlank() },
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
        context.eventHub.publishUiIntent(UiIntent.CloseSidePanel)
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
                val summary = context.sidePanelStore.state.value.historyConversations.firstOrNull {
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
        context.dispatchSessionEvent(sessionId, AppEvent.ConversationReset)
        context.coroutineLauncher.launch("restoreCurrentSessionHistory") {
            val page = context.chatService.loadCurrentConversationReplay(limit = context.historyPageSize)
            context.restoreSessionHistory(sessionId, page, false)
        }
    }

    fun loadOlderMessages() {
        val sessionId = context.activeSessionId()
        val state = context.conversationStore.state.value
        if (!state.hasOlder || state.isLoadingOlder) return
        val beforeCursor = state.oldestCursor ?: return
        context.dispatchSessionEvent(sessionId, AppEvent.ConversationOlderLoadingChanged(loading = true))
        context.coroutineLauncher.launch("loadOlderMessages(cursor=$beforeCursor)") {
            val page = context.chatService.loadOlderConversationReplay(
                cursor = beforeCursor,
                limit = context.historyPageSize,
            )
            context.restoreSessionHistory(sessionId, page, true)
        }
    }
}
