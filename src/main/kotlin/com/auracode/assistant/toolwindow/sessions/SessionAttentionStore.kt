package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.protocol.TurnOutcome

/**
 * Stores per-session completion reminder state independently from chat persistence.
 */
internal class SessionAttentionStore {
    // Stores lightweight per-session attention metadata for background completion reminders.
    private val attentionBySessionId = linkedMapOf<String, SessionAttentionSnapshot>()

    /**
     * Marks a session as completed and unread so the UI can surface follow-up attention cues.
     */
    fun markCompleted(sessionId: String, outcome: TurnOutcome, completedAt: Long): Boolean {
        val normalizedSessionId = sessionId.trim()
        if (normalizedSessionId.isBlank()) return false
        attentionBySessionId[normalizedSessionId] = SessionAttentionSnapshot(
            hasUnreadCompletion = true,
            lastOutcome = outcome,
            lastCompletedAt = completedAt,
        )
        return true
    }

    /**
     * Clears the unread marker while preserving the latest completion metadata for diagnostics.
     */
    fun clear(sessionId: String): Boolean {
        val normalizedSessionId = sessionId.trim()
        val current = attentionBySessionId[normalizedSessionId] ?: return false
        if (!current.hasUnreadCompletion) return false
        attentionBySessionId[normalizedSessionId] = current.copy(hasUnreadCompletion = false)
        return true
    }

    /**
     * Removes all attention state for a session that no longer needs to be tracked.
     */
    fun drop(sessionId: String): Boolean {
        val normalizedSessionId = sessionId.trim()
        return attentionBySessionId.remove(normalizedSessionId) != null
    }

    /**
     * Returns the current reminder snapshot for a session, or an empty snapshot when none exists.
     */
    fun snapshot(sessionId: String): SessionAttentionSnapshot {
        return attentionBySessionId[sessionId.trim()] ?: SessionAttentionSnapshot()
    }

    /**
     * Exposes the unread session ids so header tabs can render completion markers efficiently.
     */
    fun unreadCompletionSessionIds(): Set<String> {
        return attentionBySessionId
            .filterValues { it.hasUnreadCompletion }
            .keys
            .toSet()
    }
}
