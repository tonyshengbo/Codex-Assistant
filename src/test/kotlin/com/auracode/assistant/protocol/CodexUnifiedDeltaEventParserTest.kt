package com.auracode.assistant.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CodexUnifiedDeltaEventParserTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val parser = CodexUnifiedDeltaEventParser()
    private val state = CodexUnifiedParserState()

    @Test
    fun accumulatesNarrativeAndReasoningDeltas() {
        parser.parseAgentMessageDelta(params("""{"threadId":"thread-1","turnId":"turn-1","itemId":"msg_1","delta":"Hel"}"""), state)
        val message = parser.parseAgentMessageDelta(
            params("""{"threadId":"thread-1","turnId":"turn-1","itemId":"msg_1","delta":"lo"}"""),
            state,
        )
        parser.parseReasoningDelta(params("""{"threadId":"thread-1","turnId":"turn-1","itemId":"rs_1","delta":"why"}"""), state)
        val reasoning = parser.parseReasoningDelta(
            params("""{"threadId":"thread-1","turnId":"turn-1","itemId":"rs_1","delta":" now"}"""),
            state,
        )

        val messageItem = assertIs<UnifiedEvent.ItemUpdated>(message).item
        assertEquals("Hello", messageItem.text)

        val reasoningItem = assertIs<UnifiedEvent.ItemUpdated>(reasoning).item
        assertEquals("why now", reasoningItem.text)
    }

    @Test
    fun accumulatesActivityDeltaAndInheritsSnapshotFields() {
        state.itemSnapshots["call_1"] = UnifiedItem(
            id = "call_1",
            kind = ItemKind.COMMAND_EXEC,
            status = ItemStatus.RUNNING,
            name = "echo hi",
            command = "echo hi",
            cwd = "/tmp",
        )

        parser.parseCommandExecutionOutputDelta(
            params("""{"threadId":"thread-1","turnId":"turn-1","itemId":"call_1","delta":"line1"}"""),
            state,
        )
        val event = parser.parseCommandExecutionOutputDelta(
            params("""{"threadId":"thread-1","turnId":"turn-1","itemId":"call_1","delta":"\nline2"}"""),
            state,
        )

        val item = assertIs<UnifiedEvent.ItemUpdated>(event).item
        assertEquals("line1\nline2", item.text)
        assertEquals("echo hi", item.command)
        assertEquals("/tmp", item.cwd)
    }

    @Test
    fun accumulatesFileChangeAndPlanDeltaFromExistingState() {
        state.itemSnapshots["fc_1"] = UnifiedItem(
            id = "fc_1",
            kind = ItemKind.DIFF_APPLY,
            status = ItemStatus.RUNNING,
            name = "File Changes",
            fileChanges = listOf(
                UnifiedFileChange(
                    sourceScopedId = "fc_1:0",
                    path = "/tmp/a.kt",
                    kind = "update",
                ),
            ),
        )
        val fileChange = parser.parseFileChangeOutputDelta(
            params("""{"threadId":"thread-1","turnId":"turn-1","itemId":"fc_1","delta":"updated /tmp/a.kt"}"""),
            state,
        )

        state.itemSnapshots["plan:turn-plan"] = UnifiedItem(
            id = "plan:turn-plan",
            kind = ItemKind.PLAN_UPDATE,
            status = ItemStatus.RUNNING,
            text = "Existing",
        )
        val plan = parser.parsePlanDelta(
            params("""{"threadId":"thread-1","turnId":"turn-plan","itemId":"ignored","delta":" text"}"""),
            state,
        )

        val fileChangeItem = assertIs<UnifiedEvent.ItemUpdated>(fileChange).item
        assertEquals(1, fileChangeItem.fileChanges.size)
        assertEquals("/tmp/a.kt", fileChangeItem.fileChanges.first().path)

        val planItem = assertIs<UnifiedEvent.ItemUpdated>(plan).item
        assertEquals("Existing text", planItem.text)
    }

    @Test
    fun returnsNullWhenDeltaPayloadIsIncomplete() {
        assertNull(parser.parseAgentMessageDelta(params("""{"threadId":"thread-1","turnId":"turn-1","itemId":"msg_1"}"""), state))
        assertNull(parser.parseCommandExecutionOutputDelta(params("""{"threadId":"thread-1","turnId":"turn-1","delta":"line"}"""), state))
        assertNull(parser.parsePlanDelta(params("""{"threadId":"thread-1","turnId":"turn-1","itemId":"plan_1"}"""), state))
    }

    private fun params(raw: String): JsonObject {
        return json.parseToJsonElement(raw).jsonObject
    }
}
