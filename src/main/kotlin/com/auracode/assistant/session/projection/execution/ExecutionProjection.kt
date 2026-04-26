package com.auracode.assistant.session.projection.execution

import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionState
import com.auracode.assistant.toolwindow.execution.PendingApprovalRequestUiModel
import com.auracode.assistant.toolwindow.submission.ComposerRunningPlanState
import com.auracode.assistant.toolwindow.submission.ComposerRunningPlanStep
import com.auracode.assistant.toolwindow.submission.ComposerRunningPlanStepStatus
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.execution.TurnStatusUiState
import com.auracode.assistant.toolwindow.execution.ToolUserInputOptionUiModel
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel
import com.auracode.assistant.toolwindow.execution.ToolUserInputQuestionUiModel

/**
 * Stores the projected execution-facing UI models derived from session state.
 */
internal data class ExecutionProjection(
    val approvals: List<PendingApprovalRequestUiModel>,
    val toolUserInputs: List<ToolUserInputPromptUiModel>,
    val runningPlan: ComposerRunningPlanState?,
    val turnStatus: TurnStatusUiState?,
)

/**
 * Projects kernel execution state into existing approval, tool-input, and status UI models.
 */
internal class ExecutionProjectionBuilder {
    /** Projects one immutable session state snapshot into execution-ready UI models. */
    fun project(state: SessionState): ExecutionProjection {
        val approvals = state.execution.approvalRequests.values.mapIndexed { index, request ->
            PendingApprovalRequestUiModel(
                requestId = request.requestId,
                turnId = request.turnId,
                itemId = request.itemId,
                kind = request.kind.toUnifiedKind(),
                title = localizeKeyOrRaw(request.titleKey),
                body = request.body,
                command = request.command,
                cwd = request.cwd,
                permissions = request.permissions,
                allowForSession = request.allowForSession,
                queuePosition = index + 1,
                queueSize = state.execution.approvalRequests.size,
            )
        }
        val toolUserInputs = state.execution.toolUserInputs.values.mapIndexed { index, request ->
            ToolUserInputPromptUiModel(
                requestId = request.requestId,
                threadId = request.threadId,
                turnId = request.turnId,
                itemId = request.itemId,
                questions = request.questions.map { question ->
                    ToolUserInputQuestionUiModel(
                        id = question.id,
                        header = localizeKeyOrRaw(question.headerKey),
                        question = localizeKeyOrRaw(question.promptKey),
                        options = question.options.map { option ->
                            ToolUserInputOptionUiModel(
                                label = option.label,
                                description = option.description,
                            )
                        },
                        isOther = question.isOther,
                        isSecret = question.isSecret,
                    )
                },
                queuePosition = index + 1,
                queueSize = state.execution.toolUserInputs.size,
            )
        }
        return ExecutionProjection(
            approvals = approvals,
            toolUserInputs = toolUserInputs,
            runningPlan = state.execution.runningPlan?.let { plan ->
                ComposerRunningPlanState(
                    threadId = state.runtime.activeThreadId,
                    turnId = plan.turnId.orEmpty(),
                    explanation = plan.explanation,
                    steps = plan.steps.map { step ->
                        ComposerRunningPlanStep(
                            step = step.step,
                            status = when (step.status.trim().lowercase()) {
                                "completed", "complete", "success", "succeeded", "done" ->
                                    ComposerRunningPlanStepStatus.COMPLETED

                                "inprogress", "in_progress", "running", "active" ->
                                    ComposerRunningPlanStepStatus.IN_PROGRESS

                                else -> ComposerRunningPlanStepStatus.PENDING
                            },
                        )
                    },
                )
            },
            turnStatus = when {
                toolUserInputs.isNotEmpty() -> TurnStatusUiState(
                    label = UiText.Bundle("status.waitingInput"),
                    startedAtMs = state.runtime.turnStartedAtMs ?: 0L,
                    turnId = state.runtime.activeTurnId,
                )

                state.runtime.runStatus == com.auracode.assistant.session.kernel.SessionRunStatus.RUNNING -> TurnStatusUiState(
                    label = UiText.Bundle("status.running"),
                    startedAtMs = state.runtime.turnStartedAtMs ?: 0L,
                    turnId = state.runtime.activeTurnId,
                )

                else -> null
            },
        )
    }

    /** Safely resolves an i18n key and falls back to raw provider text when no bundle entry exists. */
    private fun localizeKeyOrRaw(value: String): String {
        return runCatching { com.auracode.assistant.i18n.AuraCodeBundle.message(value) }.getOrElse { value }
    }

    /** Maps shared approval kinds into the existing approval UI enum. */
    private fun SessionApprovalRequestKind.toUnifiedKind(): UnifiedApprovalRequestKind {
        return when (this) {
            SessionApprovalRequestKind.COMMAND -> UnifiedApprovalRequestKind.COMMAND
            SessionApprovalRequestKind.FILE_CHANGE -> UnifiedApprovalRequestKind.FILE_CHANGE
            SessionApprovalRequestKind.PERMISSIONS -> UnifiedApprovalRequestKind.PERMISSIONS
        }
    }
}
