package com.auracode.assistant.toolwindow.conversation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.toolwindow.shared.DesignPalette
import kotlin.math.max

private const val RUNNING_PULSE_DURATION_MS: Int = 1180
private const val RUNNING_GLOW_START_SCALE: Float = 1.28f
private const val RUNNING_GLOW_END_SCALE: Float = 1.92f
private const val RUNNING_GLOW_START_ALPHA: Float = 0.28f
private const val RUNNING_GLOW_END_ALPHA: Float = 0f
private const val RUNNING_PULSE_START_SCALE: Float = 1.18f
private const val RUNNING_PULSE_END_SCALE: Float = 2.65f
private const val RUNNING_PULSE_START_ALPHA: Float = 0.78f
private const val RUNNING_PULSE_END_ALPHA: Float = 0f

/**
 * Describes the compact chrome used by one timeline status indicator.
 */
internal data class TimelineStatusIndicatorAppearance(
    val color: Color,
    val containerSize: Dp,
    val coreDotSize: Dp,
    val pulseBorderWidth: Dp,
    val pulseEnabled: Boolean,
    val pulseDurationMs: Int,
    val glowStartScale: Float,
    val glowEndScale: Float,
    val glowStartAlpha: Float,
    val glowEndAlpha: Float,
    val pulseStartScale: Float,
    val pulseEndScale: Float,
    val pulseStartAlpha: Float,
    val pulseEndAlpha: Float,
)

/**
 * Selects whether one indicator should render as a static dot or as a pulsing running marker.
 */
internal enum class TimelineStatusIndicatorRenderMode {
    STATIC,
    PULSE,
}

/**
 * Resolves the indicator appearance so running cards can pulse without shifting layout.
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
    val maxPulseScale = max(RUNNING_GLOW_END_SCALE, RUNNING_PULSE_END_SCALE)
    return TimelineStatusIndicatorAppearance(
        color = resolvedColor,
        containerSize = timelineStatusIndicatorContainerSize(
            dotSize = dotSize,
            maxScale = maxPulseScale,
            pulseBorderWidth = 1.35.dp,
        ),
        coreDotSize = dotSize,
        pulseBorderWidth = 1.35.dp,
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
    )
}

/**
 * Resolves the rendering branch so non-running nodes never allocate infinite animations.
 */
internal fun timelineStatusIndicatorRenderMode(
    appearance: TimelineStatusIndicatorAppearance,
): TimelineStatusIndicatorRenderMode {
    return if (appearance.pulseEnabled) {
        TimelineStatusIndicatorRenderMode.PULSE
    } else {
        TimelineStatusIndicatorRenderMode.STATIC
    }
}

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
    val appearance = timelineStatusIndicatorAppearance(
        status = status,
        palette = palette,
        dotSize = dotSize,
        accentColor = accentColor,
        animatePulse = animatePulse,
    )
    when (timelineStatusIndicatorRenderMode(appearance)) {
        TimelineStatusIndicatorRenderMode.STATIC -> TimelineStaticStatusIndicatorCanvas(
            appearance = appearance,
            modifier = modifier,
        )

        TimelineStatusIndicatorRenderMode.PULSE -> TimelinePulsingStatusIndicatorCanvas(
            appearance = appearance,
            modifier = modifier,
        )
    }
}

/**
 * Draws one static status indicator without creating any animation clock.
 */
@Composable
private fun TimelineStaticStatusIndicatorCanvas(
    appearance: TimelineStatusIndicatorAppearance,
    modifier: Modifier = Modifier,
) {
    TimelineStatusIndicatorCanvas(
        appearance = appearance,
        modifier = modifier,
        pulseProgress = null,
    )
}

/**
 * Draws one animated running indicator and owns the pulse transition lifecycle.
 */
@Composable
private fun TimelinePulsingStatusIndicatorCanvas(
    appearance: TimelineStatusIndicatorAppearance,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "timeline-running-status-indicator")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = appearance.pulseDurationMs,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "timeline-running-status-progress",
    )
    TimelineStatusIndicatorCanvas(
        appearance = appearance,
        modifier = modifier,
        pulseProgress = pulseProgress,
    )
}

/**
 * Draws the visible status dot and, when provided, the current pulse frame.
 */
@Composable
private fun TimelineStatusIndicatorCanvas(
    appearance: TimelineStatusIndicatorAppearance,
    pulseProgress: Float?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.then(Modifier.size(appearance.containerSize))) {
        val center = this.center
        val coreRadius = appearance.coreDotSize.toPx() / 2f
        if (appearance.pulseEnabled && pulseProgress != null) {
            val glowRadius = coreRadius * interpolatedFloat(
                start = appearance.glowStartScale,
                end = appearance.glowEndScale,
                progress = pulseProgress,
            )
            val glowAlpha = interpolatedFloat(
                start = appearance.glowStartAlpha,
                end = appearance.glowEndAlpha,
                progress = pulseProgress,
            )
            val pulseRadius = coreRadius * interpolatedFloat(
                start = appearance.pulseStartScale,
                end = appearance.pulseEndScale,
                progress = pulseProgress,
            )
            val pulseAlpha = interpolatedFloat(
                start = appearance.pulseStartAlpha,
                end = appearance.pulseEndAlpha,
                progress = pulseProgress,
            )
            drawCircle(
                color = appearance.color.copy(alpha = glowAlpha),
                radius = glowRadius,
                center = center,
            )
            drawCircle(
                color = appearance.color.copy(alpha = pulseAlpha),
                radius = pulseRadius,
                center = center,
                style = Stroke(width = appearance.pulseBorderWidth.toPx()),
            )
        }
        drawCircle(
            color = appearance.color,
            radius = coreRadius,
            center = center,
        )
    }
}

/**
 * Linearly interpolates one float so animated appearance values can stay centralized.
 */
private fun interpolatedFloat(
    start: Float,
    end: Float,
    progress: Float,
): Float = start + (end - start) * progress

/**
 * Computes the full visible drawing box so pulse rings never rely on clipped overflow.
 */
private fun timelineStatusIndicatorContainerSize(
    dotSize: Dp,
    maxScale: Float,
    pulseBorderWidth: Dp,
): Dp = dotSize * maxScale + pulseBorderWidth * 2
