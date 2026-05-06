package com.auracode.assistant.persistence.chat

import java.util.UUID

/**
 * Stores one persisted usage snapshot entry captured for a session.
 */
internal data class PersistedSessionUsageLedgerEntry(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val providerId: String,
    val model: String,
    val contextWindow: Int,
    val inputTokens: Int,
    val cachedInputTokens: Int,
    val outputTokens: Int,
    val capturedAt: Long,
    val sourceTurnId: String?,
    val isBaseline: Boolean,
)
