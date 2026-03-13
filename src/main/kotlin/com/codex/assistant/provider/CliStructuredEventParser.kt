package com.codex.assistant.provider

import com.codex.assistant.model.EngineEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object CliStructuredEventParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseCodexLine(line: String): EngineEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return parsePlainStatus(trimmed)
        }

        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        return parseByType(obj)
    }

    fun parseClaudeLine(line: String): EngineEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null
        }

        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        return parseByType(obj)
    }

    private fun parseByType(obj: JsonObject): EngineEvent? {
        val type = obj.string("type").orEmpty()
        if (type.isBlank()) {
            val fallbackText = findText(obj)
            return fallbackText?.let { EngineEvent.AssistantTextDelta(it) }
        }

        val normalizedType = type.lowercase()
        val item = obj.objectValue("item")
        if (item != null) {
            parseItemEvent(item, normalizedType)?.let { return it }
        }

        parseProposalEvent(normalizedType, obj)?.let { return it }

        if (normalizedType == "error" || normalizedType.endsWith(".error")) {
            return EngineEvent.Error(obj.string("message") ?: findText(obj) ?: "Unknown error")
        }
        if (normalizedType.startsWith("thread.") || normalizedType.startsWith("session.")) {
            obj.string("thread_id")
                ?.takeIf { it.isNotBlank() }
                ?.let { return EngineEvent.SessionReady(it) }
            obj.string("session_id")
                ?.takeIf { it.isNotBlank() }
                ?.let { return EngineEvent.SessionReady(it) }
        }
        if (normalizedType.contains("turn.failed")) {
            val error = obj.objectValue("error")?.string("message") ?: obj.string("message") ?: findText(obj)
            return EngineEvent.Error(error ?: "Turn failed")
        }
        parseTurnUsage(normalizedType, obj)?.let { return it }
        if (normalizedType.endsWith("completed") && !normalizedType.startsWith("item.")) {
            return null
        }
        if (normalizedType.endsWith("started") || normalizedType.endsWith("completed")) {
            return EngineEvent.Status(type)
        }

        if (isThinkingEvent(normalizedType, obj)) {
            val text = findThinkingText(obj) ?: findText(obj)
            return text?.takeIf { it.isNotBlank() }?.let { EngineEvent.ThinkingDelta(it) }
        }

        if (isToolEvent(normalizedType, obj)) {
            val toolName = findToolName(obj) ?: "tool"
            val payload = findToolPayload(obj)
            val callId = findCallId(obj)
            return if (normalizedType.contains("result") || normalizedType.contains("output") || normalizedType.endsWith("completed")) {
                EngineEvent.ToolCallFinished(
                    name = toolName,
                    output = payload,
                    callId = callId,
                    isError = normalizedType.contains("error"),
                )
            } else {
                EngineEvent.ToolCallStarted(
                    name = toolName,
                    input = payload,
                    callId = callId,
                )
            }
        }

        if (isContentEvent(normalizedType, obj)) {
            val text = findContentText(obj) ?: findText(obj)
            return text?.takeIf { it.isNotBlank() }?.let { EngineEvent.AssistantTextDelta(it) }
        }

        if (normalizedType == "status") {
            return EngineEvent.Status(obj.string("message") ?: type)
        }

        val fallback = findText(obj)
        return fallback?.let { EngineEvent.AssistantTextDelta(it) }
    }

    private fun parseItemEvent(item: JsonObject, eventType: String): EngineEvent? {
        val itemType = item.string("type")?.lowercase().orEmpty()
        if (itemType.isBlank()) {
            return null
        }

        parseProposalEvent(itemType, item)?.let { return it }

        if (itemType == "reasoning" || itemType.contains("thinking")) {
            val text = item.string("text") ?: findText(item)
            return text?.takeIf { it.isNotBlank() }?.let { EngineEvent.ThinkingDelta(it) }
        }

        if (itemType == "agent_message" || itemType == "message" || itemType.contains("output_text")) {
            val text = item.string("text")
                ?: item.objectValue("message")?.string("text")
                ?: findText(item)
            return text?.takeIf { it.isNotBlank() }?.let { EngineEvent.AssistantTextDelta(it) }
        }

        if (itemType.contains("tool") || itemType.contains("function_call")) {
            val name = item.string("tool_name")
                ?: item.objectValue("tool")?.string("name")
                ?: item.string("name")
                ?: "tool"
            val callId = item.string("call_id") ?: item.string("id")
            val input = item.string("input")
                ?: item.objectValue("input")?.toString()
                ?: item.string("arguments")
                ?: item.objectValue("arguments")?.toString()
                ?: item.string("command")
            return if (eventType.contains("completed") || itemType.contains("result") || itemType.contains("output")) {
                EngineEvent.ToolCallFinished(name = name, output = input, callId = callId)
            } else {
                EngineEvent.ToolCallStarted(name = name, input = input, callId = callId)
            }
        }

        return null
    }

    private fun parseProposalEvent(type: String, obj: JsonObject): EngineEvent? {
        if (type.contains("tool")) {
            return null
        }

        val command = findCommand(obj)
        if (command != null && (type.contains("command") || type.contains("proposal"))) {
            val cwd = findCwd(obj) ?: "."
            return EngineEvent.CommandProposal(command = command, cwd = cwd)
        }

        val filePath = findDiffPath(obj)
        val newContent = findDiffContent(obj)
        if (!filePath.isNullOrBlank() && !newContent.isNullOrBlank() &&
            (type.contains("diff") || type.contains("patch") || type.contains("proposal"))
        ) {
            return EngineEvent.DiffProposal(filePath = filePath, newContent = newContent)
        }

        return null
    }

    private fun parseTurnUsage(type: String, obj: JsonObject): EngineEvent.TurnUsage? {
        if (type != "turn.completed") {
            return null
        }
        val usage = obj.objectValue("usage") ?: return null
        return EngineEvent.TurnUsage(
            inputTokens = usage.int("input_tokens"),
            cachedInputTokens = usage.int("cached_input_tokens"),
            outputTokens = usage.int("output_tokens"),
        )
    }

    private fun parsePlainStatus(text: String): EngineEvent? {
        if (text.startsWith("WARNING:")) return EngineEvent.Status(text)
        if (text.contains("Reconnecting")) return EngineEvent.Status(text)
        return null
    }

    private fun isThinkingEvent(type: String, obj: JsonObject): Boolean {
        if (type.contains("thinking") || type.contains("reasoning")) return true
        return obj.containsKey("thinking") || obj.containsKey("reasoning")
    }

    private fun isToolEvent(type: String, obj: JsonObject): Boolean {
        if (type.contains("tool")) return true
        if (type.contains("function_call")) return true
        return obj.containsKey("tool_name") || obj.containsKey("toolName") || obj.containsKey("tool")
    }

    private fun isContentEvent(type: String, obj: JsonObject): Boolean {
        if (type.contains("output_text") || type.contains("content") || type.contains("message")) return true
        if (type.contains("delta")) return true
        return obj.containsKey("content") || obj.containsKey("text") || obj.containsKey("delta")
    }

    private fun findThinkingText(obj: JsonObject): String? {
        return obj.string("thinking")
            ?: obj.objectValue("thinking")?.let { findText(it) }
            ?: obj.string("reasoning")
            ?: obj.objectValue("reasoning")?.let { findText(it) }
    }

    private fun findContentText(obj: JsonObject): String? {
        return obj.string("delta")
            ?: obj.objectValue("delta")?.string("text")
            ?: obj.string("text")
            ?: obj.string("content")
            ?: obj.objectValue("content")?.string("text")
    }

    private fun findToolName(obj: JsonObject): String? {
        return obj.string("tool_name")
            ?: obj.string("toolName")
            ?: obj.objectValue("tool")?.string("name")
            ?: obj.string("name")
    }

    private fun findToolPayload(obj: JsonObject): String? {
        return obj.string("input")
            ?: obj.objectValue("input")?.toString()
            ?: obj.string("arguments")
            ?: obj.objectValue("arguments")?.toString()
            ?: obj.string("output")
            ?: obj.objectValue("output")?.toString()
            ?: obj.string("command")
    }

    private fun findCommand(obj: JsonObject): String? {
        return obj.string("command")
            ?: obj.objectValue("proposal")?.string("command")
            ?: obj.objectValue("payload")?.string("command")
            ?: obj.objectValue("item")?.string("command")
    }

    private fun findCwd(obj: JsonObject): String? {
        return obj.string("cwd")
            ?: obj.string("working_directory")
            ?: obj.objectValue("proposal")?.string("cwd")
            ?: obj.objectValue("payload")?.string("cwd")
            ?: obj.objectValue("item")?.string("cwd")
    }

    private fun findDiffPath(obj: JsonObject): String? {
        return obj.string("file_path")
            ?: obj.string("filePath")
            ?: obj.string("path")
            ?: obj.objectValue("proposal")?.string("file_path")
            ?: obj.objectValue("proposal")?.string("filePath")
            ?: obj.objectValue("payload")?.string("file_path")
            ?: obj.objectValue("payload")?.string("filePath")
            ?: obj.objectValue("item")?.string("file_path")
            ?: obj.objectValue("item")?.string("filePath")
    }

    private fun findDiffContent(obj: JsonObject): String? {
        return obj.string("new_content")
            ?: obj.string("newContent")
            ?: obj.string("content")
            ?: obj.objectValue("proposal")?.string("new_content")
            ?: obj.objectValue("proposal")?.string("newContent")
            ?: obj.objectValue("payload")?.string("new_content")
            ?: obj.objectValue("payload")?.string("newContent")
            ?: obj.objectValue("item")?.string("new_content")
            ?: obj.objectValue("item")?.string("newContent")
    }

    private fun findCallId(obj: JsonObject): String? {
        return obj.string("call_id") ?: obj.string("id")
    }

    private fun findText(obj: JsonObject): String? {
        val priority = listOf("message", "delta", "text", "content")
        priority.forEach { key ->
            obj[key]?.let { element ->
                element.stringValue()?.let { value ->
                    if (value.isNotBlank()) return value
                }
                if (element is JsonObject) {
                    findText(element)?.let { nested ->
                        if (nested.isNotBlank()) return nested
                    }
                }
            }
        }

        obj.values.forEach { value ->
            value.stringValue()?.let {
                if (it.isNotBlank()) return it
            }
            if (value is JsonObject) {
                findText(value)?.let { nested ->
                    if (nested.isNotBlank()) return nested
                }
            }
        }
        return null
    }

    private fun JsonObject.string(key: String): String? {
        return this[key]?.jsonPrimitive?.contentOrNull
    }

    private fun JsonObject.objectValue(key: String): JsonObject? {
        return this[key]?.let { runCatching { it.jsonObject }.getOrNull() }
    }

    private fun JsonObject.int(key: String): Int {
        return this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
    }

    private fun JsonElement.stringValue(): String? {
        return when (this) {
            is JsonPrimitive -> this.contentOrNull
            is JsonObject -> null
            else -> null
        }
    }
}
