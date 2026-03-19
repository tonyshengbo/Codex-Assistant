package com.codex.assistant.toolwindow.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.protocol.ItemStatus
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.HoverTooltip
import com.codex.assistant.toolwindow.shared.assistantUiTokens

@Composable
internal fun LoadOlderHistoryButton(
    loading: Boolean,
    p: DesignPalette,
    onClick: () -> Unit,
) {
    val t = assistantUiTokens()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.topBarBg.copy(alpha = 0.74f), RoundedCornerShape(t.spacing.md))
            .border(1.dp, p.markdownDivider.copy(alpha = 0.5f), RoundedCornerShape(t.spacing.md))
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
    ) {
        Text(
            text = if (loading) CodexBundle.message("timeline.loadingOlder") else CodexBundle.message("timeline.loadOlder"),
            color = p.textSecondary,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
internal fun TimelineExpandableCard(
    title: String,
    status: ItemStatus,
    expanded: Boolean,
    palette: DesignPalette,
    onToggleExpanded: () -> Unit,
    content: @Composable () -> Unit,
) {
    val t = assistantUiTokens()
    val indicatorColor = when (status) {
        ItemStatus.FAILED -> palette.danger
        ItemStatus.RUNNING -> palette.accent
        else -> palette.success
    }
    HoverTooltip(text = if (expanded) CodexBundle.message("timeline.collapse") else CodexBundle.message("timeline.expand")) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.timelineCardBg, RoundedCornerShape(t.spacing.sm))
                .border(
                    width = 1.dp,
                    color = palette.markdownDivider.copy(alpha = 0.68f),
                    shape = RoundedCornerShape(t.spacing.sm),
                )
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = t.spacing.md, vertical = t.spacing.sm),
        ) {
            androidx.compose.foundation.layout.Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(if (expanded) "/icons/arrow-down.svg" else "/icons/arrow-right.svg"),
                            contentDescription = if (expanded) CodexBundle.message("timeline.collapse") else CodexBundle.message("timeline.expand"),
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(t.spacing.sm))
                    Text(
                        text = title,
                        color = palette.timelineCardText,
                        fontWeight = FontWeight.Medium,
                        style = androidx.compose.material.MaterialTheme.typography.subtitle1,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(t.spacing.sm)
                            .background(indicatorColor, CircleShape),
                    )
                }
                if (expanded) {
                    Spacer(Modifier.height(t.spacing.sm))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(palette.topStripBg.copy(alpha = 0.88f), RoundedCornerShape(t.spacing.sm))
                            .padding(horizontal = t.spacing.sm, vertical = t.spacing.sm),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
internal fun TimelineMarkdownActivityBody(
    title: String,
    body: String,
    status: ItemStatus,
    expanded: Boolean,
    palette: DesignPalette,
    onToggleExpanded: () -> Unit,
) {
    TimelineExpandableCard(
        title = title,
        status = status,
        expanded = expanded,
        palette = palette,
        onToggleExpanded = onToggleExpanded,
    ) {
        TimelineMarkdown(
            text = body,
            palette = palette,
        )
    }
}
