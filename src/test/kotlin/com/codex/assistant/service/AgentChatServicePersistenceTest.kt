package com.codex.assistant.service

import com.codex.assistant.i18n.CodexBundle
import com.codex.assistant.model.AgentRequest
import com.codex.assistant.model.EngineEvent
import com.codex.assistant.model.TimelineAction
import com.codex.assistant.persistence.chat.PersistedAttachmentKind
import com.codex.assistant.persistence.chat.PersistedMessageAttachment
import com.codex.assistant.persistence.chat.PersistedTimelineRecordType
import com.codex.assistant.persistence.chat.SQLiteChatSessionRepository
import com.codex.assistant.provider.AgentProvider
import com.codex.assistant.provider.AgentProviderFactory
import com.codex.assistant.provider.EngineCapabilities
import com.codex.assistant.provider.EngineDescriptor
import com.codex.assistant.provider.ProviderRegistry
import com.codex.assistant.protocol.ItemStatus
import com.codex.assistant.settings.AgentSettingsService
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
        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider = ReplyingProvider("unused")),
            settings = settings,
        )

        assertEquals("", service.listSessions().single().title)
        assertEquals(CodexBundle.message("session.new"), service.currentSessionTitle())
        service.dispose()

        val reloaded = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider = ReplyingProvider("unused")),
            settings = settings,
        )

        assertEquals("", reloaded.listSessions().single().title)
        assertEquals(CodexBundle.message("session.new"), reloaded.currentSessionTitle())
        reloaded.dispose()
    }

    @Test
    fun `persists assistant replies and restores them after service reload`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-persistence").resolve("chat.db")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider = ReplyingProvider("assistant reply")),
            settings = settings,
        )
        service.recordUserMessage(prompt = "user prompt")

        val finished = CompletableDeferred<Unit>()
        service.runAgent(
            engineId = "codex",
            model = "gpt-5.3-codex",
            prompt = "user prompt",
            contextFiles = emptyList(),
        ) { action ->
            if (action == TimelineAction.FinishTurn) {
                finished.complete(Unit)
            }
        }
        withTimeout(2_000) { finished.await() }
        service.dispose()

        val reloaded = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider = ReplyingProvider("unused")),
            settings = settings,
        )
        val history = reloaded.loadCurrentSessionTimeline(limit = 10)

        assertEquals(
            listOf(
                PersistedTimelineRecordType.MESSAGE to "user prompt",
                PersistedTimelineRecordType.MESSAGE to "assistant reply",
            ),
            history.entries.map { it.recordType to it.body },
        )

        reloaded.dispose()
    }

    @Test
    fun `persists user attachments and tool activity into timeline history`() = runBlocking {
        val dbPath = createTempDirectory("chat-service-timeline").resolve("chat.db")
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider = TimelineProvider()),
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
        ) { action ->
            if (action == TimelineAction.FinishTurn) {
                finished.complete(Unit)
            }
        }
        withTimeout(2_000) { finished.await() }
        service.dispose()

        val reloaded = AgentChatService(
            repository = SQLiteChatSessionRepository(dbPath),
            registry = registry(provider = ReplyingProvider("unused")),
            settings = settings,
        )
        val history = reloaded.loadCurrentSessionTimeline(limit = 10)

        assertEquals(
            listOf(
                PersistedTimelineRecordType.MESSAGE,
                PersistedTimelineRecordType.ACTIVITY,
                PersistedTimelineRecordType.MESSAGE,
            ),
            history.entries.map { it.recordType },
        )
        val userEntry = history.entries.first()
        assertEquals("look at this", userEntry.body)
        assertEquals("turn-1", userEntry.turnId)
        assertEquals(1, userEntry.attachments.size)
        assertEquals("/assets/preview.png", userEntry.attachments.single().assetPath)

        val activityEntry = history.entries[1]
        assertEquals("Run Echo command", activityEntry.title)
        assertEquals("done", history.entries.last().body)
        assertTrue(history.entries.all { it.turnId == "turn-1" })

        reloaded.dispose()
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
    }

    private class ReplyingProvider(
        private val reply: String,
    ) : AgentProvider {
        override fun stream(request: AgentRequest): Flow<EngineEvent> = flow {
            emit(EngineEvent.AssistantTextDelta(reply))
            emit(EngineEvent.Completed(exitCode = 0))
        }

        override fun cancel(requestId: String) = Unit
    }

    private class TimelineProvider : AgentProvider {
        override fun stream(request: AgentRequest): Flow<EngineEvent> = flow {
            emit(EngineEvent.TurnStarted(turnId = "turn-1", threadId = "thread-1"))
            emit(EngineEvent.ToolCallStarted(callId = "tool-1", name = "shell", input = "echo hi"))
            emit(EngineEvent.ToolCallFinished(callId = "tool-1", name = "shell", output = "echo hi"))
            emit(EngineEvent.AssistantTextDelta("done"))
            emit(EngineEvent.Completed(exitCode = 0))
        }

        override fun cancel(requestId: String) = Unit
    }
}
