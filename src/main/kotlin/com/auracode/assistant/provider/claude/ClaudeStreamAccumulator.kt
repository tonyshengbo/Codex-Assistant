package com.auracode.assistant.provider.claude

/**
 * 将 Claude 原始流事件聚合为可直接映射到 Aura 时间线的语义事件。
 */
internal class ClaudeStreamAccumulator {
    private var emittedSessionId: String? = null
    private var currentModel: String? = null
    private var currentMessageId: String? = null
    private var latestAssistantSnapshotText: String? = null
    private val blockStates = linkedMapOf<Int, BlockState>()
    private val toolStates = linkedMapOf<String, ToolCallState>()

    /** 接收一条原始 Claude 事件，并返回本次新增的语义事件。 */
    fun accumulate(event: ClaudeStreamEvent): List<ClaudeConversationEvent> {
        return buildList {
            observeSession(event = event)?.let(::add)
            when (event) {
                is ClaudeStreamEvent.SessionStarted -> Unit
                is ClaudeStreamEvent.MessageStart -> handleMessageStart(event, this)
                is ClaudeStreamEvent.ContentBlockStarted -> handleContentBlockStarted(event, this)
                is ClaudeStreamEvent.ContentBlockDelta -> handleContentBlockDelta(event, this)
                is ClaudeStreamEvent.ContentBlockStopped -> handleContentBlockStopped(event, this)
                is ClaudeStreamEvent.MessageDelta -> handleMessageDelta(event, this)
                is ClaudeStreamEvent.MessageStopped -> handleMessageStopped(event)
                is ClaudeStreamEvent.AssistantSnapshot -> handleAssistantSnapshot(event, this)
                is ClaudeStreamEvent.UserToolResult -> handleUserToolResult(event, this)
                is ClaudeStreamEvent.Result -> handleResult(event, this)
                is ClaudeStreamEvent.Error -> add(ClaudeConversationEvent.Error(message = event.message))
                is ClaudeStreamEvent.ControlRequest -> add(
                    ClaudeConversationEvent.PermissionRequested(
                        requestId = event.requestId,
                        toolName = event.toolName,
                        toolInput = event.toolInput,
                    ),
                )
            }
        }
    }

    /** 在首次拿到 sessionId 时补发语义层的 session-started。 */
    private fun observeSession(event: ClaudeStreamEvent): ClaudeConversationEvent.SessionStarted? {
        val sessionId = event.sessionIdOrNull()?.trim().orEmpty()
        if (sessionId.isBlank() || emittedSessionId == sessionId) return null
        emittedSessionId = sessionId
        currentModel = event.modelOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: currentModel
        return ClaudeConversationEvent.SessionStarted(
            sessionId = sessionId,
            model = currentModel,
        )
    }

    /** 开始一条新的 assistant 消息，并清空上一条消息的块状态。 */
    private fun handleMessageStart(
        event: ClaudeStreamEvent.MessageStart,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        currentMessageId = event.messageId
        currentModel = event.model?.trim()?.takeIf { it.isNotBlank() } ?: currentModel
        blockStates.clear()
        latestAssistantSnapshotText = null
    }

    /** 初始化当前块的状态，并在工具块开始时立刻暴露运行中节点。 */
    private fun handleContentBlockStarted(
        event: ClaudeStreamEvent.ContentBlockStarted,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        when (val block = event.block) {
            is ClaudeContentBlockStart.ToolUse -> {
                blockStates[event.index] = BlockState.ToolUse(
                    toolUseId = block.toolUseId,
                    toolName = block.name,
                    inputJson = block.inputJson,
                )
                val toolState = toolStates[block.toolUseId]
                    ?.copy(toolName = block.name, inputJson = block.inputJson.ifBlank { toolStates[block.toolUseId]?.inputJson.orEmpty() })
                    ?: ToolCallState(
                        toolUseId = block.toolUseId,
                        toolName = block.name,
                        inputJson = block.inputJson,
                )
                toolStates[block.toolUseId] = toolState
                if (toolState.inputJson.isNotBlank() && toolState.inputJson != "{}") {
                    events.add(toolState.toConversationEvent())
                }
            }

            is ClaudeContentBlockStart.Thinking -> {
                blockStates[event.index] = BlockState.Thinking(
                    messageId = currentMessageId.orEmpty(),
                    blockIndex = event.index,
                    text = block.thinking,
                )
                if (block.thinking.isNotBlank() && currentMessageId != null) {
                    events.add(
                        ClaudeConversationEvent.ReasoningUpdated(
                            messageId = currentMessageId.orEmpty(),
                            blockIndex = event.index,
                            text = block.thinking,
                            completed = false,
                        ),
                    )
                }
            }

            is ClaudeContentBlockStart.Text -> {
                blockStates[event.index] = BlockState.Text(
                    messageId = currentMessageId.orEmpty(),
                    blockIndex = event.index,
                    text = block.text,
                )
                emitAssistantTextSnapshot(events, currentMessageId.orEmpty())
            }
        }
    }

