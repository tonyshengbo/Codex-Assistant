package com.auracode.assistant.session.projection.submission

import com.auracode.assistant.session.kernel.SessionState
import com.auracode.assistant.toolwindow.conversation.ConversationFileChange
import com.auracode.assistant.toolwindow.conversation.ConversationFileChangeKind
import com.auracode.assistant.toolwindow.submission.EditedFileAggregate

/**
 * Stores the projected submission-facing metadata derived from session state.
 */
internal data class SubmissionUiProjection(
    val isRunning: Boolean,
    val editedFileCount: Int,
    val editedFiles: List<EditedFileAggregate>,
)

/**
 * Projects kernel submission-related state into lightweight composer-facing metadata.
 */
internal class SubmissionUiProjectionBuilder {
    /** Projects one immutable session state snapshot into submission metadata. */
    fun project(state: SessionState): SubmissionUiProjection {
        val editedFiles = state.editedFiles.filesByPath.values
            .sortedByDescending { it.lastUpdatedAt }
            .map { file ->
                EditedFileAggregate(
                    threadId = file.threadId,
                    turnId = file.turnId,
                    lastUpdatedAt = file.lastUpdatedAt,
                    change = ConversationFileChange(
                        sourceScopedId = listOfNotNull("edited-file", file.turnId, file.path).joinToString(":"),
                        path = file.path,
                        displayName = file.displayName,
                        kind = file.changeKind.toConversationFileChangeKind(),
                        timestamp = file.lastUpdatedAt,
                        addedLines = file.addedLines,
                        deletedLines = file.deletedLines,
                        unifiedDiff = file.unifiedDiff,
                        oldContent = file.oldContent,
                        newContent = file.newContent,
                    ),
                )
            }
        return SubmissionUiProjection(
            isRunning = state.runtime.runStatus == com.auracode.assistant.session.kernel.SessionRunStatus.RUNNING,
            editedFileCount = state.editedFiles.filesByPath.size,
            editedFiles = editedFiles,
        )
    }

    /** Maps shared edited-file kinds into the existing composer file-change kind enum. */
    private fun String.toConversationFileChangeKind(): ConversationFileChangeKind {
        return when (trim().lowercase()) {
            "create", "created", "add", "added" -> ConversationFileChangeKind.CREATE
            "delete", "deleted", "remove", "removed" -> ConversationFileChangeKind.DELETE
            "update", "updated", "modify", "modified" -> ConversationFileChangeKind.UPDATE
            else -> ConversationFileChangeKind.UNKNOWN
        }
    }
}
