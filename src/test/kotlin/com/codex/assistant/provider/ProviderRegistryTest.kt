package com.codex.assistant.provider

import com.codex.assistant.settings.AgentSettingsService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderRegistryTest {
    @Test
    fun `default codex registry exposes curated model list without auto`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }

        val registry = ProviderRegistry(settings)

        assertEquals(
            listOf(
                "gpt-5.3-codex",
                "gpt-5.4",
                "gpt-5.2-codex",
                "gpt-5.1-codex-max",
                "gpt-5.1-codex-mini",
            ),
            registry.engine("codex")?.models,
        )
    }

    @Test
    fun `registry exposes codex as default engine`() {
        val registry = ProviderRegistry(
            descriptors = listOf(
                EngineDescriptor(
                    id = "codex",
                    displayName = "Codex",
                    models = listOf("gpt-5.3-codex", "gpt-5.4"),
                    capabilities = EngineCapabilities(
                        supportsThinking = true,
                        supportsToolEvents = true,
                        supportsCommandProposal = true,
                        supportsDiffProposal = true,
                    ),
                ),
            ),
            factories = emptyList(),
            defaultEngineId = "codex",
        )

        assertEquals("codex", registry.defaultEngineId())
        assertEquals(listOf("codex"), registry.engines().map { it.id })
        assertEquals("Codex", registry.engine("codex")?.displayName)
    }

    @Test
    fun `registry falls back to default engine when unknown id is requested`() {
        val codex = EngineDescriptor(
            id = "codex",
            displayName = "Codex",
            models = listOf("gpt-5.3-codex"),
            capabilities = EngineCapabilities(
                supportsThinking = true,
                supportsToolEvents = true,
                supportsCommandProposal = true,
                supportsDiffProposal = true,
            ),
        )

        val registry = ProviderRegistry(
            descriptors = listOf(codex),
            factories = listOf(
                object : AgentProviderFactory {
                    override val engineId: String = "codex"
                    override fun create(): AgentProvider = object : AgentProvider {
                        override fun stream(request: com.codex.assistant.model.AgentRequest) =
                            kotlinx.coroutines.flow.emptyFlow<com.codex.assistant.model.EngineEvent>()

                        override fun cancel(requestId: String) = Unit
                    }
                },
            ),
            defaultEngineId = "codex",
        )

        val provider = registry.providerOrDefault("missing")
        assertNotNull(provider)
        assertTrue(registry.providerOrNull("codex") != null)
    }
}