    /** 处理内容块增量，并对 reasoning、tool input、assistant text 持续输出最新快照。 */
    private fun handleContentBlockDelta(
        event: ClaudeStreamEvent.ContentBlockDelta,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        when (val delta = event.delta) {
            is ClaudeContentDelta.InputJson -> {
                val block = blockStates[event.index] as? BlockState.ToolUse ?: return
                block.inputJson = when {
                    block.inputJson.isBlank() -> delta.partialJson
                    block.inputJson == "{}" -> delta.partialJson
                    else -> block.inputJson + delta.partialJson
                }
                val updated = toolStates[block.toolUseId]
                    ?.copy(inputJson = block.inputJson)
                    ?: ToolCallState(
                        toolUseId = block.toolUseId,
                        toolName = block.toolName,
                        inputJson = block.inputJson,
                    )
                toolStates[block.toolUseId] = updated
                events.add(updated.toConversationEvent())
            }

            is ClaudeContentDelta.Thinking -> {
                val block = blockStates[event.index] as? BlockState.Thinking ?: return
                block.text += delta.thinking
                if (block.text.isNotBlank() && block.messageId.isNotBlank()) {
                    events.add(
                        ClaudeConversationEvent.ReasoningUpdated(
                            messageId = block.messageId,
                            blockIndex = block.blockIndex,
                            text = block.text,
                            completed = false,
                        ),
                    )
                }
            }

            is ClaudeContentDelta.Text -> {
                val block = blockStates[event.index] as? BlockState.Text ?: return
                block.text += delta.text
                emitAssistantTextSnapshot(events, block.messageId)
            }

            is ClaudeContentDelta.Signature -> Unit
        }
    }

    /** 在 reasoning 块结束时补发成功态快照，其它块继续等待后续事件。 */
    private fun handleContentBlockStopped(
        event: ClaudeStreamEvent.ContentBlockStopped,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        val block = blockStates[event.index] ?: return
        if (block is BlockState.Thinking && block.text.isNotBlank() && block.messageId.isNotBlank()) {
            events.add(
                ClaudeConversationEvent.ReasoningUpdated(
                    messageId = block.messageId,
                    blockIndex = block.blockIndex,
                    text = block.text,
                    completed = true,
                ),
            )
        }
    }

    /** 消息级 delta 只负责携带最新 usage，不会在此阶段结束 turn。 */
    private fun handleMessageDelta(
        event: ClaudeStreamEvent.MessageDelta,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        val usage = event.usage ?: return
        events.add(
            ClaudeConversationEvent.UsageUpdated(
                model = currentModel,
                inputTokens = usage.inputTokens,
                cachedInputTokens = usage.cachedInputTokens,
                outputTokens = usage.outputTokens,
                contextWindow = ClaudeModelCatalog.contextWindow(currentModel),
            ),
        )
    }

    /** 消息 stop 后仅清理消息级块状态，不影响工具结果等待。 */
    private fun handleMessageStopped(
        event: ClaudeStreamEvent.MessageStopped,
    ) {
        currentMessageId = null
        blockStates.clear()
        latestAssistantSnapshotText = null
    }

    /** 使用 assistant 快照兜底补齐 tool_use、thinking、text，避免日志有快照但无增量时丢失 UI 信息。 */
    private fun handleAssistantSnapshot(
        event: ClaudeStreamEvent.AssistantSnapshot,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        if (!event.errorType.isNullOrBlank()) {
            val errorText = event.content.filterIsInstance<ClaudeMessageContent.Text>().joinToString(separator = "") { it.text }
            if (errorText.isNotBlank()) {
                events.add(ClaudeConversationEvent.AssistantErrorCaptured(message = errorText))
            }
            return
        }
        currentMessageId = event.messageId?.trim()?.takeIf { it.isNotBlank() } ?: currentMessageId
        event.content.filterIsInstance<ClaudeMessageContent.ToolUse>().forEach { content ->
            mergeToolUseSnapshot(content, events)
        }
        replaceThinkingSnapshot(
            contents = event.content.filterIsInstance<ClaudeMessageContent.Thinking>(),
            events = events,
        )
        replaceTextSnapshot(
            contents = event.content.filterIsInstance<ClaudeMessageContent.Text>(),
            events = events,
        )
        event.content.filterIsInstance<ClaudeMessageContent.ToolResult>().forEach { content ->
            handleUserToolResult(
                ClaudeStreamEvent.UserToolResult(
                    sessionId = event.sessionId,
                    toolUseId = content.toolUseId,
                    content = content.content,
                    isError = content.isError,
                ),
                events,
            )
        }
    }

