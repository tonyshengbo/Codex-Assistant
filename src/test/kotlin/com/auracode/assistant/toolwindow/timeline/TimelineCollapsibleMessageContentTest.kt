package com.auracode.assistant.toolwindow.timeline

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineCollapsibleMessageContentTest {
    @Test
    fun `content taller than the collapsed maximum becomes collapsible`() {
        assertTrue(
            timelineShouldCollapseMessageContent(
                fullContentHeightPx = 321,
                collapsedMaxHeightPx = 220,
            ),
        )
    }

    @Test
    fun `content within the collapsed maximum stays expanded`() {
        assertFalse(
            timelineShouldCollapseMessageContent(
                fullContentHeightPx = 220,
                collapsedMaxHeightPx = 220,
            ),
        )
    }
}
