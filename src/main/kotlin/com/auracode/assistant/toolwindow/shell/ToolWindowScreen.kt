package com.auracode.assistant.toolwindow.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.execution.ApprovalAreaState
import com.auracode.assistant.toolwindow.submission.SubmissionAreaState
import com.auracode.assistant.toolwindow.submission.SubmissionRegion
import com.auracode.assistant.toolwindow.shell.SidePanelAreaState
import com.auracode.assistant.toolwindow.shell.SidePanelKind
import com.auracode.assistant.toolwindow.shell.SidePanelRegion
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.sessions.SessionTabsAreaState
import com.auracode.assistant.settings.UiThemeMode
import com.auracode.assistant.toolwindow.sessions.SessionTabsRegion
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptState
import com.auracode.assistant.toolwindow.shared.assistantPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.auracode.assistant.toolwindow.execution.ExecutionStatusAreaState
import com.auracode.assistant.toolwindow.execution.ExecutionStatusToastOverlay
import com.auracode.assistant.toolwindow.execution.ExecutionTurnStatusRegion
import com.auracode.assistant.toolwindow.conversation.ConversationAreaState
import com.auracode.assistant.toolwindow.conversation.ConversationActivityRegion
import com.intellij.openapi.wm.ToolWindowAnchor

@Composable
internal fun ToolWindowScreen(
    sessionTabsState: SessionTabsAreaState,
    executionStatusState: ExecutionStatusAreaState,
    conversationState: ConversationAreaState,
    submissionState: SubmissionAreaState,
    sidePanelState: SidePanelAreaState,
    approvalState: ApprovalAreaState,
    toolUserInputPromptState: ToolUserInputPromptState,
    anchor: ToolWindowAnchor,
    themeMode: UiThemeMode,
    onIntent: (UiIntent) -> Unit,
) {
    val p = assistantPalette(themeMode)
    val t = assistantUiTokens()
    val dividerColor = p.markdownDivider.copy(alpha = 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(p.appBg)
            .drawBehind {
                val stroke = 1.dp.toPx()
                when (anchor) {
                    ToolWindowAnchor.RIGHT -> drawLine(
                        color = dividerColor,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = stroke,
                    )
                    ToolWindowAnchor.LEFT -> drawLine(
                        color = dividerColor,
                        start = Offset(size.width, 0f),
                        end = Offset(size.width, size.height),
                        strokeWidth = stroke,
                    )
                    ToolWindowAnchor.BOTTOM -> drawLine(
                        color = dividerColor,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = stroke,
                    )
                    ToolWindowAnchor.TOP -> drawLine(
                        color = dividerColor,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = stroke,
                    )
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            SessionTabsRegion(p = p, state = sessionTabsState, onIntent = onIntent)
            ConversationActivityRegion(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                p = p,
                state = conversationState,
                onIntent = onIntent,
            )
            ExecutionTurnStatusRegion(p = p, state = executionStatusState)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(p.submissionBg),
            ) {
                SubmissionRegion(
                    p = p,
                    state = submissionState,
                    conversationState = conversationState,
                    approvalState = approvalState,
                    toolUserInputPromptState = toolUserInputPromptState,
                    onIntent = onIntent,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = t.spacing.md, vertical = t.spacing.lg),
        ) {
            ExecutionStatusToastOverlay(
                p = p,
                state = executionStatusState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = t.controls.sendButton + t.spacing.xl),
            )
        }
        if (sidePanelState.kind != SidePanelKind.NONE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
                    .clickable(
                        interactionSource = MutableInteractionSource(),
                        indication = null,
                        onClick = { onIntent(UiIntent.CloseSidePanel) },
                    ),
            )
            val drawerModifier = if (sidePanelState.kind == SidePanelKind.SETTINGS) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxSize().padding(t.spacing.md)
            }
            Box(modifier = drawerModifier) {
                SidePanelRegion(p = p, state = sidePanelState, onIntent = onIntent)
            }
        }
    }
}
