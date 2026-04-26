package com.auracode.assistant.session.kernel

/**
 * Describes one typed command that can be sent to a session kernel.
 */
internal sealed interface SessionCommand {
    /** Identifies the session that should handle the command. */
    val sessionId: String

    /** Submits a new prompt into the session runtime. */
    data class SubmitPrompt(
        override val sessionId: String,
        val prompt: String,
        val contextFilePaths: List<String> = emptyList(),
        val attachmentIds: List<String> = emptyList(),
    ) : SessionCommand

    /** Replaces the current session history with a replayable domain-event snapshot. */
    data class ReplayHistory(
        override val sessionId: String,
        val events: List<SessionDomainEvent>,
    ) : SessionCommand

    /** Prepends older history ahead of the current in-memory event log. */
    data class LoadOlderHistory(
        override val sessionId: String,
        val olderEvents: List<SessionDomainEvent>,
    ) : SessionCommand

    /** Submits an approval decision produced by the user. */
    data class SubmitApprovalDecision(
        override val sessionId: String,
        val requestId: String,
        val decision: SessionApprovalDecision,
    ) : SessionCommand

    /** Submits structured tool user-input answers produced by the user. */
    data class SubmitToolUserInput(
        override val sessionId: String,
        val requestId: String,
        val answers: Map<String, List<String>>,
    ) : SessionCommand

    /** Requests an engine switch for the target session. */
    data class RequestEngineSwitch(
        override val sessionId: String,
        val engineId: String,
    ) : SessionCommand

    /** Cancels the currently active run, if one exists. */
    data class CancelRun(
        override val sessionId: String,
        val turnId: String? = null,
    ) : SessionCommand

    /** Opens a remote conversation into the target session. */
    data class OpenRemoteConversation(
        override val sessionId: String,
        val remoteConversationId: String,
    ) : SessionCommand

    /** Deletes the target session from the runtime manager. */
    data class DeleteSession(
        override val sessionId: String,
    ) : SessionCommand

    /** Restores a previously persisted session shell. */
    data class RestoreSession(
        override val sessionId: String,
    ) : SessionCommand
}
