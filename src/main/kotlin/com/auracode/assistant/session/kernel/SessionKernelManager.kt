package com.auracode.assistant.session.kernel

import java.util.concurrent.ConcurrentHashMap

/**
 * Creates, reuses, and removes session kernels keyed by session id.
 */
internal class SessionKernelManager(
    private val reducer: SessionStateReducer = SessionStateReducer(),
    private val runtimeRegistry: SessionRuntimeRegistry = SessionRuntimeRegistry(),
) {
    private val kernelsBySessionId = ConcurrentHashMap<String, SessionKernel>()

    /** Returns an existing kernel or creates a new one for the target session. */
    fun getOrCreate(
        sessionId: String,
        engineId: String,
    ): SessionKernel {
        return kernelsBySessionId.computeIfAbsent(sessionId) {
            SessionKernel(
                sessionId = sessionId,
                engineId = engineId,
                reducer = reducer,
                runtimeRegistry = runtimeRegistry,
            )
        }
    }

    /** Returns an existing kernel when the session is already active. */
    fun get(sessionId: String): SessionKernel? {
        return kernelsBySessionId[sessionId]
    }

    /** Removes a kernel and clears any mirrored runtime binding. */
    fun remove(sessionId: String) {
        kernelsBySessionId.remove(sessionId)
        runtimeRegistry.clear(sessionId)
    }
}
