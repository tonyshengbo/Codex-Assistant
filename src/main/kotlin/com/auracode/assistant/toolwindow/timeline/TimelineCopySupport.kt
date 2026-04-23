package com.auracode.assistant.toolwindow.timeline

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TIMELINE_COPY_FEEDBACK_DURATION_MS: Long = 1500L

internal fun timelineNodeCopyText(node: TimelineNode): String? {
    return when (node) {
        is TimelineNode.MessageNode -> node.text.takeIf { it.isNotBlank() }
        is TimelineNode.ReasoningNode -> node.body.takeIf { it.isNotBlank() }
        is TimelineNode.ToolCallNode -> node.body.takeIf { it.isNotBlank() }
        is TimelineNode.CommandNode -> timelineCommandCopyBody(
            title = node.title,
            commandText = node.commandText,
            outputText = node.outputText,
        ).takeIf { it.isNotBlank() }
        is TimelineNode.ApprovalNode -> node.body.takeIf { it.isNotBlank() }
        is TimelineNode.ContextCompactionNode -> node.body.takeIf { it.isNotBlank() }
        is TimelineNode.PlanNode -> node.body.takeIf { it.isNotBlank() }
        is TimelineNode.UserInputNode -> node.body.takeIf { it.isNotBlank() }
        is TimelineNode.UnknownActivityNode -> node.body.takeIf { it.isNotBlank() }
        is TimelineNode.ErrorNode -> node.body.takeIf { it.isNotBlank() }
        is TimelineNode.EngineSwitchedNode -> node.body.takeIf { it.isNotBlank() }
        is TimelineNode.FileChangeNode,
        is TimelineNode.LoadMoreNode,
        -> null
    }
}

internal fun timelineCommandCopyBody(
    title: String? = null,
    commandText: String? = null,
    outputText: String? = null,
): String {
    return listOfNotNull(
        title?.trim()?.takeIf { it.isNotEmpty() },
        commandText?.trim()?.takeIf { it.isNotEmpty() },
        outputText?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(separator = "\n\n")
}

internal fun copyTimelineTextToClipboard(text: String): Boolean {
    return runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        true
    }.getOrDefault(false)
}

@Composable
internal fun TimelineCopyActionButton(
    visible: Boolean,
    copyText: String,
    palette: DesignPalette,
    modifier: Modifier = Modifier,
) {
    val t = assistantUiTokens()
    val scope = rememberCoroutineScope()
    var copied by remember(copyText) { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }
    val tooltip = if (copied) AuraCodeBundle.message("timeline.copied") else AuraCodeBundle.message("timeline.copy")

    AnimatedVisibility(
        visible = visible || copied,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        HoverTooltip(text = tooltip) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(palette.topBarBg.copy(alpha = 0.96f), RoundedCornerShape(t.spacing.xs))
                    .border(1.dp, palette.markdownDivider.copy(alpha = 0.52f), RoundedCornerShape(t.spacing.xs))
                    .clickable {
                        if (!copyTimelineTextToClipboard(copyText)) {
                            return@clickable
                        }
                        copied = true
                        resetJob?.cancel()
                        resetJob = scope.launch {
                            delay(TIMELINE_COPY_FEEDBACK_DURATION_MS)
                            copied = false
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(if (copied) "/icons/check.svg" else "/icons/copy.svg"),
                    contentDescription = tooltip,
                    tint = if (copied) palette.success else palette.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
