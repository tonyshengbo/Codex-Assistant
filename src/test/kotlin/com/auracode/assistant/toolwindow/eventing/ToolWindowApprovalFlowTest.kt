package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderApprovalRequest
import com.auracode.assistant.protocol.ProviderApprovalRequestKind
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import com.auracode.assistant.toolwindow.execution.ApprovalAreaStore
import com.auracode.assistant.toolwindow.submission.SubmissionAreaStore
import com.auracode.assistant.toolwindow.shell.SidePanelAreaStore
import com.auracode.assistant.toolwindow.sessions.SessionTabsAreaStore
import com.auracode.assistant.toolwindow.execution.ExecutionStatusAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationActivityItem
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.Flow
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolWindowApprovalFlowTest {
    private fun registry(provider: AgentProvider): ProviderRegistry {
        return ProviderRegistry(
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
    }

    @Test
    fun `approval mode is passed to provider from composer mode`() {
        val harness = CoordinatorHarness()
        harness.settleStartup()

        harness.eventHub.publishUiIntent(UiIntent.ToggleExecutionMode)
        harness.waitUntil { harness.submissionStore.state.value.executionMode == SubmissionMode.APPROVAL }
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("run"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)

        harness.waitUntil { harness.provider.requests.isNotEmpty() }
        assertEquals(AgentApprovalMode.REQUIRE_CONFIRMATION, harness.provider.requests.single().approvalMode)

        harness.dispose()
    }

    @Test
    fun `approval decision is routed to provider and timeline node is updated`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.InputChanged("run"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitApproval(
            ProviderApprovalRequest(
                requestId = "approval-1",
                turnId = "turn-1",
                itemId = "item-1",
                kind = ProviderApprovalRequestKind.COMMAND,
                title = "Run command",
                body = "./gradlew test",
                command = "./gradlew test",
                cwd = ".",
            ),
        )

        harness.waitUntil { harness.approvalStore.state.value.current?.requestId == "approval-1" }
        harness.waitUntil { harness.submissionStore.state.value.approvalPrompt?.requestId == "approval-1" }
        harness.eventHub.publishUiIntent(UiIntent.SubmitApprovalAction(ApprovalAction.ALLOW_FOR_SESSION))

        harness.waitUntil { harness.provider.decisions.isNotEmpty() }
        assertEquals("approval-1" to ApprovalAction.ALLOW_FOR_SESSION, harness.provider.decisions.single())
        assertEquals(null, harness.submissionStore.state.value.approvalPrompt)
        val approvalNode = harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.ApprovalNode>().single()
        assertEquals(ItemStatus.SUCCESS, approvalNode.status)
        assertTrue(approvalNode.body.contains("Remembered for session"))

        harness.dispose()
    }

    private class CoordinatorHarness {
        private val workingDir = createTempDirectory("approval-flow")
        val provider = RecordingApprovalProvider()
        private val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        private val service = AgentChatService(
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
        val conversationStore = ConversationAreaStore()
        val approvalStore = ApprovalAreaStore()
        val submissionStore = SubmissionAreaStore()
        private val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            sessionTabsStore = SessionTabsAreaStore(),
            executionStatusStore = ExecutionStatusAreaStore(),
            conversationStore = conversationStore,
            submissionStore = submissionStore,
            sidePanelStore = SidePanelAreaStore(),
            approvalStore = approvalStore,
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

        fun settleStartup() {
            Thread.sleep(150)
        }

        fun dispose() {
            coordinator.dispose()
            service.dispose()
            Files.deleteIfExists(workingDir.resolve("chat.db"))
        }
    }

    private class RecordingApprovalProvider : AgentProvider {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        val decisions = CopyOnWriteArrayList<Pair<String, ApprovalAction>>()
        private var sink: (ProviderEvent) -> Unit = {}

        override fun stream(request: AgentRequest): kotlinx.coroutines.flow.Flow<com.auracode.assistant.session.kernel.SessionDomainEvent> = callbackFlow {
            requests += request
            sink = { event -> com.auracode.assistant.test.trySendProviderEvent(this, event); Unit }
            com.auracode.assistant.test.trySendProviderEvent(this, ProviderEvent.TurnStarted("turn-1", "thread-1"))
            awaitClose { sink = {} }
        }

        fun emitApproval(request: ProviderApprovalRequest) {
            sink(ProviderEvent.ApprovalRequested(request))
        }

        override fun submitApprovalDecision(requestId: String, decision: ApprovalAction): Boolean {
            decisions += requestId to decision
            sink(ProviderEvent.TurnCompleted("turn-1", TurnOutcome.SUCCESS))
            return true
        }

        override fun cancel(requestId: String) = Unit
    }
}
