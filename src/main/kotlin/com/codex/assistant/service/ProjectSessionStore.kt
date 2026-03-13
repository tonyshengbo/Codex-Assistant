package com.codex.assistant.service

import com.codex.assistant.model.MessageRole
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.PROJECT)
@State(name = "CodexAssistantProjectSessions", storages = [Storage("codex-assistant-sessions.xml")])
class ProjectSessionStore : PersistentStateComponent<ProjectSessionStore.State> {
    data class MessageState(
        var id: String = "",
        var role: String = MessageRole.SYSTEM.name,
        var content: String = "",
        var timestamp: Long = 0,
        var timelineActionsPayload: String = "",
    )

    data class UsageSnapshotState(
        var model: String = "",
        var contextWindow: Int = 0,
        var inputTokens: Int = 0,
        var cachedInputTokens: Int = 0,
        var outputTokens: Int = 0,
        var capturedAt: Long = 0,
    )

    data class SessionState(
        var id: String = "",
        var title: String = "New Session",
        var updatedAt: Long = 0,
        var cliSessionId: String = "",
        var usageSnapshot: UsageSnapshotState? = null,
        // Legacy field kept only to migrate older response-based session state.
        var continuationResponseId: String = "",
        var messages: MutableList<MessageState> = mutableListOf(),
    )

    data class State(
        var currentSessionId: String = "",
        var sessions: MutableList<SessionState> = mutableListOf(),
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    fun readState(): State = state

    fun saveState(newState: State) {
        state = newState
    }
}
