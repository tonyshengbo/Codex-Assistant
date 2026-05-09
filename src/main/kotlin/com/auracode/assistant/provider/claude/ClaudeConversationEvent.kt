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
        /** cache_creation_input_tokens：本次写入缓存的 token 数，计入上下文占用。 */
        val cacheCreationInputTokens: Int = 0,
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
        /** Claude CLI 建议的权限更新列表，原始 JSON 字符串，Allow 时需回传给 CLI。 */
        val permissionSuggestions: List<String> = emptyList(),
        /** 原始 tool input 的 JSON 紧凑串，allow 时需作为 updatedInput 原样回传，否则 CLI 端 zod 校验失败。 */
        val toolInputJson: String = "{}",
    ) : ClaudeConversationEvent

    /** 表示 Claude CLI 通过 AskUserQuestion 工具请求用户输入（来自 control_request/can_use_tool）。 */
    data class ToolUserInputReceived(
        val requestId: String,
        val questionsJson: String,
    ) : ClaudeConversationEvent

    /**
     * 表示一个子 Agent 的状态快照更新。
     * 由 Agent 工具调用开始时（ACTIVE）和 task_notification 完成时（COMPLETED/FAILED）触发。
     */
    data class SubagentUpdated(
        /** 对应 Agent 工具调用的 toolUseId，用作子 Agent 的唯一标识。 */
        val toolUseId: String,
        /** 来自 inputJson.description 的人类可读名称。 */
        val displayName: String,
        /** 来自 inputJson.subagent_type，如 "Explore"、"Plan"。 */
        val subagentType: String?,
        val status: SubagentStatus,
        /** 来自 task_notification.summary，完成后填充。 */
        val summary: String? = null,
        val toolUses: Int = 0,
        val durationMs: Long = 0L,
    ) : ClaudeConversationEvent

    /** 子 Agent 的生命周期状态。 */
    enum class SubagentStatus {
        ACTIVE,
        COMPLETED,
        FAILED,
    }

    /**
     * 表示上下文压缩的状态更新（来自 system/status 事件）。
     * isCompleted=false 表示压缩进行中，true 表示已结束。
     */
    data class ContextCompactionUpdated(
        val isCompleted: Boolean,
        val isSuccess: Boolean = false,
    ) : ClaudeConversationEvent
}
