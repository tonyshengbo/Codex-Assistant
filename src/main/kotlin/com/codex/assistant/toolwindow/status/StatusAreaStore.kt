package com.codex.assistant.toolwindow.status

import com.codex.assistant.protocol.UnifiedEvent
import com.codex.assistant.toolwindow.eventing.AppEvent
import com.codex.assistant.toolwindow.shared.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TurnStatusUiState(
    val label: UiText,
    val startedAtMs: Long,
)

internal data class ToastUiState(
    val id: Long,
    val text: UiText,
    val createdAtMs: Long,
)

internal data class StatusAreaState(
    val turnStatus: TurnStatusUiState? = null,
    val toast: ToastUiState? = null,
)

internal class StatusAreaStore {
    private val _state = MutableStateFlow(StatusAreaState())
    val state: StateFlow<StatusAreaState> = _state.asStateFlow()

    fun onEvent(event: AppEvent) {
        when (event) {
            is AppEvent.PromptAccepted -> {
                startTurnStatus()
            }

            AppEvent.ConversationReset -> {
                _state.value = StatusAreaState()
            }

            is AppEvent.UnifiedEventPublished -> {
                _state.value = mapUnifiedState(event.event, _state.value)
            }

            is AppEvent.StatusTextUpdated -> {
                _state.value = _state.value.copy(
                    toast = ToastUiState(
                        id = System.currentTimeMillis(),
                        text = event.text,
                        createdAtMs = System.currentTimeMillis(),
                    ),
                )
            }

            else -> Unit
        }
    }

    private fun startTurnStatus() {
        if (_state.value.turnStatus != null) return
        _state.value = _state.value.copy(
            turnStatus = TurnStatusUiState(
                label = UiText.Bundle("status.running"),
                startedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun mapUnifiedState(event: UnifiedEvent, current: StatusAreaState): StatusAreaState {
        return when (event) {
            is UnifiedEvent.Error -> current.copy(
                turnStatus = null,
                toast = ToastUiState(
                    id = System.currentTimeMillis(),
                    text = UiText.Raw(event.message),
                    createdAtMs = System.currentTimeMillis(),
                ),
            )

            is UnifiedEvent.TurnStarted -> {
                if (current.turnStatus != null) current else {
                    current.copy(
                        turnStatus = TurnStatusUiState(
                            label = UiText.Bundle("status.running"),
                            startedAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            is UnifiedEvent.TurnCompleted -> current.copy(turnStatus = null)
            else -> current
        }
    }
}
