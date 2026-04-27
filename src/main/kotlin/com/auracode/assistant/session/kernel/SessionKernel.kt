package com.auracode.assistant.session.kernel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns one session state instance and keeps live and replayed events converged.
 */
internal class SessionKernel(
    private val sessionId: String,
    private val engineId: String,
    private val reducer: SessionStateReducer = SessionStateReducer(),
    private val runtimeRegistry: SessionRuntimeRegistry? = null,
) {
    private val stateLock = Any()
    private val _state = MutableStateFlow(
        SessionState.empty(
            sessionId = sessionId,
            engineId = engineId,
        ),
    )
    private val eventLog = mutableListOf<SessionDomainEvent>()

    /** Exposes the latest immutable session state snapshot. */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Returns the latest session state snapshot synchronously. */
    val currentState: SessionState
        get() = synchronized(stateLock) { _state.value }

    /** Applies one live domain event incrementally. */
    fun applyLiveEvent(event: SessionDomainEvent) {
        synchronized(stateLock) {
            eventLog += event
            _state.value = reducer.reduce(_state.value, event)
            syncRuntimeBinding()
        }
    }

    /** Applies a batch of live domain events incrementally. */
    fun applyLiveEvents(events: Iterable<SessionDomainEvent>) {
        synchronized(stateLock) {
            events.forEach { event ->
                eventLog += event
                _state.value = reducer.reduce(_state.value, event)
            }
            syncRuntimeBinding()
        }
    }

    /** Rebuilds the session state from a full history snapshot. */
    fun restoreHistory(events: List<SessionDomainEvent>) {
        synchronized(stateLock) {
            eventLog.clear()
            eventLog += events
            _state.value = reducer.reduceAll(
                initialState = SessionState.empty(
                    sessionId = sessionId,
                    engineId = engineId,
                ),
                events = eventLog,
            )
            syncRuntimeBinding()
        }
    }

    /** Prepends older history ahead of the current event log and rebuilds state from scratch. */
    fun prependHistory(olderEvents: List<SessionDomainEvent>) {
        if (olderEvents.isEmpty()) {
            return
        }
        synchronized(stateLock) {
            val combinedEvents = olderEvents + eventLog
            eventLog.clear()
            eventLog += combinedEvents
            _state.value = reducer.reduceAll(
                initialState = SessionState.empty(
                    sessionId = sessionId,
                    engineId = engineId,
                ),
                events = eventLog,
            )
            syncRuntimeBinding()
        }
    }

    /** Dispatches supported kernel commands without introducing UI or provider dependencies. */
    fun dispatch(command: SessionCommand) {
        require(command.sessionId == sessionId) {
            "Session command targeted ${command.sessionId}, but kernel owns $sessionId."
        }
        when (command) {
            is SessionCommand.ReplayHistory -> restoreHistory(command.events)
            is SessionCommand.LoadOlderHistory -> prependHistory(command.olderEvents)
            else -> Unit
        }
    }

    /** Returns the currently stored domain-event log for verification or replay. */
    fun snapshotEventLog(): List<SessionDomainEvent> {
        return synchronized(stateLock) { eventLog.toList() }
    }

    /** Mirrors the current runtime slice into the optional registry. */
    private fun syncRuntimeBinding() {
        runtimeRegistry?.update(
            SessionRuntimeBinding(
                sessionId = currentState.sessionId,
                engineId = currentState.engineId,
                threadId = currentState.runtime.activeThreadId,
                turnId = currentState.runtime.activeTurnId,
                runStatus = currentState.runtime.runStatus,
            ),
        )
    }
}
