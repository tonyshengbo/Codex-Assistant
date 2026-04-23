package com.auracode.assistant.toolwindow.composer

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class RunningPlanComposerSectionTest {
    @Test
    fun `toggle icon uses local arrow up while collapsed`() {
        assertEquals("/icons/arrow-up.svg", runningPlanToggleIconPath(expanded = false))
        assertEquals("/icons/arrow-down.svg", runningPlanToggleIconPath(expanded = true))
    }

    @Test
    fun `header summary prefers in progress step and reports progress`() {
        val summary = runningPlanHeaderSummary(
            ComposerRunningPlanState(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = "Executing plan",
                steps = listOf(
                    ComposerRunningPlanStep(step = "Inspect logs", status = ComposerRunningPlanStepStatus.COMPLETED),
                    ComposerRunningPlanStep(step = "Update UI", status = ComposerRunningPlanStepStatus.IN_PROGRESS),
                    ComposerRunningPlanStep(step = "Verify tests", status = ComposerRunningPlanStepStatus.PENDING),
                ),
            ),
        )

        assertEquals("Running plan", summary.title)
        assertEquals("Update UI", summary.currentStep)
        assertEquals("1/3", summary.progressLabel)
    }

    @Test
    fun `header summary falls back to first pending step when nothing is running`() {
        val summary = runningPlanHeaderSummary(
            ComposerRunningPlanState(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = null,
                steps = listOf(
                    ComposerRunningPlanStep(step = "Inspect logs", status = ComposerRunningPlanStepStatus.COMPLETED),
                    ComposerRunningPlanStep(step = "Patch UI", status = ComposerRunningPlanStepStatus.PENDING),
                    ComposerRunningPlanStep(step = "Verify tests", status = ComposerRunningPlanStepStatus.PENDING),
                ),
            ),
        )

        assertEquals("Patch UI", summary.currentStep)
        assertEquals("1/3", summary.progressLabel)
    }

    @Test
    fun `progress badge chrome stays visually quiet and compact`() {
        val chrome = runningPlanProgressBadgeChrome()

        assertEquals(5.dp, chrome.horizontalPadding)
        assertEquals(0.dp, chrome.verticalPadding)
        assertEquals(34.dp, chrome.minWidth)
        assertEquals(18.dp, chrome.minHeight)
        assertEquals(999.dp, chrome.cornerRadius)
        assertEquals(0.16f, chrome.backgroundAlpha)
        assertEquals(0.24f, chrome.borderAlpha)
    }

    @Test
    fun `step rows center the status dot with the step label`() {
        val spec = runningPlanStepRowSpec()

        assertEquals(Alignment.CenterVertically, spec.rowVerticalAlignment)
        assertEquals(0.dp, spec.dotTopPadding)
        assertEquals(6.dp, spec.inactiveDotSize)
        assertEquals(7.dp, spec.activeDotSize)
    }
}
