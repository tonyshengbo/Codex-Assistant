package com.codex.assistant.protocol

import com.codex.assistant.model.EngineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EngineEventBridgeTest {
    private val requestId = "request-123"

    @Test
    fun `maps turn started engine event to unified turn started`() {
        val event = EngineEventBridge.map(
            EngineEvent.TurnStarted(
                turnId = "tu_live_1",
                threadId = "th_live_1",
            ),
            requestId = requestId,
        )

        val started = assertIs<UnifiedEvent.TurnStarted>(event)
        assertEquals("tu_live_1", started.turnId)
        assertEquals("th_live_1", started.threadId)
    }

    @Test
    fun `maps narrative item to unified narrative item`() {
        val event = EngineEventBridge.map(
            EngineEvent.NarrativeItem(
                itemId = "msg_2",
                text = "hello",
                isUser = true,
            ),
            requestId = requestId,
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("request-123:msg_2", item.id)
        assertEquals(ItemKind.NARRATIVE, item.kind)
        assertEquals("hello", item.text)
        assertEquals(ItemStatus.RUNNING, item.status)
        assertTrue(item.name?.contains("user", ignoreCase = true) == true)
    }

    @Test
    fun `maps assistant delta to a request scoped live narrative id`() {
        val event = EngineEventBridge.map(
            EngineEvent.AssistantTextDelta("partial"),
            requestId = requestId,
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("request-123:assistant-live", item.id)
        assertEquals(ItemKind.NARRATIVE, item.kind)
        assertEquals("partial", item.text)
        assertEquals(ItemStatus.RUNNING, item.status)
    }

    @Test
    fun `maps command proposal to command exec item`() {
        val event = EngineEventBridge.map(
            EngineEvent.CommandProposal(command = "./gradlew test", cwd = "."),
            requestId = requestId,
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals(ItemKind.COMMAND_EXEC, item.kind)
        assertEquals("./gradlew test", item.command)
        assertEquals(".", item.cwd)
        assertEquals(ItemStatus.RUNNING, item.status)
        assertEquals("request-123:cmd:${"./gradlew test".hashCode()}:${".".hashCode()}", item.id)
    }

    @Test
    fun `maps file changes to diff apply item with stable request scoped id and summary`() {
        val event = EngineEventBridge.map(
            EngineEvent.DiffProposal(
                itemId = "item_5",
                changes = listOf(
                    EngineEvent.FileChange(path = "/tmp/hello.java", kind = "update"),
                    EngineEvent.FileChange(path = "/tmp/Util.kt", kind = "create"),
                ),
            ),
            requestId = requestId,
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("request-123:item_5", item.id)
        assertEquals(ItemKind.DIFF_APPLY, item.kind)
        assertEquals(ItemStatus.RUNNING, item.status)
        assertEquals("File Changes (2)", item.name)
        assertTrue(item.text?.contains("update /tmp/hello.java") == true)
        assertTrue(item.text?.contains("create /tmp/Util.kt") == true)
    }

    @Test
    fun `maps turn usage to successful turn completion`() {
        val event = EngineEventBridge.map(
            EngineEvent.TurnUsage(
                inputTokens = 100,
                cachedInputTokens = 40,
                outputTokens = 20,
            ),
            requestId = requestId,
        )

        val completed = assertIs<UnifiedEvent.TurnCompleted>(event)
        assertEquals(TurnOutcome.SUCCESS, completed.outcome)
        assertEquals(100, completed.usage?.inputTokens)
        assertEquals(40, completed.usage?.cachedInputTokens)
        assertEquals(20, completed.usage?.outputTokens)
    }
}
