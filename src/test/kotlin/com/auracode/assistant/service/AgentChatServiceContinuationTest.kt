package com.auracode.assistant.service

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentChatServiceContinuationTest {
    @Test
    fun `empty session provider selection persists after service reload`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-provider-selection").resolve("chat.db")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val firstService = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(
                providers = mapOf(
                    "codex" to RecordingProvider(sessionIds = ArrayDeque(listOf("thread_codex"))),
                    "claude" to RecordingProvider(sessionIds = ArrayDeque(listOf("session_claude"))),
                ),
            ),
            settings = settings,
        )

        assertEquals("codex", firstService.defaultEngineId())
        assertTrue(firstService.setSessionProviderIfEmpty(providerId = "claude"))
        assertEquals("claude", firstService.defaultEngineId())
        firstService.dispose()

        val reloadedClaudeProvider = RecordingProvider(sessionIds = ArrayDeque(listOf("session_claude")))
        val reloadedService = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(
                providers = mapOf(
                    "codex" to RecordingProvider(sessionIds = ArrayDeque(listOf("thread_codex"))),
                    "claude" to reloadedClaudeProvider,
                ),
            ),
            settings = settings,
        )
        val finished = CompletableDeferred<Unit>()
        reloadedService.runAgent(
            engineId = "claude",
            model = "claude-sonnet-4-5",
            prompt = "hello claude",
            contextFiles = emptyList(),
            onTurnPersisted = { finished.complete(Unit) },
        )
        withTimeout(2_000) { finished.await() }

        assertEquals("claude", reloadedService.defaultEngineId())
        assertEquals(1, reloadedClaudeProvider.requests.size)
        assertEquals("claude", reloadedClaudeProvider.requests.single().engineId)
        assertNull(reloadedClaudeProvider.requests.single().remoteConversationId)
        reloadedService.dispose()
    }

    @Test
    fun `second turn reuses persisted remote conversation id after service reload`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-continuation").resolve("chat.db")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val firstProvider = RecordingProvider(sessionIds = ArrayDeque(listOf("thread_1")))
        val firstService = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(
                providers = mapOf(
                    "codex" to firstProvider,
                    "claude" to RecordingProvider(sessionIds = ArrayDeque(listOf("session_claude"))),
                ),
            ),
            settings = settings,
        )
        firstService.recordUserMessage(prompt = "First turn")

        val firstFinished = CompletableDeferred<Unit>()
        firstService.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "First turn",
            contextFiles = emptyList(),
            onTurnPersisted = { firstFinished.complete(Unit) },
        )
        withTimeout(2_000) { firstFinished.await() }
        firstService.dispose()

        val secondProvider = RecordingProvider(sessionIds = ArrayDeque(listOf("thread_1")))
        val secondService = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(
                providers = mapOf(
                    "codex" to secondProvider,
                    "claude" to RecordingProvider(sessionIds = ArrayDeque(listOf("session_claude"))),
                ),
            ),
            settings = settings,
        )
        val secondFinished = CompletableDeferred<Unit>()
        secondService.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "Second turn",
            contextFiles = emptyList(),
            onTurnPersisted = { secondFinished.complete(Unit) },
        )
        withTimeout(2_000) { secondFinished.await() }

        assertEquals(1, firstProvider.requests.size)
        assertNull(firstProvider.requests[0].remoteConversationId)
        assertEquals(1, secondProvider.requests.size)
        assertEquals("thread_1", secondProvider.requests[0].remoteConversationId)
        secondService.dispose()
    }

    @Test
    fun `second claude turn reuses persisted remote conversation id after service reload`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-continuation-claude").resolve("chat.db")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val firstProvider = RecordingProvider(sessionIds = ArrayDeque(listOf("session_claude")))
        val firstService = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(
                providers = mapOf(
                    "codex" to RecordingProvider(sessionIds = ArrayDeque(listOf("thread_codex"))),
                    "claude" to firstProvider,
                ),
            ),
            settings = settings,
        )
        assertTrue(firstService.setSessionProviderIfEmpty(providerId = "claude"))
        firstService.recordUserMessage(prompt = "First turn")

        val firstFinished = CompletableDeferred<Unit>()
        firstService.runAgent(
            engineId = "claude",
            model = "claude-sonnet-4-5",
            prompt = "First turn",
            contextFiles = emptyList(),
            onTurnPersisted = { firstFinished.complete(Unit) },
        )
        withTimeout(2_000) { firstFinished.await() }
        firstService.dispose()

        val secondProvider = RecordingProvider(sessionIds = ArrayDeque(listOf("session_claude")))
        val secondService = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(
                providers = mapOf(
                    "codex" to RecordingProvider(sessionIds = ArrayDeque(listOf("thread_codex"))),
                    "claude" to secondProvider,
                ),
            ),
            settings = settings,
        )
        val secondFinished = CompletableDeferred<Unit>()
        secondService.runAgent(
            engineId = "claude",
            model = "claude-sonnet-4-5",
            prompt = "Second turn",
            contextFiles = emptyList(),
            onTurnPersisted = { secondFinished.complete(Unit) },
        )
        withTimeout(2_000) { secondFinished.await() }

        assertEquals(1, firstProvider.requests.size)
        assertNull(firstProvider.requests[0].remoteConversationId)
        assertEquals(1, secondProvider.requests.size)
        assertEquals("session_claude", secondProvider.requests[0].remoteConversationId)
        secondService.dispose()
    }

    private fun registry(
        providers: Map<String, AgentProvider>,
    ): ProviderRegistry {
        return ProviderRegistry(
            descriptors = listOf(
                EngineDescriptor(
                    id = "claude",
                    displayName = "Claude",
                    models = listOf("claude-sonnet-4-5", "claude-opus-4-1"),
                    capabilities = EngineCapabilities(
                        supportsThinking = true,
                        supportsToolEvents = false,
                        supportsCommandProposal = false,
                        supportsDiffProposal = false,
                    ),
                ),
                EngineDescriptor(
                    id = "codex",
                    displayName = "Codex",
                    models = listOf("gpt-5.3-codex", "gpt-5.4"),
                    capabilities = EngineCapabilities(
                        supportsThinking = true,
                        supportsToolEvents = false,
                        supportsCommandProposal = false,
                        supportsDiffProposal = false,
                    ),
                ),
            ),
            factories = listOf(
                *providers.map { (engineId, provider) ->
                    object : AgentProviderFactory {
                        override val engineId: String = engineId
                        override fun create(): AgentProvider = provider
                    }
                }.toTypedArray(),
            ),
            defaultEngineId = "codex",
        )
    }

    private class RecordingProvider(
        private val sessionIds: ArrayDeque<String>,
    ) : AgentProvider {
        val requests = mutableListOf<AgentRequest>()

        override fun stream(request: AgentRequest): kotlinx.coroutines.flow.Flow<com.auracode.assistant.session.kernel.SessionDomainEvent> = com.auracode.assistant.test.providerEventFlow {
            requests += request
            val threadId = sessionIds.removeFirst()
            emit(ProviderEvent.ThreadStarted(threadId = threadId))
            emit(
                ProviderEvent.ItemUpdated(
                    ProviderItem(
                        id = "${request.requestId}:assistant",
                        kind = ItemKind.NARRATIVE,
                        status = ItemStatus.SUCCESS,
                        name = "message",
                        text = "ok",
                    ),
                ),
            )
            emit(ProviderEvent.TurnCompleted(turnId = "turn-1", outcome = TurnOutcome.SUCCESS))
        }

        override fun cancel(requestId: String) = Unit
    }
}
