package com.auracode.assistant.provider.claude.semantic

import com.auracode.assistant.provider.claude.ClaudeConversationEvent
import com.auracode.assistant.session.kernel.SessionActivityStatus
import com.auracode.assistant.session.kernel.SessionApprovalRequest
import com.auracode.assistant.session.kernel.SessionApprovalRequestKind
import com.auracode.assistant.session.kernel.SessionMessageRole
import com.auracode.assistant.session.kernel.SessionRunningPlan
import com.auracode.assistant.session.kernel.SessionRunningPlanStep
import com.auracode.assistant.session.normalizer.CommandSemanticClassifier
import com.auracode.assistant.session.normalizer.FileChangeSemanticParser
import com.auracode.assistant.session.normalizer.ToolSemanticClassifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Extracts provider-specific Claude semantic records from aggregated conversation events.
 */
internal class ClaudeSemanticEventExtractor(
    private val toolClassifier: ToolSemanticClassifier = ToolSemanticClassifier(),
    private val commandClassifier: CommandSemanticClassifier = CommandSemanticClassifier(),
    private val fileChangeParser: FileChangeSemanticParser = FileChangeSemanticParser(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** Extracts zero or more semantic records from one Claude conversation event. */
    fun extract(event: ClaudeConversationEvent): List<ClaudeSemanticRecord> {
        return when (event) {
            is ClaudeConversationEvent.AssistantTextUpdated -> listOf(
                ClaudeSemanticRecord.Message(
                    messageId = event.messageId,
                    turnId = null,
                    role = SessionMessageRole.ASSISTANT,
                    text = event.text,
                ),
            )

            is ClaudeConversationEvent.PermissionRequested -> listOf(
                ClaudeSemanticRecord.ApprovalRequest(
                    request = SessionApprovalRequest(
                        requestId = event.requestId,
                        turnId = null,
                        itemId = "claude:approval:${event.requestId}",
                        kind = approvalKind(event),
                        titleKey = approvalTitleKey(event),
                        body = approvalBody(event),
                        command = event.toolInput["command"],
                        cwd = event.toolInput["cwd"],
                        allowForSession = true,
                    ),
                ),
            )

            is ClaudeConversationEvent.ToolCallUpdated -> extractToolCall(event)
            else -> emptyList()
        }
    }

    /** Extracts semantic records from one structured Claude tool-call snapshot. */
    private fun extractToolCall(event: ClaudeConversationEvent.ToolCallUpdated): List<ClaudeSemanticRecord> {
        val status = activityStatus(event)
        val itemId = "claude:tool:${event.toolUseId}"
        return when (toolClassifier.classifyClaudeTool(event.toolName)) {
            com.auracode.assistant.session.kernel.SessionToolKind.FILE_READ -> {
                val descriptor = commandClassifier.buildClaudeToolCommand(
                    toolName = event.toolName,
                    inputJson = event.inputJson,
                ) ?: return emptyList()
                listOf(
                    ClaudeSemanticRecord.CommandActivity(
                        itemId = itemId,
                        turnId = null,
                        status = status,
                        commandKind = descriptor.kind,
                        command = descriptor.command,
                        cwd = descriptor.cwd,
                        outputText = event.outputText,
                    ),
                )
            }

            com.auracode.assistant.session.kernel.SessionToolKind.FILE_WRITE,
            com.auracode.assistant.session.kernel.SessionToolKind.FILE_EDIT,
            -> {
                val changes = fileChangeParser.parseClaudeTool(
                    toolName = event.toolName,
                    inputJson = event.inputJson,
                    outputText = event.outputText,
                )
                listOf(
                    ClaudeSemanticRecord.FileChangesActivity(
                        itemId = itemId,
                        turnId = null,
                        status = status,
                        summary = changes.joinToString("\n") { it.summary },
                        changes = changes,
                    ),
                )
            }

            com.auracode.assistant.session.kernel.SessionToolKind.PLAN_UPDATE -> {
                val plan = parseTodoWritePlan(event.inputJson) ?: return emptyList()
                listOf(
                    ClaudeSemanticRecord.RunningPlanActivity(
                        plan = plan,
                    ),
                )
            }

            else -> listOf(
                ClaudeSemanticRecord.ToolActivity(
                    itemId = itemId,
                    turnId = null,
                    status = status,
                    toolKind = toolClassifier.classifyClaudeTool(event.toolName),
                    toolName = event.toolName,
                    summary = event.outputText,
                ),
            )
        }
    }

    /** Derives a structured approval kind from the Claude tool request payload. */
    private fun approvalKind(event: ClaudeConversationEvent.PermissionRequested): SessionApprovalRequestKind {
        return when {
            event.toolInput["command"]?.isNotBlank() == true -> SessionApprovalRequestKind.COMMAND
            event.toolInput["file_path"]?.isNotBlank() == true || event.toolInput["path"]?.isNotBlank() == true ->
                SessionApprovalRequestKind.FILE_CHANGE

            else -> SessionApprovalRequestKind.PERMISSIONS
        }
    }

    /** Derives a stable metadata key for a Claude approval request. */
    private fun approvalTitleKey(event: ClaudeConversationEvent.PermissionRequested): String {
        return when (approvalKind(event)) {
            SessionApprovalRequestKind.COMMAND -> "session.execution.approval.runCommand"
            SessionApprovalRequestKind.FILE_CHANGE -> "session.execution.approval.applyFileChange"
            SessionApprovalRequestKind.PERMISSIONS -> "session.execution.approval.grantPermissions"
        }
    }

    /** Builds the structured approval body from Claude tool-request inputs. */
    private fun approvalBody(event: ClaudeConversationEvent.PermissionRequested): String {
        return event.toolInput["command"]
            ?: event.toolInput["file_path"]
            ?: event.toolInput["path"]
            ?: event.toolName
    }

    /** Maps Claude tool-call lifecycle flags into a shared activity status. */
    private fun activityStatus(event: ClaudeConversationEvent.ToolCallUpdated): SessionActivityStatus {
        return when {
            event.completed && event.isError -> SessionActivityStatus.FAILED
            event.completed -> SessionActivityStatus.SUCCESS
            else -> SessionActivityStatus.RUNNING
        }
    }

    /** Parses TodoWrite JSON input into a structured running plan snapshot. */
    private fun parseTodoWritePlan(inputJson: String): SessionRunningPlan? {
        val payload = parseJsonObject(inputJson) ?: return null
        val todos = payload["todos"]?.jsonArray.orEmpty()
        val steps = todos.mapNotNull { element ->
            val todo = element as? JsonObject ?: return@mapNotNull null
            val stepText = todo["content"]?.asText()
                ?: todo["title"]?.asText()
                ?: todo["text"]?.asText()
                ?: return@mapNotNull null
            SessionRunningPlanStep(
                step = stepText,
                status = todo["status"]?.asText().orEmpty(),
            )
        }
        if (steps.isEmpty()) {
            return null
        }
        return SessionRunningPlan(
            turnId = null,
            explanation = null,
            steps = steps,
            body = steps.joinToString("\n") { "- [${it.status}] ${it.step}" },
        )
    }

    /** Parses one JSON object from a compact Claude tool-input payload. */
    private fun parseJsonObject(inputJson: String): JsonObject? {
        val normalized = inputJson.trim()
        if (normalized.isBlank()) {
            return null
        }
        return runCatching { json.parseToJsonElement(normalized).jsonObject }.getOrNull()
    }

    /** Reads text content from a JSON element when present. */
    private fun kotlinx.serialization.json.JsonElement.asText(): String? {
        return (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}
