package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionCommandKind
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionMessageRole
import com.auracode.assistant.session.kernel.SessionToolKind
import com.auracode.assistant.session.kernel.SessionTurnOutcome
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.protocol.ProviderToolUserInputAnswerDraft
import com.auracode.assistant.protocol.ProviderToolUserInputOption
import com.auracode.assistant.protocol.ProviderToolUserInputPrompt
import com.auracode.assistant.protocol.ProviderToolUserInputQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        val threadStarted = assertIs<SessionDomainEvent.ThreadStarted>(events[0])
        assertEquals("session-123", threadStarted.threadId)

        val turnStarted = assertIs<SessionDomainEvent.TurnStarted>(events[1])
        assertEquals("session-123", turnStarted.threadId)

        val assistant = assertIs<SessionDomainEvent.MessageAppended>(events[2])
        assertEquals(SessionMessageRole.ASSISTANT, assistant.role)
        assertEquals("Hello from Claude", assistant.text)

        val finalAssistant = assertIs<SessionDomainEvent.MessageAppended>(events[3])
        assertEquals("Hello from Claude", finalAssistant.text)

        val completed = assertIs<SessionDomainEvent.TurnCompleted>(events[4])
        assertEquals(SessionTurnOutcome.SUCCESS, completed.outcome)
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

        val error = assertIs<SessionDomainEvent.ErrorAppended>(events.single())
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
        val error = assertIs<SessionDomainEvent.ErrorAppended>(events[0])
        assertFalse(error.message.isBlank())
        val completed = assertIs<SessionDomainEvent.TurnCompleted>(events[1])
        assertEquals(SessionTurnOutcome.FAILED, completed.outcome)
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
        assertIs<SessionDomainEvent.ThreadStarted>(events[0])
        assertIs<SessionDomainEvent.TurnStarted>(events[1])
        val error = assertIs<SessionDomainEvent.ErrorAppended>(events[2])
        assertEquals("API Error: 402 余额不足", error.message)
        val completed = assertIs<SessionDomainEvent.TurnCompleted>(events[3])
        assertEquals(SessionTurnOutcome.FAILED, completed.outcome)
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

        assertIs<SessionDomainEvent.ThreadStarted>(events[0])
        assertIs<SessionDomainEvent.TurnStarted>(events[1])
        val assistant = assertIs<SessionDomainEvent.MessageAppended>(events[2])
        assertEquals("Hello from Claude", assistant.text)
        val completed = assertIs<SessionDomainEvent.TurnCompleted>(events.last())
        assertEquals(SessionTurnOutcome.SUCCESS, completed.outcome)
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
        assertTrue(logs.any { it.contains("Claude CLI emitted no semantic events:").not() })
        assertTrue(logs.any { it.contains("Claude CLI exited:") })
        assertTrue(logs.any { it.contains("Claude CLI finalized:") })
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
            event is SessionDomainEvent.CommandUpdated &&
                event.commandKind == SessionCommandKind.READ_FILE &&
                event.command?.contains("/tmp/demo.txt") == true
        })
        assertTrue(events.any { event ->
            event is SessionDomainEvent.CommandUpdated &&
                event.status == SessionActivityStatus.FAILED &&
                event.outputText?.contains("File does not exist") == true
        })
        assertTrue(events.any { event ->
            event is SessionDomainEvent.ReasoningUpdated &&
                event.text == "Inspecting repository structure"
        })
        assertTrue(events.any { event ->
            event is SessionDomainEvent.MessageAppended &&
                event.role == SessionMessageRole.ASSISTANT &&
                event.text == "工程已全部创建完毕。"
        })

        val finalUsage = events.filterIsInstance<SessionDomainEvent.UsageUpdated>().last()
        assertEquals("session-123", finalUsage.threadId)
        assertEquals(200000, finalUsage.contextWindow)
        assertEquals(539211, finalUsage.inputTokens)
        assertEquals(12125, finalUsage.outputTokens)

        val completed = assertIs<SessionDomainEvent.TurnCompleted>(events.last())
        assertEquals(SessionTurnOutcome.SUCCESS, completed.outcome)
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

        val messageUpdates = events.filterIsInstance<SessionDomainEvent.MessageAppended>()
            .filter { it.role == SessionMessageRole.ASSISTANT }
        val distinctMessageIds = messageUpdates.map { it.messageId }.distinct()
        assertEquals(2, distinctMessageIds.size)
        assertNotEquals(distinctMessageIds[0], distinctMessageIds[1])

        val firstMessageIndex = events.indexOfFirst { event ->
            event is SessionDomainEvent.MessageAppended &&
                event.role == SessionMessageRole.ASSISTANT &&
                event.text == "先说明一下。"
        }
        val toolIndex = events.indexOfFirst { event ->
            event is SessionDomainEvent.CommandUpdated &&
                event.commandKind == SessionCommandKind.READ_FILE
        }
        val secondMessageIndex = events.indexOfFirst { event ->
            event is SessionDomainEvent.MessageAppended &&
                event.role == SessionMessageRole.ASSISTANT &&
                event.text == "再给最终答复。"
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

        val turnStarted = events.filterIsInstance<SessionDomainEvent.TurnStarted>().firstOrNull()
            ?: error("Missing TurnStarted event.")
        val runningPlan = events.filterIsInstance<SessionDomainEvent.RunningPlanUpdated>().firstOrNull()
            ?: error("Missing RunningPlanUpdated event for TodoWrite.")
        assertEquals(turnStarted.turnId, runningPlan.plan.turnId)
        assertEquals(3, runningPlan.plan.steps.size)
        assertEquals("Create ClaudeLocalHistoryReader.kt", runningPlan.plan.steps[0].step)
        assertEquals("Override loadInitialHistory() in ClaudeCliProvider", runningPlan.plan.steps[1].step)
        assertEquals("completed", runningPlan.plan.steps[0].status)
        assertEquals("in_progress", runningPlan.plan.steps[1].status)
        assertTrue(runningPlan.plan.body.contains("- [x] Create ClaudeLocalHistoryReader.kt"))
        assertTrue(runningPlan.plan.body.contains("Override loadInitialHistory() in ClaudeCliProvider"))

        val commandUpdates = events.filterIsInstance<SessionDomainEvent.CommandUpdated>()
        val fileChangeUpdates = events.filterIsInstance<SessionDomainEvent.FileChangesUpdated>()
        assertFalse(commandUpdates.any { it.itemId.contains("tooluse_todo") })
        assertFalse(fileChangeUpdates.any { it.itemId.contains("tooluse_todo") })

        val readItem = commandUpdates.firstOrNull {
            it.itemId.contains("tooluse_read") &&
                it.status == SessionActivityStatus.SUCCESS
        }
            ?: error("Missing Read item.")
        assertEquals(SessionCommandKind.READ_FILE, readItem.commandKind)
        assertTrue(readItem.command.orEmpty().contains("/tmp/ClaudeCliProviderTest.kt"))
        assertTrue(readItem.outputText.orEmpty().contains("ClaudeCliProviderTest"))

        val writeItem = fileChangeUpdates.firstOrNull {
            it.itemId.contains("tooluse_write") && it.status == SessionActivityStatus.SUCCESS
        }
            ?: error("Missing Write item.")
        assertEquals("/tmp/ClaudeLocalHistoryReader.kt", writeItem.changes.single().path)
        assertEquals("create", writeItem.changes.single().kind)

        val editItem = fileChangeUpdates.firstOrNull {
            it.itemId.contains("tooluse_edit") && it.status == SessionActivityStatus.SUCCESS
        }
            ?: error("Missing Edit item.")
        assertEquals("/tmp/ClaudeCliProvider.kt", editItem.changes.single().path)
        assertEquals("update", editItem.changes.single().kind)
    }

    @Test
    /** 验证 plan 模式下缺少 ExitPlanMode 时，会使用最后一次 TodoWrite 作为最终计划正文回退。 */
    fun `stream falls back to last todo write body for plan completion when exit plan mode is absent`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                stdoutLines = listOf(
                    """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_todo","content":[{"id":"tooluse_todo","input":{"todos":[{"content":"Ship plan mode","status":"completed"},{"content":"Add fallback completion body","status":"in_progress"}]},"name":"TodoWrite","type":"tool_use"}]}}""",
                    """{"type":"user","session_id":"session-123","message":{"role":"user","content":[{"tool_use_id":"tooluse_todo","type":"tool_result","content":"Todos have been modified successfully."}]}}""",
                    """{"type":"result","subtype":"success","session_id":"session-123","result":"Plan complete.","is_error":false}""",
                ),
            ),
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Plan this change",
                contextFiles = emptyList(),
                workingDirectory = ".",
                collaborationMode = com.auracode.assistant.model.AgentCollaborationMode.PLAN,
            ),
        ).toList()

        val planUpdates = events.filterIsInstance<SessionDomainEvent.RunningPlanUpdated>()
        assertTrue(planUpdates.size >= 2)
        val finalPlan = planUpdates.last().plan
        assertTrue(finalPlan.body.contains("Ship plan mode"))
        assertTrue(finalPlan.body.contains("Add fallback completion body"))
        val completed = assertIs<SessionDomainEvent.TurnCompleted>(events.last())
        assertEquals(SessionTurnOutcome.SUCCESS, completed.outcome)
    }

    @Test
    /** 验证 plan 模式下 ExitPlanMode 会被宿主自动确认并直接结束当前计划轮次。 */
    fun `stream auto accepts exit plan mode and completes plan turn without approval request`() = runBlocking {
        val launcher = RecordingClaudeCliLauncher(
            stdoutLines = listOf(
                """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
                """{"type":"assistant","session_id":"session-123","message":{"id":"msg_exit","content":[{"id":"tooluse_exit","input":{"plan":"# Plan\n\n- [completed] Inspect\n- [pending] Execute","planFilePath":"/tmp/plan.md"},"name":"ExitPlanMode","type":"tool_use"}]}}""",
                """{"type":"control_request","request_id":"approval-1","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode","display_name":"ExitPlanMode","input":{"plan":"# Plan\n\n- [completed] Inspect\n- [pending] Execute","planFilePath":"/tmp/plan.md"},"tool_use_id":"tooluse_exit"}}""",
            ),
        )
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = launcher,
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Plan this change",
                contextFiles = emptyList(),
                workingDirectory = ".",
                collaborationMode = com.auracode.assistant.model.AgentCollaborationMode.PLAN,
            ),
        ).toList()

        val planUpdates = events.filterIsInstance<SessionDomainEvent.RunningPlanUpdated>()
        assertEquals(2, planUpdates.size)
        assertEquals(
            com.auracode.assistant.session.kernel.SessionRunningPlanPresentation.TIMELINE,
            planUpdates[0].plan.presentation,
        )
        assertEquals(
            com.auracode.assistant.session.kernel.SessionRunningPlanPresentation.SUBMISSION_PANEL,
            planUpdates[1].plan.presentation,
        )
        assertTrue(planUpdates.all { it.plan.body.contains("Execute") })
        assertFalse(events.any { it is SessionDomainEvent.ApprovalRequested })
        val completed = assertIs<SessionDomainEvent.TurnCompleted>(events.last())
        assertEquals(SessionTurnOutcome.SUCCESS, completed.outcome)
        assertEquals(1, launcher.stdinWrites.size)
        assertTrue(launcher.stdinWrites.single().contains("\"type\":\"control_response\""))
        assertTrue(launcher.stdinWrites.single().contains("\"request_id\":\"approval-1\""))
        assertTrue(launcher.stdinWrites.single().contains("\"behavior\":\"allow\""))
        assertTrue(launcher.cancelCalled)
    }

    @Test
    /** 验证 Claude task_progress 事件不会中断主流程，并继续输出正常的计划/完成事件。 */
    fun `stream accepts task progress events without dropping subsequent plan lifecycle`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                stdoutLines = listOf(
                    """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
                    """{"type":"system","subtype":"task_progress","task_id":"task-1","tool_use_id":"tooluse_read","description":"Reading SessionStateReducer","usage":{"total_tokens":35627,"tool_uses":16,"duration_ms":25903},"last_tool_name":"Read","session_id":"session-123"}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_todo","content":[{"id":"tooluse_todo","input":{"todos":[{"content":"Inspect events","status":"completed"},{"content":"Finalize plan","status":"pending"}]},"name":"TodoWrite","type":"tool_use"}]}}""",
                    """{"type":"result","subtype":"success","session_id":"session-123","result":"Plan complete.","is_error":false}""",
                ),
            ),
            diagnosticLogger = { },
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Plan this change",
                contextFiles = emptyList(),
                workingDirectory = ".",
                collaborationMode = com.auracode.assistant.model.AgentCollaborationMode.PLAN,
            ),
        ).toList()

        assertTrue(events.any { it is SessionDomainEvent.RunningPlanUpdated })
        val completed = assertIs<SessionDomainEvent.TurnCompleted>(events.last())
        assertEquals(SessionTurnOutcome.SUCCESS, completed.outcome)
    }

    @Test
    /** Verifies that Claude placeholder diff actions without file paths never become file-change timeline events. */
    fun `stream ignores placeholder diff apply items without real file paths`() = runBlocking {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(
                stdoutLines = listOf(
                    """{"type":"system","subtype":"init","session_id":"session-123","model":"claude-sonnet-4-6"}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_write","content":[{"id":"tooluse_write","input":{"content":"class Placeholder"},"name":"Write","type":"tool_use"}]}}""",
                    """{"type":"user","session_id":"session-123","message":{"role":"user","content":[{"tool_use_id":"tooluse_write","type":"tool_result","content":"create"}]}}""",
                    """{"type":"assistant","session_id":"session-123","message":{"id":"msg_final","content":[{"type":"text","text":"处理完成。"}]}}""",
                    """{"type":"result","subtype":"success","session_id":"session-123","result":"处理完成。","is_error":false}""",
                ),
            ),
        )

        val events = provider.stream(
            AgentRequest(
                engineId = "claude",
                model = "claude-sonnet-4-6",
                prompt = "Filter placeholder file changes",
                contextFiles = emptyList(),
                workingDirectory = ".",
            ),
        ).toList()

        assertFalse(events.any { it is SessionDomainEvent.FileChangesUpdated })
        assertTrue(events.any { event ->
            event is SessionDomainEvent.MessageAppended &&
                event.role == SessionMessageRole.ASSISTANT &&
                event.text == "处理完成。"
        })
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

    @Test
    /** 验证 AskUserQuestion 回写使用 Claude SDK 兼容的 updatedInput 协议，并以 question 文本作为 answers key。 */
    fun `build tool user input response uses updated input payload keyed by question text`() {
        val provider = ClaudeCliProvider(
            settings = AgentSettingsService().apply { loadState(AgentSettingsService.State()) },
            launcher = FakeClaudeCliLauncher(),
            diagnosticLogger = { },
        )
        val json = provider.buildToolUserInputResponse(
            controlRequestId = "request-1",
            prompt = ProviderToolUserInputPrompt(
                requestId = "request-1",
                threadId = "thread-1",
                turnId = "turn-1",
                itemId = "item-1",
                rawQuestionsJson = """
                    [
                      {
                        "question": "当前目录已有一个 design-patterns-demo 项目，你希望怎么做？",
                        "header": "操作方式",
                        "options": [
                          {"label": "扩展现有项目", "description": "desc"},
                          {"label": "新建独立项目", "description": "desc"}
                        ],
                        "multiSelect": false
                      },
                      {
                        "question": "你希望重点覆盖哪类设计模式？",
                        "header": "模式类型",
                        "options": [
                          {"label": "创建型", "description": "desc"},
                          {"label": "结构型", "description": "desc"},
                          {"label": "行为型", "description": "desc"}
                        ],
                        "multiSelect": true
                      }
                    ]
                """.trimIndent(),
                questions = listOf(
                    ProviderToolUserInputQuestion(
                        id = "q0",
                        header = "操作方式",
                        question = "当前目录已有一个 design-patterns-demo 项目，你希望怎么做？",
                        options = listOf(
                            ProviderToolUserInputOption("扩展现有项目", "desc"),
                            ProviderToolUserInputOption("新建独立项目", "desc"),
                        ),
                    ),
                    ProviderToolUserInputQuestion(
                        id = "q1",
                        header = "模式类型",
                        question = "你希望重点覆盖哪类设计模式？",
                        options = listOf(
                            ProviderToolUserInputOption("创建型", "desc"),
                            ProviderToolUserInputOption("结构型", "desc"),
                            ProviderToolUserInputOption("行为型", "desc"),
                        ),
                        multiSelect = true,
                    ),
                ),
            ),
            answers = mapOf(
                "q0" to ProviderToolUserInputAnswerDraft(listOf("扩展现有项目")),
                "q1" to ProviderToolUserInputAnswerDraft(listOf("创建型", "结构型")),
            ),
        )

        val payload = Json.parseToJsonElement(json).jsonObject
        val response = payload.getValue("response").jsonObject
        assertEquals("success", response.getValue("subtype").jsonPrimitive.content)
        assertEquals("request-1", response.getValue("request_id").jsonPrimitive.content)
        val updatedInput = response.getValue("response").jsonObject
            .getValue("updatedInput").jsonObject
        val questions = updatedInput.getValue("questions").jsonArray
        assertEquals(2, questions.size)
        val answers = updatedInput.getValue("answers").jsonObject
        assertEquals("扩展现有项目", answers.getValue("当前目录已有一个 design-patterns-demo 项目，你希望怎么做？").jsonPrimitive.content)
        assertEquals("创建型, 结构型", answers.getValue("你希望重点覆盖哪类设计模式？").jsonPrimitive.content)
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

    private class RecordingClaudeCliLauncher(
        private val stdoutLines: List<String> = emptyList(),
        private val stderrLines: List<String> = emptyList(),
        private val exitCode: Int = 0,
    ) : ClaudeCliLauncher {
        val stdinWrites: MutableList<String> = mutableListOf()
        var cancelCalled: Boolean = false

        override fun start(
            request: AgentRequest,
            settings: AgentSettingsService,
        ): ClaudeStreamJsonSession {
            return object : ClaudeStreamJsonSession {
                override suspend fun collect(
                    onStdoutLine: suspend (String) -> Unit,
                    onStderrLine: suspend (String) -> Unit,
                ): Int {
                    stdoutLines.forEach { onStdoutLine(it) }
                    stderrLines.forEach { onStderrLine(it) }
                    return exitCode
                }

                /** Records stdin writes so the test can verify control-response behavior. */
                override suspend fun writeStdin(line: String) {
                    stdinWrites += line
                }

                /** Records cancellation because ExitPlanMode should close the current stream. */
                override fun cancel() {
                    cancelCalled = true
                }
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
