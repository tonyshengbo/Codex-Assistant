package com.auracode.assistant.session.projection.conversation

import com.auracode.assistant.i18n.AuraCodeBundle
import com.auracode.assistant.model.MessageRole
import com.auracode.assistant.conversation.presentation.ActivityTitleFormatter
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalDecision
import com.auracode.assistant.session.kernel.SessionConversationEntry
import com.auracode.assistant.session.kernel.SessionMessageRole
import com.auracode.assistant.session.kernel.SessionState
import com.auracode.assistant.toolwindow.conversation.ConversationFileChange
import com.auracode.assistant.toolwindow.conversation.ConversationFileChangeKind
import com.auracode.assistant.toolwindow.conversation.ConversationMessageAttachment
import com.auracode.assistant.toolwindow.conversation.ConversationActivityItem
import com.auracode.assistant.toolwindow.conversation.ConversationAttachmentKind
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Stores the projected conversation timeline derived from session state.
 */
internal data class ConversationUiProjection(
    val nodes: List<ConversationActivityItem>,
    val isRunning: Boolean,
    val latestError: String? = null,
)

/**
 * Projects kernel conversation state into timeline nodes without re-parsing provider payloads in UI code.
 */
internal class ConversationUiProjectionBuilder {
    /** Projects one immutable session state snapshot into timeline-ready nodes. */
    fun project(state: SessionState): ConversationUiProjection {
        val latestError = state.conversation.order
            .asReversed()
            .mapNotNull { entryId -> state.conversation.entries[entryId] as? SessionConversationEntry.Error }
            .firstOrNull()
            ?.message
        return ConversationUiProjection(
            nodes = buildNodes(state),
            isRunning = state.runtime.runStatus == com.auracode.assistant.session.kernel.SessionRunStatus.RUNNING,
            latestError = latestError,
        )
    }

    /** Builds projected timeline nodes in the same order as the kernel conversation log. */
    private fun buildNodes(state: SessionState): List<ConversationActivityItem> {
        return buildList {
            state.conversation.order.forEach { entryId ->
                val entry = state.conversation.entries[entryId] ?: return@forEach
                when (entry) {
                    is SessionConversationEntry.Message -> add(entry.toNode(state))
                    is SessionConversationEntry.Reasoning -> add(entry.toNode())
                    is SessionConversationEntry.Command -> add(entry.toNode())
                    is SessionConversationEntry.Tool -> add(entry.toNode())
                    is SessionConversationEntry.FileChanges -> addAll(entry.toNodes())
                    is SessionConversationEntry.Approval -> add(entry.toNode())
                    is SessionConversationEntry.Plan -> add(entry.toNode())
                    is SessionConversationEntry.ToolUserInput -> add(entry.toNode())
                    is SessionConversationEntry.Error -> add(entry.toNode())
                    is SessionConversationEntry.EngineSwitched -> add(entry.toNode())
                }
            }
        }
    }

    /** Converts one message entry into a timeline message node. */
    private fun SessionConversationEntry.Message.toNode(state: SessionState): ConversationActivityItem.MessageNode {
        return ConversationActivityItem.MessageNode(
            id = messageNodeId(turnId = turnId, sourceId = id),
            sourceId = id,
            role = role.toUiRole(),
            text = text,
            status = when {
                role == SessionMessageRole.ASSISTANT &&
                    turnId != null &&
                    turnId == state.runtime.activeTurnId &&
                    state.runtime.runStatus == com.auracode.assistant.session.kernel.SessionRunStatus.RUNNING ->
                    ItemStatus.RUNNING

                else -> ItemStatus.SUCCESS
            },
            timestamp = null,
            turnId = turnId,
            cursor = null,
            attachments = attachments.map { attachment ->
                ConversationMessageAttachment(
                    id = attachment.id,
                    kind = attachment.kind.toConversationAttachmentKind(),
                    displayName = attachment.displayName,
                    assetPath = attachment.assetPath,
                    originalPath = attachment.originalPath,
                    mimeType = attachment.mimeType,
                    sizeBytes = attachment.sizeBytes,
                    status = attachment.status.toItemStatus(),
                )
            },
        )
    }

    /** Converts one reasoning entry into a timeline reasoning node. */
    private fun SessionConversationEntry.Reasoning.toNode(): ConversationActivityItem.ReasoningNode {
        return ConversationActivityItem.ReasoningNode(
            id = activityNodeId(prefix = "reasoning", turnId = turnId, sourceId = id),
            sourceId = id,
            body = text,
            status = status.toItemStatus(),
            turnId = turnId,
        )
    }

