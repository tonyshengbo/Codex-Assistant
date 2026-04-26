package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.toolwindow.submission.ComposerAreaState
import com.auracode.assistant.toolwindow.submission.PendingComposerSubmission
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel

internal object ToolWindowCoordinatorIds {
    fun newLocalTurnId(): String = "local-turn-${System.currentTimeMillis()}"

    fun newPendingSubmissionId(composerState: ComposerAreaState): String {
        return "pending-${System.currentTimeMillis()}-${composerState.pendingSubmissions.size}"
    }

    fun newExternalSubmissionId(queue: ArrayDeque<PendingComposerSubmission>): String {
        return "ide-request-${System.currentTimeMillis()}-${queue.size}"
    }

    fun toolUserInputSourceId(prompt: ToolUserInputPromptUiModel): String {
        return "tool-user-input:${prompt.requestId}:${prompt.itemId}"
    }
}
