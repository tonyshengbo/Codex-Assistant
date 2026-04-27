package com.auracode.assistant.toolwindow.sessions

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.toolwindow.eventing.AppEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class SessionTabsAreaState(
    val title: String = "",
    val canCreateNewSession: Boolean = false,
)

internal class SessionTabsAreaStore {
    private val _state = MutableStateFlow(SessionTabsAreaState())
    val state: StateFlow<SessionTabsAreaState> = _state.asStateFlow()

    fun onEvent(event: AppEvent) {
        when (event) {
            is AppEvent.SessionSnapshotUpdated -> {
                if (event.activeSessionId.isBlank()) return
                val active = event.sessions.firstOrNull { it.id == event.activeSessionId }
                val title = active?.title?.trim().orEmpty()
                val canCreate = (active?.messageCount ?: 0) > 0
                _state.value = _state.value.copy(
                    title = title,
                    canCreateNewSession = canCreate,
                )
            }

            else -> Unit
        }
    }
}
