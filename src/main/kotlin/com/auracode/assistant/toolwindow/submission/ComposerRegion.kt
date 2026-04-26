package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auracode.assistant.toolwindow.execution.ApprovalAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.auracode.assistant.toolwindow.conversation.TimelineAreaState
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptState

/**
 * Describes which primary body the composer should show below the shared top strips.
 */
internal enum class ComposerRegionBodyKind {
    APPROVAL,
    TOOL_USER_INPUT,
    PLAN_COMPLETION,
    DEFAULT,
}

/**
 * Provides a small testable snapshot of the composer layout decisions.
 */
internal data class ComposerRegionContent(
    val showSubagentTray: Boolean,
    val bodyKind: ComposerRegionBodyKind,
)

/**
 * Resolves the region layout so tray visibility stays consistent across interaction cards.
 */
internal fun resolveComposerRegionContent(state: ComposerAreaState): ComposerRegionContent {
    return ComposerRegionContent(
        showSubagentTray = state.subagentTrayVisible,
        bodyKind = when (state.activeInteractionCard?.kind) {
            ComposerInteractionCardKind.APPROVAL -> ComposerRegionBodyKind.APPROVAL
            ComposerInteractionCardKind.TOOL_USER_INPUT -> ComposerRegionBodyKind.TOOL_USER_INPUT
            ComposerInteractionCardKind.PLAN_COMPLETION -> ComposerRegionBodyKind.PLAN_COMPLETION
            null -> ComposerRegionBodyKind.DEFAULT
        },
    )
}

@Composable
internal fun ComposerRegion(
    p: DesignPalette,
    state: ComposerAreaState,
    conversationState: TimelineAreaState,
    approvalState: ApprovalAreaState,
    toolUserInputPromptState: ToolUserInputPromptState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val running = state.sessionIsRunning || conversationState.isRunning
    val content = resolveComposerRegionContent(state)

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
        PendingQueueSection(
            p = p,
            submissions = state.pendingSubmissions,
            onIntent = onIntent,
        )
        if (state.pendingSubmissions.isNotEmpty()) {
            Spacer(Modifier.height(t.spacing.xs))
        }
        if (content.showSubagentTray) {
            ComposerSubagentTray(
                p = p,
                state = state,
                onIntent = onIntent,
            )
            Spacer(Modifier.height(t.spacing.xs))
        }
        when (content.bodyKind) {
            ComposerRegionBodyKind.APPROVAL -> ApprovalComposerSection(
                p = p,
                state = approvalState,
                onIntent = onIntent,
            )

            ComposerRegionBodyKind.TOOL_USER_INPUT -> ToolUserInputComposerSection(
                p = p,
                state = toolUserInputPromptState,
                onIntent = onIntent,
            )

            ComposerRegionBodyKind.PLAN_COMPLETION -> {
                state.planCompletion?.let { planCompletion ->
                    PlanCompletionComposerSection(
                        p = p,
                        state = planCompletion,
                        onIntent = onIntent,
                    )
                }
            }

            ComposerRegionBodyKind.DEFAULT -> {
                AttachmentStrip(p = p, state = state, onIntent = onIntent)
                if (state.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(t.spacing.xs))
                }
                EditedFilesPanel(p = p, state = state, onIntent = onIntent)
                if (state.editedFilesExpanded && state.editedFiles.isNotEmpty()) {
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
    }
}
