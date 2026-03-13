package com.codex.assistant.toolwindow

class ApprovalUiPolicy {
    val chipLabel: String = "Approvals: Planned"
    val isInteractive: Boolean = false

    fun shouldExecuteCommandProposal(): Boolean = true

    fun shouldApplyDiffProposal(): Boolean = true
}
