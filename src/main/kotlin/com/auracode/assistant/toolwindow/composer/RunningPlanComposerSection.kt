package com.auracode.assistant.toolwindow.composer

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
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

internal data class RunningPlanHeaderSummary(
    val title: String,
    val currentStep: String,
    val progressLabel: String,
)

internal fun runningPlanToggleIconPath(expanded: Boolean): String {
    return if (expanded) "/icons/arrow-down.svg" else "/icons/arrow-up.svg"
}

internal fun runningPlanHeaderSummary(state: ComposerRunningPlanState): RunningPlanHeaderSummary {
    val totalCount = state.steps.size
    val completedCount = state.steps.count { it.status == ComposerRunningPlanStepStatus.COMPLETED }
    val currentStep = state.steps.firstOrNull { it.status == ComposerRunningPlanStepStatus.IN_PROGRESS }
        ?: state.steps.firstOrNull { it.status == ComposerRunningPlanStepStatus.PENDING }
        ?: state.steps.lastOrNull()
    return RunningPlanHeaderSummary(
        title = "Running plan",
        currentStep = currentStep?.step.orEmpty(),
        progressLabel = if (totalCount > 0) "$completedCount/$totalCount" else "",
    )
}

@Composable
internal fun RunningPlanComposerSection(
    p: DesignPalette,
    state: ComposerRunningPlanState,
    expanded: Boolean,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val summary = runningPlanHeaderSummary(state)
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
                .padding(horizontal = t.spacing.md - 1.dp, vertical = 6.dp)
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .size(if (step.status == ComposerRunningPlanStepStatus.IN_PROGRESS) 7.dp else 6.dp)
                                .background(step.status.dotColor(p), CircleShape),
                        )
                        Text(
                            text = step.step,
                            color = step.status.textColor(p),
                            fontSize = t.type.meta,
                            fontWeight = if (step.status == ComposerRunningPlanStepStatus.IN_PROGRESS) FontWeight.SemiBold else FontWeight.Normal,
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

@Composable
private fun RowScope.RunningPlanProgressBadge(
    p: DesignPalette,
    label: String,
) {
    if (label.isBlank()) return
    val t = assistantUiTokens()
    Box(
        modifier = Modifier
            .background(p.accent.copy(alpha = 0.1f), RoundedCornerShape(999.dp))
            .border(1.dp, p.accent.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            color = p.textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private fun ComposerRunningPlanStepStatus.textColor(p: DesignPalette) = when (this) {
    ComposerRunningPlanStepStatus.COMPLETED -> p.textSecondary
    ComposerRunningPlanStepStatus.IN_PROGRESS -> p.textPrimary
    ComposerRunningPlanStepStatus.PENDING -> p.textMuted
}

private fun ComposerRunningPlanStepStatus.dotColor(p: DesignPalette) = when (this) {
    ComposerRunningPlanStepStatus.COMPLETED -> p.success
    ComposerRunningPlanStepStatus.IN_PROGRESS -> p.accent
    ComposerRunningPlanStepStatus.PENDING -> p.textMuted
}
