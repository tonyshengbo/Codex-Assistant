package com.auracode.assistant.toolwindow.conversation

import com.auracode.assistant.protocol.FileChangeMetrics
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal data class TimelineResolvedDiff(
    val oldContent: String?,
    val newContent: String?,
    val addedLines: Int?,
    val deletedLines: Int?,
)

internal data class TimelineParsedTurnDiff(
    val path: String,
    val displayName: String,
    val kind: TimelineFileChangeKind,
    val addedLines: Int,
    val deletedLines: Int,
    val unifiedDiff: String,
    val oldContent: String? = null,
    val newContent: String? = null,
)

internal object TimelineFileChangePreview {
    fun resolve(parsed: TimelineParsedTurnDiff): TimelineResolvedDiff {
        val diskContent = readFile(parsed.path)
        val newContent = parsed.newContent ?: when (parsed.kind) {
            TimelineFileChangeKind.DELETE -> ""
            else -> diskContent
        }
        val oldContent = parsed.oldContent ?: when (parsed.kind) {
            TimelineFileChangeKind.CREATE -> ""
            TimelineFileChangeKind.DELETE -> parsed.oldContent
            TimelineFileChangeKind.UPDATE,
            TimelineFileChangeKind.UNKNOWN,
            -> newContent?.let { reverseApplyUnifiedDiff(it, parsed.unifiedDiff) }
        }
        val computedStats = FileChangeMetrics.fromContents(oldContent = oldContent, newContent = newContent)
        return TimelineResolvedDiff(
            oldContent = oldContent,
            newContent = newContent,
            addedLines = parsed.addedLines.takeIf { it > 0 } ?: computedStats?.addedLines,
            deletedLines = parsed.deletedLines.takeIf { it > 0 } ?: computedStats?.deletedLines,
        )
    }

    fun resolve(change: TimelineFileChange): TimelineResolvedDiff {
        if (!change.unifiedDiff.isNullOrBlank()) {
            val parsed = parseTurnDiff(change.unifiedDiff)[change.path]
            if (parsed != null) {
                val resolved = resolve(parsed)
                return TimelineResolvedDiff(
                    oldContent = change.oldContent ?: resolved.oldContent,
                    newContent = change.newContent ?: resolved.newContent,
                    addedLines = change.addedLines ?: resolved.addedLines,
                    deletedLines = change.deletedLines ?: resolved.deletedLines,
                )
            }
        }

        val diskContent = readFile(change.path)
        val oldContent = when (change.kind) {
            TimelineFileChangeKind.CREATE -> change.oldContent ?: ""
            TimelineFileChangeKind.DELETE -> change.oldContent ?: diskContent
            TimelineFileChangeKind.UPDATE,
            TimelineFileChangeKind.UNKNOWN,
            -> change.oldContent ?: diskContent
        }
        val newContent = when (change.kind) {
            TimelineFileChangeKind.CREATE -> change.newContent ?: diskContent
            TimelineFileChangeKind.DELETE -> change.newContent ?: ""
            TimelineFileChangeKind.UPDATE,
            TimelineFileChangeKind.UNKNOWN,
            -> change.newContent ?: if (change.oldContent != null) diskContent else null
        }
        val computedStats = FileChangeMetrics.fromContents(
            oldContent = oldContent,
            newContent = newContent,
        )
        return TimelineResolvedDiff(
            oldContent = oldContent,
            newContent = newContent,
            addedLines = change.addedLines ?: computedStats?.addedLines,
            deletedLines = change.deletedLines ?: computedStats?.deletedLines,
        )
    }

    fun parseTurnDiff(diff: String): Map<String, TimelineParsedTurnDiff> {
        if (diff.isBlank()) return emptyMap()
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
        return sections.mapNotNull(::parseTurnDiffSection).associateBy { it.path }
    }

    fun revertParsedDiff(parsed: TimelineParsedTurnDiff): Result<String> {
        return runCatching {
            val path = Path.of(parsed.path)
            when (parsed.kind) {
                TimelineFileChangeKind.CREATE -> {
                    Files.deleteIfExists(path)
                    "Reverted ${parsed.displayName}."
                }

                TimelineFileChangeKind.DELETE -> {
                    val oldContent = parsed.oldContent
                        ?: throw IllegalStateException("No previous content available for ${parsed.displayName}.")
                    path.parent?.let { Files.createDirectories(it) }
                    Files.writeString(
                        path,
                        oldContent,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    )
                    "Reverted ${parsed.displayName}."
                }

                TimelineFileChangeKind.UPDATE,
                TimelineFileChangeKind.UNKNOWN,
                -> {
                    val current = readFile(parsed.path)
                        ?: throw IllegalStateException("Current content unavailable for ${parsed.displayName}.")
                    val reverted = reverseApplyUnifiedDiff(current, parsed.unifiedDiff)
                        ?: throw IllegalStateException("Unable to reverse diff for ${parsed.displayName}.")
                    path.parent?.let { Files.createDirectories(it) }
                    Files.writeString(
                        path,
                        reverted,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    )
                    "Reverted ${parsed.displayName}."
                }
            }
        }
    }

