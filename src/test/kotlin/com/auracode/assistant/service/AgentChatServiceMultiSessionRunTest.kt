package com.auracode.assistant.service

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.session.kernel.SessionDomainEvent
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
import kotlin.test.assertEquals

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
            onSessionDomainEvents = { events ->
                if (events.any { it is SessionDomainEvent.ThreadStarted }) {
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
            onSessionDomainEvents = { events ->
                if (events.any { it is SessionDomainEvent.ThreadStarted }) {
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

    @Test
    fun `run state callback fires when request starts and finishes`() = runBlocking {
        val provider = BlockingProvider()
        val service = createService(provider)
        val stateTransitions = mutableListOf<Boolean>()
        val requestStarted = CompletableDeferred<Unit>()

        service.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "run-state",
            contextFiles = emptyList(),
            onSessionDomainEvents = { events ->
                if (events.any { it is SessionDomainEvent.ThreadStarted }) {
                    requestStarted.complete(Unit)
                }
            },
            onRunStateChanged = {
                val running = service.listSessions().associateBy { it.id }
                    .getValue(service.getCurrentSessionId())
                    .isRunning
                stateTransitions += running
            },
        )

        withTimeout(5_000) { requestStarted.await() }
        provider.completePrompt("run-state")
        withTimeout(5_000) { provider.awaitCompletedRequest("run-state") }
        withTimeout(5_000) {
            while (service.listSessions().associateBy { it.id }.getValue(service.getCurrentSessionId()).isRunning) {
                delay(10)
            }
        }

        assertEquals(listOf(true, false), stateTransitions)
        service.dispose()
    }

    @Test
    fun `resetting a populated session for engine switch updates provider and clears remote conversation`() = runBlocking {
        val provider = BlockingProvider()
        val service = createService(provider)
        val sessionId = service.getCurrentSessionId()
        val requestStarted = CompletableDeferred<Unit>()

        service.recordUserMessage(
            sessionId = sessionId,
            prompt = "seed-session",
        )
        service.runAgent(
            sessionId = sessionId,
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "seed-session",
            contextFiles = emptyList(),
            onSessionDomainEvents = { events ->
                if (events.any { it is SessionDomainEvent.ThreadStarted }) {
                    requestStarted.complete(Unit)
                }
            },
        )

        withTimeout(5_000) { requestStarted.await() }
        provider.completePrompt("seed-session")
        withTimeout(5_000) { provider.awaitCompletedRequest("seed-session") }
        withTimeout(5_000) {
            while (service.listSessions().associateBy { it.id }.getValue(sessionId).isRunning) {
                delay(10)
            }
        }

        val beforeReset = service.listSessions().associateBy { it.id }.getValue(sessionId)
        assertEquals("thread-seed-session", beforeReset.remoteConversationId)
        assertEquals("codex", beforeReset.providerId)
        assertEquals(1, beforeReset.messageCount)

        assertTrue(service.resetSessionForEngineSwitch(sessionId = sessionId, providerId = "claude"))

        val afterReset = service.listSessions().associateBy { it.id }.getValue(sessionId)
        assertEquals("claude", afterReset.providerId)
        assertEquals("", afterReset.remoteConversationId)
        assertEquals("", afterReset.title)
        assertEquals(0, afterReset.messageCount)
        assertEquals(null, afterReset.usageSnapshot)

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

        override fun stream(request: AgentRequest): kotlinx.coroutines.flow.Flow<com.auracode.assistant.session.kernel.SessionDomainEvent> = com.auracode.assistant.test.providerEventFlow {
            requestIdsByPrompt[request.prompt] = request.requestId
            emit(ProviderEvent.ThreadStarted(threadId = "thread-${request.prompt}"))
            when (outcomesByPrompt.getOrPut(request.prompt) { CompletableDeferred() }.await()) {
                TurnOutcome.SUCCESS -> {
                    emit(ProviderEvent.TurnCompleted(turnId = "turn-${request.prompt}", outcome = TurnOutcome.SUCCESS))
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
