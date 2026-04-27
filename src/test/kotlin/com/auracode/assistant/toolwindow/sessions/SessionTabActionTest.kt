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
                fullTitle = "Session 1",
                displayTitle = "Session 1",
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
}

private fun <T> runOnEdt(block: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait {
        result = runCatching(block)
    }
    return result!!.getOrThrow()
}
