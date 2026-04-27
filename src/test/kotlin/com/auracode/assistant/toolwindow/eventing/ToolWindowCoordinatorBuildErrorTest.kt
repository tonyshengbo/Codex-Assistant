package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.integration.build.BuildErrorAuraRequest
import com.auracode.assistant.integration.build.BuildErrorSnapshot
import com.auracode.assistant.integration.ide.IdeExternalRequest
import com.auracode.assistant.integration.ide.IdeRequestSource
import com.auracode.assistant.model.ContextFile
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.submission.SubmissionAreaStore
import com.auracode.assistant.toolwindow.shell.SidePanelAreaStore
import com.auracode.assistant.toolwindow.sessions.SessionTabsAreaStore
import com.auracode.assistant.toolwindow.execution.ExecutionStatusAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationAreaStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
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
        assertTrue(harness.submissionStore.state.value.document.text.isBlank())

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

        harness.waitUntil { harness.submissionStore.state.value.pendingSubmissions.size == 1 }
        assertEquals(1, harness.provider.requests.size)

        harness.provider.completeTurn("turn-1")
        harness.waitUntil { harness.provider.requests.size == 2 }
        assertEquals("Analyze this queued build error", harness.provider.requests.last().prompt)

        harness.dispose()
    }

    @Test
    fun `external request forwards explicit context files to provider`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(
            UiIntent.SubmitExternalRequest(
                IdeExternalRequest(
                    source = IdeRequestSource.EDITOR_SELECTION,
                    title = "Explain Selected Code",
                    prompt = "Explain this selection",
                    contextFiles = listOf(
                        ContextFile(
                            path = "/src/Main.kt:8-12",
                            content = "fun answer() = 42",
                        ),
                    ),
                ),
            ),
        )

        harness.waitUntil { harness.provider.requests.isNotEmpty() }
        val request = harness.provider.requests.single()
        assertEquals("Explain this selection", request.prompt)
        assertEquals(1, request.contextFiles.size)
        assertEquals("/src/Main.kt:8-12", request.contextFiles.single().path)
        assertEquals("fun answer() = 42", request.contextFiles.single().content)

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
        val submissionStore = SubmissionAreaStore()
        private val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            sessionTabsStore = SessionTabsAreaStore(),
            executionStatusStore = ExecutionStatusAreaStore(),
            conversationStore = ConversationAreaStore(),
            submissionStore = submissionStore,
            sidePanelStore = SidePanelAreaStore(),
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
        val requests = CopyOnWriteArrayList<AgentRequest>()
        private var sink: (ProviderEvent) -> Unit = {}
        private var nextThreadIndex: Int = 1

        override fun stream(request: AgentRequest): kotlinx.coroutines.flow.Flow<com.auracode.assistant.session.kernel.SessionDomainEvent> = callbackFlow {
            requests += request
            val turnId = "turn-${requests.size}"
            val threadId = "thread-${nextThreadIndex++}"
            sink = { event -> com.auracode.assistant.test.trySendProviderEvent(this, event); Unit }
            com.auracode.assistant.test.trySendProviderEvent(this, ProviderEvent.ThreadStarted(threadId))
            com.auracode.assistant.test.trySendProviderEvent(this, ProviderEvent.TurnStarted(turnId, threadId))
            awaitClose { sink = {} }
        }

        fun completeTurn(turnId: String) {
            sink(ProviderEvent.TurnCompleted(turnId, TurnOutcome.SUCCESS))
        }

        override fun cancel(requestId: String) = Unit
    }
}
