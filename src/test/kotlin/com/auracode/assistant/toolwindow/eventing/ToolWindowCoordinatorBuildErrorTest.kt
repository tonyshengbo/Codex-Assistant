package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.integration.build.BuildErrorAuraRequest
import com.auracode.assistant.integration.build.BuildErrorSnapshot
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.composer.ComposerAreaStore
import com.auracode.assistant.toolwindow.drawer.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.header.HeaderAreaStore
import com.auracode.assistant.toolwindow.status.StatusAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineAreaStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolWindowCoordinatorBuildErrorTest {
    @Test
    fun `submitting build error request dispatches prompt to current session immediately`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(
            UiIntent.SubmitBuildErrorRequest(
                BuildErrorAuraRequest(
                    snapshot = BuildErrorSnapshot(
                        title = "Compilation failed",
                        detail = "e: Main.kt:1:1 Compilation failed",
                        source = "Gradle Build",
                    ),
                    prompt = "Analyze this build error",
                ),
            ),
        )

        harness.waitUntil { harness.provider.requests.isNotEmpty() }
        val request = harness.provider.requests.single()
        assertEquals("Analyze this build error", request.prompt)
        assertTrue(harness.composerStore.state.value.document.text.isBlank())

        harness.dispose()
    }

    @Test
    fun `build error request queues while a run is active`() {
        val harness = CoordinatorHarness()
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("existing prompt"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }

        harness.eventHub.publishUiIntent(
            UiIntent.SubmitBuildErrorRequest(
                BuildErrorAuraRequest(
                    snapshot = BuildErrorSnapshot(
                        title = "Compilation failed",
                        detail = "details",
                        source = "Build",
                    ),
                    prompt = "Analyze this queued build error",
                ),
            ),
        )

        harness.waitUntil { harness.composerStore.state.value.pendingSubmissions.size == 1 }
        assertEquals(1, harness.provider.requests.size)

        harness.provider.completeTurn("turn-1")
        harness.waitUntil { harness.provider.requests.size == 2 }
        assertEquals("Analyze this queued build error", harness.provider.requests.last().prompt)

        harness.dispose()
    }

    private class CoordinatorHarness {
        private val workingDir = createTempDirectory("build-error-flow")
        val provider = RecordingProvider()
        private val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        private val service = AgentChatService(
            repository = com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = ProviderRegistry(
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
            ),
            settings = settings,
            workingDirectoryProvider = { workingDir.toString() },
        )
        val eventHub = ToolWindowEventHub()
        val composerStore = ComposerAreaStore()
        private val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = TimelineAreaStore(),
            composerStore = composerStore,
            rightDrawerStore = RightDrawerAreaStore(),
        )

        fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean) {
            val start = System.currentTimeMillis()
            while (!condition()) {
                if (System.currentTimeMillis() - start > timeoutMs) {
                    throw AssertionError("Condition was not met within ${timeoutMs}ms")
                }
                Thread.sleep(20)
            }
        }

        fun dispose() {
            coordinator.dispose()
            service.dispose()
            Files.deleteIfExists(workingDir.resolve("chat.db"))
        }
    }

    private class RecordingProvider : AgentProvider {
        val requests = mutableListOf<AgentRequest>()
        private var sink: (UnifiedEvent) -> Unit = {}
        private var nextThreadIndex: Int = 1

        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = callbackFlow {
            requests += request
            val turnId = "turn-${requests.size}"
            val threadId = "thread-${nextThreadIndex++}"
            sink = { event -> trySend(event); Unit }
            trySend(UnifiedEvent.ThreadStarted(threadId))
            trySend(UnifiedEvent.TurnStarted(turnId, threadId))
            awaitClose { sink = {} }
        }

        fun completeTurn(turnId: String) {
            sink(UnifiedEvent.TurnCompleted(turnId, TurnOutcome.SUCCESS))
        }

        override fun cancel(requestId: String) = Unit
    }
}
