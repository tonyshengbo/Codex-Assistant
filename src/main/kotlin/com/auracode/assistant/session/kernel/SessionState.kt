package com.auracode.assistant.session.kernel

/**
 * Stores the single source of truth for one session runtime.
 */
internal data class SessionState(
    val sessionId: String,
    val engineId: String,
    val runtime: SessionRuntimeState = SessionRuntimeState(),
    val conversation: SessionConversationState = SessionConversationState(),
    val submission: SessionSubmissionState = SessionSubmissionState(),
    val execution: SessionExecutionState = SessionExecutionState(),
    val editedFiles: SessionEditedFilesState = SessionEditedFilesState(),
    val usage: SessionUsageState = SessionUsageState(),
    val subagents: SessionSubagentState = SessionSubagentState(),
    val history: SessionHistoryState = SessionHistoryState(),
) {
    companion object {
        /** Creates an empty session state with only identity and engine binding populated. */
        fun empty(
            sessionId: String,
            engineId: String,
        ): SessionState {
            return SessionState(
                sessionId = sessionId,
                engineId = engineId,
            )
        }
    }
}

/**
 * Stores runtime-scoped thread, turn, and run-status information.
 */
internal data class SessionRuntimeState(
    val activeThreadId: String? = null,
    val activeTurnId: String? = null,
    val runStatus: SessionRunStatus = SessionRunStatus.IDLE,
    val lastOutcome: SessionTurnOutcome? = null,
    val turnStartedAtMs: Long? = null,
)

/**
 * Describes whether the session currently owns a running turn.
 */
internal enum class SessionRunStatus {
    IDLE,
    RUNNING,
}

/**
 * Stores the ordered conversation stream for the session.
 */
internal data class SessionConversationState(
    val order: List<String> = emptyList(),
    val entries: Map<String, SessionConversationEntry> = emptyMap(),
)

/**
 * Describes one structured conversation entry stored in session state.
 */
internal sealed interface SessionConversationEntry {
    /** Identifies the stable entry id in the conversation stream. */
    val id: String

    /** Stores one normalized message entry. */
    data class Message(
        override val id: String,
        val turnId: String?,
        val role: SessionMessageRole,
        val text: String,
        val attachments: List<SessionMessageAttachment> = emptyList(),
    ) : SessionConversationEntry

    /** Stores one normalized reasoning entry. */
    data class Reasoning(
        override val id: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val text: String,
    ) : SessionConversationEntry

    /** Stores one normalized command activity entry. */
    data class Command(
        override val id: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val commandKind: SessionCommandKind,
        val command: String?,
        val cwd: String?,
        val outputText: String?,
    ) : SessionConversationEntry

    /** Stores one normalized tool activity entry. */
    data class Tool(
        override val id: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val toolKind: SessionToolKind,
        val toolName: String,
        val summary: String?,
    ) : SessionConversationEntry

    /** Stores one normalized file-change activity entry. */
    data class FileChanges(
        override val id: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val summary: String,
        val changes: List<SessionFileChange>,
    ) : SessionConversationEntry

    /** Stores one normalized approval activity entry. */
    data class Approval(
        override val id: String,
        val turnId: String?,
        val request: SessionApprovalRequest,
        val status: SessionActivityStatus,
        val decision: SessionApprovalDecision? = null,
    ) : SessionConversationEntry

    /** Stores one normalized tool user-input entry. */
    data class ToolUserInput(
        override val id: String,
        val turnId: String?,
        val request: SessionToolUserInputRequest,
        val status: SessionActivityStatus,
        val responseSummary: String? = null,
    ) : SessionConversationEntry

    /** Stores one normalized plan entry. */
    data class Plan(
        override val id: String,
        val turnId: String?,
        val status: SessionActivityStatus,
        val body: String,
    ) : SessionConversationEntry

    /** Stores one terminal error entry. */
    data class Error(
        override val id: String,
        val turnId: String?,
        val message: String,
    ) : SessionConversationEntry

    /** Stores one local engine-switch boundary entry in the conversation stream. */
    data class EngineSwitched(
        override val id: String,
        val targetEngineLabel: String,
        val timestamp: Long,
    ) : SessionConversationEntry
}

/**
 * Stores draft and pending-submission state for the session.
 */
internal data class SessionSubmissionState(
    val draftText: String = "",
)

/**
 * Stores execution-scoped prompts and requests for the session.
 */
internal data class SessionExecutionState(
    val approvalRequests: Map<String, SessionApprovalRequest> = emptyMap(),
    val toolUserInputs: Map<String, SessionToolUserInputRequest> = emptyMap(),
    val toolUserInputEntryIdsByRequestId: Map<String, String> = emptyMap(),
    val runningPlan: SessionRunningPlan? = null,
)

/**
 * Stores edited-file metadata tracked at the session level.
 */
internal data class SessionEditedFilesState(
    val filesByPath: Map<String, SessionEditedFileState> = emptyMap(),
)

/**
 * Stores one edited file snapshot in the session state.
 */
internal data class SessionEditedFileState(
    val path: String,
    val changeKind: String,
    val displayName: String,
    val threadId: String?,
    val turnId: String?,
    val addedLines: Int? = null,
    val deletedLines: Int? = null,
    val unifiedDiff: String? = null,
    val oldContent: String? = null,
    val newContent: String? = null,
    val lastUpdatedAt: Long = 0L,
)

/**
 * Stores usage metadata derived at the session level.
 */
internal data class SessionUsageState(
    val model: String? = null,
    val contextWindow: Int = 0,
    val inputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val outputTokens: Int = 0,
)

/**
 * Stores subagent snapshots tracked for the session.
 */
internal data class SessionSubagentState(
    val agents: List<SessionSubagentSnapshot> = emptyList(),
)

/**
 * Stores history paging metadata for the session.
 */
internal data class SessionHistoryState(
    val loadedHistory: Boolean = false,
    val hasOlderPage: Boolean = false,
    val oldestCursor: String? = null,
)
