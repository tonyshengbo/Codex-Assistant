package com.auracode.assistant.toolwindow.conversation

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

internal fun conversationActivityCopyText(node: ConversationActivityItem): String? {
    return when (node) {
        is ConversationActivityItem.MessageNode -> node.text.takeIf { it.isNotBlank() }
        is ConversationActivityItem.ReasoningNode -> node.body.takeIf { it.isNotBlank() }
        is ConversationActivityItem.ToolCallNode -> node.body.takeIf { it.isNotBlank() }
        is ConversationActivityItem.CommandNode -> conversationCommandCopyBody(
            title = node.title,
            commandText = node.commandText,
            outputText = node.outputText,
        ).takeIf { it.isNotBlank() }
        is ConversationActivityItem.ApprovalNode -> node.body.takeIf { it.isNotBlank() }
        is ConversationActivityItem.ContextCompactionNode -> node.body.takeIf { it.isNotBlank() }
        is ConversationActivityItem.PlanNode -> node.body.takeIf { it.isNotBlank() }
        is ConversationActivityItem.UserInputNode -> node.body.takeIf { it.isNotBlank() }
        is ConversationActivityItem.UnknownActivityNode -> node.body.takeIf { it.isNotBlank() }
        is ConversationActivityItem.ErrorNode -> node.body.takeIf { it.isNotBlank() }
        is ConversationActivityItem.EngineSwitchedNode -> node.body.takeIf { it.isNotBlank() }
        is ConversationActivityItem.FileChangeNode,
        is ConversationActivityItem.LoadMoreNode,
        -> null
    }
}

internal fun conversationCommandCopyBody(
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

internal fun copyConversationTextToClipboard(text: String): Boolean {
    return runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        true
    }.getOrDefault(false)
}

@Composable
internal fun ConversationCopyActionButton(
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
                        if (!copyConversationTextToClipboard(copyText)) {
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
