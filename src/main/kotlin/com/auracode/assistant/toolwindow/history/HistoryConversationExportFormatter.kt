package com.auracode.assistant.toolwindow.history

import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.conversation.presentation.ActivityTitleFormatter
import com.auracode.assistant.session.kernel.SessionConversationEntry
import com.auracode.assistant.session.kernel.SessionDomainEvent
import com.auracode.assistant.session.kernel.SessionMessageRole
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
import kotlin.io.path.name

private val exportTimestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

internal fun formatConversationExportMarkdown(
    summary: ConversationSummary,
    events: List<SessionDomainEvent>,
): String {
    val title = summary.title.trim().ifBlank { summary.remoteConversationId }
    return buildString {
        appendLine("# $title")
        appendLine()
        appendLine("- Conversation ID: `${summary.remoteConversationId}`")
        appendLine("- Status: ${formatHistoryStatus(summary.status)}")
        appendLine("- Updated: ${formatExportTimestamp(summary.updatedAt)}")
        events.asSequence()
            .mapNotNull(::toMarkdownSection)
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

private fun toMarkdownSection(event: SessionDomainEvent): String? {
    return when (event) {
        is SessionDomainEvent.MessageAppended -> narrativeSection(event)
        is SessionDomainEvent.CommandUpdated -> commandSection(event)
        is SessionDomainEvent.FileChangesUpdated -> diffSection(event)
        is SessionDomainEvent.ToolUpdated -> toolSection(event)
        is SessionDomainEvent.RunningPlanUpdated -> genericSection("Plan", event.plan.body)
        is SessionDomainEvent.ApprovalRequested -> genericSection("Approval", event.request.body)
        is SessionDomainEvent.ToolUserInputRequested -> genericSection("Input", null)
        is SessionDomainEvent.ToolUserInputResolved -> genericSection("Input", event.responseSummary)
        is SessionDomainEvent.ErrorAppended -> genericSection("Error", event.message)
        is SessionDomainEvent.EngineSwitched -> genericSection("System", event.targetEngineLabel)
        else -> null
    }
}

private fun narrativeSection(event: SessionDomainEvent.MessageAppended): String? {
    val body = event.text.trim()
    if (body.isBlank()) return null
    val heading = when (event.role) {
        SessionMessageRole.USER -> "User"
        SessionMessageRole.SYSTEM -> "System"
        else -> "Assistant"
    }
    return "## $heading\n\n$body\n"
}

private fun commandSection(event: SessionDomainEvent.CommandUpdated): String? {
    val shell = event.command?.trim().orEmpty()
    val output = event.outputText?.trim().orEmpty()
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

private fun diffSection(event: SessionDomainEvent.FileChangesUpdated): String? {
    if (event.changes.isEmpty()) return null
    return buildString {
        event.changes.forEachIndexed { index, change ->
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

private fun toolSection(event: SessionDomainEvent.ToolUpdated): String? {
    val normalized = event.summary?.trim().orEmpty()
    if (normalized.isBlank()) return null
    val heading = ActivityTitleFormatter.toolTitle(
        explicitName = event.toolName,
        body = normalized,
        status = event.status.toItemStatus(),
    )
    return "## $heading\n\n$normalized\n"
}

private fun genericSection(title: String, body: String?): String? {
    val normalized = body?.trim().orEmpty()
    if (normalized.isBlank()) return null
    return "## $title\n\n$normalized\n"
}

private fun com.auracode.assistant.session.kernel.SessionFileChange.kindLabel(): String {
    return when (kind.trim().lowercase()) {
        "created", "create", "added", "add" -> "Created"
        "deleted", "delete", "removed", "remove" -> "Deleted"
        else -> "Updated"
    }
}

private fun com.auracode.assistant.session.kernel.SessionActivityStatus.toItemStatus(): com.auracode.assistant.protocol.ItemStatus {
    return when (this) {
        com.auracode.assistant.session.kernel.SessionActivityStatus.RUNNING -> com.auracode.assistant.protocol.ItemStatus.RUNNING
        com.auracode.assistant.session.kernel.SessionActivityStatus.SUCCESS -> com.auracode.assistant.protocol.ItemStatus.SUCCESS
        com.auracode.assistant.session.kernel.SessionActivityStatus.FAILED -> com.auracode.assistant.protocol.ItemStatus.FAILED
        com.auracode.assistant.session.kernel.SessionActivityStatus.SKIPPED -> com.auracode.assistant.protocol.ItemStatus.SKIPPED
    }
}

private fun formatExportTimestamp(updatedAtSeconds: Long): String {
    if (updatedAtSeconds <= 0L) return "unknown"
    return exportTimestampFormatter.format(Instant.ofEpochSecond(updatedAtSeconds))
}
