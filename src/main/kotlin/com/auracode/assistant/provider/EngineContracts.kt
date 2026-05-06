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
    /** slug → display name 映射，用于动态获取的模型展示名，默认空 map 保持向后兼容。 */
    val modelDisplayNames: Map<String, String> = emptyMap(),
    val capabilities: EngineCapabilities,
)

internal interface AgentProviderFactory {
    val engineId: String
    fun create(): AgentProvider
}
