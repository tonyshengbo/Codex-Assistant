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
import kotlin.test.assertTrue

class AgentChatServiceLoggingTest {
    @Test
    fun `logs codex cli session chain across turns`() = runBlocking {
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
        val logs = mutableListOf<String>()
        val service = createServiceWithLogger(
            registry = registry,
            settings = settings,
            logSink = logs::add,
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

        assertTrue(
            logs.any { it.contains("Codex chain request:") && it.contains("cliSessionId=<none>") },
            "Expected a first-turn log without cliSessionId, got: ${logs.joinToString()}",
        )
        assertTrue(
            logs.any { it.contains("Codex chain stored cli session:") && it.contains("cliSessionId=thread_1") },
            "Expected a stored cliSessionId log for thread_1, got: ${logs.joinToString()}",
        )
        assertTrue(
            logs.any { it.contains("Codex chain request:") && it.contains("cliSessionId=thread_1") },
            "Expected a second-turn log with cliSessionId=thread_1, got: ${logs.joinToString()}",
        )
        service.dispose()
    }

    private fun createServiceWithLogger(
        registry: ProviderRegistry,
        settings: AgentSettingsService,
        logSink: (String) -> Unit,
    ): AgentChatService {
        val ctor = AgentChatService::class.java.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.size == 5 &&
                candidate.parameterTypes[0] == ProjectSessionStore::class.java &&
                candidate.parameterTypes[1] == ProviderRegistry::class.java &&
                candidate.parameterTypes[2] == AgentSettingsService::class.java &&
                candidate.parameterTypes[3].name == "kotlin.jvm.functions.Function0" &&
                candidate.parameterTypes[4].name == "kotlin.jvm.functions.Function1"
        } ?: error("Expected AgentChatService test constructor with diagnostic logger")
        ctor.isAccessible = true
        return ctor.newInstance(
            ProjectSessionStore(),
            registry,
            settings,
            { "." },
            logSink,
        ) as AgentChatService
    }

    private class RecordingProvider(
        private val sessionIds: ArrayDeque<String>,
    ) : AgentProvider {
        override fun stream(request: AgentRequest): Flow<EngineEvent> = flow {
            emit(EngineEvent.AssistantTextDelta("ok"))
            emit(EngineEvent.SessionReady(sessionIds.removeFirst()))
            emit(EngineEvent.Completed(exitCode = 0))
        }

        override fun cancel(requestId: String) = Unit
    }
}
