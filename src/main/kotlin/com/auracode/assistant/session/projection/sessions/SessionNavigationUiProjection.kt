package com.auracode.assistant.session.projection.sessions

import com.auracode.assistant.session.kernel.SessionState
import com.auracode.assistant.toolwindow.submission.SessionSubagentUiModel
import com.auracode.assistant.toolwindow.submission.toSessionSubagentUiModel

/**
 * Stores session-scoped navigation metadata that UI stores consume outside the timeline.
 */
internal data class SessionNavigationUiProjection(
    val subagents: List<SessionSubagentUiModel>,
)

/**
 * Projects kernel navigation metadata into lightweight session UI models.
 */
internal class SessionNavigationUiProjectionBuilder {
    /** Projects one immutable session snapshot into navigation-facing UI data. */
    fun project(state: SessionState): SessionNavigationUiProjection {
        return SessionNavigationUiProjection(
            subagents = state.subagents.agents.map { agent -> agent.toSessionSubagentUiModel() },
        )
    }
}
