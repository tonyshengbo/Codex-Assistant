package com.auracode.assistant.session.kernel

/**
 * Describes one normalized domain event emitted into the session kernel.
 */
internal sealed interface SessionDomainEvent {
    /** Announces that the session is now bound to a remote thread identifier. */
    data class ThreadStarted(
        val threadId: String,
    ) : SessionDomainEvent

    /** Announces that a new turn is now active for the session. */
    data class TurnStarted(
        val turnId: String,
        val threadId: String? = null,
        val startedAtMs: Long? = null,
    ) : SessionDomainEvent

    /** Appends or updates one structured message in the conversation stream. */
    data class MessageAppended(
        val messageId: String,
        val turnId: String?,
        val role: SessionMessageRole,
        val text: String,
        val attachments: List<SessionMessageAttachment> = emptyList(),
    ) : SessionDomainEvent

    /** Upserts one structured reasoning activity in the conversation stream. */
    data class ReasoningUpdated(
        val itemId: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val text: String,
    ) : SessionDomainEvent

    /** Upserts one structured command activity in the conversation stream. */
    data class CommandUpdated(
        val itemId: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val commandKind: SessionCommandKind,
        val command: String?,
        val cwd: String?,
        val outputText: String?,
    ) : SessionDomainEvent

    /** Upserts one structured tool activity in the conversation stream. */
    data class ToolUpdated(
        val itemId: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val toolKind: SessionToolKind,
        val toolName: String,
        val summary: String?,
    ) : SessionDomainEvent

    /** Upserts one structured file-change activity in the conversation stream. */
    data class FileChangesUpdated(
        val itemId: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val summary: String,
        val changes: List<SessionFileChange>,
    ) : SessionDomainEvent

    /** Enqueues a structured approval request for the execution slice. */
    data class ApprovalRequested(
        val request: SessionApprovalRequest,
    ) : SessionDomainEvent

    /** Resolves one structured approval request and records the final decision. */
    data class ApprovalResolved(
        val requestId: String,
        val decision: SessionApprovalDecision,
    ) : SessionDomainEvent

    /** Enqueues a structured tool user-input request for the execution slice. */
    data class ToolUserInputRequested(
        val request: SessionToolUserInputRequest,
    ) : SessionDomainEvent

    /** Resolves one structured tool user-input request with a terminal status. */
    data class ToolUserInputResolved(
        val requestId: String,
        val status: SessionActivityStatus,
        val responseSummary: String?,
    ) : SessionDomainEvent

    /** Updates the currently visible running plan for the execution slice. */
    data class RunningPlanUpdated(
        val plan: SessionRunningPlan,
    ) : SessionDomainEvent

    /** Tracks the latest edited-file snapshot for submission-facing UI without creating timeline entries. */
    data class EditedFilesTracked(
        val threadId: String,
        val turnId: String,
        val changes: List<SessionFileChange>,
    ) : SessionDomainEvent

    /** Updates the latest usage counters captured for the active session thread. */
    data class UsageUpdated(
        val threadId: String?,
        val turnId: String?,
        val model: String?,
        val contextWindow: Int,
        val inputTokens: Int,
        val cachedInputTokens: Int,
        val outputTokens: Int,
    ) : SessionDomainEvent

    /** Replaces the visible session subagent snapshot list. */
    data class SubagentsUpdated(
        val threadId: String?,
        val turnId: String?,
        val agents: List<SessionSubagentSnapshot>,
    ) : SessionDomainEvent

    /** Appends a local system boundary when the current session switches to a different engine. */
    data class EngineSwitched(
        val itemId: String,
        val targetEngineLabel: String,
        val timestamp: Long,
    ) : SessionDomainEvent

    /** Appends one terminal provider error to the conversation stream. */
    data class ErrorAppended(
        val itemId: String,
        val turnId: String?,
        val message: String,
        val terminal: Boolean = true,
    ) : SessionDomainEvent

    /** Marks the active turn as finished with a terminal outcome. */
    data class TurnCompleted(
        val turnId: String,
        val outcome: SessionTurnOutcome,
    ) : SessionDomainEvent
}

/**
 * Describes the role of one conversation message entry.
 */
