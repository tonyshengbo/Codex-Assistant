package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.model.AgentApprovalMode
import com.auracode.assistant.model.AgentCollaborationMode
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedItem
import com.auracode.assistant.protocol.UnifiedToolUserInputAnswerDraft
import com.auracode.assistant.protocol.UnifiedToolUserInputOption
import com.auracode.assistant.protocol.UnifiedToolUserInputPrompt
import com.auracode.assistant.protocol.UnifiedToolUserInputQuestion
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.submission.ComposerAreaStore
import com.auracode.assistant.toolwindow.shell.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.sessions.HeaderAreaStore
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.execution.StatusAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineNode
import com.auracode.assistant.toolwindow.execution.ToolUserInputPromptStore
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ToolWindowToolUserInputFlowTest {
    @Test
    fun `tool user input submission updates status prompt and timeline summary`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitToolUserInput(prompt())

        harness.waitUntil { harness.toolUserInputStore.state.value.current?.requestId == "user-input-1" }
        harness.waitUntil { harness.composerStore.state.value.toolUserInputPrompt?.requestId == "user-input-1" }
        assertEquals(UiText.Bundle("status.waitingInput"), harness.statusStore.state.value.turnStatus?.label)

        harness.eventHub.publishUiIntent(
            UiIntent.SelectToolUserInputOption(
                questionId = "builder_demo_target",
                optionLabel = "Reuse existing demo",
            ),
        )
        harness.eventHub.publishUiIntent(UiIntent.SubmitToolUserInputPrompt)

        harness.waitUntil { harness.provider.submissions.isNotEmpty() }
        assertEquals(
            "user-input-1" to mapOf(
                "builder_demo_target" to UnifiedToolUserInputAnswerDraft(
                    answers = listOf("Reuse existing demo"),
                ),
            ),
            harness.provider.submissions.single(),
        )
        harness.waitUntil { !harness.toolUserInputStore.state.value.visible }
        assertEquals(null, harness.composerStore.state.value.toolUserInputPrompt)
        assertEquals(UiText.Bundle("status.running"), harness.statusStore.state.value.turnStatus?.label)
        harness.waitUntil {
            harness.timelineStore.state.value.nodes
                .filterIsInstance<TimelineNode.UserInputNode>()
                .singleOrNull()
                ?.status == ItemStatus.SUCCESS
        }

        val node = harness.timelineStore.state.value.nodes.filterIsInstance<TimelineNode.UserInputNode>().single()
        assertEquals(ItemStatus.SUCCESS, node.status)
        assertTrue(node.body.contains("Reuse existing demo"))
        assertTrue(node.collapsedSummary.orEmpty().contains("Reuse existing demo"))

        harness.dispose()
    }

    @Test
    fun `plan mode continues after tool user input and still enters composer completion state`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }
        assertEquals(AgentCollaborationMode.PLAN, harness.provider.requests.single().collaborationMode)
        assertEquals(AgentApprovalMode.AUTO, harness.provider.requests.single().approvalMode)

        harness.provider.emitToolUserInput(prompt())
        harness.waitUntil { harness.toolUserInputStore.state.value.visible }
        harness.waitUntil { harness.composerStore.state.value.toolUserInputPrompt != null }
        harness.eventHub.publishUiIntent(
            UiIntent.SelectToolUserInputOption(
                questionId = "builder_demo_target",
                optionLabel = "Reuse existing demo",
            ),
        )
        harness.eventHub.publishUiIntent(UiIntent.SubmitToolUserInputPrompt)
        harness.waitUntil { harness.provider.submissions.isNotEmpty() }

        harness.provider.emitPlanUpdateAndCompleteTurn()

        harness.waitUntil { harness.composerStore.state.value.planCompletion != null }
        assertFalse(harness.toolUserInputStore.state.value.visible)
        assertTrue(harness.composerStore.state.value.planCompletion?.planTitle?.contains("Ship plan mode") == true)

        harness.dispose()
    }

    @Test
    fun `other like answer can be typed and submitted without explicit option confirmation`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitToolUserInput(otherLikePrompt())
        harness.waitUntil { harness.toolUserInputStore.state.value.current?.requestId == "user-input-2" }

        harness.eventHub.publishUiIntent(UiIntent.MoveToolUserInputSelectionNext)
        harness.eventHub.publishUiIntent(UiIntent.MoveToolUserInputSelectionNext)
        harness.waitUntil { harness.toolUserInputStore.state.value.freeformActive }

        harness.eventHub.publishUiIntent(
            UiIntent.EditToolUserInputAnswer(
                questionId = "token_type",
                value = "积分代币",
            ),
        )
        harness.eventHub.publishUiIntent(UiIntent.SubmitToolUserInputPrompt)

        harness.waitUntil { harness.provider.submissions.isNotEmpty() }
        assertEquals(
            "user-input-2" to mapOf(
                "token_type" to UnifiedToolUserInputAnswerDraft(
                    answers = listOf("积分代币"),
                ),
            ),
            harness.provider.submissions.single(),
        )

        harness.dispose()
    }

    @Test
    fun `cancelling tool user input ends the active run instead of returning to running`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitToolUserInput(prompt())
        harness.waitUntil { harness.toolUserInputStore.state.value.current?.requestId == "user-input-1" }
        assertEquals(UiText.Bundle("status.waitingInput"), harness.statusStore.state.value.turnStatus?.label)

        harness.eventHub.publishUiIntent(UiIntent.CancelToolUserInputPrompt)

        harness.waitUntil { harness.provider.submissions.isNotEmpty() }
        assertEquals(
            "user-input-1" to emptyMap(),
            harness.provider.submissions.single(),
        )
        harness.waitUntil { harness.provider.cancelledRequestIds.isNotEmpty() }
        harness.waitUntil { harness.statusStore.state.value.turnStatus == null }
        assertEquals(null, harness.composerStore.state.value.toolUserInputPrompt)

        harness.dispose()
    }

    @Test
    fun `cancelling plan mode tool input prevents later plan completion prompt`() {
        val harness = CoordinatorHarness()

        harness.eventHub.publishUiIntent(UiIntent.TogglePlanMode)
        harness.eventHub.publishUiIntent(UiIntent.InputChanged("Plan this change"))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.isNotEmpty() }

        harness.provider.emitToolUserInput(prompt())
        harness.waitUntil { harness.toolUserInputStore.state.value.visible }

        harness.eventHub.publishUiIntent(UiIntent.CancelToolUserInputPrompt)

        harness.waitUntil { harness.provider.cancelledRequestIds.isNotEmpty() }
        harness.provider.emitPlanUpdateAndCompleteTurn()
        Thread.sleep(100)

        assertEquals(null, harness.composerStore.state.value.planCompletion)
        assertEquals(null, harness.statusStore.state.value.turnStatus)

        harness.dispose()
    }

    private fun prompt(): UnifiedToolUserInputPrompt {
        return UnifiedToolUserInputPrompt(
            requestId = "user-input-1",
            threadId = "thread-1",
            turnId = "turn-1",
            itemId = "call-1",
            questions = listOf(
                UnifiedToolUserInputQuestion(
                    id = "builder_demo_target",
                    header = "Target",
                    question = "How should I handle the builder demo?",
                    options = listOf(
                        UnifiedToolUserInputOption(
                            label = "Reuse existing demo",
                            description = "Keep the current file and refine it",
                        ),
                    ),
                    isOther = true,
                    isSecret = false,
                ),
            ),
        )
    }

    private fun otherLikePrompt(): UnifiedToolUserInputPrompt {
        return UnifiedToolUserInputPrompt(
            requestId = "user-input-2",
            threadId = "thread-1",
            turnId = "turn-1",
            itemId = "call-2",
            questions = listOf(
                UnifiedToolUserInputQuestion(
                    id = "token_type",
                    header = "代币类型",
                    question = "你说的“代币系统”是哪种？",
                    options = listOf(
                        UnifiedToolUserInputOption(
                            label = "应用积分代币 (Recommended)",
                            description = "中心化账户余额系统，支持发放/转账/消费",
                        ),
                        UnifiedToolUserInputOption(
                            label = "区块链代币",
                            description = "智能合约风格（如 ERC-20）模型",
                        ),
                        UnifiedToolUserInputOption(
                            label = "Other",
                            description = "Provide a custom answer.",
                        ),
                    ),
                    isOther = true,
                    isSecret = false,
                ),
            ),
        )
    }

    private class CoordinatorHarness {
        private val workingDir = createTempDirectory("tool-user-input-flow")
        private val testDispatcher = Dispatchers.Default.limitedParallelism(1)
        val provider = RecordingToolUserInputProvider()
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
        val timelineStore = TimelineAreaStore()
        val composerStore = ComposerAreaStore()
        val statusStore = StatusAreaStore()
        val toolUserInputStore = ToolUserInputPromptStore()
        private val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = statusStore,
            timelineStore = timelineStore,
            composerStore = composerStore,
            rightDrawerStore = RightDrawerAreaStore(),
            toolUserInputPromptStore = toolUserInputStore,
            runStartupWarmups = false,
            scopeDispatcher = testDispatcher,
        )

        fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
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

    private class RecordingToolUserInputProvider : AgentProvider {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        val submissions = CopyOnWriteArrayList<Pair<String, Map<String, UnifiedToolUserInputAnswerDraft>>>()
        val cancelledRequestIds = CopyOnWriteArrayList<String>()
        private var sink: (UnifiedEvent) -> Unit = {}

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
            sink = { event -> trySend(event); Unit }
            trySend(UnifiedEvent.ThreadStarted("thread-1"))
            trySend(UnifiedEvent.TurnStarted("turn-1", "thread-1"))
            awaitClose { sink = {} }
        }

        fun emitToolUserInput(prompt: UnifiedToolUserInputPrompt) {
            sink(UnifiedEvent.ToolUserInputRequested(prompt))
        }

        fun emitPlanUpdateAndCompleteTurn() {
            sink(
                UnifiedEvent.ItemUpdated(
                    UnifiedItem(
                        id = "req-1:plan:turn-1",
                        kind = ItemKind.PLAN_UPDATE,
                        status = ItemStatus.SUCCESS,
                        name = "Plan Update",
                        text = "- [pending] Ship plan mode",
                    ),
                ),
            )
            sink(UnifiedEvent.TurnCompleted("turn-1", TurnOutcome.SUCCESS))
        }

        override fun submitToolUserInput(
            requestId: String,
            answers: Map<String, UnifiedToolUserInputAnswerDraft>,
        ): Boolean {
            submissions += requestId to answers
            sink(UnifiedEvent.ToolUserInputResolved(requestId))
            return true
        }

        override fun cancel(requestId: String) {
            cancelledRequestIds += requestId
        }
    }
}
