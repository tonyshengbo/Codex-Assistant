package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.provider.diagnostics.ProviderDiagnosticFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Replays Claude stream fixtures to prevent parser and mapper regressions.
 */
class ClaudeStreamReplayTest {
    /** Verifies that the diagnostic fixture still replays tool, reasoning, narrative, and usage events. */
    @Test
    fun `replays recorded claude stream fixture into unified events`() {
        val parser = ClaudeStreamEventParser()
        val accumulator = ClaudeStreamAccumulator()
        val mapper = ClaudeProviderEventMapper(
            request = AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Replay stream",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        )

        val events = fixtureLines().flatMap { line ->
            parser.parse(line)
                ?.let(accumulator::accumulate)
                ?.flatMap(mapper::map)
                .orEmpty()
        }

        assertTrue(events.any { event ->
            event is ProviderEvent.ItemUpdated &&
                event.item.kind == ItemKind.COMMAND_EXEC &&
                event.item.status == com.auracode.assistant.protocol.ItemStatus.FAILED &&
                event.item.text?.contains("File does not exist") == true
        })
        assertTrue(events.any { event ->
            event is ProviderEvent.ItemUpdated &&
                event.item.kind == ItemKind.NARRATIVE &&
                event.item.name == "reasoning" &&
                event.item.text == "Inspecting repository structure"
        })
        assertTrue(events.any { event ->
            event is ProviderEvent.ItemUpdated &&
                event.item.kind == ItemKind.NARRATIVE &&
                event.item.name == "message" &&
                event.item.text == "工程已全部创建完毕。"
        })

        val finalUsage = events.filterIsInstance<ProviderEvent.ThreadTokenUsageUpdated>().last()
        assertEquals(200000, finalUsage.contextWindow)
        assertEquals(12125, finalUsage.outputTokens)

        val completed = assertIs<ProviderEvent.TurnCompleted>(events.last())
        assertEquals(TurnOutcome.SUCCESS, completed.outcome)
    }

    @Test
    /** Verifies that replay keeps assistant text around tools split into separate unified items. */
    fun `replay keeps assistant messages before and after tool in separate unified items`() {
        val parser = ClaudeStreamEventParser()
        val accumulator = ClaudeStreamAccumulator()
        val mapper = ClaudeProviderEventMapper(
            request = AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Replay stream with tool boundary",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        )

        val events = replayLines().flatMap { line ->
            parser.parse(line)
                ?.let(accumulator::accumulate)
                ?.flatMap(mapper::map)
                .orEmpty()
        }

        val itemUpdates = events.filterIsInstance<ProviderEvent.ItemUpdated>()
        val messageUpdates = itemUpdates.filter { event ->
            event.item.kind == ItemKind.NARRATIVE && event.item.name == "message"
        }
        val distinctMessageIds = messageUpdates.map { it.item.id }.distinct()

        assertEquals(2, distinctMessageIds.size)
        assertNotEquals(distinctMessageIds[0], distinctMessageIds[1])

        val firstMessageIndex = itemUpdates.indexOfFirst { event ->
            event.item.kind == ItemKind.NARRATIVE &&
                event.item.name == "message" &&
                event.item.text == "先说明一下。"
        }
        val toolIndex = itemUpdates.indexOfFirst { event ->
            event.item.kind == ItemKind.COMMAND_EXEC &&
                event.item.name == "Read"
        }
        val secondMessageIndex = itemUpdates.indexOfFirst { event ->
            event.item.kind == ItemKind.NARRATIVE &&
                event.item.name == "message" &&
                event.item.text == "再给最终答复。"
        }

        assertTrue(firstMessageIndex >= 0)
        assertTrue(toolIndex > firstMessageIndex)
        assertTrue(secondMessageIndex > toolIndex)
    }

    /** Verifies that Claude api_retry system lines surface as non-terminal unified errors. */
    @Test
    fun `replay maps api retry into non terminal unified error`() {
        val parser = ClaudeStreamEventParser()
        val accumulator = ClaudeStreamAccumulator()
        val mapper = ClaudeProviderEventMapper(
            request = AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Replay Claude retry stream",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        )

        val events = listOf(
            """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
            """{"type":"system","subtype":"api_retry","attempt":3,"max_retries":10,"retry_delay_ms":2436.076846771844,"error_status":null,"error":"unknown","session_id":"session-123","uuid":"retry-1"}""",
        ).flatMap { line ->
            parser.parse(line)
                ?.let(accumulator::accumulate)
                ?.flatMap(mapper::map)
                .orEmpty()
        }

        val error = assertIs<ProviderEvent.Error>(events.last())
        assertEquals(false, error.terminal)
        assertTrue(error.message.contains("3/10"))
        assertTrue(error.message.contains("2.4s"))
    }

    /** Loads the shared Claude diagnostic fixture through the classpath fixture loader. */
    private fun fixtureLines(): List<String> {
        return ProviderDiagnosticFixture
            .load("/provider/claude/claude-diagnostic-stream.jsonl")
            .lines
    }

    /** Returns a minimal replay sample with an assistant -> tool -> assistant boundary. */
    private fun replayLines(): List<String> {
        return listOf(
            """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
            """{"type":"stream_event","event":{"message":{"content":[],"id":"msg_before_tool","model":"claude-sonnet-4-6","role":"assistant","type":"message"},"type":"message_start"},"session_id":"session-123"}""",
            """{"type":"stream_event","event":{"content_block":{"text":"","type":"text"},"index":0,"type":"content_block_start"},"session_id":"session-123"}""",
            """{"type":"stream_event","event":{"delta":{"text":"先说明一下。","type":"text_delta"},"index":0,"type":"content_block_delta"},"session_id":"session-123"}""",
            """{"type":"stream_event","event":{"type":"message_stop"},"session_id":"session-123"}""",
            """{"type":"stream_event","event":{"message":{"content":[],"id":"msg_tool","model":"claude-sonnet-4-6","role":"assistant","type":"message"},"type":"message_start"},"session_id":"session-123"}""",
            """{"type":"stream_event","event":{"content_block":{"id":"tooluse_1","input":{},"name":"Read","type":"tool_use"},"index":0,"type":"content_block_start"},"session_id":"session-123"}""",
            """{"type":"stream_event","event":{"delta":{"partial_json":"{\"file_path\":\"/tmp/demo.txt\"}","type":"input_json_delta"},"index":0,"type":"content_block_delta"},"session_id":"session-123"}""",
            """{"type":"user","session_id":"session-123","message":{"role":"user","content":[{"type":"tool_result","content":"File does not exist","is_error":true,"tool_use_id":"tooluse_1"}]}}""",
            """{"type":"stream_event","event":{"type":"message_stop"},"session_id":"session-123"}""",
            """{"type":"stream_event","event":{"message":{"content":[],"id":"msg_after_tool","model":"claude-sonnet-4-6","role":"assistant","type":"message"},"type":"message_start"},"session_id":"session-123"}""",
            """{"type":"stream_event","event":{"content_block":{"text":"","type":"text"},"index":0,"type":"content_block_start"},"session_id":"session-123"}""",
            """{"type":"stream_event","event":{"delta":{"text":"再给最终答复。","type":"text_delta"},"index":0,"type":"content_block_delta"},"session_id":"session-123"}""",
            """{"type":"result","subtype":"success","session_id":"session-123","result":"再给最终答复。","is_error":false}""",
        )
    }
}
