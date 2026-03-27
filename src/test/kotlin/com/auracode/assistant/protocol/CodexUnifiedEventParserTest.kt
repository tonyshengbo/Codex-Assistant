package com.auracode.assistant.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CodexUnifiedEventParserTest {
    @Test
    fun `parses thread started event`() {
        val event = CodexUnifiedEventParser.parseLine(
            """{"type":"thread.started","thread_id":"th_123"}""",
        )

        val started = assertIs<UnifiedEvent.ThreadStarted>(event)
        assertEquals("th_123", started.threadId)
    }

    @Test
    fun `parses turn completed usage and success status`() {
        val event = CodexUnifiedEventParser.parseLine(
            """{"type":"turn.completed","turn_id":"tu_1","usage":{"input_tokens":10,"cached_input_tokens":3,"output_tokens":7}}""",
        )

        val completed = assertIs<UnifiedEvent.TurnCompleted>(event)
        assertEquals("tu_1", completed.turnId)
        assertEquals(TurnOutcome.SUCCESS, completed.outcome)
        assertEquals(10, completed.usage?.inputTokens)
        assertEquals(3, completed.usage?.cachedInputTokens)
        assertEquals(7, completed.usage?.outputTokens)
    }

    @Test
    fun `parses approval request item as pending`() {
        val event = CodexUnifiedEventParser.parseLine(
            """{"type":"item.started","item":{"id":"it_10","type":"approval_request","target_type":"command","payload":{"command":"./gradlew test","cwd":"."},"decision":"pending"}}""",
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("it_10", item.id)
        assertEquals(ItemKind.APPROVAL_REQUEST, item.kind)
        assertEquals(ItemStatus.RUNNING, item.status)
        assertEquals(ApprovalDecision.PENDING, item.approvalDecision)
    }

    @Test
    fun `parses plan update item text`() {
        val event = CodexUnifiedEventParser.parseLine(
            """{"type":"item.started","item":{"id":"it_3","type":"plan_update","text":"Create protocol layer before UI migration"}}""",
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("it_3", item.id)
        assertEquals(ItemKind.PLAN_UPDATE, item.kind)
        assertEquals("Create protocol layer before UI migration", item.text)
    }

    @Test
    fun `parses file change item with change list into diff apply summary`() {
        val event = CodexUnifiedEventParser.parseLine(
            """{"type":"item.completed","item":{"id":"it_5","type":"file_change","changes":[{"path":"/tmp/hello.java","kind":"update","old_content":"a\nb\nc","new_content":"a\nb2\nc\nd"},{"path":"/tmp/Util.kt","kind":"create"}]}}""",
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("it_5", item.id)
        assertEquals(ItemKind.DIFF_APPLY, item.kind)
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertEquals("File Changes (2)", item.name)
        assertEquals("update /tmp/hello.java\ncreate /tmp/Util.kt", item.text)
        assertEquals("it_5:0", item.fileChanges.first().sourceScopedId)
        assertEquals(2, item.fileChanges.first().addedLines)
        assertEquals(1, item.fileChanges.first().deletedLines)
    }

    @Test
    fun `parses turn diff updated event`() {
        val event = CodexUnifiedEventParser.parseLine(
            """{"type":"turn.diff.updated","thread_id":"thread-1","turn_id":"turn-1","diff":"diff --git a/foo b/foo"}""",
        )

        val updated = assertIs<UnifiedEvent.TurnDiffUpdated>(event)
        assertEquals("thread-1", updated.threadId)
        assertEquals("turn-1", updated.turnId)
        assertEquals("diff --git a/foo b/foo", updated.diff)
    }

    @Test
    fun `parses context compaction item into dedicated compaction activity`() {
        val event = CodexUnifiedEventParser.parseLine(
            """{"method":"item/started","params":{"item":{"type":"contextCompaction","id":"28a1b402-fd03-4921-b416-641c720ac400"},"threadId":"019d2843-2387-7243-9192-1d1bed702252","turnId":"019d28c1-46a5-7e82-9ffd-d451f0542031"}}""",
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("28a1b402-fd03-4921-b416-641c720ac400", item.id)
        assertEquals(ItemKind.CONTEXT_COMPACTION, item.kind)
        assertEquals(ItemStatus.RUNNING, item.status)
        assertNull(item.name)
        assertNull(item.text)
    }

    @Test
    fun `parses webSearch item as tool call for app server method payload`() {
        val event = CodexUnifiedEventParser.parseLine(
            """{"method":"item/completed","params":{"item":{"type":"webSearch","id":"ws_123","query":"kotlin compose ime","action":{"type":"search"}}}}""",
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("ws_123", item.id)
        assertEquals(ItemKind.TOOL_CALL, item.kind)
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertNull(item.name)
        assertEquals("kotlin compose ime", item.text)
    }

    @Test
    fun `parses web_search item as tool call for legacy type payload`() {
        val event = CodexUnifiedEventParser.parseLine(
            """{"type":"item.started","item":{"type":"web_search","id":"ws_legacy","input":"query: kotlin coroutines"}}""",
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("ws_legacy", item.id)
        assertEquals(ItemKind.TOOL_CALL, item.kind)
        assertEquals(ItemStatus.RUNNING, item.status)
        assertNull(item.name)
        assertEquals("query: kotlin coroutines", item.text)
    }

    @Test
    fun `preserves unknown item type as name for easier diagnostics`() {
        val event = CodexUnifiedEventParser.parseLine(
            """{"type":"item.started","item":{"id":"it_unknown","type":"fooBarBaz","status":"running"}}""",
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals(ItemKind.UNKNOWN, item.kind)
        assertEquals("fooBarBaz", item.name)
        assertNull(item.text)
    }

    @Test
    fun `returns null for unsupported content`() {
        assertNull(CodexUnifiedEventParser.parseLine("WARNING: partial status"))
    }
}
