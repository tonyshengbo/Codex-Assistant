package com.auracode.assistant.provider

data class EngineCapabilities(
    val supportsThinking: Boolean,
    val supportsToolEvents: Boolean,
    val supportsCommandProposal: Boolean,
    val supportsDiffProposal: Boolean,
    val supportsReasoningEffortSelection: Boolean = true,
)

data class EngineDescriptor(
    val id: String,
    val displayName: String,
    val models: List<String>,
    val capabilities: EngineCapabilities,
)

interface AgentProviderFactory {
    val engineId: String
    fun create(): AgentProvider
}
