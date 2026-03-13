package com.codex.assistant.provider

import com.codex.assistant.model.AgentRequest
import com.codex.assistant.settings.AgentSettingsService
import java.util.concurrent.ConcurrentHashMap

private val codexCapabilities = EngineCapabilities(
    supportsThinking = true,
    supportsToolEvents = true,
    supportsCommandProposal = false,
    supportsDiffProposal = false,
)

internal class CodexInvocationSpec : CliInvocationSpec {
    override fun buildCommand(executablePath: String, request: AgentRequest, prompt: String): List<String> {
        val command = mutableListOf(
            executablePath,
            "exec",
        )
        val sessionId = request.cliSessionId?.trim().orEmpty()
        if (sessionId.isNotBlank()) {
            command += "resume"
        }
        request.model
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                command += "-m"
                command += it
            }
        command += "--skip-git-repo-check"
        command += "--dangerously-bypass-approvals-and-sandbox"
        command += "--json"
        if (sessionId.isNotBlank()) {
            command += sessionId
        }
        command += prompt.trim()
        return command
    }
}

private class CodexEventParser : StructuredEventParser {
    override fun parse(line: String) = CliStructuredEventParser.parseCodexLine(line)

    override fun shouldEmitUnparsedLine(line: String): Boolean = line.isNotBlank()
}

class CodexCliProvider(settings: AgentSettingsService) : CliAgentProvider(
    executablePath = { settings.getState().executablePathFor(CodexProviderFactory.ENGINE_ID) },
    displayName = "Codex",
    invocationSpec = CodexInvocationSpec(),
    eventParser = CodexEventParser(),
)

class CodexProviderFactory(private val settings: AgentSettingsService) : AgentProviderFactory {
    override val engineId: String = ENGINE_ID

    override fun create(): AgentProvider = CodexCliProvider(settings)

    companion object {
        const val ENGINE_ID: String = "codex"
    }
}

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
                id = CodexProviderFactory.ENGINE_ID,
                displayName = "Codex",
                models = CodexModelCatalog.ids(),
                capabilities = codexCapabilities,
            ),
        ),
        factories = listOf(CodexProviderFactory(settings)),
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
}
