package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.settings.AgentSettingsService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 验证 Claude CLI provider 的统一事件映射与收尾行为。
 */
class ClaudeCliProviderTest {
    @Test
    /** 验证标准的 system/assistant/result 流会被映射为完整的统一事件。 */
    fun `stream maps system assistant and result lines to unified events`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                stdoutLines = listOf(
                    """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-5"}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_1","content":[{"type":"text","text":"Hello from Claude"}]}}""",
                    """{"type":"result","subtype":"success","session_id":"session-123","result":"Hello from Claude","is_error":false}""",
                ),
            ),
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-5",
                prompt = "Say hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        assertIs<UnifiedEvent.ThreadStarted>(events[0])
        assertEquals("session-123", (events[0] as UnifiedEvent.ThreadStarted).threadId)

        val turnStarted = assertIs<UnifiedEvent.TurnStarted>(events[1])
        assertEquals("session-123", turnStarted.threadId)

        val assistant = assertIs<UnifiedEvent.ItemUpdated>(events[2])
        assertEquals(ItemKind.NARRATIVE, assistant.item.kind)
        assertEquals("Hello from Claude", assistant.item.text)

        val finalAssistant = assertIs<UnifiedEvent.ItemUpdated>(events[3])
        assertEquals("Hello from Claude", finalAssistant.item.text)

        val completed = assertIs<UnifiedEvent.TurnCompleted>(events[4])
        assertEquals(TurnOutcome.SUCCESS, completed.outcome)
    }

    @Test
    /** 验证 Claude 进程启动失败时会向上游返回统一错误事件。 */
    fun `stream emits unified error when launcher fails`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                failure = IllegalStateException("cannot launch claude"),
            ),
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                prompt = "Say hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        val error = assertIs<UnifiedEvent.Error>(events.single())
        assertFalse(error.message.isBlank())
    }

    @Test
    /** 验证 Claude 静默退出时 provider 仍会发出错误和完成事件，避免界面无限 loading。 */
    fun `stream emits terminal events when claude exits without payload`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                stdoutLines = emptyList(),
                stderrLines = emptyList(),
                exitCode = 0,
            ),
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                prompt = "Say hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        assertEquals(2, events.size)
        val error = assertIs<UnifiedEvent.Error>(events[0])
        assertFalse(error.message.isBlank())
        val completed = assertIs<UnifiedEvent.TurnCompleted>(events[1])
        assertEquals(TurnOutcome.FAILED, completed.outcome)
    }

    @Test
    /** 验证 Claude 错误结果只会映射为单条错误事件，避免普通消息与 Error 卡片重复显示。 */
    fun `stream maps claude error result to a single terminal error without narrative duplication`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                stdoutLines = listOf(
                    """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_1","content":[{"type":"text","text":"API Error: 402 余额不足"}]},"error":"invalid_request"}""",
                    """{"type":"result","subtype":"success","session_id":"session-123","result":"API Error: 402 余额不足","is_error":true}""",
                ),
            ),
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Say hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        assertEquals(4, events.size)
        assertIs<UnifiedEvent.ThreadStarted>(events[0])
        assertIs<UnifiedEvent.TurnStarted>(events[1])
        val error = assertIs<UnifiedEvent.Error>(events[2])
        assertEquals("API Error: 402 余额不足", error.message)
        val completed = assertIs<UnifiedEvent.TurnCompleted>(events[3])
        assertEquals(TurnOutcome.FAILED, completed.outcome)
    }

    @Test
    /** 验证 Claude provider 能处理从 IO 上下文回调的流式事件，避免触发 Flow invariant。 */
    fun `stream accepts session callbacks emitted from io context`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = IoDispatchingClaudeCliLauncher(),
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Say hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        assertIs<UnifiedEvent.ThreadStarted>(events[0])
        assertIs<UnifiedEvent.TurnStarted>(events[1])
        val assistant = assertIs<UnifiedEvent.ItemUpdated>(events[2])
        assertEquals("Hello from Claude", assistant.item.text)
        val completed = assertIs<UnifiedEvent.TurnCompleted>(events.last())
        assertEquals(TurnOutcome.SUCCESS, completed.outcome)
    }

    @Test
    /** 验证 Claude provider 会输出关键诊断日志，便于定位流式调用卡住的阶段。 */
    fun `stream writes lifecycle diagnostics for claude session`() = runBlocking {
        val logs = mutableListOf<String>()
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                stdoutLines = listOf(
                    """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-5"}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_1","content":[{"type":"text","text":"Hello from Claude"}]}}""",
                    """{"type":"result","subtype":"success","session_id":"session-123","result":"Hello from Claude","is_error":false}""",
                ),
                stderrLines = listOf("stderr noise"),
            ),
            diagnosticLogger = { message -> logs += message },
        )

        provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-5",
                prompt = "Say hello",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        assertTrue(logs.any { it.contains("Claude CLI start: requestId=") })
        assertTrue(logs.any { it.contains("Claude CLI stdout:") })
        assertTrue(logs.any { it.contains("Claude CLI stderr:") })
        assertTrue(logs.any { it.contains("Claude CLI parsed event:") })
        assertTrue(logs.any { it.contains("Claude CLI emit unified event:") })
        assertTrue(logs.any { it.contains("Claude CLI exited:") })
    }

    @Test
    /** 验证 Claude provider 会把真实 stream_event 过程对齐成 reasoning、tool、正文流与 token usage。 */
    fun `stream maps claude process details into unified timeline events`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                stdoutLines = listOf(
                    """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
                    """{"type":"stream_event","event":{"message":{"content":[],"id":"msg_tool","model":"claude-sonnet-4-6","role":"assistant","type":"message","usage":{"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"input_tokens":32982,"output_tokens":0}},"type":"message_start"},"session_id":"session-123"}""",
                    """{"type":"stream_event","event":{"content_block":{"id":"tooluse_1","input":{},"name":"Read","type":"tool_use"},"index":0,"type":"content_block_start"},"session_id":"session-123"}""",
                    """{"type":"stream_event","event":{"delta":{"partial_json":"{\"file_path\":\"/tmp/demo.txt\"}","type":"input_json_delta"},"index":0,"type":"content_block_delta"},"session_id":"session-123"}""",
                    """{"type":"user","session_id":"session-123","message":{"role":"user","content":[{"type":"tool_result","content":"File does not exist","is_error":true,"tool_use_id":"tooluse_1"}]}}""",
                    """{"type":"stream_event","event":{"message":{"content":[],"id":"msg_answer","model":"claude-sonnet-4-6","role":"assistant","type":"message","usage":{"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"input_tokens":33052,"output_tokens":0}},"type":"message_start"},"session_id":"session-123"}""",
                    """{"type":"stream_event","event":{"content_block":{"thinking":"","type":"thinking"},"index":0,"type":"content_block_start"},"session_id":"session-123"}""",
                    """{"type":"stream_event","event":{"delta":{"thinking":"Inspecting repository structure","type":"thinking_delta"},"index":0,"type":"content_block_delta"},"session_id":"session-123"}""",
                    """{"type":"stream_event","event":{"index":0,"type":"content_block_stop"},"session_id":"session-123"}""",
                    """{"type":"stream_event","event":{"content_block":{"text":"","type":"text"},"index":1,"type":"content_block_start"},"session_id":"session-123"}""",
                    """{"type":"stream_event","event":{"delta":{"text":"工程已全部创建完毕。","type":"text_delta"},"index":1,"type":"content_block_delta"},"session_id":"session-123"}""",
                    """{"type":"stream_event","event":{"delta":{"stop_reason":"end_turn"},"type":"message_delta","usage":{"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"input_tokens":46300,"output_tokens":808}},"session_id":"session-123"}""",
                    """{"type":"result","subtype":"success","session_id":"session-123","result":"工程已全部创建完毕。","is_error":false,"usage":{"input_tokens":539211,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":12125},"modelUsage":{"claude-sonnet-4-6":{"inputTokens":539211,"outputTokens":12125,"cacheReadInputTokens":0,"cacheCreationInputTokens":0,"contextWindow":200000,"maxOutputTokens":32000}}}""",
                ),
            ),
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Explain what you are doing",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        assertTrue(events.any { event ->
            event is UnifiedEvent.ItemUpdated &&
                event.item.kind == ItemKind.COMMAND_EXEC &&
                event.item.name == "Read" &&
                event.item.command?.contains("/tmp/demo.txt") == true
        })
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
        assertEquals("session-123", finalUsage.threadId)
        assertEquals(200000, finalUsage.contextWindow)
        assertEquals(539211, finalUsage.inputTokens)
        assertEquals(12125, finalUsage.outputTokens)

        val completed = assertIs<UnifiedEvent.TurnCompleted>(events.last())
        assertEquals(TurnOutcome.SUCCESS, completed.outcome)
    }

    @Test
    /** 验证 tool 前后的两段 assistant message 会映射成两个 narrative 节点，而不是复用同一个节点。 */
    fun `stream keeps assistant messages before and after tool in separate unified items`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                stdoutLines = listOf(
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
                ),
            ),
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Explain and use tools",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

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

    @Test
    /** 验证 Claude 的 TodoWrite 会映射为顶部运行计划事件，其它工具仍映射到结构化 timeline 节点。 */
    fun `stream maps claude todo to running plan and other tools to timeline items`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                stdoutLines = listOf(
                    """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_todo","content":[{"id":"tooluse_todo","input":{"todos":[{"content":"Create ClaudeLocalHistoryReader.kt","status":"completed"},{"content":"Override loadInitialHistory() in ClaudeCliProvider","status":"in_progress"},{"content":"Add ClaudeLocalHistoryReaderTest","status":"pending"}]},"name":"TodoWrite","type":"tool_use"}]}}""",
                    """{"type":"user","session_id":"session-123","message":{"role":"user","content":[{"tool_use_id":"tooluse_todo","type":"tool_result","content":"Todos have been modified successfully. Ensure that you continue to use the todo list to track your progress."}]}}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_read","content":[{"id":"tooluse_read","input":{"file_path":"/tmp/ClaudeCliProviderTest.kt"},"name":"Read","type":"tool_use"}]}}""",
                    """{"type":"user","session_id":"session-123","message":{"role":"user","content":[{"tool_use_id":"tooluse_read","type":"tool_result","content":"1→class ClaudeCliProviderTest"}]}}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_write","content":[{"id":"tooluse_write","input":{"file_path":"/tmp/ClaudeLocalHistoryReader.kt","content":"class ClaudeLocalHistoryReader"},"name":"Write","type":"tool_use"}]}}""",
                    """{"type":"user","session_id":"session-123","message":{"role":"user","content":[{"tool_use_id":"tooluse_write","type":"tool_result","content":"File created successfully at: /tmp/ClaudeLocalHistoryReader.kt"}]}}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_edit","content":[{"id":"tooluse_edit","input":{"replace_all":false,"file_path":"/tmp/ClaudeCliProvider.kt","old_string":"old text","new_string":"new text"},"name":"Edit","type":"tool_use"}]}}""",
                    """{"type":"user","session_id":"session-123","message":{"role":"user","content":[{"tool_use_id":"tooluse_edit","type":"tool_result","content":"The file /tmp/ClaudeCliProvider.kt has been updated successfully."}]}}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_final","content":[{"type":"text","text":"全部完成。"}]}}""",
                    """{"type":"result","subtype":"success","session_id":"session-123","result":"全部完成。","is_error":false}""",
                ),
            ),
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Map Claude tools",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        val turnStarted = events.filterIsInstance<UnifiedEvent.TurnStarted>().firstOrNull()
            ?: error("Missing TurnStarted event.")
        val runningPlan = events.filterIsInstance<UnifiedEvent.RunningPlanUpdated>().firstOrNull()
            ?: error("Missing RunningPlanUpdated event for TodoWrite.")
        assertEquals(turnStarted.turnId, runningPlan.turnId)
        assertEquals(3, runningPlan.steps.size)
        assertEquals("Create ClaudeLocalHistoryReader.kt", runningPlan.steps[0].step)
        assertEquals("Override loadInitialHistory() in ClaudeCliProvider", runningPlan.steps[1].step)
        assertEquals("completed", runningPlan.steps[0].status)
        assertEquals("in_progress", runningPlan.steps[1].status)
        assertTrue(runningPlan.body.contains("- [x] Create ClaudeLocalHistoryReader.kt"))
        assertTrue(runningPlan.body.contains("Override loadInitialHistory() in ClaudeCliProvider"))

        val itemUpdates = events.filterIsInstance<UnifiedEvent.ItemUpdated>()
        assertFalse(itemUpdates.any { it.item.id.contains("tooluse_todo") })

        val readItem = itemUpdates.firstOrNull {
            it.item.id.contains("tooluse_read") &&
                it.item.status == com.auracode.assistant.protocol.ItemStatus.SUCCESS
        }
            ?: error("Missing Read item.")
        assertEquals(ItemKind.COMMAND_EXEC, readItem.item.kind)
        assertEquals("Read", readItem.item.name)
        assertTrue(readItem.item.command.orEmpty().contains("/tmp/ClaudeCliProviderTest.kt"))
        assertTrue(readItem.item.text.orEmpty().contains("ClaudeCliProviderTest"))

        val writeItem = itemUpdates.firstOrNull { it.item.id.contains("tooluse_write") && it.item.status == com.auracode.assistant.protocol.ItemStatus.SUCCESS }
            ?: error("Missing Write item.")
        assertEquals(ItemKind.DIFF_APPLY, writeItem.item.kind)
        assertEquals("/tmp/ClaudeLocalHistoryReader.kt", writeItem.item.fileChanges.single().path)
        assertEquals("create", writeItem.item.fileChanges.single().kind)

        val editItem = itemUpdates.firstOrNull { it.item.id.contains("tooluse_edit") && it.item.status == com.auracode.assistant.protocol.ItemStatus.SUCCESS }
            ?: error("Missing Edit item.")
        assertEquals(ItemKind.DIFF_APPLY, editItem.item.kind)
        assertEquals("/tmp/ClaudeCliProvider.kt", editItem.item.fileChanges.single().path)
        assertEquals("update", editItem.item.fileChanges.single().kind)
    }

    @Test
    /** 验证 Claude provider 声明支持 plan 模式，确保 UI 层能正确启用 Plan 切换按钮。 */
    fun `capabilities reports supportsPlanMode true`() {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(),
        )

        assertTrue(provider.capabilities().supportsPlanMode)
    }

    @Test
    /** 验证 Claude provider 会声明支持结构化历史，确保 timeline 可以直接复用统一事件恢复。 */
    fun `capabilities reports supports structured history true`() {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(),
        )

        assertTrue(provider.capabilities().supportsStructuredHistory)
    }

    private class FakeClaudeCliLauncher(
        private val stdoutLines: List<String> = emptyList(),
        private val stderrLines: List<String> = emptyList(),
        private val exitCode: Int = 0,
        private val failure: Throwable? = null,
    ) : ClaudeCliLauncher {
        override fun start(
            request: AgentRequest,
            settings: AgentSettingsService,
        ): ClaudeStreamJsonSession {
            failure?.let { throw it }
            return object : ClaudeStreamJsonSession {
                override suspend fun collect(
                    onStdoutLine: suspend (String) -> Unit,
                    onStderrLine: suspend (String) -> Unit,
                ): Int {
                    stdoutLines.forEach { onStdoutLine(it) }
                    stderrLines.forEach { onStderrLine(it) }
                    return exitCode
                }

                /** Ignores stdin writes because this fake session only replays recorded output. */
                override suspend fun writeStdin(line: String) = Unit

                override fun cancel() = Unit
            }
        }
    }

    /** 模拟真实进程在 IO 上下文中回调 stdout 的行为。 */
    private class IoDispatchingClaudeCliLauncher : ClaudeCliLauncher {
        /** 返回一个会在 Dispatchers.IO 中逐条转发 stdout 的假会话。 */
        override fun start(
            request: AgentRequest,
            settings: AgentSettingsService,
        ): ClaudeStreamJsonSession {
            return object : ClaudeStreamJsonSession {
                /** 在 IO 上下文中回放 Claude 事件，复现真实子进程读取线程的调度方式。 */
                override suspend fun collect(
                    onStdoutLine: suspend (String) -> Unit,
                    onStderrLine: suspend (String) -> Unit,
                ): Int {
                    val stdoutLines = listOf(
                        """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
                        """{"type":"assistant","session_id":"session-123","message":{"id":"msg_1","content":[{"type":"text","text":"Hello from Claude"}]}}""",
                        """{"type":"result","subtype":"success","session_id":"session-123","result":"Hello from Claude","is_error":false}""",
                    )
                    stdoutLines.forEach { line ->
                        withContext(Dispatchers.IO) {
                            onStdoutLine(line)
                        }
                    }
                    return 0
                }

                /** Ignores stdin writes because this fake session only replays recorded output. */
                override suspend fun writeStdin(line: String) = Unit

                /** 单测不需要真正取消子进程。 */
                override fun cancel() = Unit
            }
        }
    }
}
