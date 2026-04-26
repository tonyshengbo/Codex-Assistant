package com.auracode.assistant.integration.ide

import com.auracode.assistant.model.ContextFile
import com.auracode.assistant.toolwindow.submission.FocusedContextSnapshot

/**
 * Builds normalized context-file payloads for IDE-originated requests.
 */
internal object IdeContextFiles {
    internal fun fromFocusedSnapshot(snapshot: FocusedContextSnapshot): List<ContextFile> {
        val selectedText = snapshot.selectedText?.takeIf { it.isNotBlank() }
        return when {
            selectedText != null -> listOf(
                ContextFile(
                    path = encodeSelectionPath(snapshot),
                    content = selectedText,
                ),
            )

            else -> listOf(ContextFile(path = snapshot.path))
        }
    }

    internal fun fromFilePath(path: String): List<ContextFile> = listOf(ContextFile(path = path))

    private fun encodeSelectionPath(snapshot: FocusedContextSnapshot): String {
        val start = snapshot.startLine ?: return snapshot.path
        val end = snapshot.endLine ?: return snapshot.path
        return if (start == end) "${snapshot.path}:$start" else "${snapshot.path}:$start-$end"
    }
}
