package com.auracode.assistant.toolwindow.drawer

import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.protocol.ActivityTitleFormatter
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedFileChange
import com.auracode.assistant.protocol.UnifiedItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.name

private val exportTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

internal fun formatConversationExportMarkdown(
    summary: ConversationSummary,
    events: List<UnifiedEvent>,
): String {
    val title = summary.title.trim().ifBlank { summary.remoteConversationId }
    return buildString {
        appendLine("# $title")
        appendLine()
        appendLine("- Conversation ID: `${summary.remoteConversationId}`")
        appendLine("- Status: ${formatHistoryStatus(summary.status)}")
        appendLine("- Updated: ${formatExportTimestamp(summary.updatedAt)}")
        events.asSequence()
            .filterIsInstance<UnifiedEvent.ItemUpdated>()
            .mapNotNull { it.item.toMarkdownSection() }
            .forEach { section ->
                appendLine()
                append(section)
                if (!section.endsWith("\n")) appendLine()
            }
    }.trimEnd() + "\n"
}

internal fun suggestConversationExportFileName(
    title: String,
    remoteConversationId: String,
): String {
    val baseName = title.trim()
        .replace(Regex("[\\\\/:*?\"<>|]+"), " ")
        .replace(Regex("\\s+"), "-")
        .trim('-', '.', ' ')
        .ifBlank { remoteConversationId.trim() }
        .take(64)
        .ifBlank { "conversation" }
    return "$baseName.md"
}

private fun UnifiedItem.toMarkdownSection(): String? {
    return when (kind) {
        ItemKind.NARRATIVE -> narrativeSection()
        ItemKind.COMMAND_EXEC -> commandSection()
        ItemKind.DIFF_APPLY -> diffSection()
        ItemKind.TOOL_CALL -> toolSection()
        ItemKind.CONTEXT_COMPACTION -> genericSection("Context", text)
        ItemKind.PLAN_UPDATE -> genericSection("Plan", text)
        ItemKind.APPROVAL_REQUEST -> genericSection("Approval", text)
        ItemKind.USER_INPUT -> genericSection("Input", text)
        ItemKind.UNKNOWN -> genericSection("Event", text ?: command)
    }
}

private fun UnifiedItem.narrativeSection(): String? {
    val body = text?.trim().orEmpty()
    if (body.isBlank()) return null
    val heading = when (name) {
        "user_message" -> "User"
        "system_message" -> "System"
        else -> "Assistant"
    }
    return "## $heading\n\n$body\n"
}

private fun UnifiedItem.commandSection(): String? {
    val shell = command?.trim().orEmpty()
    val output = text?.trim().orEmpty()
    if (shell.isBlank() && output.isBlank()) return null
    return buildString {
        appendLine("## Command")
        appendLine()
        if (shell.isNotBlank()) {
            appendLine("```sh")
            appendLine(shell)
            appendLine("```")
            if (output.isNotBlank()) {
                appendLine()
            }
        }
        if (output.isNotBlank()) {
            appendLine("```text")
            appendLine(output)
            appendLine("```")
        }
    }
}

private fun UnifiedItem.diffSection(): String? {
    if (fileChanges.isEmpty()) return null
    return buildString {
        fileChanges.forEachIndexed { index, change ->
            if (index > 0) appendLine()
            appendLine("## File Change")
            appendLine()
            appendLine("${change.kindLabel()} `${Path(change.path).name}`")
            val diff = change.unifiedDiff?.trimEnd().orEmpty()
            if (diff.isNotBlank()) {
                appendLine()
                appendLine("```diff")
                appendLine(diff)
                appendLine("```")
            }
        }
    }
}

private fun UnifiedItem.toolSection(): String? {
    val normalized = (text ?: command)?.trim().orEmpty()
    if (normalized.isBlank()) return null
    val heading = ActivityTitleFormatter.toolTitle(
        explicitName = name,
        body = normalized,
        status = status,
    )
    return "## $heading\n\n$normalized\n"
}

private fun UnifiedItem.genericSection(title: String, body: String?): String? {
    val normalized = body?.trim().orEmpty()
    if (normalized.isBlank()) return null
    return "## $title\n\n$normalized\n"
}

private fun UnifiedFileChange.kindLabel(): String {
    return when (kind.trim().lowercase()) {
        "created", "create", "added", "add" -> "Created"
        "deleted", "delete", "removed", "remove" -> "Deleted"
        else -> "Updated"
    }
}

private fun formatExportTimestamp(updatedAtSeconds: Long): String {
    if (updatedAtSeconds <= 0L) return "unknown"
    return exportTimestampFormatter.format(Instant.ofEpochSecond(updatedAtSeconds))
}
