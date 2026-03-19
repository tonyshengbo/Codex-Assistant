package com.codex.assistant.toolwindow.timeline

import com.codex.assistant.model.MessageRole
import com.codex.assistant.persistence.chat.PersistedAttachmentKind
import com.codex.assistant.protocol.ItemKind
import com.codex.assistant.protocol.ItemStatus
import com.codex.assistant.protocol.TurnOutcome

internal enum class TimelineActivityKind {
    TOOL,
    COMMAND,
    DIFF,
    APPROVAL,
    PLAN,
    UNKNOWN,
}

internal enum class TimelineAttachmentKind {
    IMAGE,
    FILE,
    TEXT,
}

internal data class TimelineMessageAttachment(
    val id: String,
    val kind: TimelineAttachmentKind,
    val displayName: String,
    val assetPath: String,
    val originalPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: ItemStatus,
)

internal enum class TimelineFileChangeKind {
    UPDATE,
    CREATE,
    DELETE,
    UNKNOWN,
}

internal data class TimelineFileChange(
    val path: String,
    val displayName: String,
    val kind: TimelineFileChangeKind,
    val addedLines: Int? = null,
    val deletedLines: Int? = null,
    val oldContent: String? = null,
    val newContent: String? = null,
)

internal sealed interface TimelineNode {
    val id: String
    val sourceId: String?
    val status: ItemStatus?
    val turnId: String?

    data class MessageNode(
        override val id: String,
        override val sourceId: String,
        val role: MessageRole,
        val text: String,
        override val status: ItemStatus,
        val timestamp: Long?,
        override val turnId: String?,
        val cursor: Long?,
        val attachments: List<TimelineMessageAttachment> = emptyList(),
    ) : TimelineNode

    data class ToolCallNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : TimelineNode

    data class CommandNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : TimelineNode

    data class FileChangeNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val changes: List<TimelineFileChange>,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : TimelineNode

    data class ApprovalNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : TimelineNode

    data class PlanNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : TimelineNode

    data class UnknownActivityNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : TimelineNode

    data class LoadMoreNode(
        override val id: String = LOAD_MORE_NODE_ID,
        val isLoading: Boolean,
    ) : TimelineNode {
        override val sourceId: String? = null
        override val status: ItemStatus? = null
        override val turnId: String? = null
    }

    companion object {
        const val LOAD_MORE_NODE_ID: String = "timeline-load-more"
    }
}

internal fun PersistedAttachmentKind.toTimelineAttachmentKind(): TimelineAttachmentKind {
    return when (this) {
        PersistedAttachmentKind.IMAGE -> TimelineAttachmentKind.IMAGE
        PersistedAttachmentKind.FILE -> TimelineAttachmentKind.FILE
        PersistedAttachmentKind.TEXT -> TimelineAttachmentKind.TEXT
    }
}

internal sealed interface TimelineMutation {
    data class ThreadStarted(
        val threadId: String,
    ) : TimelineMutation

    data class TurnStarted(
        val turnId: String,
        val threadId: String? = null,
    ) : TimelineMutation

    data class UpsertMessage(
        val sourceId: String,
        val role: MessageRole,
        val text: String,
        val status: ItemStatus,
        val timestamp: Long? = null,
        val turnId: String? = null,
        val cursor: Long? = null,
        val attachments: List<TimelineMessageAttachment> = emptyList(),
    ) : TimelineMutation

    data class UpsertToolCall(
        val sourceId: String,
        val title: String,
        val body: String,
        val status: ItemStatus,
        val turnId: String? = null,
    ) : TimelineMutation

    data class UpsertCommand(
        val sourceId: String,
        val title: String,
        val body: String,
        val status: ItemStatus,
        val turnId: String? = null,
    ) : TimelineMutation

    data class UpsertFileChange(
        val sourceId: String,
        val title: String,
        val changes: List<TimelineFileChange>,
        val status: ItemStatus,
        val turnId: String? = null,
    ) : TimelineMutation

    data class UpsertApproval(
        val sourceId: String,
        val title: String,
        val body: String,
        val status: ItemStatus,
        val turnId: String? = null,
    ) : TimelineMutation

    data class UpsertPlan(
        val sourceId: String,
        val title: String,
        val body: String,
        val status: ItemStatus,
        val turnId: String? = null,
    ) : TimelineMutation

    data class UpsertUnknownActivity(
        val sourceId: String,
        val title: String,
        val body: String,
        val status: ItemStatus,
        val turnId: String? = null,
    ) : TimelineMutation

    data class TurnCompleted(
        val turnId: String,
        val outcome: TurnOutcome,
    ) : TimelineMutation

    data class Error(
        val message: String,
    ) : TimelineMutation
}

internal fun ItemKind.toTimelineActivityKind(): TimelineActivityKind {
    return when (this) {
        ItemKind.TOOL_CALL -> TimelineActivityKind.TOOL
        ItemKind.COMMAND_EXEC -> TimelineActivityKind.COMMAND
        ItemKind.DIFF_APPLY -> TimelineActivityKind.DIFF
        ItemKind.APPROVAL_REQUEST -> TimelineActivityKind.APPROVAL
        ItemKind.PLAN_UPDATE -> TimelineActivityKind.PLAN
        ItemKind.UNKNOWN,
        ItemKind.NARRATIVE,
        -> TimelineActivityKind.UNKNOWN
    }
}
