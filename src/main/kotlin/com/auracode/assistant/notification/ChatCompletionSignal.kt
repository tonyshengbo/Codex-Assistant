package com.auracode.assistant.notification

import com.auracode.assistant.protocol.TurnOutcome

/**
 * Represents a user-facing completion reminder payload.
 */
internal data class ChatCompletionSignal(
    val sessionId: String,
    val sessionTitle: String,
    val outcome: TurnOutcome,
)
