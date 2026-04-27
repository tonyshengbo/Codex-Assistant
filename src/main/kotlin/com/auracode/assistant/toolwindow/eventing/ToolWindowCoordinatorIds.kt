package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.toolwindow.submission.SubmissionAreaState
import com.auracode.assistant.toolwindow.submission.PendingSubmission
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel

internal object ToolWindowCoordinatorIds {
    fun newLocalTurnId(): String = "local-turn-${System.currentTimeMillis()}"

    fun newPendingSubmissionId(submissionState: SubmissionAreaState): String {
        return "pending-${System.currentTimeMillis()}-${submissionState.pendingSubmissions.size}"
    }

    fun newExternalSubmissionId(queue: ArrayDeque<PendingSubmission>): String {
        return "ide-request-${System.currentTimeMillis()}-${queue.size}"
    }

    fun toolUserInputSourceId(prompt: ToolUserInputPromptUiModel): String {
        return "tool-user-input:${prompt.requestId}:${prompt.itemId}"
    }
}
