package com.auracode.assistant.session.kernel

import java.util.concurrent.ConcurrentHashMap

/**
 * Stores the latest runtime binding metadata for each active session.
 */
internal class SessionRuntimeRegistry {
    private val bindingsBySessionId = ConcurrentHashMap<String, SessionRuntimeBinding>()

    /** Updates the latest runtime binding for one session. */
    fun update(binding: SessionRuntimeBinding) {
        bindingsBySessionId[binding.sessionId] = binding
    }

    /** Returns the latest runtime binding for one session, if present. */
    fun binding(sessionId: String): SessionRuntimeBinding? {
        return bindingsBySessionId[sessionId]
    }

    /** Clears any runtime binding associated with one session. */
    fun clear(sessionId: String) {
        bindingsBySessionId.remove(sessionId)
    }
}

/**
 * Stores the runtime-facing thread and turn references for one session.
 */
internal data class SessionRuntimeBinding(
    val sessionId: String,
    val engineId: String,
    val threadId: String?,
    val turnId: String?,
    val runStatus: SessionRunStatus,
)
