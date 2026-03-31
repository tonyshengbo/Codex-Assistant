package com.auracode.assistant.service

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.settings.AgentSettingsService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentChatServiceMultiSessionRunTest {
    @Test
    fun `runs are isolated per session and cancel independently`() = runBlocking {
        val provider = BlockingProvider()
        val service = createService(provider)

        val sessionA = service.getCurrentSessionId()
        val sessionB = service.createSession()

        val requestAStarted = CompletableDeferred<Unit>()
        val requestBStarted = CompletableDeferred<Unit>()

        service.runAgent(
            sessionId = sessionA,
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "run-a",
            contextFiles = emptyList(),
            onUnifiedEvent = { event ->
                if (event is UnifiedEvent.ThreadStarted) {
                    requestAStarted.complete(Unit)
                }
            },
        )
        service.runAgent(
            sessionId = sessionB,
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "run-b",
            contextFiles = emptyList(),
            onUnifiedEvent = { event ->
                if (event is UnifiedEvent.ThreadStarted) {
                    requestBStarted.complete(Unit)
                }
            },
        )

        withTimeout(5_000) { requestAStarted.await() }
        withTimeout(5_000) { requestBStarted.await() }

        val sessionsById = service.listSessions().associateBy { it.id }
        assertTrue(sessionsById.getValue(sessionA).isRunning)
        assertTrue(sessionsById.getValue(sessionB).isRunning)

        service.cancelSessionRun(sessionA)

        withTimeout(5_000) {
            provider.awaitCancelledRequest(prompt = "run-a")
        }
        assertFalse(service.listSessions().associateBy { it.id }.getValue(sessionA).isRunning)
        assertTrue(service.listSessions().associateBy { it.id }.getValue(sessionB).isRunning)

        provider.completePrompt("run-b")
        withTimeout(5_000) {
            provider.awaitCompletedRequest(prompt = "run-b")
        }
        withTimeout(5_000) {
            while (service.listSessions().associateBy { it.id }.getValue(sessionB).isRunning) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertFalse(service.listSessions().associateBy { it.id }.getValue(sessionB).isRunning)

        service.dispose()
    }

    private fun createService(provider: AgentProvider): AgentChatService {
        val dbPath = createTempDirectory("chat-service-multi-session").resolve("chat.db")
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

    private class BlockingProvider : AgentProvider {
        private val completionSignals = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val cancelSignals = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val requestIdsByPrompt = mutableMapOf<String, String>()
        private val outcomesByPrompt = mutableMapOf<String, CompletableDeferred<TurnOutcome>>()

        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = flow {
            requestIdsByPrompt[request.prompt] = request.requestId
            emit(UnifiedEvent.ThreadStarted(threadId = "thread-${request.prompt}"))
            when (outcomesByPrompt.getOrPut(request.prompt) { CompletableDeferred() }.await()) {
                TurnOutcome.SUCCESS -> {
                    emit(UnifiedEvent.TurnCompleted(turnId = "turn-${request.prompt}", outcome = TurnOutcome.SUCCESS))
                    completionSignals.getOrPut(request.prompt) { CompletableDeferred() }.complete(Unit)
                }

                TurnOutcome.CANCELLED -> Unit

                else -> Unit
            }
        }

        override fun cancel(requestId: String) {
            val prompt = requestIdsByPrompt.entries.firstOrNull { it.value == requestId }?.key ?: return
            cancelSignals.getOrPut(prompt) { CompletableDeferred() }.complete(Unit)
            outcomesByPrompt.getOrPut(prompt) { CompletableDeferred() }.complete(TurnOutcome.CANCELLED)
        }

        fun completePrompt(prompt: String) {
            outcomesByPrompt.getOrPut(prompt) { CompletableDeferred() }.complete(TurnOutcome.SUCCESS)
        }

        suspend fun awaitCancelledRequest(prompt: String) {
            cancelSignals.getOrPut(prompt) { CompletableDeferred() }.await()
        }

        suspend fun awaitCompletedRequest(prompt: String) {
            completionSignals.getOrPut(prompt) { CompletableDeferred() }.await()
        }
    }
}
