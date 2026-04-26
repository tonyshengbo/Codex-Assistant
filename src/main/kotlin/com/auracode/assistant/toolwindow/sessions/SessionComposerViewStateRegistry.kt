package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.toolwindow.submission.ComposerAreaState
import com.auracode.assistant.toolwindow.submission.ComposerAreaStore
import com.auracode.assistant.toolwindow.eventing.AppEvent

/**
 * Stores only per-session composer view state that is not reconstructed from kernel projections.
 */
internal class SessionComposerViewStateRegistry {
    private val stateBySessionId = linkedMapOf<String, ComposerAreaState>()

    /** Captures the currently visible composer state before the user switches away from a session. */
    fun capture(
        sessionId: String,
        state: ComposerAreaState,
    ) {
        if (sessionId.isBlank()) return
        stateBySessionId[sessionId] = state
    }

    /** Applies one session-scoped composer event while the target session is not currently visible. */
    fun applyEvent(
        sessionId: String,
        event: AppEvent,
    ) {
        if (sessionId.isBlank() || !event.affectsBackgroundComposerState()) return
        val store = ComposerAreaStore()
        store.restoreState(stateBySessionId[sessionId] ?: ComposerAreaState(currentSessionId = sessionId))
        store.onEvent(event)
        stateBySessionId[sessionId] = store.state.value
    }

    /** Restores one captured session view state onto the latest active composer snapshot. */
    fun restore(
        sessionId: String,
        baseState: ComposerAreaState,
    ): ComposerAreaState {
        val cachedState = stateBySessionId[sessionId] ?: return baseState
        return with(ComposerAreaStore()) {
            cachedState.restoreSessionViewOnto(baseState)
        }
    }

    /** Drops any remembered draft state for a session that was reset or deleted. */
    fun drop(sessionId: String) {
        stateBySessionId.remove(sessionId)
    }
}

/** Returns true when the event affects composer-local state that projections do not rebuild on session switch. */
private fun AppEvent.affectsBackgroundComposerState(): Boolean {
    return when (this) {
        is AppEvent.PendingSubmissionsUpdated,
        is AppEvent.TurnDiffUpdated,
        is AppEvent.PlanCompletionPromptUpdated,
        -> true

        is AppEvent.UnifiedEventPublished -> event is UnifiedEvent.SubagentsUpdated

        else -> false
    }
}
