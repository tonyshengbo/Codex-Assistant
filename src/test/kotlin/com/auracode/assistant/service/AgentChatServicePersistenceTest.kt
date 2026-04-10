package com.auracode.assistant.service

import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationRef
import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.conversation.ConversationSummaryPage
import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.persistence.chat.PersistedAttachmentKind
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentChatServicePersistenceTest {
    @Test
    fun `new empty session keeps raw title blank and resolves localized fallback on reload`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-session-title").resolve("chat.db")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State(uiLanguage = "EN")) }
        val provider = RecordingHistoryProvider(threadId = "thread-1")
        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider),
            settings = settings,
        )

        assertEquals("", service.listSessions().single().title)
        assertEquals(AuraCodeBundle.message("session.new"), service.currentSessionTitle())
        service.dispose()

        val reloaded = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider),
            settings = settings,
        )

        assertEquals("", reloaded.listSessions().single().title)
        assertEquals(AuraCodeBundle.message("session.new"), reloaded.currentSessionTitle())
        reloaded.dispose()
    }

    @Test
    fun `restores remote history after reload without local timeline persistence`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-persistence").resolve("chat.db")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val provider = RecordingHistoryProvider(threadId = "thread-1")
        provider.enqueueAssistantReply("assistant reply")

        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider),
            settings = settings,
        )
        service.recordUserMessage(prompt = "user prompt", turnId = "local-turn-1")

        val finished = CompletableDeferred<Unit>()
        service.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "user prompt",
            localTurnId = "local-turn-1",
            contextFiles = emptyList(),
            onTurnPersisted = { finished.complete(Unit) },
        )
        withTimeout(2_000) { finished.await() }
        service.dispose()

        val reloaded = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider),
            settings = settings,
        )
        val history = reloaded.loadCurrentConversationHistory(limit = 10)

        assertEquals(
            listOf(MessageRole.USER to "user prompt", MessageRole.ASSISTANT to "assistant reply"),
            history.events.narrativeTexts(),
        )

        reloaded.dispose()
    }

    @Test
    fun `merges local attachments into restored remote user history`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-assets").resolve("chat.db")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val provider = RecordingHistoryProvider(threadId = "thread-1").apply {
            enqueueAssistantReply("done")
            emitToolInNextTurn(
                UnifiedItem(
                    id = "tool-1",
                    kind = ItemKind.TOOL_CALL,
                    status = ItemStatus.SUCCESS,
                    name = "shell",
                    text = "echo hi",
                ),
            )
        }
        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider),
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
                    sizeBytes = 42L,
                    status = ItemStatus.SUCCESS,
                ),
            ),
        )

        val finished = CompletableDeferred<Unit>()
        service.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "look at this",
            localTurnId = "local-turn-1",
            contextFiles = emptyList(),
            onTurnPersisted = { finished.complete(Unit) },
        )
        withTimeout(2_000) { finished.await() }
        service.dispose()

        val reloaded = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider),
            settings = settings,
        )
        val history = reloaded.loadCurrentConversationHistory(limit = 10)
        val userEvent = history.events.firstNarrative(role = MessageRole.USER)
        val toolEvent = history.events.first { it is UnifiedEvent.ItemUpdated && it.item.kind == ItemKind.TOOL_CALL }
        val assistantEvent = history.events.firstNarrative(role = MessageRole.ASSISTANT)

        assertEquals("look at this", userEvent.item.text)
        assertEquals(1, userEvent.item.attachments.size)
        assertEquals("/assets/preview.png", userEvent.item.attachments.single().assetPath)
        assertEquals("echo hi", (toolEvent as UnifiedEvent.ItemUpdated).item.text)
        assertEquals("done", assistantEvent.item.text)

        reloaded.dispose()
    }

    @Test
    fun `falls back to unfiltered remote history summaries when cwd-scoped query is empty`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-history-summary-fallback").resolve("chat.db")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val provider = CwdSensitiveHistorySummaryProvider(
            expectedCwd = "/project/current",
            fallbackPage = ConversationSummaryPage(
                conversations = listOf(
                    ConversationSummary(
                        remoteConversationId = "thread-1",
                        title = "历史会话",
                        createdAt = 1L,
                        updatedAt = 2L,
                        status = "idle",
                    ),
                ),
                nextCursor = null,
            ),
        )
        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider),
            settings = settings,
            workingDirectoryProvider = { "/project/current" },
        )

        val page = service.loadRemoteConversationSummaries(limit = 20)

        assertEquals(listOf("/project/current", null), provider.recordedCwds)
        assertEquals(listOf("thread-1"), page.conversations.map { it.remoteConversationId })

        service.dispose()
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

    private class RecordingHistoryProvider(
        private val threadId: String,
    ) : AgentProvider {
        private val turns = mutableListOf<List<UnifiedEvent>>()
        private val pendingAssistantReplies = ArrayDeque<String>()
        private var pendingTool: UnifiedItem? = null
        private var nextTurnNumber = 1

        fun enqueueAssistantReply(reply: String) {
            pendingAssistantReplies += reply
        }

        fun emitToolInNextTurn(item: UnifiedItem) {
            pendingTool = item
        }

        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = flow {
            val turnId = "turn-${nextTurnNumber++}"
            val toolItem = pendingTool
            val reply = pendingAssistantReplies.removeFirstOrNull().orEmpty()
            val events = buildList {
                add(UnifiedEvent.ThreadStarted(threadId = threadId))
                add(UnifiedEvent.TurnStarted(turnId = turnId, threadId = threadId))
                toolItem?.let { tool ->
                    add(UnifiedEvent.ItemUpdated(tool.copy(id = "${request.requestId}:${tool.id}")))
                }
                add(
                    UnifiedEvent.ItemUpdated(
                        UnifiedItem(
                            id = "${request.requestId}:assistant:$turnId",
                            kind = ItemKind.NARRATIVE,
                            status = ItemStatus.SUCCESS,
                            name = "message",
                            text = reply,
                        ),
                    ),
                )
                add(UnifiedEvent.TurnCompleted(turnId = turnId, outcome = TurnOutcome.SUCCESS))
            }
            turns += buildHistoryTurn(
                turnId = turnId,
                userText = request.prompt,
                assistantText = reply,
                toolItem = toolItem?.copy(id = "history:${toolItem.id}:$turnId"),
            )
            pendingTool = null
            events.forEach { emit(it) }
        }

        override suspend fun loadInitialHistory(ref: ConversationRef, pageSize: Int): ConversationHistoryPage {
            val pageTurns = turns.takeLast(pageSize)
            return ConversationHistoryPage(
                events = pageTurns.flatten(),
                hasOlder = turns.size > pageTurns.size,
                olderCursor = (turns.size - pageTurns.size).takeIf { it > 0 }?.toString(),
            )
        }

        override suspend fun loadOlderHistory(ref: ConversationRef, cursor: String, pageSize: Int): ConversationHistoryPage {
            val endExclusive = cursor.toIntOrNull() ?: 0
            val startInclusive = (endExclusive - pageSize).coerceAtLeast(0)
            val pageTurns = turns.subList(startInclusive, endExclusive)
            return ConversationHistoryPage(
                events = pageTurns.flatten(),
                hasOlder = startInclusive > 0,
                olderCursor = startInclusive.takeIf { it > 0 }?.toString(),
            )
        }

        override fun cancel(requestId: String) = Unit

        private fun buildHistoryTurn(
            turnId: String,
            userText: String,
            assistantText: String,
            toolItem: UnifiedItem? = null,
        ): List<UnifiedEvent> {
            return buildList {
                add(UnifiedEvent.TurnStarted(turnId = turnId, threadId = threadId))
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
                toolItem?.let { add(UnifiedEvent.ItemUpdated(it)) }
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
                add(UnifiedEvent.TurnCompleted(turnId = turnId, outcome = TurnOutcome.SUCCESS))
            }
        }
    }

    private class CwdSensitiveHistorySummaryProvider(
        private val expectedCwd: String,
        private val fallbackPage: ConversationSummaryPage,
    ) : AgentProvider {
        val recordedCwds = mutableListOf<String?>()

        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = flow { }

        override suspend fun listRemoteConversations(
            pageSize: Int,
            cursor: String?,
            cwd: String?,
            searchTerm: String?,
        ): ConversationSummaryPage {
            recordedCwds += cwd
            return if (cwd == expectedCwd) {
                ConversationSummaryPage(conversations = emptyList(), nextCursor = null)
            } else {
                fallbackPage
            }
        }

        override fun cancel(requestId: String) = Unit
    }

    private fun List<UnifiedEvent>.narrativeTexts(): List<Pair<MessageRole, String>> {
        return mapNotNull { event ->
            val item = (event as? UnifiedEvent.ItemUpdated)?.item ?: return@mapNotNull null
            if (item.kind != ItemKind.NARRATIVE || item.text.isNullOrBlank()) return@mapNotNull null
            val role = when (item.name) {
                "user_message" -> MessageRole.USER
                "system_message" -> MessageRole.SYSTEM
                else -> MessageRole.ASSISTANT
            }
            role to item.text.orEmpty()
        }
    }

    private fun List<UnifiedEvent>.firstNarrative(role: MessageRole): UnifiedEvent.ItemUpdated {
        return first { event ->
            val item = (event as? UnifiedEvent.ItemUpdated)?.item ?: return@first false
            item.kind == ItemKind.NARRATIVE && when (item.name) {
                "user_message" -> role == MessageRole.USER
                "system_message" -> role == MessageRole.SYSTEM
                else -> role == MessageRole.ASSISTANT
            }
        } as UnifiedEvent.ItemUpdated
    }
}
