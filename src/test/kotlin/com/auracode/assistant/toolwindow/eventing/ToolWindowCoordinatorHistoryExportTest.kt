package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationRef
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.persistence.chat.PersistedChatSession
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
import com.auracode.assistant.toolwindow.shared.UiText
import com.auracode.assistant.toolwindow.shared.resolve
import com.auracode.assistant.toolwindow.execution.StatusAreaStore
import com.auracode.assistant.toolwindow.conversation.TimelineAreaStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ToolWindowCoordinatorHistoryExportTest {
    @Test
    fun `export remote conversation writes markdown transcript`() {
        val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val workingDir = createTempDirectory("coordinator-history-export")
        val repository = SQLiteChatSessionRepository(workingDir.resolve("chat.db"))
        repository.upsertSession(
            PersistedChatSession(
                id = "session-1",
                providerId = "codex",
                title = "Refine timeline",
                createdAt = 1L,
                updatedAt = 2L,
                messageCount = 2,
                remoteConversationId = "thread-1",
                usageSnapshot = null,
                isActive = true,
            ),
        )
        val provider = ExportHistoryProvider(
            historyTurns = listOf(
                historyTurn(turnId = "turn-1", userText = "First question", assistantText = "First answer"),
                historyTurn(turnId = "turn-2", userText = "Second question", assistantText = "Second answer"),
            ),
        )
        val service = AgentChatService(
            repository = repository,
            registry = registry(provider),
            settings = settings,
        )
        val eventHub = ToolWindowEventHub()
        val statusStore = StatusAreaStore()
        var suggestedName: String? = null
        var exportPath: String? = null
        var exportContent: String? = null
        val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            headerStore = HeaderAreaStore(),
            statusStore = statusStore,
            timelineStore = TimelineAreaStore(),
            composerStore = ComposerAreaStore(),
            rightDrawerStore = RightDrawerAreaStore(),
            pickExportPath = { suggested ->
                suggestedName = suggested
                workingDir.resolve(suggested).toString()
            },
            writeExportFile = { path, content ->
                exportPath = path
                exportContent = content
            },
            historyPageSize = 1,
        )

        eventHub.publishUiIntent(
            UiIntent.ExportRemoteConversation(
                remoteConversationId = "thread-1",
                title = "Refine timeline",
            ),
        )

        waitUntil(timeoutMs = 2_000) { exportContent != null }

        assertEquals("Refine-timeline.md", suggestedName)
        assertEquals(workingDir.resolve("Refine-timeline.md").toString(), exportPath)
        assertContains(assertNotNull(exportContent), "# Refine timeline")
        assertContains(assertNotNull(exportContent), "First question")
        assertContains(assertNotNull(exportContent), "Second answer")
        val toast = assertNotNull(statusStore.state.value.toast)
        assertContains((toast.text as UiText.Raw).resolve(), "Exported conversation")

        coordinator.dispose()
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

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("Condition was not met within ${timeoutMs}ms")
            }
            Thread.sleep(20)
        }
    }

    private class ExportHistoryProvider(
        private val historyTurns: List<List<UnifiedEvent>>,
    ) : AgentProvider {
        override fun stream(request: AgentRequest): Flow<UnifiedEvent> = emptyFlow()

        override suspend fun loadInitialHistory(ref: ConversationRef, pageSize: Int): ConversationHistoryPage {
            val pageTurns = historyTurns.takeLast(pageSize)
            return ConversationHistoryPage(
                events = pageTurns.flatten(),
                hasOlder = historyTurns.size > pageTurns.size,
                olderCursor = (historyTurns.size - pageTurns.size).takeIf { it > 0 }?.toString(),
            )
        }

        override suspend fun loadOlderHistory(ref: ConversationRef, cursor: String, pageSize: Int): ConversationHistoryPage {
            val endExclusive = cursor.toInt()
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
        fun historyTurn(turnId: String, userText: String, assistantText: String): List<UnifiedEvent> {
            return listOf(
                UnifiedEvent.TurnStarted(turnId = turnId, threadId = "thread-1"),
                UnifiedEvent.ItemUpdated(
                    UnifiedItem(
                        id = "user:$turnId",
                        kind = ItemKind.NARRATIVE,
                        status = ItemStatus.SUCCESS,
                        name = "user_message",
                        text = userText,
                    ),
                ),
                UnifiedEvent.ItemUpdated(
                    UnifiedItem(
                        id = "assistant:$turnId",
                        kind = ItemKind.NARRATIVE,
                        status = ItemStatus.SUCCESS,
                        name = "message",
                        text = assistantText,
                    ),
                ),
                UnifiedEvent.TurnCompleted(turnId = turnId, outcome = TurnOutcome.SUCCESS),
            )
        }
    }
}
