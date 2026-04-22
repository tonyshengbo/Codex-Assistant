package com.auracode.assistant.provider.claude

/**
 * 表示 Claude 流里的 token usage 快照。
 */
internal data class ClaudeTokenUsage(
    val inputTokens: Int,
    val cachedInputTokens: Int,
    val outputTokens: Int,
)

/**
 * 表示 Claude result.modelUsage 中的单模型用量信息。
 */
internal data class ClaudeModelUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
)

/**
 * 表示 stream_event.content_block_start 里的块类型。
 */
internal sealed interface ClaudeContentBlockStart {
    /** 表示工具调用块开始。 */
    data class ToolUse(
        val toolUseId: String,
        val name: String,
        val inputJson: String,
    ) : ClaudeContentBlockStart

    /** 表示思考块开始。 */
    data class Thinking(
        val thinking: String,
    ) : ClaudeContentBlockStart

    /** 表示正文文本块开始。 */
    data class Text(
        val text: String,
    ) : ClaudeContentBlockStart
}

/**
 * 表示 stream_event.content_block_delta 里的增量类型。
 */
internal sealed interface ClaudeContentDelta {
    /** 表示工具输入 JSON 增量。 */
    data class InputJson(
        val partialJson: String,
    ) : ClaudeContentDelta

    /** 表示 thinking 文本增量。 */
    data class Thinking(
        val thinking: String,
    ) : ClaudeContentDelta

    /** 表示 assistant 正文文本增量。 */
    data class Text(
        val text: String,
    ) : ClaudeContentDelta

    /** 表示 thinking 签名增量。 */
    data class Signature(
        val signature: String,
    ) : ClaudeContentDelta
}

/**
 * 表示 assistant/user 快照消息里的 content 块。
 */
internal sealed interface ClaudeMessageContent {
    /** 表示 assistant 快照中的工具调用块。 */
    data class ToolUse(
        val toolUseId: String,
        val name: String,
        val inputJson: String,
    ) : ClaudeMessageContent

    /** 表示 assistant 快照中的 thinking 块。 */
    data class Thinking(
        val text: String,
    ) : ClaudeMessageContent

    /** 表示 assistant 快照中的正文文本块。 */
    data class Text(
        val text: String,
    ) : ClaudeMessageContent

    /** 表示 user 快照中的工具结果块。 */
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean,
    ) : ClaudeMessageContent
}

/**
 * 表示 Claude CLI 输出的一条原始结构化事件。
 */
internal sealed interface ClaudeStreamEvent {
    /** 表示 Claude CLI 已返回会话初始化信息。 */
    data class SessionStarted(
        val sessionId: String,
        val model: String? = null,
    ) : ClaudeStreamEvent

    /** 表示一条 assistant 消息开始。 */
    data class MessageStart(
        val sessionId: String? = null,
        val messageId: String,
        val model: String? = null,
        val usage: ClaudeTokenUsage? = null,
    ) : ClaudeStreamEvent

    /** 表示内容块开始。 */
    data class ContentBlockStarted(
        val sessionId: String? = null,
        val index: Int,
        val block: ClaudeContentBlockStart,
    ) : ClaudeStreamEvent

    /** 表示内容块增量。 */
    data class ContentBlockDelta(
        val sessionId: String? = null,
        val index: Int,
        val delta: ClaudeContentDelta,
    ) : ClaudeStreamEvent

    /** 表示内容块结束。 */
    data class ContentBlockStopped(
        val sessionId: String? = null,
        val index: Int,
    ) : ClaudeStreamEvent

    /** 表示消息级 delta，例如 stop_reason 与 usage。 */
    data class MessageDelta(
        val sessionId: String? = null,
        val stopReason: String? = null,
        val usage: ClaudeTokenUsage? = null,
    ) : ClaudeStreamEvent

    /** 表示消息级 stop。 */
    data class MessageStopped(
        val sessionId: String? = null,
    ) : ClaudeStreamEvent

    /** 表示 Claude 返回的一帧 assistant 快照。 */
    data class AssistantSnapshot(
        val sessionId: String? = null,
        val messageId: String? = null,
        val content: List<ClaudeMessageContent>,
        val errorType: String? = null,
    ) : ClaudeStreamEvent

    /** 表示 user 消息里的工具结果。 */
    data class UserToolResult(
        val sessionId: String? = null,
        val toolUseId: String,
        val content: String,
        val isError: Boolean,
    ) : ClaudeStreamEvent

    /** 表示 Claude CLI 返回的一帧结果收尾事件。 */
    data class Result(
        val sessionId: String? = null,
        val subtype: String? = null,
        val resultText: String? = null,
        val isError: Boolean = false,
        val usage: ClaudeTokenUsage? = null,
        val modelUsage: Map<String, ClaudeModelUsage> = emptyMap(),
    ) : ClaudeStreamEvent

    /** 表示 Claude CLI 返回的显式错误事件。 */
    data class Error(
        val message: String,
        val sessionId: String? = null,
    ) : ClaudeStreamEvent
}
