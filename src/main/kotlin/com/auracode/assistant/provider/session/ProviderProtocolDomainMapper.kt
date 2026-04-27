package com.auracode.assistant.provider.session

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.ProviderAgentSnapshot
import com.auracode.assistant.protocol.ProviderAgentStatus
import com.auracode.assistant.protocol.ProviderApprovalRequest
import com.auracode.assistant.protocol.ProviderApprovalRequestKind
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.protocol.ProviderRunningPlanPresentation
import com.auracode.assistant.protocol.ProviderToolUserInputPrompt
import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalRequest
import com.auracode.assistant.session.kernel.SessionApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionMessageAttachment
import com.auracode.assistant.session.kernel.SessionMessageRole
import com.auracode.assistant.session.kernel.SessionRunningPlan
import com.auracode.assistant.session.kernel.SessionRunningPlanPresentation
import com.auracode.assistant.session.kernel.SessionRunningPlanStep
import com.auracode.assistant.session.kernel.SessionSubagentSnapshot
import com.auracode.assistant.session.kernel.SessionSubagentStatus
import com.auracode.assistant.session.kernel.SessionToolUserInputOption
import com.auracode.assistant.session.kernel.SessionToolUserInputQuestion
import com.auracode.assistant.session.kernel.SessionToolUserInputRequest
import com.auracode.assistant.session.normalizer.CommandSemanticClassifier
import com.auracode.assistant.session.normalizer.FileChangeSemanticParser
import com.auracode.assistant.session.normalizer.ToolSemanticClassifier
import java.util.UUID

/**
 * Normalizes provider-local protocol events into the session-kernel domain stream.
 */
