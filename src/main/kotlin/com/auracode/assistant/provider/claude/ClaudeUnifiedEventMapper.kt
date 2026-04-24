package com.auracode.assistant.provider.claude

import com.auracode.assistant.model.AgentRequest
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.TurnUsage
import com.auracode.assistant.protocol.UnifiedApprovalRequest
import com.auracode.assistant.protocol.UnifiedApprovalRequestKind
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedItem

/**
 * 将 Claude 语义事件映射为 Aura 统一事件。
 */
internal class ClaudeUnifiedEventMapper(
    private val request: AgentRequest,
    private val toolCallItemMapper: ClaudeToolCallItemMapper = ClaudeToolCallItemMapper(),
) {
    private val turnId: String = "claude:${request.requestId}"
    private val fallbackAssistantItemId: String = "${request.requestId}:assistant"
    private var threadId: String? = request.remoteConversationId?.trim()?.takeIf { it.isNotBlank() }
    private var currentModel: String? = request.model?.trim()?.takeIf { it.isNotBlank() }
    private var threadStartedEmitted: Boolean = false
    private var turnStartedEmitted: Boolean = false
    private var completed: Boolean = false
    private var latestAssistantText: String = ""
    private var latestAssistantMessageId: String? = null
    private var latestErrorMessage: String? = null

    /** 将 Claude 语义事件映射为统一事件。 */
    fun map(event: ClaudeConversationEvent): List<UnifiedEvent> {
        return when (event) {
            is ClaudeConversationEvent.SessionStarted -> {
                threadId = event.sessionId
                currentModel = event.model?.trim()?.takeIf { it.isNotBlank() } ?: currentModel
                buildList {
                    maybeEmitThreadStarted(this)
                }
            }

            is ClaudeConversationEvent.ToolCallUpdated -> {
                buildList {
                    maybeEmitThreadStarted(this)
                    maybeEmitTurnStarted(this)
                    if (event.toolName.trim().equals("TodoWrite", ignoreCase = true)) {
                        val plan = toolCallItemMapper.mapTodoWritePlan(event)
                        add(
                            UnifiedEvent.RunningPlanUpdated(
                                threadId = threadId,
                                turnId = turnId,
                                explanation = null,
                                steps = plan.steps,
                                body = plan.body,
                            ),
                        )
                    } else {
                        add(UnifiedEvent.ItemUpdated(toolCallItemMapper.map(ownerId = request.requestId, event = event)))
                    }
                }
            }

            is ClaudeConversationEvent.ReasoningUpdated -> {
                buildList {
                    maybeEmitThreadStarted(this)
                    maybeEmitTurnStarted(this)
                    add(
                        UnifiedEvent.ItemUpdated(
                            UnifiedItem(
                                id = "${request.requestId}:reasoning:${event.messageId}:${event.blockIndex}",
                                kind = ItemKind.NARRATIVE,
                                status = if (event.completed) ItemStatus.SUCCESS else ItemStatus.RUNNING,
                                name = "reasoning",
                                text = event.text,
                            ),
                        ),
                    )
                }
            }

            is ClaudeConversationEvent.AssistantTextUpdated -> {
                val (thinkingText, mainText) = splitThinkingFromText(event.text)
                latestAssistantText = mainText.ifBlank { event.text }
                latestAssistantMessageId = event.messageId.trim().takeIf { it.isNotBlank() } ?: latestAssistantMessageId
                buildList {
                    maybeEmitThreadStarted(this)
                    maybeEmitTurnStarted(this)
                    if (thinkingText.isNotBlank()) {
                        val thinkingComplete = event.completed || event.text.contains("</thinking>")
                        add(
                            UnifiedEvent.ItemUpdated(
                                UnifiedItem(
                                    id = "${request.requestId}:reasoning:${event.messageId}",
                                    kind = ItemKind.NARRATIVE,
                                    status = if (thinkingComplete) ItemStatus.SUCCESS else ItemStatus.RUNNING,
                                    name = "reasoning",
                                    text = thinkingText,
                                ),
                            ),
                        )
                    }
                    if (mainText.isNotBlank()) {
                        add(
                            UnifiedEvent.ItemUpdated(
                                UnifiedItem(
                                    id = assistantItemId(event.messageId),
                                    kind = ItemKind.NARRATIVE,
                                    status = if (event.completed) ItemStatus.SUCCESS else ItemStatus.RUNNING,
                                    name = "message",
                                    text = mainText,
                                ),
                            ),
                        )
                    }
                }
            }

            is ClaudeConversationEvent.AssistantErrorCaptured -> {
                latestErrorMessage = event.message.trim().takeIf { it.isNotBlank() } ?: latestErrorMessage
                emptyList()
            }

            is ClaudeConversationEvent.UsageUpdated -> {
                currentModel = event.model?.trim()?.takeIf { it.isNotBlank() } ?: currentModel
                buildList {
                    maybeEmitThreadStarted(this)
                    maybeEmitTurnStarted(this)
                    val currentThreadId = threadId?.trim().orEmpty()
                    if (currentThreadId.isNotBlank()) {
                        add(
                            UnifiedEvent.ThreadTokenUsageUpdated(
                                threadId = currentThreadId,
                                turnId = turnId,
                                contextWindow = event.contextWindow ?: ClaudeModelCatalog.contextWindow(currentModel),
                                inputTokens = event.inputTokens,
                                cachedInputTokens = event.cachedInputTokens,
                                outputTokens = event.outputTokens,
                            ),
                        )
                    }
                }
            }

            is ClaudeConversationEvent.Completed -> {
                completed = true
                currentModel = event.model?.trim()?.takeIf { it.isNotBlank() } ?: currentModel
                latestAssistantMessageId = event.messageId?.trim()?.takeIf { it.isNotBlank() } ?: latestAssistantMessageId
                if (event.isError) {
                    latestErrorMessage = event.resultText?.trim()?.takeIf { it.isNotBlank() }
                        ?: latestErrorMessage
                        ?: "Claude CLI returned an error result."
                }
                buildList {
                    maybeEmitThreadStarted(this)
                    maybeEmitTurnStarted(this)
                    val rawFinalText = event.resultText?.trim().orEmpty().ifBlank { latestAssistantText }
                    val (_, finalText) = splitThinkingFromText(rawFinalText)
                    if (!event.isError && finalText.isNotBlank()) {
                        latestAssistantText = finalText
                        add(
                            UnifiedEvent.ItemUpdated(
                                UnifiedItem(
                                    id = latestAssistantItemId(),
                                    kind = ItemKind.NARRATIVE,
                                    status = ItemStatus.SUCCESS,
                                    name = "message",
                                    text = finalText,
                                ),
                            ),
                        )
                    }
                    if (event.isError) {
                        add(UnifiedEvent.Error(latestErrorMessage ?: "Claude CLI request failed."))
                        add(
                            UnifiedEvent.TurnCompleted(
                                turnId = turnId,
                                outcome = TurnOutcome.FAILED,
                                usage = event.usage?.toTurnUsage(),
                            ),
                        )
                    } else {
                        add(
                            UnifiedEvent.TurnCompleted(
                                turnId = turnId,
                                outcome = TurnOutcome.SUCCESS,
                                usage = event.usage?.toTurnUsage(),
                            ),
                        )
                    }
                }
            }

            is ClaudeConversationEvent.Error -> {
                latestErrorMessage = event.message
                buildList {
                    maybeEmitThreadStarted(this)
                    maybeEmitTurnStarted(this)
                    add(UnifiedEvent.Error(event.message))
                    add(UnifiedEvent.TurnCompleted(turnId = turnId, outcome = TurnOutcome.FAILED))
                }.also { completed = true }
            }

            is ClaudeConversationEvent.PermissionRequested -> {
                buildList {
                    maybeEmitThreadStarted(this)
                    maybeEmitTurnStarted(this)
                    add(
                        UnifiedEvent.ApprovalRequested(
                            request = buildApprovalRequest(event),
                        ),
                    )
                }
            }
        }
    }

    /** 将 PermissionRequested 语义事件转换为统一授权请求模型。 */
    private fun buildApprovalRequest(event: ClaudeConversationEvent.PermissionRequested): UnifiedApprovalRequest {
        val toolName = event.toolName
        val command = event.toolInput["command"]
        val filePath = event.toolInput["file_path"] ?: event.toolInput["path"]
        val kind = when {
            command != null -> UnifiedApprovalRequestKind.COMMAND
            filePath != null -> UnifiedApprovalRequestKind.FILE_CHANGE
            else -> UnifiedApprovalRequestKind.COMMAND
        }
        val body = when (kind) {
            UnifiedApprovalRequestKind.COMMAND -> command ?: toolName
            UnifiedApprovalRequestKind.FILE_CHANGE -> filePath ?: toolName
            UnifiedApprovalRequestKind.PERMISSIONS -> toolName
        }
        return UnifiedApprovalRequest(
            requestId = event.requestId,
            turnId = turnId,
            itemId = "${request.requestId}:approval:${event.requestId}",
            kind = kind,
            title = toolName,
            body = body,
            command = command,
            allowForSession = true,
        )
    }

    /** 在 Claude 进程退出后补发必要的终止事件。 */
    fun onProcessExit(exitCode: Int): List<UnifiedEvent> {
        if (completed) return emptyList()
        if (exitCode == 0) {
            if (!latestErrorMessage.isNullOrBlank()) {
                completed = true
                return buildList {
                    maybeEmitThreadStarted(this)
                    maybeEmitTurnStarted(this)
                    add(UnifiedEvent.Error(latestErrorMessage ?: "Claude CLI request failed."))
                    add(UnifiedEvent.TurnCompleted(turnId = turnId, outcome = TurnOutcome.FAILED))
                }
            }
            return if (latestAssistantText.isBlank()) {
                completed = true
                buildList {
                    add(UnifiedEvent.Error("Claude CLI exited without returning any assistant output."))
                    add(UnifiedEvent.TurnCompleted(turnId = turnId, outcome = TurnOutcome.FAILED))
                }
            } else {
                completed = true
                buildList {
                    maybeEmitThreadStarted(this)
                    maybeEmitTurnStarted(this)
                    add(
                        UnifiedEvent.ItemUpdated(
                            UnifiedItem(
                                id = latestAssistantItemId(),
                                kind = ItemKind.NARRATIVE,
                                status = ItemStatus.SUCCESS,
                                name = "message",
                                text = latestAssistantText,
                            ),
                        ),
                    )
                    add(UnifiedEvent.TurnCompleted(turnId = turnId, outcome = TurnOutcome.SUCCESS))
                }
            }
        }
        val message = latestErrorMessage ?: "Claude CLI exited with code $exitCode."
        completed = true
        return buildList {
            maybeEmitThreadStarted(this)
            maybeEmitTurnStarted(this)
            add(UnifiedEvent.Error(message))
            add(UnifiedEvent.TurnCompleted(turnId = turnId, outcome = TurnOutcome.FAILED))
        }
    }

    /** 根据 Claude messageId 生成 assistant narrative item id；缺失时回退到稳定兜底值。 */
    private fun assistantItemId(messageId: String?): String {
        val normalizedMessageId = messageId?.trim().orEmpty()
        return if (normalizedMessageId.isBlank()) {
            fallbackAssistantItemId
        } else {
            "${request.requestId}:assistant:$normalizedMessageId"
        }
    }

    /** 返回最后一条 assistant 消息应回写的 unified item id。 */
    private fun latestAssistantItemId(): String {
        return assistantItemId(latestAssistantMessageId)
    }

    /** 首次拿到有效 threadId 时发出 thread-started。 */
    private fun maybeEmitThreadStarted(events: MutableList<UnifiedEvent>) {
        val currentThreadId = threadId?.trim().orEmpty()
        if (threadStartedEmitted || currentThreadId.isBlank()) return
        threadStartedEmitted = true
        events.add(UnifiedEvent.ThreadStarted(threadId = currentThreadId))
    }

    /** 首次进入当前 turn 时发出 turn-started。 */
    private fun maybeEmitTurnStarted(events: MutableList<UnifiedEvent>) {
        if (turnStartedEmitted) return
        turnStartedEmitted = true
        events.add(UnifiedEvent.TurnStarted(turnId = turnId, threadId = threadId))
    }

    /** 将 Claude usage 转换为 UnifiedEvent.TurnCompleted 需要的结构。 */
    private fun ClaudeTokenUsage.toTurnUsage(): TurnUsage {
        return TurnUsage(
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
        )
    }

    /**
     * 从 assistant 文本中分离 <thinking>...</thinking> 块。
     * 返回 (thinkingContent, mainContent)。
     * 流式过程中 </thinking> 可能尚未到达，此时 mainContent 为空。
     */
    private fun splitThinkingFromText(text: String): Pair<String, String> {
        val startTag = "<thinking>"
        val endTag = "</thinking>"
        val startIdx = text.indexOf(startTag)
        if (startIdx == -1) return Pair("", text)
        val endIdx = text.indexOf(endTag)
        return if (endIdx == -1) {
            // 思考块尚未关闭，提取已有内容，主文本暂为空
            val thinking = text.substring(startIdx + startTag.length)
            Pair(thinking, "")
        } else {
            val thinking = text.substring(startIdx + startTag.length, endIdx)
            val main = text.substring(endIdx + endTag.length).trim()
            Pair(thinking, main)
        }
    }
}
