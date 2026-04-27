package com.auracode.assistant.toolwindow.submission

import com.auracode.assistant.toolwindow.eventing.SubmissionMode
import kotlin.test.Test
import kotlin.test.assertEquals

class SubmissionPlanCompletionSectionTest {
    @Test
    fun `plan completion header ui model omits summary text`() {
        val header = planCompletionHeaderUiModel(
            SubmissionPlanCompletionState(
                turnId = "turn-1",
                threadId = "thread-1",
                preferredExecutionMode = SubmissionMode.APPROVAL,
                planTitle = "Ship plan mode",
                planSummary = "Keep the timeline flat",
                planBody = "# Ship plan mode",
            ),
        )

        assertEquals("Ship plan mode", header.title)
    }
}
