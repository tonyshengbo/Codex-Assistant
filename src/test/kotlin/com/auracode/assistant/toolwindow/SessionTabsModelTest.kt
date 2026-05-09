package com.auracode.assistant.toolwindow

import com.auracode.assistant.service.AgentChatService
import com.auracode.assistant.toolwindow.sessions.SessionTabsModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTabsModelTest {
    @Test
    fun `builds header tabs in open order with active marker`() {
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = listOf("s2", "s1"),
            activeSessionId = "s1",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "First Session", 2L, 3, "", providerId = "codex"),
                AgentChatService.SessionSummary("s2", "Second Session", 1L, 1, "", providerId = "claude"),
            ),
            availableWidthPx = 480,
        )

        assertEquals(listOf("Second Session", "First Session"), layout.visibleTabs.map { it.tooltipTitle })
        assertEquals(listOf(false, true), layout.visibleTabs.map { it.active })
        assertTrue(layout.visibleTabs.all { it.closable })
        assertTrue(layout.overflowTabs.isEmpty())
    }

    @Test
    fun `falls back to numbered titles when session title is blank`() {
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = listOf("s1"),
            activeSessionId = "s1",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "", 1L, 0, ""),
            ),
            availableWidthPx = 160,
        )

        assertEquals("T1", layout.visibleTabs.single().tooltipTitle)
        assertEquals("T1", layout.visibleTabs.single().displayTitle)
        assertEquals("T1", layout.visibleTabs.single().overflowTitle)
        assertFalse(layout.visibleTabs.single().closable)
    }

    @Test
    fun `long visible titles are truncated while full title is preserved`() {
        val layout = SessionTabsModel.buildTabs(
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
            availableWidthPx = 220,
        )

        assertEquals("Optimize top tab management panel like codex competitor", layout.visibleTabs.single().tooltipTitle)
        assertEquals("Optimize top ta...", layout.visibleTabs.single().displayTitle)
    }

    @Test
    fun `running tabs keep running state without changing the header title`() {
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = listOf("s1"),
            activeSessionId = "s1",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "First Session", 1L, 0, "", isRunning = true),
            ),
            availableWidthPx = 180,
        )

        assertEquals("First Session", layout.visibleTabs.single().tooltipTitle)
        assertTrue(layout.visibleTabs.single().running)
    }

    @Test
    fun `background completion marker keeps the clean title unchanged`() {
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = listOf("s1"),
            activeSessionId = "other",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "First Session", 1L, 0, "", isRunning = false),
            ),
            availableWidthPx = 180,
            unreadCompletionSessionIds = setOf("s1"),
        )

        assertTrue(layout.visibleTabs.single().hasUnreadCompletion)
        assertEquals("First Session", layout.visibleTabs.single().tooltipTitle)
    }

    @Test
    fun `strips provider metadata blocks from tab titles`() {
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = listOf("s1"),
            activeSessionId = "s1",
            sessions = listOf(
                AgentChatService.SessionSummary(
                    "s1",
                    """
                    修复 tab hover 标题

                    ##Agent Role and Instructions
                    1：用中文回复结果和推理过程
                    """.trimIndent(),
                    1L,
                    0,
                    "",
                ),
            ),
            availableWidthPx = 260,
        )

        assertEquals("修复 tab hover 标题", layout.visibleTabs.single().tooltipTitle)
        assertEquals("修复 tab hover 标题", layout.visibleTabs.single().displayTitle)
    }

    @Test
    fun `overflow popup title is capped to a stable maximum length`() {
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = listOf("s1"),
            activeSessionId = "s1",
            sessions = listOf(
                AgentChatService.SessionSummary(
                    "s1",
                    "12345678901234567890123456789012345678901234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ",
                    1L,
                    0,
                    "",
                ),
            ),
            availableWidthPx = 220,
        )

        assertEquals(48, layout.visibleTabs.single().overflowTitle.length)
        assertTrue(layout.visibleTabs.single().overflowTitle.endsWith("..."))
    }

    @Test
    fun `builds overflow when open tabs exceed visible limit`() {
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = listOf("s1", "s2", "s3", "s4", "s5"),
            activeSessionId = "s2",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "S1", 1L, 0, ""),
                AgentChatService.SessionSummary("s2", "S2", 1L, 0, ""),
                AgentChatService.SessionSummary("s3", "S3", 1L, 0, ""),
                AgentChatService.SessionSummary("s4", "S4", 1L, 0, ""),
                AgentChatService.SessionSummary("s5", "S5", 1L, 0, ""),
            ),
            availableWidthPx = 360,
        )

        assertEquals(listOf("s1", "s2", "s3"), layout.visibleTabs.map { it.sessionId })
        assertEquals(listOf("s4", "s5"), layout.overflowTabs.map { it.sessionId })
        assertTrue(layout.hasOverflow)
    }

    @Test
    fun `active overflow tab is promoted into visible area`() {
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = listOf("s1", "s2", "s3", "s4", "s5"),
            activeSessionId = "s5",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "S1", 1L, 0, ""),
                AgentChatService.SessionSummary("s2", "S2", 1L, 0, ""),
                AgentChatService.SessionSummary("s3", "S3", 1L, 0, ""),
                AgentChatService.SessionSummary("s4", "S4", 1L, 0, ""),
                AgentChatService.SessionSummary("s5", "S5", 1L, 0, ""),
            ),
            availableWidthPx = 360,
        )

        assertEquals(listOf("s1", "s2", "s5"), layout.visibleTabs.map { it.sessionId })
        assertEquals(listOf("s3", "s4"), layout.overflowTabs.map { it.sessionId })
        assertTrue(layout.visibleTabs.last().active)
    }

    @Test
    fun `does not create overflow when tabs exactly fit the width budget`() {
        val layout = SessionTabsModel.buildTabs(
            openSessionIds = listOf("s1", "s2", "s3", "s4", "s5"),
            activeSessionId = "s3",
            sessions = listOf(
                AgentChatService.SessionSummary("s1", "S1", 1L, 0, ""),
                AgentChatService.SessionSummary("s2", "S2", 1L, 0, ""),
                AgentChatService.SessionSummary("s3", "S3", 1L, 0, ""),
                AgentChatService.SessionSummary("s4", "S4", 1L, 0, ""),
                AgentChatService.SessionSummary("s5", "S5", 1L, 0, ""),
            ),
            availableWidthPx = 460,
        )

        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), layout.visibleTabs.map { it.sessionId })
        assertTrue(layout.overflowTabs.isEmpty())
    }
}
