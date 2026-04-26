package com.auracode.assistant.provider.claude.semantic

import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalRequest
import com.auracode.assistant.session.kernel.SessionCommandKind
import com.auracode.assistant.session.kernel.SessionFileChange
import com.auracode.assistant.session.kernel.SessionMessageRole
import com.auracode.assistant.session.kernel.SessionRunningPlan
import com.auracode.assistant.session.kernel.SessionToolKind

/**
 * Describes provider-specific semantic records extracted from Claude conversation events.
 */
internal sealed interface ClaudeSemanticRecord {
    /** Stores one normalized Claude message snapshot. */
    data class Message(
        val messageId: String,
        val turnId: String?,
        val role: SessionMessageRole,
        val text: String,
    ) : ClaudeSemanticRecord

    /** Stores one normalized Claude command activity. */
    data class CommandActivity(
        val itemId: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val commandKind: SessionCommandKind,
        val command: String?,
        val cwd: String?,
        val outputText: String?,
    ) : ClaudeSemanticRecord

    /** Stores one normalized Claude tool activity. */
    data class ToolActivity(
        val itemId: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val toolKind: SessionToolKind,
        val toolName: String,
        val summary: String?,
    ) : ClaudeSemanticRecord

    /** Stores one normalized Claude file-change activity. */
    data class FileChangesActivity(
        val itemId: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val summary: String,
        val changes: List<SessionFileChange>,
    ) : ClaudeSemanticRecord

    /** Stores one normalized Claude approval request. */
    data class ApprovalRequest(
        val request: SessionApprovalRequest,
    ) : ClaudeSemanticRecord

    /** Stores one normalized Claude running plan update. */
    data class RunningPlanActivity(
        val plan: SessionRunningPlan,
    ) : ClaudeSemanticRecord
}
