package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.toolwindow.conversation.ConversationAreaState
import com.auracode.assistant.toolwindow.conversation.ConversationAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationScrollSnapshot

/**
 * Stores session-local timeline UI affordances that are not part of session projections.
 */
internal class SessionConversationUiStateRegistry {
    private data class SessionConversationUiState(
        val expandedNodeIds: Set<String>,
        val scrollSnapshot: ConversationScrollSnapshot?,
    )

    private val stateBySessionId = linkedMapOf<String, SessionConversationUiState>()

    /** Captures the current timeline expansion snapshot before leaving the active session. */
    fun capture(
        sessionId: String,
        state: ConversationAreaState,
    ) {
        if (sessionId.isBlank()) return
        stateBySessionId[sessionId] = SessionConversationUiState(
            expandedNodeIds = state.expandedNodeIds,
            scrollSnapshot = state.scrollSnapshot,
        )
    }

    /** Restores saved expansion state after projection/history rehydrates the visible timeline. */
    fun restore(
        sessionId: String,
        store: ConversationAreaStore,
    ) {
        val snapshot = stateBySessionId[sessionId] ?: return
        store.restoreExpandedNodeIds(snapshot.expandedNodeIds)
        snapshot.scrollSnapshot?.let(store::requestScrollRestore)
    }

    /** Clears remembered UI state for a session that was deleted or reset. */
    fun drop(sessionId: String) {
        stateBySessionId.remove(sessionId)
    }
}
