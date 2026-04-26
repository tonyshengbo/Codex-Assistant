package com.auracode.assistant.session.normalizer

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.UnifiedApprovalRequest
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedItem
import com.auracode.assistant.protocol.UnifiedToolUserInputPrompt
import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalRequest
import com.auracode.assistant.session.kernel.SessionApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionMessageRole
import com.auracode.assistant.session.kernel.SessionMessageAttachment
import com.auracode.assistant.session.kernel.SessionRunningPlan
import com.auracode.assistant.session.kernel.SessionRunningPlanStep
import com.auracode.assistant.session.kernel.SessionToolUserInputOption
import com.auracode.assistant.session.kernel.SessionToolUserInputQuestion
import com.auracode.assistant.session.kernel.SessionToolUserInputRequest

/**
 * Maps persisted or live unified events into session-kernel domain events.
 */
internal class UnifiedEventSessionEventMapper(
    private val commandClassifier: CommandSemanticClassifier = CommandSemanticClassifier(),
    private val toolClassifier: ToolSemanticClassifier = ToolSemanticClassifier(),
    private val fileChangeParser: FileChangeSemanticParser = FileChangeSemanticParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var activeThreadId: String? = null
    private var activeTurnId: String? = null
    private var errorCount: Int = 0

    /** Maps one unified event into zero or more kernel-facing domain events. */
    fun map(event: UnifiedEvent): List<SessionDomainEvent> {
        return when (event) {
            is UnifiedEvent.ThreadStarted -> {
                activeThreadId = event.threadId
                listOf(
                    SessionDomainEvent.ThreadStarted(
                        threadId = event.threadId,
                    ),
                )
            }

            is UnifiedEvent.TurnStarted -> {
                activeThreadId = event.threadId ?: activeThreadId
                activeTurnId = event.turnId
                listOf(
                    SessionDomainEvent.TurnStarted(
                        turnId = event.turnId,
                        threadId = event.threadId ?: activeThreadId,
                        startedAtMs = clock(),
                    ),
                )
            }

            is UnifiedEvent.ItemUpdated -> mapItem(event.item)
            is UnifiedEvent.ApprovalRequested -> listOf(
                SessionDomainEvent.ApprovalRequested(
                    request = approvalRequest(event.request),
                ),
            )

            is UnifiedEvent.ToolUserInputRequested -> listOf(
                SessionDomainEvent.ToolUserInputRequested(
                    request = toolUserInputRequest(event.prompt),
                ),
            )

            is UnifiedEvent.ToolUserInputResolved -> listOf(
                SessionDomainEvent.ToolUserInputResolved(
                    requestId = event.requestId,
                    status = SessionActivityStatus.FAILED,
                    responseSummary = null,
                ),
            )

            is UnifiedEvent.RunningPlanUpdated -> listOf(
                SessionDomainEvent.RunningPlanUpdated(
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

            is UnifiedEvent.TurnCompleted -> {
                activeTurnId = event.turnId
                listOf(
                    SessionDomainEvent.TurnCompleted(
                        turnId = event.turnId,
                        outcome = when (event.outcome) {
                            com.auracode.assistant.protocol.TurnOutcome.SUCCESS ->
                                com.auracode.assistant.session.kernel.SessionTurnOutcome.SUCCESS

                            com.auracode.assistant.protocol.TurnOutcome.FAILED ->
                                com.auracode.assistant.session.kernel.SessionTurnOutcome.FAILED

                            com.auracode.assistant.protocol.TurnOutcome.CANCELLED ->
                                com.auracode.assistant.session.kernel.SessionTurnOutcome.CANCELLED

                            com.auracode.assistant.protocol.TurnOutcome.RUNNING ->
                                com.auracode.assistant.session.kernel.SessionTurnOutcome.SUCCESS
                        },
                    ),
                )
            }

            is UnifiedEvent.Error -> listOf(
                SessionDomainEvent.ErrorAppended(
                    itemId = nextErrorId(),
                    turnId = activeTurnId,
                    message = event.message,
                    terminal = event.terminal,
                ),
            )

            is UnifiedEvent.SubagentsUpdated,
            is UnifiedEvent.ThreadTokenUsageUpdated,
            is UnifiedEvent.TurnDiffUpdated,
            -> emptyList()
        }
    }

    /** Resets mapper-local state before replaying a fresh history stream. */
    fun reset() {
        activeThreadId = null
        activeTurnId = null
        errorCount = 0
    }

    /** Maps one unified item into structured kernel conversation events. */
    private fun mapItem(item: UnifiedItem): List<SessionDomainEvent> {
        val turnId = activeTurnId
        return when (item.kind) {
            ItemKind.NARRATIVE -> {
                val text = item.text?.takeIf { it.isNotBlank() } ?: return emptyList()
                when (item.name) {
                    "reasoning" -> listOf(
                        SessionDomainEvent.ReasoningUpdated(
                            itemId = item.id,
                            turnId = turnId,
                            status = item.status.toSessionStatus(),
                            text = text,
                        ),
                    )

                    else -> listOf(
                        SessionDomainEvent.MessageAppended(
                            messageId = item.id,
                            turnId = turnId,
                            role = item.toSessionMessageRole(),
                            text = text,
                            attachments = item.attachments.map { attachment ->
                                SessionMessageAttachment(
                                    id = attachment.id,
                                    kind = attachment.kind,
                                    displayName = attachment.displayName,
                                    assetPath = attachment.assetPath,
                                    originalPath = attachment.originalPath,
                                    mimeType = attachment.mimeType,
                                    sizeBytes = attachment.sizeBytes,
                                    status = attachment.status.toSessionStatus(),
                                )
                            },
                        ),
                    )
                }
            }

            ItemKind.COMMAND_EXEC -> listOf(
                SessionDomainEvent.CommandUpdated(
                    itemId = item.id,
                    turnId = turnId,
                    status = item.status.toSessionStatus(),
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

            ItemKind.TOOL_CALL -> listOf(
                SessionDomainEvent.ToolUpdated(
                    itemId = item.id,
                    turnId = turnId,
                    status = item.status.toSessionStatus(),
                    toolKind = toolClassifier.classifyUnifiedTool(item.name),
                    toolName = item.name.orEmpty().ifBlank { "tool" },
                    summary = item.text,
                ),
            )

            ItemKind.DIFF_APPLY -> {
                val changes = if (item.fileChanges.isNotEmpty()) {
                    fileChangeParser.parseUnifiedFileChanges(item.fileChanges)
                } else {
                    fileChangeParser.parseSummaryBody(item.text.orEmpty())
                }
                if (changes.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        SessionDomainEvent.FileChangesUpdated(
                            itemId = item.id,
                            turnId = turnId,
                            status = item.status.toSessionStatus(),
                            summary = changes.joinToString("\n") { it.summary },
                            changes = changes,
                        ),
                    )
                }
            }

            ItemKind.APPROVAL_REQUEST,
            ItemKind.CONTEXT_COMPACTION,
            ItemKind.PLAN_UPDATE,
            ItemKind.USER_INPUT,
            ItemKind.UNKNOWN,
            -> emptyList()
        }
    }

    /** Converts a unified approval request into a kernel-facing execution request. */
    private fun approvalRequest(request: UnifiedApprovalRequest): SessionApprovalRequest {
        return SessionApprovalRequest(
            requestId = request.requestId,
            turnId = request.turnId ?: activeTurnId,
            itemId = request.itemId,
            kind = when (request.kind) {
                UnifiedApprovalRequestKind.COMMAND -> SessionApprovalRequestKind.COMMAND
                UnifiedApprovalRequestKind.FILE_CHANGE -> SessionApprovalRequestKind.FILE_CHANGE
                UnifiedApprovalRequestKind.PERMISSIONS -> SessionApprovalRequestKind.PERMISSIONS
            },
            titleKey = when (request.kind) {
                UnifiedApprovalRequestKind.COMMAND -> "session.execution.approval.runCommand"
                UnifiedApprovalRequestKind.FILE_CHANGE -> "session.execution.approval.applyFileChange"
                UnifiedApprovalRequestKind.PERMISSIONS -> "session.execution.approval.grantPermissions"
            },
            body = request.body,
            command = request.command,
            cwd = request.cwd,
            permissions = request.permissions,
            allowForSession = request.allowForSession,
        )
    }

    /** Converts a unified tool user-input request into a kernel-facing execution request. */
    private fun toolUserInputRequest(prompt: UnifiedToolUserInputPrompt): SessionToolUserInputRequest {
        return SessionToolUserInputRequest(
            requestId = prompt.requestId,
            threadId = prompt.threadId,
            turnId = prompt.turnId ?: activeTurnId,
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

    /** Maps unified item status values into shared kernel activity statuses. */
    private fun ItemStatus.toSessionStatus(): SessionActivityStatus {
        return when (this) {
            ItemStatus.RUNNING -> SessionActivityStatus.RUNNING
            ItemStatus.SUCCESS -> SessionActivityStatus.SUCCESS
            ItemStatus.FAILED -> SessionActivityStatus.FAILED
            ItemStatus.SKIPPED -> SessionActivityStatus.SKIPPED
        }
    }

    /** Maps unified narrative item names into shared message roles. */
    private fun UnifiedItem.toSessionMessageRole(): SessionMessageRole {
        return when (name) {
            "user_message" -> SessionMessageRole.USER
            "system_message" -> SessionMessageRole.SYSTEM
            else -> SessionMessageRole.ASSISTANT
        }
    }

    /** Allocates a stable error entry id within one mapper lifecycle. */
    private fun nextErrorId(): String {
        errorCount += 1
        return "session-error-$errorCount"
    }
}
