package com.auracode.assistant.toolwindow.conversation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationCollapsibleMessageContentTest {
    @Test
    fun `content taller than the collapsed maximum becomes collapsible`() {
        assertTrue(
            conversationShouldCollapseMessageContent(
                fullContentHeightPx = 321,
                collapsedMaxHeightPx = 220,
            ),
        )
    }

    @Test
    fun `content within the collapsed maximum stays expanded`() {
        assertFalse(
            conversationShouldCollapseMessageContent(
                fullContentHeightPx = 220,
                collapsedMaxHeightPx = 220,
            ),
        )
    }

    @Test
    fun `collapsed viewport height is capped by the collapsed maximum`() {
        kotlin.test.assertEquals(
            220,
            conversationMessageViewportHeightPx(
                fullContentHeightPx = 321,
                collapsedMaxHeightPx = 220,
                expanded = false,
            ),
        )
    }

    @Test
    fun `expanded viewport height reveals the full measured content`() {
        kotlin.test.assertEquals(
            321,
            conversationMessageViewportHeightPx(
                fullContentHeightPx = 321,
                collapsedMaxHeightPx = 220,
                expanded = true,
            ),
        )
    }
}
