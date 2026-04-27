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
import com.auracode.assistant.toolwindow.conversation.ConversationAreaState
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptState

/**
 * Describes which primary body the composer should show below the shared top strips.
 */
internal enum class SubmissionRegionBodyKind {
    APPROVAL,
    TOOL_USER_INPUT,
    PLAN_COMPLETION,
    DEFAULT,
}

/**
 * Provides a small testable snapshot of the composer layout decisions.
 */
internal data class SubmissionRegionContent(
    val showSubagentTray: Boolean,
    val bodyKind: SubmissionRegionBodyKind,
)

/**
 * Resolves the region layout so tray visibility stays consistent across interaction cards.
 */
internal fun resolveSubmissionRegionContent(state: SubmissionAreaState): SubmissionRegionContent {
    return SubmissionRegionContent(
        showSubagentTray = state.subagentTrayVisible,
        bodyKind = when (state.activeInteractionCard?.kind) {
            SubmissionInteractionCardKind.APPROVAL -> SubmissionRegionBodyKind.APPROVAL
            SubmissionInteractionCardKind.TOOL_USER_INPUT -> SubmissionRegionBodyKind.TOOL_USER_INPUT
            SubmissionInteractionCardKind.PLAN_COMPLETION -> SubmissionRegionBodyKind.PLAN_COMPLETION
            null -> SubmissionRegionBodyKind.DEFAULT
        },
    )
}

@Composable
internal fun SubmissionRegion(
    p: DesignPalette,
    state: SubmissionAreaState,
    conversationState: ConversationAreaState,
    approvalState: ApprovalAreaState,
    toolUserInputPromptState: ToolUserInputPromptState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val running = state.sessionIsRunning || conversationState.isRunning
    val content = resolveSubmissionRegionContent(state)

    SubmissionAttachmentPreviewDialog(
        p = p,
        state = state,
        onIntent = onIntent,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.submissionBg)
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
            SubmissionSubagentTray(
                p = p,
                state = state,
                onIntent = onIntent,
            )
            Spacer(Modifier.height(t.spacing.xs))
        }
        when (content.bodyKind) {
            SubmissionRegionBodyKind.APPROVAL -> SubmissionApprovalSection(
                p = p,
                state = approvalState,
                onIntent = onIntent,
            )

            SubmissionRegionBodyKind.TOOL_USER_INPUT -> SubmissionToolUserInputSection(
                p = p,
                state = toolUserInputPromptState,
                onIntent = onIntent,
            )

            SubmissionRegionBodyKind.PLAN_COMPLETION -> {
                state.planCompletion?.let { planCompletion ->
                    SubmissionPlanCompletionSection(
                        p = p,
                        state = planCompletion,
                        onIntent = onIntent,
                    )
                }
            }

            SubmissionRegionBodyKind.DEFAULT -> {
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
                SubmissionInputSection(
                    p = p,
                    state = state,
                    running = running,
                    onIntent = onIntent,
                )
                Spacer(Modifier.height(6.dp))
                SubmissionControlBar(
                    p = p,
                    state = state,
                    running = running,
                    onIntent = onIntent,
                )
            }
        }
    }
}
