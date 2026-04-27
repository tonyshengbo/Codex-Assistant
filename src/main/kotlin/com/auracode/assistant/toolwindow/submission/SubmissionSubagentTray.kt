package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/**
 * Renders the lightweight per-session subagent tray above the composer input.
 */
@Composable
internal fun SubmissionSubagentTray(
    p: DesignPalette,
    state: SubmissionAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    if (!state.subagentTrayVisible) return
    val t = assistantUiTokens()
    val accentColor = subagentTrayAccentColor(state = state, p = p)
    val headerText = buildHeaderText(state.sessionSubagents)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.timelineCardBg, RoundedCornerShape(t.spacing.sm))
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = if (hasUrgentSubagent(state.sessionSubagents)) 0.26f else 0.14f),
                shape = RoundedCornerShape(t.spacing.sm),
            )
            .padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onIntent(UiIntent.ToggleSubagentTrayExpanded) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource("/icons/community.svg"),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(t.controls.iconMd),
                )
                Spacer(Modifier.width(t.spacing.sm))
                Text(
                    text = headerText,
                    color = p.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.material.MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
                )
            }
            Spacer(Modifier.width(t.spacing.sm))
            HoverTooltip(text = AuraCodeBundle.message("common.close")) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onIntent(UiIntent.DismissSubagentTray) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource("/icons/close-small.svg"),
                        contentDescription = AuraCodeBundle.message("common.close"),
                        tint = p.textMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Spacer(Modifier.width(t.spacing.xs))
            HoverTooltip(
                text = AuraCodeBundle.message(
                    if (state.subagentTrayExpanded) "timeline.collapse" else "timeline.expand",
                ),
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onIntent(UiIntent.ToggleSubagentTrayExpanded) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(if (state.subagentTrayExpanded) "/icons/arrow-up.svg" else "/icons/arrow-down.svg"),
                        contentDescription = null,
                        tint = p.textMuted,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        if (state.subagentTrayExpanded) {
            state.sessionSubagents.forEach { subagent ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (state.selectedSubagentThreadId == subagent.threadId) {
                                    p.submissionBg.copy(alpha = 0.92f)
                                } else {
                                    p.submissionBg
                                },
                                shape = RoundedCornerShape(t.spacing.sm),
                            )
                            .clickable { onIntent(UiIntent.ToggleSubagentDetails(subagent.threadId)) }
                            .padding(horizontal = t.spacing.sm, vertical = t.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(status = subagent.status, p = p)
                        Spacer(Modifier.width(t.spacing.sm))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
                        ) {
                            Text(
                                text = subagent.displayName,
                                color = p.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = androidx.compose.material.MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
                            )
                        }
                        Spacer(Modifier.width(t.spacing.sm))
                        Text(
                            text = localizedSubagentState(subagent.status),
                            color = subagentStatusColor(status = subagent.status, p = p),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = androidx.compose.material.MaterialTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
                        )
                    }
                    if (state.selectedSubagentThreadId == subagent.threadId) {
                        SubmissionSubagentDetailCard(
                            p = p,
                            subagent = subagent,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Builds the compact tray header so the collapsed state fits into a single line.
 */
private fun buildHeaderText(subagents: List<SessionSubagentUiModel>): String {
    val countText = AuraCodeBundle.message("composer.subagents.summary", subagents.size)
    val summaryText = buildCollapsedSummary(subagents)
    return AuraCodeBundle.message("composer.subagents.header", countText, summaryText)
}

/**
 * Builds the collapsed status summary from the highest-priority subagent states.
 */
private fun buildCollapsedSummary(subagents: List<SessionSubagentUiModel>): String {
    val primary = subagents.firstOrNull()
    if (primary == null) {
        return AuraCodeBundle.message("composer.subagents.summary.none")
    }
    val detail = primary.summary?.takeIf { it.isNotBlank() } ?: localizedSubagentState(primary.status)
    return "${primary.displayName}: $detail"
}

/**
 * Returns true when the tray should use the stronger active/failed accent treatment.
 */
private fun hasUrgentSubagent(subagents: List<SessionSubagentUiModel>): Boolean {
    return subagents.any { subagent ->
        subagent.status == SessionSubagentStatus.ACTIVE || subagent.status == SessionSubagentStatus.FAILED
    }
}

/**
 * Renders a compact status dot so the tray stays lightweight without extra icon assets.
 */
@Composable
private fun StatusDot(
    status: SessionSubagentStatus,
    p: DesignPalette,
) {
    val t = assistantUiTokens()
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(t.controls.iconSm)
            .background(
                color = subagentStatusColor(status = status, p = p),
                shape = RoundedCornerShape(percent = 50),
            ),
    )
}

/**
 * Picks the tray accent from the most urgent visible subagent so active and failed sessions stand out.
 */
private fun subagentTrayAccentColor(
    state: SubmissionAreaState,
    p: DesignPalette,
): androidx.compose.ui.graphics.Color {
    return when (state.sessionSubagents.firstOrNull()?.status) {
        SessionSubagentStatus.FAILED -> p.danger
        SessionSubagentStatus.ACTIVE -> p.accent
        SessionSubagentStatus.PENDING -> p.textSecondary
        SessionSubagentStatus.COMPLETED -> p.success
        SessionSubagentStatus.IDLE -> p.textMuted
        SessionSubagentStatus.UNKNOWN, null -> p.textSecondary
    }
}

/**
 * Centralizes tray status coloring so rows, dots, and details stay visually consistent.
 */
internal fun subagentStatusColor(
    status: SessionSubagentStatus,
    p: DesignPalette,
): androidx.compose.ui.graphics.Color {
    return when (status) {
        SessionSubagentStatus.ACTIVE -> p.accent
        SessionSubagentStatus.IDLE -> p.textMuted
        SessionSubagentStatus.PENDING -> p.textSecondary
        SessionSubagentStatus.FAILED -> p.danger
        SessionSubagentStatus.COMPLETED -> p.success
        SessionSubagentStatus.UNKNOWN -> p.markdownDivider
    }
}

/**
 * Maps UI status values to localized short labels used by the tray summary.
 */
internal fun localizedSubagentState(status: SessionSubagentStatus): String {
    return when (status) {
        SessionSubagentStatus.ACTIVE -> AuraCodeBundle.message("composer.subagents.state.active")
        SessionSubagentStatus.IDLE -> AuraCodeBundle.message("composer.subagents.state.idle")
        SessionSubagentStatus.PENDING -> AuraCodeBundle.message("composer.subagents.state.pending")
        SessionSubagentStatus.FAILED -> AuraCodeBundle.message("composer.subagents.state.failed")
        SessionSubagentStatus.COMPLETED -> AuraCodeBundle.message("composer.subagents.state.completed")
        SessionSubagentStatus.UNKNOWN -> AuraCodeBundle.message("composer.subagents.state.unknown")
    }
}
