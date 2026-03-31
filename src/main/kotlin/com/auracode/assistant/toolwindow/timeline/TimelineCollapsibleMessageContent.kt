package com.auracode.assistant.toolwindow.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

/**
 * Returns `true` when the rendered content is taller than the collapsed viewport.
 */
internal fun timelineShouldCollapseMessageContent(
    fullContentHeightPx: Int,
    collapsedMaxHeightPx: Int,
): Boolean {
    return fullContentHeightPx > collapsedMaxHeightPx
}

/**
 * Caps oversized user message content and exposes an explicit expand/collapse action.
 */
@Composable
internal fun TimelineCollapsibleMessageContent(
    messageId: String,
    palette: DesignPalette,
    modifier: Modifier = Modifier,
    collapsedMaxHeight: Dp = timelineExpandedBodyMaxHeight(),
    content: @Composable () -> Unit,
) {
    val t = assistantUiTokens()
    val density = LocalDensity.current
    var measuredContentHeightPx by remember(messageId) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val isCollapsible = timelineShouldCollapseMessageContent(
        fullContentHeightPx = measuredContentHeightPx,
        collapsedMaxHeightPx = with(density) {
            collapsedMaxHeight.roundToPx()
        },
    )

    LaunchedEffect(isCollapsible) {
        if (!isCollapsible && expanded) {
            expanded = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .let { baseModifier ->
                    if (isCollapsible && !expanded) {
                        baseModifier.heightIn(max = collapsedMaxHeight)
                    } else {
                        baseModifier.wrapContentHeight()
                    }
                },
        ) {
            TimelineMeasuredMessageContent(
                modifier = Modifier.fillMaxWidth(),
                onMeasuredHeightChanged = { measuredHeightPx ->
                    measuredContentHeightPx = measuredHeightPx
                },
                content = content,
            )
            if (isCollapsible && !expanded) {
                TimelineCollapsedMessageOverlay(
                    palette = palette,
                    modifier = Modifier
                        .align(Alignment.BottomCenter),
                    onExpand = { expanded = true },
                )
            }
        }
        if (isCollapsible && expanded) {
            TimelineExpandedMessageFooter(
                palette = palette,
                onCollapse = { expanded = false },
            )
        }
    }
}

/**
 * Measures the full rendered height once so collapse decisions are based on real layout size.
 */
@Composable
private fun TimelineMeasuredMessageContent(
    modifier: Modifier = Modifier,
    onMeasuredHeightChanged: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    var reportedHeightPx by remember { mutableIntStateOf(Int.MIN_VALUE) }
    SubcomposeLayout(modifier = modifier) { constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val contentPlaceable = subcompose(TimelineMeasuredContentSlot.CONTENT) {
            Box(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }.single().measure(
            Constraints(
                minWidth = constraints.minWidth,
                maxWidth = width,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            ),
        )

        if (contentPlaceable.height != reportedHeightPx) {
            reportedHeightPx = contentPlaceable.height
            onMeasuredHeightChanged(contentPlaceable.height)
        }

        layout(
            width = contentPlaceable.width,
            height = contentPlaceable.height,
        ) {
            contentPlaceable.placeRelative(0, 0)
        }
    }
}

private enum class TimelineMeasuredContentSlot {
    CONTENT,
}
