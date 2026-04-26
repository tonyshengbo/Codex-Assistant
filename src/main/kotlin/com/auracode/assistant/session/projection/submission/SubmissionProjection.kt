package com.auracode.assistant.session.projection.submission

import com.auracode.assistant.session.kernel.SessionState

/**
 * Stores the projected submission-facing metadata derived from session state.
 */
internal data class SubmissionProjection(
    val isRunning: Boolean,
    val editedFileCount: Int,
)

/**
 * Projects kernel submission-related state into lightweight composer-facing metadata.
 */
internal class SubmissionProjectionBuilder {
    /** Projects one immutable session state snapshot into submission metadata. */
    fun project(state: SessionState): SubmissionProjection {
        return SubmissionProjection(
            isRunning = state.runtime.runStatus == com.auracode.assistant.session.kernel.SessionRunStatus.RUNNING,
            editedFileCount = state.editedFiles.filesByPath.size,
        )
    }
}
