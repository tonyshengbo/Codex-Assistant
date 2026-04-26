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
import com.auracode.assistant.toolwindow.submission.ComposerAreaState
import com.auracode.assistant.toolwindow.submission.ComposerRegion
import com.auracode.assistant.toolwindow.shell.RightDrawerAreaState
import com.auracode.assistant.toolwindow.shell.RightDrawerKind
import com.auracode.assistant.toolwindow.shell.RightDrawerRegion
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.sessions.HeaderAreaState
import com.auracode.assistant.settings.UiThemeMode
import com.auracode.assistant.toolwindow.sessions.HeaderRegion
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptState
import com.auracode.assistant.toolwindow.shared.assistantPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.auracode.assistant.toolwindow.execution.StatusAreaState
import com.auracode.assistant.toolwindow.execution.StatusToastOverlay
import com.auracode.assistant.toolwindow.execution.TurnStatusRegion
import com.auracode.assistant.toolwindow.conversation.TimelineAreaState
import com.auracode.assistant.toolwindow.conversation.TimelineRegion
import com.intellij.openapi.wm.ToolWindowAnchor

@Composable
internal fun ToolWindowScreen(
    headerState: HeaderAreaState,
    statusState: StatusAreaState,
    timelineState: TimelineAreaState,
    composerState: ComposerAreaState,
    rightDrawerState: RightDrawerAreaState,
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
            HeaderRegion(p = p, state = headerState, onIntent = onIntent)
            TimelineRegion(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                p = p,
                state = timelineState,
                onIntent = onIntent,
            )
            composerState.runningPlan?.let { runningPlan ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(p.composerBg)
                        .padding(horizontal = t.spacing.md, vertical = t.spacing.xs),
                ) {
                    com.auracode.assistant.toolwindow.submission.RunningPlanComposerSection(
                        p = p,
                        state = runningPlan,
                        expanded = composerState.runningPlanExpanded,
                        onIntent = onIntent,
                    )
                }
            }
            TurnStatusRegion(p = p, state = statusState)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(p.composerBg),
            ) {
                ComposerRegion(
                    p = p,
                    state = composerState,
                    conversationState = timelineState,
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
            StatusToastOverlay(
                p = p,
                state = statusState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = t.controls.sendButton + t.spacing.xl),
            )
        }
        if (rightDrawerState.kind != RightDrawerKind.NONE) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
                    .clickable(
                        interactionSource = MutableInteractionSource(),
                        indication = null,
                        onClick = { onIntent(UiIntent.CloseRightDrawer) },
                    ),
            )
            val drawerModifier = if (rightDrawerState.kind == RightDrawerKind.SETTINGS) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxSize().padding(t.spacing.md)
            }
            Box(modifier = drawerModifier) {
                RightDrawerRegion(p = p, state = rightDrawerState, onIntent = onIntent)
            }
        }
    }
}
