package com.auracode.assistant.provider.codex.semantic

import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalRequest
import com.auracode.assistant.session.kernel.SessionCommandKind
import com.auracode.assistant.session.kernel.SessionFileChange
import com.auracode.assistant.session.kernel.SessionRunningPlan
import com.auracode.assistant.session.kernel.SessionToolKind
import com.auracode.assistant.session.kernel.SessionToolUserInputRequest

/**
 * Describes provider-specific semantic records extracted from Codex unified events.
 */
internal sealed interface CodexSemanticRecord {
    /** Stores one normalized Codex command activity. */
    data class CommandActivity(
        val itemId: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val commandKind: SessionCommandKind,
        val command: String?,
        val cwd: String?,
        val outputText: String?,
    ) : CodexSemanticRecord

    /** Stores one normalized Codex tool activity. */
    data class ToolActivity(
        val itemId: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val toolKind: SessionToolKind,
        val toolName: String,
        val summary: String?,
    ) : CodexSemanticRecord

    /** Stores one normalized Codex file-change activity. */
    data class FileChangesActivity(
        val itemId: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val summary: String,
        val changes: List<SessionFileChange>,
    ) : CodexSemanticRecord

    /** Stores one normalized Codex approval request. */
    data class ApprovalRequest(
        val request: SessionApprovalRequest,
    ) : CodexSemanticRecord

    /** Stores one normalized Codex tool user-input request. */
    data class ToolUserInputRequest(
        val request: SessionToolUserInputRequest,
    ) : CodexSemanticRecord

    /** Stores one normalized Codex running-plan update. */
    data class RunningPlanActivity(
        val plan: SessionRunningPlan,
    ) : CodexSemanticRecord
}
