package com.codex.assistant.toolwindow.actions

import com.codex.assistant.model.TimelineAction
import com.intellij.openapi.vfs.VirtualFile

internal class DiffProposalAction(
    private val supportsDiffProposal: () -> Boolean,
    private val resolveVirtualFile: (String) -> VirtualFile?,
    private val shouldApplyDiffProposal: () -> Boolean,
    private val emitSystemMessage: (String) -> Unit,
    private val refreshMessages: () -> Unit,
) {
    fun apply(action: TimelineAction.DiffProposalReceived) {
        if (!supportsDiffProposal()) {
            return
        }
        if (shouldApplyDiffProposal()) {
            emitSystemMessage("Diff application is unavailable for change-list payloads: ${action.summary}")
            refreshMessages()
        }
    }
}
