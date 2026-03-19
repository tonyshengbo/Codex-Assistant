package com.codex.assistant.toolwindow.eventing

import com.codex.assistant.model.ChatMessage
import com.codex.assistant.model.EngineEvent
import com.codex.assistant.model.MessageRole
import com.codex.assistant.persistence.chat.PersistedAttachmentKind
import com.codex.assistant.persistence.chat.PersistedMessageAttachment
import com.codex.assistant.persistence.chat.SQLiteChatSessionRepository
import com.codex.assistant.provider.AgentProvider
import com.codex.assistant.provider.AgentProviderFactory
import com.codex.assistant.provider.EngineCapabilities
import com.codex.assistant.provider.EngineDescriptor
import com.codex.assistant.provider.ProviderRegistry
import com.codex.assistant.service.AgentChatService
import com.codex.assistant.protocol.ItemStatus
import com.codex.assistant.settings.AgentSettingsService
import com.codex.assistant.toolwindow.composer.ComposerAreaStore
import com.codex.assistant.toolwindow.drawer.RightDrawerAreaStore
import com.codex.assistant.toolwindow.header.HeaderAreaStore
import com.codex.assistant.toolwindow.status.StatusAreaStore
import com.codex.assistant.toolwindow.timeline.TimelineAreaStore
import com.codex.assistant.toolwindow.timeline.TimelineNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolWindowCoordinatorRestoreTest {
    @Test
    fun `rehydrate loads latest persisted history page for the active session`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val repository = SQLiteChatSessionRepository(createTempDirectory("coordinator-restore").resolve("chat.db"))
        val service = AgentChatService(
            repository = repository,
            registry = registryWithNoopProvider(),
            settings = settings,
        )
        service.recordUserMessage(prompt = "hello")
        repository.insertMessageRecord(
            sessionId = service.getCurrentSessionId(),
            message = ChatMessage(role = MessageRole.ASSISTANT, content = "world"),
        )

        val eventHub = ToolWindowEventHub()
        val timelineStore = TimelineAreaStore()
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = timelineStore,
            composerStore = ComposerAreaStore(),
            rightDrawerStore = RightDrawerAreaStore(),
            historyPageSize = 10,
        )
        waitUntil(timeoutMs = 2_000) { timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().size == 2 }

        val state = timelineStore.state.value
        assertEquals(listOf("hello", "world"), state.nodes.filterIsInstance<TimelineNode.MessageNode>().map { it.text })
        assertFalse(state.isRunning)

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `rehydrate restores tool activity and user attachments from persisted timeline history`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(createTempDirectory("coordinator-rich-restore").resolve("chat.db")),
            registry = registryWithTimelineProvider(),
            settings = settings,
        )
        service.recordUserMessage(
            prompt = "look at this",
            turnId = "local-turn-1",
            attachments = listOf(
                PersistedMessageAttachment(
                    id = "attachment-1",
                    kind = PersistedAttachmentKind.IMAGE,
                    displayName = "preview.png",
                    assetPath = "/assets/preview.png",
                    originalPath = "/tmp/preview.png",
                    mimeType = "image/png",
                    sizeBytes = 64L,
                    status = ItemStatus.SUCCESS,
                ),
            ),
        )

        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        service.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "look at this",
            localTurnId = "local-turn-1",
            contextFiles = emptyList(),
        ) { action ->
            if (action == com.codex.assistant.model.TimelineAction.FinishTurn) {
                finished.set(true)
            }
        }
        waitUntil(timeoutMs = 2_000) { finished.get() }

        val eventHub = ToolWindowEventHub()
        val timelineStore = TimelineAreaStore()
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = timelineStore,
            composerStore = ComposerAreaStore(),
            rightDrawerStore = RightDrawerAreaStore(),
            historyPageSize = 10,
        )
        waitUntil(timeoutMs = 2_000) { timelineStore.state.value.nodes.size == 3 }

        val state = timelineStore.state.value
        assertEquals(3, state.nodes.size)
        val userNode = assertIs<TimelineNode.MessageNode>(state.nodes[0])
        assertEquals("look at this", userNode.text)
        assertEquals(1, userNode.attachments.size)
        assertEquals("/assets/preview.png", userNode.attachments.single().assetPath)
        assertIs<TimelineNode.ToolCallNode>(state.nodes[1])
        assertEquals("done", assertIs<TimelineNode.MessageNode>(state.nodes[2]).text)

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `load older intent prepends older history without resetting newer messages`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(createTempDirectory("coordinator-history").resolve("chat.db")),
            registry = registryWithNoopProvider(),
            settings = settings,
        )
        listOf("first", "second", "third", "fourth").forEach { text ->
            service.recordUserMessage(prompt = text)
        }

        val eventHub = ToolWindowEventHub()
        val timelineStore = TimelineAreaStore()
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = timelineStore,
            composerStore = ComposerAreaStore(),
            rightDrawerStore = RightDrawerAreaStore(),
            historyPageSize = 2,
        )
        waitUntil(timeoutMs = 2_000) { timelineStore.state.value.nodes.size == 3 }

        assertTrue(timelineStore.state.value.nodes.first() is TimelineNode.LoadMoreNode)
        assertEquals(
            listOf("third", "fourth"),
            timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().map { it.text },
        )
        assertTrue(timelineStore.state.value.hasOlder)

        eventHub.publishUiIntent(UiIntent.LoadOlderMessages)
        waitUntil(timeoutMs = 2_000) { timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().size == 4 }

        val state = timelineStore.state.value
        assertEquals(listOf("first", "second", "third", "fourth"), state.nodes.filterIsInstance<TimelineNode.MessageNode>().map { it.text })
        assertFalse(state.hasOlder)

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `rehydrate then continue chat should append assistant response even when provider reuses turn id`() {
        val dbPath = createTempDirectory("coordinator-continue").resolve("chat.db")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val firstService = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registryWithSingleReplyProvider(reply = "old answer", turnId = "turn-1"),
            settings = settings,
        )
        firstService.recordUserMessage(prompt = "old question", turnId = "local-turn-1")

        val firstFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        firstService.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "old question",
            localTurnId = "local-turn-1",
            contextFiles = emptyList(),
        ) { action ->
            if (action == com.codex.assistant.model.TimelineAction.FinishTurn) {
                firstFinished.set(true)
            }
        }
        waitUntil(timeoutMs = 2_000) { firstFinished.get() }
        firstService.dispose()

        val secondService = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registryWithSingleReplyProvider(reply = "new answer", turnId = "turn-1"),
            settings = settings,
        )

        val eventHub = ToolWindowEventHub()
        val timelineStore = TimelineAreaStore()
        val coordinator = ToolWindowCoordinator(
            chatService = secondService,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = timelineStore,
            composerStore = ComposerAreaStore(),
            rightDrawerStore = RightDrawerAreaStore(),
            historyPageSize = 10,
        )
        waitUntil(timeoutMs = 2_000) { timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().size == 2 }

        eventHub.publishUiIntent(UiIntent.InputChanged("new question"))
        eventHub.publishUiIntent(UiIntent.SendPrompt)

        waitUntil(timeoutMs = 2_000) {
            !timelineStore.state.value.isRunning &&
                timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().size >= 3
        }

        val messages = timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>()
        assertEquals(
            listOf("old question", "old answer", "new question", "new answer"),
            messages.map { it.text },
        )

        coordinator.dispose()
        secondService.dispose()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition was not met within ${timeoutMs}ms")
            }
            Thread.sleep(20)
        }
    }

    private fun registryWithNoopProvider(): ProviderRegistry {
        return ProviderRegistry(
            descriptors = listOf(
                EngineDescriptor(
                    id = "codex",
                    displayName = "Codex",
                    models = listOf("gpt-5.3-codex"),
                    capabilities = EngineCapabilities(
                        supportsThinking = true,
                        supportsToolEvents = true,
                        supportsCommandProposal = false,
                        supportsDiffProposal = false,
                    ),
                ),
            ),
            factories = listOf(
                object : AgentProviderFactory {
                    override val engineId: String = "codex"
                    override fun create(): AgentProvider = NoopProvider
                },
            ),
            defaultEngineId = "codex",
        )
    }

    private fun registryWithTimelineProvider(): ProviderRegistry {
        return ProviderRegistry(
            descriptors = listOf(
                EngineDescriptor(
                    id = "codex",
                    displayName = "Codex",
                    models = listOf("gpt-5.3-codex"),
                    capabilities = EngineCapabilities(
                        supportsThinking = true,
                        supportsToolEvents = true,
                        supportsCommandProposal = false,
                        supportsDiffProposal = false,
                    ),
                ),
            ),
            factories = listOf(
                object : AgentProviderFactory {
                    override val engineId: String = "codex"
                    override fun create(): AgentProvider = TimelineProvider
                },
            ),
            defaultEngineId = "codex",
        )
    }

    private fun registryWithSingleReplyProvider(
        reply: String,
        turnId: String,
    ): ProviderRegistry {
        return ProviderRegistry(
            descriptors = listOf(
                EngineDescriptor(
                    id = "codex",
                    displayName = "Codex",
                    models = listOf("gpt-5.3-codex"),
                    capabilities = EngineCapabilities(
                        supportsThinking = true,
                        supportsToolEvents = true,
                        supportsCommandProposal = false,
                        supportsDiffProposal = false,
                    ),
                ),
            ),
            factories = listOf(
                object : AgentProviderFactory {
                    override val engineId: String = "codex"
                    override fun create(): AgentProvider = SingleReplyProvider(reply = reply, turnId = turnId)
                },
            ),
            defaultEngineId = "codex",
        )
    }

    private object NoopProvider : AgentProvider {
        override fun stream(request: com.codex.assistant.model.AgentRequest): Flow<EngineEvent> = emptyFlow()
        override fun cancel(requestId: String) = Unit
    }

    private object TimelineProvider : AgentProvider {
        override fun stream(request: com.codex.assistant.model.AgentRequest): Flow<EngineEvent> = flow {
            emit(EngineEvent.TurnStarted(turnId = "turn-1", threadId = "thread-1"))
            emit(EngineEvent.ToolCallStarted(callId = "tool-1", name = "shell", input = "echo hi"))
            emit(EngineEvent.ToolCallFinished(callId = "tool-1", name = "shell", output = "echo hi"))
            emit(EngineEvent.AssistantTextDelta("done"))
            emit(EngineEvent.Completed(exitCode = 0))
        }

        override fun cancel(requestId: String) = Unit
    }

    private class SingleReplyProvider(
        private val reply: String,
        private val turnId: String,
    ) : AgentProvider {
        override fun stream(request: com.codex.assistant.model.AgentRequest): Flow<EngineEvent> = flow {
            emit(EngineEvent.TurnStarted(turnId = turnId, threadId = "thread-1"))
            emit(EngineEvent.AssistantTextDelta(reply))
            emit(EngineEvent.Completed(exitCode = 0))
        }

        override fun cancel(requestId: String) = Unit
    }
}
