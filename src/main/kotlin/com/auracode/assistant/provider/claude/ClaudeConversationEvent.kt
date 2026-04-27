package com.auracode.assistant.provider.claude

/**
 * 表示对原始 Claude 流进行聚合后，更接近 Aura UI 语义的一层事件。
 */
internal sealed interface ClaudeConversationEvent {
    /** 表示会话标识已确定，可用于发出 thread-started。 */
    data class SessionStarted(
        val sessionId: String,
        val model: String? = null,
    ) : ClaudeConversationEvent

    /** 表示工具调用节点的最新快照。 */
    data class ToolCallUpdated(
        val toolUseId: String,
        val toolName: String,
        val inputJson: String,
        val outputText: String? = null,
        val isError: Boolean = false,
        val completed: Boolean = false,
    ) : ClaudeConversationEvent

    /** 表示 reasoning 节点的最新快照。 */
    data class ReasoningUpdated(
        val messageId: String,
        val blockIndex: Int,
        val text: String,
        val completed: Boolean,
    ) : ClaudeConversationEvent

    /** 表示 assistant 正文节点的最新消息级完整快照，而不是单个 text block 的局部文本。 */
    data class AssistantTextUpdated(
        val messageId: String,
        val text: String,
        val completed: Boolean,
    ) : ClaudeConversationEvent

    /** 表示 assistant 错误快照，仅供上层记录失败信息。 */
    data class AssistantErrorCaptured(
        val message: String,
    ) : ClaudeConversationEvent

    /** 表示线程级 token usage 更新。 */
    data class UsageUpdated(
        val model: String?,
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val outputTokens: Int,
        val contextWindow: Int? = null,
    ) : ClaudeConversationEvent

    /** 表示 Claude turn 已收到最终 result。 */
    data class Completed(
        val messageId: String? = null,
        val resultText: String?,
        val isError: Boolean,
        val usage: ClaudeTokenUsage? = null,
        val contextWindow: Int? = null,
        val model: String? = null,
    ) : ClaudeConversationEvent

    /** 表示 Claude 显式返回的错误事件。 */
    data class Error(
        val message: String,
    ) : ClaudeConversationEvent

    /** Represents one retry notice that should surface as a non-terminal provider error. */
    data class RetryScheduled(
        val attempt: Int,
        val maxRetries: Int,
        val retryDelayMs: Long,
        val errorStatus: String? = null,
        val error: String? = null,
    ) : ClaudeConversationEvent

    /** 表示 Claude CLI 请求工具执行授权（来自 control_request/can_use_tool）。 */
    data class PermissionRequested(
        val requestId: String,
        val toolName: String,
        val toolInput: Map<String, String>,
    ) : ClaudeConversationEvent
}
