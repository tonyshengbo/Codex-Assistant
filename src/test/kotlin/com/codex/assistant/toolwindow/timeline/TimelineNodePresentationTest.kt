package com.codex.assistant.toolwindow.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineNodePresentationTest {
    @Test
    fun `assistant and result nodes use narrative presentation without toggle`() {
        val assistant = TimelineNodePresentation.forKind(TimelineNodeKind.ASSISTANT_NOTE)
        val result = TimelineNodePresentation.forKind(TimelineNodeKind.RESULT)

        assertEquals(TimelineNodeChrome.NARRATIVE, assistant.chrome)
        assertFalse(assistant.isToggleable)

        assertEquals(TimelineNodeChrome.RESULT, result.chrome)
        assertFalse(result.isToggleable)
    }

    @Test
    fun `tool and command nodes use execution presentation with card toggle`() {
        val tool = TimelineNodePresentation.forKind(TimelineNodeKind.TOOL_STEP)
        val command = TimelineNodePresentation.forKind(TimelineNodeKind.COMMAND_STEP)

        assertEquals(TimelineNodeChrome.EXECUTION, tool.chrome)
        assertTrue(tool.isToggleable)

        assertEquals(TimelineNodeChrome.EXECUTION, command.chrome)
        assertTrue(command.isToggleable)
    }

    @Test
    fun `failure and system nodes stay expanded and use supporting chrome`() {
        val failure = TimelineNodePresentation.forKind(TimelineNodeKind.FAILURE)
        val system = TimelineNodePresentation.forKind(TimelineNodeKind.SYSTEM_AUX)

        assertEquals(TimelineNodeChrome.ALERT, failure.chrome)
        assertFalse(failure.isToggleable)

        assertEquals(TimelineNodeChrome.SUPPORTING, system.chrome)
        assertFalse(system.isToggleable)
    }
}
