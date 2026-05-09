package com.auracode.assistant.toolwindow.submission

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.shared.EffectiveTheme
import com.auracode.assistant.toolwindow.shared.assistantPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubmissionRunningPlanSectionTest {
    @Test
    fun `toggle icon uses local arrow up while collapsed`() {
        assertEquals("/icons/arrow-up.svg", runningPlanToggleIconPath(expanded = false))
        assertEquals("/icons/arrow-down.svg", runningPlanToggleIconPath(expanded = true))
    }

    @Test
    fun `header summary prefers in progress step and reports progress`() {
        val summary = runningPlanHeaderSummary(
            SubmissionRunningPlanState(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = "Executing plan",
                steps = listOf(
                    SubmissionRunningPlanStep(step = "Inspect logs", status = SubmissionRunningPlanStepStatus.COMPLETED),
                    SubmissionRunningPlanStep(step = "Update UI", status = SubmissionRunningPlanStepStatus.IN_PROGRESS),
                    SubmissionRunningPlanStep(step = "Verify tests", status = SubmissionRunningPlanStepStatus.PENDING),
                ),
            ),
        )

        assertEquals("Running plan", summary.title)
        assertEquals("Update UI", summary.currentStep)
        assertEquals("2/3", summary.progressLabel)
    }

    @Test
    fun `header summary shows step 1 of N when first step is in progress`() {
        val summary = runningPlanHeaderSummary(
            SubmissionRunningPlanState(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = "Executing plan",
                steps = listOf(
                    SubmissionRunningPlanStep(step = "Inspect logs", status = SubmissionRunningPlanStepStatus.IN_PROGRESS),
                    SubmissionRunningPlanStep(step = "Update UI", status = SubmissionRunningPlanStepStatus.PENDING),
                    SubmissionRunningPlanStep(step = "Verify tests", status = SubmissionRunningPlanStepStatus.PENDING),
                ),
            ),
        )

        assertEquals("Inspect logs", summary.currentStep)
        assertEquals("1/3", summary.progressLabel)
    }

    @Test
    fun `header summary falls back to first pending step when nothing is running`() {
        val summary = runningPlanHeaderSummary(
            SubmissionRunningPlanState(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = null,
                steps = listOf(
                    SubmissionRunningPlanStep(step = "Inspect logs", status = SubmissionRunningPlanStepStatus.COMPLETED),
                    SubmissionRunningPlanStep(step = "Patch UI", status = SubmissionRunningPlanStepStatus.PENDING),
                    SubmissionRunningPlanStep(step = "Verify tests", status = SubmissionRunningPlanStepStatus.PENDING),
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

    /**
     * Verifies that the running-plan pulse uses a compact footprint while preserving accent semantics.
     */
    @Test
    fun `in progress dot appearance stays compact and animated`() {
        val palette = assistantPalette(EffectiveTheme.DARK)

        val appearance = runningPlanInProgressDotAppearance(palette = palette)

        assertEquals(palette.accent, appearance.color)
        assertEquals(7.dp * 1.92f + 1.dp * 2, appearance.containerSize)
        assertEquals(7.dp, appearance.coreDotSize)
        assertEquals(1.dp, appearance.pulseBorderWidth)
        assertTrue(appearance.pulseEnabled)
        assertEquals(1180, appearance.pulseDurationMs)
        assertEquals(1.12f, appearance.glowStartScale)
        assertEquals(1.52f, appearance.glowEndScale)
        assertEquals(1.08f, appearance.pulseStartScale)
        assertEquals(1.92f, appearance.pulseEndScale)
    }

    /**
     * Verifies that only in-progress steps opt into the animated running indicator.
     */
    @Test
    fun `only in progress steps use animated indicator`() {
        assertTrue(runningPlanUsesAnimatedIndicator(SubmissionRunningPlanStepStatus.IN_PROGRESS))
        assertFalse(runningPlanUsesAnimatedIndicator(SubmissionRunningPlanStepStatus.COMPLETED))
        assertFalse(runningPlanUsesAnimatedIndicator(SubmissionRunningPlanStepStatus.PENDING))
    }

    /**
     * Verifies that the compact appearance can still render statically when animation is gated off.
     */
    @Test
    fun `in progress dot appearance can disable pulse when gated off`() {
        val palette = assistantPalette(EffectiveTheme.DARK)

        val appearance = runningPlanInProgressDotAppearance(
            palette = palette,
            animatePulse = false,
        )

        assertEquals(palette.accent, appearance.color)
        assertFalse(appearance.pulseEnabled)
    }
}
