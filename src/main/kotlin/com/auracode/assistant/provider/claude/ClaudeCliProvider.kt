package com.auracode.assistant.provider.claude

import com.auracode.assistant.conversation.ConversationCapabilities
import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationRef
import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.provider.AgentProvider
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.settings.AgentSettingsService
import com.auracode.assistant.toolwindow.approval.ApprovalAction
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/**
 * 负责驱动 Claude CLI 流式会话，并在关键边界输出诊断日志。
 */
internal class ClaudeCliProvider(
    private val settings: AgentSettingsService,
    private val launcher: ClaudeCliLauncher = DefaultClaudeCliLauncher(),
    private val parser: ClaudeStreamEventParser = ClaudeStreamEventParser(),
    private val historyReader: ClaudeLocalHistoryReader = ClaudeLocalHistoryReader(),
    private val diagnosticLogger: (String) -> Unit = { message -> LOG.info(message) },
) : AgentProvider {
    override val providerId: String = ClaudeProviderFactory.ENGINE_ID
    private val running = ConcurrentHashMap<String, ClaudeStreamJsonSession>()

    /** requestId → (requestId → deferred) 的两级映射，支持同一 turn 内多个并发授权请求。 */
    private val pendingApprovals = ConcurrentHashMap<String, ConcurrentHashMap<String, CompletableDeferred<ApprovalAction>>>()

    /** 启动 Claude CLI 流式会话，并将原始输出转换为统一事件。 */
    override fun stream(request: AgentRequest): Flow<UnifiedEvent> = channelFlow {
        logStreamStart(request)
        val session = runCatching { launcher.start(request, settings) }
            .onFailure { error ->
                diagnosticLogger(
                    "Claude CLI launch failed: requestId=${request.requestId} " +
                        "message=${error.message.orEmpty().ifBlank { error::class.java.simpleName }}",
                )
            }
            .getOrElse { error ->
                send(UnifiedEvent.Error(error.message.orEmpty().ifBlank { "Failed to start Claude CLI." }))
                return@channelFlow
            }
        running[request.requestId] = session
        val approvals = ConcurrentHashMap<String, CompletableDeferred<ApprovalAction>>()
        pendingApprovals[request.requestId] = approvals
        val accumulator = ClaudeStreamAccumulator()
        val mapper = ClaudeUnifiedEventMapper(request)
        try {
            val exitCode = session.collect(
                onStdoutLine = { line ->
                    collectLine(
                        request = request,
                        channel = "stdout",
                        line = line,
                        accumulator = accumulator,
                        mapper = mapper,
                        emitUnified = { unified ->
                            if (unified is UnifiedEvent.ApprovalRequested) {
                                // 注册 deferred，等待 UI 层调用 submitApprovalDecision。
                                val deferred = CompletableDeferred<ApprovalAction>()
                                approvals[unified.request.requestId] = deferred
                                send(unified)
                                val decision = deferred.await()
                                approvals.remove(unified.request.requestId)
                                session.writeStdin(buildControlResponse(unified.request.requestId, decision))
                                diagnosticLogger(
                                    "Claude CLI approval decision: requestId=${request.requestId} " +
                                        "approvalId=${unified.request.requestId} decision=$decision",
                                )
                            } else {
                                send(unified)
                            }
                        },
                    )
                },
                onStderrLine = { line ->
                    collectLine(
                        request = request,
                        channel = "stderr",
                        line = line,
                        accumulator = accumulator,
                        mapper = mapper,
                        emitUnified = { unified -> send(unified) },
                    )
                },
            )
            diagnosticLogger("Claude CLI exited: requestId=${request.requestId} exitCode=$exitCode")
            mapper.onProcessExit(exitCode).forEach { unified ->
                emitUnifiedEvent(request, unified) { event -> send(event) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            diagnosticLogger(
                "Claude CLI stream failed: requestId=${request.requestId} " +
                    "message=${error.message.orEmpty().ifBlank { error::class.java.simpleName }}",
            )
            send(UnifiedEvent.Error(error.message.orEmpty().ifBlank { "Claude CLI request failed." }))
        } finally {
            pendingApprovals.remove(request.requestId)?.values?.forEach { it.complete(ApprovalAction.REJECT) }
            running.remove(request.requestId)
            session.cancel()
            diagnosticLogger("Claude CLI finalized: requestId=${request.requestId}")
        }
    }

    /** 返回 Claude 引擎当前支持的会话能力。 */
    override fun capabilities(): ConversationCapabilities = ConversationCapabilities(
        supportsStructuredHistory = true,
        supportsHistoryPagination = false,
        supportsPlanMode = true,
        supportsApprovalRequests = true,
        supportsToolUserInput = false,
        supportsResume = true,
        supportsAttachments = true,
        supportsImageInputs = true,
        supportsSubagents = false,
    )

    /** 将 UI 层的授权决定写回 Claude CLI 的 stdin。 */
    override fun submitApprovalDecision(requestId: String, decision: ApprovalAction): Boolean {
        for (approvals in pendingApprovals.values) {
            val deferred = approvals[requestId] ?: continue
            return deferred.complete(decision)
        }
        return false
    }

    /** 从 Claude CLI 本地 JSONL 文件中读取历史会话记录。 */
    override suspend fun loadInitialHistory(ref: ConversationRef, pageSize: Int): ConversationHistoryPage {
        return historyReader.readHistory(ref.remoteConversationId)
    }

    /** 取消指定请求对应的 Claude CLI 进程。 */
    override fun cancel(requestId: String) {
        pendingApprovals.remove(requestId)?.values?.forEach { it.complete(ApprovalAction.REJECT) }
        running.remove(requestId)?.cancel()
        diagnosticLogger("Claude CLI cancelled: requestId=$requestId")
    }

    /** 构造发回 Claude CLI stdin 的 control_response JSON 行。 */
    private fun buildControlResponse(approvalRequestId: String, decision: ApprovalAction): String {
        val behavior = when (decision) {
            ApprovalAction.ALLOW, ApprovalAction.ALLOW_FOR_SESSION -> "allow"
            ApprovalAction.REJECT -> "deny"
        }
        val response = buildJsonObject {
            put("type", "control_response")
            put("request_id", approvalRequestId)
            put("response", buildJsonObject {
                put("subtype", "success")
                put("response", buildJsonObject {
                    put("behavior", behavior)
                })
            })
        }
        return response.toString()
    }

    /** 记录会话启动元数据，便于确认 CLI 的执行上下文。 */
    private fun logStreamStart(request: AgentRequest) {
        diagnosticLogger(
            "Claude CLI start: requestId=${request.requestId} " +
                "model=${request.model?.trim().orEmpty().ifBlank { "<auto>" }} " +
                "cwd=${request.workingDirectory} " +
                "resume=${request.remoteConversationId?.isNotBlank() == true} " +
                "contextFiles=${request.contextFiles.size} " +
                "images=${request.imageAttachments.size} files=${request.fileAttachments.size}",
        )
    }

    /** 处理 Claude CLI 的单行输出，并记录原始内容、解析结果和统一事件。 */
    private suspend fun collectLine(
        request: AgentRequest,
        channel: String,
        line: String,
        accumulator: ClaudeStreamAccumulator,
        mapper: ClaudeUnifiedEventMapper,
        emitUnified: suspend (UnifiedEvent) -> Unit,
    ) {
        diagnosticLogger("Claude CLI $channel: requestId=${request.requestId} line=${line.toLogSnippet()}")
        val rawEvent = parser.parse(line)
        if (rawEvent == null) {
            diagnosticLogger("Claude CLI ignored $channel line: requestId=${request.requestId}")
            return
        }
        diagnosticLogger(
            "Claude CLI parsed event: requestId=${request.requestId} " +
                "channel=$channel event=${rawEvent.toLogSummary()}",
        )
        val semanticEvents = accumulator.accumulate(rawEvent)
        if (semanticEvents.isEmpty()) {
            diagnosticLogger("Claude CLI emitted no semantic events: requestId=${request.requestId} channel=$channel")
            return
        }
        semanticEvents.forEach { semanticEvent ->
            diagnosticLogger(
                "Claude CLI semantic event: requestId=${request.requestId} " +
                    "channel=$channel event=${semanticEvent.toLogSummary()}",
            )
            mapper.map(semanticEvent).forEach { unified ->
                emitUnifiedEvent(request, unified, emitUnified)
            }
        }
    }

    /** 记录统一事件摘要，再向上游发射。 */
    private suspend fun emitUnifiedEvent(
        request: AgentRequest,
        event: UnifiedEvent,
        emitUnified: suspend (UnifiedEvent) -> Unit,
    ) {
        diagnosticLogger(
            "Claude CLI emit unified event: requestId=${request.requestId} event=${event.toLogSummary()}",
        )
        emitUnified(event)
    }

    /** 将原始输出裁剪成适合日志阅读的单行片段。 */
    private fun String.toLogSnippet(): String {
        return replace("\n", "\\n").take(LOG_SNIPPET_LIMIT)
    }

    /** 生成 Claude 原始事件的紧凑摘要。 */
    private fun ClaudeStreamEvent.toLogSummary(): String {
        return when (this) {
            is ClaudeStreamEvent.AssistantSnapshot -> {
                "assistant sessionId=${sessionId.orEmpty()} messageId=${messageId.orEmpty()} content=${content.size}"
            }

            is ClaudeStreamEvent.ContentBlockDelta -> {
                "content-block-delta sessionId=${sessionId.orEmpty()} index=$index delta=${delta::class.java.simpleName}"
            }

            is ClaudeStreamEvent.ContentBlockStarted -> {
                "content-block-start sessionId=${sessionId.orEmpty()} index=$index block=${block::class.java.simpleName}"
            }

            is ClaudeStreamEvent.ContentBlockStopped -> {
                "content-block-stop sessionId=${sessionId.orEmpty()} index=$index"
            }

            is ClaudeStreamEvent.Error -> {
                "error sessionId=${sessionId.orEmpty()} message=${message.toLogSnippet()}"
            }

            is ClaudeStreamEvent.MessageDelta -> {
                "message-delta sessionId=${sessionId.orEmpty()} stopReason=${stopReason.orEmpty()} outputTokens=${usage?.outputTokens ?: 0}"
            }

            is ClaudeStreamEvent.MessageStart -> {
                "message-start sessionId=${sessionId.orEmpty()} messageId=$messageId model=${model.orEmpty()}"
            }

            is ClaudeStreamEvent.MessageStopped -> {
                "message-stop sessionId=${sessionId.orEmpty()}"
            }

            is ClaudeStreamEvent.Result -> {
                "result sessionId=${sessionId.orEmpty()} subtype=${subtype.orEmpty()} " +
                    "isError=$isError result=${resultText.orEmpty().toLogSnippet()}"
            }

            is ClaudeStreamEvent.SessionStarted -> {
                "system-init sessionId=$sessionId model=${model.orEmpty()}"
            }

            is ClaudeStreamEvent.UserToolResult -> {
                "tool-result sessionId=${sessionId.orEmpty()} toolUseId=$toolUseId isError=$isError content=${content.toLogSnippet()}"
            }

            is ClaudeStreamEvent.ControlRequest -> {
                "control-request sessionId=${sessionId.orEmpty()} requestId=$requestId toolName=$toolName"
            }
        }
    }

    /** 生成语义层事件的紧凑摘要，便于比对聚合层是否漏掉真实过程。 */
    private fun ClaudeConversationEvent.toLogSummary(): String {
        return when (this) {
            is ClaudeConversationEvent.AssistantErrorCaptured -> {
                "assistant-error message=${message.toLogSnippet()}"
            }

            is ClaudeConversationEvent.AssistantTextUpdated -> {
                "assistant-text messageId=$messageId completed=$completed text=${text.toLogSnippet()}"
            }

            is ClaudeConversationEvent.Completed -> {
                "completed isError=$isError model=${model.orEmpty()} result=${resultText.orEmpty().toLogSnippet()}"
            }

            is ClaudeConversationEvent.Error -> {
                "error message=${message.toLogSnippet()}"
            }

            is ClaudeConversationEvent.ReasoningUpdated -> {
                "reasoning messageId=$messageId blockIndex=$blockIndex completed=$completed text=${text.toLogSnippet()}"
            }

            is ClaudeConversationEvent.SessionStarted -> {
                "session-started sessionId=$sessionId model=${model.orEmpty()}"
            }

            is ClaudeConversationEvent.ToolCallUpdated -> {
                "tool-call toolUseId=$toolUseId name=$toolName completed=$completed isError=$isError"
            }

            is ClaudeConversationEvent.UsageUpdated -> {
                "usage model=${model.orEmpty()} inputTokens=$inputTokens outputTokens=$outputTokens contextWindow=${contextWindow ?: 0}"
            }

            is ClaudeConversationEvent.PermissionRequested -> {
                "permission-requested requestId=$requestId toolName=$toolName"
            }
        }
    }

    /** 生成统一事件的紧凑摘要，便于比对 UI 是否收到收尾事件。 */
    private fun UnifiedEvent.toLogSummary(): String {
        return when (this) {
            is UnifiedEvent.Error -> "error terminal=$terminal message=${message.toLogSnippet()}"
            is UnifiedEvent.ItemUpdated -> {
                "item kind=${item.kind} status=${item.status} name=${item.name.orEmpty()} " +
                    "text=${item.text.orEmpty().toLogSnippet()}"
            }

            is UnifiedEvent.ThreadStarted -> "thread-started threadId=$threadId"
            is UnifiedEvent.TurnCompleted -> "turn-completed turnId=$turnId outcome=$outcome"
            is UnifiedEvent.TurnStarted -> "turn-started turnId=$turnId threadId=${threadId.orEmpty()}"
            is UnifiedEvent.ApprovalRequested -> "approval-request requestId=${request.requestId}"
            is UnifiedEvent.RunningPlanUpdated -> "running-plan turnId=$turnId steps=${steps.size}"
            is UnifiedEvent.SubagentsUpdated -> "subagents-updated count=${agents.size}"
            is UnifiedEvent.ThreadTokenUsageUpdated -> {
                "token-usage threadId=$threadId turnId=${turnId.orEmpty()} outputTokens=$outputTokens"
            }

            is UnifiedEvent.ToolUserInputRequested -> "tool-user-input requestId=${prompt.requestId}"
            is UnifiedEvent.ToolUserInputResolved -> "tool-user-input-resolved requestId=$requestId"
            is UnifiedEvent.TurnDiffUpdated -> "turn-diff turnId=$turnId"
        }
    }

    companion object {
        private const val LOG_SNIPPET_LIMIT: Int = 4000
        private val LOG = Logger.getInstance(ClaudeCliProvider::class.java)
    }
}
