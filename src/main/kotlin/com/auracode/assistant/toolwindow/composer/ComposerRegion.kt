package com.auracode.assistant.toolwindow.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.approval.ApprovalAreaState
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.auracode.assistant.toolwindow.timeline.TimelineAreaState
import com.auracode.assistant.toolwindow.toolinput.ToolUserInputPromptState

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

    ComposerAttachmentPreviewDialog(
        p = p,
        state = state,
        onIntent = onIntent,
    )
    ComposerEngineSwitchConfirmationDialog(
        state = state.engineSwitchConfirmation,
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
        when (state.activeInteractionCard?.kind) {
            ComposerInteractionCardKind.APPROVAL -> ApprovalComposerSection(
                p = p,
                state = approvalState,
                onIntent = onIntent,
            )

            ComposerInteractionCardKind.TOOL_USER_INPUT -> ToolUserInputComposerSection(
                p = p,
                state = toolUserInputPromptState,
                onIntent = onIntent,
            )

            ComposerInteractionCardKind.PLAN_COMPLETION -> {
                state.planCompletion?.let { planCompletion ->
                    PlanCompletionComposerSection(
                        p = p,
                        state = planCompletion,
                        onIntent = onIntent,
                    )
                }
            }

            null -> {
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

/**
 * Confirms whether switching engines should branch the current populated session into a new tab.
 */
@Composable
private fun ComposerEngineSwitchConfirmationDialog(
    state: EngineSwitchConfirmationState?,
    onIntent: (UiIntent) -> Unit,
) {
    if (state == null) return
    AlertDialog(
        onDismissRequest = { onIntent(UiIntent.DismissEngineSwitchDialog) },
        title = { Text(AuraCodeBundle.message("composer.engineSwitch.confirm.title")) },
        text = {
            Text(
                AuraCodeBundle.message(
                    "composer.engineSwitch.confirm.message",
                    state.targetEngineLabel,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onIntent(UiIntent.SelectEngine(state.targetEngineId)) },
            ) {
                Text(AuraCodeBundle.message("composer.engineSwitch.confirm.confirm"))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onIntent(UiIntent.DismissEngineSwitchDialog) },
            ) {
                Text(AuraCodeBundle.message("common.cancel"))
            }
        },
    )
}
