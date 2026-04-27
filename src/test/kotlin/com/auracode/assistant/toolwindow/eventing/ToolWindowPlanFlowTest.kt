package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
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
import com.auracode.assistant.toolwindow.conversation.ConversationActivityItem
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ToolWindowPlanFlowTest {
    @Test
    fun `running turn plan updates populate timeline plan nodes`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitRunningPlanUpdate(
            ProviderEvent.RunningPlanUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = "Working through plan",
                steps = listOf(
                    com.auracode.assistant.protocol.ProviderPlanStep(step = "Inspect events", status = "completed"),
                    com.auracode.assistant.protocol.ProviderPlanStep(step = "Wire composer panel", status = "inProgress"),
                    com.auracode.assistant.protocol.ProviderPlanStep(step = "Verify flow", status = "pending"),
                ),
                body = """
                    Working through plan

                    - [completed] Inspect events
                    - [inProgress] Wire composer panel
                    - [pending] Verify flow
                """.trimIndent(),
            ),
        )

        harness.waitUntil { harness.conversationStore.state.value.nodes.any { it is ConversationActivityItem.PlanNode } }
        val planNode = harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.PlanNode>().single()
        assertEquals("turn-1", planNode.turnId)
        assertTrue(planNode.body.contains("Wire composer panel"))
        assertNull(harness.submissionStore.state.value.runningPlan)

        harness.dispose()
    }

    @Test
    fun `plan mode is passed to provider and successful plan turn opens completion prompt`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.SelectMode(SubmissionMode.APPROVAL))
        harness.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)

        harness.waitUntil { harness.provider.requests.isNotEmpty() }
        val request = harness.provider.requests.single()
        assertEquals(AgentCollaborationMode.PLAN, request.collaborationMode)
        assertEquals(AgentApprovalMode.REQUIRE_CONFIRMATION, request.approvalMode)

        harness.provider.emitPlanUpdate(
            ProviderItem(
                id = "req-1:plan:turn-1",
                kind = ItemKind.PLAN_UPDATE,
                status = ItemStatus.SUCCESS,
                name = "Plan Update",
                text = "- [pending] Ship plan mode\n- [pending] Reuse decision overlay",
            ),
        )
        harness.provider.completeTurn()

        harness.waitUntil { harness.submissionStore.state.value.planCompletion != null }
        val prompt = harness.submissionStore.state.value.planCompletion
        assertEquals("Ship plan mode", prompt?.planTitle)
        assertNull(harness.submissionStore.state.value.runningPlan)

        harness.dispose()
    }

    @Test
    fun `successful running plan without completion body still clears running panel`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitRunningPlanUpdate(
            ProviderEvent.RunningPlanUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = "Working through plan",
                steps = listOf(
                    com.auracode.assistant.protocol.ProviderPlanStep(step = "Inspect events", status = "inProgress"),
                ),
                body = "",
            ),
        )

        harness.waitUntil { harness.conversationStore.state.value.nodes.any { it is ConversationActivityItem.PlanNode } }
        harness.provider.completeTurn()
        harness.waitUntil { !harness.conversationStore.state.value.isRunning }
        assertNull(harness.submissionStore.state.value.planCompletion)

        harness.dispose()
    }

    @Test
    fun `non plan turn running plan updates populate timeline and clear on turn completion`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Run normal claude turn"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitRunningPlanUpdate(
            ProviderEvent.RunningPlanUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = null,
                steps = listOf(
                    com.auracode.assistant.protocol.ProviderPlanStep(step = "Analyze timeline mapping", status = "completed"),
                    com.auracode.assistant.protocol.ProviderPlanStep(step = "Show running plan above composer", status = "in_progress"),
                ),
                body = """
                    - [x] Analyze timeline mapping
                    - [~] Show running plan above composer
                """.trimIndent(),
            ),
        )

        harness.waitUntil { harness.conversationStore.state.value.nodes.any { it is ConversationActivityItem.PlanNode } }
        val runningPlan = harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.PlanNode>().single()
        assertEquals("turn-1", runningPlan.turnId)
        assertTrue(runningPlan.body.contains("Show running plan above composer"))
        assertNull(harness.submissionStore.state.value.runningPlan)

        harness.provider.completeTurn()
        harness.waitUntil { !harness.conversationStore.state.value.isRunning }
        assertNull(harness.submissionStore.state.value.planCompletion)

        harness.dispose()
    }

    @Test
    fun `executing approved plan starts follow up turn on same thread with last execution mode`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.SelectMode(SubmissionMode.APPROVAL))
        harness.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitPlanUpdate(
            ProviderItem(
                id = "req-1:plan:turn-1",
                kind = ItemKind.PLAN_UPDATE,
                status = ItemStatus.SUCCESS,
                name = "Plan Update",
                text = "- [pending] Execute the plan",
            ),
        )
        harness.provider.completeTurn()
        harness.waitUntil { harness.submissionStore.state.value.planCompletion != null }

        harness.eventHub.publishUiIntent(UiIntent.ExecuteApprovedPlan)

        harness.waitUntil {
            harness.provider.requests.size == 2 &&
                harness.submissionStore.state.value.planCompletion == null
        }
        val executionRequest = harness.provider.requests.last()
        assertEquals("thread-1", executionRequest.remoteConversationId)
        assertEquals(AgentCollaborationMode.DEFAULT, executionRequest.collaborationMode)
        assertEquals(AgentApprovalMode.REQUIRE_CONFIRMATION, executionRequest.approvalMode)
        assertTrue(executionRequest.prompt.contains("approved the latest plan", ignoreCase = true))
        assertEquals(SubmissionMode.APPROVAL, harness.submissionStore.state.value.executionMode)
        assertEquals(false, harness.submissionStore.state.value.planEnabled)
        assertNull(harness.submissionStore.state.value.runningPlan)
        harness.waitUntil { harness.conversationStore.state.value.isRunning }

        harness.provider.emitRunningPlanUpdate(
            ProviderEvent.RunningPlanUpdated(
                threadId = "thread-1",
                turnId = "turn-1",
                explanation = "Working through execution",
                steps = listOf(
                    com.auracode.assistant.protocol.ProviderPlanStep(step = "Apply patch", status = "inProgress"),
                ),
                body = "- [inProgress] Apply patch",
            ),
        )
        harness.provider.emitPlanUpdate(
            ProviderItem(
                id = "req-2:plan:turn-1",
                kind = ItemKind.PLAN_UPDATE,
                status = ItemStatus.SUCCESS,
                name = "Plan Update",
                text = "- [completed] Apply patch",
            ),
        )
        harness.provider.completeTurn()
        harness.waitUntil { !harness.conversationStore.state.value.isRunning }

        assertNull(harness.submissionStore.state.value.runningPlan)
        assertNull(harness.submissionStore.state.value.planCompletion)

        harness.dispose()
    }

    @Test
    fun `dismissing plan completion keeps plan mode without auto starting execution`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitPlanUpdate(
            ProviderItem(
                id = "req-1:plan:turn-1",
                kind = ItemKind.PLAN_UPDATE,
                status = ItemStatus.SUCCESS,
                name = "Plan Update",
                text = "- [pending] Revise the plan",
            ),
        )
        harness.provider.completeTurn()
        harness.waitUntil { harness.submissionStore.state.value.planCompletion != null }

        harness.eventHub.publishUiIntent(UiIntent.DismissPlanCompletionPrompt)
        harness.waitUntil { harness.submissionStore.state.value.planCompletion == null }

        assertEquals(1, harness.provider.requests.size)
        assertTrue(harness.submissionStore.state.value.planEnabled)

        harness.dispose()
    }

    @Test
    fun `submitting plan revision starts another plan turn on same thread`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitPlanUpdate(
            ProviderItem(
                id = "req-1:plan:turn-1",
                kind = ItemKind.PLAN_UPDATE,
                status = ItemStatus.SUCCESS,
                name = "Plan Update",
                text = "- [pending] Revise the plan",
            ),
        )
        harness.provider.completeTurn()
        harness.waitUntil { harness.submissionStore.state.value.planCompletion != null }

        harness.eventHub.publishUiIntent(UiIntent.EditPlanRevisionDraft("Make it more incremental"))
        harness.eventHub.publishUiIntent(UiIntent.SubmitPlanRevision)

        harness.waitUntil { harness.provider.requests.size == 2 }
        val revisionRequest = harness.provider.requests.last()
        assertEquals("thread-1", revisionRequest.remoteConversationId)
        assertEquals(AgentCollaborationMode.PLAN, revisionRequest.collaborationMode)
        assertEquals(AgentApprovalMode.AUTO, revisionRequest.approvalMode)
        assertTrue(revisionRequest.prompt.contains("Make it more incremental"))
        assertTrue(harness.submissionStore.state.value.planEnabled)
        assertEquals(null, harness.submissionStore.state.value.planCompletion)

        harness.dispose()
    }

    private class CoordinatorHarness {
        private val workingDir = createTempDirectory("plan-flow")
        private val testDispatcher = Dispatchers.Default.limitedParallelism(1)
        val provider = RecordingPlanProvider()
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
            scopeDispatcher = testDispatcher,
        )
        val eventHub = ToolWindowEventHub()
        val conversationStore = ConversationAreaStore()
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
            runStartupWarmups = false,
            scopeDispatcher = testDispatcher,
        )

        fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
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

    private class RecordingPlanProvider : AgentProvider {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        private var sink: (ProviderEvent) -> Unit = {}

        override fun capabilities(): ConversationCapabilities = ConversationCapabilities(
            supportsStructuredHistory = false,
            supportsHistoryPagination = false,
            supportsPlanMode = true,
            supportsApprovalRequests = false,
            supportsToolUserInput = false,
            supportsResume = true,
            supportsAttachments = true,
            supportsImageInputs = true,
        )

        override fun stream(request: AgentRequest): kotlinx.coroutines.flow.Flow<com.auracode.assistant.session.kernel.SessionDomainEvent> = callbackFlow {
            requests += request
            sink = { event -> com.auracode.assistant.test.trySendProviderEvent(this, event); Unit }
            com.auracode.assistant.test.trySendProviderEvent(this, ProviderEvent.ThreadStarted("thread-1"))
            com.auracode.assistant.test.trySendProviderEvent(this, ProviderEvent.TurnStarted("turn-1", "thread-1"))
            awaitClose { sink = {} }
        }

        fun emitPlanUpdate(item: ProviderItem) {
            sink(ProviderEvent.ItemUpdated(item))
        }

        fun emitRunningPlanUpdate(event: ProviderEvent.RunningPlanUpdated) {
            sink(event)
        }

        fun completeTurn() {
            sink(ProviderEvent.TurnCompleted("turn-1", TurnOutcome.SUCCESS))
        }

        override fun cancel(requestId: String) = Unit
    }
}
