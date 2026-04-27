package com.auracode.assistant.protocol

enum class TurnOutcome {
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
}

enum class ItemKind {
    NARRATIVE,
    TOOL_CALL,
    COMMAND_EXEC,
    DIFF_APPLY,
    CONTEXT_COMPACTION,
    APPROVAL_REQUEST,
    PLAN_UPDATE,
    USER_INPUT,
    UNKNOWN,
}

enum class ItemStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
}

enum class ApprovalDecision {
    PENDING,
    APPROVED,
    REJECTED,
}

enum class ProviderApprovalRequestKind {
    COMMAND,
    FILE_CHANGE,
    PERMISSIONS,
}

data class ProviderApprovalRequest(
    val requestId: String,
    val turnId: String?,
    val itemId: String,
    val kind: ProviderApprovalRequestKind,
    val title: String,
    val body: String,
    val command: String? = null,
    val cwd: String? = null,
    val fileChanges: List<ProviderFileChange> = emptyList(),
    val permissions: List<String> = emptyList(),
    val allowForSession: Boolean = true,
)

data class ProviderToolUserInputOption(
    val label: String,
    val description: String,
)

data class ProviderToolUserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<ProviderToolUserInputOption> = emptyList(),
    val isOther: Boolean = false,
    val isSecret: Boolean = false,
)

data class ProviderToolUserInputPrompt(
    val requestId: String,
    val threadId: String,
    val turnId: String?,
    val itemId: String,
    val questions: List<ProviderToolUserInputQuestion>,
    val responseSummary: String? = null,
    val status: ItemStatus = ItemStatus.RUNNING,
)

data class ProviderToolUserInputAnswerDraft(
    val answers: List<String>,
)

data class ProviderToolUserInputSubmission(
    val answers: Map<String, ProviderToolUserInputAnswerDraft>,
)

data class TurnUsage(
    val inputTokens: Int,
    val cachedInputTokens: Int,
    val outputTokens: Int,
)

data class ProviderFileChange(
    val sourceScopedId: String,
    val turnId: String? = null,
    val path: String,
    val kind: String,
    val timestamp: Long? = null,
    val addedLines: Int? = null,
    val deletedLines: Int? = null,
    val unifiedDiff: String? = null,
    val oldContent: String? = null,
    val newContent: String? = null,
)

data class ProviderMessageAttachment(
    val id: String,
    val kind: String,
    val displayName: String,
    val assetPath: String,
    val originalPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: ItemStatus,
)

data class ProviderPlanStep(
    val step: String,
    val status: String,
)

data class ProviderItem(
    val id: String,
    val kind: ItemKind,
    val status: ItemStatus,
    val name: String? = null,
    val text: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val filePath: String? = null,
    val fileChanges: List<ProviderFileChange> = emptyList(),
    val attachments: List<ProviderMessageAttachment> = emptyList(),
    val exitCode: Int? = null,
    val approvalDecision: ApprovalDecision? = null,
    val toolUserInputPrompt: ProviderToolUserInputPrompt? = null,
)

sealed class ProviderEvent {
    data class SubagentsUpdated(
        val threadId: String?,
        val turnId: String?,
        val agents: List<ProviderAgentSnapshot>,
    ) : ProviderEvent()

    data class ApprovalRequested(
        val request: ProviderApprovalRequest,
    ) : ProviderEvent()

    data class ToolUserInputRequested(
        val prompt: ProviderToolUserInputPrompt,
    ) : ProviderEvent()

    data class ToolUserInputResolved(
        val requestId: String,
    ) : ProviderEvent()

    data class ThreadStarted(
        val threadId: String,
        val resumedFromTurnId: String? = null,
    ) : ProviderEvent()

    data class TurnStarted(
        val turnId: String,
        val threadId: String? = null,
    ) : ProviderEvent()

    data class ThreadTokenUsageUpdated(
        val threadId: String,
        val turnId: String?,
        val contextWindow: Int,
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val outputTokens: Int,
    ) : ProviderEvent()

    data class TurnDiffUpdated(
        val threadId: String,
        val turnId: String,
        val diff: String,
    ) : ProviderEvent()

    data class RunningPlanUpdated(
        val threadId: String?,
        val turnId: String,
        val explanation: String?,
        val steps: List<ProviderPlanStep>,
        val body: String,
    ) : ProviderEvent()

    data class TurnCompleted(
        val turnId: String,
        val outcome: TurnOutcome,
        val usage: TurnUsage? = null,
    ) : ProviderEvent()

    data class ItemUpdated(
        val item: ProviderItem,
    ) : ProviderEvent()

    data class Error(
        val message: String,
        val terminal: Boolean = true,
    ) : ProviderEvent()
}
