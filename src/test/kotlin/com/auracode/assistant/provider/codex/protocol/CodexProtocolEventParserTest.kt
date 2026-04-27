package com.auracode.assistant.provider.codex.protocol

import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderRunningPlanPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexProviderEventParserTest {
    @Test
    fun parsesThreadStartedEventFromMethodPayload() {
        val event = CodexProviderEventParser.parseLine(
            """{"method":"thread/started","params":{"thread":{"id":"019d4d2d-6a0a-7850-b4df-80af9d8a14d5"}}}""",
        )

        val started = assertIs<ProviderEvent.ThreadStarted>(event)
        assertEquals("019d4d2d-6a0a-7850-b4df-80af9d8a14d5", started.threadId)
    }

    @Test
    fun parsesTurnStartedAndCompletedEvents() {
        val started = CodexProviderEventParser.parseLine(
            """{"method":"turn/started","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"inProgress"}}}""",
        )
        val completed = CodexProviderEventParser.parseLine(
            """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"completed"},"usage":{"inputTokens":10,"cachedInputTokens":3,"outputTokens":7}}}""",
        )

        val startedEvent = assertIs<ProviderEvent.TurnStarted>(started)
        assertEquals("turn-1", startedEvent.turnId)
        assertEquals("thread-1", startedEvent.threadId)

        val completedEvent = assertIs<ProviderEvent.TurnCompleted>(completed)
        assertEquals("turn-1", completedEvent.turnId)
        assertEquals(TurnOutcome.SUCCESS, completedEvent.outcome)
        assertEquals(10, completedEvent.usage?.inputTokens)
        assertEquals(3, completedEvent.usage?.cachedInputTokens)
        assertEquals(7, completedEvent.usage?.outputTokens)
    }

    @Test
    fun parsesThreadTokenUsageUpdatedEvent() {
        val event = CodexProviderEventParser.parseLine(
            """{"method":"thread/tokenUsage/updated","params":{"threadId":"thread-1","turnId":"turn-1","tokenUsage":{"total":{"inputTokens":100,"cachedInputTokens":60,"outputTokens":40},"modelContextWindow":258400}}}""",
        )

        val updated = assertIs<ProviderEvent.ThreadTokenUsageUpdated>(event)
        assertEquals("thread-1", updated.threadId)
        assertEquals("turn-1", updated.turnId)
        assertEquals(258400, updated.contextWindow)
        assertEquals(100, updated.inputTokens)
        assertEquals(60, updated.cachedInputTokens)
        assertEquals(40, updated.outputTokens)
    }

    @Test
    fun parsesTurnDiffUpdatedEvent() {
        val event = CodexProviderEventParser.parseLine(
            """{"method":"turn/diff/updated","params":{"threadId":"thread-1","turnId":"turn-1","diff":"diff --git a/foo b/foo"}}""",
        )

        val updated = assertIs<ProviderEvent.TurnDiffUpdated>(event)
        assertEquals("thread-1", updated.threadId)
        assertEquals("turn-1", updated.turnId)
        assertEquals("diff --git a/foo b/foo", updated.diff)
    }

    @Test
    fun parsesTurnPlanUpdatedEvent() {
        val event = CodexProviderEventParser.parseLine(
            """{"method":"turn/plan/updated","params":{"threadId":"thread-1","turnId":"turn-1","explanation":"Follow the parser refactor","plan":[{"step":"Split routers","status":"in_progress"},{"step":"Add tests","status":"pending"}]}}""",
        )

        val updated = assertIs<ProviderEvent.RunningPlanUpdated>(event)
        assertEquals("thread-1", updated.threadId)
        assertEquals("turn-1", updated.turnId)
        assertEquals("Follow the parser refactor", updated.explanation)
        assertEquals(2, updated.steps.size)
        assertTrue(updated.body.contains("Split routers"))
        assertEquals(ProviderRunningPlanPresentation.SUBMISSION_PANEL, updated.presentation)
    }

    @Test
    fun parsesItemLifecycleForWebSearchAndCommandExecution() {
        val started = CodexProviderEventParser.parseLine(
            """{"method":"item/started","params":{"item":{"type":"webSearch","id":"ws_123","query":"","action":{"type":"other"}},"threadId":"thread-1","turnId":"turn-1"}}""",
        )
        val completed = CodexProviderEventParser.parseLine(
            """{"method":"item/completed","params":{"item":{"type":"commandExecution","id":"call_123","command":"echo hi","cwd":"/tmp","status":"completed","aggregatedOutput":"hi\n","exitCode":0},"threadId":"thread-1","turnId":"turn-1"}}""",
        )

        val webSearchItem = assertIs<ProviderEvent.ItemUpdated>(started).item
        assertEquals(ItemKind.TOOL_CALL, webSearchItem.kind)
        assertEquals(ItemStatus.RUNNING, webSearchItem.status)
        assertEquals("web_search", webSearchItem.name)

        val commandItem = assertIs<ProviderEvent.ItemUpdated>(completed).item
        assertEquals(ItemKind.COMMAND_EXEC, commandItem.kind)
        assertEquals(ItemStatus.SUCCESS, commandItem.status)
        assertEquals("hi\n", commandItem.text)
        assertEquals(0, commandItem.exitCode)
    }

    @Test
    fun parsesItemTypeAliasesAndUserMessage() {
        val aliasedWebSearch = CodexProviderEventParser.parseLine(
            """{"method":"item/started","params":{"item":{"type":"web_search","id":"ws_alias","query":"compose","action":{"type":"search","query":"compose","queries":["compose"]}},"threadId":"thread-1","turnId":"turn-1"}}""",
        )
        val userMessage = CodexProviderEventParser.parseLine(
            """{"method":"item/completed","params":{"item":{"type":"userMessage","id":"msg_user","text":"Please continue"},"threadId":"thread-1","turnId":"turn-1"}}""",
        )

        val webSearchItem = assertIs<ProviderEvent.ItemUpdated>(aliasedWebSearch).item
        assertEquals(ItemKind.TOOL_CALL, webSearchItem.kind)
        assertEquals("web_search", webSearchItem.name)
        assertEquals("compose", webSearchItem.text)

        val userMessageItem = assertIs<ProviderEvent.ItemUpdated>(userMessage).item
        assertEquals(ItemKind.NARRATIVE, userMessageItem.kind)
        assertEquals(ItemStatus.SUCCESS, userMessageItem.status)
        assertEquals("user_message", userMessageItem.name)
        assertEquals("Please continue", userMessageItem.text)
    }

    @Test
    fun parsesMcpToolCallLifecycle() {
        val started = CodexProviderEventParser.parseLine(
            """{"method":"item/started","params":{"item":{"type":"mcpToolCall","id":"call_mcp_1","server":"cloudview-gray","tool":"get_figma_node","status":"inProgress","arguments":{"fileKey":"kUYVH0Cp30Bt1KyItM2JtO","nodeId":"12:182","depth":2},"result":null,"error":null},"threadId":"thread-1","turnId":"turn-1"}}""",
        )
        val completed = CodexProviderEventParser.parseLine(
            """{"method":"item/completed","params":{"item":{"type":"mcpToolCall","id":"call_mcp_1","server":"cloudview-gray","tool":"get_figma_node","status":"completed","arguments":{"fileKey":"kUYVH0Cp30Bt1KyItM2JtO","nodeId":"12:182","depth":2},"result":{"content":[{"type":"text","text":"{\"name\":\"多窗口\"}"}]},"error":null},"threadId":"thread-1","turnId":"turn-1"}}""",
        )

        val startedItem = assertIs<ProviderEvent.ItemUpdated>(started).item
        assertEquals(ItemKind.TOOL_CALL, startedItem.kind)
        assertEquals(ItemStatus.RUNNING, startedItem.status)
        assertEquals("mcp:cloudview-gray", startedItem.name)

        val completedItem = assertIs<ProviderEvent.ItemUpdated>(completed).item
        assertEquals(ItemKind.TOOL_CALL, completedItem.kind)
        assertEquals(ItemStatus.SUCCESS, completedItem.status)
        assertEquals("mcp:cloudview-gray", completedItem.name)
        assertTrue(completedItem.text.orEmpty().contains("{\"name\":\"多窗口\"}"))
    }

    @Test
    fun parsesFileChangeLifecycleWithDiffSummary() {
        val event = CodexProviderEventParser.parseLine(
            """{"method":"item/completed","params":{"item":{"type":"fileChange","id":"call_fc","status":"completed","changes":[{"path":"/tmp/a.kt","kind":{"type":"update","move_path":null},"old_content":"a\nb\n","new_content":"a\nb2\n"},{"path":"/tmp/b.kt","kind":{"type":"create","move_path":null}}]},"threadId":"thread-1","turnId":"turn-1"}}""",
        )

        val item = assertIs<ProviderEvent.ItemUpdated>(event).item
        assertEquals(ItemKind.DIFF_APPLY, item.kind)
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertEquals(2, item.fileChanges.size)
        assertEquals("update /tmp/a.kt\ncreate /tmp/b.kt", item.text)
    }

    @Test
    fun parsesContextCompactionLifecycleAndCompactedSignal() {
        CodexProviderEventParser.parseLine(
            """{"method":"item/started","params":{"item":{"type":"contextCompaction","id":"ctx_1"},"threadId":"thread-1","turnId":"turn-1"}}""",
        )

        val compacted = CodexProviderEventParser.parseLine(
            """{"method":"thread/compacted","params":{"threadId":"thread-1","turnId":"turn-1"}}""",
        )

        val item = assertIs<ProviderEvent.ItemUpdated>(compacted).item
        assertEquals(ItemKind.CONTEXT_COMPACTION, item.kind)
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertEquals("Context compacted", item.text)
    }

    @Test
    fun parsesNarrativeAndActivityDeltasWithAccumulation() {
        CodexProviderEventParser.parseLine(
            """{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"msg_1","delta":"Hel"}}""",
        )
        val messageEvent = CodexProviderEventParser.parseLine(
            """{"method":"item/agentMessage/delta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"msg_1","delta":"lo"}}""",
        )

        CodexProviderEventParser.parseLine(
            """{"method":"item/commandExecution/outputDelta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"call_1","delta":"line1"}}""",
        )
        val outputEvent = CodexProviderEventParser.parseLine(
            """{"method":"item/commandExecution/outputDelta","params":{"threadId":"thread-1","turnId":"turn-1","itemId":"call_1","delta":"\nline2"}}""",
        )

        val messageItem = assertIs<ProviderEvent.ItemUpdated>(messageEvent).item
        assertEquals(ItemKind.NARRATIVE, messageItem.kind)
        assertEquals("Hello", messageItem.text)

        val outputItem = assertIs<ProviderEvent.ItemUpdated>(outputEvent).item
        assertEquals(ItemKind.COMMAND_EXEC, outputItem.kind)
        assertEquals("line1\nline2", outputItem.text)
    }

    @Test
    fun parsesPlanDeltaWithAccumulation() {
        CodexProviderEventParser.parseLine(
            """{"method":"item/plan/delta","params":{"threadId":"thread-1","turnId":"turn-plan","itemId":"turn-plan-plan","delta":"#"}}""",
        )
        val event = CodexProviderEventParser.parseLine(
            """{"method":"item/plan/delta","params":{"threadId":"thread-1","turnId":"turn-plan","itemId":"turn-plan-plan","delta":" Plan"}}""",
        )

        val item = assertIs<ProviderEvent.ItemUpdated>(event).item
        assertEquals(ItemKind.PLAN_UPDATE, item.kind)
        assertEquals("# Plan", item.text)
    }

    @Test
    fun parsesErrorEventAndRetrySemantics() {
        val retryable = CodexProviderEventParser.parseLine(
            """{"method":"error","params":{"error":{"message":"Transport disconnected"},"willRetry":true}}""",
        )
        val terminal = CodexProviderEventParser.parseLine(
            """{"method":"error","params":{"message":"Turn failed because command exited with 1"}}""",
        )

        val retryableError = assertIs<ProviderEvent.Error>(retryable)
        assertEquals("Transport disconnected", retryableError.message)
        assertEquals(false, retryableError.terminal)

        val terminalError = assertIs<ProviderEvent.Error>(terminal)
        assertEquals("Turn failed because command exited with 1", terminalError.message)
        assertEquals(true, terminalError.terminal)
    }

    @Test
    fun returnsNullForOldTypeFormatAndInvalidContent() {
        assertNull(CodexProviderEventParser.parseLine("""{"type":"thread.started","thread_id":"th_123"}"""))
        assertNull(CodexProviderEventParser.parseLine("""{"method":"item/started","params":{"item":{"id":"missing_type"}}}"""))
        assertNull(CodexProviderEventParser.parseLine("WARNING: partial status"))
    }
}
