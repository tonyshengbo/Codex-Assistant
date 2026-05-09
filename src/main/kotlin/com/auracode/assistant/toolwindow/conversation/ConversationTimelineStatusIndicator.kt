package com.auracode.assistant.toolwindow.conversation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.toolwindow.shared.DesignPalette

private const val RUNNING_PULSE_DURATION_MS: Int = 1400
private const val RUNNING_PULSE_START_SCALE: Float = 1.15f
private const val RUNNING_PULSE_END_SCALE: Float = 2.2f
private const val RUNNING_PULSE_START_ALPHA: Float = 0.34f
private const val RUNNING_PULSE_END_ALPHA: Float = 0f

/**
 * Describes the compact chrome used by one timeline status indicator.
 */
internal data class TimelineStatusIndicatorAppearance(
    val color: Color,
    val containerSize: Dp,
    val coreDotSize: Dp,
    val pulseRingSize: Dp,
    val pulseBorderWidth: Dp,
    val pulseEnabled: Boolean,
    val pulseDurationMs: Int,
    val pulseStartScale: Float,
    val pulseEndScale: Float,
    val pulseStartAlpha: Float,
    val pulseEndAlpha: Float,
)

/**
 * Resolves the indicator appearance so running cards can pulse without shifting layout.
 */
internal fun timelineStatusIndicatorAppearance(
    status: ItemStatus,
    palette: DesignPalette,
    dotSize: Dp,
    accentColor: Color? = null,
): TimelineStatusIndicatorAppearance {
    val resolvedColor = accentColor ?: when (status) {
        ItemStatus.FAILED -> palette.danger
        ItemStatus.RUNNING -> palette.accent
        else -> palette.success
    }
    return TimelineStatusIndicatorAppearance(
        color = resolvedColor,
        containerSize = dotSize,
        coreDotSize = dotSize,
        pulseRingSize = dotSize,
        pulseBorderWidth = 1.dp,
        pulseEnabled = status == ItemStatus.RUNNING,
        pulseDurationMs = RUNNING_PULSE_DURATION_MS,
        pulseStartScale = RUNNING_PULSE_START_SCALE,
        pulseEndScale = RUNNING_PULSE_END_SCALE,
        pulseStartAlpha = RUNNING_PULSE_START_ALPHA,
        pulseEndAlpha = RUNNING_PULSE_END_ALPHA,
    )
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
    modifier: Modifier = Modifier,
) {
    val appearance = timelineStatusIndicatorAppearance(
        status = status,
        palette = palette,
        dotSize = dotSize,
        accentColor = accentColor,
    )
    Box(
        modifier = modifier.size(appearance.containerSize),
        contentAlignment = Alignment.Center,
    ) {
        if (appearance.pulseEnabled) {
            val infiniteTransition = rememberInfiniteTransition(label = "timeline-running-status-indicator")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = appearance.pulseStartScale,
                targetValue = appearance.pulseEndScale,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = appearance.pulseDurationMs,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "timeline-running-status-scale",
            )
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = appearance.pulseStartAlpha,
                targetValue = appearance.pulseEndAlpha,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = appearance.pulseDurationMs,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "timeline-running-status-alpha",
            )
            Box(
                modifier = Modifier
                    .size(appearance.pulseRingSize)
                    .graphicsLayer(alpha = pulseAlpha)
                    .scale(pulseScale)
                    .border(
                        width = appearance.pulseBorderWidth,
                        color = appearance.color,
                        shape = CircleShape,
                    ),
            )
        }
        Box(
            modifier = Modifier
                .size(appearance.coreDotSize)
                .background(appearance.color, CircleShape),
        )
    }
}
