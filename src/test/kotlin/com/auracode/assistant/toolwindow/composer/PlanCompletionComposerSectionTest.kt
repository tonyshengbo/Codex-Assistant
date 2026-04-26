package com.auracode.assistant.toolwindow.submission

import com.auracode.assistant.toolwindow.eventing.ComposerMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanCompletionComposerSectionTest {
    @Test
    fun `plan completion header ui model omits summary text`() {
        val header = planCompletionHeaderUiModel(
            ComposerPlanCompletionState(
                turnId = "turn-1",
                threadId = "thread-1",
                preferredExecutionMode = ComposerMode.APPROVAL,
                planTitle = "Ship plan mode",
                planSummary = "Keep the timeline flat",
                planBody = "# Ship plan mode",
            ),
        )

        assertEquals("Ship plan mode", header.title)
    }
}
