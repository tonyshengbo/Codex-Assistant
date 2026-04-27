package com.auracode.assistant.session.projection.execution

import com.auracode.assistant.protocol.ProviderApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionState
import com.auracode.assistant.toolwindow.execution.PendingApprovalRequestUiModel
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.execution.ExecutionTurnStatusUiState
import com.auracode.assistant.toolwindow.execution.ToolUserInputOptionUiModel
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel
import com.auracode.assistant.toolwindow.execution.ToolUserInputQuestionUiModel

/**
 * Stores the projected execution-facing UI models derived from session state.
 */
internal data class ExecutionUiProjection(
    val approvals: List<PendingApprovalRequestUiModel>,
    val toolUserInputs: List<ToolUserInputPromptUiModel>,
    val turnStatus: ExecutionTurnStatusUiState?,
)

/**
 * Projects kernel execution state into existing approval, tool-input, and status UI models.
 */
internal class ExecutionUiProjectionBuilder {
    /** Projects one immutable session state snapshot into execution-ready UI models. */
    fun project(state: SessionState): ExecutionUiProjection {
        val approvals = state.execution.approvalRequests.values.mapIndexed { index, request ->
            PendingApprovalRequestUiModel(
                requestId = request.requestId,
                turnId = request.turnId,
                itemId = request.itemId,
                kind = request.kind.toProviderKind(),
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
        return ExecutionUiProjection(
            approvals = approvals,
            toolUserInputs = toolUserInputs,
            turnStatus = when {
                toolUserInputs.isNotEmpty() -> ExecutionTurnStatusUiState(
                    label = UiText.Bundle("status.waitingInput"),
                    startedAtMs = state.runtime.turnStartedAtMs ?: 0L,
                    turnId = state.runtime.activeTurnId,
                )

                state.runtime.runStatus == com.auracode.assistant.session.kernel.SessionRunStatus.RUNNING -> ExecutionTurnStatusUiState(
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
    private fun SessionApprovalRequestKind.toProviderKind(): ProviderApprovalRequestKind {
        return when (this) {
            SessionApprovalRequestKind.COMMAND -> ProviderApprovalRequestKind.COMMAND
            SessionApprovalRequestKind.FILE_CHANGE -> ProviderApprovalRequestKind.FILE_CHANGE
            SessionApprovalRequestKind.PERMISSIONS -> ProviderApprovalRequestKind.PERMISSIONS
        }
    }
}
