package com.auracode.assistant.session.projection

import com.auracode.assistant.session.kernel.SessionState
import com.auracode.assistant.session.projection.conversation.ConversationProjection
import com.auracode.assistant.session.projection.conversation.ConversationProjectionBuilder
import com.auracode.assistant.session.projection.execution.ExecutionProjection
import com.auracode.assistant.session.projection.execution.ExecutionProjectionBuilder
import com.auracode.assistant.session.projection.submission.SubmissionProjection
import com.auracode.assistant.session.projection.submission.SubmissionProjectionBuilder

/**
 * Stores the aggregated UI projection derived from one immutable session state snapshot.
 */
internal data class SessionProjection(
    val conversation: ConversationProjection,
    val execution: ExecutionProjection,
    val submission: SubmissionProjection,
)

/**
 * Aggregates feature projections so toolwindow code consumes one read-only projection graph.
 */
internal class SessionProjectionBuilder(
    private val conversationProjectionBuilder: ConversationProjectionBuilder = ConversationProjectionBuilder(),
    private val executionProjectionBuilder: ExecutionProjectionBuilder = ExecutionProjectionBuilder(),
    private val submissionProjectionBuilder: SubmissionProjectionBuilder = SubmissionProjectionBuilder(),
) {
    /** Projects one immutable kernel snapshot into the read-only UI projection graph. */
    fun project(state: SessionState): SessionProjection {
        return SessionProjection(
            conversation = conversationProjectionBuilder.project(state),
            execution = executionProjectionBuilder.project(state),
            submission = submissionProjectionBuilder.project(state),
        )
    }
}
