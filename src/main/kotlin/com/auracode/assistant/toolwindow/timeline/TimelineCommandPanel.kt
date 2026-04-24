package com.auracode.assistant.toolwindow.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

private const val TIMELINE_COMMAND_NEAR_BOTTOM_THRESHOLD_PX: Int = 40

internal data class TimelineCommandPanelContent(
    val label: String,
    val commandText: String,
    val outputText: String?,
)

internal data class TimelineCommandScrollSnapshot(
    val currentOffset: Int,
    val maxOffset: Int,
)

internal fun timelineCommandPanelContent(
    commandText: String?,
    outputText: String?,
): TimelineCommandPanelContent {
    return TimelineCommandPanelContent(
        label = "Shell",
        commandText = commandText?.trim().orEmpty(),
        outputText = outputText?.trim()?.takeIf { it.isNotEmpty() },
    )
}

internal fun timelineCommandScrollSnapshot(
    currentOffset: Int,
    maxOffset: Int,
): TimelineCommandScrollSnapshot {
    return TimelineCommandScrollSnapshot(
        currentOffset = currentOffset.coerceAtLeast(0),
        maxOffset = maxOffset.coerceAtLeast(0),
    )
}

internal fun timelineCommandBottomOverflowPx(snapshot: TimelineCommandScrollSnapshot): Int {
    return (snapshot.maxOffset - snapshot.currentOffset).coerceAtLeast(0)
}

internal fun timelineCommandIsNearBottom(
    snapshot: TimelineCommandScrollSnapshot,
    thresholdPx: Int = TIMELINE_COMMAND_NEAR_BOTTOM_THRESHOLD_PX,
): Boolean {
    return timelineCommandBottomOverflowPx(snapshot) <= thresholdPx
}

internal fun timelineCommandResolveAutoFollow(
    wasAutoFollowEnabled: Boolean,
    isScrollInProgress: Boolean,
    isNearBottom: Boolean,
): Boolean {
    // Keep the previous state while the user is actively dragging, so streaming updates
    // do not fight with the gesture. Once scrolling settles, follow mode mirrors whether
    // the user is back at the tail.
    return if (isScrollInProgress) wasAutoFollowEnabled else isNearBottom
}

@Composable
internal fun TimelineCommandExecutionPanel(
    commandText: String?,
    outputText: String?,
    palette: DesignPalette,
) {
    val panel = timelineCommandPanelContent(commandText = commandText, outputText = outputText)
    val t = assistantUiTokens()
    val scrollState = rememberScrollState()
    val selectionColors = rememberTimelineCommandSelectionColors()
    var autoFollowEnabled by remember { mutableStateOf(true) }

    LaunchedEffect(scrollState.isScrollInProgress, scrollState.value, scrollState.maxValue) {
        val snapshot = timelineCommandScrollSnapshot(
            currentOffset = scrollState.value,
            maxOffset = scrollState.maxValue,
        )
        autoFollowEnabled = timelineCommandResolveAutoFollow(
            wasAutoFollowEnabled = autoFollowEnabled,
            isScrollInProgress = scrollState.isScrollInProgress,
            isNearBottom = timelineCommandIsNearBottom(snapshot),
        )
    }

    LaunchedEffect(panel.outputText, scrollState.maxValue, autoFollowEnabled) {
        // Only keep pinning to the bottom while the user is already following the live tail.
        if (autoFollowEnabled && scrollState.maxValue > 0) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(commandPanelBackground(palette), RoundedCornerShape(t.spacing.sm))
            .border(1.dp, commandPanelBorder(palette), RoundedCornerShape(t.spacing.sm))
            .padding(horizontal = t.spacing.sm + 2.dp, vertical = t.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
    ) {
        Text(
            text = panel.label,
            color = palette.textMuted,
            style = MaterialTheme.typography.caption,
            fontWeight = FontWeight.Medium,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = timelineExpandedBodyMaxHeight())
                .verticalScroll(scrollState),
        ) {
            TimelineSelectableText(selectionColors = selectionColors) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(t.spacing.sm),
                ) {
                    Text(
                        text = "$ ${panel.commandText}",
                        color = Color(0xFFF2F5FB),
                        style = MaterialTheme.typography.body2,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )

                    panel.outputText?.takeIf { it.isNotBlank() }?.let { output ->
                        Text(
                            text = output,
                            color = Color(0xFFCCD5E2),
                            style = MaterialTheme.typography.caption,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (scrollState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    commandPanelBackground(palette).copy(alpha = 0f),
                                    commandPanelBackground(palette).copy(alpha = 0.96f),
                                ),
                            ),
                        ),
                )
            }
        }
    }
}

private fun commandPanelBackground(palette: DesignPalette): Color {
    return if (palette.appBg.red > 0.5f) Color(0xFF2B2F36) else Color(0xFF1F232A)
}

private fun commandPanelBorder(palette: DesignPalette): Color {
    return if (palette.appBg.red > 0.5f) Color(0xFF3A404B) else palette.markdownDivider.copy(alpha = 0.28f)
}