    private fun parseTurnDiffSection(lines: List<String>): TimelineParsedTurnDiff? {
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
            lines.any { it.startsWith("new file mode") } || oldHeader == "/dev/null" -> TimelineFileChangeKind.CREATE
            lines.any { it.startsWith("deleted file mode") } || newHeader == "/dev/null" -> TimelineFileChangeKind.DELETE
            else -> TimelineFileChangeKind.UPDATE
        }

        var addedLines = 0
        var deletedLines = 0
        val oldContentLines = mutableListOf<String>()
        val newContentLines = mutableListOf<String>()
        var inHunk = false
        lines.drop(1).forEach { line ->
            when {
                line.startsWith("@@") -> inHunk = true
                !inHunk -> Unit
                line.startsWith("\\ No newline at end of file") -> Unit
                line.startsWith("+") -> {
                    addedLines += 1
                    newContentLines += line.removePrefix("+")
                }
                line.startsWith("-") -> {
                    deletedLines += 1
                    oldContentLines += line.removePrefix("-")
                }
                line.startsWith(" ") -> {
                    val content = line.removePrefix(" ")
                    oldContentLines += content
                    newContentLines += content
                }
            }
        }

        return TimelineParsedTurnDiff(
            path = path,
            displayName = path.substringAfterLast('/').ifBlank { path },
            kind = kind,
            addedLines = addedLines,
            deletedLines = deletedLines,
            unifiedDiff = lines.joinToString("\n"),
            oldContent = when (kind) {
                TimelineFileChangeKind.CREATE -> ""
                else -> oldContentLines.joinToString("\n").withTerminalNewline()
            },
            newContent = when (kind) {
                TimelineFileChangeKind.DELETE -> ""
                else -> newContentLines.joinToString("\n").withTerminalNewline()
            },
        )
    }

    private fun reverseApplyUnifiedDiff(
        newContent: String,
        unifiedDiff: String,
    ): String? {
        val hunks = parseHunks(unifiedDiff)
        if (hunks.isEmpty()) return null
        val newLines = newContent.split('\n')
        val hadTrailingNewline = newContent.endsWith("\n")
        val oldLines = mutableListOf<String>()
        var newIndex = 0
        hunks.forEach { hunk ->
            val targetIndex = (hunk.newStart - 1).coerceAtLeast(0)
            while (newIndex < targetIndex && newIndex < newLines.size) {
                oldLines += newLines[newIndex]
                newIndex += 1
            }
            hunk.lines.forEach { line ->
                when (line.prefix) {
                    ' ' -> {
                        if (newIndex < newLines.size) {
                            oldLines += newLines[newIndex]
                            newIndex += 1
                        } else {
                            oldLines += line.content
                        }
                    }
                    '+' -> {
                        if (newIndex < newLines.size) {
                            newIndex += 1
                        }
                    }
                    '-' -> oldLines += line.content
                }
            }
        }
        while (newIndex < newLines.size) {
            oldLines += newLines[newIndex]
            newIndex += 1
        }
        return oldLines.joinToString("\n").let { if (hadTrailingNewline) it.withTerminalNewline() else it }
    }

    private data class DiffHunk(
        val newStart: Int,
        val lines: List<DiffHunkLine>,
    )

    private data class DiffHunkLine(
        val prefix: Char,
        val content: String,
    )

    private fun parseHunks(unifiedDiff: String): List<DiffHunk> {
        val result = mutableListOf<DiffHunk>()
        val lines = unifiedDiff.lineSequence().toList()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (!line.startsWith("@@")) {
                index += 1
                continue
            }
            val match = HUNK_HEADER.find(line)
            if (match == null) {
                index += 1
                continue
            }
            val newStart = match.groupValues[2].toIntOrNull() ?: 1
            index += 1
            val hunkLines = mutableListOf<DiffHunkLine>()
            while (index < lines.size) {
                val current = lines[index]
                if (current.startsWith("diff --git ") || current.startsWith("@@")) break
                if (current.startsWith("\\ No newline at end of file")) {
                    index += 1
                    continue
                }
                val prefix = current.firstOrNull()
                if (prefix == ' ' || prefix == '+' || prefix == '-') {
                    hunkLines += DiffHunkLine(prefix = prefix, content = current.drop(1))
                }
                index += 1
            }
            result += DiffHunk(newStart = newStart, lines = hunkLines)
        }
        return result
    }

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

    private fun String.withTerminalNewline(): String {
        return if (isEmpty() || endsWith("\n")) this else "$this\n"
    }

    private fun readFile(path: String): String? {
        val target = runCatching { Path.of(path) }.getOrNull() ?: return null
        if (!Files.exists(target) || Files.isDirectory(target)) return null
        return runCatching { Files.readString(target, StandardCharsets.UTF_8) }.getOrNull()
    }

    private val HUNK_HEADER = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")
}
