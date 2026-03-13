package com.codex.assistant.provider

import com.codex.assistant.model.AgentRequest
import com.codex.assistant.model.EngineEvent
import kotlinx.coroutines.flow.Flow

interface AgentProvider {
    fun stream(request: AgentRequest): Flow<EngineEvent>
    fun cancel(requestId: String)
}
