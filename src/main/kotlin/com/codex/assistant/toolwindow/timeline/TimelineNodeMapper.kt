package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.model.MessageRole
import com.codex.assistant.persistence.chat.PersistedActivityKind
import com.codex.assistant.persistence.chat.PersistedTimelineEntry
import com.codex.assistant.protocol.ActivityTitleFormatter
import com.codex.assistant.protocol.ItemKind
import com.codex.assistant.protocol.UnifiedEvent
import com.codex.assistant.protocol.UnifiedItem
import kotlin.io.path.Path
import kotlin.io.path.name

internal object TimelineNodeMapper {
    fun fromHistory(entry: PersistedTimelineEntry): TimelineNode {
        return when (entry.recordType) {
            com.codex.assistant.persistence.chat.PersistedTimelineRecordType.MESSAGE -> {
                TimelineNode.MessageNode(
                    id = historyNodeId(entry.cursor, entry.id),
                    sourceId = entry.sourceId,
                    role = entry.role ?: MessageRole.ASSISTANT,
                    text = entry.body,
                    status = entry.status,
                    timestamp = entry.createdAt,
                    turnId = entry.turnId.ifBlank { null },
                    cursor = entry.cursor,
                    attachments = entry.attachments.map { attachment ->
                        TimelineMessageAttachment(
                            id = attachment.id,
                            kind = attachment.kind.toTimelineAttachmentKind(),
                            displayName = attachment.displayName,
                            assetPath = attachment.assetPath,
                            originalPath = attachment.originalPath,
                            mimeType = attachment.mimeType,
                            sizeBytes = attachment.sizeBytes,
                            status = attachment.status,
                        )
                    },
                )
            }

            com.codex.assistant.persistence.chat.PersistedTimelineRecordType.ACTIVITY -> {
                val turnId = entry.turnId.ifBlank { null }
                when (entry.activityKind ?: PersistedActivityKind.UNKNOWN) {
                    PersistedActivityKind.TOOL -> TimelineNode.ToolCallNode(
                        id = activityNodeId("tool", turnId, entry.sourceId),
                        sourceId = entry.sourceId,
                        title = ActivityTitleFormatter.toolTitle(
                            explicitName = entry.title,
                            body = entry.body,
                        ),
                        body = entry.body,
                        status = entry.status,
                        turnId = turnId,
                    )

                    PersistedActivityKind.COMMAND -> TimelineNode.CommandNode(
                        id = activityNodeId("command", turnId, entry.sourceId),
                        sourceId = entry.sourceId,
                        title = ActivityTitleFormatter.commandTitle(
                            explicitName = entry.title,
                            body = entry.body,
                        ),
                        body = entry.body,
                        status = entry.status,
                        turnId = turnId,
                    )

                    PersistedActivityKind.DIFF -> {
                        val parsedChanges = parseFileChanges(entry.body)
                        TimelineNode.FileChangeNode(
                            id = activityNodeId("diff", turnId, entry.sourceId),
                            sourceId = entry.sourceId,
                            changes = parsedChanges,
                            title = ActivityTitleFormatter.fileChangeTitle(
                                explicitName = entry.title,
                                changes = parsedChanges.map { change ->
                                    ActivityTitleFormatter.FileChangeSummary(
                                        path = change.path,
                                        kind = change.kind.name,
                                    )
                                },
                                body = entry.body,
                            ),
                            status = entry.status,
                            turnId = turnId,
                        )
                    }

                    PersistedActivityKind.APPROVAL -> TimelineNode.ApprovalNode(
                        id = activityNodeId("approval", turnId, entry.sourceId),
                        sourceId = entry.sourceId,
                        title = entry.title,
                        body = entry.body,
                        status = entry.status,
                        turnId = turnId,
                    )

                    PersistedActivityKind.PLAN -> TimelineNode.PlanNode(
                        id = activityNodeId("plan", turnId, entry.sourceId),
                        sourceId = entry.sourceId,
                        title = entry.title,
                        body = entry.body,
                        status = entry.status,
                        turnId = turnId,
                    )

                    PersistedActivityKind.UNKNOWN -> TimelineNode.UnknownActivityNode(
                        id = activityNodeId("unknown", turnId, entry.sourceId),
                        sourceId = entry.sourceId,
                        title = entry.title,
                        body = entry.body,
                        status = entry.status,
                        turnId = turnId,
                    )
                }
            }

            com.codex.assistant.persistence.chat.PersistedTimelineRecordType.ATTACHMENT ->
                error("Attachment records should be grouped into parent message nodes before mapping")
        }
    }

