package com.auracode.assistant.settings.mcp

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpServerDraftJsonTest {
    @Test
    fun `new draft defaults to mcp servers wrapper template`() {
        val draft = McpServerDraft()

        assertTrue(draft.configJson.contains("\"mcpServers\""))
        assertTrue(draft.configJson.contains("\"server-name\""))
        assertTrue(draft.configJson.contains("\"command\": \"\""))
    }

    @Test
    fun `mcp servers wrapper parses multiple server entries`() {
        val draft = McpServerDraft(
            configJson = """
                {
                  "mcpServers": {
                    "mastergo-magic-mcp": {
                      "command": "npx",
                      "args": ["-y", "@mastergo/magic-mcp"]
                    },
                    "context7": {
                      "url": "https://example.com/mcp"
                    }
                  }
                }
            """.trimIndent(),
        )

        val entries = draft.parseServerEntries().getOrThrow()
        assertEquals(2, entries.size)
        assertEquals("mastergo-magic-mcp", entries[0].name)
        assertEquals("npx", entries[0].config["command"]?.jsonPrimitive?.content)
        assertEquals("context7", entries[1].name)
        assertEquals("https://example.com/mcp", entries[1].config["url"]?.jsonPrimitive?.content)
    }

    @Test
    fun `draft rejects direct top level server map`() {
        val draft = McpServerDraft(
            configJson = """
                {
                  "docs": {
                    "url": "https://example.com/mcp"
                  },
                  "figma": {
                    "command": "npx"
                  }
                }
            """.trimIndent(),
        )

        assertEquals(
            "New servers must use {\"mcpServers\": {...}} JSON.",
            draft.validate().json,
        )
    }

    @Test
    fun `draft rejects multiple server entries for ui validation`() {
        val draft = McpServerDraft(
            configJson = """
                {
                  "mcpServers": {
                    "docs": {
                      "url": "https://example.com/mcp"
                    },
                    "figma": {
                      "command": "npx"
                    }
                  }
                }
            """.trimIndent(),
        )

        assertEquals(2, draft.parseServerEntries().getOrThrow().size)
        assertEquals("JSON must contain exactly one MCP server.", draft.validate().json)
    }

    @Test
    fun `new draft rejects anonymous config object`() {
        val draft = McpServerDraft(
            configJson = """
                {
                  "command": "npx",
                  "args": ["-y", "@acme/docs-mcp"]
                }
            """.trimIndent(),
        )

        assertEquals(
            "New servers must use {\"mcpServers\": {...}} JSON.",
            draft.validate().json,
        )
    }

    @Test
    fun `draft rejects blank server key`() {
        val draft = McpServerDraft(
            configJson = """
                {
                  "mcpServers": {
                    " ": { "command": "npx" }
                  }
                }
            """.trimIndent(),
        )

        assertEquals("Server name cannot be blank.", draft.validate().json)
    }

    @Test
    fun `draft validation points to specific invalid server`() {
        val draft = McpServerDraft(
            configJson = """
                {
                  "mcpServers": {
                    "docs": {
                      "command": "npx",
                      "url": "https://example.com/mcp"
                    }
                  }
                }
            """.trimIndent(),
        )

        assertEquals("Server 'docs': stdio config cannot include url.", draft.validate().json)
    }

    @Test
    fun `editor display json leaves invalid direct map unchanged`() {
        val draft = McpServerDraft(
            configJson = """
                {
                  "docs": {
                    "command": "npx"
                  }
                }
            """.trimIndent(),
        )

        val display = draft.editorDisplayJson()
        assertTrue(display.contains("\"docs\""))
        assertTrue(display.trimStart().startsWith("{\n  \"docs\""))
    }
}
