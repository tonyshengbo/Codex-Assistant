package com.auracode.assistant.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexUnifiedItemLifecycleParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val parser = CodexUnifiedItemLifecycleParser(CodexUnifiedItemTypeParsers())
    private val state = CodexUnifiedParserState()

    @Test
    fun canonicalizesAliasesAndUpdatesState() {
        val event = parser.parseItemStarted(
            params(
                """{"item":{"type":"web_search","id":"ws_1","query":"compose","action":{"type":"search","query":"compose","queries":["compose"]}},"threadId":"thread-1","turnId":"turn-1"}""",
            ),
            state = state,
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals(ItemKind.TOOL_CALL, item.kind)
        assertEquals("web_search", item.name)
        assertEquals("compose", item.text)
        assertEquals("thread-1", state.activeThreadId)
        assertEquals("turn-1", state.activeTurnId)
    }

    @Test
    fun preservesPreviousFileChangesWhenCompletedPayloadOmitsChanges() {
        parser.parseItemStarted(
            params(
                """{"item":{"type":"fileChange","id":"fc_1","status":"inProgress","changes":[{"path":"/tmp/a.kt","kind":{"type":"update","move_path":null},"old_content":"a\n","new_content":"b\n"}]},"threadId":"thread-1","turnId":"turn-1"}""",
            ),
            state = state,
        )

        val event = parser.parseItemCompleted(
            params(
                """{"item":{"type":"fileChange","id":"fc_1","status":"completed"},"threadId":"thread-1","turnId":"turn-1"}""",
            ),
            state = state,
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals(1, item.fileChanges.size)
        assertEquals("/tmp/a.kt", item.fileChanges.first().path)
    }

    @Test
    fun recordsContextCompactionByTurnId() {
        val event = parser.parseItemStarted(
            params(
                """{"item":{"type":"contextCompaction","id":"ctx_1"},"threadId":"thread-1","turnId":"turn-ctx"}""",
            ),
            state = state,
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals(ItemKind.CONTEXT_COMPACTION, item.kind)
        assertEquals("ctx_1", state.contextCompactionItemIdsByTurnId["turn-ctx"])
    }

    @Test
    fun parsesUserMessageAndUnknownFallback() {
        val userMessage = parser.parseItemCompleted(
            params(
                """{"item":{"type":"userMessage","id":"msg_u","text":"continue"},"threadId":"thread-1","turnId":"turn-1"}""",
            ),
            state = state,
        )
        val fallback = parser.parseItemCompleted(
            params(
                """{"item":{"type":"customToolCall","id":"tool_1","name":"custom"},"threadId":"thread-1","turnId":"turn-1"}""",
            ),
            state = state,
        )

        val userItem = assertIs<UnifiedEvent.ItemUpdated>(userMessage).item
        assertEquals(ItemKind.NARRATIVE, userItem.kind)
        assertEquals("user_message", userItem.name)

        val fallbackItem = assertIs<UnifiedEvent.ItemUpdated>(fallback).item
        assertEquals(ItemKind.TOOL_CALL, fallbackItem.kind)
        assertEquals("custom", fallbackItem.name)
    }

    @Test
    fun parsesMcpToolCallWithDedicatedBranch() {
        val started = parser.parseItemStarted(
            params(
                """{"item":{"type":"mcpToolCall","id":"call_mcp_1","server":"cloudview-gray","tool":"get_figma_node","status":"inProgress","arguments":{"fileKey":"kUYVH0Cp30Bt1KyItM2JtO","nodeId":"12:182","depth":2},"result":null,"error":null},"threadId":"thread-1","turnId":"turn-1"}""",
            ),
            state = state,
        )
        val completed = parser.parseItemCompleted(
            params(
                """{"item":{"type":"mcpToolCall","id":"call_mcp_1","server":"cloudview-gray","tool":"get_figma_node","status":"completed","arguments":{"fileKey":"kUYVH0Cp30Bt1KyItM2JtO","nodeId":"12:182","depth":2},"result":{"content":[{"type":"text","text":"{\"name\":\"多窗口\"}"}]},"error":null},"threadId":"thread-1","turnId":"turn-1"}""",
            ),
            state = state,
        )

        val startedItem = assertIs<UnifiedEvent.ItemUpdated>(started).item
        assertEquals(ItemKind.TOOL_CALL, startedItem.kind)
        assertEquals("mcp:cloudview-gray", startedItem.name)
        assertTrue(startedItem.text.orEmpty().contains("- Tool: `get_figma_node`"))
        assertTrue(startedItem.text.orEmpty().contains("**Arguments**"))

        val completedItem = assertIs<UnifiedEvent.ItemUpdated>(completed).item
        assertEquals(ItemKind.TOOL_CALL, completedItem.kind)
        assertEquals("mcp:cloudview-gray", completedItem.name)
        assertTrue(completedItem.text.orEmpty().contains("**Result**"))
        assertTrue(completedItem.text.orEmpty().contains("{\"name\":\"多窗口\"}"))
    }

    @Test
    fun returnsNullWhenItemOrTypeIsMissing() {
        assertNull(parser.parseItemStarted(params("""{}"""), state))
        assertNull(parser.parseItemStarted(params("""{"item":{"id":"missing_type"}}"""), state))
    }

    @Test
    fun canonicalTypeRejectsBlankAndHandlesAliases() {
        assertNull(CodexUnifiedItemTypeAliases.canonicalType(null))
        assertNull(CodexUnifiedItemTypeAliases.canonicalType("  "))
        assertEquals(CodexUnifiedItemTypeAliases.AGENT_MESSAGE, CodexUnifiedItemTypeAliases.canonicalType("agent-message"))
        assertEquals(CodexUnifiedItemTypeAliases.FILE_CHANGE, CodexUnifiedItemTypeAliases.canonicalType("fileChange"))
        assertEquals(CodexUnifiedItemTypeAliases.MCP_TOOL_CALL, CodexUnifiedItemTypeAliases.canonicalType("mcpToolCall"))
        assertEquals(CodexUnifiedItemTypeAliases.USER_MESSAGE, CodexUnifiedItemTypeAliases.canonicalType("user_message"))
    }

    private fun params(raw: String): JsonObject {
        return json.parseToJsonElement(raw).jsonObject
    }
}
