package com.auracode.assistant.toolwindow.status

import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.toolwindow.eventing.AppEvent
import com.auracode.assistant.toolwindow.shared.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TurnStatusUiState(
    val label: UiText,
    val startedAtMs: Long,
    val turnId: String? = null,
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

    fun restoreState(state: StatusAreaState) {
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

    private fun startTurnStatus(turnId: String?) {
        _state.value = _state.value.copy(
            turnStatus = TurnStatusUiState(
                label = UiText.Bundle("status.running"),
                startedAtMs = System.currentTimeMillis(),
                turnId = turnId?.trim().takeIf { !it.isNullOrBlank() },
            ),
        )
    }

    private fun mapUnifiedState(event: UnifiedEvent, current: StatusAreaState): StatusAreaState {
        return when (event) {
            is UnifiedEvent.Error -> {
                if (event.terminal) {
                    current.copy(
                        turnStatus = null,
                        toast = null,
                    )
                } else {
                    current.copy(
                        // Non-terminal errors are retry notices from the app-server. We still show the
                        // toast, but we keep the running turn alive so automatic recovery can continue.
                        turnStatus = current.turnStatus,
                        toast = ToastUiState(
                            id = System.currentTimeMillis(),
                            text = UiText.Raw(event.message),
                            createdAtMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }

            is UnifiedEvent.TurnStarted -> {
                current.copy(
                    turnStatus = (current.turnStatus ?: TurnStatusUiState(
                        label = UiText.Bundle("status.running"),
                        startedAtMs = System.currentTimeMillis(),
                    )).copy(
                        label = UiText.Bundle("status.running"),
                        turnId = event.turnId,
                    ),
                )
            }

            is UnifiedEvent.ToolUserInputRequested -> current.copy(
                turnStatus = current.turnStatus?.copy(label = UiText.Bundle("status.waitingInput")),
            )

            is UnifiedEvent.ToolUserInputResolved -> current.copy(
                turnStatus = current.turnStatus?.copy(label = UiText.Bundle("status.running")),
            )

            is UnifiedEvent.TurnCompleted -> {
                val activeTurnStatus = current.turnStatus
                when {
                    activeTurnStatus == null -> current
                    activeTurnStatus.turnId.isNullOrBlank() -> current.copy(turnStatus = null)
                    activeTurnStatus.turnId == event.turnId -> current.copy(turnStatus = null)
                    else -> current
                }
            }
            else -> current
        }
    }
}