internal class ProviderProtocolDomainMapper(
    private val commandClassifier: CommandSemanticClassifier = CommandSemanticClassifier(),
    private val toolClassifier: ToolSemanticClassifier = ToolSemanticClassifier(),
    private val fileChangeParser: FileChangeSemanticParser = FileChangeSemanticParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var activeThreadId: String? = null
    private var activeTurnId: String? = null

    /** Maps one provider protocol event into zero or more session domain events. */
    fun map(event: ProviderEvent): List<SessionDomainEvent> {
        return when (event) {
            is ProviderEvent.ThreadStarted -> {
                activeThreadId = event.threadId
                listOf(SessionDomainEvent.ThreadStarted(threadId = event.threadId))
            }

            is ProviderEvent.TurnStarted -> {
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

            is ProviderEvent.ItemUpdated -> mapItem(event.item)
            is ProviderEvent.ApprovalRequested -> {
                listOf(SessionDomainEvent.ApprovalRequested(request = approvalRequest(event.request)))
            }

            is ProviderEvent.ToolUserInputRequested -> {
                listOf(SessionDomainEvent.ToolUserInputRequested(request = toolUserInputRequest(event.prompt)))
            }

            is ProviderEvent.ToolUserInputResolved -> {
                listOf(
                    SessionDomainEvent.ToolUserInputResolved(
                        requestId = event.requestId,
                        status = SessionActivityStatus.FAILED,
                        responseSummary = null,
                    ),
                )
            }

            is ProviderEvent.RunningPlanUpdated -> {
                listOf(
                    SessionDomainEvent.RunningPlanUpdated(
                        plan = SessionRunningPlan(
                            turnId = event.turnId,
                            explanation = event.explanation,
                            steps = event.steps.map { step ->
                                SessionRunningPlanStep(step = step.step, status = step.status)
                            },
                            body = event.body,
                            presentation = event.presentation.toSessionPresentation(),
                        ),
                    ),
                )
            }

            is ProviderEvent.TurnDiffUpdated -> fileChangeParser.parseTurnDiff(
                diff = event.diff,
                updatedAtMs = clock(),
            ).takeIf { it.isNotEmpty() }?.let { changes ->
                listOf(
                    SessionDomainEvent.EditedFilesTracked(
                        threadId = event.threadId,
                        turnId = event.turnId,
                        changes = changes,
                    ),
                )
            }.orEmpty()

            is ProviderEvent.TurnCompleted -> {
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

            is ProviderEvent.Error -> {
                listOf(
                    SessionDomainEvent.ErrorAppended(
                        itemId = nextErrorId(),
                        turnId = activeTurnId,
                        message = event.message,
                        terminal = event.terminal,
                    ),
                )
            }

            is ProviderEvent.SubagentsUpdated -> {
                listOf(
                    SessionDomainEvent.SubagentsUpdated(
                        threadId = event.threadId,
                        turnId = event.turnId,
                        agents = event.agents.map { agent -> agent.toSessionSnapshot() },
                    ),
                )
            }

            is ProviderEvent.ThreadTokenUsageUpdated -> {
                listOf(
                    SessionDomainEvent.UsageUpdated(
                        threadId = event.threadId,
                        turnId = event.turnId,
                        model = null,
                        contextWindow = event.contextWindow,
                        inputTokens = event.inputTokens,
                        cachedInputTokens = event.cachedInputTokens,
                        outputTokens = event.outputTokens,
                    ),
                )
            }
        }
    }

    /** Resets mapper-local state before replaying a fresh provider history stream. */
    fun reset() {
        activeThreadId = null
        activeTurnId = null
    }

    /** Maps one provider protocol item into structured session conversation events. */
    private fun mapItem(item: ProviderItem): List<SessionDomainEvent> {
        val turnId = activeTurnId
        return when (item.kind) {
            ItemKind.NARRATIVE -> {
                val text = item.text?.takeIf { it.isNotBlank() } ?: return emptyList()
                when (item.name) {
                    "reasoning" -> {
                        listOf(
                            SessionDomainEvent.ReasoningUpdated(
                                itemId = item.id,
                                turnId = turnId,
                                status = item.status.toSessionStatus(),
                                text = text,
                            ),
                        )
                    }

                    else -> {
                        listOf(
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
            }

            ItemKind.COMMAND_EXEC -> {
                listOf(
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
            }

            ItemKind.TOOL_CALL -> {
                listOf(
                    SessionDomainEvent.ToolUpdated(
                        itemId = item.id,
                        turnId = turnId,
                        status = item.status.toSessionStatus(),
                        toolKind = toolClassifier.classifyProviderTool(item.name),
                        toolName = item.name.orEmpty().ifBlank { "tool" },
                        summary = item.text,
                    ),
                )
            }

            ItemKind.DIFF_APPLY -> {
                val changes = if (item.fileChanges.isNotEmpty()) {
                    fileChangeParser.parseProviderFileChanges(item.fileChanges)
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

            ItemKind.PLAN_UPDATE -> {
                val body = item.text?.trim().orEmpty()
                if (body.isBlank()) {
                    emptyList()
                } else {
                    listOf(
                        SessionDomainEvent.RunningPlanUpdated(
                            plan = SessionRunningPlan(
                                turnId = turnId,
                                explanation = null,
                                steps = parsePlanSteps(body),
                                body = body,
                                presentation = SessionRunningPlanPresentation.TIMELINE,
                            ),
                        ),
                    )
                }
            }

            ItemKind.CONTEXT_COMPACTION,
            ItemKind.UNKNOWN,
            -> emptyList()

            ItemKind.USER_INPUT -> {
                val prompt = item.toolUserInputPrompt ?: return emptyList()
                buildList {
                    add(SessionDomainEvent.ToolUserInputRequested(request = toolUserInputRequest(prompt)))
                    if (prompt.status != ItemStatus.RUNNING) {
                        add(
                            SessionDomainEvent.ToolUserInputResolved(
                                requestId = prompt.requestId,
                                status = prompt.status.toSessionStatus(),
                                responseSummary = prompt.responseSummary,
                            ),
                        )
                    }
                }
            }

            ItemKind.APPROVAL_REQUEST -> emptyList()
        }
    }

    /** Parses checklist-like plan bodies emitted by providers into structured steps. */
    private fun parsePlanSteps(body: String): List<SessionRunningPlanStep> {
        return body.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { line ->
                val match = PLAN_STEP_REGEX.matchEntire(line) ?: return@mapNotNull null
                SessionRunningPlanStep(
                    step = match.groupValues[2].trim(),
                    status = normalizePlanStatus(match.groupValues[1]),
                )
            }
            .toList()
    }

    /** Normalizes checklist markers into the running-plan status vocabulary used by projections. */
    private fun normalizePlanStatus(rawStatus: String): String {
        return when (rawStatus.trim().lowercase()) {
            "x", "done", "completed", "complete", "success", "succeeded" -> "completed"
            "~", "inprogress", "in_progress", "running", "active" -> "in_progress"
            else -> "pending"
        }
    }

    /** Maps provider running-plan presentation into the session-domain presentation enum. */
    private fun ProviderRunningPlanPresentation.toSessionPresentation(): SessionRunningPlanPresentation {
        return when (this) {
            ProviderRunningPlanPresentation.TIMELINE -> SessionRunningPlanPresentation.TIMELINE
            ProviderRunningPlanPresentation.SUBMISSION_PANEL -> SessionRunningPlanPresentation.SUBMISSION_PANEL
        }
    }

    /** Converts one provider approval request into a kernel-facing execution request. */
    private fun approvalRequest(request: ProviderApprovalRequest): SessionApprovalRequest {
        return SessionApprovalRequest(
            requestId = request.requestId,
            turnId = request.turnId ?: activeTurnId,
            itemId = request.itemId,
            kind = when (request.kind) {
                ProviderApprovalRequestKind.COMMAND -> SessionApprovalRequestKind.COMMAND
                ProviderApprovalRequestKind.FILE_CHANGE -> SessionApprovalRequestKind.FILE_CHANGE
                ProviderApprovalRequestKind.PERMISSIONS -> SessionApprovalRequestKind.PERMISSIONS
            },
            titleKey = when (request.kind) {
                ProviderApprovalRequestKind.COMMAND -> "session.execution.approval.runCommand"
                ProviderApprovalRequestKind.FILE_CHANGE -> "session.execution.approval.applyFileChange"
                ProviderApprovalRequestKind.PERMISSIONS -> "session.execution.approval.grantPermissions"
            },
            body = request.body,
            command = request.command,
            cwd = request.cwd,
            permissions = request.permissions,
            allowForSession = request.allowForSession,
        )
    }

    /** Converts one provider user-input request into a kernel-facing execution request. */
    private fun toolUserInputRequest(prompt: ProviderToolUserInputPrompt): SessionToolUserInputRequest {
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

    /** Maps provider item status values into shared kernel activity statuses. */
    private fun ItemStatus.toSessionStatus(): SessionActivityStatus {
        return when (this) {
            ItemStatus.RUNNING -> SessionActivityStatus.RUNNING
            ItemStatus.SUCCESS -> SessionActivityStatus.SUCCESS
            ItemStatus.FAILED -> SessionActivityStatus.FAILED
            ItemStatus.SKIPPED -> SessionActivityStatus.SKIPPED
        }
    }

    /** Maps provider narrative item names into shared message roles. */
    private fun ProviderItem.toSessionMessageRole(): SessionMessageRole {
        return when (name) {
            "user_message" -> SessionMessageRole.USER
            "system_message" -> SessionMessageRole.SYSTEM
            else -> SessionMessageRole.ASSISTANT
        }
    }

    /** Allocates a globally unique error entry id across mapper lifecycles. */
    private fun nextErrorId(): String {
        return "session-error:${UUID.randomUUID()}"
    }

    /** Converts one provider subagent snapshot into the kernel collaboration model. */
    private fun ProviderAgentSnapshot.toSessionSnapshot(): SessionSubagentSnapshot {
        return SessionSubagentSnapshot(
            threadId = threadId,
            displayName = displayName,
            mentionSlug = mentionSlug,
            status = status.toSessionStatus(),
            statusText = statusText,
            summary = summary,
            updatedAt = updatedAt,
        )
    }

    /** Maps provider collaboration statuses into the kernel collaboration vocabulary. */
    private fun ProviderAgentStatus.toSessionStatus(): SessionSubagentStatus {
        return when (this) {
            ProviderAgentStatus.ACTIVE -> SessionSubagentStatus.ACTIVE
            ProviderAgentStatus.IDLE -> SessionSubagentStatus.IDLE
            ProviderAgentStatus.PENDING -> SessionSubagentStatus.PENDING
            ProviderAgentStatus.FAILED -> SessionSubagentStatus.FAILED
            ProviderAgentStatus.COMPLETED -> SessionSubagentStatus.COMPLETED
            ProviderAgentStatus.UNKNOWN -> SessionSubagentStatus.UNKNOWN
        }
    }

    private companion object {
        private val PLAN_STEP_REGEX = Regex("""-\s*\[([^\]]+)]\s*(.+)""")
    }
}