    /** Converts one command entry into a projected command node. */
    private fun SessionConversationEntry.Command.toNode(): ConversationActivityItem.CommandNode {
        val body = outputText?.takeIf { it.isNotBlank() }
            ?: command?.takeIf { it.isNotBlank() }
            ?: ""
        val presentation = ActivityTitleFormatter.commandPresentation(
            explicitName = null,
            command = command,
            body = body,
        )
        return ConversationActivityItem.CommandNode(
            id = activityNodeId(prefix = "command", turnId = turnId, sourceId = id),
            sourceId = id,
            title = presentation.title,
            titleTargetLabel = presentation.targetLabel,
            titleTargetPath = presentation.targetPath,
            body = body,
            commandText = command,
            outputText = outputText,
            collapsedSummary = if (status == SessionActivityStatus.RUNNING) {
                AuraCodeBundle.message("timeline.process.working")
            } else {
                null
            },
            status = status.toItemStatus(),
            turnId = turnId,
        )
    }

    /** Converts one tool entry into a projected tool node. */
    private fun SessionConversationEntry.Tool.toNode(): ConversationActivityItem.ToolCallNode {
        val body = summary.orEmpty()
        val presentation = ActivityTitleFormatter.toolPresentation(
            explicitName = toolName,
            body = body,
            status = status.toItemStatus(),
        )
        return ConversationActivityItem.ToolCallNode(
            id = activityNodeId(prefix = "tool", turnId = turnId, sourceId = id),
            sourceId = id,
            title = presentation.title,
            titleTargetLabel = presentation.targetLabel,
            titleTargetPath = presentation.targetPath,
            body = body,
            collapsedSummary = body.takeIf { it.isNotBlank() },
            status = status.toItemStatus(),
            turnId = turnId,
        )
    }

    /** Converts one file-change entry into one or more projected file-change nodes. */
    private fun SessionConversationEntry.FileChanges.toNodes(): List<ConversationActivityItem.FileChangeNode> {
        return changes.mapIndexed { index, change ->
            val scopedId = "$id:$index"
            ConversationActivityItem.FileChangeNode(
                id = activityNodeId(prefix = "diff", turnId = turnId, sourceId = scopedId),
                sourceId = scopedId,
                title = ActivityTitleFormatter.fileChangeTitle(
                    explicitName = null,
                    changes = listOf(
                        ActivityTitleFormatter.FileChangeSummary(
                            path = change.path,
                            kind = change.kind,
                        ),
                    ),
                    body = summary,
                ),
                titleTargetLabel = change.path.fileDisplayName(),
                titleTargetPath = change.path,
                changes = listOf(
                    ConversationFileChange(
                        sourceScopedId = scopedId,
                        path = change.path,
                        displayName = change.displayName,
                        kind = change.kind.toConversationFileChangeKind(),
                        timestamp = change.updatedAtMs,
                        addedLines = change.addedLines,
                        deletedLines = change.deletedLines,
                        unifiedDiff = change.unifiedDiff,
                        oldContent = change.oldContent,
                        newContent = change.newContent,
                    ),
                ),
                collapsedSummary = if (status == SessionActivityStatus.RUNNING) change.summary else null,
                status = status.toItemStatus(),
                turnId = turnId,
            )
        }
    }

    /** Converts one approval entry into a projected approval node. */
    private fun SessionConversationEntry.Approval.toNode(): ConversationActivityItem.ApprovalNode {
        return ConversationActivityItem.ApprovalNode(
            id = activityNodeId(prefix = "approval", turnId = turnId, sourceId = request.itemId.ifBlank { id }),
            sourceId = request.itemId.ifBlank { id },
            title = localizeKeyOrRaw(request.titleKey),
            body = buildApprovalBody(this),
            status = status.toItemStatus(),
            turnId = turnId,
        )
    }

    /** Converts one tool user-input entry into a projected user-input node. */
    private fun SessionConversationEntry.ToolUserInput.toNode(): ConversationActivityItem.UserInputNode {
        val body = when {
            !responseSummary.isNullOrBlank() -> responseSummary
            status == SessionActivityStatus.FAILED -> AuraCodeBundle.message("timeline.userInput.cancelled")
            else -> AuraCodeBundle.message("timeline.userInput.waiting")
        }
        return ConversationActivityItem.UserInputNode(
            id = activityNodeId(prefix = "user-input", turnId = turnId, sourceId = request.itemId.ifBlank { id }),
            sourceId = request.itemId.ifBlank { id },
            title = AuraCodeBundle.message("timeline.userInput.title"),
            body = body,
            collapsedSummary = body.lineSequence().map(String::trim).filter(String::isNotBlank).joinToString(" · "),
            status = status.toItemStatus(),
            turnId = turnId,
        )
    }

    /** Converts one plan entry into a projected plan node. */
    private fun SessionConversationEntry.Plan.toNode(): ConversationActivityItem.PlanNode {
        return ConversationActivityItem.PlanNode(
            id = activityNodeId(prefix = "plan", turnId = turnId, sourceId = id),
            sourceId = id,
            title = AuraCodeBundle.message("timeline.plan.title"),
            body = body,
            status = status.toItemStatus(),
            turnId = turnId,
        )
    }

