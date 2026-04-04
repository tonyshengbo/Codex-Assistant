package com.auracode.assistant.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexUnifiedMethodEventRouterTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesTurnCompletedOutcomeMappings() {
        val interrupted = parse(
            """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-1","status":"interrupted"}}}""",
        )
        val failed = parse(
            """{"method":"turn/completed","params":{"threadId":"thread-1","turn":{"id":"turn-2","status":"failed"}}}""",
        )

        val interruptedEvent = assertIs<UnifiedEvent.TurnCompleted>(interrupted)
        assertEquals(TurnOutcome.CANCELLED, interruptedEvent.outcome)

        val failedEvent = assertIs<UnifiedEvent.TurnCompleted>(failed)
        assertEquals(TurnOutcome.FAILED, failedEvent.outcome)
    }

    @Test
    fun parsesErrorRetrySemantics() {
        val retryable = parse(
            """{"method":"error","params":{"error":{"message":"retry later"},"willRetry":"true"}}""",
        )
        val terminal = parse(
            """{"method":"error","params":{"message":"fatal"}}""",
        )

        val retryableEvent = assertIs<UnifiedEvent.Error>(retryable)
        assertEquals("retry later", retryableEvent.message)
        assertEquals(false, retryableEvent.terminal)

        val terminalEvent = assertIs<UnifiedEvent.Error>(terminal)
        assertEquals("fatal", terminalEvent.message)
        assertEquals(true, terminalEvent.terminal)
    }

    @Test
    fun parsesTurnPlanUpdatedBody() {
        val event = parse(
            """{"method":"turn/plan/updated","params":{"threadId":"thread-1","turnId":"turn-1","explanation":"do work","plan":[{"step":"Split parser","status":"completed"},{"step":"Add tests","status":"in_progress"}]}}""",
        )

        val updated = assertIs<UnifiedEvent.RunningPlanUpdated>(event)
        assertEquals("thread-1", updated.threadId)
        assertEquals("turn-1", updated.turnId)
        assertTrue(updated.body.contains("do work"))
        assertTrue(updated.body.contains("Split parser"))
        assertTrue(updated.body.contains("Add tests"))
    }

    @Test
    fun returnsNullForUnsupportedOrInvalidMethodPayload() {
        assertNull(parse("""{"method":"thread/unknown","params":{}}"""))
        assertNull(parse("""{"method":"thread/started","params":{}}"""))
        assertNull(parse("""{"method":"turn/started","params":{"turn":{"status":"inProgress"}}}"""))
    }

    private fun parse(raw: String): UnifiedEvent? {
        val router = CodexUnifiedMethodEventRouter()
        val obj = json.parseToJsonElement(raw).jsonObject
        return router.parse(obj)
    }
}
