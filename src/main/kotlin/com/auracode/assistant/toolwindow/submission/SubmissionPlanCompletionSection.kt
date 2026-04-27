package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.UiIntent
import com.auracode.assistant.toolwindow.shared.DesignPalette
import com.auracode.assistant.toolwindow.shared.assistantUiTokens

internal data class PlanCompletionHeaderUiModel(
    val title: String,
)

internal fun planCompletionActionEmphasisStyle(): SubmissionCardActionEmphasisStyle =
    SubmissionCardActionEmphasisStyle.SUBTLE_HIGHLIGHT

internal fun planCompletionHeaderUiModel(state: SubmissionPlanCompletionState): PlanCompletionHeaderUiModel {
    return PlanCompletionHeaderUiModel(title = state.planTitle)
}

internal fun planCompletionInteractionFocusTarget(
    selectedAction: PlanCompletionAction,
): SubmissionInteractionFocusTarget {
    return restoreSubmissionInteractionFocusTarget(preferInput = selectedAction == PlanCompletionAction.REVISION)
}

@Composable
internal fun SubmissionPlanCompletionSection(
    p: DesignPalette,
    state: SubmissionPlanCompletionState,
    onIntent: (UiIntent) -> Unit,
) {
    val t = assistantUiTokens()
    val cardFocusRequester = FocusRequester()
    val revisionFocusRequester = FocusRequester()
    val revisionInputState = rememberInlineTextFieldValue(
        identityKey = state.turnId,
        text = state.revisionDraft,
    )
    val headerUiModel = planCompletionHeaderUiModel(state)

    LaunchedEffect(state.turnId, state.selectedAction) {
        withFrameNanos { }
        restoreSubmissionInteractionFocus(
            target = planCompletionInteractionFocusTarget(state.selectedAction),
            cardFocusRequester = cardFocusRequester,
            inputFocusRequester = revisionFocusRequester,
        )
    }

    SubmissionInteractionCard(
        p = p,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = t.spacing.md,
            vertical = t.spacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
        onRequestFocus = {
            restoreSubmissionInteractionFocus(
                target = planCompletionInteractionFocusTarget(state.selectedAction),
                cardFocusRequester = cardFocusRequester,
                inputFocusRequester = revisionFocusRequester,
            )
        },
        modifier = Modifier
            .focusRequester(cardFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight,
                    Key.DirectionDown,
                    -> {
                        if (state.selectedAction != PlanCompletionAction.REVISION) {
                            onIntent(UiIntent.MovePlanCompletionSelectionNext)
                            true
                        } else {
                            false
                        }
                    }

                    Key.DirectionLeft,
                    Key.DirectionUp,
                    -> {
                        if (state.selectedAction != PlanCompletionAction.REVISION) {
                            onIntent(UiIntent.MovePlanCompletionSelectionPrevious)
                            true
                        } else {
                            false
                        }
                    }

                    Key.Enter,
                    Key.NumPadEnter,
                    -> {
                        if (state.selectedAction != PlanCompletionAction.REVISION) {
                            when (state.selectedAction) {
                                PlanCompletionAction.EXECUTE -> onIntent(UiIntent.ExecuteApprovedPlan)
                                PlanCompletionAction.CANCEL -> onIntent(UiIntent.DismissPlanCompletionPrompt)
                                PlanCompletionAction.REVISION -> false
                            }
                            true
                        } else {
                            false
                        }
                    }

                    Key.Escape -> {
                        onIntent(UiIntent.DismissPlanCompletionPrompt)
                        true
                    }

                    else -> false
                }
            },
    ) {
        Text(
            text = AuraCodeBundle.message("composer.planCompletion.badge"),
            color = p.textSecondary,
        )
        Text(
            text = headerUiModel.title,
            color = p.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(t.spacing.xs),
        ) {
            SubmissionCardAction(
                label = AuraCodeBundle.message("composer.planCompletion.execute"),
                emphasized = state.selectedAction == PlanCompletionAction.EXECUTE,
                p = p,
                emphasisStyle = planCompletionActionEmphasisStyle(),
                showKeyboardHintIcon = shouldShowPlanCompletionKeyboardHint(
                    action = PlanCompletionAction.EXECUTE,
                    selectedAction = state.selectedAction,
                ),
                modifier = Modifier.fillMaxWidth(),
                compactVerticalPadding = 8.dp,
                onClick = { onIntent(UiIntent.ExecuteApprovedPlan) },
            )
            SubmissionCardAction(
                label = AuraCodeBundle.message("composer.planCompletion.cancel"),
                emphasized = state.selectedAction == PlanCompletionAction.CANCEL,
                p = p,
                emphasisStyle = planCompletionActionEmphasisStyle(),
                showKeyboardHintIcon = shouldShowPlanCompletionKeyboardHint(
                    action = PlanCompletionAction.CANCEL,
                    selectedAction = state.selectedAction,
                ),
                modifier = Modifier.fillMaxWidth(),
                compactVerticalPadding = 8.dp,
                onClick = { onIntent(UiIntent.DismissPlanCompletionPrompt) },
            )
            if (state.selectedAction == PlanCompletionAction.REVISION) {
                InlineSubmissionInputChoice(
                    value = revisionInputState.value,
                    placeholder = AuraCodeBundle.message("composer.planCompletion.revisionPlaceholder"),
                    emphasized = true,
                    isSecret = false,
                    p = p,
                    showKeyboardHintIcon = shouldShowPlanCompletionKeyboardHint(
                        action = PlanCompletionAction.REVISION,
                        selectedAction = state.selectedAction,
                    ),
                    focusRequester = revisionFocusRequester,
                    modifier = Modifier.fillMaxWidth(),
                    onValueChange = { value ->
                        revisionInputState.value = value
                        onIntent(UiIntent.EditPlanRevisionDraft(value.text))
                    },
                    onMovePrevious = {
                        onIntent(UiIntent.SelectPlanCompletionAction(PlanCompletionAction.CANCEL))
                        true
                    },
                    onMoveNext = {
                        onIntent(UiIntent.SelectPlanCompletionAction(PlanCompletionAction.EXECUTE))
                        true
                    },
                    onSubmit = {
                        if (!canSubmitInlineTextField(revisionInputState.value) || revisionInputState.value.text.isBlank()) {
                            false
                        } else {
                            onIntent(UiIntent.SubmitPlanRevision)
                            true
                        }
                    },
                    onCancel = {
                        onIntent(UiIntent.DismissPlanCompletionPrompt)
                        true
                    },
                )
            } else {
                SubmissionCardAction(
                    label = state.revisionDraft.ifBlank {
                        AuraCodeBundle.message("composer.planCompletion.revisionPlaceholder")
                    },
                    emphasized = state.selectedAction == PlanCompletionAction.REVISION,
                    p = p,
                    emphasisStyle = planCompletionActionEmphasisStyle(),
                    showKeyboardHintIcon = shouldShowPlanCompletionKeyboardHint(
                        action = PlanCompletionAction.REVISION,
                        selectedAction = state.selectedAction,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    compactVerticalPadding = 8.dp,
                    onClick = { onIntent(UiIntent.SelectPlanCompletionAction(PlanCompletionAction.REVISION)) },
                )
            }
        }
        Text(
            text = AuraCodeBundle.message("composer.planCompletion.footer"),
            color = p.textMuted,
        )
    }
}
