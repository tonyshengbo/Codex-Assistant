package com.auracode.assistant.toolwindow.conversation

import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.persistence.chat.PersistedAttachmentKind
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome

internal enum class ConversationActivityKind {
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

internal enum class ConversationAttachmentKind {
    IMAGE,
    FILE,
    TEXT,
}

internal data class ConversationMessageAttachment(
    val id: String,
    val kind: ConversationAttachmentKind,
    val displayName: String,
    val assetPath: String,
    val originalPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val status: ItemStatus,
)

internal enum class ConversationFileChangeKind {
    UPDATE,
    CREATE,
    DELETE,
    UNKNOWN,
}

internal data class ConversationFileChange(
    val sourceScopedId: String,
    val path: String,
    val displayName: String,
    val kind: ConversationFileChangeKind,
    val timestamp: Long? = null,
    val addedLines: Int? = null,
    val deletedLines: Int? = null,
    val unifiedDiff: String? = null,
    val oldContent: String? = null,
    val newContent: String? = null,
)

internal sealed interface ConversationActivityItem {
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
        val attachments: List<ConversationMessageAttachment> = emptyList(),
    ) : ConversationActivityItem

    data class ReasoningNode(
        override val id: String,
        override val sourceId: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : ConversationActivityItem

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
    ) : ConversationActivityItem

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
    ) : ConversationActivityItem

    data class FileChangeNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val titleTargetLabel: String? = null,
        val titleTargetPath: String? = null,
        val changes: List<ConversationFileChange>,
        val collapsedSummary: String? = null,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : ConversationActivityItem

    data class ApprovalNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : ConversationActivityItem

    data class ContextCompactionNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : ConversationActivityItem

    data class PlanNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : ConversationActivityItem

    data class UserInputNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        val collapsedSummary: String? = null,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : ConversationActivityItem

    data class UnknownActivityNode(
        override val id: String,
        override val sourceId: String,
        val title: String,
        val body: String,
        override val status: ItemStatus,
        override val turnId: String?,
    ) : ConversationActivityItem

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
    ) : ConversationActivityItem

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
    ) : ConversationActivityItem

    data class LoadMoreNode(
        override val id: String = LOAD_MORE_NODE_ID,
        val isLoading: Boolean,
    ) : ConversationActivityItem {
        override val sourceId: String? = null
        override val status: ItemStatus? = null
        override val turnId: String? = null
    }

    companion object {
        const val LOAD_MORE_NODE_ID: String = "timeline-load-more"
    }
}

internal fun PersistedAttachmentKind.toConversationAttachmentKind(): ConversationAttachmentKind {
    return when (this) {
        PersistedAttachmentKind.IMAGE -> ConversationAttachmentKind.IMAGE
        PersistedAttachmentKind.FILE -> ConversationAttachmentKind.FILE
        PersistedAttachmentKind.TEXT -> ConversationAttachmentKind.TEXT
    }
}

internal fun ItemKind.toConversationActivityKind(): ConversationActivityKind {
    return when (this) {
        ItemKind.NARRATIVE -> ConversationActivityKind.REASONING
        ItemKind.TOOL_CALL -> ConversationActivityKind.TOOL
        ItemKind.COMMAND_EXEC -> ConversationActivityKind.COMMAND
        ItemKind.DIFF_APPLY -> ConversationActivityKind.DIFF
        ItemKind.CONTEXT_COMPACTION -> ConversationActivityKind.CONTEXT_COMPACTION
        ItemKind.APPROVAL_REQUEST -> ConversationActivityKind.APPROVAL
        ItemKind.PLAN_UPDATE -> ConversationActivityKind.PLAN
        ItemKind.USER_INPUT -> ConversationActivityKind.USER_INPUT
        ItemKind.UNKNOWN,
        -> ConversationActivityKind.UNKNOWN
    }
}

internal fun ConversationActivityItem.isProcessActivityNode(): Boolean {
    return when (this) {
        is ConversationActivityItem.ToolCallNode,
        is ConversationActivityItem.CommandNode,
        is ConversationActivityItem.FileChangeNode,
        -> true

        else -> false
    }
}
