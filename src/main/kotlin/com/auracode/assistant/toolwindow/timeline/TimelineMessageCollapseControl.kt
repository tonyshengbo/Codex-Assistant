package com.auracode.assistant.toolwindow.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

private val TIMELINE_MESSAGE_COLLAPSE_OVERLAY_HEIGHT = 72.dp

/**
 * Describes the localized action and icon used by the collapse control.
 */
internal data class TimelineMessageCollapseControlModel(
    val actionLabelKey: String,
    val iconPath: String,
)

/**
 * Resolves the visual state for the message collapse control.
 */
internal fun timelineMessageCollapseControlModel(expanded: Boolean): TimelineMessageCollapseControlModel {
    return if (expanded) {
        TimelineMessageCollapseControlModel(
            actionLabelKey = "timeline.message.collapse",
            iconPath = "/icons/arrow-up.svg",
        )
    } else {
        TimelineMessageCollapseControlModel(
            actionLabelKey = "timeline.message.expand",
            iconPath = "/icons/arrow-down.svg",
        )
    }
}

/**
 * Renders the centered chip control used by collapsed and expanded user messages.
 */
@Composable
internal fun TimelineMessageCollapseControl(
    expanded: Boolean,
    palette: DesignPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val model = timelineMessageCollapseControlModel(expanded = expanded)
    val t = assistantUiTokens()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = palette.topStripBg.copy(alpha = 0.94f),
                shape = RoundedCornerShape(999.dp),
            )
            .border(
                width = 1.dp,
                color = palette.markdownDivider.copy(alpha = 0.46f),
                shape = RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = t.spacing.md, vertical = t.spacing.xs + 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(model.iconPath),
            contentDescription = null,
            tint = palette.textSecondary,
        )
        Box(modifier = Modifier.padding(start = t.spacing.xs))
        Text(
            text = AuraCodeBundle.message(model.actionLabelKey),
            color = palette.timelineCardText,
            style = MaterialTheme.typography.body2,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Anchors the collapsed-state hint and action inside the fade overlay.
 */
@Composable
internal fun TimelineCollapsedMessageOverlay(
    palette: DesignPalette,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
) {
    val t = assistantUiTokens()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TIMELINE_MESSAGE_COLLAPSE_OVERLAY_HEIGHT)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        palette.userBubbleBg.copy(alpha = 0f),
                        palette.userBubbleBg.copy(alpha = 0.84f),
                        palette.userBubbleBg.copy(alpha = 0.98f),
                    ),
                ),
                RoundedCornerShape(bottomStart = t.spacing.sm, bottomEnd = t.spacing.sm),
            ),
    ) {
        TimelineMessageCollapseControl(
            expanded = false,
            palette = palette,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = t.spacing.xs + 2.dp),
            onClick = onExpand,
        )
    }
}

/**
 * Separates expanded content from the collapse action with a subtle divider.
 */
@Composable
internal fun TimelineExpandedMessageFooter(
    palette: DesignPalette,
    modifier: Modifier = Modifier,
    onCollapse: () -> Unit,
) {
    val t = assistantUiTokens()
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(palette.markdownDivider.copy(alpha = 0.18f)),
        )
        TimelineMessageCollapseControl(
            expanded = true,
            palette = palette,
            modifier = Modifier.fillMaxWidth(),
            onClick = onCollapse,
        )
    }
}
