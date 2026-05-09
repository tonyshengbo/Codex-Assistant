package com.auracode.assistant.toolwindow.sessions

import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTabsOverflowActionTest {
    @Test
    fun `overflow popup actions use the bounded popup title`() {
        val action = SessionTabsOverflowAction(
            overflowTabs = listOf(
                SessionTab(
                    sessionId = "session-1",
                    tooltipTitle = "This is the full clean title that should stay out of the popup width budget",
                    displayTitle = "This is the full...",
                    overflowTitle = "This is the bounded popup title...",
                    active = false,
                    closable = true,
                    running = false,
                ),
            ),
            onSelect = {},
        )

        assertEquals(
            "This is the bounded popup title...",
            action.buildOverflowActions().single().templatePresentation.text,
        )
    }
}
