package com.codex.assistant.toolwindow.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codex.assistant.toolwindow.eventing.UiIntent
import com.codex.assistant.toolwindow.shared.DesignPalette
import com.codex.assistant.toolwindow.shared.assistantUiTokens
import com.codex.assistant.toolwindow.timeline.TimelineAreaState

@Composable
internal fun ComposerRegion(
    p: DesignPalette,
    state: ComposerAreaState,
    conversationState: TimelineAreaState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val running = conversationState.isRunning

    ComposerAttachmentPreviewDialog(
        p = p,
        state = state,
        onIntent = onIntent,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.composerBg)
            .padding(start = t.spacing.md, end = t.spacing.md, top = t.spacing.xs, bottom = t.spacing.sm),
    ) {
        AttachmentStrip(p = p, state = state, onIntent = onIntent)
        if (state.attachments.isNotEmpty()) {
            Spacer(Modifier.height(t.spacing.xs))
        }
        ContextEntryStrip(p = p, state = state, onIntent = onIntent)
        Spacer(Modifier.height(t.spacing.xs))
        ComposerInputSection(
            p = p,
            state = state,
            running = running,
            onIntent = onIntent,
        )
        Spacer(Modifier.height(6.dp))
        ComposerControlBar(
            p = p,
            state = state,
            running = running,
            onIntent = onIntent,
        )
    }
}
