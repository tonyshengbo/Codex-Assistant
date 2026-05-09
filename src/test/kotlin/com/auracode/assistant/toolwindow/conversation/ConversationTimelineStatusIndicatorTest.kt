package com.auracode.assistant.toolwindow.conversation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.toolwindow.shared.EffectiveTheme
import com.auracode.assistant.toolwindow.shared.assistantPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the compact appearance rules used by the timeline running status indicator.
 */
class ConversationTimelineStatusIndicatorTest {
    /**
     * Verifies that running status keeps the accent color and enables the pulse ring.
     */
    @Test
    fun `running status indicator enables pulse and keeps accent color`() {
        val palette = assistantPalette(EffectiveTheme.DARK)

        val appearance = timelineStatusIndicatorAppearance(
            status = ItemStatus.RUNNING,
            palette = palette,
            dotSize = 8.dp,
        )

        assertEquals(palette.accent, appearance.color)
        assertEquals(8.dp * 2.65f + 1.35.dp * 2, appearance.containerSize)
        assertEquals(8.dp, appearance.coreDotSize)
        assertTrue(appearance.pulseEnabled)
        assertEquals(1180, appearance.pulseDurationMs)
        assertEquals(1.28f, appearance.glowStartScale)
        assertEquals(1.92f, appearance.glowEndScale)
        assertEquals(0.28f, appearance.glowStartAlpha)
        assertEquals(0f, appearance.glowEndAlpha)
        assertEquals(1.18f, appearance.pulseStartScale)
        assertEquals(2.65f, appearance.pulseEndScale)
        assertEquals(0.78f, appearance.pulseStartAlpha)
        assertEquals(0f, appearance.pulseEndAlpha)
        assertEquals(TimelineStatusIndicatorRenderMode.PULSE, timelineStatusIndicatorRenderMode(appearance))
    }

    /**
     * Verifies that non-running statuses stay static while preserving their mapped color.
     */
    @Test
    fun `non running status indicators stay static with mapped colors`() {
        val palette = assistantPalette(EffectiveTheme.LIGHT)

        val successAppearance = timelineStatusIndicatorAppearance(
            status = ItemStatus.SUCCESS,
            palette = palette,
            dotSize = 8.dp,
        )
        val failedAppearance = timelineStatusIndicatorAppearance(
            status = ItemStatus.FAILED,
            palette = palette,
            dotSize = 8.dp,
        )
        val skippedAppearance = timelineStatusIndicatorAppearance(
            status = ItemStatus.SKIPPED,
            palette = palette,
            dotSize = 8.dp,
        )

        assertFalse(successAppearance.pulseEnabled)
        assertFalse(failedAppearance.pulseEnabled)
        assertFalse(skippedAppearance.pulseEnabled)
        assertEquals(palette.success, successAppearance.color)
        assertEquals(palette.danger, failedAppearance.color)
        assertEquals(palette.success, skippedAppearance.color)
        assertEquals(TimelineStatusIndicatorRenderMode.STATIC, timelineStatusIndicatorRenderMode(successAppearance))
        assertEquals(TimelineStatusIndicatorRenderMode.STATIC, timelineStatusIndicatorRenderMode(failedAppearance))
        assertEquals(TimelineStatusIndicatorRenderMode.STATIC, timelineStatusIndicatorRenderMode(skippedAppearance))
    }

    /**
     * Verifies that explicit accent overrides still win without changing pulse eligibility.
     */
    @Test
    fun `accent override changes color but not pulse rule`() {
        val palette = assistantPalette(EffectiveTheme.DARK)
        val overrideColor = Color(0xFF7AC7FF)

        val successAppearance = timelineStatusIndicatorAppearance(
            status = ItemStatus.SUCCESS,
            palette = palette,
            dotSize = 8.dp,
            accentColor = overrideColor,
        )
        val runningAppearance = timelineStatusIndicatorAppearance(
            status = ItemStatus.RUNNING,
            palette = palette,
            dotSize = 8.dp,
            accentColor = overrideColor,
        )

        assertEquals(overrideColor, successAppearance.color)
        assertEquals(overrideColor, runningAppearance.color)
        assertFalse(successAppearance.pulseEnabled)
        assertTrue(runningAppearance.pulseEnabled)
    }

    /**
     * Verifies that session-level gating can stop the pulse without changing running-state color mapping.
     */
    @Test
    fun `running status indicator can disable pulse when animation is gated off`() {
        val palette = assistantPalette(EffectiveTheme.DARK)

        val appearance = timelineStatusIndicatorAppearance(
            status = ItemStatus.RUNNING,
            palette = palette,
            dotSize = 8.dp,
            animatePulse = false,
        )

        assertEquals(palette.accent, appearance.color)
        assertFalse(appearance.pulseEnabled)
        assertEquals(TimelineStatusIndicatorRenderMode.STATIC, timelineStatusIndicatorRenderMode(appearance))
    }
}
