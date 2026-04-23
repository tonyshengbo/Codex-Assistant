package com.auracode.assistant.provider

import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.provider.claude.ClaudeModelCatalog
import com.auracode.assistant.provider.codex.CodexModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderRegistryTest {
    @Test
    fun `default registry exposes codex and claude engines`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }

        val registry = ProviderRegistry(settings)

        assertEquals(listOf("claude", "codex"), registry.engines().map { it.id }.sorted())
        assertEquals("Claude", registry.engine("claude")?.displayName)
    }

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
    fun `default claude registry exposes current curated model list`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }

        val registry = ProviderRegistry(settings)

        assertEquals(
            listOf(
                "claude-sonnet-4-6",
                "claude-sonnet-4-6[1m]",
                "claude-opus-4-6",
                "claude-opus-4-6[1m]",
                "claude-haiku-4-5-20251001",
            ),
            registry.engine("claude")?.models,
        )
    }

    @Test
    fun `settings persist selected model per engine`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }

        settings.setSelectedComposerModel(engineId = "claude", model = "claude-sonnet-4-5")
        settings.setSelectedComposerModel(engineId = "codex", model = "gpt-5.4")

        assertEquals("claude-sonnet-4-6", settings.selectedComposerModel("claude"))
        assertEquals("gpt-5.4", settings.selectedComposerModel("codex"))
    }

    @Test
    fun `settings migrate legacy curated claude model ids to current replacements`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }

        settings.setSelectedComposerModel(engineId = "claude", model = "claude-opus-4-1")
        assertEquals("claude-opus-4-6", settings.selectedComposerModel("claude"))

        settings.setSelectedComposerModel(engineId = "claude", model = "claude-haiku-4-5")
        assertEquals("claude-haiku-4-5-20251001", settings.selectedComposerModel("claude"))
    }

    @Test
    fun `curated model catalogs expose display friendly short names`() {
        assertEquals("GPT 5.3 Codex", CodexModelCatalog.option("gpt-5.3-codex")?.description)
        assertEquals("GPT 5.4", CodexModelCatalog.option("gpt-5.4")?.description)
        assertEquals("Sonnet 4.6", ClaudeModelCatalog.option("claude-sonnet-4-6")?.shortName)
        assertEquals("Haiku 4.5", ClaudeModelCatalog.option("claude-haiku-4-5-20251001")?.shortName)
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
                        override fun stream(request: com.auracode.assistant.model.AgentRequest) =
                            kotlinx.coroutines.flow.emptyFlow<UnifiedEvent>()

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
