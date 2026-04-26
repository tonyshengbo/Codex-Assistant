package com.auracode.assistant.toolwindow.submission

import com.auracode.assistant.protocol.UnifiedAgentSnapshot
import com.auracode.assistant.protocol.UnifiedAgentStatus

/**
 * UI-facing status for one session subagent.
 */
internal enum class SessionSubagentStatus {
    ACTIVE,
    IDLE,
    PENDING,
    FAILED,
    COMPLETED,
    UNKNOWN,
}

/**
 * UI snapshot used by the composer tray and the grouped mention popup.
 */
internal data class SessionSubagentUiModel(
    val threadId: String,
    val displayName: String,
    val mentionSlug: String,
    val status: SessionSubagentStatus,
    val statusText: String,
    val summary: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * One grouped mention suggestion row source.
 */
internal sealed interface MentionSuggestion {
    data class Agent(val agent: SessionSubagentUiModel) : MentionSuggestion
    data class File(val entry: ContextEntry) : MentionSuggestion
}

/**
 * Aggregates the current tray counts so the header can surface active and failed work at a glance.
 */
internal data class SessionSubagentCounts(
    val active: Int,
    val failed: Int,
    val pending: Int,
    val completed: Int,
)

/**
 * Maps protocol-level snapshots into composer UI models.
 */
internal fun UnifiedAgentSnapshot.toSessionSubagentUiModel(): SessionSubagentUiModel {
    return SessionSubagentUiModel(
        threadId = threadId,
        displayName = displayName,
        mentionSlug = mentionSlug,
        status = when (status) {
            UnifiedAgentStatus.ACTIVE -> SessionSubagentStatus.ACTIVE
            UnifiedAgentStatus.IDLE -> SessionSubagentStatus.IDLE
            UnifiedAgentStatus.PENDING -> SessionSubagentStatus.PENDING
            UnifiedAgentStatus.FAILED -> SessionSubagentStatus.FAILED
            UnifiedAgentStatus.COMPLETED -> SessionSubagentStatus.COMPLETED
            UnifiedAgentStatus.UNKNOWN -> SessionSubagentStatus.UNKNOWN
        },
        statusText = statusText,
        summary = summary,
        updatedAt = updatedAt,
    )
}

/**
 * Keeps subagent rows ordered by urgency first and recency second across tray and mention entrypoints.
 */
internal fun sortSessionSubagents(subagents: List<SessionSubagentUiModel>): List<SessionSubagentUiModel> {
    return subagents.sortedWith(
        compareBy<SessionSubagentUiModel> { sessionSubagentStatusPriority(it.status) }
            .thenByDescending { it.updatedAt },
    )
}

/**
 * Summarizes the current subagent list into lightweight tray header counts.
 */
internal fun countSessionSubagents(subagents: List<SessionSubagentUiModel>): SessionSubagentCounts {
    return SessionSubagentCounts(
        active = subagents.count { it.status == SessionSubagentStatus.ACTIVE },
        failed = subagents.count { it.status == SessionSubagentStatus.FAILED },
        pending = subagents.count { it.status == SessionSubagentStatus.PENDING },
        completed = subagents.count { it.status == SessionSubagentStatus.COMPLETED },
    )
}

/**
 * Assigns a stable priority so failed and active agents stay pinned above passive states.
 */
internal fun sessionSubagentStatusPriority(status: SessionSubagentStatus): Int {
    return when (status) {
        SessionSubagentStatus.FAILED -> 0
        SessionSubagentStatus.ACTIVE -> 1
        SessionSubagentStatus.PENDING -> 2
        SessionSubagentStatus.COMPLETED -> 3
        SessionSubagentStatus.IDLE -> 4
        SessionSubagentStatus.UNKNOWN -> 5
    }
}
