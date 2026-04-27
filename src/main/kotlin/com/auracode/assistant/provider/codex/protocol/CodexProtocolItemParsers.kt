package com.auracode.assistant.provider.codex.protocol

import com.auracode.assistant.diff.FileChangeMetrics
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.ProviderFileChange
import com.auracode.assistant.protocol.ProviderItem
import kotlinx.serialization.json.JsonObject

internal class CodexProviderItemTypeParsers {
    /**
     * {"method":"item/started","params":{"item":{"type":"agentMessage","id":"msg_x","text":"","phase":"commentary"},"threadId":"x","turnId":"x"}}
     * {"method":"item/completed","params":{"item":{"type":"agentMessage","id":"msg_x","text":"hello","phase":"final_answer"},"threadId":"x","turnId":"x"}}
     */
    fun parseAgentMessage(item: JsonObject, status: ItemStatus): ProviderItem {
        return ProviderItem(
            id = item.string("id") ?: "item-agentMessage",
            kind = ItemKind.NARRATIVE,
            status = status,
            name = "message",
            text = extractText(item),
        )
    }

    /**
     * {"method":"item/started","params":{"item":{"type":"reasoning","id":"rs_x","summary":[],"content":[]},"threadId":"x","turnId":"x"}}
     * {"method":"item/completed","params":{"item":{"type":"reasoning","id":"rs_x","summary":[],"content":[]},"threadId":"x","turnId":"x"}}
     */
    fun parseReasoning(item: JsonObject, status: ItemStatus): ProviderItem {
        return ProviderItem(
            id = item.string("id") ?: "item-reasoning",
            kind = ItemKind.NARRATIVE,
            status = status,
            name = "reasoning",
            text = extractText(item),
        )
    }

    /**
     * {"method":"item/started","params":{"item":{"type":"commandExecution","id":"call_x","command":"/bin/zsh -lc \"sed -n '1,220p' build.gradle.kts\"","cwd":"/Users/tonysheng/StudioProject/Aura","status":"inProgress"},"threadId":"x","turnId":"x"}}
     * {"method":"item/completed","params":{"item":{"type":"commandExecution","id":"call_x","command":"/bin/zsh -lc \"sed -n '1,220p' build.gradle.kts\"","cwd":"/Users/tonysheng/StudioProject/Aura","status":"completed","aggregatedOutput":"plugins {...}","exitCode":0},"threadId":"x","turnId":"x"}}
     */
    fun parseCommandExecution(
        item: JsonObject,
        status: ItemStatus,
        bufferedOutput: String,
    ): ProviderItem {
        return ProviderItem(
            id = item.string("id") ?: "item-commandExecution",
            kind = ItemKind.COMMAND_EXEC,
            status = status,
            name = item.string("command") ?: "Exec Command",
            text = item.string("aggregatedOutput") ?: item.string("aggregated_output") ?: bufferedOutput,
            command = item.string("command"),
            cwd = item.string("cwd"),
            exitCode = item.intOrNull("exitCode", "exit_code"),
        )
    }

    /**
     * {"method":"item/started","params":{"item":{"type":"fileChange","id":"call_x","changes":[{"path":"/Users/tonysheng/StudioProject/Aura/src/test/kotlin/com/auracode/assistant/toolwindow/shared/AttachmentPreviewOverlayTest.kt","kind":{"type":"update","move_path":null},"diff":"@@ ..."}],"status":"inProgress"},"threadId":"x","turnId":"x"}}
     * {"method":"item/completed","params":{"item":{"type":"fileChange","id":"call_x","changes":[{"path":"/Users/tonysheng/StudioProject/Aura/src/test/kotlin/com/auracode/assistant/toolwindow/shared/AttachmentPreviewOverlayTest.kt","kind":{"type":"update","move_path":null},"diff":"@@ ..."}],"status":"completed"},"threadId":"x","turnId":"x"}}
     */
    fun parseFileChange(
        item: JsonObject,
        status: ItemStatus,
        previousChanges: List<ProviderFileChange>,
    ): ProviderItem {
        val id = item.string("id") ?: "item-fileChange"
        val changes = extractFileChanges(item = item, sourceId = id).ifEmpty { previousChanges }
        return ProviderItem(
            id = id,
            kind = ItemKind.DIFF_APPLY,
            status = status,
            name = "File Changes",
            text = extractFileChangeSummary(changes, item),
            fileChanges = changes,
        )
    }

    /**
     * {"method":"item/started","params":{"item":{"type":"contextCompaction","id":"23480200-1d53-4a26-82f9-2eab84e10303"},"threadId":"019d47ab-e17e-7401-ba0a-4b69ecf81858","turnId":"019d4c47-1dbe-7bf0-84e3-8262199bc8d5"}}
     * {"method":"item/completed","params":{"item":{"type":"contextCompaction","id":"23480200-1d53-4a26-82f9-2eab84e10303"},"threadId":"019d47ab-e17e-7401-ba0a-4b69ecf81858","turnId":"019d4c47-1dbe-7bf0-84e3-8262199bc8d5"}}
     */
    fun parseContextCompaction(item: JsonObject, status: ItemStatus): ProviderItem {
        return ProviderItem(
            id = item.string("id") ?: "item-contextCompaction",
            kind = ItemKind.CONTEXT_COMPACTION,
            status = status,
            name = "Context Compaction",
            text = contextCompactionText(status),
        )
    }

