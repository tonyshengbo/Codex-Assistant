package com.auracode.assistant.toolwindow.submission

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.AnimatedStatusDot
import com.auracode.assistant.toolwindow.shared.AnimatedStatusDotAppearance
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.animatedStatusDotAppearance
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

private const val RUNNING_PLAN_PULSE_DURATION_MS: Int = 1180
private const val RUNNING_PLAN_GLOW_START_SCALE: Float = 1.12f
private const val RUNNING_PLAN_GLOW_END_SCALE: Float = 1.52f
private const val RUNNING_PLAN_GLOW_START_ALPHA: Float = 0.24f
private const val RUNNING_PLAN_GLOW_END_ALPHA: Float = 0f
private const val RUNNING_PLAN_PULSE_START_SCALE: Float = 1.08f
private const val RUNNING_PLAN_PULSE_END_SCALE: Float = 1.92f
private const val RUNNING_PLAN_PULSE_START_ALPHA: Float = 0.7f
private const val RUNNING_PLAN_PULSE_END_ALPHA: Float = 0f

/**
 * Collects the compact visual chrome used by the running plan progress badge.
 */
internal data class RunningPlanProgressBadgeChrome(
    val horizontalPadding: androidx.compose.ui.unit.Dp,
    val verticalPadding: androidx.compose.ui.unit.Dp,
    val minWidth: androidx.compose.ui.unit.Dp,
    val minHeight: androidx.compose.ui.unit.Dp,
    val cornerRadius: androidx.compose.ui.unit.Dp,
    val backgroundAlpha: Float,
    val borderAlpha: Float,
)

/**
 * Describes the step-row alignment so the status dot stays vertically centered with the label.
 */
internal data class RunningPlanStepRowSpec(
    val rowVerticalAlignment: Alignment.Vertical,
    val dotTopPadding: androidx.compose.ui.unit.Dp,
    val inactiveDotSize: androidx.compose.ui.unit.Dp,
    val activeDotSize: androidx.compose.ui.unit.Dp,
)

internal data class RunningPlanHeaderSummary(
    val title: String,
    val currentStep: String,
    val progressLabel: String,
)

/**
 * Resolves the local icon resource for the expand/collapse affordance.
 */
internal fun runningPlanToggleIconPath(expanded: Boolean): String {
    return if (expanded) "/icons/arrow-down.svg" else "/icons/arrow-up.svg"
}

/**
 * Builds the compact summary content shown in the running-plan header.
 */
internal fun runningPlanHeaderSummary(state: SubmissionRunningPlanState): RunningPlanHeaderSummary {
    val totalCount = state.steps.size
    val completedCount = state.steps.count { it.status == SubmissionRunningPlanStepStatus.COMPLETED }
    val hasInProgress = state.steps.any { it.status == SubmissionRunningPlanStepStatus.IN_PROGRESS }
    val currentStep = state.steps.firstOrNull { it.status == SubmissionRunningPlanStepStatus.IN_PROGRESS }
        ?: state.steps.firstOrNull { it.status == SubmissionRunningPlanStepStatus.PENDING }
        ?: state.steps.lastOrNull()
    // 有正在执行的步骤时，显示当前步骤序号（completedCount + 1），否则显示已完成数
    val displayCount = if (hasInProgress) completedCount + 1 else completedCount
    return RunningPlanHeaderSummary(
        title = AuraCodeBundle.message("submission.runningPlan.title"),
        currentStep = currentStep?.step.orEmpty(),
        progressLabel = if (totalCount > 0) "$displayCount/$totalCount" else "",
    )
}

/**
 * Keeps the progress badge centered, compact, and slightly brighter than the surrounding chrome.
 */
internal fun runningPlanProgressBadgeChrome(): RunningPlanProgressBadgeChrome {
    return RunningPlanProgressBadgeChrome(
        horizontalPadding = 5.dp,
        verticalPadding = 0.dp,
        minWidth = 34.dp,
        minHeight = 18.dp,
        cornerRadius = 999.dp,
        backgroundAlpha = 0.16f,
        borderAlpha = 0.24f,
    )
}

/**
 * Keeps step dots aligned to the label center without changing the surrounding structure.
 */
internal fun runningPlanStepRowSpec(): RunningPlanStepRowSpec {
    return RunningPlanStepRowSpec(
        rowVerticalAlignment = Alignment.CenterVertically,
        dotTopPadding = 0.dp,
        inactiveDotSize = 6.dp,
        activeDotSize = 7.dp,
    )
}

/**
 * Builds the compact running-plan appearance so the pulse reads like timeline while preserving row density.
 */
internal fun runningPlanInProgressDotAppearance(
    palette: DesignPalette,
    dotSize: Dp = runningPlanStepRowSpec().activeDotSize,
    animatePulse: Boolean = true,
): AnimatedStatusDotAppearance {
    return animatedStatusDotAppearance(
        color = palette.accent,
        dotSize = dotSize,
        pulseEnabled = animatePulse,
        pulseDurationMs = RUNNING_PLAN_PULSE_DURATION_MS,
        glowStartScale = RUNNING_PLAN_GLOW_START_SCALE,
        glowEndScale = RUNNING_PLAN_GLOW_END_SCALE,
        glowStartAlpha = RUNNING_PLAN_GLOW_START_ALPHA,
        glowEndAlpha = RUNNING_PLAN_GLOW_END_ALPHA,
        pulseStartScale = RUNNING_PLAN_PULSE_START_SCALE,
        pulseEndScale = RUNNING_PLAN_PULSE_END_SCALE,
        pulseStartAlpha = RUNNING_PLAN_PULSE_START_ALPHA,
        pulseEndAlpha = RUNNING_PLAN_PULSE_END_ALPHA,
        pulseBorderWidth = 1.dp,
    )
}

