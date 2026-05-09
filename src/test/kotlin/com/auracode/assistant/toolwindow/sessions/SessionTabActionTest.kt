package com.auracode.assistant.toolwindow.sessions

import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTabActionTest {
    @Test
    fun `clicking the title label selects the tab`() {
        var selectedSessionId: String? = null
        val action = SessionTabAction(
            tab = SessionTab(
                sessionId = "session-1",
                tooltipTitle = "Session 1",
                displayTitle = "Session 1",
                overflowTitle = "Session 1",
                active = false,
                closable = true,
                running = false,
            ),
            onSelect = { selectedSessionId = it },
            onClose = {},
        )

        val component = runOnEdt {
            action.createCustomComponent(action.templatePresentation, "test") as JPanel
        }
        val titleLabel = component.getClientProperty("label") as JLabel

        SwingUtilities.invokeAndWait {
            titleLabel.dispatchEvent(
                MouseEvent(
                    titleLabel,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    0,
                    1,
                    1,
                    1,
                    false,
                    MouseEvent.BUTTON1,
                ),
            )
        }

        assertEquals("session-1", selectedSessionId)
    }

    @Test
    fun `tooltip uses the clean title instead of the visible label`() {
        val action = SessionTabAction(
            tab = SessionTab(
                sessionId = "session-1",
                tooltipTitle = "修复 tab hover 标题",
                displayTitle = "修复 tab hover...",
                overflowTitle = "修复 tab hover 标题",
                active = false,
                closable = true,
                running = false,
            ),
            onSelect = {},
            onClose = {},
        )

        val component = runOnEdt {
            action.createCustomComponent(action.templatePresentation, "test") as JPanel
        }
        val titleLabel = component.getClientProperty("label") as JLabel

        assertEquals("修复 tab hover 标题", titleLabel.toolTipText)
        assertEquals("修复 tab hover 标题", component.toolTipText)
        assertEquals("修复 tab hover...", titleLabel.text)
    }
}

private fun <T> runOnEdt(block: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait {
        result = runCatching(block)
    }
    return result!!.getOrThrow()
}
