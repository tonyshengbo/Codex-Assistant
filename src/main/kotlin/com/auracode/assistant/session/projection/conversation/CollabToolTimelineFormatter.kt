package com.auracode.assistant.session.projection.conversation

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.session.kernel.SessionCollabToolActionSource
import com.auracode.assistant.session.kernel.SessionCollabToolSemantic

/**
 * Formats collaboration tool semantics into user-facing timeline titles.
 */
internal object CollabToolTimelineFormatter {
    /**
     * Stores the projected title and collapsed summary for one collaboration card.
     */
    data class Presentation(
        val title: String,
        val collapsedSummary: String?,
    )

    /** Formats one collaboration card snapshot for timeline display. */
    fun present(
        semantic: SessionCollabToolSemantic,
    ): Presentation {
        val title = when (semantic.actionSource) {
            SessionCollabToolActionSource.COMMAND -> semantic.actionText?.trim().orEmpty()
            SessionCollabToolActionSource.PHASE,
            SessionCollabToolActionSource.FAILURE,
            -> semantic.actionKey?.let(AuraCodeBundle::message).orEmpty()
        }.ifBlank { AuraCodeBundle.message("timeline.process.working") }
        return Presentation(
            title = title,
            collapsedSummary = semantic.failureMessage?.trim()?.takeIf { it.isNotBlank() },
        )
    }
}
