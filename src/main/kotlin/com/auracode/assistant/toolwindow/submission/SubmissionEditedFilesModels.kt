package com.auracode.assistant.toolwindow.submission

import com.auracode.assistant.toolwindow.conversation.ConversationFileChange
import com.auracode.assistant.toolwindow.conversation.ConversationParsedTurnDiff

internal data class EditedFileAggregate(
    val threadId: String?,
    val turnId: String?,
    val lastUpdatedAt: Long,
    val change: ConversationFileChange,
) {
    val path: String
        get() = change.path

    val displayName: String
        get() = change.displayName

    val latestAddedLines: Int?
        get() = change.addedLines

    val latestDeletedLines: Int?
        get() = change.deletedLines

    val parsedDiff: ConversationParsedTurnDiff
        get() = ConversationParsedTurnDiff(
            path = change.path,
            displayName = change.displayName,
            kind = change.kind,
            addedLines = change.addedLines ?: 0,
            deletedLines = change.deletedLines ?: 0,
            unifiedDiff = change.unifiedDiff.orEmpty(),
            oldContent = change.oldContent,
            newContent = change.newContent,
        )
}

internal data class EditedFilesSummary(
    val total: Int = 0,
)
