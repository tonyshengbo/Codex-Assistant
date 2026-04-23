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

enum class UnifiedApprovalRequestKind {
    COMMAND,
    FILE_CHANGE,
    PERMISSIONS,
}

data class UnifiedApprovalRequest(
    val requestId: String,
    val turnId: String?,
    val itemId: String,
    val kind: UnifiedApprovalRequestKind,
    val title: String,
    val body: String,
    val command: String? = null,
    val cwd: String? = null,
    val fileChanges: List<UnifiedFileChange> = emptyList(),
    val permissions: List<String> = emptyList(),
    val allowForSession: Boolean = true,
)

data class UnifiedToolUserInputOption(
    val label: String,
    val description: String,
)

data class UnifiedToolUserInputQuestion(
    val id: String,
    val header: String,
    val question: String,
    val options: List<UnifiedToolUserInputOption> = emptyList(),
    val isOther: Boolean = false,
    val isSecret: Boolean = false,
)

data class UnifiedToolUserInputPrompt(
    val requestId: String,
    val threadId: String,
    val turnId: String?,
    val itemId: String,
    val questions: List<UnifiedToolUserInputQuestion>,
)

data class UnifiedToolUserInputAnswerDraft(
    val answers: List<String>,
)

data class UnifiedToolUserInputSubmission(
    val answers: Map<String, UnifiedToolUserInputAnswerDraft>,
)

data class TurnUsage(
    val inputTokens: Int,
    val cachedInputTokens: Int,
    val outputTokens: Int,
)

data class UnifiedFileChange(
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

data class UnifiedMessageAttachment(
    val id: String,
    val kind: String,
    val displayName: String,
    val assetPath: String,
    val originalPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: ItemStatus,
)

data class UnifiedRunningPlanStep(
    val step: String,
    val status: String,
)

data class UnifiedItem(
    val id: String,
    val kind: ItemKind,
    val status: ItemStatus,
    val name: String? = null,
    val text: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val filePath: String? = null,
    val fileChanges: List<UnifiedFileChange> = emptyList(),
    val attachments: List<UnifiedMessageAttachment> = emptyList(),
    val exitCode: Int? = null,
    val approvalDecision: ApprovalDecision? = null,
)

sealed class UnifiedEvent {
    data class SubagentsUpdated(
        val threadId: String?,
        val turnId: String?,
        val agents: List<UnifiedAgentSnapshot>,
    ) : UnifiedEvent()

    data class ApprovalRequested(
        val request: UnifiedApprovalRequest,
    ) : UnifiedEvent()

    data class ToolUserInputRequested(
        val prompt: UnifiedToolUserInputPrompt,
    ) : UnifiedEvent()

    data class ToolUserInputResolved(
        val requestId: String,
    ) : UnifiedEvent()

    data class ThreadStarted(
        val threadId: String,
        val resumedFromTurnId: String? = null,
    ) : UnifiedEvent()

    data class TurnStarted(
        val turnId: String,
        val threadId: String? = null,
    ) : UnifiedEvent()

    data class ThreadTokenUsageUpdated(
        val threadId: String,
        val turnId: String?,
        val contextWindow: Int,
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val outputTokens: Int,
    ) : UnifiedEvent()

    data class TurnDiffUpdated(
        val threadId: String,
        val turnId: String,
        val diff: String,
    ) : UnifiedEvent()

    data class RunningPlanUpdated(
        val threadId: String?,
        val turnId: String,
        val explanation: String?,
        val steps: List<UnifiedRunningPlanStep>,
        val body: String,
    ) : UnifiedEvent()

    data class TurnCompleted(
        val turnId: String,
        val outcome: TurnOutcome,
        val usage: TurnUsage? = null,
    ) : UnifiedEvent()

    data class ItemUpdated(
        val item: UnifiedItem,
    ) : UnifiedEvent()

    data class Error(
        val message: String,
        val terminal: Boolean = true,
    ) : UnifiedEvent()
}
