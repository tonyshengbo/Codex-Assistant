package com.auracode.assistant.provider.codex.semantic

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.ProviderApprovalRequest
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.protocol.ProviderToolUserInputPrompt
import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalRequest
import com.auracode.assistant.session.kernel.SessionApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionRunningPlan
import com.auracode.assistant.session.kernel.SessionRunningPlanStep
import com.auracode.assistant.session.kernel.SessionToolUserInputOption
import com.auracode.assistant.session.kernel.SessionToolUserInputQuestion
import com.auracode.assistant.session.kernel.SessionToolUserInputRequest
import com.auracode.assistant.session.normalizer.CommandSemanticClassifier
import com.auracode.assistant.session.normalizer.FileChangeSemanticParser
import com.auracode.assistant.session.normalizer.ToolSemanticClassifier

/**
 * Extracts provider-specific Codex semantic records from unified provider events.
 */
internal class CodexSemanticEventExtractor(
    private val toolClassifier: ToolSemanticClassifier = ToolSemanticClassifier(),
    private val commandClassifier: CommandSemanticClassifier = CommandSemanticClassifier(),
    private val fileChangeParser: FileChangeSemanticParser = FileChangeSemanticParser(),
) {
    /** Extracts zero or more semantic records from one Codex unified event. */
    fun extract(event: ProviderEvent): List<CodexSemanticRecord> {
        return when (event) {
            is ProviderEvent.ItemUpdated -> extractItem(event.item)
            is ProviderEvent.ApprovalRequested -> listOf(
                CodexSemanticRecord.ApprovalRequest(
                    request = approvalRequest(event.request),
                ),
            )

            is ProviderEvent.ToolUserInputRequested -> listOf(
                CodexSemanticRecord.ToolUserInputRequest(
                    request = toolUserInputRequest(event.prompt),
                ),
            )

            is ProviderEvent.RunningPlanUpdated -> listOf(
                CodexSemanticRecord.RunningPlanActivity(
                    plan = SessionRunningPlan(
                        turnId = event.turnId,
                        explanation = event.explanation,
                        steps = event.steps.map { step ->
                            SessionRunningPlanStep(
                                step = step.step,
                                status = step.status,
                            )
                        },
                        body = event.body,
                    ),
                ),
            )

            else -> emptyList()
        }
    }

    /** Extracts semantic records from one structured unified item. */
    private fun extractItem(item: ProviderItem): List<CodexSemanticRecord> {
        val status = item.status.toSessionStatus()
        return when (item.kind) {
            ItemKind.COMMAND_EXEC -> listOf(
                CodexSemanticRecord.CommandActivity(
                    itemId = item.id,
                    turnId = null,
                    status = status,
                    commandKind = commandClassifier.classify(
                        command = item.command,
                        toolName = item.name,
                        filePath = item.filePath,
                    ),
                    command = item.command,
                    cwd = item.cwd,
                    outputText = item.text,
                ),
            )

            ItemKind.DIFF_APPLY -> {
                val changes = fileChangeParser.parseProviderFileChanges(item.fileChanges)
                listOf(
                    CodexSemanticRecord.FileChangesActivity(
                        itemId = item.id,
                        turnId = null,
                        status = status,
                        summary = changes.joinToString("\n") { it.summary }.ifBlank { item.text.orEmpty() },
                        changes = changes,
                    ),
                )
            }

            ItemKind.TOOL_CALL -> listOf(
                CodexSemanticRecord.ToolActivity(
                    itemId = item.id,
                    turnId = null,
                    status = status,
                    toolKind = toolClassifier.classifyProviderTool(item.name),
                    toolName = item.name.orEmpty().ifBlank { "tool" },
                    summary = item.text,
                ),
            )

            else -> emptyList()
        }
    }

    /** Converts a unified approval request into a kernel-facing approval request model. */
    private fun approvalRequest(request: ProviderApprovalRequest): SessionApprovalRequest {
        return SessionApprovalRequest(
            requestId = request.requestId,
            turnId = request.turnId,
            itemId = request.itemId,
            kind = when (request.kind) {
                com.auracode.assistant.protocol.ProviderApprovalRequestKind.COMMAND -> SessionApprovalRequestKind.COMMAND
                com.auracode.assistant.protocol.ProviderApprovalRequestKind.FILE_CHANGE -> SessionApprovalRequestKind.FILE_CHANGE
                com.auracode.assistant.protocol.ProviderApprovalRequestKind.PERMISSIONS -> SessionApprovalRequestKind.PERMISSIONS
            },
            titleKey = when (request.kind) {
                com.auracode.assistant.protocol.ProviderApprovalRequestKind.COMMAND ->
                    "session.execution.approval.runCommand"

                com.auracode.assistant.protocol.ProviderApprovalRequestKind.FILE_CHANGE ->
                    "session.execution.approval.applyFileChange"

                com.auracode.assistant.protocol.ProviderApprovalRequestKind.PERMISSIONS ->
                    "session.execution.approval.grantPermissions"
            },
            body = request.body,
            command = request.command,
            cwd = request.cwd,
            permissions = request.permissions,
            allowForSession = request.allowForSession,
        )
    }

    /** Converts a unified tool user-input prompt into a kernel-facing request model. */
    private fun toolUserInputRequest(prompt: ProviderToolUserInputPrompt): SessionToolUserInputRequest {
        return SessionToolUserInputRequest(
            requestId = prompt.requestId,
            threadId = prompt.threadId,
            turnId = prompt.turnId,
            itemId = prompt.itemId,
            questions = prompt.questions.map { question ->
                SessionToolUserInputQuestion(
                    id = question.id,
                    headerKey = question.header,
                    promptKey = question.question,
                    options = question.options.map { option ->
                        SessionToolUserInputOption(
                            label = option.label,
                            description = option.description,
                        )
                    },
                    isOther = question.isOther,
                    isSecret = question.isSecret,
                )
            },
        )
    }

    /** Maps unified item status into the shared session activity status enum. */
    private fun ItemStatus.toSessionStatus(): SessionActivityStatus {
        return when (this) {
            ItemStatus.RUNNING -> SessionActivityStatus.RUNNING
            ItemStatus.SUCCESS -> SessionActivityStatus.SUCCESS
            ItemStatus.FAILED -> SessionActivityStatus.FAILED
            ItemStatus.SKIPPED -> SessionActivityStatus.SKIPPED
        }
    }
}