/**
 * Identifies whether one running-plan step should render the animated running indicator.
 */
internal fun runningPlanUsesAnimatedIndicator(
    status: SubmissionRunningPlanStepStatus,
): Boolean = status == SubmissionRunningPlanStepStatus.IN_PROGRESS

@Composable
internal fun SubmissionRunningPlanSection(
    p: DesignPalette,
    state: SubmissionRunningPlanState,
    expanded: Boolean,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val summary = runningPlanHeaderSummary(state)
    val stepRowSpec = runningPlanStepRowSpec()
    val containerShape = RoundedCornerShape(12.dp)
    val bodyShape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.timelineCardBg.copy(alpha = 0.42f), containerShape)
            .border(1.dp, p.markdownDivider.copy(alpha = 0.4f), containerShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = if (expanded) p.timelineCardBg.copy(alpha = 0.14f) else p.timelineCardBg.copy(alpha = 0.08f),
                    shape = if (expanded) RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp) else containerShape,
                )
                .clickable { onIntent(UiIntent.ToggleRunningPlanExpanded) }
                .padding(horizontal = t.spacing.md - 1.dp, vertical = 2.dp)
                .defaultMinSize(minHeight = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary.title,
                color = p.textSecondary,
                fontSize = t.type.meta,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            if (summary.currentStep.isNotBlank()) {
                Text(
                    text = summary.currentStep,
                    color = if (expanded) p.textSecondary else p.textPrimary,
                    fontSize = t.type.label,
                    fontWeight = if (expanded) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            RunningPlanProgressBadge(p = p, label = summary.progressLabel)
            Box(
                modifier = Modifier.width(16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    painter = painterResource(runningPlanToggleIconPath(expanded)),
                    contentDescription = if (expanded) {
                        AuraCodeBundle.message("timeline.collapse")
                    } else {
                        AuraCodeBundle.message("timeline.expand")
                    },
                    tint = p.textMuted,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(durationMillis = 150)) + expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(durationMillis = 170),
            ),
            exit = fadeOut(animationSpec = tween(durationMillis = 120)) + shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(durationMillis = 140),
            ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.spacing.md - 1.dp)
                    .height(1.dp)
                    .background(p.markdownDivider.copy(alpha = 0.28f)),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(p.timelineCardBg.copy(alpha = 0.04f), bodyShape)
                    .padding(horizontal = t.spacing.md - 1.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.steps.forEach { step ->
                    val isActive = step.status == SubmissionRunningPlanStepStatus.IN_PROGRESS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isActive) {
                                    Modifier
                                        .background(p.accent.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                } else {
                                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                },
                            ),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = stepRowSpec.rowVerticalAlignment,
                    ) {
                        if (runningPlanUsesAnimatedIndicator(step.status)) {
                            RunningPlanAnimatedIndicator(
                                appearance = runningPlanInProgressDotAppearance(palette = p),
                                reservedDotSize = stepRowSpec.activeDotSize,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .padding(top = stepRowSpec.dotTopPadding)
                                    .size(stepRowSpec.inactiveDotSize)
                                    .background(step.status.dotColor(p), CircleShape),
                            )
                        }
                        Text(
                            text = step.step,
                            color = step.status.textColor(p),
                            fontSize = t.type.meta,
                            fontWeight = if (step.status == SubmissionRunningPlanStepStatus.IN_PROGRESS) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * Renders the animated running-plan indicator while preserving the original dot slot width.
 */
@Composable
private fun RunningPlanAnimatedIndicator(
    appearance: AnimatedStatusDotAppearance,
    reservedDotSize: Dp,
) {
    AnimatedStatusDot(
        appearance = appearance,
        modifier = Modifier.layout { measurable, _ ->
            val reservedDotSizePx = reservedDotSize.roundToPx()
            val containerSizePx = appearance.containerSize.roundToPx()
            val placeable = measurable.measure(
                Constraints.fixed(
                    containerSizePx,
                    containerSizePx,
                ),
            )
            layout(
                width = reservedDotSizePx,
                height = reservedDotSizePx,
            ) {
                placeable.placeRelative(
                    x = (reservedDotSizePx - placeable.width) / 2,
                    y = (reservedDotSizePx - placeable.height) / 2,
                )
            }
        },
    )
}

@Composable
private fun RowScope.RunningPlanProgressBadge(
    p: DesignPalette,
    label: String,
) {
    if (label.isBlank()) return
    val chrome = runningPlanProgressBadgeChrome()
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = chrome.minWidth, minHeight = chrome.minHeight)
            .background(p.topStripBg.copy(alpha = chrome.backgroundAlpha), RoundedCornerShape(chrome.cornerRadius))
            .border(1.dp, p.markdownDivider.copy(alpha = chrome.borderAlpha), RoundedCornerShape(chrome.cornerRadius))
            .padding(horizontal = chrome.horizontalPadding, vertical = chrome.verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = p.textSecondary,
            style = MaterialTheme.typography.overline.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

private fun SubmissionRunningPlanStepStatus.textColor(p: DesignPalette) = when (this) {
    SubmissionRunningPlanStepStatus.COMPLETED -> p.textSecondary
    SubmissionRunningPlanStepStatus.IN_PROGRESS -> p.textPrimary
    SubmissionRunningPlanStepStatus.PENDING -> p.textMuted
}

private fun SubmissionRunningPlanStepStatus.dotColor(p: DesignPalette) = when (this) {
    SubmissionRunningPlanStepStatus.COMPLETED -> p.success
    SubmissionRunningPlanStepStatus.IN_PROGRESS -> p.accent
    SubmissionRunningPlanStepStatus.PENDING -> p.textMuted
}
