package com.auracode.assistant.toolwindow.shared

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
import kotlin.math.max

/**
 * Describes the full rendering contract for one animated status dot.
 */
internal data class AnimatedStatusDotAppearance(
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
 * Selects whether one status dot renders statically or with a running pulse.
 */
internal enum class AnimatedStatusDotRenderMode {
    STATIC,
    PULSE,
}

/**
 * Builds one reusable status-dot appearance from visual parameters only.
 */
internal fun animatedStatusDotAppearance(
    color: Color,
    dotSize: Dp,
    pulseEnabled: Boolean,
    pulseDurationMs: Int,
    glowStartScale: Float,
    glowEndScale: Float,
    glowStartAlpha: Float,
    glowEndAlpha: Float,
    pulseStartScale: Float,
    pulseEndScale: Float,
    pulseStartAlpha: Float,
    pulseEndAlpha: Float,
    pulseBorderWidth: Dp,
): AnimatedStatusDotAppearance {
    val maxPulseScale = max(glowEndScale, pulseEndScale)
    return AnimatedStatusDotAppearance(
        color = color,
        containerSize = animatedStatusDotContainerSize(
            dotSize = dotSize,
            maxScale = maxPulseScale,
            pulseBorderWidth = pulseBorderWidth,
        ),
        coreDotSize = dotSize,
        pulseBorderWidth = pulseBorderWidth,
        pulseEnabled = pulseEnabled,
        pulseDurationMs = pulseDurationMs,
        glowStartScale = glowStartScale,
        glowEndScale = glowEndScale,
        glowStartAlpha = glowStartAlpha,
        glowEndAlpha = glowEndAlpha,
        pulseStartScale = pulseStartScale,
        pulseEndScale = pulseEndScale,
        pulseStartAlpha = pulseStartAlpha,
        pulseEndAlpha = pulseEndAlpha,
    )
}

/**
 * Resolves the rendering branch without forcing callers to reason about animation internals.
 */
internal fun animatedStatusDotRenderMode(
    appearance: AnimatedStatusDotAppearance,
): AnimatedStatusDotRenderMode {
    return if (appearance.pulseEnabled) {
        AnimatedStatusDotRenderMode.PULSE
    } else {
        AnimatedStatusDotRenderMode.STATIC
    }
}

/**
 * Draws one status dot and enables the pulse branch only when the supplied appearance requires it.
 */
@Composable
internal fun AnimatedStatusDot(
    appearance: AnimatedStatusDotAppearance,
    modifier: Modifier = Modifier,
) {
    when (animatedStatusDotRenderMode(appearance)) {
        AnimatedStatusDotRenderMode.STATIC -> StaticAnimatedStatusDotCanvas(
            appearance = appearance,
            modifier = modifier,
        )

        AnimatedStatusDotRenderMode.PULSE -> PulsingAnimatedStatusDotCanvas(
            appearance = appearance,
            modifier = modifier,
        )
    }
}

/**
 * Draws one static status dot without allocating an infinite animation clock.
 */
@Composable
private fun StaticAnimatedStatusDotCanvas(
    appearance: AnimatedStatusDotAppearance,
    modifier: Modifier = Modifier,
) {
    AnimatedStatusDotCanvas(
        appearance = appearance,
        modifier = modifier,
        pulseProgress = null,
    )
}

/**
 * Draws one pulsing status dot and owns the pulse transition lifecycle.
 */
@Composable
private fun PulsingAnimatedStatusDotCanvas(
    appearance: AnimatedStatusDotAppearance,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "animated-status-dot")
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
        label = "animated-status-dot-progress",
    )
    AnimatedStatusDotCanvas(
        appearance = appearance,
        modifier = modifier,
        pulseProgress = pulseProgress,
    )
}

/**
 * Draws the visible status dot and, when enabled, the current pulse frame.
 */
@Composable
private fun AnimatedStatusDotCanvas(
    appearance: AnimatedStatusDotAppearance,
    pulseProgress: Float?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.then(Modifier.size(appearance.containerSize))) {
        val center = this.center
        val coreRadius = appearance.coreDotSize.toPx() / 2f
        if (appearance.pulseEnabled && pulseProgress != null) {
            val glowRadius = coreRadius * interpolatedStatusDotFloat(
                start = appearance.glowStartScale,
                end = appearance.glowEndScale,
                progress = pulseProgress,
            )
            val glowAlpha = interpolatedStatusDotFloat(
                start = appearance.glowStartAlpha,
                end = appearance.glowEndAlpha,
                progress = pulseProgress,
            )
            val pulseRadius = coreRadius * interpolatedStatusDotFloat(
                start = appearance.pulseStartScale,
                end = appearance.pulseEndScale,
                progress = pulseProgress,
            )
            val pulseAlpha = interpolatedStatusDotFloat(
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
 * Linearly interpolates one animated float value.
 */
private fun interpolatedStatusDotFloat(
    start: Float,
    end: Float,
    progress: Float,
): Float = start + (end - start) * progress

/**
 * Computes the drawing box so pulse rings stay visible without layout drift.
 */
private fun animatedStatusDotContainerSize(
    dotSize: Dp,
    maxScale: Float,
    pulseBorderWidth: Dp,
): Dp = dotSize * maxScale + pulseBorderWidth * 2

/**
 * Exposes the default border width used by the timeline running pulse.
 */
internal fun defaultAnimatedStatusDotPulseBorderWidth(): Dp = 1.35.dp
