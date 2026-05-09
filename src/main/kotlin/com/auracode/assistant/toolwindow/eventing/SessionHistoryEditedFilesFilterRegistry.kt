package com.auracode.assistant.toolwindow.eventing

import com.auracode.assistant.toolwindow.submission.EditedFileAggregate

/**
 * Keeps one per-session baseline of edited files restored from history so
 * the composer can hide historical file aggregates while still showing new live edits.
 */
internal class SessionHistoryEditedFilesFilterRegistry {
    private val baselineBySessionId = linkedMapOf<String, Map<String, EditedFileAggregate>>()

    /** Replaces the session baseline with the current history-restored edited files. */
    fun replaceBaseline(
        sessionId: String,
        editedFiles: List<EditedFileAggregate>,
    ) {
        baselineBySessionId[sessionId] = editedFiles.associateBy { it.path }
    }

    /** Adds only newly introduced prepended-history files into the existing baseline. */
    fun capturePrependedHistory(
        sessionId: String,
        before: List<EditedFileAggregate>,
        after: List<EditedFileAggregate>,
    ) {
        val beforePaths = before.asSequence().map { it.path }.toHashSet()
        val merged = linkedMapOf<String, EditedFileAggregate>()
        baselineBySessionId[sessionId]?.forEach { (path, aggregate) ->
            merged[path] = aggregate
        }
        after.forEach { aggregate ->
            if (aggregate.path !in beforePaths) {
                merged.putIfAbsent(aggregate.path, aggregate)
            }
        }
        baselineBySessionId[sessionId] = merged
    }

    /** Returns only the edited files that do not exactly match the historical baseline. */
    fun filterVisible(
        sessionId: String,
        editedFiles: List<EditedFileAggregate>,
    ): List<EditedFileAggregate> {
        val baseline = baselineBySessionId[sessionId].orEmpty()
        if (baseline.isEmpty()) return editedFiles
        return editedFiles.filter { aggregate -> baseline[aggregate.path] != aggregate }
    }

    /** Drops any remembered baseline when the session is reset, deleted, or switched in place. */
    fun drop(sessionId: String) {
        baselineBySessionId.remove(sessionId)
    }
}
