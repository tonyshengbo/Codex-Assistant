package com.auracode.assistant.conversation.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityTitleFormatterTest {
    @Test
    fun `formats sed print command as read`() {
        assertEquals(
            "Read Timeline.kt",
            ActivityTitleFormatter.commandTitle(
                command = "sed -n '1,120p' src/timeline/Timeline.kt",
            ),
        )
    }

    @Test
    fun `formats sed in-place command as edit`() {
        assertEquals(
            "Edit Timeline.kt",
            ActivityTitleFormatter.commandTitle(
                command = "sed -i '' 's/old/new/g' src/timeline/Timeline.kt",
            ),
        )
    }

    @Test
    fun `formats updated file change titles as edited`() {
        assertEquals(
            "Edited Timeline.kt",
            ActivityTitleFormatter.fileChangeTitle(
                changes = listOf(
                    ActivityTitleFormatter.FileChangeSummary(
                        path = "src/timeline/Timeline.kt",
                        kind = "updated",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `formats plural updated file changes as edited`() {
        assertEquals(
            "Edited 2 files",
            ActivityTitleFormatter.fileChangeTitle(
                changes = listOf(
                    ActivityTitleFormatter.FileChangeSummary(
                        path = "src/timeline/Timeline.kt",
                        kind = "updated",
                    ),
                    ActivityTitleFormatter.FileChangeSummary(
                        path = "src/timeline/TimelineRow.kt",
                        kind = "updated",
                    ),
                ),
            ),
        )
    }

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
