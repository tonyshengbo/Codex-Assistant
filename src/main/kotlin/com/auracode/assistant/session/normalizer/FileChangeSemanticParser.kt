package com.auracode.assistant.session.normalizer

import com.auracode.assistant.protocol.ProviderFileChange
import com.auracode.assistant.session.kernel.SessionFileChange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses provider-specific file-change payloads into shared session file-change models.
 */
internal class FileChangeSemanticParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Parses one Claude file-mutation tool payload into shared file-change models. */
    fun parseClaudeTool(
        toolName: String,
        inputJson: String,
        outputText: String?,
    ): List<SessionFileChange> {
        val payload = runCatching { json.parseToJsonElement(inputJson).jsonObject }.getOrNull() ?: return emptyList()
        val path = payload["file_path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return emptyList()
        val kind = when (toolName.trim().lowercase()) {
            "write" -> if (outputText.orEmpty().contains("updated", ignoreCase = true)) "update" else "create"
            "edit" -> "update"
            else -> return emptyList()
        }
        return listOf(
            SessionFileChange(
                path = path,
                kind = kind,
                summary = "$kind $path",
                displayName = path.displayName(),
            ),
        )
    }

    /** Parses unified file-change objects into shared session file-change models. */
    fun parseProviderFileChanges(changes: List<ProviderFileChange>): List<SessionFileChange> {
        return changes.mapIndexed { index, change ->
            SessionFileChange(
                path = change.path,
                kind = change.kind,
                summary = "${change.kind} ${change.path}",
                displayName = change.path.displayName(),
                updatedAtMs = (change.timestamp ?: System.currentTimeMillis()) + index,
                addedLines = change.addedLines,
                deletedLines = change.deletedLines,
                unifiedDiff = change.unifiedDiff,
                oldContent = change.oldContent,
                newContent = change.newContent,
            )
        }
    }

    /** Parses fallback plain-text file-change summaries into shared file-change models. */
    fun parseSummaryBody(body: String): List<SessionFileChange> {
        return body.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapIndexed { index, line ->
                val splitIndex = line.indexOf(' ')
                val kind = if (splitIndex > 0) line.substring(0, splitIndex).trim() else "update"
                val path = if (splitIndex > 0 && splitIndex < line.lastIndex) {
                    line.substring(splitIndex + 1).trim()
                } else {
                    line
                }
                SessionFileChange(
                    path = path,
                    kind = kind,
                    summary = "$kind $path",
                    displayName = path.displayName(),
                    updatedAtMs = System.currentTimeMillis() + index,
                )
            }
            .toList()
    }

    /** Parses a unified turn diff into structured edited-file snapshots for the submission slice. */
    fun parseTurnDiff(
        diff: String,
        updatedAtMs: Long,
    ): List<SessionFileChange> {
        if (diff.isBlank()) return emptyList()
        val sections = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        diff.lineSequence().forEach { line ->
            if (line.startsWith("diff --git ") && current.isNotEmpty()) {
                sections += current.toList()
                current = mutableListOf()
            }
            current += line
        }
        if (current.isNotEmpty()) sections += current.toList()
        return sections.mapIndexedNotNull { index, lines ->
            parseTurnDiffSection(lines = lines, updatedAtMs = updatedAtMs + index)
        }
    }

    /** Parses one `diff --git` section into a structured session file-change snapshot. */
    private fun parseTurnDiffSection(
        lines: List<String>,
        updatedAtMs: Long,
    ): SessionFileChange? {
        if (lines.isEmpty()) return null
        val header = lines.first()
        if (!header.startsWith("diff --git ")) return null
        val oldHeader = lines.firstOrNull { it.startsWith("--- ") }?.removePrefix("--- ")?.trim().orEmpty()
        val newHeader = lines.firstOrNull { it.startsWith("+++ ") }?.removePrefix("+++ ")?.trim().orEmpty()
        val rawPath = when {
            newHeader == "/dev/null" -> oldHeader
            newHeader.isNotBlank() -> newHeader
            else -> header.removePrefix("diff --git ").substringAfter(' ', missingDelimiterValue = "")
        }
        val path = normalizeDiffPath(rawPath)
        if (path.isBlank()) return null

        val kind = when {
            lines.any { it.startsWith("new file mode") } || oldHeader == "/dev/null" -> "create"
            lines.any { it.startsWith("deleted file mode") } || newHeader == "/dev/null" -> "delete"
            else -> "update"
        }

        var addedLines = 0
        var deletedLines = 0
        val oldContentLines = mutableListOf<String>()
        val newContentLines = mutableListOf<String>()
        var oldHasTrailingNewline = true
        var newHasTrailingNewline = true
        var inHunk = false
        var lastDiffLinePrefix: Char? = null
        lines.drop(1).forEach { line ->
            when {
                line.startsWith("@@") -> inHunk = true
                !inHunk -> Unit
                line.startsWith("\\ No newline at end of file") -> {
                    when (lastDiffLinePrefix) {
                        '-' -> oldHasTrailingNewline = false
                        '+' -> newHasTrailingNewline = false
                        ' ' -> {
                            oldHasTrailingNewline = false
                            newHasTrailingNewline = false
                        }
                    }
                }
                line.startsWith("+") -> {
                    addedLines += 1
                    newContentLines += line.removePrefix("+")
                    lastDiffLinePrefix = '+'
                }

                line.startsWith("-") -> {
                    deletedLines += 1
                    oldContentLines += line.removePrefix("-")
                    lastDiffLinePrefix = '-'
                }

                line.startsWith(" ") -> {
                    val content = line.removePrefix(" ")
                    oldContentLines += content
                    newContentLines += content
                    lastDiffLinePrefix = ' '
                }
            }
        }

        return SessionFileChange(
            path = path,
            kind = kind,
            summary = "$kind $path",
            displayName = path.displayName(),
            updatedAtMs = updatedAtMs,
            addedLines = addedLines,
            deletedLines = deletedLines,
            unifiedDiff = lines.joinToString("\n"),
            oldContent = when (kind) {
                "create" -> ""
                else -> joinDiffContent(oldContentLines, oldHasTrailingNewline)
            },
            newContent = when (kind) {
                "delete" -> ""
                else -> joinDiffContent(newContentLines, newHasTrailingNewline)
            },
        )
    }

    /** Normalizes git diff headers into canonical absolute-or-project-relative paths. */
    private fun normalizeDiffPath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "/dev/null") return ""
        return trimmed
            .removePrefix("a/")
            .removePrefix("b/")
            .removePrefix("a\\")
            .removePrefix("b\\")
            .replace('\\', '/')
    }

    /** Preserves terminal newlines so revert and diff preview calculations stay stable. */
    private fun String.withTerminalNewline(): String {
        return if (isEmpty() || endsWith("\n")) this else "$this\n"
    }

    /** Builds one diff-side content snapshot while preserving EOF newline semantics. */
    private fun joinDiffContent(
        lines: List<String>,
        hasTrailingNewline: Boolean,
    ): String {
        return lines.joinToString("\n").let { content ->
            if (hasTrailingNewline) {
                content.withTerminalNewline()
            } else {
                content.removeTrailingNewline()
            }
        }
    }

    /** Removes exactly one terminal newline so `\ No newline at end of file` stays faithful. */
    private fun String.removeTrailingNewline(): String {
        return removeSuffix("\n")
    }

    /** Builds a stable filename label from a normalized filesystem path. */
    private fun String.displayName(): String {
        return substringAfterLast('/').substringAfterLast('\\').ifBlank { this }
    }
}
