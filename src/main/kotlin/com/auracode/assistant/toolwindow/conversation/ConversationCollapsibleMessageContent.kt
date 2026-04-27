package com.auracode.assistant.toolwindow.conversation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import kotlin.math.min

/**
 * Returns `true` when the rendered content is taller than the collapsed viewport.
 */
internal fun conversationShouldCollapseMessageContent(
    fullContentHeightPx: Int,
    collapsedMaxHeightPx: Int,
): Boolean {
    return fullContentHeightPx > collapsedMaxHeightPx
}

/**
 * Resolves the visible viewport height for the message body.
 */
internal fun conversationMessageViewportHeightPx(
    fullContentHeightPx: Int,
    collapsedMaxHeightPx: Int,
    expanded: Boolean,
): Int {
    if (fullContentHeightPx <= 0) {
        return 0
    }
    if (!conversationShouldCollapseMessageContent(fullContentHeightPx, collapsedMaxHeightPx)) {
        return fullContentHeightPx
    }
    return if (expanded) {
        fullContentHeightPx
    } else {
        min(fullContentHeightPx, collapsedMaxHeightPx)
    }
}

/**
 * Caps oversized user message content and exposes an explicit expand/collapse action.
 */
@Composable
internal fun ConversationCollapsibleMessageContent(
    messageId: String,
    palette: DesignPalette,
    modifier: Modifier = Modifier,
    collapsedMaxHeight: Dp = conversationExpandedBodyMaxHeight(),
    content: @Composable () -> Unit,
) {
    val t = assistantUiTokens()
    val density = LocalDensity.current
    val collapsedMaxHeightPx = with(density) {
        collapsedMaxHeight.roundToPx()
    }
    var measuredContentHeightPx by remember(messageId) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    val isCollapsible = conversationShouldCollapseMessageContent(
        fullContentHeightPx = measuredContentHeightPx,
        collapsedMaxHeightPx = collapsedMaxHeightPx,
    )
    val targetViewportHeightPx = conversationMessageViewportHeightPx(
        fullContentHeightPx = measuredContentHeightPx,
        collapsedMaxHeightPx = collapsedMaxHeightPx,
        expanded = expanded,
    )
    val animatedViewportHeight by animateDpAsState(
        targetValue = if (targetViewportHeightPx > 0) {
            with(density) { targetViewportHeightPx.toDp() }
        } else {
            collapsedMaxHeight
        },
    )

    LaunchedEffect(isCollapsible) {
        if (!isCollapsible && expanded) {
            expanded = false
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds(),
        ) {
            TimelineAnimatedMessageViewport(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { baseModifier ->
                        if (measuredContentHeightPx > 0 && isCollapsible) {
                            baseModifier.height(animatedViewportHeight)
                        } else {
                            baseModifier
                        }
                    },
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
 * Measures and renders the message body with a single subcomposition tree.
 */
@Composable
private fun TimelineAnimatedMessageViewport(
    modifier: Modifier = Modifier,
    onMeasuredHeightChanged: (Int) -> Unit,
    content: @Composable () -> Unit,
) {
    var reportedHeightPx by remember { mutableIntStateOf(Int.MIN_VALUE) }
    SubcomposeLayout(modifier = modifier) { constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        val contentPlaceable = subcompose(ConversationMeasuredContentSlot.CONTENT) {
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

        val layoutWidth = if (constraints.hasBoundedWidth) {
            contentPlaceable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        } else {
            contentPlaceable.width.coerceAtLeast(constraints.minWidth)
        }
        val layoutHeight = if (constraints.hasBoundedHeight) {
            contentPlaceable.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        } else {
            contentPlaceable.height.coerceAtLeast(constraints.minHeight)
        }

        layout(
            width = layoutWidth,
            height = layoutHeight,
        ) {
            contentPlaceable.placeRelative(0, 0)
        }
    }
}

private enum class ConversationMeasuredContentSlot {
    CONTENT,
}
