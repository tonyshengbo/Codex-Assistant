package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedApprovalRequest
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.execution.ApprovalAction
import com.auracode.assistant.toolwindow.execution.ApprovalAreaStore
import com.auracode.assistant.toolwindow.submission.ComposerAreaStore
import com.auracode.assistant.toolwindow.shell.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.sessions.HeaderAreaStore
import com.auracode.assistant.toolwindow.execution.StatusAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineNode
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

        harness.eventHub.publishUiIntent(UiIntent.ToggleExecutionMode)
        harness.waitUntil { harness.composerStore.state.value.executionMode == ComposerMode.APPROVAL }
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
            UnifiedApprovalRequest(
                requestId = "approval-1",
                turnId = "turn-1",
                itemId = "item-1",
                kind = UnifiedApprovalRequestKind.COMMAND,
                title = "Run command",
                body = "./gradlew test",
                command = "./gradlew test",
                cwd = ".",
            ),
        )

        harness.waitUntil { harness.approvalStore.state.value.current?.requestId == "approval-1" }
        harness.waitUntil { harness.composerStore.state.value.approvalPrompt?.requestId == "approval-1" }
        harness.eventHub.publishUiIntent(UiIntent.SubmitApprovalAction(ApprovalAction.ALLOW_FOR_SESSION))

        harness.waitUntil { harness.provider.decisions.isNotEmpty() }
        assertEquals("approval-1" to ApprovalAction.ALLOW_FOR_SESSION, harness.provider.decisions.single())
        assertEquals(null, harness.composerStore.state.value.approvalPrompt)
        val approvalNode = harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.ApprovalNode>().single()
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
        val timelineStore = TimelineAreaStore()
        val approvalStore = ApprovalAreaStore()
        val composerStore = ComposerAreaStore()
        private val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = timelineStore,
            composerStore = composerStore,
            rightDrawerStore = RightDrawerAreaStore(),
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

        fun dispose() {
            coordinator.dispose()
            service.dispose()
            Files.deleteIfExists(workingDir.resolve("chat.db"))
        }
    }

    private class RecordingApprovalProvider : AgentProvider {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        val decisions = CopyOnWriteArrayList<Pair<String, ApprovalAction>>()
        private var sink: (UnifiedEvent) -> Unit = {}

        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = callbackFlow {
            requests += request
            sink = { event -> trySend(event); Unit }
            trySend(UnifiedEvent.TurnStarted("turn-1", "thread-1"))
            awaitClose { sink = {} }
        }

        fun emitApproval(request: UnifiedApprovalRequest) {
            sink(UnifiedEvent.ApprovalRequested(request))
        }

        override fun submitApprovalDecision(requestId: String, decision: ApprovalAction): Boolean {
            decisions += requestId to decision
            sink(UnifiedEvent.TurnCompleted("turn-1", TurnOutcome.SUCCESS))
            return true
        }

        override fun cancel(requestId: String) = Unit
    }
}
