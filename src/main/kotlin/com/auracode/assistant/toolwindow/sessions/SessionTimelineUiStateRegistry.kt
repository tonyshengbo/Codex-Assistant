package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.toolwindow.conversation.TimelineAreaState
import com.auracode.assistant.toolwindow.conversation.TimelineAreaStore

/**
 * Stores session-local timeline UI affordances that are not part of session projections.
 */
internal class SessionTimelineUiStateRegistry {
    private val expandedNodeIdsBySessionId = linkedMapOf<String, Set<String>>()

    /** Captures the current timeline expansion snapshot before leaving the active session. */
    fun capture(
        sessionId: String,
        state: TimelineAreaState,
    ) {
        if (sessionId.isBlank()) return
        expandedNodeIdsBySessionId[sessionId] = state.expandedNodeIds
    }

    /** Restores saved expansion state after projection/history rehydrates the visible timeline. */
    fun restore(
        sessionId: String,
        store: TimelineAreaStore,
    ) {
        val expandedNodeIds = expandedNodeIdsBySessionId[sessionId] ?: return
        store.restoreExpandedNodeIds(expandedNodeIds)
    }

    /** Clears remembered UI state for a session that was deleted or reset. */
    fun drop(sessionId: String) {
        expandedNodeIdsBySessionId.remove(sessionId)
    }
}
