package com.auracode.assistant.toolwindow.eventing

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.provider.AgentProviderFactory
import com.auracode.assistant.provider.EngineCapabilities
import com.auracode.assistant.provider.EngineDescriptor
import com.auracode.assistant.provider.ProviderRegistry
import com.auracode.assistant.provider.claude.ClaudeModelCatalog
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.protocol.ProviderToolUserInputPrompt
import com.auracode.assistant.protocol.ProviderToolUserInputQuestion
import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.submission.SubmissionAreaStore
import com.auracode.assistant.toolwindow.shell.SidePanelAreaStore
import com.auracode.assistant.toolwindow.sessions.SessionTabsAreaStore
import com.auracode.assistant.toolwindow.execution.ExecutionStatusAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationAreaStore
import com.auracode.assistant.toolwindow.conversation.ConversationActivityItem
import com.auracode.assistant.persistence.chat.SQLiteChatSessionRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolWindowCoordinatorMultiSessionTest {
    @Test
    fun `selecting claude routes the next submission through claude provider`() {
        val harness = MultiEngineCoordinatorHarness()
        harness.settleStartup()

        harness.eventHub.publishUiIntent(UiIntent.SelectEngine("claude"))
        harness.waitUntil { harness.submissionStore.state.value.selectedEngineId == "claude" }

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-claude", TextRange(10))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.claudeProvider.requests.size == 1 }

        assertEquals(1, harness.claudeProvider.requests.size)
        assertEquals("claude", harness.claudeProvider.requests.single().engineId)
        assertEquals(ClaudeModelCatalog.defaultModel, harness.claudeProvider.requests.single().model)
        assertTrue(harness.codexProvider.requests.isEmpty())

        harness.dispose()
    }

    @Test
    fun `selecting a different engine from a populated session keeps the same session and preserves draft state`() {
        val harness = MultiEngineCoordinatorHarness()
        harness.settleStartup()
        val sentFile = harness.workingDir.resolve("sent.md")
        val draftFile = harness.workingDir.resolve("draft.md")
        Files.writeString(sentFile, "sent file")
        Files.writeString(draftFile, "draft file")

        val originalSessionId = harness.service.getCurrentSessionId()
        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-codex", TextRange(9))))
        harness.eventHub.publishUiIntent(UiIntent.AddContextFiles(listOf(sentFile.toString())))
        harness.eventHub.publishUiIntent(UiIntent.AddAttachments(listOf(sentFile.toString())))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.codexProvider.requests.size == 1 }
        harness.codexProvider.emit("run-codex", ProviderEvent.ThreadStarted(threadId = "thread-codex"))
        harness.codexProvider.emit("run-codex", ProviderEvent.TurnStarted(turnId = "turn-codex", threadId = "thread-codex"))
        harness.codexProvider.emit("run-codex", ProviderEvent.TurnCompleted(turnId = "turn-codex", outcome = TurnOutcome.SUCCESS))
        harness.waitUntil {
            harness.service.listSessions().first { it.id == originalSessionId }.messageCount == 1
        }
        harness.service.cancelSessionRun(originalSessionId)
        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("draft-follow-up", TextRange(15))))
        harness.eventHub.publishUiIntent(UiIntent.AddContextFiles(listOf(draftFile.toString())))
        harness.eventHub.publishUiIntent(UiIntent.AddAttachments(listOf(draftFile.toString())))

        harness.eventHub.publishUiIntent(UiIntent.SelectEngine("claude"))
        harness.waitUntil { harness.submissionStore.state.value.selectedEngineId == "claude" }
        harness.waitUntil {
            harness.service.listSessions().first { it.id == originalSessionId }.providerId == "claude"
        }

        val currentSession = harness.service.listSessions().first { it.id == originalSessionId }
        assertEquals(originalSessionId, harness.service.getCurrentSessionId())
        assertEquals("claude", currentSession.providerId)
        assertEquals("", currentSession.title)
        assertEquals("", currentSession.remoteConversationId)
        assertEquals(0, currentSession.messageCount)
        assertEquals(null, currentSession.usageSnapshot)
        assertTrue(harness.openedSessionIds.isEmpty())
        harness.waitUntil {
            harness.conversationStore.state.value.nodes.lastOrNull() is ConversationActivityItem.EngineSwitchedNode
        }
        val switchNode = harness.conversationStore.state.value.nodes.last() as ConversationActivityItem.EngineSwitchedNode
        assertEquals("Claude", switchNode.targetEngineLabel)
        assertTrue(switchNode.body.contains("Claude"))
        assertEquals("draft-follow-up", harness.submissionStore.state.value.document.text)
        assertEquals(1, harness.submissionStore.state.value.attachments.size)
        assertTrue(harness.submissionStore.state.value.contextEntries.any { it.path == draftFile.toString() })

        harness.dispose()
    }

    @Test
    fun `requesting engine switch on a populated session no longer opens a confirmation dialog`() {
        val harness = MultiEngineCoordinatorHarness()
        harness.settleStartup()

        val originalSessionId = harness.service.getCurrentSessionId()
        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-codex", TextRange(9))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.codexProvider.requests.size == 1 }
        harness.waitUntil {
            harness.service.listSessions().first { it.id == originalSessionId }.messageCount == 1
        }

        harness.eventHub.publishUiIntent(UiIntent.RequestEngineSwitch("claude"))
        harness.waitUntil { harness.submissionStore.state.value.engineSwitchConfirmation == null }

        assertEquals(originalSessionId, harness.service.getCurrentSessionId())
        assertEquals("codex", harness.service.listSessions().first { it.id == originalSessionId }.providerId)
        assertTrue(harness.openedSessionIds.isEmpty())

        harness.dispose()
    }

    @Test
    fun `sending after an in-place engine switch starts a fresh remote conversation`() {
        val harness = MultiEngineCoordinatorHarness()
        harness.settleStartup()

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-codex", TextRange(9))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.codexProvider.requests.size == 1 }
        harness.codexProvider.emit("run-codex", ProviderEvent.ThreadStarted(threadId = "thread-codex"))
        harness.codexProvider.emit("run-codex", ProviderEvent.TurnStarted(turnId = "turn-codex", threadId = "thread-codex"))
        harness.codexProvider.emit("run-codex", ProviderEvent.TurnCompleted(turnId = "turn-codex", outcome = TurnOutcome.SUCCESS))
        harness.waitUntil {
            harness.service.listSessions().first { it.id == harness.service.getCurrentSessionId() }.remoteConversationId == "thread-codex"
        }
        harness.service.cancelSessionRun(harness.service.getCurrentSessionId())

        harness.eventHub.publishUiIntent(UiIntent.SelectEngine("claude"))
        harness.waitUntil { harness.submissionStore.state.value.selectedEngineId == "claude" }
        harness.waitUntil {
            harness.service.listSessions().first { it.id == harness.service.getCurrentSessionId() }.providerId == "claude"
        }

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-claude", TextRange(10))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.claudeProvider.requests.size == 1 }

        assertEquals(null, harness.claudeProvider.requests.single().remoteConversationId)

        harness.dispose()
    }

    @Test
    fun `switching engine while current session is running is ignored`() {
        val harness = MultiEngineCoordinatorHarness()
        harness.settleStartup()
        val originalSessionId = harness.service.getCurrentSessionId()

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-codex", TextRange(9))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.codexProvider.requests.size == 1 }
        harness.codexProvider.emit("run-codex", ProviderEvent.ThreadStarted(threadId = "thread-codex"))
        harness.codexProvider.emit("run-codex", ProviderEvent.TurnStarted(turnId = "turn-codex", threadId = "thread-codex"))
        harness.waitUntil {
            harness.service.listSessions().first { it.id == originalSessionId }.isRunning
        }

        harness.eventHub.publishUiIntent(UiIntent.SelectEngine("claude"))
        Thread.sleep(150)

        val currentSession = harness.service.listSessions().first { it.id == originalSessionId }
        assertEquals("codex", currentSession.providerId)
        assertEquals("thread-codex", currentSession.remoteConversationId)
        assertTrue(
            harness.conversationStore.state.value.nodes.none { it is ConversationActivityItem.EngineSwitchedNode },
        )

        harness.dispose()
    }

    @Test
    fun `switching engine on an empty session does not append engine switched marker`() {
        val harness = MultiEngineCoordinatorHarness()
        harness.settleStartup()
        val originalSessionId = harness.service.getCurrentSessionId()

        harness.eventHub.publishUiIntent(UiIntent.SelectEngine("claude"))
        harness.waitUntil { harness.submissionStore.state.value.selectedEngineId == "claude" }
        harness.waitUntil {
            harness.service.listSessions().first { it.id == originalSessionId }.providerId == "claude"
        }

        val currentSession = harness.service.listSessions().first { it.id == originalSessionId }
        assertEquals("claude", currentSession.providerId)
        assertEquals(0, currentSession.messageCount)
        assertTrue(
            harness.conversationStore.state.value.nodes.none { it is ConversationActivityItem.EngineSwitchedNode },
        )

        harness.dispose()
    }

    @Test
    fun `engine switched marker survives session projection rebuild after tab switches`() {
        val harness = MultiEngineCoordinatorHarness()
        harness.settleStartup()
        val originalSessionId = harness.service.getCurrentSessionId()

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-codex", TextRange(9))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.codexProvider.requests.size == 1 }
        harness.codexProvider.emit("run-codex", ProviderEvent.ThreadStarted(threadId = "thread-codex"))
        harness.codexProvider.emit("run-codex", ProviderEvent.TurnStarted(turnId = "turn-codex", threadId = "thread-codex"))
        harness.codexProvider.emit("run-codex", ProviderEvent.TurnCompleted(turnId = "turn-codex", outcome = TurnOutcome.SUCCESS))
        harness.waitUntil {
            harness.service.listSessions().first { it.id == originalSessionId }.messageCount == 1
        }
        harness.service.cancelSessionRun(originalSessionId)

        harness.eventHub.publishUiIntent(UiIntent.SelectEngine("claude"))
        harness.waitUntil {
            harness.conversationStore.state.value.nodes.lastOrNull() is ConversationActivityItem.EngineSwitchedNode
        }

        val secondSessionId = harness.createSession()
        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(secondSessionId))
        harness.waitUntil { harness.service.getCurrentSessionId() == secondSessionId }
        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(originalSessionId))
        harness.waitUntil { harness.service.getCurrentSessionId() == originalSessionId }

        harness.waitUntil {
            harness.conversationStore.state.value.nodes.lastOrNull() is ConversationActivityItem.EngineSwitchedNode
        }
        val switchNode = harness.conversationStore.state.value.nodes.last() as ConversationActivityItem.EngineSwitchedNode
        assertEquals("Claude", switchNode.targetEngineLabel)

        harness.dispose()
    }

    @Test
    fun `background session events do not overwrite the active tab timeline and restore on switch back`() {
        val harness = CoordinatorHarness()

        val sessionA = harness.service.getCurrentSessionId()
        val sessionB = harness.createSession()
        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionA }

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-a", TextRange(5))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }

        harness.provider.emit(
            prompt = "run-a",
            event = ProviderEvent.ThreadStarted(threadId = "thread-a"),
        )
        harness.provider.emit(
            prompt = "run-a",
            event = ProviderEvent.TurnStarted(turnId = "turn-a", threadId = "thread-a"),
        )
        harness.provider.emit(
            prompt = "run-a",
            event = ProviderEvent.ItemUpdated(
                ProviderItem(
                    id = "run-a:assistant",
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.RUNNING,
                    name = "message",
                    text = "assistant-a",
                ),
            ),
        )
        harness.waitUntil {
            harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.MessageNode>().any { it.text == "assistant-a" }
        }

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionB))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionB }
        harness.waitUntil {
            harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.MessageNode>().none { it.text == "assistant-a" }
        }

        harness.provider.emit(
            prompt = "run-a",
            event = ProviderEvent.ItemUpdated(
                ProviderItem(
                    id = "run-a:assistant",
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.SUCCESS,
                    name = "message",
                    text = "assistant-a done",
                ),
            ),
        )
        harness.provider.emit(
            prompt = "run-a",
            event = ProviderEvent.TurnCompleted(turnId = "turn-a", outcome = TurnOutcome.SUCCESS),
        )

        Thread.sleep(150)
        assertTrue(
            harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.MessageNode>().none {
                it.text.contains("assistant-a", ignoreCase = true)
            },
        )

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil {
            harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.MessageNode>().map { it.text } ==
                listOf("run-a", "assistant-a done")
        }

        val messages = harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.MessageNode>().map { it.text }
        assertEquals(listOf("run-a", "assistant-a done"), messages)

        harness.dispose()
    }

    @Test
    fun `queued background session submission dispatches without mutating the active tab`() {
        val harness = CoordinatorHarness()

        val sessionA = harness.service.getCurrentSessionId()
        val sessionB = harness.createSession()
        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionA }

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-a-1", TextRange(7))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }
        harness.provider.emit("run-a-1", ProviderEvent.ThreadStarted(threadId = "thread-a"))
        harness.provider.emit("run-a-1", ProviderEvent.TurnStarted(turnId = "turn-a-1", threadId = "thread-a"))

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-a-2", TextRange(7))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.submissionStore.state.value.pendingSubmissions.size == 1 }

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionB))
        harness.waitUntil {
            harness.chatService.getCurrentSessionId() == sessionB &&
                harness.submissionStore.state.value.currentSessionId == sessionB &&
                harness.submissionStore.state.value.pendingSubmissions.isEmpty()
        }
        assertTrue(harness.submissionStore.state.value.pendingSubmissions.isEmpty())

        harness.provider.emit("run-a-1", ProviderEvent.TurnCompleted(turnId = "turn-a-1", outcome = TurnOutcome.SUCCESS))
        harness.waitUntil { harness.provider.requests.size == 2 }
        assertEquals("run-a-2", harness.provider.requests.last().prompt)
        assertTrue(
            harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.MessageNode>().none { it.text == "run-a-2" },
        )

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil {
            harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.MessageNode>().any { it.text == "run-a-2" }
        }

        harness.dispose()
    }

    /** Verifies queued submissions survive session view restoration and stay scoped to their original session. */
    @Test
    fun `queued submission restores after switching away and back to the original session`() {
        val harness = CoordinatorHarness()

        val sessionA = harness.service.getCurrentSessionId()
        val sessionB = harness.createSession()
        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionA }

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-a-1", TextRange(7))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }
        harness.provider.emit("run-a-1", ProviderEvent.ThreadStarted(threadId = "thread-a"))
        harness.provider.emit("run-a-1", ProviderEvent.TurnStarted(turnId = "turn-a-1", threadId = "thread-a"))

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-a-2", TextRange(7))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.submissionStore.state.value.pendingSubmissions.size == 1 }

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionB))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionB }
        harness.waitUntil { harness.submissionStore.state.value.pendingSubmissions.isEmpty() }

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionA }
        harness.waitUntil { harness.submissionStore.state.value.pendingSubmissions.size == 1 }

        assertEquals("run-a-2", harness.submissionStore.state.value.pendingSubmissions.single().prompt)

        harness.dispose()
    }

    @Test
    fun `background tool input resolution keeps the correct user input timeline state`() {
        val harness = CoordinatorHarness()

        val sessionA = harness.service.getCurrentSessionId()
        val sessionB = harness.createSession()
        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil { harness.chatService.getCurrentSessionId() == sessionA }

        harness.eventHub.publishUiIntent(UiIntent.UpdateDocument(TextFieldValue("run-a", TextRange(5))))
        harness.eventHub.publishUiIntent(UiIntent.SendPrompt)
        harness.waitUntil { harness.provider.requests.size == 1 }
        harness.provider.emit("run-a", ProviderEvent.ThreadStarted(threadId = "thread-a"))
        harness.provider.emit("run-a", ProviderEvent.TurnStarted(turnId = "turn-a", threadId = "thread-a"))
        harness.provider.emit("run-a", ProviderEvent.ToolUserInputRequested(backgroundPrompt()))
        harness.waitUntil {
            harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.UserInputNode>().any {
                it.status == ItemStatus.RUNNING
            }
        }

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionB))
        harness.waitUntil {
            harness.chatService.getCurrentSessionId() == sessionB &&
                harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.UserInputNode>().none()
        }
        assertFalse(harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.UserInputNode>().any())

        harness.provider.emit("run-a", ProviderEvent.ToolUserInputResolved(requestId = "user-input-a"))
        Thread.sleep(150)

        harness.eventHub.publishUiIntent(UiIntent.SwitchSession(sessionA))
        harness.waitUntil {
            harness.conversationStore.state.value.nodes.filterIsInstance<ConversationActivityItem.UserInputNode>().any {
                it.status == ItemStatus.FAILED
            }
        }

        harness.dispose()
    }

    private fun backgroundPrompt(): ProviderToolUserInputPrompt {
        return ProviderToolUserInputPrompt(
            requestId = "user-input-a",
            threadId = "thread-a",
            turnId = "turn-a",
            itemId = "call-a",
            questions = listOf(
                ProviderToolUserInputQuestion(
                    id = "target",
                    header = "Target",
                    question = "Where should this run?",
                    options = emptyList(),
                    isOther = true,
                    isSecret = false,
                ),
            ),
        )
    }

    private class CoordinatorHarness {
        private val workingDir = createTempDirectory("multi-session-flow")
        val provider = RecordingMultiSessionProvider()
        private val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
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
        val submissionStore = SubmissionAreaStore()
        val chatService: AgentChatService = service
        private val coordinator = ToolWindowCoordinator(
            chatService = service,
            settingsService = settings,
            eventHub = eventHub,
            sessionTabsStore = SessionTabsAreaStore(),
            executionStatusStore = ExecutionStatusAreaStore(),
            conversationStore = conversationStore,
            submissionStore = submissionStore,
            sidePanelStore = SidePanelAreaStore(),
            historyPageSize = 10,
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

        fun createSession(): String {
            val sessionId = service.createSession()
            coordinator.onSessionActivated()
            return sessionId
        }

        fun dispose() {
            coordinator.dispose()
            service.dispose()
        }
    }

    private class MultiEngineCoordinatorHarness {
        val workingDir = createTempDirectory("multi-engine-flow")
        val codexProvider = RecordingMultiSessionProvider()
        val claudeProvider = RecordingMultiSessionProvider()
        val openedSessionIds = CopyOnWriteArrayList<String>()
        val conversationStore = ConversationAreaStore()
        private val settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) }
        val service = AgentChatService(
            repository = SQLiteChatSessionRepository(workingDir.resolve("chat.db")),
            registry = ProviderRegistry(
                descriptors = listOf(
                    EngineDescriptor(
                        id = "claude",
                        displayName = "Claude",
                        models = listOf("claude-sonnet-4-6", "claude-opus-4-1"),
                        capabilities = EngineCapabilities(
                            supportsThinking = true,
                            supportsToolEvents = false,
                            supportsCommandProposal = false,
                            supportsDiffProposal = false,
                        ),
                    ),
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
                        override val engineId: String = "claude"
                        override fun create(): AgentProvider = claudeProvider
                    },
                    object : AgentProviderFactory {
                        override val engineId: String = "codex"
                        override fun create(): AgentProvider = codexProvider
                    },
                ),
                defaultEngineId = "codex",
            ),
            settings = settings,
            workingDirectoryProvider = { workingDir.toString() },
        )
        val eventHub = ToolWindowEventHub()
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
            openSessionInNewTab = { sessionId ->
                openedSessionIds += sessionId
                true
            },
            historyPageSize = 10,
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

        fun settleStartup() {
            Thread.sleep(150)
        }

        fun createSession(): String {
            return service.createSession()
        }

        fun dispose() {
            coordinator.dispose()
            service.dispose()
        }
    }

    private class RecordingMultiSessionProvider : AgentProvider {
        val requests = CopyOnWriteArrayList<AgentRequest>()
        private val sinks = ConcurrentHashMap<String, kotlinx.coroutines.channels.SendChannel<com.auracode.assistant.session.kernel.SessionDomainEvent>>()

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

        override fun stream(request: AgentRequest): kotlinx.coroutines.flow.Flow<com.auracode.assistant.session.kernel.SessionDomainEvent> = callbackFlow {
            requests += request
            sinks[request.prompt] = channel
            awaitClose {
                sinks.remove(request.prompt)
            }
        }

        fun emit(prompt: String, event: ProviderEvent) {
            com.auracode.assistant.test.trySendProviderEvent(
                checkNotNull(sinks[prompt]) { "No active stream for prompt '$prompt'" },
                event,
            )
        }

        override fun cancel(requestId: String) = Unit
    }
}
