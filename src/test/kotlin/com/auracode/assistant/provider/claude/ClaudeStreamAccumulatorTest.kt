package com.auracode.assistant.provider.claude

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 验证 Claude 原始流事件会被聚合为更接近 UI 语义的会话事件。
 */
class ClaudeStreamAccumulatorTest {
    /** 验证一段真实的 tool_use -> tool_result -> thinking -> text 流会被正确聚合。 */
    @Test
    fun `accumulates claude raw events into tool reasoning text and usage snapshots`() {
        val accumulator = ClaudeStreamAccumulator()

        val events = buildList {
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.SessionStarted(
                        sessionId = "session-123",
                        model = "claude-sonnet-4-6",
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.MessageStart(
                        sessionId = "session-123",
                        messageId = "msg_tool",
                        model = "claude-sonnet-4-6",
                        usage = ClaudeTokenUsage(
                            inputTokens = 32982,
                            cachedInputTokens = 0,
                            outputTokens = 0,
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockStarted(
                        sessionId = "session-123",
                        index = 0,
                        block = ClaudeContentBlockStart.ToolUse(
                            toolUseId = "tooluse_1",
                            name = "Read",
                            inputJson = "{}",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockDelta(
                        sessionId = "session-123",
                        index = 0,
                        delta = ClaudeContentDelta.InputJson(
                            partialJson = """{"file_path":"/tmp/demo.txt"}""",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.UserToolResult(
                        sessionId = "session-123",
                        toolUseId = "tooluse_1",
                        content = "File does not exist",
                        isError = true,
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.MessageStart(
                        sessionId = "session-123",
                        messageId = "msg_answer",
                        model = "claude-sonnet-4-6",
                        usage = ClaudeTokenUsage(
                            inputTokens = 33052,
                            cachedInputTokens = 0,
                            outputTokens = 0,
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockStarted(
                        sessionId = "session-123",
                        index = 0,
                        block = ClaudeContentBlockStart.Thinking(
                            thinking = "",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockDelta(
                        sessionId = "session-123",
                        index = 0,
                        delta = ClaudeContentDelta.Thinking(
                            thinking = "Inspecting repository structure",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockStopped(
                        sessionId = "session-123",
                        index = 0,
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockStarted(
                        sessionId = "session-123",
                        index = 1,
                        block = ClaudeContentBlockStart.Text(
                            text = "",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockDelta(
                        sessionId = "session-123",
                        index = 1,
                        delta = ClaudeContentDelta.Text(
                            text = "工程已全部创建完毕。",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.MessageDelta(
                        sessionId = "session-123",
                        stopReason = "end_turn",
                        usage = ClaudeTokenUsage(
                            inputTokens = 46300,
                            cachedInputTokens = 0,
                            outputTokens = 808,
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.Result(
                        sessionId = "session-123",
                        subtype = "success",
                        resultText = "工程已全部创建完毕。",
                        isError = false,
                        usage = ClaudeTokenUsage(
                            inputTokens = 539211,
                            cachedInputTokens = 0,
                            outputTokens = 12125,
                        ),
                        modelUsage = mapOf(
                            "claude-sonnet-4-6" to ClaudeModelUsage(
                                inputTokens = 539211,
                                outputTokens = 12125,
                                cachedInputTokens = 0,
                                contextWindow = 200000,
                                maxOutputTokens = 32000,
                            ),
                        ),
                    ),
                ),
            )
        }

        val sessionStarted = assertIs<ClaudeConversationEvent.SessionStarted>(events.first())
        assertEquals("session-123", sessionStarted.sessionId)

        val runningTool = events.filterIsInstance<ClaudeConversationEvent.ToolCallUpdated>().first()
        assertEquals("tooluse_1", runningTool.toolUseId)
        assertEquals("Read", runningTool.toolName)
        assertEquals("""{"file_path":"/tmp/demo.txt"}""", runningTool.inputJson)
        assertFalse(runningTool.completed)

        val finishedTool = events.filterIsInstance<ClaudeConversationEvent.ToolCallUpdated>().last()
        assertEquals("File does not exist", finishedTool.outputText)
        assertTrue(finishedTool.isError)
        assertTrue(finishedTool.completed)

        val reasoningEvents = events.filterIsInstance<ClaudeConversationEvent.ReasoningUpdated>()
        assertEquals(2, reasoningEvents.size)
        assertEquals("Inspecting repository structure", reasoningEvents.first().text)
        assertFalse(reasoningEvents.first().completed)
        assertTrue(reasoningEvents.last().completed)

        val assistantText = events.filterIsInstance<ClaudeConversationEvent.AssistantTextUpdated>().last()
        assertEquals("工程已全部创建完毕。", assistantText.text)
        assertFalse(assistantText.completed)

        val usageEvents = events.filterIsInstance<ClaudeConversationEvent.UsageUpdated>()
        assertEquals(2, usageEvents.size)
        assertEquals(808, usageEvents.first().outputTokens)
        assertEquals(12125, usageEvents.last().outputTokens)
        assertEquals(200000, usageEvents.last().contextWindow)

        val completed = assertIs<ClaudeConversationEvent.Completed>(events.last())
        assertEquals("工程已全部创建完毕。", completed.resultText)
        assertFalse(completed.isError)
    }

    @Test
    /** 验证同一条 Claude message 内多个 text block 会被聚合成完整正文快照，而不是彼此覆盖。 */
    fun `accumulates full assistant text snapshot across multiple text blocks in one message`() {
        val accumulator = ClaudeStreamAccumulator()

        val events = buildList {
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.MessageStart(
                        sessionId = "session-123",
                        messageId = "msg_multi_text",
                        model = "claude-sonnet-4-6",
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockStarted(
                        sessionId = "session-123",
                        index = 0,
                        block = ClaudeContentBlockStart.Text(
                            text = "第一段",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockStarted(
                        sessionId = "session-123",
                        index = 1,
                        block = ClaudeContentBlockStart.Text(
                            text = "第二段",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockDelta(
                        sessionId = "session-123",
                        index = 0,
                        delta = ClaudeContentDelta.Text(
                            text = "，补充A",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockDelta(
                        sessionId = "session-123",
                        index = 1,
                        delta = ClaudeContentDelta.Text(
                            text = "，补充B",
                        ),
                    ),
                ),
            )
        }

        val assistantTexts = events.filterIsInstance<ClaudeConversationEvent.AssistantTextUpdated>()
        assertEquals(
            listOf(
                "第一段",
                "第一段第二段",
                "第一段，补充A第二段",
                "第一段，补充A第二段，补充B",
            ),
            assistantTexts.map { it.text },
        )
        assertTrue(assistantTexts.all { it.messageId == "msg_multi_text" })
        assertTrue(assistantTexts.all { !it.completed })
    }

    @Test
    /** 验证 assistant snapshot 的 text 索引与流式 block 索引不一致时，不会把旧正文重复拼接进去。 */
    fun `assistant snapshot does not duplicate streamed text when snapshot reindexes content blocks`() {
        val accumulator = ClaudeStreamAccumulator()

        val events = buildList {
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.MessageStart(
                        sessionId = "session-123",
                        messageId = "msg_snapshot",
                        model = "claude-sonnet-4-6",
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockStarted(
                        sessionId = "session-123",
                        index = 1,
                        block = ClaudeContentBlockStart.Text(
                            text = "",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.ContentBlockDelta(
                        sessionId = "session-123",
                        index = 1,
                        delta = ClaudeContentDelta.Text(
                            text = "Now add the tests:",
                        ),
                    ),
                ),
            )
            addAll(
                accumulator.accumulate(
                    ClaudeStreamEvent.AssistantSnapshot(
                        sessionId = "session-123",
                        messageId = "msg_snapshot",
                        content = listOf(
                            ClaudeMessageContent.Text(
                                text = "Now add the tests:",
                            ),
                        ),
                    ),
                ),
            )
        }

        val assistantTexts = events.filterIsInstance<ClaudeConversationEvent.AssistantTextUpdated>()
        assertEquals(
            listOf("Now add the tests:"),
            assistantTexts.map { it.text }.distinct(),
        )
        assertEquals("Now add the tests:", assistantTexts.last().text)
    }
}