    /**
     * {"method":"item/started","params":{"item":{"type":"webSearch","id":"ws_x","query":"","action":{"type":"other"}},"threadId":"x","turnId":"x"}}
     * {"method":"item/completed","params":{"item":{"type":"webSearch","id":"ws_x","query":"JetBrains Compose Desktop SelectionContainer artifacts text overlap issue","action":{"type":"search","query":"JetBrains Compose Desktop SelectionContainer artifacts text overlap issue","queries":["JetBrains Compose Desktop SelectionContainer artifacts text overlap issue"]}},"threadId":"x","turnId":"x"}}
     */
    fun parseWebSearch(item: JsonObject, status: ItemStatus): ProviderItem {
        return ProviderItem(
            id = item.string("id") ?: "item-webSearch",
            kind = ItemKind.TOOL_CALL,
            status = status,
            name = "web_search",
            text = extractWebSearchText(item),
        )
    }

    /**
     * {"method":"item/started","params":{"item":{"type":"mcpToolCall","id":"call_mvmZLEPiStaVAJCjyL8a9C9B","server":"cloudview-gray","tool":"get_figma_node","status":"inProgress","arguments":{"fileKey":"kUYVH0Cp30Bt1KyItM2JtO","nodeId":"12:182","depth":2},"result":null,"error":null,"durationMs":null},"threadId":"019d5956-dbbc-7071-9d8c-ff08ae6eb533","turnId":"019d5957-fce2-7370-b9ff-d4b0f45bcf1e"}}
     * {"method":"item/completed","params":{"item":{"type":"mcpToolCall","id":"call_mvmZLEPiStaVAJCjyL8a9C9B","server":"cloudview-gray","tool":"get_figma_node","status":"completed","arguments":{"fileKey":"kUYVH0Cp30Bt1KyItM2JtO","nodeId":"12:182","depth":2},"result":{"content":[{"type":"text","text":"{\"name\":\"多窗口\"}"}]},"error":null,"durationMs":null},"threadId":"019d5956-dbbc-7071-9d8c-ff08ae6eb533","turnId":"019d5957-fce2-7370-b9ff-d4b0f45bcf1e"}}
     */
    fun parseMcpToolCall(item: JsonObject, status: ItemStatus): ProviderItem {
        val server = item.string("server").orEmpty().trim()
        return ProviderItem(
            id = item.string("id") ?: "item-mcpToolCall",
            kind = ItemKind.TOOL_CALL,
            status = status,
            name = server.takeIf { it.isNotBlank() }?.let { "mcp:$it" } ?: "mcp",
            text = CodexMcpToolContentFormatter.formatBody(item),
        )
    }

    /**
     * {"method":"item/started","params":{"item":{"type":"plan","id":"019d4d6a-38f5-7df2-ada6-28943def2f9d-plan","text":""},"threadId":"x","turnId":"x"}}
     * {"method":"item/completed","params":{"item":{"type":"plan","id":"019d4d6a-38f5-7df2-ada6-28943def2f9d-plan","text":"# CLI 环境检测内核重构计划"},"threadId":"x","turnId":"x"}}
     */
    fun parsePlan(item: JsonObject, status: ItemStatus): ProviderItem {
        return ProviderItem(
            id = item.string("id") ?: "item-plan",
            kind = ItemKind.PLAN_UPDATE,
            status = status,
            name = "Plan Update",
            text = extractText(item),
        )
    }

    /**
     * {"method":"item/started","params":{"item":{"type":"userMessage","id":"msg_u","text":"User request"},"threadId":"x","turnId":"x"}}
     * {"method":"item/completed","params":{"item":{"type":"userMessage","id":"msg_u","text":"User request"},"threadId":"x","turnId":"x"}}
     */
    fun parseUserMessage(item: JsonObject, status: ItemStatus): ProviderItem {
        return ProviderItem(
            id = item.string("id") ?: "item-userMessage",
            kind = ItemKind.NARRATIVE,
            status = status,
            name = "user_message",
            text = extractText(item),
        )
    }

    /**
     * {"method":"item/started","params":{"item":{"type":"customToolCall","id":"tool_x","name":"custom","text":"..."},"threadId":"x","turnId":"x"}}
     * {"method":"item/completed","params":{"item":{"type":"fooBarBaz","id":"item_x","status":"completed"},"threadId":"x","turnId":"x"}}
     */
    fun parseFallback(item: JsonObject, normalizedType: String, status: ItemStatus): ProviderItem {
        val fallbackKind = if (normalizedType.contains("tool") || normalizedType.contains("call")) {
            ItemKind.TOOL_CALL
        } else {
            ItemKind.UNKNOWN
        }
        return ProviderItem(
            id = item.string("id") ?: "item-${normalizedType.ifBlank { "unknown" }}",
            kind = fallbackKind,
            status = status,
            name = item.string("title") ?: item.string("name") ?: item.string("toolName") ?: item.string("tool_name"),
            text = extractText(item),
            command = item.string("command"),
            cwd = item.string("cwd"),
        )
    }

