package com.auracode.assistant.conversation.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityTitleFormatterTest {
    @Test
    fun `formats mcp tool title from structured tool name`() {
        assertEquals(
            "Call MCP · cloudview-gray · get_figma_node",
            ActivityTitleFormatter.toolTitle(
                explicitName = "mcp:cloudview-gray",
                body = """
                    - Server: `cloudview-gray`
                    - Tool: `get_figma_node`
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `extracts mcp server name only from valid mcp tool names`() {
        assertEquals("cloudview-gray", ActivityTitleFormatter.mcpServerName("mcp:cloudview-gray"))
        assertNull(ActivityTitleFormatter.mcpServerName("Tool Call"))
        assertNull(ActivityTitleFormatter.mcpServerName("mcp:"))
    }
}
