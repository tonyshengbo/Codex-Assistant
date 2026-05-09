package com.auracode.assistant.toolwindow.eventing

/**
 * Tracks sessions whose latest visible UI came from history replay, so
 * transient running indicators can be suppressed until a real live run resumes.
 */
internal class SessionHistoryTransientUiRegistry {
    private val suppressedSessionIds = linkedSetOf<String>()

    /** Marks one session as history-restored and eligible for transient UI suppression. */
    fun markHistoryRestored(sessionId: String) {
        if (sessionId.isBlank()) return
        suppressedSessionIds += sessionId
    }

    /** Returns true when one session should hide replayed transient running indicators. */
    fun shouldSuppress(sessionId: String): Boolean {
        return sessionId.isNotBlank() && sessionId in suppressedSessionIds
    }

    /** Drops remembered suppression state when the session is reset, deleted, or replaced. */
    fun drop(sessionId: String) {
        suppressedSessionIds.remove(sessionId)
    }
}
