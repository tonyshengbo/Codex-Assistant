package com.auracode.assistant.toolwindow.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.toolwindow.shared.AnimatedStatusDot
import com.auracode.assistant.toolwindow.shared.AnimatedStatusDotAppearance
import com.auracode.assistant.toolwindow.shared.AnimatedStatusDotRenderMode
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.animatedStatusDotAppearance
import com.auracode.assistant.toolwindow.shared.animatedStatusDotRenderMode
import com.auracode.assistant.toolwindow.shared.defaultAnimatedStatusDotPulseBorderWidth

private const val RUNNING_PULSE_DURATION_MS: Int = 1180
private const val RUNNING_GLOW_START_SCALE: Float = 1.28f
private const val RUNNING_GLOW_END_SCALE: Float = 1.92f
private const val RUNNING_GLOW_START_ALPHA: Float = 0.28f
private const val RUNNING_GLOW_END_ALPHA: Float = 0f
private const val RUNNING_PULSE_START_SCALE: Float = 1.18f
private const val RUNNING_PULSE_END_SCALE: Float = 2.65f
private const val RUNNING_PULSE_START_ALPHA: Float = 0.78f
private const val RUNNING_PULSE_END_ALPHA: Float = 0f

internal typealias TimelineStatusIndicatorAppearance = AnimatedStatusDotAppearance
internal typealias TimelineStatusIndicatorRenderMode = AnimatedStatusDotRenderMode

/**
 * Resolves the conversation status-dot appearance while preserving timeline-specific status mapping.
 */
internal fun timelineStatusIndicatorAppearance(
    status: ItemStatus,
    palette: DesignPalette,
    dotSize: Dp,
    accentColor: Color? = null,
    animatePulse: Boolean = true,
): TimelineStatusIndicatorAppearance {
    val resolvedColor = accentColor ?: when (status) {
        ItemStatus.FAILED -> palette.danger
        ItemStatus.RUNNING -> palette.accent
        else -> palette.success
    }
    return animatedStatusDotAppearance(
        color = resolvedColor,
        dotSize = dotSize,
        pulseEnabled = status == ItemStatus.RUNNING && animatePulse,
        pulseDurationMs = RUNNING_PULSE_DURATION_MS,
        glowStartScale = RUNNING_GLOW_START_SCALE,
        glowEndScale = RUNNING_GLOW_END_SCALE,
        glowStartAlpha = RUNNING_GLOW_START_ALPHA,
        glowEndAlpha = RUNNING_GLOW_END_ALPHA,
        pulseStartScale = RUNNING_PULSE_START_SCALE,
        pulseEndScale = RUNNING_PULSE_END_SCALE,
        pulseStartAlpha = RUNNING_PULSE_START_ALPHA,
        pulseEndAlpha = RUNNING_PULSE_END_ALPHA,
        pulseBorderWidth = defaultAnimatedStatusDotPulseBorderWidth(),
    )
}

/**
 * Resolves the conversation-specific render mode from the shared animated status-dot appearance.
 */
internal fun timelineStatusIndicatorRenderMode(
    appearance: TimelineStatusIndicatorAppearance,
): TimelineStatusIndicatorRenderMode = animatedStatusDotRenderMode(appearance)

/**
 * Renders the right-edge timeline status indicator with an optional running pulse ring.
 */
@Composable
internal fun TimelineStatusIndicator(
    status: ItemStatus,
    palette: DesignPalette,
    dotSize: Dp,
    accentColor: Color? = null,
    animatePulse: Boolean = true,
    modifier: Modifier = Modifier,
) {
    AnimatedStatusDot(
        appearance = timelineStatusIndicatorAppearance(
            status = status,
            palette = palette,
            dotSize = dotSize,
            accentColor = accentColor,
            animatePulse = animatePulse,
        ),
        modifier = modifier,
    )
}
