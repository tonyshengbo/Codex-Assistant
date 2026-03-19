package com.codex.assistant.protocol

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
            """{"type":"item.completed","item":{"id":"it_5","type":"file_change","changes":[{"path":"/tmp/hello.java","kind":"update"},{"path":"/tmp/Util.kt","kind":"create"}]}}""",
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("it_5", item.id)
        assertEquals(ItemKind.DIFF_APPLY, item.kind)
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertEquals("File Changes (2)", item.name)
        assertEquals("update /tmp/hello.java\ncreate /tmp/Util.kt", item.text)
    }

    @Test
    fun `returns null for unsupported content`() {
        assertNull(CodexUnifiedEventParser.parseLine("WARNING: partial status"))
    }
}
