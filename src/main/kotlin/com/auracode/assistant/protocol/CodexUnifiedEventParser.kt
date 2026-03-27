package com.auracode.assistant.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object CodexUnifiedEventParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseLine(line: String): UnifiedEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        return parseObject(obj)
    }

    private fun parseObject(obj: JsonObject): UnifiedEvent? {
        val method = obj.string("method")?.trim()
        if (!method.isNullOrBlank()) {
            return parseMethodObject(method, obj.objectValue("params"))
        }

        val type = obj.string("type")?.lowercase().orEmpty()
        if (type.isBlank()) return null

        if (type == "thread.started") {
            val threadId = obj.string("thread_id") ?: return null
            return UnifiedEvent.ThreadStarted(
                threadId = threadId,
                resumedFromTurnId = obj.string("resume_from"),
            )
        }

        if (type == "turn.started") {
            val turnId = obj.string("turn_id") ?: return null
            return UnifiedEvent.TurnStarted(
                turnId = turnId,
                threadId = obj.string("thread_id"),
            )
        }

        if (type == "turn.diff.updated") {
            val threadId = obj.string("thread_id") ?: return null
            val turnId = obj.string("turn_id") ?: return null
            val diff = obj.string("diff") ?: return null
            return UnifiedEvent.TurnDiffUpdated(
                threadId = threadId,
                turnId = turnId,
                diff = diff,
            )
        }

        if (type == "turn.completed") {
            val usage = obj.objectValue("usage")?.let {
                TurnUsage(
                    inputTokens = it.int("input_tokens"),
                    cachedInputTokens = it.int("cached_input_tokens"),
                    outputTokens = it.int("output_tokens"),
                )
            }
            return UnifiedEvent.TurnCompleted(
                turnId = obj.string("turn_id").orEmpty(),
                outcome = TurnOutcome.SUCCESS,
                usage = usage,
            )
        }

        if (type == "turn.failed") {
            val turnId = obj.string("turn_id").orEmpty()
            return UnifiedEvent.TurnCompleted(
                turnId = turnId,
                outcome = TurnOutcome.FAILED,
                usage = null,
            )
        }

        if (type == "error") {
            return UnifiedEvent.Error(obj.string("message") ?: "Unknown error")
        }

        if (type.startsWith("item.")) {
            val status = if (type.endsWith("completed")) {
                ItemStatus.SUCCESS
            } else {
                ItemStatus.RUNNING
            }
            val item = obj.objectValue("item") ?: return null
            return UnifiedEvent.ItemUpdated(parseItem(item, status, type))
        }

        return null
    }

    private fun parseMethodObject(
        method: String,
        params: JsonObject?,
    ): UnifiedEvent? {
        val normalized = method.trim().lowercase()
        val item = params?.objectValue("item")
        return when (normalized) {
            "item/started" -> item?.let { UnifiedEvent.ItemUpdated(parseItem(it, ItemStatus.RUNNING, normalized)) }
            "item/completed" -> item?.let { UnifiedEvent.ItemUpdated(parseItem(it, ItemStatus.SUCCESS, normalized)) }
            else -> null
        }
    }

    private fun parseItem(item: JsonObject, fallbackStatus: ItemStatus, eventType: String): UnifiedItem {
        val itemType = item.string("type").orEmpty()
        val id = item.string("id") ?: item.string("call_id") ?: "item-${itemType.ifBlank { "unknown" }}"
        val decision = item.string("decision")?.let { parseApprovalDecision(it) }
        val status = parseStatus(item.string("status"), fallbackStatus)
        val kind = parseItemKind(itemType)
        val diffChanges = if (kind == ItemKind.DIFF_APPLY) {
            item.arrayValue("changes")
                ?.mapIndexedNotNull { index, change ->
                    val value = runCatching { change.jsonObject }.getOrNull() ?: return@mapIndexedNotNull null
                    val path = value.string("path") ?: value.string("file_path") ?: value.string("filePath")
                    val oldContent = value.string("old_content") ?: value.string("oldContent")
                    val newContent = value.string("new_content") ?: value.string("newContent") ?: value.string("content")
                    val computedStats = FileChangeMetrics.fromContents(oldContent = oldContent, newContent = newContent)
                    path?.let {
                        UnifiedFileChange(
                            sourceScopedId = "$id:$index",
                            path = it,
                            kind = value.string("kind").orEmpty().ifBlank { "update" },
                            addedLines = value.intOrNull("added_lines", "addedLines") ?: computedStats?.addedLines,
                            deletedLines = value.intOrNull("deleted_lines", "deletedLines") ?: computedStats?.deletedLines,
                            unifiedDiff = value.string("diff"),
                            oldContent = oldContent,
                            newContent = newContent,
                        )
                    }
                }
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val inferredName = inferUnknownItemName(itemType = itemType, kind = kind)
        val text = diffChanges?.joinToString("\n") { "${it.kind} ${it.path}" } ?: item.string("text")
            ?: item.string("output")
            ?: item.string("input")
            ?: item.string("query")
            ?: item.objectValue("payload")?.toString()
        val command = item.string("command")
            ?: item.objectValue("payload")?.string("command")
        val cwd = item.string("cwd")
            ?: item.objectValue("payload")?.string("cwd")
        val exitCode = item.intOrNull("exit_code")
        val name = if (kind == ItemKind.DIFF_APPLY) {
            "File Changes (${text?.lineSequence()?.count { it.isNotBlank() } ?: 0})"
        } else {
            item.string("tool_name")
                ?: item.string("name")
                ?: item.string("action")?.takeIf { kind == ItemKind.TOOL_CALL }
                ?: inferredName
        }
        val errorStatus = if (eventType.contains("failed")) ItemStatus.FAILED else status

        return UnifiedItem(
            id = id,
            kind = kind,
            status = errorStatus,
            name = name,
            text = text,
            command = command,
            cwd = cwd,
            fileChanges = diffChanges.orEmpty(),
            exitCode = exitCode,
            approvalDecision = decision,
        )
    }

    private fun parseItemKind(itemType: String): ItemKind {
        val type = itemType.lowercase()
        return when {
            type == "approval_request" -> ItemKind.APPROVAL_REQUEST
            type == "plan_update" -> ItemKind.PLAN_UPDATE
            type == "contextcompaction" || type == "context_compaction" || type == "context-compaction" ->
                ItemKind.CONTEXT_COMPACTION
            type.contains("command") || type.contains("shell") -> ItemKind.COMMAND_EXEC
            type.contains("diff") || type.contains("patch") || type.contains("file_change") -> ItemKind.DIFF_APPLY
            type == "websearch" || type == "web_search" || type == "web-search" -> ItemKind.TOOL_CALL
            type.contains("tool") || type.contains("function_call") -> ItemKind.TOOL_CALL
            type.contains("reasoning") || type.contains("narrative") || type.contains("message") -> ItemKind.NARRATIVE
            else -> ItemKind.UNKNOWN
        }
    }

    private fun inferUnknownItemName(
        itemType: String,
        kind: ItemKind,
    ): String? {
        if (kind != ItemKind.UNKNOWN) return null
        val normalized = itemType.trim()
        if (normalized.isBlank()) return null
        return normalized
    }

    private fun parseStatus(status: String?, fallback: ItemStatus): ItemStatus {
        val value = status?.trim()?.lowercase().orEmpty()
        if (value.isBlank()) return fallback
        return when (value) {
            "running", "in_progress", "started" -> ItemStatus.RUNNING
            "success", "succeeded", "completed" -> ItemStatus.SUCCESS
            "failed", "failure", "error", "incomplete", "cancelled", "canceled" -> ItemStatus.FAILED
            "skipped" -> ItemStatus.SKIPPED
            else -> fallback
        }
    }

    private fun parseApprovalDecision(decision: String): ApprovalDecision {
        return when (decision.trim().lowercase()) {
            "approved" -> ApprovalDecision.APPROVED
            "rejected" -> ApprovalDecision.REJECTED
            else -> ApprovalDecision.PENDING
        }
    }

    private fun JsonObject.string(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.objectValue(key: String): JsonObject? {
        return this[key]?.let { runCatching { it.jsonObject }.getOrNull() }
    }

    private fun JsonObject.arrayValue(key: String): JsonArray? {
        val value = this[key] ?: return null
        return value as? JsonArray
    }

    private fun JsonObject.int(key: String): Int {
        return this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    }

    private fun JsonObject.intOrNull(vararg keys: String): Int? {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun JsonElement.stringValue(): String? {
        return if (this is JsonPrimitive) this.contentOrNull else null
    }
}