    fun extractText(item: JsonObject): String {
        return item.string("text")
            ?: item.string("output")
            ?: item.string("aggregatedOutput")
            ?: item.string("query")
            ?: item.objectValue("content")?.string("text")
            ?: item.arrayValue("content")?.firstTextBlock()
            ?: ""
    }

    private fun extractWebSearchText(item: JsonObject): String {
        val query = item.string("query").orEmpty().trim()
        val action = item.objectValue("action")
        if (action == null) return query
        val detail = webSearchDetail(query = query, action = action)
        return detail.ifBlank { query }
    }

    private fun webSearchDetail(query: String, action: JsonObject): String {
        val actionType = action.string("type").orEmpty().trim().lowercase()
        return when (actionType) {
            "search" -> {
                val primary = action.string("query")
                    ?.takeIf { it.isNotBlank() }
                    ?: action.arrayValue("queries")
                        ?.mapNotNull { it.primitiveTextOrNull()?.takeIf(String::isNotBlank) }
                        .orEmpty()
                        .firstOrNull()
                    ?: query
                val extraQueries = action.arrayValue("queries")
                    ?.mapNotNull { it.primitiveTextOrNull()?.takeIf(String::isNotBlank) }
                    .orEmpty()
                    .dropWhile { it == primary }
                listOfNotNull(
                    primary.takeIf { it.isNotBlank() },
                    extraQueries.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                ).joinToString("\n")
            }

            "open_page", "openpage" -> action.string("url").orEmpty().ifBlank { query }

            "find_in_page", "findinpage" -> {
                val pattern = action.string("pattern").orEmpty().trim()
                val url = action.string("url").orEmpty().trim()
                when {
                    pattern.isNotBlank() && url.isNotBlank() -> "'$pattern' in $url"
                    pattern.isNotBlank() -> pattern
                    url.isNotBlank() -> url
                    else -> query
                }
            }

            else -> query
        }
    }

    private fun contextCompactionText(status: ItemStatus): String {
        return when (status) {
            ItemStatus.RUNNING -> "Compacting context"
            ItemStatus.SUCCESS -> "Context compacted"
            ItemStatus.FAILED -> "Context compaction interrupted"
            ItemStatus.SKIPPED -> "Context compaction skipped"
        }
    }

    private fun extractFileChangeSummary(changes: List<ProviderFileChange>, item: JsonObject): String {
        if (changes.isNotEmpty()) {
            return changes.joinToString("\n") { "${it.kind} ${it.path}" }
        }
        return extractText(item)
    }

    private fun extractFileChanges(item: JsonObject, sourceId: String): List<ProviderFileChange> {
        val timestamp = item.longOrNull("updatedAt", "updated_at", "createdAt", "created_at")
            ?: System.currentTimeMillis()
        val changeArrays = listOfNotNull(
            item.arrayValue("changes"),
            item.objectValue("payload")?.arrayValue("changes"),
            item.objectValue("result")?.arrayValue("changes"),
            item.objectValue("proposal")?.arrayValue("changes"),
            item.objectValue("fileChange")?.arrayValue("changes"),
            item.objectValue("file_change")?.arrayValue("changes"),
        )
        changeArrays.forEach { changes ->
            val parsed = changes.mapIndexedNotNull { index, change ->
                val value = change as? JsonObject ?: return@mapIndexedNotNull null
                val path = value.string("path")
                    ?: value.string("filePath")
                    ?: value.string("file_path")
                    ?: return@mapIndexedNotNull null
                val oldContent = value.string("oldContent") ?: value.string("old_content")
                val newContent = value.string("newContent") ?: value.string("new_content") ?: value.string("content")
                val computedStats = FileChangeMetrics.fromContents(oldContent = oldContent, newContent = newContent)
                ProviderFileChange(
                    sourceScopedId = "$sourceId:$index",
                    path = path,
                    kind = extractFileChangeKind(value),
                    timestamp = timestamp,
                    addedLines = value.intOrNull("addedLines", "added_lines") ?: computedStats?.addedLines,
                    deletedLines = value.intOrNull("deletedLines", "deleted_lines") ?: computedStats?.deletedLines,
                    unifiedDiff = value.string("diff"),
                    oldContent = oldContent,
                    newContent = newContent,
                )
            }
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun extractFileChangeKind(value: JsonObject): String {
        val kindField = value["kind"]
        if (kindField is JsonObject) {
            return kindField.string("type").orEmpty().ifBlank { "update" }
        }
        return value.string("kind").orEmpty().ifBlank { "update" }
    }
}