    fun localUserMessageMutation(entry: PersistedTimelineEntry): TimelineMutation.UpsertMessage {
        return TimelineNode.MessageNode(
            id = historyNodeId(entry.cursor, entry.id),
            sourceId = entry.sourceId,
            role = entry.role ?: MessageRole.USER,
            text = entry.body,
            status = entry.status,
            timestamp = entry.createdAt,
            turnId = entry.turnId.ifBlank { null },
            cursor = entry.cursor,
            attachments = entry.attachments.map { attachment ->
                TimelineMessageAttachment(
                    id = attachment.id,
                    kind = attachment.kind.toTimelineAttachmentKind(),
                    displayName = attachment.displayName,
                    assetPath = attachment.assetPath,
                    originalPath = attachment.originalPath,
                    mimeType = attachment.mimeType,
                    sizeBytes = attachment.sizeBytes,
                    status = attachment.status,
                )
            },
        ).let { node ->
            TimelineMutation.UpsertMessage(
                sourceId = node.sourceId,
                role = node.role,
                text = node.text,
                status = node.status,
                timestamp = node.timestamp,
                turnId = node.turnId,
                cursor = node.cursor,
                attachments = node.attachments,
            )
        }
    }

    fun fromUnifiedEvent(event: UnifiedEvent): TimelineMutation? {
        return when (event) {
            is UnifiedEvent.ThreadStarted -> TimelineMutation.ThreadStarted(threadId = event.threadId)
            is UnifiedEvent.TurnStarted -> TimelineMutation.TurnStarted(turnId = event.turnId, threadId = event.threadId)
            is UnifiedEvent.TurnCompleted -> TimelineMutation.TurnCompleted(
                turnId = event.turnId,
                outcome = event.outcome,
            )

            is UnifiedEvent.Error -> TimelineMutation.Error(message = event.message)
            is UnifiedEvent.ItemUpdated -> event.item.toTimelineMutation()
        }
    }

    private fun UnifiedItem.toTimelineMutation(): TimelineMutation? {
        return when (kind) {
            ItemKind.NARRATIVE -> {
                val content = text?.takeIf { it.isNotBlank() } ?: return null
                TimelineMutation.UpsertMessage(
                    sourceId = id,
                    role = narrativeRole(),
                    text = content,
                    status = status,
                    attachments = emptyList(),
                )
            }

            ItemKind.TOOL_CALL -> TimelineMutation.UpsertToolCall(
                sourceId = id,
                title = ActivityTitleFormatter.toolTitle(
                    explicitName = titleTextOrNull(),
                    body = bodyText(),
                ),
                body = bodyText(),
                status = status,
            )

            ItemKind.COMMAND_EXEC -> TimelineMutation.UpsertCommand(
                sourceId = id,
                title = ActivityTitleFormatter.commandTitle(
                    explicitName = titleTextOrNull(),
                    command = command,
                    body = bodyText(),
                ),
                body = bodyText(),
                status = status,
            )

            ItemKind.DIFF_APPLY -> TimelineMutation.UpsertFileChange(
                sourceId = id,
                changes = if (fileChanges.isNotEmpty()) {
                    fileChanges.map { change ->
                        TimelineFileChange(
                            path = change.path,
                            displayName = change.path.fileDisplayName(),
                            kind = change.kind.toTimelineFileChangeKind(),
                            oldContent = change.oldContent,
                            newContent = change.newContent,
                        )
                    }
                } else {
                    parseFileChanges(bodyText())
                },
                title = ActivityTitleFormatter.fileChangeTitle(
                    explicitName = titleTextOrNull(),
                    changes = fileChanges.map { change ->
                        ActivityTitleFormatter.FileChangeSummary(
                            path = change.path,
                            kind = change.kind,
                        )
                    },
                    body = bodyText(),
                ),
                status = status,
            )

            ItemKind.APPROVAL_REQUEST -> TimelineMutation.UpsertApproval(
                sourceId = id,
                title = titleText(),
                body = bodyText(),
                status = status,
            )

            ItemKind.PLAN_UPDATE -> TimelineMutation.UpsertPlan(
                sourceId = id,
                title = titleText(),
                body = bodyText(),
                status = status,
            )

            ItemKind.UNKNOWN -> TimelineMutation.UpsertUnknownActivity(
                sourceId = id,
                title = titleText(),
                body = bodyText(),
                status = status,
            )
        }
    }

