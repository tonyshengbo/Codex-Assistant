package com.auracode.assistant.toolwindow.execution

import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.shared.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ExecutionTurnStatusUiState(
    val label: UiText,
    val startedAtMs: Long,
    val turnId: String? = null,
)

internal data class ExecutionToastUiState(
    val id: Long,
    val text: UiText,
    val createdAtMs: Long,
)

internal data class ExecutionStatusAreaState(
    val turnStatus: ExecutionTurnStatusUiState? = null,
    val toast: ExecutionToastUiState? = null,
)

internal class ExecutionStatusAreaStore {
    private val _state = MutableStateFlow(ExecutionStatusAreaState())
    val state: StateFlow<ExecutionStatusAreaState> = _state.asStateFlow()

    fun restoreState(state: ExecutionStatusAreaState) {
        _state.value = state
    }

    fun onEvent(event: AppEvent) {
        when (event) {
            is AppEvent.PromptAccepted -> {
                startTurnStatus(event.localTurnId)
            }

            is AppEvent.ToolUserInputRequested -> {
                _state.value = _state.value.copy(
                    turnStatus = _state.value.turnStatus?.copy(label = UiText.Bundle("status.waitingInput")),
                )
            }

            is AppEvent.ToolUserInputResolved -> {
                _state.value = _state.value.copy(
                    turnStatus = _state.value.turnStatus?.copy(label = UiText.Bundle("status.running")),
                )
            }

            AppEvent.ActiveRunCancelled -> {
                _state.value = _state.value.copy(turnStatus = null)
            }

            AppEvent.ConversationReset -> {
                _state.value = ExecutionStatusAreaState()
            }

            is AppEvent.StatusTextUpdated -> {
                _state.value = _state.value.copy(
                    toast = ExecutionToastUiState(
                        id = System.currentTimeMillis(),
                        text = event.text,
                        createdAtMs = System.currentTimeMillis(),
                    ),
                )
            }

            is AppEvent.ExecutionUiProjectionUpdated -> {
                _state.value = _state.value.copy(
                    turnStatus = event.turnStatus,
                )
            }

            else -> Unit
        }
    }

    private fun startTurnStatus(turnId: String?) {
        _state.value = _state.value.copy(
            turnStatus = ExecutionTurnStatusUiState(
                label = UiText.Bundle("status.running"),
                startedAtMs = System.currentTimeMillis(),
                turnId = turnId?.trim().takeIf { !it.isNullOrBlank() },
            ),
        )
    }
}
