package com.auracode.assistant.provider

import com.auracode.assistant.provider.claude.ClaudeModelCatalog
import com.auracode.assistant.provider.claude.ClaudeProviderFactory
import com.auracode.assistant.provider.codex.CodexAppServerProvider
import com.auracode.assistant.provider.codex.CodexModelCatalog
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import java.util.concurrent.ConcurrentHashMap

private val codexCapabilities = EngineCapabilities(
    supportsThinking = true,
    supportsToolEvents = true,
    supportsCommandProposal = true,
    supportsDiffProposal = true,
    supportsReasoningEffortSelection = true,
)

private val claudeCapabilities = EngineCapabilities(
    supportsThinking = true,
    supportsToolEvents = false,
    supportsCommandProposal = false,
    supportsDiffProposal = false,
    supportsReasoningEffortSelection = false,
)

/** Registers the Codex provider implementation behind the shared provider factory contract. */
class CodexProviderFactory(private val settings: AgentSettingsService) : AgentProviderFactory {
    override val engineId: String = ENGINE_ID

    override fun create(): AgentProvider = CodexAppServerProvider(settings)

    companion object {
        const val ENGINE_ID: String = "codex"
    }
}

/** Resolves engine descriptors and lazily instantiates provider implementations. */
class ProviderRegistry(
    descriptors: List<EngineDescriptor>,
    factories: List<AgentProviderFactory>,
    private val defaultEngineId: String,
) {
    private val descriptorMap = descriptors.associateBy { it.id }
    private val factoryMap = factories.associateBy { it.engineId }
    private val providers = ConcurrentHashMap<String, AgentProvider>()

    constructor(settings: AgentSettingsService) : this(
        descriptors = listOf(
            EngineDescriptor(
                id = ClaudeProviderFactory.ENGINE_ID,
                displayName = "Claude",
                models = ClaudeModelCatalog.ids(),
                capabilities = claudeCapabilities,
            ),
            EngineDescriptor(
                id = CodexProviderFactory.ENGINE_ID,
                displayName = "Codex",
                models = CodexModelCatalog.ids(),
                capabilities = codexCapabilities,
            ),
        ),
        factories = listOf(
            ClaudeProviderFactory(settings),
            CodexProviderFactory(settings),
        ),
        defaultEngineId = CodexProviderFactory.ENGINE_ID,
    )

    fun engines(): List<EngineDescriptor> = descriptorMap.values.sortedBy { it.displayName }

    fun defaultEngineId(): String = defaultEngineId

    fun engine(engineId: String): EngineDescriptor? = descriptorMap[engineId]

    fun providerOrNull(engineId: String): AgentProvider? {
        val factory = factoryMap[engineId] ?: return null
        return providers.computeIfAbsent(engineId) { factory.create() }
    }

    fun providerOrDefault(engineId: String): AgentProvider {
        return providerOrNull(engineId) ?: providerOrNull(defaultEngineId)
        ?: error("No provider is registered for default engine '$defaultEngineId'.")
    }

    fun cancel(requestId: String) {
        providers.values.forEach { it.cancel(requestId) }
    }

    fun submitApprovalDecision(requestId: String, decision: ApprovalAction): Boolean {
        return providers.values.any { it.submitApprovalDecision(requestId, decision) }
    }

    fun submitToolUserInput(
        requestId: String,
        answers: Map<String, UnifiedToolUserInputAnswerDraft>,
    ): Boolean {
        return providers.values.any { it.submitToolUserInput(requestId, answers) }
    }
}
