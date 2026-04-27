package com.auracode.assistant.toolwindow.history

import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderFileChange
import com.auracode.assistant.protocol.ProviderItem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class HistoryConversationFormatterTest {
    @Test
    fun `formats thread status into friendly label`() {
        assertEquals("Running", formatHistoryStatus("active"))
        assertEquals("Ready", formatHistoryStatus("idle"))
        assertEquals("Unavailable", formatHistoryStatus("notLoaded"))
        assertEquals("Error", formatHistoryStatus("systemError"))
    }

    @Test
    fun `formats relative updated time`() {
        val nowMillis = 3_600_000L
        assertEquals("just now", formatHistoryUpdatedAt(updatedAtSeconds = 3_600L, nowMillis = nowMillis))
        assertEquals("5m ago", formatHistoryUpdatedAt(updatedAtSeconds = 3_300L, nowMillis = nowMillis))
        assertEquals("2h ago", formatHistoryUpdatedAt(updatedAtSeconds = -3_600L, nowMillis = nowMillis))
    }

    @Test
    fun `formats conversation export markdown as compact transcript`() {
        val markdown = formatConversationExportMarkdown(
            summary = ConversationSummary(
                remoteConversationId = "thread-1",
                title = "Refine timeline",
                createdAt = 1L,
                updatedAt = 1_710_000_000L,
                status = "active",
            ),
            events = com.auracode.assistant.test.mapProviderEvents(
                listOf(
                    ProviderEvent.TurnStarted(turnId = "turn-1", threadId = "thread-1"),
                    ProviderEvent.ItemUpdated(
                        ProviderItem(
                            id = "user-1",
                            kind = ItemKind.NARRATIVE,
                            status = ItemStatus.SUCCESS,
                            name = "user_message",
                            text = "Please refine the timeline UI.",
                        ),
                    ),
                    ProviderEvent.ItemUpdated(
                        ProviderItem(
                            id = "command-1",
                            kind = ItemKind.COMMAND_EXEC,
                            status = ItemStatus.SUCCESS,
                            command = "rg timeline",
                            text = "2 matches",
                        ),
                    ),
                    ProviderEvent.ItemUpdated(
                        ProviderItem(
                            id = "file-1",
                            kind = ItemKind.DIFF_APPLY,
                            status = ItemStatus.SUCCESS,
                            fileChanges = listOf(
                                ProviderFileChange(
                                    sourceScopedId = "change-1",
                                    path = "src/main/kotlin/Timeline.kt",
                                    kind = "updated",
                                    unifiedDiff = "@@ -1 +1 @@\n-old\n+new",
                                ),
                            ),
                        ),
                    ),
                    ProviderEvent.ItemUpdated(
                        ProviderItem(
                            id = "assistant-1",
                            kind = ItemKind.NARRATIVE,
                            status = ItemStatus.SUCCESS,
                            name = "message",
                            text = "Done.",
                        ),
                    ),
                    ProviderEvent.TurnCompleted(turnId = "turn-1", outcome = TurnOutcome.SUCCESS),
                ),
            ),
        )

        assertContains(markdown, "# Refine timeline")
        assertContains(markdown, "- Conversation ID: `thread-1`")
        assertContains(markdown, "## User")
        assertContains(markdown, "Please refine the timeline UI.")
        assertContains(markdown, "## Command")
        assertContains(markdown, "```sh\nrg timeline\n```")
        assertContains(markdown, "```text\n2 matches\n```")
        assertContains(markdown, "## File Change")
        assertContains(markdown, "Updated `Timeline.kt`")
        assertContains(markdown, "```diff\n@@ -1 +1 @@\n-old\n+new\n```")
        assertContains(markdown, "## Assistant")
        assertContains(markdown, "Done.")
    }

    @Test
    fun `formats web search tool call in export with codex style heading`() {
        val markdown = formatConversationExportMarkdown(
            summary = ConversationSummary(
                remoteConversationId = "thread-2",
                title = "Web search",
                createdAt = 1L,
                updatedAt = 1_710_000_000L,
                status = "idle",
            ),
            events = com.auracode.assistant.test.mapProviderEvents(
                listOf(
                    ProviderEvent.ItemUpdated(
                        ProviderItem(
                            id = "tool-web-1",
                            kind = ItemKind.TOOL_CALL,
                            status = ItemStatus.SUCCESS,
                            name = "web_search",
                            text = "OpenAI Codex latest version official\nsite:github.com/openai/codex/releases latest codex release github",
                        ),
                    ),
                ),
            ),
        )

        assertContains(markdown, "## Searched")
        assertContains(markdown, "OpenAI Codex latest version official")
        assertContains(markdown, "site:github.com/openai/codex/releases latest codex release github")
    }

    @Test
    fun `formats mcp tool call in export with mcp specific heading`() {
        val markdown = formatConversationExportMarkdown(
            summary = ConversationSummary(
                remoteConversationId = "thread-3",
                title = "MCP tool",
                createdAt = 1L,
                updatedAt = 1_710_000_000L,
                status = "idle",
            ),
            events = com.auracode.assistant.test.mapProviderEvents(
                listOf(
                    ProviderEvent.ItemUpdated(
                        ProviderItem(
                            id = "tool-mcp-1",
                            kind = ItemKind.TOOL_CALL,
                            status = ItemStatus.SUCCESS,
                            name = "mcp:cloudview-gray",
                            text = """
                                - Server: `cloudview-gray`
                                - Tool: `get_figma_node`
                                
                                **Result**
                                
                                ```json
                                {"name":"多窗口"}
                                ```
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )

        assertContains(markdown, "## Call MCP · cloudview-gray · get_figma_node")
        assertContains(markdown, "- Tool: `get_figma_node`")
        assertContains(markdown, "{\"name\":\"多窗口\"}")
    }

    @Test
    fun `suggests stable markdown export filename`() {
        assertEquals(
            "Refine-timeline-UI.md",
            suggestConversationExportFileName(
                title = "  Refine / timeline:UI  ",
                remoteConversationId = "thread-1",
            ),
        )
        assertEquals(
            "thread-1.md",
            suggestConversationExportFileName(
                title = " <> ",
                remoteConversationId = "thread-1",
            ),
        )
    }
}
