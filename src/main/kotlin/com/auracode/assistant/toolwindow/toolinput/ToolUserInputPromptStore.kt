package com.auracode.assistant.toolwindow.toolinput

import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.eventing.UiIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ToolUserInputPromptStore {
    private val _state = MutableStateFlow(ToolUserInputPromptState())
    val state: StateFlow<ToolUserInputPromptState> = _state.asStateFlow()

    fun restoreState(state: ToolUserInputPromptState) {
        _state.value = state
    }

    fun onEvent(event: AppEvent) {
        when (event) {
            is AppEvent.ToolUserInputRequested -> enqueue(event.prompt)
            is AppEvent.ToolUserInputResolved -> resolve(event.requestId)
            AppEvent.ClearToolUserInputs,
            is AppEvent.PromptAccepted,
            AppEvent.ConversationReset,
            -> _state.value = ToolUserInputPromptState()

            is AppEvent.UiIntentPublished -> handleUiIntent(event.intent)
            else -> Unit
        }
    }

    private fun enqueue(prompt: ToolUserInputPromptUiModel) {
        val previous = _state.value
        val queue = (previous.queue + prompt).reindexed()
        val current = previous.current ?: queue.firstOrNull()
        _state.value = ToolUserInputPromptState(
            queue = queue,
            current = current,
            answerDrafts = if (previous.current == null) {
                current?.emptyDrafts().orEmpty()
            } else {
                previous.answerDrafts
            },
            activeQuestionIndex = 0,
            visible = current != null,
        ).syncSelection()
    }

    private fun resolve(requestId: String) {
        val previous = _state.value
        val wasCurrent = previous.current?.requestId == requestId
        val queue = previous.queue.filterNot { it.requestId == requestId }.reindexed()
        val current = if (wasCurrent) queue.firstOrNull() else previous.current
        _state.value = ToolUserInputPromptState(
            queue = queue,
            current = current,
            answerDrafts = if (wasCurrent) {
                current?.emptyDrafts().orEmpty()
            } else {
                previous.answerDrafts
            },
            activeQuestionIndex = 0,
            visible = current != null,
        ).syncSelection()
    }

    private fun handleUiIntent(intent: UiIntent) {
        val current = _state.value.current ?: return
        when (intent) {
            is UiIntent.SelectToolUserInputOption -> {
                val question = current.questions.firstOrNull { it.id == intent.questionId } ?: return
                val choiceIndex = question.presentedChoices().indexOfFirst { it.label == intent.optionLabel }
                val currentDraft = _state.value.answerDrafts[intent.questionId].orEmpty()
                val nextDraft = currentDraft.copy(
                    selectedOptionLabel = intent.optionLabel,
                    textValue = if (question.optionRequiresText(intent.optionLabel)) {
                        currentDraft.textValue
                    } else {
                        ""
                    },
                )
                _state.value = _state.value.copy(
                    answerDrafts = _state.value.answerDrafts + (intent.questionId to nextDraft),
                    activeChoiceIndex = choiceIndex.takeIf { it >= 0 } ?: _state.value.activeChoiceIndex,
                ).syncSelection()
            }

            is UiIntent.EditToolUserInputAnswer -> {
                val question = current.questions.firstOrNull { it.id == intent.questionId } ?: return
                val currentDraft = _state.value.answerDrafts[intent.questionId].orEmpty()
                val activeChoice = _state.value.takeIf { it.activeQuestion?.id == intent.questionId }?.activeChoice
                val selected = when {
                    question.options.isEmpty() -> null
                    activeChoice?.kind == ToolUserInputChoiceKind.FREEFORM -> activeChoice.label
                    currentDraft.selectedOptionLabel == null -> TOOL_USER_INPUT_OTHER_OPTION
                    else -> currentDraft.selectedOptionLabel
                }
                _state.value = _state.value.copy(
                    answerDrafts = _state.value.answerDrafts + (
                        intent.questionId to currentDraft.copy(
                            selectedOptionLabel = selected,
                            textValue = intent.value,
                        )
                    ),
                ).syncSelection()
            }

            UiIntent.MoveToolUserInputSelectionNext -> {
                val choices = _state.value.activeQuestion?.presentedChoices().orEmpty()
                if (choices.isEmpty()) return
                _state.value = _state.value.copy(
                    activeChoiceIndex = (_state.value.activeChoiceIndex + 1) % choices.size,
                ).syncSelection()
            }

            UiIntent.MoveToolUserInputSelectionPrevious -> {
                val choices = _state.value.activeQuestion?.presentedChoices().orEmpty()
                if (choices.isEmpty()) return
                _state.value = _state.value.copy(
                    activeChoiceIndex = (_state.value.activeChoiceIndex - 1 + choices.size) % choices.size,
                ).syncSelection()
            }

            UiIntent.AdvanceToolUserInputPrompt -> {
                val state = _state.value
                if (!state.canAdvance) return
                _state.value = state.copy(
                    activeQuestionIndex = (state.activeQuestionIndex + 1).coerceAtMost(current.questions.lastIndex),
                    activeChoiceIndex = 0,
                    freeformActive = false,
                ).syncSelection()
            }

            UiIntent.RetreatToolUserInputPrompt -> {
                val state = _state.value
                if (!state.canRetreat) return
                _state.value = state.copy(
                    activeQuestionIndex = (state.activeQuestionIndex - 1).coerceAtLeast(0),
                    activeChoiceIndex = 0,
                    freeformActive = false,
                ).syncSelection()
            }

            else -> Unit
        }
    }

    private fun ToolUserInputAnswerDraftUiModel?.orEmpty(): ToolUserInputAnswerDraftUiModel {
        return this ?: ToolUserInputAnswerDraftUiModel()
    }

    private fun List<ToolUserInputPromptUiModel>.reindexed(): List<ToolUserInputPromptUiModel> {
        val total = size
        return mapIndexed { index, prompt ->
            prompt.copy(queuePosition = index + 1, queueSize = total)
        }
    }
}
