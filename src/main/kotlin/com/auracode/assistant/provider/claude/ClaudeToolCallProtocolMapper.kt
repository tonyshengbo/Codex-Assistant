package com.auracode.assistant.provider.claude

import com.auracode.assistant.diff.FileChangeMetrics
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.ProviderFileChange
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.protocol.ProviderPlanStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 将 Claude 工具调用快照映射为 Aura timeline 能直接消费的结构化统一条目。
 */
internal class ClaudeToolCallProtocolMapper(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /** 根据工具名称将 Claude 工具节点转换为对应的 ProviderItem。 */
    fun map(
        ownerId: String,
        event: ClaudeConversationEvent.ToolCallUpdated,
    ): ProviderItem {
        return when (event.toolName.trim().lowercase()) {
            "read" -> mapRead(ownerId = ownerId, event = event)
            "write" -> mapWrite(ownerId = ownerId, event = event)
            "edit" -> mapEdit(ownerId = ownerId, event = event)
            else -> mapFallback(ownerId = ownerId, event = event)
        }
    }

    /** 将 TodoWrite 输入转换为运行态计划快照；解析失败时返回空步骤与兜底正文。 */
    fun mapTodoWritePlan(
        event: ClaudeConversationEvent.ToolCallUpdated,
    ): ClaudeTodoPlanSnapshot {
        val input = parseJsonObject(event.inputJson)
        val todos = input?.get("todos") as? JsonArray
        val steps = todos?.mapNotNull { element ->
            val todo = element as? JsonObject ?: return@mapNotNull null
            val content = todo.string("content", "title", "text", "activeForm").orEmpty()
            if (content.isBlank()) return@mapNotNull null
            ProviderPlanStep(
                step = content,
                status = todoStatusValue(todo.string("status").orEmpty()),
            )
        }.orEmpty()
        return ClaudeTodoPlanSnapshot(
            steps = steps,
            body = steps.takeIf { it.isNotEmpty() }?.joinToString("\n") { step ->
                "${todoStatusCheckbox(step.status)} ${step.step}"
            } ?: fallbackPlanBody(event.inputJson),
        )
    }

    /** 将 Read 映射为命令卡片，复用现有 command 标题与输出面板。 */
    private fun mapRead(
        ownerId: String,
        event: ClaudeConversationEvent.ToolCallUpdated,
    ): ProviderItem {
        val input = parseJsonObject(event.inputJson)
        val filePath = input?.string("file_path", "filePath").orEmpty()
        return ProviderItem(
            id = toolItemId(ownerId = ownerId, toolUseId = event.toolUseId),
            kind = ItemKind.COMMAND_EXEC,
            status = unifiedStatus(event),
            name = "Read",
            text = event.outputText,
            command = filePath.takeIf { it.isNotBlank() }?.let { "cat $it" },
            filePath = filePath.takeIf { it.isNotBlank() },
        )
    }

    /** 将 Write 映射为文件变更卡片。 */
    private fun mapWrite(
        ownerId: String,
        event: ClaudeConversationEvent.ToolCallUpdated,
    ): ProviderItem {
        val input = parseJsonObject(event.inputJson)
        val filePath = input?.string("file_path", "filePath").orEmpty()
        val newContent = input?.string("content").orEmpty()
        val changeKind = when {
            event.outputText.orEmpty().contains("updated successfully", ignoreCase = true) -> "update"
            else -> "create"
        }
        val fileChange = ProviderFileChange(
            sourceScopedId = "${toolItemId(ownerId = ownerId, toolUseId = event.toolUseId)}:0",
            path = filePath,
            kind = changeKind,
            addedLines = newContent.lineCountOrNull(),
            oldContent = null,
            newContent = newContent.ifBlank { null },
        )
        return ProviderItem(
            id = toolItemId(ownerId = ownerId, toolUseId = event.toolUseId),
            kind = ItemKind.DIFF_APPLY,
            status = unifiedStatus(event),
            name = "File Changes",
            text = "$changeKind $filePath".trim(),
            fileChanges = filePath.takeIf { it.isNotBlank() }?.let { listOf(fileChange) }.orEmpty(),
        )
    }

    /** 将 Edit 映射为文件变更卡片。 */
    private fun mapEdit(
        ownerId: String,
        event: ClaudeConversationEvent.ToolCallUpdated,
    ): ProviderItem {
        val input = parseJsonObject(event.inputJson)
        val filePath = input?.string("file_path", "filePath").orEmpty()
        val oldText = input?.string("old_string", "oldString").orEmpty()
        val newText = input?.string("new_string", "newString").orEmpty()
        val metrics = FileChangeMetrics.fromContents(
            oldContent = oldText.ifBlank { null },
            newContent = newText.ifBlank { null },
        )
        val fileChange = ProviderFileChange(
            sourceScopedId = "${toolItemId(ownerId = ownerId, toolUseId = event.toolUseId)}:0",
            path = filePath,
            kind = "update",
            addedLines = metrics?.addedLines,
            deletedLines = metrics?.deletedLines,
            oldContent = oldText.ifBlank { null },
            newContent = newText.ifBlank { null },
        )
        return ProviderItem(
            id = toolItemId(ownerId = ownerId, toolUseId = event.toolUseId),
            kind = ItemKind.DIFF_APPLY,
            status = unifiedStatus(event),
            name = "File Changes",
            text = "update $filePath".trim(),
            fileChanges = filePath.takeIf { it.isNotBlank() }?.let { listOf(fileChange) }.orEmpty(),
        )
    }

    /** 未知工具继续保留通用工具卡片回退。 */
    private fun mapFallback(
        ownerId: String,
        event: ClaudeConversationEvent.ToolCallUpdated,
    ): ProviderItem {
        return ProviderItem(
            id = toolItemId(ownerId = ownerId, toolUseId = event.toolUseId),
            kind = ItemKind.TOOL_CALL,
            status = unifiedStatus(event),
            name = event.toolName,
            text = fallbackToolBody(event),
        )
    }

    /** 在 TodoWrite 输入尚未成形时，给计划卡片一个稳定的占位正文。 */
    private fun fallbackPlanBody(inputJson: String): String {
        return inputJson.takeIf { it.isNotBlank() } ?: "Updating plan"
    }

    /** 将通用工具输入/输出拼装为兜底正文。 */
    private fun fallbackToolBody(event: ClaudeConversationEvent.ToolCallUpdated): String {
        return buildString {
            val normalizedInput = event.inputJson.trim()
            if (normalizedInput.isNotBlank()) {
                append("Input\n\n```json\n")
                append(normalizedInput)
                append("\n```")
            }
            val normalizedOutput = event.outputText?.trim().orEmpty()
            if (normalizedOutput.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Result\n\n")
                append(normalizedOutput)
            }
        }
    }

    /** 解析工具输入 JSON；不完整增量会安全返回 null。 */
    private fun parseJsonObject(inputJson: String): JsonObject? {
        val normalized = inputJson.trim()
        if (normalized.isBlank()) return null
        return runCatching { json.parseToJsonElement(normalized).jsonObject }.getOrNull()
    }

    /** 生成工具条目的稳定统一 ID。 */
    private fun toolItemId(
        ownerId: String,
        toolUseId: String,
    ): String {
        return "$ownerId:tool:$toolUseId"
    }

    /** 将 Claude 工具状态转换为 ProviderItem 状态。 */
    private fun unifiedStatus(event: ClaudeConversationEvent.ToolCallUpdated): ItemStatus {
        return when {
            event.completed && event.isError -> ItemStatus.FAILED
            event.completed -> ItemStatus.SUCCESS
            else -> ItemStatus.RUNNING
        }
    }

    /** 将 Todo 状态映射为 markdown checklist 勾选前缀。 */
    private fun todoStatusCheckbox(status: String): String {
        return when (todoStatusValue(status)) {
            "completed" -> "- [x]"
            "in_progress" -> "- [~]"
            else -> "- [ ]"
        }
    }

    /** 将 Todo 状态归一化为统一运行计划状态值。 */
    private fun todoStatusValue(status: String): String {
        return when (status.trim().lowercase()) {
            "completed", "success", "done" -> "completed"
            "inprogress", "in_progress", "running", "active" -> "in_progress"
            else -> "pending"
        }
    }

    /** 从 JsonObject 中读取原始字符串字段。 */
    private fun JsonObject.string(vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.contentOrNull
        }
    }

    /** 将单文件内容换算成简易新增行数。 */
    private fun String.lineCountOrNull(): Int? {
        return takeIf { it.isNotBlank() }?.lineSequence()?.count()
    }

    /** 承载 TodoWrite 解析结果，供 provider 组装 RunningPlanUpdated。 */
    data class ClaudeTodoPlanSnapshot(
        val steps: List<ProviderPlanStep>,
        val body: String,
    )
}
