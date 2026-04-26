package com.auracode.assistant.toolwindow.conversation

import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.persistence.chat.PersistedAttachmentKind
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome

internal enum class TimelineActivityKind {
    REASONING,
    TOOL,
    COMMAND,
    DIFF,
    CONTEXT_COMPACTION,
    APPROVAL,
    PLAN,
    USER_INPUT,
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
    val sourceScopedId: String,
    val path: String,
    val displayName: String,
    val kind: TimelineFileChangeKind,
    val timestamp: Long? = null,
    val addedLines: Int? = null,
    val deletedLines: Int? = null,
    val unifiedDiff: String? = null,
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

    data class ReasoningNode(
        override val id: String,
        override val sourceId: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : TimelineNode

    data class ToolCallNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val titleTargetLabel: String? = null,
        val titleTargetPath: String? = null,
        val body: String,
        val collapsedSummary: String? = null,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : TimelineNode

    data class CommandNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val titleTargetLabel: String? = null,
        val titleTargetPath: String? = null,
        val body: String,
        val commandText: String? = null,
        val outputText: String? = null,
        val collapsedSummary: String? = null,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : TimelineNode

    data class FileChangeNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val titleTargetLabel: String? = null,
        val titleTargetPath: String? = null,
        val changes: List<TimelineFileChange>,
        val collapsedSummary: String? = null,
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

    data class ContextCompactionNode(
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

    data class UserInputNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        val collapsedSummary: String? = null,
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

    /**
     * Keeps terminal conversation failures explicit in the timeline instead of
     * hiding them behind toast-only rendering or collapsed failure summaries.
     */
    data class ErrorNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : TimelineNode

    /**
     * Marks the visible boundary where the current tab switches to a different engine.
     */
    data class EngineSwitchedNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val targetEngineLabel: String,
        val body: String,
        val iconPath: String,
        val timestamp: Long?,
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

    data class UpsertReasoning(
        val sourceId: String,
        val body: String,
        val status: ItemStatus,
        val turnId: String? = null,
    ) : TimelineMutation

    data class UpsertToolCall(
        val sourceId: String,
        val title: String,
        val titleTargetLabel: String? = null,
        val titleTargetPath: String? = null,
        val body: String,
        val status: ItemStatus,
        val turnId: String? = null,
    ) : TimelineMutation

    data class UpsertCommand(
        val sourceId: String,
        val title: String,
        val titleTargetLabel: String? = null,
        val titleTargetPath: String? = null,
        val body: String,
        val commandText: String? = null,
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

    data class UpsertContextCompaction(
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

    data class UpsertUserInput(
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

    /**
     * Appends a dedicated timeline error item for terminal conversation
     * failures while keeping retry notifications out of the timeline.
     */
    data class AppendError(
        val message: String,
    ) : TimelineMutation

    /**
     * Inserts a local-only timeline marker after the user switches the active engine in place.
     */
    data class AppendEngineSwitched(
        val sourceId: String,
        val targetEngineLabel: String,
        val body: String,
        val timestamp: Long? = null,
    ) : TimelineMutation
}

internal fun ItemKind.toTimelineActivityKind(): TimelineActivityKind {
    return when (this) {
        ItemKind.NARRATIVE -> TimelineActivityKind.REASONING
        ItemKind.TOOL_CALL -> TimelineActivityKind.TOOL
        ItemKind.COMMAND_EXEC -> TimelineActivityKind.COMMAND
        ItemKind.DIFF_APPLY -> TimelineActivityKind.DIFF
        ItemKind.CONTEXT_COMPACTION -> TimelineActivityKind.CONTEXT_COMPACTION
        ItemKind.APPROVAL_REQUEST -> TimelineActivityKind.APPROVAL
        ItemKind.PLAN_UPDATE -> TimelineActivityKind.PLAN
        ItemKind.USER_INPUT -> TimelineActivityKind.USER_INPUT
        ItemKind.UNKNOWN,
        -> TimelineActivityKind.UNKNOWN
    }
}

internal fun TimelineNode.isProcessActivityNode(): Boolean {
    return when (this) {
        is TimelineNode.ToolCallNode,
        is TimelineNode.CommandNode,
        is TimelineNode.FileChangeNode,
        -> true

        else -> false
    }
}
