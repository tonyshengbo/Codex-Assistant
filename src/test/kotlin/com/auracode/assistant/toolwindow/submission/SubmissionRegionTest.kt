package com.auracode.assistant.toolwindow.submission

import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubmissionRegionTest {
    @Test
    fun `subagent tray stays visible above all interaction cards`() {
        val cases = listOf(
            SubmissionAreaState(
                sessionSubagents = listOf(sampleSubagent()),
                approvalPrompt = com.auracode.assistant.toolwindow.execution.PendingApprovalRequestUiModel(
                    requestId = "approval-1",
                    turnId = "turn-1",
                    itemId = "item-1",
                    kind = com.auracode.assistant.protocol.ProviderApprovalRequestKind.COMMAND,
                    title = "Run command",
                    body = "./gradlew test",
                ),
            ) to SubmissionRegionBodyKind.APPROVAL,
            SubmissionAreaState(
                sessionSubagents = listOf(sampleSubagent()),
                toolUserInputPrompt = ToolUserInputPromptUiModel(
                    requestId = "req-1",
                    threadId = "thread-1",
                    turnId = "turn-1",
                    itemId = "item-1",
                    questions = emptyList(),
                ),
            ) to SubmissionRegionBodyKind.TOOL_USER_INPUT,
            SubmissionAreaState(
                sessionSubagents = listOf(sampleSubagent()),
                planCompletion = SubmissionPlanCompletionState(
                    turnId = "turn-1",
                    threadId = "thread-1",
                    preferredExecutionMode = com.auracode.assistant.toolwindow.eventing.SubmissionMode.AUTO,
                    planTitle = "Ship plan",
                    planSummary = "",
                    planBody = "- [pending] Ship plan",
                ),
            ) to SubmissionRegionBodyKind.PLAN_COMPLETION,
        )

        cases.forEach { (state, expectedBodyKind) ->
            val content = resolveSubmissionRegionContent(state)
            assertTrue(content.showSubagentTray)
            assertFalse(content.showRunningPlan)
            assertEquals(expectedBodyKind, content.bodyKind)
        }
    }

    @Test
    fun `running plan shows above default composer content only when no interaction card is active`() {
        val runningPlan = SubmissionRunningPlanState(
            threadId = "thread-1",
            turnId = "turn-1",
            explanation = "Executing plan",
            steps = listOf(
                SubmissionRunningPlanStep(step = "Inspect logs", status = SubmissionRunningPlanStepStatus.IN_PROGRESS),
            ),
        )

        val defaultContent = resolveSubmissionRegionContent(
            SubmissionAreaState(
                runningPlan = runningPlan,
            ),
        )
        assertTrue(defaultContent.showRunningPlan)
        assertEquals(SubmissionRegionBodyKind.DEFAULT, defaultContent.bodyKind)

        val approvalContent = resolveSubmissionRegionContent(
            SubmissionAreaState(
                runningPlan = runningPlan,
                approvalPrompt = com.auracode.assistant.toolwindow.execution.PendingApprovalRequestUiModel(
                    requestId = "approval-1",
                    turnId = "turn-1",
                    itemId = "item-1",
                    kind = com.auracode.assistant.protocol.ProviderApprovalRequestKind.COMMAND,
                    title = "Run command",
                    body = "./gradlew test",
                ),
            ),
        )
        assertFalse(approvalContent.showRunningPlan)
        assertEquals(SubmissionRegionBodyKind.APPROVAL, approvalContent.bodyKind)
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
