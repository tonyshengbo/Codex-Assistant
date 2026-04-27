package com.auracode.assistant.protocol

/**
 * Normalized collaboration status for engine-specific subagent activity.
 */
enum class ProviderAgentStatus {
    ACTIVE,
    IDLE,
    PENDING,
    FAILED,
    COMPLETED,
    UNKNOWN,
}

/**
 * Describes one subagent that belongs to the current main session.
 */
data class ProviderAgentSnapshot(
    val threadId: String,
    val displayName: String,
    val mentionSlug: String,
    val status: ProviderAgentStatus,
    val statusText: String,
    val summary: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
