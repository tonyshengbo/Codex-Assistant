package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.toolwindow.submission.SubmissionAreaState
import com.auracode.assistant.toolwindow.submission.SubmissionAreaStore
import com.auracode.assistant.toolwindow.eventing.AppEvent

/**
 * Stores only per-session composer view state that is not reconstructed from kernel projections.
 */
internal class SessionSubmissionViewStateRegistry {
    private val stateBySessionId = linkedMapOf<String, SubmissionAreaState>()

    /** Captures the currently visible composer state before the user switches away from a session. */
    fun capture(
        sessionId: String,
        state: SubmissionAreaState,
    ) {
        if (sessionId.isBlank()) return
        stateBySessionId[sessionId] = state
    }

    /** Applies one session-scoped composer event while the target session is not currently visible. */
    fun applyEvent(
        sessionId: String,
        event: AppEvent,
    ) {
        if (sessionId.isBlank() || !event.affectsBackgroundSubmissionState()) return
        val store = SubmissionAreaStore()
        store.restoreState(stateBySessionId[sessionId] ?: SubmissionAreaState(currentSessionId = sessionId))
        store.onEvent(event)
        stateBySessionId[sessionId] = store.state.value
    }

    /** Restores one captured session view state onto the latest active composer snapshot. */
    fun restore(
        sessionId: String,
        baseState: SubmissionAreaState,
    ): SubmissionAreaState {
        val cachedState = stateBySessionId[sessionId] ?: return baseState
        return with(SubmissionAreaStore()) {
            cachedState.restoreSessionViewOnto(baseState)
        }
    }

    /** Drops any remembered draft state for a session that was reset or deleted. */
    fun drop(sessionId: String) {
        stateBySessionId.remove(sessionId)
    }
}

/** Returns true when the event affects composer-local state that projections do not rebuild on session switch. */
private fun AppEvent.affectsBackgroundSubmissionState(): Boolean {
    return when (this) {
        is AppEvent.PendingSubmissionsUpdated,
        is AppEvent.PlanCompletionPromptUpdated,
        is AppEvent.SessionNavigationUiProjectionUpdated,
        -> true

        else -> false
    }
}
