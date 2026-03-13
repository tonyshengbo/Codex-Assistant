package com.codex.assistant.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class AgentAction {
    CHAT
}

data class ContextFile(
    val path: String,
    val content: String,
)

data class AgentRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val engineId: String,
    val action: AgentAction = AgentAction.CHAT,
    val model: String? = null,
    val prompt: String,
    val contextFiles: List<ContextFile>,
    val workingDirectory: String,
    val cliSessionId: String? = null,
)

sealed class AgentEvent {
    data class TokenChunk(val text: String) : AgentEvent()
    data class ThinkingChunk(val text: String) : AgentEvent()
    data class ContentChunk(val text: String) : AgentEvent()
    data class ToolCall(
        val name: String,
        val input: String?,
        val callId: String? = null,
    ) : AgentEvent()
    data class ToolResult(
        val name: String,
        val output: String?,
        val callId: String? = null,
        val isError: Boolean = false,
    ) : AgentEvent()
    data class Status(val message: String) : AgentEvent()
    data class CommandProposal(val command: String, val cwd: String) : AgentEvent()
    data class DiffProposal(val filePath: String, val newContent: String) : AgentEvent()
    data class FinalMessage(val content: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data object Completed : AgentEvent()
}

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = Instant.now().toEpochMilli(),
    val timelineActionsPayload: String = "",
)

data class TurnUsageSnapshot(
    val model: String,
    val contextWindow: Int,
    val inputTokens: Int,
    val cachedInputTokens: Int,
    val outputTokens: Int,
    val capturedAt: Long = Instant.now().toEpochMilli(),
) {
    val usedTokens: Int
        get() = inputTokens + outputTokens

    fun leftPercent(): Int? {
        if (contextWindow <= 0) return null
        val remainingRatio = 1.0 - usedTokens.toDouble() / contextWindow.toDouble()
        return (remainingRatio * 100).roundToInt().coerceIn(0, 100)
    }

    fun headerLabel(): String = leftPercent()?.let { "$it% left" } ?: "--"

    fun tooltipText(): String {
        return buildString {
            append("Input ").append(formatTokenCount(inputTokens))
            append(" · Output ").append(formatTokenCount(outputTokens))
            if (cachedInputTokens > 0) {
                append("\nCached ").append(formatTokenCount(cachedInputTokens))
            }
            if (model.isNotBlank() || contextWindow > 0) {
                append("\n")
                if (model.isNotBlank()) {
                    append("Model ").append(model)
                }
                if (contextWindow > 0) {
                    if (model.isNotBlank()) append(" · ")
                    append("Context ").append(formatTokenCount(contextWindow))
                }
            }
            append("\nCaptured from latest completed turn")
        }
    }

    private fun formatTokenCount(value: Int): String {
        return String.format(Locale.US, "%,d", value)
    }
}

fun MessageRole.label(): String = when (this) {
    MessageRole.USER -> "You"
    MessageRole.ASSISTANT -> "Agent"
    MessageRole.SYSTEM -> "System"
}

sealed class EngineEvent {
    data class AssistantTextDelta(val text: String) : EngineEvent()
    data class ThinkingDelta(val text: String) : EngineEvent()
    data class SessionReady(val sessionId: String) : EngineEvent()
    data class TurnUsage(
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val outputTokens: Int,
    ) : EngineEvent()
    data class ToolCallStarted(
        val callId: String?,
        val name: String,
        val input: String?,
    ) : EngineEvent()
    data class ToolCallFinished(
        val callId: String?,
        val name: String,
        val output: String?,
        val isError: Boolean = false,
    ) : EngineEvent()
    data class Status(val message: String) : EngineEvent()
    data class CommandProposal(val command: String, val cwd: String) : EngineEvent()
    data class DiffProposal(val filePath: String, val newContent: String) : EngineEvent()
    data class Error(val message: String) : EngineEvent()
    data class Completed(val exitCode: Int) : EngineEvent()
}

@Serializable
enum class TimelineNarrativeKind {
    NOTE,
    RESULT,
}

@Serializable
enum class TimelineActionStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
}

@Serializable
sealed class TimelineAction {
    @Serializable
    @SerialName("append_narrative")
    data class AppendNarrative(
        val id: String,
        val kind: TimelineNarrativeKind,
        val text: String,
        val sequence: Int,
        val timestampMs: Long? = null,
    ) : TimelineAction()

    @Serializable
    @SerialName("append_thinking")
    data class AppendThinking(
        val id: String = "thinking",
        val text: String,
        val timestampMs: Long? = null,
    ) : TimelineAction()

    @Serializable
    @SerialName("upsert_tool")
    data class UpsertTool(
        val id: String,
        val name: String,
        val input: String = "",
        val output: String = "",
        val status: TimelineActionStatus,
        val sequence: Int,
        val timestampMs: Long? = null,
    ) : TimelineAction()

    @Serializable
    @SerialName("command_proposal")
    data class CommandProposalReceived(
        val id: String,
        val command: String,
        val cwd: String,
        val sequence: Int,
        val timestampMs: Long? = null,
    ) : TimelineAction()

    @Serializable
    @SerialName("upsert_command")
    data class UpsertCommand(
        val id: String,
        val command: String,
        val cwd: String,
        val output: String = "",
        val status: TimelineActionStatus,
        val sequence: Int,
        val exitCode: Int? = null,
        val timestampMs: Long? = null,
    ) : TimelineAction()

    @Serializable
    @SerialName("diff_proposal")
    data class DiffProposalReceived(
        val filePath: String,
        val newContent: String,
        val timestampMs: Long? = null,
    ) : TimelineAction()

    @Serializable
    @SerialName("mark_failed")
    data class MarkTurnFailed(
        val message: String,
        val sequence: Int,
        val timestampMs: Long? = null,
    ) : TimelineAction()

    @Serializable
    @SerialName("finish_turn")
    data object FinishTurn : TimelineAction()
}

object TimelineActionCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encode(actions: List<TimelineAction>): String {
        if (actions.isEmpty()) return ""
        return json.encodeToString(ListSerializer(TimelineAction.serializer()), actions)
    }

    fun decode(payload: String): List<TimelineAction> {
        if (payload.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(TimelineAction.serializer()), payload)
        }.getOrElse { emptyList() }
    }
}
