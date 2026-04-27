package com.auracode.assistant.toolwindow.submission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.focusable
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.auracode.assistant.toolwindow.shared.HoverTooltip
import com.auracode.assistant.toolwindow.shared.assistantUiTokens
import com.auracode.assistant.toolwindow.execution.ToolUserInputAnswerDraftUiModel
import com.auracode.assistant.toolwindow.execution.ToolUserInputChoiceKind
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptState
import com.auracode.assistant.toolwindow.execution.TOOL_USER_INPUT_OTHER_OPTION
import com.auracode.assistant.toolwindow.execution.inlineDescription
import com.auracode.assistant.toolwindow.execution.presentedChoices
import androidx.compose.runtime.withFrameNanos

@Composable
internal fun SubmissionToolUserInputSection(
    p: DesignPalette,
    state: ToolUserInputPromptState,
    onIntent: (UiIntent) -> Unit,
) {
    val current = state.current ?: return
    val question = state.activeQuestion ?: return
    val t = assistantUiTokens()
    val cardFocusRequester = FocusRequester()
    val inputFocusRequester = FocusRequester()
    val draft = state.answerDrafts[question.id] ?: ToolUserInputAnswerDraftUiModel()
    val choices = question.presentedChoices()
    val otherPlaceholder = AuraCodeBundle.message("toolInput.prompt.otherDescription")
    val inputState = rememberInlineTextFieldValue(
        identityKey = "${current.requestId}:${state.activeQuestionIndex}:${state.activeChoiceIndex}",
        text = draft.textValue,
    )

    LaunchedEffect(current.requestId, state.activeQuestionIndex, state.freeformActive) {
        withFrameNanos { }
        if (state.freeformActive || choices.isEmpty()) {
            inputFocusRequester.requestFocus()
        } else {
            cardFocusRequester.requestFocus()
        }
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
                target = restoreSubmissionInteractionFocusTarget(preferInput = state.freeformActive || choices.isEmpty()),
                cardFocusRequester = cardFocusRequester,
                inputFocusRequester = inputFocusRequester,
            )
        },
        modifier = Modifier
            .focusRequester(cardFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        if (choices.isNotEmpty() && !state.freeformActive) {
                            onIntent(UiIntent.MoveToolUserInputSelectionNext)
                            true
                        } else {
                            false
                        }
                    }

                    Key.DirectionUp -> {
                        if (choices.isNotEmpty() && !state.freeformActive) {
                            onIntent(UiIntent.MoveToolUserInputSelectionPrevious)
                            true
                        } else {
                            false
                        }
                    }

                    Key.DirectionRight,
                    -> if (!state.freeformActive && choices.isNotEmpty()) {
                        onIntent(UiIntent.MoveToolUserInputSelectionNext)
                        true
                    } else {
                        false
                    }

                    Key.DirectionLeft,
                    -> if (!state.freeformActive && choices.isNotEmpty()) {
                        onIntent(UiIntent.MoveToolUserInputSelectionPrevious)
                        true
                    } else {
                        false
                    }

                    Key.Enter,
                    Key.NumPadEnter,
                    -> if (state.freeformActive || choices.isEmpty()) {
                        false
                    } else {
                        handleToolUserInputPrimaryAction(
                            state = state,
                            onIntent = onIntent,
                        )
                    }

                    Key.Escape -> {
                        onIntent(UiIntent.CancelToolUserInputPrompt)
                        true
                    }

                    else -> false
                }
            },
    ) {
        Text(
            text = AuraCodeBundle.message("toolInput.prompt.badge") +
                if (current.queueSize > 1) " ${current.queuePosition}/${current.queueSize}" else "",
            color = p.textSecondary,
        )
        Text(
            text = "${question.header} ${state.activeQuestionIndex + 1}/${current.questions.size}",
            color = p.textSecondary,
        )
        Text(
            text = question.question,
            color = p.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        if (choices.isNotEmpty()) {
            choices.forEachIndexed { index, choice ->
                if (choice.kind == ToolUserInputChoiceKind.FREEFORM && index == state.activeChoiceIndex) {
                    InlineSubmissionInputChoice(
                        value = inputState.value,
                        placeholder = otherPlaceholder,
                        emphasized = true,
                        isSecret = question.isSecret,
                        p = p,
                        showKeyboardHintIcon = shouldShowToolUserInputKeyboardHint(
                            kind = choice.kind,
                            index = index,
                            activeChoiceIndex = state.activeChoiceIndex,
                        ),
                        focusRequester = inputFocusRequester,
                        modifier = Modifier.fillMaxWidth(),
                        onValueChange = { value ->
                            inputState.value = value
                            onIntent(UiIntent.EditToolUserInputAnswer(question.id, value.text))
                        },
                        onMovePrevious = {
                            onIntent(UiIntent.MoveToolUserInputSelectionPrevious)
                            true
                        },
                        onMoveNext = {
                            onIntent(UiIntent.MoveToolUserInputSelectionNext)
                            true
                        },
                        onSubmit = {
                            if (!canSubmitInlineTextField(inputState.value)) {
                                false
                            } else if (state.canAdvance) {
                                onIntent(UiIntent.AdvanceToolUserInputPrompt)
                                true
                            } else if (state.canSubmit) {
                                onIntent(UiIntent.SubmitToolUserInputPrompt)
                                true
                            } else {
                                false
                            }
                        },
                        onCancel = {
                            onIntent(UiIntent.CancelToolUserInputPrompt)
                            true
                        },
                    )
                } else {
                    HoverTooltip(text = if (choice.kind == ToolUserInputChoiceKind.FIXED) choice.description else "") {
                        SubmissionCardAction(
                            label = freeformChoiceDisplayText(
                                label = choice.label,
                                kind = choice.kind,
                                textValue = draft.textValue,
                                placeholder = otherPlaceholder,
                            ),
                            description = choice.inlineDescription(isFocused = index == state.activeChoiceIndex),
                            emphasized = index == state.activeChoiceIndex,
                            p = p,
                            emphasisStyle = SubmissionCardActionEmphasisStyle.SUBTLE_HIGHLIGHT,
                            showKeyboardHintIcon = shouldShowToolUserInputKeyboardHint(
                                kind = choice.kind,
                                index = index,
                                activeChoiceIndex = state.activeChoiceIndex,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            compactVerticalPadding = 8.dp,
                            onClick = {
                                commitToolUserInputChoice(
                                    state = state,
                                    questionId = question.id,
                                    optionLabel = choice.label,
                                    onIntent = onIntent,
                                )
                            },
                        )
                    }
                }
            }
        }

        if (choices.isEmpty()) {
            InlineSubmissionInputChoice(
                value = inputState.value,
                placeholder = otherPlaceholder,
                emphasized = true,
                isSecret = question.isSecret,
                p = p,
                showKeyboardHintIcon = true,
                focusRequester = inputFocusRequester,
                modifier = Modifier.fillMaxWidth(),
                onValueChange = { value ->
                    inputState.value = value
                    onIntent(UiIntent.EditToolUserInputAnswer(question.id, value.text))
                },
                onMovePrevious = null,
                onMoveNext = null,
                onSubmit = {
                    if (!canSubmitInlineTextField(inputState.value)) {
                        false
                    } else if (state.canSubmit) {
                        onIntent(UiIntent.SubmitToolUserInputPrompt)
                        true
                    } else {
                        false
                    }
                },
                onCancel = {
                    onIntent(UiIntent.CancelToolUserInputPrompt)
                    true
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(t.spacing.sm),
        ) {
            if (state.canRetreat) {
                SubmissionCardAction(
                    label = AuraCodeBundle.message("toolInput.prompt.back"),
                    emphasized = false,
                    p = p,
                    // Keep footer actions balanced so the trailing cancel button does not collapse
                    // into a narrow column when both actions are visible.
                    modifier = Modifier.weight(1f),
                    compactVerticalPadding = 8.dp,
                    onClick = { onIntent(UiIntent.RetreatToolUserInputPrompt) },
                )
            }
            SubmissionCardAction(
                label = AuraCodeBundle.message("toolInput.prompt.cancel"),
                emphasized = false,
                p = p,
                modifier = Modifier.weight(1f),
                compactVerticalPadding = 8.dp,
                onClick = { onIntent(UiIntent.CancelToolUserInputPrompt) },
            )
        }

        Text(
            text = AuraCodeBundle.message("toolInput.prompt.footer"),
            color = p.textMuted,
        )
    }
}

private fun freeformChoiceDisplayText(
    label: String,
    kind: ToolUserInputChoiceKind,
    textValue: String,
    placeholder: String,
): String {
    return if (kind == ToolUserInputChoiceKind.FREEFORM) {
        textValue.ifBlank { placeholder }
    } else {
        label
    }
}

private fun handleToolUserInputPrimaryAction(
    state: ToolUserInputPromptState,
    onIntent: (UiIntent) -> Unit,
): Boolean {
    val choice = state.activeChoice ?: return false
    return if (state.freeformActive || state.activeQuestion?.options.isNullOrEmpty()) {
        when {
            state.canAdvance -> {
                onIntent(UiIntent.AdvanceToolUserInputPrompt)
                true
            }

            state.canSubmit -> {
                onIntent(UiIntent.SubmitToolUserInputPrompt)
                true
            }

            else -> false
        }
    } else {
        commitToolUserInputChoice(
            state = state,
            questionId = state.activeQuestion?.id ?: return false,
            optionLabel = choice.label,
            onIntent = onIntent,
        )
    }
}

private fun commitToolUserInputChoice(
    state: ToolUserInputPromptState,
    questionId: String,
    optionLabel: String,
    onIntent: (UiIntent) -> Unit,
): Boolean {
    onIntent(UiIntent.SelectToolUserInputOption(questionId, optionLabel))
    val choice = state.activeQuestion?.presentedChoices()?.firstOrNull { it.label == optionLabel }
    return when {
        choice == null -> false
        choice.kind == ToolUserInputChoiceKind.FREEFORM -> true
        state.isLastQuestion -> {
            onIntent(UiIntent.SubmitToolUserInputPrompt)
            true
        }

        else -> {
            onIntent(UiIntent.AdvanceToolUserInputPrompt)
            true
        }
    }
}
