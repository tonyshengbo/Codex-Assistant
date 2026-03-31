package com.auracode.assistant.toolwindow.eventing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedItem
import com.auracode.assistant.protocol.UnifiedToolUserInputPrompt
import com.auracode.assistant.protocol.UnifiedToolUserInputQuestion
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.composer.ComposerAreaStore
import com.auracode.assistant.toolwindow.drawer.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.header.HeaderAreaStore
import com.auracode.assistant.toolwindow.status.StatusAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineAreaStore
import com.auracode.assistant.toolwindow.timeline.TimelineNode
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolWindowCoordinatorMultiSessionTest {
    @Test
    fun `background session events do not overwrite the active tab timeline and restore on switch back`() {
        val harness = CoordinatorHarness()

        val sessionA = harness.service.getCurrentSessionId()
        val sessionB = harness.createSession()
        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionA }

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-a", TextRange(5))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }

        harness.provider.emit(
            prompt = "run-a",
            event = UnifiedEvent.ThreadStarted(threadId = "thread-a"),
        )
        harness.provider.emit(
            prompt = "run-a",
            event = UnifiedEvent.TurnStarted(turnId = "turn-a", threadId = "thread-a"),
        )
        harness.provider.emit(
            prompt = "run-a",
            event = UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "run-a:assistant",
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.RUNNING,
                    name = "message",
                    text = "assistant-a",
                ),
            ),
        )
        harness.waitUntil {
            harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().any { it.text == "assistant-a" }
        }

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionB))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionB }
        harness.waitUntil {
            harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().none { it.text == "assistant-a" }
        }

        harness.provider.emit(
            prompt = "run-a",
            event = UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = "run-a:assistant",
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.SUCCESS,
                    name = "message",
                    text = "assistant-a done",
                ),
            ),
        )
        harness.provider.emit(
            prompt = "run-a",
            event = UnifiedEvent.TurnCompleted(turnId = "turn-a", outcome = TurnOutcome.SUCCESS),
        )

        Thread.sleep(150)
        assertTrue(
            harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().none {
                it.text.contains("assistant-a", ignoreCase = true)
            },
        )

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil {
            harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().any { it.text == "assistant-a done" }
        }

        val messages = harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().map { it.text }
        assertEquals(listOf("run-a", "assistant-a done"), messages)

        harness.dispose()
    }

    @Test
    fun `queued background session submission dispatches without mutating the active tab`() {
        val harness = CoordinatorHarness()

        val sessionA = harness.service.getCurrentSessionId()
        val sessionB = harness.createSession()
        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionA }

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-a-1", TextRange(7))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }
        harness.provider.emit("run-a-1", UnifiedEvent.ThreadStarted(threadId = "thread-a"))
        harness.provider.emit("run-a-1", UnifiedEvent.TurnStarted(turnId = "turn-a-1", threadId = "thread-a"))

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-a-2", TextRange(7))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.composerStore.state.value.pendingSubmissions.size == 1 }

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionB))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionB }
        assertTrue(harness.composerStore.state.value.pendingSubmissions.isEmpty())

        harness.provider.emit("run-a-1", UnifiedEvent.TurnCompleted(turnId = "turn-a-1", outcome = TurnOutcome.SUCCESS))
        harness.waitUntil { harness.provider.requests.size == 2 }
        assertEquals("run-a-2", harness.provider.requests.last().prompt)
        assertTrue(
            harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().none { it.text == "run-a-2" },
        )

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil {
            harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().any { it.text == "run-a-2" }
        }

        harness.dispose()
    }

    @Test
    fun `background tool input resolution keeps the correct user input timeline state`() {
        val harness = CoordinatorHarness()

        val sessionA = harness.service.getCurrentSessionId()
        val sessionB = harness.createSession()
        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionA }

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-a", TextRange(5))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }
        harness.provider.emit("run-a", UnifiedEvent.ThreadStarted(threadId = "thread-a"))
        harness.provider.emit("run-a", UnifiedEvent.TurnStarted(turnId = "turn-a", threadId = "thread-a"))
        harness.provider.emit("run-a", UnifiedEvent.ToolUserInputRequested(backgroundPrompt()))
        harness.waitUntil {
            harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.UserInputNode>().any {
                it.status == ItemStatus.RUNNING
            }
        }

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionB))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionB }
        assertFalse(harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.UserInputNode>().any())

        harness.provider.emit("run-a", UnifiedEvent.ToolUserInputResolved(requestId = "user-input-a"))
        Thread.sleep(150)

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil {
            harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.UserInputNode>().any {
                it.status == ItemStatus.FAILED
            }
        }

        harness.dispose()
    }

    private fun backgroundPrompt(): UnifiedToolUserInputPrompt {
        return UnifiedToolUserInputPrompt(
            requestId = "user-input-a",
            threadId = "thread-a",
            turnId = "turn-a",
            itemId = "call-a",
            questions = listOf(
                UnifiedToolUserInputQuestion(
                    id = "target",
                    header = "Target",
                    question = "Where should this run?",
                    options = emptyList(),
                    isOther = true,
                    isSecret = false,
                ),
            ),
        )
    }

    private class CoordinatorHarness {
        private val workingDir = createTempDirectory("multi-session-flow")
        val provider = RecordingMultiSessionProvider()
        private val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
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
        val timelineStore = TimelineAreaStore()
        val composerStore = ComposerAreaStore()
        val chatService: AgentChatService = service
        private val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = timelineStore,
            composerStore = composerStore,
            rightDrawerStore = RightDrawerAreaStore(),
            historyPageSize = 10,
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

        fun createSession(): String {
            val sessionId = service.createSession()
            coordinator.onSessionActivated()
            return sessionId
        }

        fun dispose() {
            coordinator.dispose()
            service.dispose()
        }
    }

    private class RecordingMultiSessionProvider : AgentProvider {
        val requests = mutableListOf<AgentRequest>()
        private val sinks = mutableMapOf<String, kotlinx.coroutines.channels.SendChannel<UnifiedEvent>>()

        override fun capabilities(): ConversationCapabilities = ConversationCapabilities(
            supportsStructuredHistory = false,
            supportsHistoryPagination = false,
            supportsPlanMode = true,
            supportsApprovalRequests = false,
            supportsToolUserInput = true,
            supportsResume = true,
            supportsAttachments = true,
            supportsImageInputs = true,
        )

        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = callbackFlow {
            requests += request
            sinks[request.prompt] = channel
            awaitClose {
                sinks.remove(request.prompt)
            }
        }

        fun emit(prompt: String, event: UnifiedEvent) {
            checkNotNull(sinks[prompt]) { "No active stream for prompt '$prompt'" }.trySend(event)
        }

        override fun cancel(requestId: String) = Unit
    }
}
