package com.auracode.assistant.toolwindow.conversation

import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineMessageCollapseControlTest {
    @Test
    fun `collapsed state uses expand copy and downward chevron`() {
        val model = timelineMessageCollapseControlModel(expanded = false)

        assertEquals("timeline.message.expand", model.actionLabelKey)
        assertEquals("/icons/arrow-down.svg", model.iconPath)
    }

    @Test
    fun `expanded state uses collapse copy and upward chevron`() {
        val model = timelineMessageCollapseControlModel(expanded = true)

        assertEquals("timeline.message.collapse", model.actionLabelKey)
        assertEquals("/icons/arrow-up.svg", model.iconPath)
    }
}