    private fun UnifiedItem.narrativeRole(): MessageRole {
        return when (name) {
            "user_message" -> MessageRole.USER
            "system_message" -> MessageRole.SYSTEM
            else -> MessageRole.ASSISTANT
        }
    }

    private fun UnifiedItem.titleTextOrNull(): String? {
        val candidate = name?.trim().orEmpty()
        if (candidate.isNotBlank()) {
            return candidate
                .split('_', '-', ' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { token ->
                    token.replaceFirstChar { ch -> ch.uppercase() }
                }
        }
        return when (kind.toTimelineActivityKind()) {
            TimelineActivityKind.TOOL -> "Tool Call"
            TimelineActivityKind.COMMAND -> "Exec Command"
            TimelineActivityKind.DIFF -> "File Changes"
            TimelineActivityKind.APPROVAL -> "Approval"
            TimelineActivityKind.PLAN -> "Plan Update"
            TimelineActivityKind.UNKNOWN -> "Activity"
        }
    }

    private fun UnifiedItem.titleText(): String = titleTextOrNull().orEmpty()

    private fun UnifiedItem.bodyText(): String {
        return listOfNotNull(
            text?.takeIf { it.isNotBlank() },
            command?.takeIf { it.isNotBlank() },
            filePath?.takeIf { it.isNotBlank() },
        ).joinToString("\n").ifBlank { id }
    }

    internal fun parseFileChanges(body: String): List<TimelineFileChange> {
        return body.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val splitIndex = trimmed.indexOf(' ')
                val rawKind = if (splitIndex > 0) trimmed.substring(0, splitIndex).trim() else "update"
                val path = if (splitIndex > 0 && splitIndex < trimmed.lastIndex) {
                    trimmed.substring(splitIndex + 1).trim()
                } else {
                    trimmed.substringAfterLast(' ').ifBlank { trimmed }
                }
                TimelineFileChange(
                    path = path,
                    displayName = path.fileDisplayName(),
                    kind = rawKind.toTimelineFileChangeKind(),
                )
            }
            .toList()
    }

    private fun String.fileDisplayName(): String {
        return runCatching { Path(this).name }.getOrNull()
            ?: substringAfterLast('/').substringAfterLast('\\').ifBlank { this }
    }

    private fun String.toTimelineFileChangeKind(): TimelineFileChangeKind {
        return when (trim().lowercase()) {
            "create", "created", "add", "added" -> TimelineFileChangeKind.CREATE
            "delete", "deleted", "remove", "removed" -> TimelineFileChangeKind.DELETE
            "update", "updated", "modify", "modified" -> TimelineFileChangeKind.UPDATE
            else -> TimelineFileChangeKind.UNKNOWN
        }
    }

    private fun historyNodeId(cursor: Long, messageId: String): String = "history-$cursor-$messageId"

    internal fun activityNodeId(
        prefix: String,
        turnId: String?,
        sourceId: String,
    ): String = listOfNotNull(prefix, turnId?.takeIf { it.isNotBlank() }, sourceId).joinToString(":")
}
