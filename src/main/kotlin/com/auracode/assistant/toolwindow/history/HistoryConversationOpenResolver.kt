package com.auracode.assistant.toolwindow.history

import com.auracode.assistant.service.AgentChatService

/** Resolves how a history conversation should be opened from the current session and tab state. */
internal class HistoryConversationOpenResolver {
    /** Computes the next open action without mutating any session or UI state. */
    fun resolve(
        remoteConversationId: String,
        providerId: String,
        activeSessionId: String,
        sessions: List<AgentChatService.SessionSummary>,
        openSessionTabIds: Set<String>,
    ): HistoryConversationOpenAction {
        val normalizedRemoteId = remoteConversationId.trim()
        val normalizedProviderId = providerId.trim()
        if (normalizedRemoteId.isBlank()) return HistoryConversationOpenAction.NoOp
        val openedSession = sessions.firstOrNull { session ->
            session.id in openSessionTabIds &&
                session.providerId == normalizedProviderId &&
                session.remoteConversationId.trim() == normalizedRemoteId
        }
        if (openedSession != null) {
            return HistoryConversationOpenAction.SwitchToExistingSession(openedSession.id)
        }
        val existingSession = sessions.firstOrNull { session ->
            session.providerId == normalizedProviderId &&
                session.remoteConversationId.trim() == normalizedRemoteId
        }
        if (existingSession != null) {
            return HistoryConversationOpenAction.SwitchToExistingSession(existingSession.id)
        }
        val activeSession = sessions.firstOrNull { it.id == activeSessionId }
        if (activeSession != null && activeSession.messageCount == 0 && activeSession.remoteConversationId.isBlank()) {
            return HistoryConversationOpenAction.ReuseActiveEmptySession
        }
        return HistoryConversationOpenAction.OpenInNewSessionTab
    }
}

/** Represents the resolved history-open action before any side effects are executed. */
internal sealed interface HistoryConversationOpenAction {
    data class SwitchToExistingSession(val sessionId: String) : HistoryConversationOpenAction
    data object ReuseActiveEmptySession : HistoryConversationOpenAction
    data object OpenInNewSessionTab : HistoryConversationOpenAction
    data object NoOp : HistoryConversationOpenAction
}
