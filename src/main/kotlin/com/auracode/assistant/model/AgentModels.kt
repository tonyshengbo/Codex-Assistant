package com.auracode.assistant.model

import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

enum class AgentAction {
    CHAT
}

data class ContextFile(
    val path: String,
    val content: String? = null,
)

data class ImageAttachment(
    val path: String,
    val name: String,
    val mimeType: String = "image/png",
)

data class FileAttachment(
    val path: String,
    val name: String,
    val mimeType: String = "application/octet-stream",
)

enum class AgentApprovalMode {
    AUTO,
    REQUIRE_CONFIRMATION,
}

enum class AgentCollaborationMode {
    DEFAULT,
    PLAN,
}

data class AgentRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val engineId: String,
    val action: AgentAction = AgentAction.CHAT,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val prompt: String,
    val systemInstructions: List<String> = emptyList(),
    val contextFiles: List<ContextFile>,
    val imageAttachments: List<ImageAttachment> = emptyList(),
    val fileAttachments: List<FileAttachment> = emptyList(),
    val workingDirectory: String,
    val remoteConversationId: String? = null,
    val approvalMode: AgentApprovalMode = AgentApprovalMode.AUTO,
    val collaborationMode: AgentCollaborationMode = AgentCollaborationMode.DEFAULT,
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

    val effectiveUsedTokens: Int
        get() = when {
            contextWindow <= 0 -> usedTokens
            else -> usedTokens % contextWindow
        }

    fun usedPercent(): Int? {
        if (contextWindow <= 0) return null
        val usedRatio = effectiveUsedTokens.toDouble() / contextWindow.toDouble()
        return (usedRatio * 100).roundToInt().coerceIn(0, 100)
    }

    fun usedFraction(): Float? {
        if (contextWindow <= 0) return null
        return (effectiveUsedTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
    }

    fun leftPercent(): Int? {
        if (contextWindow <= 0) return null
        val remainingRatio = 1.0 - effectiveUsedTokens.toDouble() / contextWindow.toDouble()
        return (remainingRatio * 100).roundToInt().coerceIn(0, 100)
    }

    fun headerLabel(): String = leftPercent()?.let { "Est. $it% left" } ?: "Usage"

    fun contextUsageTooltipText(): String {
        return buildString {
            usedPercent()?.let { append("Used ").append(it).append("%") }
            if (contextWindow > 0) {
                if (isNotEmpty()) append("\n")
                append(formatTokenCount(effectiveUsedTokens))
                append(" / ")
                append(formatTokenCount(contextWindow))
                append(" tokens")
            }
            append("\nInput ").append(formatTokenCount(inputTokens))
            append(" · Output ").append(formatTokenCount(outputTokens))
            if (cachedInputTokens > 0) {
                append("\nCached ").append(formatTokenCount(cachedInputTokens))
                append(" (included in input)")
            }
            if (model.isNotBlank()) {
                append("\nModel ").append(model)
            }
        }
    }

    fun tooltipText(): String {
        return buildString {
            append("Input ").append(formatTokenCount(inputTokens))
            append(" · Output ").append(formatTokenCount(outputTokens))
            if (cachedInputTokens > 0) {
                append("\nCached ").append(formatTokenCount(cachedInputTokens))
                append(" (included in input)")
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
            append("\nEstimated from the latest completed turn")
            if (contextWindow > 0) {
                append("\n0% left is not a hard stop; older context may be truncated automatically")
            }
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
