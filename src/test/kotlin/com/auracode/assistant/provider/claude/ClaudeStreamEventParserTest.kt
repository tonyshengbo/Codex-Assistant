package com.auracode.assistant.provider.claude

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 验证 Claude 原始 stream-json 行会被解析为结构化的事件模型。
 */
class ClaudeStreamEventParserTest {
    private val parser = ClaudeStreamEventParser()

    /** 验证 system/init 行会携带会话标识与模型。 */
    @Test
    fun `parses system init line into session event`() {
        val event = parser.parse(
            """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
        )

        val session = assertIs<ClaudeStreamEvent.SessionStarted>(event)
        assertEquals("session-123", session.sessionId)
        assertEquals("claude-sonnet-4-6", session.model)
    }

    /** 验证 system/api_retry 行会保留重试次数、最大次数与退避延迟。 */
    @Test
    fun `parses system api retry line into retry event`() {
        val event = parser.parse(
            """
            {"type":"system","subtype":"api_retry","attempt":3,"max_retries":10,"retry_delay_ms":2436.076846771844,"error_status":null,"error":"unknown","session_id":"session-123","uuid":"retry-1"}
            """.trimIndent(),
        )

        val retry = assertIs<ClaudeStreamEvent.ApiRetry>(event)
        assertEquals("session-123", retry.sessionId)
        assertEquals(3, retry.attempt)
        assertEquals(10, retry.maxRetries)
        assertEquals(2436L, retry.retryDelayMs)
        assertEquals("unknown", retry.error)
    }

    /** 验证 stream_event 的工具启动块会保留 tool_use 标识、名称与索引。 */
    @Test
    fun `parses stream event tool block start into structured raw event`() {
        val event = parser.parse(
            """
            {"type":"stream_event","event":{"content_block":{"id":"tooluse_1","input":{},"name":"Read","type":"tool_use"},"index":0,"type":"content_block_start"},"session_id":"session-123"}
            """.trimIndent(),
        )

        val started = assertIs<ClaudeStreamEvent.ContentBlockStarted>(event)
        assertEquals("session-123", started.sessionId)
        assertEquals(0, started.index)
        val block = assertIs<ClaudeContentBlockStart.ToolUse>(started.block)
        assertEquals("tooluse_1", block.toolUseId)
        assertEquals("Read", block.name)
        assertEquals("{}", block.inputJson)
    }

    /** 验证 stream_event 的输入 JSON 增量会被解析为独立 delta。 */
    @Test
    fun `parses input json delta into partial tool input payload`() {
        val event = parser.parse(
            """
            {"type":"stream_event","event":{"delta":{"partial_json":"{\"file_path\":\"/tmp/demo.txt\"}","type":"input_json_delta"},"index":0,"type":"content_block_delta"},"session_id":"session-123"}
            """.trimIndent(),
        )

        val delta = assertIs<ClaudeStreamEvent.ContentBlockDelta>(event)
        assertEquals("session-123", delta.sessionId)
        assertEquals(0, delta.index)
        val payload = assertIs<ClaudeContentDelta.InputJson>(delta.delta)
        assertEquals("""{"file_path":"/tmp/demo.txt"}""", payload.partialJson)
    }

    /** 验证 assistant 快照里的文本内容会保留为结构化 content 列表。 */
    @Test
    fun `parses assistant message content into structured content blocks`() {
        val event = parser.parse(
            """
            {"type":"assistant","session_id":"session-123","message":{"id":"msg_1","content":[{"type":"thinking","thinking":"Inspecting project"},{"type":"text","text":"Hello world"}]}}
            """.trimIndent(),
        )

        val assistant = assertIs<ClaudeStreamEvent.AssistantSnapshot>(event)
        assertEquals("session-123", assistant.sessionId)
        assertEquals("msg_1", assistant.messageId)
        assertEquals(2, assistant.content.size)
        assertIs<ClaudeMessageContent.Thinking>(assistant.content[0])
        val textBlock = assertIs<ClaudeMessageContent.Text>(assistant.content[1])
        assertEquals("Hello world", textBlock.text)
    }

    /** 验证 user 消息中的 tool_result 会被解析为单独的工具结果事件。 */
    @Test
    fun `parses user tool result into structured tool result event`() {
        val event = parser.parse(
            """
            {"type":"user","session_id":"session-123","message":{"role":"user","content":[{"type":"tool_result","content":"File does not exist","is_error":true,"tool_use_id":"tooluse_1"}]}}
            """.trimIndent(),
        )

        val result = assertIs<ClaudeStreamEvent.UserToolResult>(event)
        assertEquals("session-123", result.sessionId)
        assertEquals("tooluse_1", result.toolUseId)
        assertEquals("File does not exist", result.content)
        assertTrue(result.isError)
    }

    /** 验证 user 消息中的数组型 tool_result 内容会被安全解析为文本。 */
    @Test
    fun `parses user tool result array content into structured tool result event`() {
        val event = parser.parse(
            """
            {"type":"user","session_id":"session-123","message":{"role":"user","content":[{"type":"tool_result","content":[{"text":"Plan ready"},{"text":"Execute next"}],"is_error":false,"tool_use_id":"tooluse_2"}]}}
            """.trimIndent(),
        )

        val result = assertIs<ClaudeStreamEvent.UserToolResult>(event)
        assertEquals("session-123", result.sessionId)
        assertEquals("tooluse_2", result.toolUseId)
        assertEquals("Plan ready\n\nExecute next", result.content)
        assertEquals(false, result.isError)
    }

    /** 验证 message_delta 会保留 stop_reason 与 token usage。 */
    @Test
    fun `parses message delta usage into usage snapshot`() {
        val event = parser.parse(
            """
            {"type":"stream_event","event":{"delta":{"stop_reason":"tool_use"},"type":"message_delta","usage":{"cache_creation_input_tokens":0,"cache_read_input_tokens":12,"input_tokens":200,"output_tokens":30}},"session_id":"session-123"}
            """.trimIndent(),
        )

        val messageDelta = assertIs<ClaudeStreamEvent.MessageDelta>(event)
        assertEquals("session-123", messageDelta.sessionId)
        assertEquals("tool_use", messageDelta.stopReason)
        assertEquals(200, messageDelta.usage?.inputTokens)
        assertEquals(12, messageDelta.usage?.cachedInputTokens)
        assertEquals(30, messageDelta.usage?.outputTokens)
    }

    /** 验证 result 行会保留最终 usage 与 modelUsage 的上下文窗口。 */
    @Test
    fun `parses result line into completion event with model usage`() {
        val event = parser.parse(
            """
            {"type":"result","subtype":"success","session_id":"session-123","result":"All done","is_error":false,"usage":{"input_tokens":500,"cache_creation_input_tokens":0,"cache_read_input_tokens":20,"output_tokens":80},"modelUsage":{"claude-sonnet-4-6":{"inputTokens":500,"outputTokens":80,"cacheReadInputTokens":20,"cacheCreationInputTokens":0,"contextWindow":200000,"maxOutputTokens":32000}}}
            """.trimIndent(),
        )

        val result = assertIs<ClaudeStreamEvent.Result>(event)
        assertEquals("session-123", result.sessionId)
        assertEquals("success", result.subtype)
        assertEquals("All done", result.resultText)
        assertEquals(false, result.isError)
        assertEquals(500, result.usage?.inputTokens)
        assertEquals(20, result.usage?.cachedInputTokens)
        assertEquals(80, result.usage?.outputTokens)
        assertEquals(200000, result.modelUsage["claude-sonnet-4-6"]?.contextWindow)
    }

    /** 验证 thinking_delta 与 text_delta 会保留边界空格和换行，避免正文粘连。 */
    @Test
    fun `preserves whitespace in thinking and text deltas`() {
        val thinkingEvent = parser.parse(
            """
            {"type":"stream_event","event":{"delta":{"thinking":" review what\n","type":"thinking_delta"},"index":0,"type":"content_block_delta"},"session_id":"session-123"}
            """.trimIndent(),
        )
        val textEvent = parser.parse(
            """
            {"type":"stream_event","event":{"delta":{"text":" add the override after ","type":"text_delta"},"index":1,"type":"content_block_delta"},"session_id":"session-123"}
            """.trimIndent(),
        )

        val thinkingDelta = assertIs<ClaudeStreamEvent.ContentBlockDelta>(thinkingEvent)
        val thinking = assertIs<ClaudeContentDelta.Thinking>(thinkingDelta.delta)
        assertEquals(" review what\n", thinking.thinking)

        val textDelta = assertIs<ClaudeStreamEvent.ContentBlockDelta>(textEvent)
        val text = assertIs<ClaudeContentDelta.Text>(textDelta.delta)
        assertEquals(" add the override after ", text.text)
    }

    /** 验证未知的非 JSON 噪声会被安全忽略。 */
    @Test
    fun `ignores unknown non json lines`() {
        assertNull(parser.parse("plain-text noise"))
    }
}
