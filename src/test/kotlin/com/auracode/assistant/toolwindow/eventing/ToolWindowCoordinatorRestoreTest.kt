package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationRef
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.persistence.chat.PersistedAttachmentKind
import com.auracode.assistant.persistence.chat.PersistedChatSession
import com.auracode.assistant.persistence.chat.PersistedMessageAttachment
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
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
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.toolwindow.submission.ComposerAreaStore
import com.auracode.assistant.toolwindow.shell.RightDrawerAreaStore
import com.auracode.assistant.toolwindow.sessions.HeaderAreaStore
import com.auracode.assistant.toolwindow.execution.StatusAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolWindowCoordinatorRestoreTest {
    @Test
    fun `rehydrate loads latest remote history page for the active session`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val repository = SQLiteChatSessionRepository(createTempDirectory("coordinator-restore").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                providerId = "codex",
                title = "hello",
                createdAt = 1L,
                updatedAt = 2L,
                messageCount = 2,
                remoteConversationId = "thread-1",
                usageSnapshot = null,
                isActive = true,
            ),
        )
        val provider = HistoryBackedProvider(
            historyTurns = mutableListOf(
                historyTurn(
                    turnId = "turn-1",
                    userText = "hello",
                    assistantText = "world",
                ),
            ),
        )
        val service = AgentChatService(
            repository = repository,
            registry = registry(provider),
            settings = settings,
        )

        val timelineStore = TimelineAreaStore()
        val coordinator = createCoordinator(service, settings, timelineStore)
        waitUntil(timeoutMs = 2_000) { timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().size == 2 }

        val state = timelineStore.state.value
        assertEquals(listOf("hello", "world"), state.nodes.filterIsInstance<TimelineNode.MessageNode>().map { it.text })
        assertFalse(state.isRunning)

        coordinator.dispose()
        service.dispose()
    }

    @Test
    fun `rehydrate restores tool activity and user attachments from remote history plus local assets`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val repository = SQLiteChatSessionRepository(createTempDirectory("coordinator-rich-restore").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                providerId = "codex",
                title = "look at this",
                createdAt = 1L,
                updatedAt = 2L,
                messageCount = 1,
                remoteConversationId = "thread-1",
                usageSnapshot = null,
                isActive = true,
            ),
        )
        repository.saveSessionAssets(
            sessionId = "session-1",
            turnId = "turn-1",
            messageRole = MessageRole.USER,
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
            createdAt = 3L,
        )
        val provider = HistoryBackedProvider(
            historyTurns = mutableListOf(
                historyTurn(
                    turnId = "turn-1",
                    userText = "look at this",
                    assistantText = "done",
                    extraItems = listOf(
                        UnifiedItem(
                            id = "tool-1",
                            kind = ItemKind.TOOL_CALL,
                            status = ItemStatus.SUCCESS,
                            name = "shell",
                            text = "echo hi",
                        ),
                    ),
                ),
            ),
        )
        val service = AgentChatService(
            repository = repository,
            registry = registry(provider),
            settings = settings,
        )

        val timelineStore = TimelineAreaStore()
        val coordinator = createCoordinator(service, settings, timelineStore)
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
        val repository = SQLiteChatSessionRepository(createTempDirectory("coordinator-history").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                providerId = "codex",
                title = "first",
                createdAt = 1L,
                updatedAt = 4L,
                messageCount = 4,
                remoteConversationId = "thread-1",
                usageSnapshot = null,
                isActive = true,
            ),
        )
        val provider = HistoryBackedProvider(
            historyTurns = mutableListOf(
                historyTurn(turnId = "turn-1", userText = "first", assistantText = ""),
                historyTurn(turnId = "turn-2", userText = "second", assistantText = ""),
                historyTurn(turnId = "turn-3", userText = "third", assistantText = ""),
                historyTurn(turnId = "turn-4", userText = "fourth", assistantText = ""),
            ),
        )
        val eventHub = ToolWindowEventHub()
        val timelineStore = TimelineAreaStore()
        val coordinator = ToolWindowCoordinator(
            chatService = AgentChatService(repository = repository, registry = registry(provider), settings = settings),
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
    }

    @Test
    fun `rehydrate then continue chat should append assistant response even when provider reuses turn id`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val repository = SQLiteChatSessionRepository(createTempDirectory("coordinator-continue").resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                providerId = "codex",
                title = "old question",
                createdAt = 1L,
                updatedAt = 2L,
                messageCount = 2,
                remoteConversationId = "thread-1",
                usageSnapshot = null,
                isActive = true,
            ),
        )
        val provider = HistoryBackedProvider(
            historyTurns = mutableListOf(
                historyTurn(turnId = "turn-1", userText = "old question", assistantText = "old answer"),
            ),
            liveStream = { request ->
                flow {
                    emit(UnifiedEvent.ThreadStarted(threadId = "thread-1"))
                    emit(UnifiedEvent.TurnStarted(turnId = "turn-1", threadId = "thread-1"))
                    emit(
                        UnifiedEvent.ItemUpdated(
                            UnifiedItem(
                                id = "${request.requestId}:assistant",
                                kind = ItemKind.NARRATIVE,
                                status = ItemStatus.SUCCESS,
                                name = "message",
                                text = "new answer",
                            ),
                        ),
                    )
                    emit(UnifiedEvent.TurnCompleted(turnId = "turn-1", outcome = TurnOutcome.SUCCESS))
                }
            },
        )
        val service = AgentChatService(
            repository = repository,
            registry = registry(provider),
            settings = settings,
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

        eventHub.publishUiIntent(UiIntent.InputChanged("new question"))
        eventHub.publishUiIntent(UiIntent.SendPrompt)

        waitUntil(timeoutMs = 2_000) {
            !timelineStore.state.value.isRunning &&
                timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>().size >= 4
        }

        val messages = timelineStore.state.value.nodes.filterIsInstance<TimelineNode.MessageNode>()
        assertEquals(
            listOf("old question", "old answer", "new question", "new answer"),
            messages.map { it.text },
        )

        coordinator.dispose()
        service.dispose()
    }

    private fun createCoordinator(
        service: AgentChatService,
        settings: AgentSettingsService,
        timelineStore: TimelineAreaStore,
    ): ToolWindowCoordinator {
        return ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = ToolWindowEventHub(),
            headerStore = HeaderAreaStore(),
            statusStore = StatusAreaStore(),
            timelineStore = timelineStore,
            composerStore = ComposerAreaStore(),
            rightDrawerStore = RightDrawerAreaStore(),
            historyPageSize = 10,
        )
    }

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

    private class HistoryBackedProvider(
        private val historyTurns: MutableList<List<UnifiedEvent>>,
        private val liveStream: (AgentRequest) -> Flow<UnifiedEvent> = { emptyFlow() },
    ) : AgentProvider {
        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = liveStream(request)

        override suspend fun loadInitialHistory(ref: ConversationRef, pageSize: Int): ConversationHistoryPage {
            val pageTurns = historyTurns.takeLast(pageSize)
            return ConversationHistoryPage(
                events = pageTurns.flatten(),
                hasOlder = historyTurns.size > pageTurns.size,
                olderCursor = (historyTurns.size - pageTurns.size).takeIf { it > 0 }?.toString(),
            )
        }

        override suspend fun loadOlderHistory(ref: ConversationRef, cursor: String, pageSize: Int): ConversationHistoryPage {
            val endExclusive = cursor.toIntOrNull() ?: 0
            val startInclusive = (endExclusive - pageSize).coerceAtLeast(0)
            val pageTurns = historyTurns.subList(startInclusive, endExclusive)
            return ConversationHistoryPage(
                events = pageTurns.flatten(),
                hasOlder = startInclusive > 0,
                olderCursor = startInclusive.takeIf { it > 0 }?.toString(),
            )
        }

        override fun cancel(requestId: String) = Unit
    }

    private companion object {
        fun historyTurn(
            turnId: String,
            userText: String,
            assistantText: String,
            extraItems: List<UnifiedItem> = emptyList(),
        ): List<UnifiedEvent> {
            return buildList {
                add(UnifiedEvent.TurnStarted(turnId = turnId, threadId = "thread-1"))
                add(
                    UnifiedEvent.ItemUpdated(
                        UnifiedItem(
                            id = "history:user:$turnId",
                            kind = ItemKind.NARRATIVE,
                            status = ItemStatus.SUCCESS,
                            name = "user_message",
                            text = userText,
                        ),
                    ),
                )
                extraItems.forEach { add(UnifiedEvent.ItemUpdated(it.copy(id = "history:${it.id}:$turnId"))) }
                if (assistantText.isNotBlank()) {
                    add(
                        UnifiedEvent.ItemUpdated(
                            UnifiedItem(
                                id = "history:assistant:$turnId",
                                kind = ItemKind.NARRATIVE,
                                status = ItemStatus.SUCCESS,
                                name = "message",
                                text = assistantText,
                            ),
                        ),
                    )
                }
                add(UnifiedEvent.TurnCompleted(turnId = turnId, outcome = TurnOutcome.SUCCESS))
            }
        }
    }
}