    /** 合并 assistant 快照中的工具调用信息。 */
    private fun mergeToolUseSnapshot(
        content: ClaudeMessageContent.ToolUse,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        val current = toolStates[content.toolUseId]
        val merged = current?.copy(
            toolName = content.name,
            inputJson = content.inputJson.ifBlank { current.inputJson },
        ) ?: ToolCallState(
            toolUseId = content.toolUseId,
            toolName = content.name,
            inputJson = content.inputJson,
        )
        val changed = current == null || current.toolName != merged.toolName || current.inputJson != merged.inputJson
        toolStates[content.toolUseId] = merged
        if (changed && merged.inputJson.isNotBlank()) {
            events.add(merged.toConversationEvent())
        }
    }

    /** 使用 assistant 快照整体替换当前消息的 thinking 块，避免索引重排导致重复拼接。 */
    private fun replaceThinkingSnapshot(
        contents: List<ClaudeMessageContent.Thinking>,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        if (contents.isEmpty()) return
        val messageId = currentMessageId?.takeIf { it.isNotBlank() } ?: return
        val existingBlocks = blockStates.values
            .filterIsInstance<BlockState.Thinking>()
            .filter { it.messageId == messageId }
            .sortedBy { it.blockIndex }
        val resolvedIndices = resolveSnapshotBlockIndices(
            existingIndices = existingBlocks.map { it.blockIndex },
            count = contents.size,
        )
        removeMessageBlocks<BlockState.Thinking>(messageId = messageId)
        contents.forEachIndexed { order, content ->
            val blockIndex = resolvedIndices[order]
            val previousText = existingBlocks.getOrNull(order)?.text
            blockStates[blockIndex] = BlockState.Thinking(
                messageId = messageId,
                blockIndex = blockIndex,
                text = content.text,
            )
            if (previousText != content.text && content.text.isNotBlank()) {
                events.add(
                    ClaudeConversationEvent.ReasoningUpdated(
                        messageId = messageId,
                        blockIndex = blockIndex,
                        text = content.text,
                        completed = false,
                    ),
                )
            }
        }
    }

    /** 使用 assistant 快照整体替换当前消息的正文块，避免旧 block 残留造成正文重复。 */
    private fun replaceTextSnapshot(
        contents: List<ClaudeMessageContent.Text>,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        if (contents.isEmpty()) return
        val messageId = currentMessageId?.takeIf { it.isNotBlank() } ?: return
        val existingBlocks = blockStates.values
            .filterIsInstance<BlockState.Text>()
            .filter { it.messageId == messageId }
            .sortedBy { it.blockIndex }
        val resolvedIndices = resolveSnapshotBlockIndices(
            existingIndices = existingBlocks.map { it.blockIndex },
            count = contents.size,
        )
        removeMessageBlocks<BlockState.Text>(messageId = messageId)
        contents.forEachIndexed { order, content ->
            val blockIndex = resolvedIndices[order]
            blockStates[blockIndex] = BlockState.Text(
                messageId = messageId,
                blockIndex = blockIndex,
                text = content.text,
            )
        }
        emitAssistantTextSnapshot(events, messageId)
    }

    /** 为 assistant 快照中的同类型内容分配稳定 block 索引，优先复用已有索引。 */
    private fun resolveSnapshotBlockIndices(
        existingIndices: List<Int>,
        count: Int,
    ): List<Int> {
        val nextIndexBase = (blockStates.keys.maxOrNull() ?: -1) + 1
        return List(count) { order ->
            existingIndices.getOrNull(order) ?: (nextIndexBase + order)
        }
    }

