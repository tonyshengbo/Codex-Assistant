package com.auracode.assistant.toolwindow.timeline

import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.persistence.chat.PersistedMessageAttachment
import com.auracode.assistant.protocol.ActivityTitleFormatter
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedItem
import kotlin.io.path.Path
import kotlin.io.path.name

internal object TimelineNodeMapper {

    fun localUserMessageMutation(
        sourceId: String,
        text: String,
        timestamp: Long,
        turnId: String?,
        attachments: List<PersistedMessageAttachment>,
    ): TimelineMutation.UpsertMessage {
        return TimelineNode.MessageNode(
            id = messageNodeId(turnId = turnId, sourceId = sourceId),
            sourceId = sourceId,
            role = MessageRole.USER,
            text = text,
            status = com.auracode.assistant.protocol.ItemStatus.SUCCESS,
            timestamp = timestamp,
            turnId = turnId,
            cursor = null,
            attachments = attachments.map { attachment ->
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
            is UnifiedEvent.SubagentsUpdated -> null
            is UnifiedEvent.ApprovalRequested -> TimelineMutation.UpsertApproval(
                sourceId = event.request.itemId,
                title = event.request.title,
                body = event.request.body,
                status = com.auracode.assistant.protocol.ItemStatus.RUNNING,
                turnId = event.request.turnId,
            )
            is UnifiedEvent.ToolUserInputRequested,
            is UnifiedEvent.ToolUserInputResolved,
            is UnifiedEvent.RunningPlanUpdated,
            is UnifiedEvent.ThreadTokenUsageUpdated,
            is UnifiedEvent.TurnDiffUpdated,
            -> null
            is UnifiedEvent.ThreadStarted -> TimelineMutation.ThreadStarted(threadId = event.threadId)
            is UnifiedEvent.TurnStarted -> TimelineMutation.TurnStarted(turnId = event.turnId, threadId = event.threadId)
            is UnifiedEvent.TurnCompleted -> TimelineMutation.TurnCompleted(
                turnId = event.turnId,
                outcome = event.outcome,
            )

            is UnifiedEvent.Error ->
                // Retryable app-server errors should not collapse the active timeline into a
                // terminal failure state. They are surfaced via toast/status only.
                if (event.terminal) TimelineMutation.AppendError(message = event.message) else null
            is UnifiedEvent.ItemUpdated -> event.item.toTimelineMutation()
        }
    }

    private fun UnifiedItem.toTimelineMutation(): TimelineMutation? {
        return when (kind) {
            ItemKind.NARRATIVE -> {
                val content = text?.takeIf { it.isNotBlank() } ?: return null
                when (name) {
                    "reasoning" -> TimelineMutation.UpsertReasoning(
                        sourceId = id,
                        body = content,
                        status = status,
                    )

                    else -> TimelineMutation.UpsertMessage(
                        sourceId = id,
                        role = narrativeRole(),
                        text = content,
                        status = status,
                        attachments = attachments.map { attachment ->
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
            }

            ItemKind.TOOL_CALL -> ActivityTitleFormatter.toolPresentation(
                explicitName = titleTextOrNull(),
                body = bodyText(),
                status = status,
            ).let { presentation ->
                TimelineMutation.UpsertToolCall(
                    sourceId = id,
                    title = presentation.title,
                    titleTargetLabel = presentation.targetLabel,
                    titleTargetPath = presentation.targetPath,
                    body = bodyText(),
                    status = status,
                )
            }

            ItemKind.COMMAND_EXEC -> ActivityTitleFormatter.commandPresentation(
                explicitName = titleTextOrNull(),
                command = command,
                body = bodyText(),
            ).let { presentation ->
                TimelineMutation.UpsertCommand(
                    sourceId = id,
                    title = presentation.title,
                    titleTargetLabel = presentation.targetLabel,
                    titleTargetPath = presentation.targetPath,
                    body = text?.takeIf { it.isNotBlank() }
                        ?: command?.takeIf { it.isNotBlank() }
                        ?: bodyText(),
                    commandText = command?.takeIf { it.isNotBlank() },
                    status = status,
                )
            }

            ItemKind.DIFF_APPLY -> TimelineMutation.UpsertFileChange(
                sourceId = id,
                changes = if (fileChanges.isNotEmpty()) {
                    fileChanges.map { change ->
                        TimelineFileChange(
                            sourceScopedId = change.sourceScopedId,
                            path = change.path,
                            displayName = change.path.fileDisplayName(),
                            kind = change.kind.toTimelineFileChangeKind(),
                            timestamp = change.timestamp,
                            addedLines = change.addedLines,
                            deletedLines = change.deletedLines,
                            unifiedDiff = change.unifiedDiff,
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

            ItemKind.CONTEXT_COMPACTION -> TimelineMutation.UpsertContextCompaction(
                sourceId = id,
                title = titleTextOrNull() ?: "Context Compaction",
                body = bodyText(),
                status = status,
            )

            ItemKind.PLAN_UPDATE -> TimelineMutation.UpsertPlan(
                sourceId = id,
                title = titleText(),
                body = bodyText(),
                status = status,
            )

            ItemKind.USER_INPUT -> TimelineMutation.UpsertUserInput(
                sourceId = id,
                title = titleTextOrNull() ?: "User Input",
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
        if (ActivityTitleFormatter.isWebSearchTool(explicitName = candidate)) {
            return candidate
        }
        if (ActivityTitleFormatter.isMcpTool(explicitName = candidate)) {
            return candidate
        }
        if (candidate.isNotBlank()) {
            if (kind == ItemKind.UNKNOWN) {
                return candidate
            }
            return candidate
                .replace(Regex("([a-z])([A-Z])"), "$1 $2")
                .split('_', '-', ' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { token ->
                    token.replaceFirstChar { ch -> ch.uppercase() }
                }
        }
        return when (kind.toTimelineActivityKind()) {
            TimelineActivityKind.REASONING -> "Reasoning"
            TimelineActivityKind.TOOL -> "Tool Call"
            TimelineActivityKind.COMMAND -> "Exec Command"
            TimelineActivityKind.DIFF -> "File Changes"
            TimelineActivityKind.CONTEXT_COMPACTION -> "Context Compaction"
            TimelineActivityKind.APPROVAL -> "Approval"
            TimelineActivityKind.PLAN -> "Plan Update"
            TimelineActivityKind.USER_INPUT -> "User Input"
            TimelineActivityKind.UNKNOWN -> "Activity"
        }
    }

    private fun UnifiedItem.titleText(): String = titleTextOrNull().orEmpty()

    private fun UnifiedItem.bodyText(): String {
        val content = listOfNotNull(
            text?.takeIf { it.isNotBlank() },
            command?.takeIf { it.isNotBlank() },
            filePath?.takeIf { it.isNotBlank() },
        ).joinToString("\n")
        if (content.isNotBlank()) {
            return content
        }

        // Web search and other tool-call activities can start without user-facing detail.
        // Falling back to opaque ids leaks transport-level noise into the timeline.
        if (kind == ItemKind.TOOL_CALL) {
            return ""
        }

        return id
    }

    internal fun parseFileChanges(body: String): List<TimelineFileChange> {
        return body.lineSequence()
            .mapIndexedNotNull { index, line ->
                val trimmed = line.trim()
                if (trimmed.isBlank()) return@mapIndexedNotNull null
                val splitIndex = trimmed.indexOf(' ')
                val rawKind = if (splitIndex > 0) trimmed.substring(0, splitIndex).trim() else "update"
                val path = if (splitIndex > 0 && splitIndex < trimmed.lastIndex) {
                    trimmed.substring(splitIndex + 1).trim()
                } else {
                    trimmed.substringAfterLast(' ').ifBlank { trimmed }
                }
                TimelineFileChange(
                    sourceScopedId = "parsed:$index:$path",
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

    private fun messageNodeId(turnId: String?, sourceId: String): String {
        val normalizedTurnId = turnId?.trim().orEmpty()
        return if (normalizedTurnId.isBlank()) {
            "message:$sourceId"
        } else {
            "message:$normalizedTurnId:$sourceId"
        }
    }

    private fun String.toTimelineAttachmentKind(): TimelineAttachmentKind {
        return when (trim().lowercase()) {
            "image" -> TimelineAttachmentKind.IMAGE
            "text" -> TimelineAttachmentKind.TEXT
            else -> TimelineAttachmentKind.FILE
        }
    }

    internal fun activityNodeId(
        prefix: String,
        turnId: String?,
        sourceId: String,
    ): String = listOfNotNull(prefix, turnId?.takeIf { it.isNotBlank() }, sourceId).joinToString(":")
}
