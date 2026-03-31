package com.auracode.assistant.service

import kotlinx.coroutines.Job

/**
 * Stores live run ownership per chat session so tabs can execute independently.
 */
internal class SessionRunRegistry {
    private val runsBySessionId = linkedMapOf<String, SessionRunContext>()

    fun ensureSession(sessionId: String) {
        runsBySessionId.getOrPut(sessionId) { SessionRunContext() }
    }

    fun removeSession(sessionId: String): SessionRunContext? {
        return runsBySessionId.remove(sessionId)
    }

    fun retainSessions(sessionIds: Set<String>) {
        runsBySessionId.keys.removeIf { it !in sessionIds }
        sessionIds.forEach(::ensureSession)
    }

    fun replaceRun(
        sessionId: String,
        requestId: String,
        job: Job,
    ): SessionRunContext? {
        val previous = runsBySessionId.put(
            sessionId,
            SessionRunContext(
                requestId = requestId,
                job = job,
            ),
        )
        return previous
    }

    fun clearRun(
        sessionId: String,
        requestId: String? = null,
    ): SessionRunContext? {
        val current = runsBySessionId[sessionId] ?: return null
        if (requestId != null && current.requestId != requestId) {
            return null
        }
        runsBySessionId[sessionId] = SessionRunContext()
        return current
    }

    fun requestId(sessionId: String): String? = runsBySessionId[sessionId]?.requestId

    fun isRunning(sessionId: String): Boolean = runsBySessionId[sessionId]?.isRunning == true
}

internal data class SessionRunContext(
    val requestId: String? = null,
    val job: Job? = null,
) {
    val isRunning: Boolean
        get() = requestId != null && job != null
}
