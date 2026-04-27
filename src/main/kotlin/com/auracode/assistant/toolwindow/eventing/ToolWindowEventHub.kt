package com.auracode.assistant.toolwindow.eventing

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

internal class ToolWindowEventHub {
    private val _eventLog = MutableStateFlow<List<AppEvent>>(emptyList())
    val events: StateFlow<List<AppEvent>> = _eventLog.asStateFlow()

    private val _stream = Channel<AppEvent>(capacity = Channel.UNLIMITED)
    val stream: Flow<AppEvent> = _stream.receiveAsFlow()

    fun publishUiIntent(intent: UiIntent) {
        publish(AppEvent.UiIntentPublished(intent))
    }

    fun publish(event: AppEvent) {
        _eventLog.value = _eventLog.value + event
        _stream.trySend(event)
    }
}
