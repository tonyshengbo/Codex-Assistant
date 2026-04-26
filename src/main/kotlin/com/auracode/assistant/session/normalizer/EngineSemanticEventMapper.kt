package com.auracode.assistant.session.normalizer

import com.auracode.assistant.provider.claude.semantic.ClaudeSemanticRecord
import com.auracode.assistant.provider.codex.semantic.CodexSemanticRecord
import com.auracode.assistant.session.kernel.SessionDomainEvent

/**
 * Maps provider-specific semantic records into shared session domain events.
 */
internal class EngineSemanticEventMapper {
    /** Maps one Claude semantic record into zero or more session domain events. */
    fun map(record: ClaudeSemanticRecord): List<SessionDomainEvent> {
        return when (record) {
            is ClaudeSemanticRecord.Message -> listOf(
                SessionDomainEvent.MessageAppended(
                    messageId = record.messageId,
                    turnId = record.turnId,
                    role = record.role,
                    text = record.text,
                ),
            )

            is ClaudeSemanticRecord.CommandActivity -> listOf(
                SessionDomainEvent.CommandUpdated(
                    itemId = record.itemId,
                    turnId = record.turnId,
                    status = record.status,
                    commandKind = record.commandKind,
                    command = record.command,
                    cwd = record.cwd,
                    outputText = record.outputText,
                ),
            )

            is ClaudeSemanticRecord.ToolActivity -> listOf(
                SessionDomainEvent.ToolUpdated(
                    itemId = record.itemId,
                    turnId = record.turnId,
                    status = record.status,
                    toolKind = record.toolKind,
                    toolName = record.toolName,
                    summary = record.summary,
                ),
            )

            is ClaudeSemanticRecord.FileChangesActivity -> listOf(
                SessionDomainEvent.FileChangesUpdated(
                    itemId = record.itemId,
                    turnId = record.turnId,
                    status = record.status,
                    summary = record.summary,
                    changes = record.changes,
                ),
            )

            is ClaudeSemanticRecord.ApprovalRequest -> listOf(
                SessionDomainEvent.ApprovalRequested(
                    request = record.request,
                ),
            )

            is ClaudeSemanticRecord.RunningPlanActivity -> listOf(
                SessionDomainEvent.RunningPlanUpdated(
                    plan = record.plan,
                ),
            )
        }
    }

    /** Maps one Codex semantic record into zero or more session domain events. */
    fun map(record: CodexSemanticRecord): List<SessionDomainEvent> {
        return when (record) {
            is CodexSemanticRecord.CommandActivity -> listOf(
                SessionDomainEvent.CommandUpdated(
                    itemId = record.itemId,
                    turnId = record.turnId,
                    status = record.status,
                    commandKind = record.commandKind,
                    command = record.command,
                    cwd = record.cwd,
                    outputText = record.outputText,
                ),
            )

            is CodexSemanticRecord.ToolActivity -> listOf(
                SessionDomainEvent.ToolUpdated(
                    itemId = record.itemId,
                    turnId = record.turnId,
                    status = record.status,
                    toolKind = record.toolKind,
                    toolName = record.toolName,
                    summary = record.summary,
                ),
            )

            is CodexSemanticRecord.FileChangesActivity -> listOf(
                SessionDomainEvent.FileChangesUpdated(
                    itemId = record.itemId,
                    turnId = record.turnId,
                    status = record.status,
                    summary = record.summary,
                    changes = record.changes,
                ),
            )

            is CodexSemanticRecord.ApprovalRequest -> listOf(
                SessionDomainEvent.ApprovalRequested(
                    request = record.request,
                ),
            )

            is CodexSemanticRecord.ToolUserInputRequest -> listOf(
                SessionDomainEvent.ToolUserInputRequested(
                    request = record.request,
                ),
            )

            is CodexSemanticRecord.RunningPlanActivity -> listOf(
                SessionDomainEvent.RunningPlanUpdated(
                    plan = record.plan,
                ),
            )
        }
    }
}
