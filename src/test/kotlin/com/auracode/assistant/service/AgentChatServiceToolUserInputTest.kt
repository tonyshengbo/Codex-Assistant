package com.auracode.assistant.service

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderToolUserInputAnswerDraft
import com.auracode.assistant.settings.AgentSettingsService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentChatServiceToolUserInputTest {
    @Test
    fun `submit tool user input forwards answers to active provider`() {
        val provider = RecordingToolUserInputProvider()
        val service = createService(provider)
        service.conversationCapabilities()

        val submitted = service.submitToolUserInput(
            requestId = "request-1",
            answers = mapOf(
                "builder_demo_target" to ProviderToolUserInputAnswerDraft(
                    answers = listOf("Reuse existing demo"),
                ),
            ),
        )

        assertTrue(submitted)
        assertEquals(
            "request-1" to mapOf(
                "builder_demo_target" to ProviderToolUserInputAnswerDraft(
                    answers = listOf("Reuse existing demo"),
                ),
            ),
            provider.submissions.single(),
        )

        service.dispose()
    }

    private fun createService(provider: AgentProvider): AgentChatService {
        val dbPath = createTempDirectory("chat-service-user-input").resolve("chat.db")
        val registry = ProviderRegistry(
            descriptors = listOf(
                EngineDescriptor(
                    id = "codex",
                    displayName = "Codex",
                    models = listOf("gpt-5.3-codex"),
                    capabilities = EngineCapabilities(
                        supportsThinking = true,
                        supportsToolEvents = true,
                        supportsCommandProposal = true,
                        supportsDiffProposal = true,
                    ),
                ),
            ),
            factories = listOf(
                object : AgentProviderFactory {
                    override val engineId: String = "codex"
                    override fun create(): AgentProvider = provider
                },
            ),
            defaultEngineId = "codex",
        )
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        return AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry,
            settings = settings,
        )
    }

    private class RecordingToolUserInputProvider : AgentProvider {
        val submissions = mutableListOf<Pair<String, Map<String, ProviderToolUserInputAnswerDraft>>>()

        override fun stream(request: AgentRequest): kotlinx.coroutines.flow.Flow<com.auracode.assistant.session.kernel.SessionDomainEvent> = com.auracode.assistant.test.emptySessionDomainEventFlow()

        override fun submitToolUserInput(
            requestId: String,
            answers: Map<String, ProviderToolUserInputAnswerDraft>,
        ): Boolean {
            submissions += requestId to answers
            return true
        }

        override fun cancel(requestId: String) = Unit
    }
}
