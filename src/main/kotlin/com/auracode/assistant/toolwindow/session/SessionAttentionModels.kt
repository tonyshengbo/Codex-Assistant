package com.auracode.assistant.toolwindow.session

import com.auracode.assistant.protocol.TurnOutcome

/**
 * Captures the lightweight reminder state associated with a single session tab.
 */
internal data class SessionAttentionSnapshot(
    val hasUnreadCompletion: Boolean = false,
    val lastOutcome: TurnOutcome? = null,
    val lastCompletedAt: Long? = null,
)
