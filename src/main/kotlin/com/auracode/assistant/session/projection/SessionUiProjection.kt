package com.auracode.assistant.session.projection

import com.auracode.assistant.session.kernel.SessionState
import com.auracode.assistant.session.projection.conversation.ConversationUiProjection
import com.auracode.assistant.session.projection.conversation.ConversationUiProjectionBuilder
import com.auracode.assistant.session.projection.execution.ExecutionUiProjection
import com.auracode.assistant.session.projection.execution.ExecutionUiProjectionBuilder
import com.auracode.assistant.session.projection.sessions.SessionNavigationUiProjection
import com.auracode.assistant.session.projection.sessions.SessionNavigationUiProjectionBuilder
import com.auracode.assistant.session.projection.submission.SubmissionUiProjection
import com.auracode.assistant.session.projection.submission.SubmissionUiProjectionBuilder

/**
 * Stores the aggregated UI projection derived from one immutable session state snapshot.
 */
internal data class SessionUiProjection(
    val conversation: ConversationUiProjection,
    val execution: ExecutionUiProjection,
    val submission: SubmissionUiProjection,
    val navigation: SessionNavigationUiProjection,
)

/**
 * Aggregates feature projections so toolwindow code consumes one read-only projection graph.
 */
internal class SessionUiProjectionBuilder(
    private val conversationProjectionBuilder: ConversationUiProjectionBuilder = ConversationUiProjectionBuilder(),
    private val executionProjectionBuilder: ExecutionUiProjectionBuilder = ExecutionUiProjectionBuilder(),
    private val submissionProjectionBuilder: SubmissionUiProjectionBuilder = SubmissionUiProjectionBuilder(),
    private val navigationProjectionBuilder: SessionNavigationUiProjectionBuilder = SessionNavigationUiProjectionBuilder(),
) {
    /** Projects one immutable kernel snapshot into the read-only UI projection graph. */
    fun project(state: SessionState): SessionUiProjection {
        return SessionUiProjection(
            conversation = conversationProjectionBuilder.project(state),
            execution = executionProjectionBuilder.project(state),
            submission = submissionProjectionBuilder.project(state),
            navigation = navigationProjectionBuilder.project(state),
        )
    }
}
