package com.auracode.assistant.commit

import com.intellij.openapi.vcs.changes.Change

/**
 * Renders included VCS changes into a compact, prompt-friendly summary.
 */
internal object CommitChangeSummaryRenderer {
    private const val maxFiles = 12
    private const val maxContentLength = 400
    private const val maxLines = 20

    fun fromChanges(changes: List<Change>): String? {
        if (changes.isEmpty()) return null
        val sections = changes.asSequence()
            .take(maxFiles)
            .mapNotNull(::renderChange)
            .toList()
        return sections.joinToString(separator = "\n\n").trim().takeIf { it.isNotBlank() }
    }

    private fun renderChange(change: Change): String? {
        val path = change.afterRevision?.file?.path
            ?: change.beforeRevision?.file?.path
            ?: return null
        val type = change.type.name
        return when (change.type) {
            Change.Type.NEW -> {
                val afterContent = change.afterRevision.safeContent()
                buildString {
                    appendLine("$type: $path")
                    append(renderSnapshot(afterContent, prefix = "+ "))
                }.trim()
            }

            Change.Type.DELETED -> "$type: $path\nFile deleted."
            Change.Type.MODIFICATION -> {
                val beforeContent = change.beforeRevision.safeContent()
                val afterContent = change.afterRevision.safeContent()
                buildString {
                    appendLine("$type: $path")
                    append(renderLineDiff(beforeContent, afterContent))
                }.trim()
            }

            else -> "$type: $path"
        }
    }

    private fun renderLineDiff(before: String?, after: String?): String {
        val beforeLines = normalize(before)
        val afterLines = normalize(after)
        val max = maxOf(beforeLines.size, afterLines.size)
        val rendered = mutableListOf<String>()
        for (index in 0 until max) {
            val oldLine = beforeLines.getOrNull(index)
            val newLine = afterLines.getOrNull(index)
            if (oldLine == newLine) continue
            if (oldLine != null && oldLine.isNotBlank()) rendered += "- ${truncateLine(oldLine)}"
            if (newLine != null && newLine.isNotBlank()) rendered += "+ ${truncateLine(newLine)}"
            if (rendered.size >= maxLines) break
        }
        return if (rendered.isEmpty()) {
            "Content changed."
        } else {
            rendered.take(maxLines).joinToString(separator = "\n")
        }
    }

    private fun renderSnapshot(content: String?, prefix: String): String {
        val lines = normalize(content)
            .filter { it.isNotBlank() }
            .take(maxLines)
            .map { prefix + truncateLine(it) }
        return if (lines.isEmpty()) "Content unavailable." else lines.joinToString(separator = "\n")
    }

    private fun normalize(content: String?): List<String> {
        return content
            ?.replace("\r\n", "\n")
            ?.take(maxContentLength)
            ?.split('\n')
            ?: emptyList()
    }

    private fun truncateLine(line: String): String {
        return if (line.length <= 120) line else line.take(117) + "..."
    }

    private fun com.intellij.openapi.vcs.changes.ContentRevision?.safeContent(): String? {
        return runCatching { this?.content }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
}