    /** 删除当前消息内指定类型的块状态，为 snapshot 全量替换让位。 */
    private inline fun <reified T : BlockState> removeMessageBlocks(messageId: String) {
        val iterator = blockStates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val sameMessage = when (val state = entry.value) {
                is BlockState.Text -> state.messageId == messageId
                is BlockState.Thinking -> state.messageId == messageId
                is BlockState.ToolUse -> false
            }
            if (sameMessage && entry.value is T) {
                iterator.remove()
            }
        }
    }

    /** 按 blockIndex 顺序聚合同一条 Claude message 的全部 text block，并仅在快照变化时发出事件。 */
    private fun emitAssistantTextSnapshot(
        events: MutableList<ClaudeConversationEvent>,
        messageId: String,
    ) {
        if (messageId.isBlank()) return
        val snapshot = currentAssistantMessageText(messageId)
        if (snapshot.isBlank() || snapshot == latestAssistantSnapshotText) return
        latestAssistantSnapshotText = snapshot
        events.add(
            ClaudeConversationEvent.AssistantTextUpdated(
                messageId = messageId,
                text = snapshot,
                completed = false,
            ),
        )
    }

    /** 返回当前 messageId 对应的完整 assistant 正文快照。 */
    private fun currentAssistantMessageText(messageId: String): String {
        return blockStates.entries
            .asSequence()
            .sortedBy { it.key }
            .mapNotNull { (_, blockState) ->
                (blockState as? BlockState.Text)
                    ?.takeIf { it.messageId == messageId }
                    ?.text
            }
            .joinToString(separator = "")
    }

    /** 工具结果会回写到对应的 tool_use 节点，并将其标记为完成。 */
    private fun handleUserToolResult(
        event: ClaudeStreamEvent.UserToolResult,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        val current = toolStates[event.toolUseId] ?: ToolCallState(
            toolUseId = event.toolUseId,
            toolName = event.toolUseId,
            inputJson = "",
        )
        val updated = current.copy(
            outputText = event.content,
            isError = event.isError,
            completed = true,
        )
        toolStates[event.toolUseId] = updated
        events.add(updated.toConversationEvent())
    }

    /** result 负责补齐最终 usage/contextWindow，并发出 turn 完成语义事件。 */
    private fun handleResult(
        event: ClaudeStreamEvent.Result,
        events: MutableList<ClaudeConversationEvent>,
    ) {
        val modelUsageEntry = event.modelUsage.entries.firstOrNull()
        val resolvedModel = modelUsageEntry?.key?.trim()?.takeIf { it.isNotBlank() } ?: currentModel
        currentModel = resolvedModel ?: currentModel
        val resolvedContextWindow = modelUsageEntry?.value?.contextWindow ?: ClaudeModelCatalog.contextWindow(currentModel)
        event.usage?.let { usage ->
            events.add(
                ClaudeConversationEvent.UsageUpdated(
                    model = currentModel,
                    inputTokens = usage.inputTokens,
                    cachedInputTokens = usage.cachedInputTokens,
                    outputTokens = usage.outputTokens,
                    contextWindow = resolvedContextWindow,
                ),
            )
        }
        events.add(
            ClaudeConversationEvent.Completed(
                messageId = currentMessageId,
                resultText = event.resultText,
                isError = event.isError,
                usage = event.usage,
                contextWindow = resolvedContextWindow,
                model = currentModel,
            ),
        )
    }

    /** 返回当前原始事件可能携带的 sessionId。 */
    private fun ClaudeStreamEvent.sessionIdOrNull(): String? {
        return when (this) {
            is ClaudeStreamEvent.AssistantSnapshot -> sessionId
            is ClaudeStreamEvent.ContentBlockDelta -> sessionId
            is ClaudeStreamEvent.ContentBlockStarted -> sessionId
            is ClaudeStreamEvent.ContentBlockStopped -> sessionId
            is ClaudeStreamEvent.Error -> sessionId
            is ClaudeStreamEvent.MessageDelta -> sessionId
            is ClaudeStreamEvent.MessageStart -> sessionId
            is ClaudeStreamEvent.MessageStopped -> sessionId
            is ClaudeStreamEvent.Result -> sessionId
            is ClaudeStreamEvent.SessionStarted -> sessionId
            is ClaudeStreamEvent.UserToolResult -> sessionId
            is ClaudeStreamEvent.ControlRequest -> sessionId
        }
    }

    /** 返回当前原始事件可能携带的模型信息。 */
    private fun ClaudeStreamEvent.modelOrNull(): String? {
        return when (this) {
            is ClaudeStreamEvent.MessageStart -> model
            is ClaudeStreamEvent.Result -> modelUsage.keys.firstOrNull()
            is ClaudeStreamEvent.SessionStarted -> model
            is ClaudeStreamEvent.ControlRequest -> null
            else -> null
        }
    }

    /** 表示一条正在累积中的工具调用。 */
    private data class ToolCallState(
        val toolUseId: String,
        val toolName: String,
        val inputJson: String,
        val outputText: String? = null,
        val isError: Boolean = false,
        val completed: Boolean = false,
    ) {
        /** 将工具状态转换成统一的语义层快照。 */
        fun toConversationEvent(): ClaudeConversationEvent.ToolCallUpdated {
            return ClaudeConversationEvent.ToolCallUpdated(
                toolUseId = toolUseId,
                toolName = toolName,
                inputJson = inputJson,
                outputText = outputText,
                isError = isError,
                completed = completed,
            )
        }
    }

    /** 表示当前消息内的块级状态。 */
    private sealed interface BlockState {
        /** 表示工具调用块状态。 */
        data class ToolUse(
            val toolUseId: String,
            val toolName: String,
            var inputJson: String,
        ) : BlockState

        /** 表示 reasoning 块状态。 */
        data class Thinking(
            val messageId: String,
            val blockIndex: Int,
            var text: String,
        ) : BlockState

        /** 表示正文文本块状态。 */
        data class Text(
            val messageId: String,
            val blockIndex: Int,
            var text: String,
        ) : BlockState
    }
}
