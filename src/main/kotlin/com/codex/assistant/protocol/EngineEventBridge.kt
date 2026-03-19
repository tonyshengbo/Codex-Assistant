package com.codex.assistant.protocol

import com.codex.assistant.model.EngineEvent

object EngineEventBridge {
    fun map(event: EngineEvent, requestId: String): UnifiedEvent? {
        return when (event) {
            is EngineEvent.TurnStarted -> UnifiedEvent.TurnStarted(
                turnId = event.turnId,
                threadId = event.threadId,
            )
            is EngineEvent.SessionReady -> UnifiedEvent.ThreadStarted(threadId = event.sessionId)
            is EngineEvent.NarrativeItem -> UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = scopedId(requestId, event.itemId),
                    kind = ItemKind.NARRATIVE,
                    status = if (event.completed) ItemStatus.SUCCESS else ItemStatus.RUNNING,
                    name = if (event.isUser) "user_message" else if (event.isThinking) "reasoning" else "message",
                    text = event.text,
                ),
            )
            is EngineEvent.AssistantTextDelta -> UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = scopedId(requestId, "assistant-live"),
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.RUNNING,
                    text = event.text,
                ),
            )
            is EngineEvent.ThinkingDelta -> UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = scopedId(requestId, "thinking-live"),
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.RUNNING,
                    text = event.text,
                ),
            )
            is EngineEvent.ToolCallStarted -> UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = scopedId(requestId, "tool:${event.callId ?: event.name}"),
                    kind = ItemKind.TOOL_CALL,
                    status = ItemStatus.RUNNING,
                    name = event.name,
                    text = event.input,
                ),
            )
            is EngineEvent.ToolCallFinished -> UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = scopedId(requestId, "tool:${event.callId ?: event.name}"),
                    kind = ItemKind.TOOL_CALL,
                    status = if (event.isError) ItemStatus.FAILED else ItemStatus.SUCCESS,
                    name = event.name,
                    text = event.output,
                ),
            )
            is EngineEvent.CommandProposal -> UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = scopedId(requestId, "cmd:${event.command.hashCode()}:${event.cwd.hashCode()}"),
                    kind = ItemKind.COMMAND_EXEC,
                    status = ItemStatus.RUNNING,
                    command = event.command,
                    cwd = event.cwd,
                ),
            )
            is EngineEvent.DiffProposal -> UnifiedEvent.ItemUpdated(
                UnifiedItem(
                    id = scopedId(requestId, event.itemId),
                    kind = ItemKind.DIFF_APPLY,
                    status = ItemStatus.RUNNING,
                    name = "File Changes (${event.changes.size})",
                    text = event.changes.joinToString("\n") { change ->
                        "${change.kind} ${change.path}"
                    },
                    fileChanges = event.changes.map { change ->
                        UnifiedFileChange(
                            path = change.path,
                            kind = change.kind,
                            newContent = change.newContent,
                        )
                    },
                ),
            )
            is EngineEvent.TurnUsage -> UnifiedEvent.TurnCompleted(
                turnId = "",
                outcome = TurnOutcome.SUCCESS,
                usage = TurnUsage(
                    inputTokens = event.inputTokens,
                    cachedInputTokens = event.cachedInputTokens,
                    outputTokens = event.outputTokens,
                ),
            )
            is EngineEvent.Error -> UnifiedEvent.Error(event.message)
            is EngineEvent.Completed -> UnifiedEvent.TurnCompleted(
                turnId = "",
                outcome = if (event.exitCode == 0) TurnOutcome.SUCCESS else TurnOutcome.FAILED,
                usage = null,
            )
            is EngineEvent.Status -> null
        }
    }

    private fun scopedId(requestId: String, rawId: String): String {
        val normalizedRequestId = requestId.trim()
        val normalizedRawId = rawId.trim()
        return if (normalizedRequestId.isNotBlank() && normalizedRawId.isNotBlank()) {
            "$normalizedRequestId:$normalizedRawId"
        } else {
            normalizedRawId
        }
    }
}
