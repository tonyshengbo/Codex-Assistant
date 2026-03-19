package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.model.MessageRole
import com.codex.assistant.protocol.ItemStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineRegionLogicTest {
    @Test
    fun `user message node is recognized by role`() {
        val userItem = TimelineNode.MessageNode(
            id = "n1",
            sourceId = "n1",
            role = MessageRole.USER,
            text = "hello",
            status = ItemStatus.SUCCESS,
            timestamp = null,
            turnId = null,
            cursor = null,
        )
        val assistantItem = userItem.copy(id = "n2", role = MessageRole.ASSISTANT)
        val toolItem = TimelineNode.ToolCallNode(
            id = "n3",
            sourceId = "tool-1",
            title = "shell",
            body = "ls",
            status = ItemStatus.SUCCESS,
            turnId = null,
        )

        assertTrue(isUserMessageNode(userItem))
        assertFalse(isUserMessageNode(assistantItem))
        assertFalse(isUserMessageNode(toolItem))
    }
}
