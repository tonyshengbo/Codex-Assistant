package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.toolwindow.eventing.AppEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PlanCompletionPromptStore {
    private val _state = MutableStateFlow(PlanCompletionPromptState())
    val state: StateFlow<PlanCompletionPromptState> = _state.asStateFlow()

    fun onEvent(event: AppEvent) {
        when (event) {
            is AppEvent.PlanCompletionPromptUpdated -> {
                _state.value = PlanCompletionPromptState(
                    current = event.prompt,
                    visible = event.prompt != null,
                )
            }

            is AppEvent.PromptAccepted,
            AppEvent.ConversationReset,
            -> _state.value = PlanCompletionPromptState()

            else -> Unit
        }
    }
}