    /** Converts one terminal error entry into a projected error node. */
    private fun SessionConversationEntry.Error.toNode(): ConversationActivityItem.ErrorNode {
        return ConversationActivityItem.ErrorNode(
            id = activityNodeId(prefix = "error", turnId = turnId, sourceId = id),
            sourceId = id,
            title = "Error",
            body = message,
            status = ItemStatus.FAILED,
            turnId = turnId,
        )
    }

    /** Converts one local engine-switch entry into a projected system boundary node. */
    private fun SessionConversationEntry.EngineSwitched.toNode(): ConversationActivityItem.EngineSwitchedNode {
        return ConversationActivityItem.EngineSwitchedNode(
            id = activityNodeId(prefix = "engine-switched", turnId = null, sourceId = id),
            sourceId = id,
            title = AuraCodeBundle.message("timeline.system.engineSwitched.title"),
            targetEngineLabel = targetEngineLabel,
            body = AuraCodeBundle.message("timeline.system.engineSwitched", targetEngineLabel),
            iconPath = "/icons/swap-horiz.svg",
            timestamp = timestamp,
            status = ItemStatus.SUCCESS,
            turnId = null,
        )
    }

    /** Builds the final approval body including any resolved decision label. */
    private fun buildApprovalBody(entry: SessionConversationEntry.Approval): String {
        val decisionLabel = when (entry.decision) {
            SessionApprovalDecision.ALLOW -> AuraCodeBundle.message("session.execution.approval.allowed")
            SessionApprovalDecision.REJECT -> AuraCodeBundle.message("session.execution.approval.rejected")
            SessionApprovalDecision.ALLOW_FOR_SESSION -> AuraCodeBundle.message("session.execution.approval.rememberedForSession")
            null -> null
        }
        return listOfNotNull(
            entry.request.body.takeIf { it.isNotBlank() },
            decisionLabel,
        ).joinToString("\n\n")
    }

    /** Safely resolves an i18n key and falls back to raw provider text when no bundle entry exists. */
    private fun localizeKeyOrRaw(value: String): String {
        return runCatching { AuraCodeBundle.message(value) }.getOrElse { value }
    }

    /** Maps shared session message roles into the existing timeline message roles. */
    private fun SessionMessageRole.toUiRole(): MessageRole {
        return when (this) {
            SessionMessageRole.USER -> MessageRole.USER
            SessionMessageRole.ASSISTANT -> MessageRole.ASSISTANT
            SessionMessageRole.SYSTEM -> MessageRole.SYSTEM
        }
    }

    /** Maps shared activity statuses into existing timeline item statuses. */
    private fun SessionActivityStatus.toItemStatus(): ItemStatus {
        return when (this) {
            SessionActivityStatus.RUNNING -> ItemStatus.RUNNING
            SessionActivityStatus.SUCCESS -> ItemStatus.SUCCESS
            SessionActivityStatus.FAILED -> ItemStatus.FAILED
            SessionActivityStatus.SKIPPED -> ItemStatus.SKIPPED
        }
    }

    /** Maps shared attachment kinds into existing timeline attachment kinds. */
    private fun String.toConversationAttachmentKind(): ConversationAttachmentKind {
        return when (trim().lowercase()) {
            "image" -> ConversationAttachmentKind.IMAGE
            "text" -> ConversationAttachmentKind.TEXT
            else -> ConversationAttachmentKind.FILE
        }
    }

    /** Maps shared file-change kinds into existing timeline file-change kinds. */
    private fun String.toConversationFileChangeKind(): ConversationFileChangeKind {
        return when (trim().lowercase()) {
            "create", "created", "add", "added" -> ConversationFileChangeKind.CREATE
            "delete", "deleted", "remove", "removed" -> ConversationFileChangeKind.DELETE
            "update", "updated", "modify", "modified" -> ConversationFileChangeKind.UPDATE
            else -> ConversationFileChangeKind.UNKNOWN
        }
    }

    /** Builds the stable timeline id used for projected message nodes. */
    private fun messageNodeId(turnId: String?, sourceId: String): String {
        return listOfNotNull("message", turnId?.takeIf { it.isNotBlank() }, sourceId).joinToString(":")
    }

    /** Builds the stable timeline id used for projected activity nodes. */
    private fun activityNodeId(prefix: String, turnId: String?, sourceId: String): String {
        return listOfNotNull(prefix, turnId?.takeIf { it.isNotBlank() }, sourceId).joinToString(":")
    }

    /** Builds the UI display name for one filesystem path. */
    private fun String.fileDisplayName(): String {
        return runCatching { Path.of(this).name }.getOrNull()
            ?: substringAfterLast('/').substringAfterLast('\\').ifBlank { this }
    }
}
