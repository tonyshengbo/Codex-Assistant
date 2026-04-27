package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.notification.ChatCompletionNotificationService
import com.auracode.assistant.notification.ChatCompletionSignal
import com.auracode.assistant.notification.CompletionNotificationPublisher
import com.auracode.assistant.notification.IdeAttentionState
import com.auracode.assistant.notification.IdeAttentionStateProvider
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.submission.SubmissionAreaStore
import com.auracode.assistant.toolwindow.shell.SidePanelAreaStore
import com.auracode.assistant.toolwindow.sessions.SessionTabsAreaStore
import com.auracode.assistant.toolwindow.sessions.SessionAttentionStore
import com.auracode.assistant.toolwindow.execution.ExecutionStatusAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationAreaStore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolWindowCoordinatorNotificationTest {
    @Test
    fun `background session completion publishes a reminder and marks unread`() {
        val harness = CoordinatorHarness()
        val sessionA = harness.service.getCurrentSessionId()
        val sessionB = harness.createSession()
        harness.attentionState = IdeAttentionState(
            isIdeFrameFocused = true,
            isToolWindowVisible = true,
            activeSessionId = sessionB,
        )

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil { harness.service.getCurrentSessionId() == sessionA }
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("run-a"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionB))
        harness.waitUntil { harness.service.getCurrentSessionId() == sessionB }

        harness.provider.emit("run-a", ProviderEvent.TurnCompleted(turnId = "turn-a", outcome = TurnOutcome.SUCCESS))

        harness.waitUntil { harness.publisher.signals.isNotEmpty() }
        assertTrue(harness.attentionStore.snapshot(sessionA).hasUnreadCompletion)
        assertEquals(sessionA, harness.publisher.signals.single().sessionId)

        harness.dispose()
    }

    @Test
    fun `visible active session completion does not publish a reminder`() {
        val harness = CoordinatorHarness()
        val sessionA = harness.service.getCurrentSessionId()
        harness.attentionState = IdeAttentionState(
            isIdeFrameFocused = true,
            isToolWindowVisible = true,
            activeSessionId = sessionA,
        )

        harness.eventHub.publishUiIntent(UiIntent.InputChanged("run-a"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }

        harness.provider.emit("run-a", ProviderEvent.TurnCompleted(turnId = "turn-a", outcome = TurnOutcome.SUCCESS))
        Thread.sleep(100)

        assertTrue(harness.publisher.signals.isEmpty())
        assertFalse(harness.attentionStore.snapshot(sessionA).hasUnreadCompletion)

        harness.dispose()
    }

    @Test
    fun `disabled reminder setting suppresses background completion reminders`() {
        val harness = CoordinatorHarness(
            settingsState = AgentSettingsService.State(
                backgroundCompletionNotificationsEnabled = false,
            ),
        )
        val sessionA = harness.service.getCurrentSessionId()
        val sessionB = harness.createSession()
        harness.attentionState = IdeAttentionState(
            isIdeFrameFocused = false,
            isToolWindowVisible = false,
            activeSessionId = sessionB,
        )

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil { harness.service.getCurrentSessionId() == sessionA }
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("run-a"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }
        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionB))
        harness.waitUntil { harness.service.getCurrentSessionId() == sessionB }

        harness.provider.emit("run-a", ProviderEvent.TurnCompleted(turnId = "turn-a", outcome = TurnOutcome.SUCCESS))
        Thread.sleep(100)

        assertTrue(harness.publisher.signals.isEmpty())
        assertFalse(harness.attentionStore.snapshot(sessionA).hasUnreadCompletion)

        harness.dispose()
    }

    private class CoordinatorHarness(
        settingsState: AgentSettingsService.State = AgentSettingsService.State(),
    ) {
        private val workingDir = createTempDirectory("notification-flow")
        val provider = RecordingProvider()
        val publisher = RecordingPublisher()
        val attentionStore = SessionAttentionStore()
        var attentionState = IdeAttentionState(
            isIdeFrameFocused = false,
            isToolWindowVisible = false,
            activeSessionId = "",
        )
        private val settings = AgentSettingsService().apply { loadState(settingsState) }
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
        private val completionService = ChatCompletionNotificationService(
            settingsService = settings,
            attentionStateProvider = object : IdeAttentionStateProvider {
                override fun currentState(): IdeAttentionState = attentionState
            },
            attentionStore = attentionStore,
            publisher = publisher,
        )
        private val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            sessionTabsStore = SessionTabsAreaStore(),
            executionStatusStore = ExecutionStatusAreaStore(),
            conversationStore = ConversationAreaStore(),
            submissionStore = SubmissionAreaStore(),
            sidePanelStore = SidePanelAreaStore(),
            completionNotificationService = completionService,
            sessionAttentionStore = attentionStore,
        )

        fun createSession(): String {
            val sessionId = service.createSession()
            coordinator.onSessionActivated()
            return sessionId
        }

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
        }
    }

    private class RecordingProvider : AgentProvider {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        private val sinks = ConcurrentHashMap<String, kotlinx.coroutines.channels.SendChannel<com.auracode.assistant.session.kernel.SessionDomainEvent>>()

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

        override fun stream(request: AgentRequest): kotlinx.coroutines.flow.Flow<com.auracode.assistant.session.kernel.SessionDomainEvent> = callbackFlow {
            requests += request
            sinks[request.prompt] = channel
            awaitClose { sinks.remove(request.prompt) }
        }

        fun emit(prompt: String, event: ProviderEvent) {
            com.auracode.assistant.test.trySendProviderEvent(
                checkNotNull(sinks[prompt]) { "No active stream for prompt '$prompt'" },
                event,
            )
        }

        override fun cancel(requestId: String) = Unit
    }

    private class RecordingPublisher : CompletionNotificationPublisher {
        val signals = CopyOnWriteArrayList<ChatCompletionSignal>()

        override fun publish(signal: ChatCompletionSignal) {
            signals += signal
        }
    }
}
