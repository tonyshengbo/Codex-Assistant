package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 使用真实日志形态的 JSONL 夹具回放 Claude 流协议，防止过程事件再次丢失。
 */
class ClaudeStreamReplayTest {
    /** 验证日志夹具能稳定回放出工具、reasoning、正文与最终 usage。 */
    @Test
    fun `replays recorded claude stream fixture into unified events`() {
        val parser = ClaudeStreamEventParser()
        val accumulator = ClaudeStreamAccumulator()
        val mapper = ClaudeUnifiedEventMapper(
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
            event is UnifiedEvent.ItemUpdated &&
                event.item.kind == ItemKind.COMMAND_EXEC &&
                event.item.status == com.auracode.assistant.protocol.ItemStatus.FAILED &&
                event.item.text?.contains("File does not exist") == true
        })
        assertTrue(events.any { event ->
            event is UnifiedEvent.ItemUpdated &&
                event.item.kind == ItemKind.NARRATIVE &&
                event.item.name == "reasoning" &&
                event.item.text == "Inspecting repository structure"
        })
        assertTrue(events.any { event ->
            event is UnifiedEvent.ItemUpdated &&
                event.item.kind == ItemKind.NARRATIVE &&
                event.item.name == "message" &&
                event.item.text == "工程已全部创建完毕。"
        })

        val finalUsage = events.filterIsInstance<UnifiedEvent.ThreadTokenUsageUpdated>().last()
        assertEquals(200000, finalUsage.contextWindow)
        assertEquals(12125, finalUsage.outputTokens)

        val completed = assertIs<UnifiedEvent.TurnCompleted>(events.last())
        assertEquals(TurnOutcome.SUCCESS, completed.outcome)
    }

    @Test
    /** 验证回放链路也会保留 tool 前后的 assistant message 边界，不会把两段文本压成一个 unified item。 */
    fun `replay keeps assistant messages before and after tool in separate unified items`() {
        val parser = ClaudeStreamEventParser()
        val accumulator = ClaudeStreamAccumulator()
        val mapper = ClaudeUnifiedEventMapper(
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

        val itemUpdates = events.filterIsInstance<UnifiedEvent.ItemUpdated>()
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

    /** 读取测试资源里的 Claude stream JSONL。 */
    private fun fixtureLines(): List<String> {
        return requireNotNull(javaClass.getResource("/claude/claude-stream-replay.jsonl")) {
            "Missing Claude replay fixture."
        }.readText().lineSequence().filter { it.isNotBlank() }.toList()
    }

    /** 返回一组带有 assistant -> tool -> assistant 边界的最小回放行。 */
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
