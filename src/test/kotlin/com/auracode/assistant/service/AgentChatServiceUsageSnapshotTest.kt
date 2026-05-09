package com.auracode.assistant.service

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.settings.AgentSettingsService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class AgentChatServiceUsageSnapshotTest {
    @Test
    fun `stores latest thread token usage snapshot in the current session`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-usage").resolve("chat.db")
        val provider = UsageReportingProvider()
        val service = createService(dbPath, provider)

        val finished = CompletableDeferred<Unit>()
        service.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "Summarize the repo",
            contextFiles = emptyList(),
            onTurnPersisted = { finished.complete(Unit) },
        )
        withTimeout(2_000) { finished.await() }

        val snapshot = assertNotNull(service.currentUsageSnapshot())
        assertEquals("gpt-5.3-codex", snapshot.model)
        assertEquals(400_000, snapshot.contextWindow)
        assertEquals(116_986, snapshot.inputTokens)
        assertEquals(93_440, snapshot.cachedInputTokens)
        assertEquals(3_202, snapshot.outputTokens)
        assertEquals("47%", snapshot.headerLabel())

        val persistedRepository = SQLiteChatSessionRepository(dbPath)
        val persistedSnapshot = assertNotNull(persistedRepository.loadActiveSession()?.usageSnapshot)
        assertEquals("gpt-5.3-codex", persistedSnapshot.model)
        assertEquals(400_000, persistedSnapshot.contextWindow)
        assertEquals(116_986, persistedSnapshot.inputTokens)
        assertEquals(93_440, persistedSnapshot.cachedInputTokens)
        assertEquals(3_202, persistedSnapshot.outputTokens)
        val ledgerRecords = persistedRepository.listRecordsBySession(service.getCurrentSessionId())
        assertEquals(1, ledgerRecords.size)
        assertEquals(false, ledgerRecords.single().isBaseline)
        assertEquals("turn-1", ledgerRecords.single().sourceTurnId)

        val reloaded = createService(dbPath, provider = UsageReportingProvider())
        val reloadedSnapshot = assertNotNull(reloaded.currentUsageSnapshot())
        assertEquals("47%", reloadedSnapshot.headerLabel())

        reloaded.dispose()
        service.dispose()
    }

    @Test
    fun `stores first new-session usage snapshot as non baseline and appends later snapshots`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-usage-ledger").resolve("chat.db")
        val provider = SequenceUsageReportingProvider(
            updates = listOf(
                UsageUpdateSpec(
                    model = "gpt-5.4",
                    contextWindow = 400_000,
                    inputTokens = 100,
                    cachedInputTokens = 10,
                    outputTokens = 5,
                    turnId = "turn-1",
                ),
                UsageUpdateSpec(
                    model = "gpt-5.4",
                    contextWindow = 400_000,
                    inputTokens = 160,
                    cachedInputTokens = 30,
                    outputTokens = 20,
                    turnId = "turn-1",
                ),
            ),
        )
        val service = createService(dbPath, provider)

        val finished = CompletableDeferred<Unit>()
        service.runAgent(
            engineId = "codex",
            model = "gpt-5.4",
            prompt = "Summarize the repo",
            contextFiles = emptyList(),
            onTurnPersisted = { finished.complete(Unit) },
        )
        withTimeout(2_000) { finished.await() }

        val records = SQLiteChatSessionRepository(dbPath).listRecordsBySession(service.getCurrentSessionId())
        assertEquals(2, records.size)
        assertFalse(records.first().isBaseline)
        assertFalse(records.last().isBaseline)
        assertEquals(listOf(100, 160), records.map { it.inputTokens })
        assertEquals(listOf("turn-1", "turn-1"), records.map { it.sourceTurnId })

        service.dispose()
    }

    @Test
    fun `stores baseline for legacy session that already had a persisted usage snapshot`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-usage-legacy-baseline").resolve("chat.db")
        val repository = SQLiteChatSessionRepository(dbPath)
        repository.upsertSession(
            com.auracode.assistant.persistence.chat.PersistedChatSession(
                id = "legacy-session",
                providerId = "codex",
                title = "Legacy session",
                createdAt = 1L,
                updatedAt = 2L,
                messageCount = 3,
                remoteConversationId = "thread-legacy",
                usageSnapshot = com.auracode.assistant.model.TurnUsageSnapshot(
                    model = "gpt-5.4",
                    contextWindow = 400_000,
                    inputTokens = 90,
                    cachedInputTokens = 8,
                    outputTokens = 4,
                    capturedAt = 2L,
                ),
                isActive = true,
            ),
        )
        val provider = SequenceUsageReportingProvider(
            updates = listOf(
                UsageUpdateSpec(
                    model = "gpt-5.4",
                    contextWindow = 400_000,
                    inputTokens = 100,
                    cachedInputTokens = 10,
                    outputTokens = 5,
                    turnId = "turn-1",
                ),
            ),
        )
        val service = createService(dbPath, provider)

        val finished = CompletableDeferred<Unit>()
        service.runAgent(
            engineId = "codex",
            model = "gpt-5.4",
            prompt = "Continue the legacy session",
            contextFiles = emptyList(),
            onTurnPersisted = { finished.complete(Unit) },
        )
        withTimeout(2_000) { finished.await() }

        val records = SQLiteChatSessionRepository(dbPath).listRecordsBySession(service.getCurrentSessionId())
        assertEquals(1, records.size)
        assertEquals(true, records.single().isBaseline)

        service.dispose()
    }

    @Test
    fun `skips duplicate usage snapshots when tokens and model do not change`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-usage-dedup").resolve("chat.db")
        val provider = SequenceUsageReportingProvider(
            updates = listOf(
                UsageUpdateSpec(
                    model = "gpt-5.4",
                    contextWindow = 400_000,
                    inputTokens = 100,
                    cachedInputTokens = 10,
                    outputTokens = 5,
                    turnId = "turn-1",
                ),
                UsageUpdateSpec(
                    model = "gpt-5.4",
                    contextWindow = 200_000,
                    inputTokens = 100,
                    cachedInputTokens = 10,
                    outputTokens = 5,
                    turnId = "turn-1",
                ),
            ),
        )
        val service = createService(dbPath, provider)

        val finished = CompletableDeferred<Unit>()
        service.runAgent(
            engineId = "codex",
            model = "gpt-5.4",
            prompt = "Summarize the repo",
            contextFiles = emptyList(),
            onTurnPersisted = { finished.complete(Unit) },
        )
        withTimeout(2_000) { finished.await() }

        val records = SQLiteChatSessionRepository(dbPath).listRecordsBySession(service.getCurrentSessionId())
        assertEquals(1, records.size)
        assertEquals(400_000, records.single().contextWindow)
        assertEquals(false, records.single().isBaseline)

        service.dispose()
    }

    private fun createService(
        dbPath: java.nio.file.Path,
        provider: AgentProvider,
    ): AgentChatService {
        val registry = ProviderRegistry(
            descriptors = listOf(
                EngineDescriptor(
                    id = "codex",
                    displayName = "Codex",
                    models = listOf("gpt-5.3-codex", "gpt-5.4"),
                    capabilities = EngineCapabilities(
                        supportsThinking = true,
                        supportsToolEvents = false,
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
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        return AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry,
            settings = settings,
        )
    }

    private class UsageReportingProvider : AgentProvider {
        override fun stream(request: AgentRequest): kotlinx.coroutines.flow.Flow<com.auracode.assistant.session.kernel.SessionDomainEvent> = com.auracode.assistant.test.providerEventFlow {
            emit(
                ProviderEvent.ItemUpdated(
                    ProviderItem(
                        id = "${request.requestId}:assistant",
                        kind = ItemKind.NARRATIVE,
                        status = ItemStatus.SUCCESS,
                        name = "message",
                        text = "done",
                    ),
                ),
            )
            emit(
                ProviderEvent.ThreadTokenUsageUpdated(
                    threadId = "thread-1",
                    turnId = "turn-1",
                    contextWindow = 400_000,
                    inputTokens = 116_986,
                    cachedInputTokens = 93_440,
                    outputTokens = 3_202,
                ),
            )
            emit(ProviderEvent.TurnCompleted(turnId = "turn-1", outcome = TurnOutcome.SUCCESS))
        }

        override fun cancel(requestId: String) = Unit
    }

    private data class UsageUpdateSpec(
        val model: String,
        val contextWindow: Int,
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val outputTokens: Int,
        val turnId: String,
    )

    private class SequenceUsageReportingProvider(
        private val updates: List<UsageUpdateSpec>,
    ) : AgentProvider {
        override fun stream(request: AgentRequest): Flow<com.auracode.assistant.session.kernel.SessionDomainEvent> = com.auracode.assistant.test.providerEventFlow {
            emit(
                ProviderEvent.ItemUpdated(
                    ProviderItem(
                        id = "${request.requestId}:assistant",
                        kind = ItemKind.NARRATIVE,
                        status = ItemStatus.SUCCESS,
                        name = "message",
                        text = "done",
                    ),
                ),
            )
            updates.forEach { update ->
                emit(
                    ProviderEvent.ThreadTokenUsageUpdated(
                        threadId = "thread-1",
                        turnId = update.turnId,
                        contextWindow = update.contextWindow,
                        inputTokens = update.inputTokens,
                        cachedInputTokens = update.cachedInputTokens,
                        outputTokens = update.outputTokens,
                    ),
                )
            }
            emit(
                ProviderEvent.TurnCompleted(
                    turnId = updates.lastOrNull()?.turnId ?: "turn-1",
                    outcome = TurnOutcome.SUCCESS,
                ),
            )
        }

        override fun cancel(requestId: String) = Unit
    }
}
