package com.auracode.assistant.toolwindow.history

import com.auracode.assistant.service.AgentChatService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HistoryConversationOpenResolverTest {
    private val resolver = HistoryConversationOpenResolver()

    @Test
    fun `returns existing open session when target conversation already has an opened tab`() {
        val result = resolver.resolve(
            remoteConversationId = "thread-1",
            activeSessionId = "active-session",
            sessions = listOf(
                session(id = "active-session", remoteConversationId = "", messageCount = 0),
                session(id = "opened-session", remoteConversationId = "thread-1", messageCount = 4),
                session(id = "closed-session", remoteConversationId = "thread-1", messageCount = 3),
            ),
            openSessionTabIds = setOf("active-session", "opened-session"),
        )

        assertIs<HistoryConversationOpenAction.SwitchToExistingSession>(result)
        assertEquals("opened-session", result.sessionId)
    }

    @Test
    fun `reuses current empty session when target conversation is not already opened`() {
        val result = resolver.resolve(
            remoteConversationId = "thread-2",
            activeSessionId = "active-session",
            sessions = listOf(
                session(id = "active-session", remoteConversationId = "", messageCount = 0),
                session(id = "closed-session", remoteConversationId = "thread-2", messageCount = 2),
            ),
            openSessionTabIds = setOf("active-session"),
        )

        assertIs<HistoryConversationOpenAction.ReuseActiveEmptySession>(result)
    }

    @Test
    fun `opens a new tab when target conversation is not opened and active session is not empty`() {
        val result = resolver.resolve(
            remoteConversationId = "thread-2",
            activeSessionId = "active-session",
            sessions = listOf(
                session(id = "active-session", remoteConversationId = "thread-current", messageCount = 1),
                session(id = "closed-session", remoteConversationId = "thread-2", messageCount = 2),
            ),
            openSessionTabIds = setOf("active-session"),
        )

        assertIs<HistoryConversationOpenAction.OpenInNewSessionTab>(result)
    }

    /** Builds a compact session summary fixture for history-open tests. */
    private fun session(
        id: String,
        remoteConversationId: String,
        messageCount: Int,
    ): AgentChatService.SessionSummary {
        return AgentChatService.SessionSummary(
            id = id,
            title = id,
            updatedAt = 1L,
            messageCount = messageCount,
            remoteConversationId = remoteConversationId,
            usageSnapshot = null,
            isRunning = false,
            providerId = "codex",
        )
    }
}
