package com.auracode.assistant.session.kernel

/**
 * Describes the collaboration tool category stored in session state.
 */
internal enum class SessionCollabToolType {
    SPAWN,
    WAIT,
    SEND_INPUT,
    RESUME,
    CLOSE,
}

/**
 * Describes the current collaboration card phase stored in session state.
 */
internal enum class SessionCollabToolPhase {
    DISPATCHING,
    STARTING,
    RUNNING,
    WAITING_RESULT,
    COMPLETED,
    FAILED,
}

/**
 * Describes which protocol source currently owns the collaboration card title.
 */
internal enum class SessionCollabToolActionSource {
    PHASE,
    COMMAND,
    FAILURE,
}

/**
 * Stores one collaboration tool semantic snapshot in session state.
 */
internal data class SessionCollabToolSemantic(
    val collabToolType: SessionCollabToolType,
    val collabPhase: SessionCollabToolPhase,
    val actionSource: SessionCollabToolActionSource,
    val actionKey: String? = null,
    val actionText: String? = null,
    val receiverThreadId: String? = null,
    val failureMessage: String? = null,
)
