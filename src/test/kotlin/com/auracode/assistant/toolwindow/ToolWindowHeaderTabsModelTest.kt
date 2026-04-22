package com.auracode.assistant.toolwindow

import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.toolwindow.session.ToolWindowHeaderTabsModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolWindowHeaderTabsModelTest {
    @Test
    fun `builds header tabs in open order with active marker`() {
        val layout = ToolWindowHeaderTabsModel.buildTabs(
            openSessionIds = listOf("s2", "s1"),
            activeSessionId = "s1",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "First Session", 2L, 3, "", providerId = "codex"),
                AgentChatService.SessionSummary("s2", "Second Session", 1L, 1, "", providerId = "claude"),
            ),
        )

        assertEquals(listOf("Second Session", "First Session"), layout.visibleTabs.map { it.fullTitle })
        assertEquals(listOf(false, true), layout.visibleTabs.map { it.active })
        assertTrue(layout.visibleTabs.all { it.closable })
        assertTrue(layout.overflowTabs.isEmpty())
    }

    @Test
    fun `falls back to numbered titles when session title is blank`() {
        val layout = ToolWindowHeaderTabsModel.buildTabs(
            openSessionIds = listOf("s1"),
            activeSessionId = "s1",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "", 1L, 0, ""),
            ),
        )

        assertEquals("T1", layout.visibleTabs.single().fullTitle)
        assertEquals("T1", layout.visibleTabs.single().displayTitle)
        assertFalse(layout.visibleTabs.single().closable)
    }

    @Test
    fun `long visible titles are truncated while full title is preserved`() {
        val layout = ToolWindowHeaderTabsModel.buildTabs(
            openSessionIds = listOf("s1"),
            activeSessionId = "s1",
            sessions = listOf(
                AgentChatService.SessionSummary(
                    "s1",
                    "Optimize top tab management panel like codex competitor",
                    1L,
                    0,
                    "",
                ),
            ),
        )

        assertEquals("Optimize top tab management panel like codex competitor", layout.visibleTabs.single().fullTitle)
        assertEquals("Optimize top ta...", layout.visibleTabs.single().displayTitle)
    }

    @Test
    fun `running tabs keep running state without changing the header title`() {
        val layout = ToolWindowHeaderTabsModel.buildTabs(
            openSessionIds = listOf("s1"),
            activeSessionId = "s1",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "First Session", 1L, 0, "", isRunning = true),
            ),
        )

        assertEquals("First Session", layout.visibleTabs.single().fullTitle)
        assertTrue(layout.visibleTabs.single().running)
    }

    @Test
    fun `background completion marker is included in unread tabs`() {
        val layout = ToolWindowHeaderTabsModel.buildTabs(
            openSessionIds = listOf("s1"),
            activeSessionId = "other",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "First Session", 1L, 0, "", isRunning = false),
            ),
            unreadCompletionSessionIds = setOf("s1"),
        )

        assertTrue(layout.visibleTabs.single().hasUnreadCompletion)
        assertEquals("First Session (Done)", layout.visibleTabs.single().fullTitle)
    }

    @Test
    fun `builds overflow when open tabs exceed visible limit`() {
        val layout = ToolWindowHeaderTabsModel.buildTabs(
            openSessionIds = listOf("s1", "s2", "s3", "s4", "s5"),
            activeSessionId = "s2",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "S1", 1L, 0, ""),
                AgentChatService.SessionSummary("s2", "S2", 1L, 0, ""),
                AgentChatService.SessionSummary("s3", "S3", 1L, 0, ""),
                AgentChatService.SessionSummary("s4", "S4", 1L, 0, ""),
                AgentChatService.SessionSummary("s5", "S5", 1L, 0, ""),
            ),
        )

        assertEquals(listOf("s1", "s2", "s3"), layout.visibleTabs.map { it.sessionId })
        assertEquals(listOf("s4", "s5"), layout.overflowTabs.map { it.sessionId })
        assertTrue(layout.hasOverflow)
    }

    @Test
    fun `active overflow tab is promoted into visible area`() {
        val layout = ToolWindowHeaderTabsModel.buildTabs(
            openSessionIds = listOf("s1", "s2", "s3", "s4", "s5"),
            activeSessionId = "s5",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "S1", 1L, 0, ""),
                AgentChatService.SessionSummary("s2", "S2", 1L, 0, ""),
                AgentChatService.SessionSummary("s3", "S3", 1L, 0, ""),
                AgentChatService.SessionSummary("s4", "S4", 1L, 0, ""),
                AgentChatService.SessionSummary("s5", "S5", 1L, 0, ""),
            ),
        )

        assertEquals(listOf("s1", "s2", "s5"), layout.visibleTabs.map { it.sessionId })
        assertEquals(listOf("s3", "s4"), layout.overflowTabs.map { it.sessionId })
        assertTrue(layout.visibleTabs.last().active)
    }
}
