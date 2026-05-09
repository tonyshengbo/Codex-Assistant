package com.auracode.assistant.protocol

/**
 * Describes the collaboration tool category emitted by provider protocol items.
 */
enum class ProviderCollabToolType {
    SPAWN,
    WAIT,
    SEND_INPUT,
    RESUME,
    CLOSE,
}

/**
 * Describes the parent collaboration card phase derived from the provider protocol.
 */
enum class ProviderCollabToolPhase {
    DISPATCHING,
    STARTING,
    RUNNING,
    WAITING_RESULT,
    COMPLETED,
    FAILED,
}

/**
 * Describes which protocol source produced the current collaboration card title.
 */
enum class ProviderCollabToolActionSource {
    PHASE,
    COMMAND,
    FAILURE,
}

/**
 * Stores provider-level collaboration semantics for one timeline tool card.
 */
data class ProviderCollabToolSemantic(
    val collabToolType: ProviderCollabToolType,
    val collabPhase: ProviderCollabToolPhase,
    val actionSource: ProviderCollabToolActionSource,
    val actionKey: String? = null,
    val actionText: String? = null,
    val receiverThreadId: String? = null,
    val failureMessage: String? = null,
)