internal enum class SessionMessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

/**
 * Describes the lifecycle state of one conversation activity entry.
 */
internal enum class SessionActivityStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
}

/**
 * Describes the structured category of one command activity.
 */
internal enum class SessionCommandKind {
    SHELL,
    READ_FILE,
}

/**
 * Describes the structured category of one tool activity.
 */
internal enum class SessionToolKind {
    FILE_READ,
    FILE_WRITE,
    FILE_EDIT,
    PLAN_UPDATE,
    GENERIC,
}

/**
 * Describes the terminal outcome of one session turn.
 */
internal enum class SessionTurnOutcome {
    SUCCESS,
    FAILED,
    CANCELLED,
}

/**
 * Describes the category of one structured approval request.
 */
internal enum class SessionApprovalRequestKind {
    COMMAND,
    FILE_CHANGE,
    PERMISSIONS,
}

/**
 * Describes the resolved decision taken for one structured approval request.
 */
internal enum class SessionApprovalDecision {
    ALLOW,
    REJECT,
    ALLOW_FOR_SESSION,
}

/**
 * Stores one normalized approval request in the execution slice.
 */
internal data class SessionApprovalRequest(
    val requestId: String,
    val turnId: String?,
    val itemId: String,
    val kind: SessionApprovalRequestKind,
    val titleKey: String,
    val body: String,
    val command: String? = null,
    val cwd: String? = null,
    val permissions: List<String> = emptyList(),
    val allowForSession: Boolean = true,
)

/**
 * Stores one normalized tool user-input request in the execution slice.
 */
internal data class SessionToolUserInputRequest(
    val requestId: String,
    val threadId: String,
    val turnId: String?,
    val itemId: String,
    val questions: List<SessionToolUserInputQuestion>,
)

/**
 * Stores one normalized question inside a tool user-input request.
 */
internal data class SessionToolUserInputQuestion(
    val id: String,
    val headerKey: String,
    val promptKey: String,
    val options: List<SessionToolUserInputOption>,
    val isOther: Boolean = false,
    val isSecret: Boolean = false,
)

/**
 * Stores one normalized option inside a tool user-input question.
 */
internal data class SessionToolUserInputOption(
    val label: String,
    val description: String,
)

/**
 * Stores one structured file change in the session domain.
 */
internal data class SessionFileChange(
    val path: String,
    val kind: String,
    val summary: String,
    val displayName: String = path.substringAfterLast('/').substringAfterLast('\\').ifBlank { path },
    val updatedAtMs: Long? = null,
    val addedLines: Int? = null,
    val deletedLines: Int? = null,
    val unifiedDiff: String? = null,
    val oldContent: String? = null,
    val newContent: String? = null,
)

/**
 * Describes the normalized runtime status of one session subagent.
 */
internal enum class SessionSubagentStatus {
    ACTIVE,
    IDLE,
    PENDING,
    FAILED,
    COMPLETED,
    UNKNOWN,
}

/**
 * Stores one normalized subagent snapshot in the session domain.
 */
internal data class SessionSubagentSnapshot(
    val threadId: String,
    val displayName: String,
    val mentionSlug: String,
    val status: SessionSubagentStatus,
    val statusText: String,
    val summary: String? = null,
    val updatedAt: Long = 0L,
)

/**
 * Stores one normalized message attachment in the session domain.
 */
internal data class SessionMessageAttachment(
    val id: String,
    val kind: String,
    val displayName: String,
    val assetPath: String,
    val originalPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: SessionActivityStatus,
)

/**
 * Stores the active running plan in the session domain.
 */
internal data class SessionRunningPlan(
    val turnId: String?,
    val explanation: String?,
    val steps: List<SessionRunningPlanStep>,
    val body: String,
    val presentation: SessionRunningPlanPresentation = SessionRunningPlanPresentation.TIMELINE,
)

/**
 * Stores one structured running-plan step in the session domain.
 */
internal data class SessionRunningPlanStep(
    val step: String,
    val status: String,
)

/**
 * Describes which primary UI surface should render one session running plan.
 */
internal enum class SessionRunningPlanPresentation {
    TIMELINE,
    SUBMISSION_PANEL,
}
