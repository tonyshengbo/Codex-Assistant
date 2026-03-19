package com.codex.assistant.toolwindow.bootstrap

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
import com.codex.assistant.toolwindow.composer.ComposerAreaState
import com.codex.assistant.toolwindow.composer.ComposerRegion
import com.codex.assistant.toolwindow.drawer.RightDrawerAreaState
import com.codex.assistant.toolwindow.drawer.RightDrawerKind
import com.codex.assistant.toolwindow.drawer.RightDrawerRegion
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.header.HeaderAreaState
import com.codex.assistant.settings.UiThemeMode
import com.codex.assistant.toolwindow.header.HeaderRegion
import com.codex.assistant.toolwindow.shared.assistantPalette
import com.codex.assistant.toolwindow.shared.assistantUiTokens
import com.codex.assistant.toolwindow.status.StatusAreaState
import com.codex.assistant.toolwindow.status.StatusToastOverlay
import com.codex.assistant.toolwindow.status.TurnStatusRegion
import com.codex.assistant.toolwindow.timeline.TimelineAreaState
import com.codex.assistant.toolwindow.timeline.TimelineRegion
import com.intellij.openapi.wm.ToolWindowAnchor

@Composable
internal fun ToolWindowScreen(
    headerState: HeaderAreaState,
    statusState: StatusAreaState,
    timelineState: TimelineAreaState,
    composerState: ComposerAreaState,
    rightDrawerState: RightDrawerAreaState,
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
                    onIntent = onIntent,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = t.spacing.md, vertical = t.spacing.lg),
            contentAlignment = Alignment.BottomCenter,
        ) {
            StatusToastOverlay(
                p = p,
                state = statusState,
                modifier = Modifier.padding(bottom = t.controls.sendButton + t.spacing.xl),
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
