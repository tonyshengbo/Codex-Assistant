package com.codex.assistant.service

import com.codex.assistant.model.AgentRequest
import com.codex.assistant.model.EngineEvent
import com.codex.assistant.model.TimelineAction
import com.codex.assistant.provider.AgentProvider
import com.codex.assistant.provider.AgentProviderFactory
import com.codex.assistant.provider.EngineCapabilities
import com.codex.assistant.provider.EngineDescriptor
import com.codex.assistant.provider.ProviderRegistry
import com.codex.assistant.settings.AgentSettingsService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentChatServiceContinuationTest {
    @Test
    fun `second turn reuses cli session id from the same session`() = runBlocking {
        val provider = RecordingProvider(
            sessionIds = ArrayDeque(listOf("thread_1", "thread_1")),
        )
        val registry = ProviderRegistry(
            descriptors = listOf(
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
                object : AgentProviderFactory {
                    override val engineId: String = "codex"
                    override fun create(): AgentProvider = provider
                },
            ),
            defaultEngineId = "codex",
        )
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            sessionStore = ProjectSessionStore(),
            registry = registry,
            settings = settings,
        )

        val firstFinished = CompletableDeferred<Unit>()
        service.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "First turn",
            contextFiles = emptyList(),
        ) { action ->
            if (action == TimelineAction.FinishTurn) {
                firstFinished.complete(Unit)
            }
        }
        withTimeout(2_000) { firstFinished.await() }

        val secondFinished = CompletableDeferred<Unit>()
        service.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "Second turn",
            contextFiles = emptyList(),
        ) { action ->
            if (action == TimelineAction.FinishTurn) {
                secondFinished.complete(Unit)
            }
        }
        withTimeout(2_000) { secondFinished.await() }

        assertEquals(2, provider.requests.size)
        assertNull(provider.requests[0].cliSessionId)
        assertEquals("thread_1", provider.requests[1].cliSessionId)
        service.dispose()
    }

    private class RecordingProvider(
        private val sessionIds: ArrayDeque<String>,
    ) : AgentProvider {
        val requests = mutableListOf<AgentRequest>()

        override fun stream(request: AgentRequest): Flow<EngineEvent> = flow {
            requests += request
            emit(EngineEvent.AssistantTextDelta("ok"))
            emit(EngineEvent.SessionReady(sessionIds.removeFirst()))
            emit(EngineEvent.Completed(exitCode = 0))
        }

        override fun cancel(requestId: String) = Unit
    }
}
