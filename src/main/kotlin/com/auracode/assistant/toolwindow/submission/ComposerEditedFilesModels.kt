package com.auracode.assistant.toolwindow.submission

import com.auracode.assistant.toolwindow.conversation.TimelineParsedTurnDiff

internal data class EditedFileAggregate(
    val path: String,
    val displayName: String,
    val threadId: String,
    val turnId: String,
    val latestAddedLines: Int?,
    val latestDeletedLines: Int?,
    val lastUpdatedAt: Long,
    val parsedDiff: TimelineParsedTurnDiff,
)

internal data class EditedFilesSummary(
    val total: Int = 0,
)
