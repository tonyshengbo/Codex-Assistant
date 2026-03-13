package com.codex.assistant.provider

import com.codex.assistant.model.EngineEvent

data class EngineCapabilities(
    val supportsThinking: Boolean,
    val supportsToolEvents: Boolean,
    val supportsCommandProposal: Boolean,
    val supportsDiffProposal: Boolean,
)

data class EngineDescriptor(
    val id: String,
    val displayName: String,
    val models: List<String>,
    val capabilities: EngineCapabilities,
)

interface CliInvocationSpec {
    fun buildCommand(executablePath: String, request: com.codex.assistant.model.AgentRequest, prompt: String): List<String>
}

interface StructuredEventParser {
    fun parse(line: String): EngineEvent?
    fun shouldEmitUnparsedLine(line: String): Boolean = false
    fun unparsedLineEvent(line: String): EngineEvent = EngineEvent.AssistantTextDelta(line + "\n")
}

interface AgentProviderFactory {
    val engineId: String
    fun create(): AgentProvider
}
