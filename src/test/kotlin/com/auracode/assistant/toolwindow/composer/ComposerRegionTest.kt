package com.auracode.assistant.toolwindow.composer

import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposerRegionTest {
    @Test
    fun `subagent tray stays visible above all interaction cards`() {
        val cases = listOf(
            ComposerAreaState(
                sessionSubagents = listOf(sampleSubagent()),
                approvalPrompt = com.auracode.assistant.toolwindow.approval.PendingApprovalRequestUiModel(
                    requestId = "approval-1",
                    turnId = "turn-1",
                    itemId = "item-1",
                    kind = com.auracode.assistant.protocol.UnifiedApprovalRequestKind.COMMAND,
                    title = "Run command",
                    body = "./gradlew test",
                ),
            ) to ComposerRegionBodyKind.APPROVAL,
            ComposerAreaState(
                sessionSubagents = listOf(sampleSubagent()),
                toolUserInputPrompt = ToolUserInputPromptUiModel(
                    requestId = "req-1",
                    threadId = "thread-1",
                    turnId = "turn-1",
                    itemId = "item-1",
                    questions = emptyList(),
                ),
            ) to ComposerRegionBodyKind.TOOL_USER_INPUT,
            ComposerAreaState(
                sessionSubagents = listOf(sampleSubagent()),
                planCompletion = ComposerPlanCompletionState(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    preferredExecutionMode = com.auracode.assistant.toolwindow.eventing.ComposerMode.AUTO,
                    planTitle = "Ship plan",
                    planSummary = "",
                    planBody = "- [pending] Ship plan",
                ),
            ) to ComposerRegionBodyKind.PLAN_COMPLETION,
        )

        cases.forEach { (state, expectedBodyKind) ->
            val content = resolveComposerRegionContent(state)
            assertTrue(content.showSubagentTray)
            assertEquals(expectedBodyKind, content.bodyKind)
        }
    }

    /**
     * Creates one stable subagent snapshot for layout tests.
     */
    private fun sampleSubagent(): SessionSubagentUiModel {
        return SessionSubagentUiModel(
            threadId = "thread-review-1",
            displayName = "Review Agent",
            mentionSlug = "review-agent",
            status = SessionSubagentStatus.ACTIVE,
            statusText = "active",
            summary = "Reviewing diff",
            updatedAt = 10L,
        )
    }
}
