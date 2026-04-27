package com.auracode.assistant.provider.claude

import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.provider.session.ProviderProtocolDomainMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * 从 Claude CLI 本地 JSONL 文件中读取历史会话记录。
 *
 * Claude CLI 将每次会话持久化到：
 *   ~/.claude/projects/<encoded-cwd>/<session-id>.jsonl
 * 其中 encoded-cwd 是工作目录路径将 "/" 替换为 "-" 后的结果。
 */
internal class ClaudeLocalHistoryReader(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val claudeProjectsDir: Path = Path.of(System.getProperty("user.home"), ".claude", "projects"),
) {
    private val parser: ClaudeStreamEventParser = ClaudeStreamEventParser(json = json)
    private val toolCallItemMapper: ClaudeToolCallProtocolMapper = ClaudeToolCallProtocolMapper(json = json)

    /**
     * 根据 sessionId 在所有项目目录中查找对应的 JSONL 文件。
     * 只搜索一层深度（projects/<encoded-cwd>/<session-id>.jsonl），不递归进入子目录。
     */
    fun findSessionFile(sessionId: String): Path? {
        if (!Files.exists(claudeProjectsDir)) return null
        return Files.list(claudeProjectsDir).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .map { it.resolve("$sessionId.jsonl") }
                .filter { Files.exists(it) }
                .findFirst()
                .orElse(null)
        }
    }

    /**
     * 读取并解析 JSONL 文件，将 user/assistant 条目转换为 ProviderEvent 列表。
     * 每对 user+assistant 构成一个 turn：TurnStarted → ItemUpdated(user) → ItemUpdated(assistant) → TurnCompleted。
     */
    fun readHistory(sessionId: String): ConversationHistoryPage {
        val file = findSessionFile(sessionId)
            ?: return ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)

        val events = mutableListOf<ProviderEvent>()
        var pendingTurnId: String? = null
        val toolStates = linkedMapOf<String, HistoryToolState>()

        file.toFile().bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith("{")) return@forEach

                val payload = runCatching {
                    json.parseToJsonElement(trimmed).jsonObject
                }.getOrNull() ?: return@forEach

                // 跳过 sidechain 条目（子 agent 的消息不属于主对话）
                if (payload["isSidechain"]?.jsonPrimitive?.booleanOrNull == true) return@forEach

                val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val uuid = payload["uuid"]?.jsonPrimitive?.contentOrNull ?: return@forEach

                when (type) {
                    "user" -> {
                        // isMeta 行是 CLI 内部消息，跳过用户消息提取，但仍处理工具结果
                        val isMeta = payload["isMeta"]?.jsonPrimitive?.booleanOrNull == true
                        appendUserEvents(
                            sessionId = sessionId,
                            uuid = uuid,
                            payload = if (isMeta) JsonObject(emptyMap()) else payload,
                            rawLine = trimmed,
                            toolStates = toolStates,
                            pendingTurnId = pendingTurnId,
                            events = events,
                        )?.let { pendingTurnId = it }
                    }
                    "assistant" -> {
                        pendingTurnId = appendAssistantEvents(
                            sessionId = sessionId,
                            uuid = uuid,
                            payload = payload,
                            rawLine = trimmed,
                            toolStates = toolStates,
                            pendingTurnId = pendingTurnId,
                            events = events,
                        )
                    }
                }
            }
        }

        val mapper = ProviderProtocolDomainMapper()
        return ConversationHistoryPage(
            events = events.flatMap(mapper::map),
            hasOlder = false,
            olderCursor = null,
        )
    }

    /**
     * 处理历史里的 user 行，既恢复真实用户消息，也回放工具结果更新。
     */
    private fun appendUserEvents(
        sessionId: String,
        uuid: String,
        payload: JsonObject,
        rawLine: String,
        toolStates: MutableMap<String, HistoryToolState>,
        pendingTurnId: String?,
        events: MutableList<ProviderEvent>,
    ): String? {
        val nextPendingTurnId = extractPlainMessageText(payload)?.let { text ->
            events += ProviderEvent.TurnStarted(turnId = uuid, threadId = sessionId)
            events += ProviderEvent.ItemUpdated(
                item = ProviderItem(
                    id = uuid,
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.SUCCESS,
                    name = "user_message",
                    text = text,
                ),
            )
            uuid
        } ?: pendingTurnId

        val rawEvent = parser.parse(rawLine) as? ClaudeStreamEvent.UserToolResult ?: return nextPendingTurnId
        val current = toolStates[rawEvent.toolUseId] ?: HistoryToolState(
            ownerId = uuid,
            event = ClaudeConversationEvent.ToolCallUpdated(
                toolUseId = rawEvent.toolUseId,
                toolName = rawEvent.toolUseId,
                inputJson = "",
            ),
        )
        val updated = current.event.copy(
            outputText = rawEvent.content,
            isError = rawEvent.isError,
            completed = true,
        )
        toolStates[rawEvent.toolUseId] = current.copy(event = updated)
        if (!updated.toolName.equals("TodoWrite", ignoreCase = true)) {
            events += ProviderEvent.ItemUpdated(
                toolCallItemMapper.map(ownerId = current.ownerId, event = updated),
            )
        }
        return nextPendingTurnId
    }

    /**
     * 处理历史里的 assistant 行，恢复正文、reasoning 和工具过程节点。
     */
    private fun appendAssistantEvents(
        sessionId: String,
        uuid: String,
        payload: JsonObject,
        rawLine: String,
        toolStates: MutableMap<String, HistoryToolState>,
        pendingTurnId: String?,
        events: MutableList<ProviderEvent>,
    ): String? {
        val snapshot = parser.parse(rawLine) as? ClaudeStreamEvent.AssistantSnapshot
        if (snapshot == null) {
            val rawText = extractPlainMessageText(payload) ?: return pendingTurnId
            val text = stripThinkingTags(rawText).takeIf { it.isNotBlank() } ?: return pendingTurnId
            events += ProviderEvent.ItemUpdated(
                item = ProviderItem(
                    id = payload.messageIdOrUuid(uuid),
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.SUCCESS,
                    name = "message",
                    text = text,
                ),
            )
            events += ProviderEvent.TurnCompleted(
                turnId = pendingTurnId ?: uuid,
                outcome = TurnOutcome.SUCCESS,
            )
            return null
        }

        snapshot.content.filterIsInstance<ClaudeMessageContent.ToolUse>().forEach { content ->
            val toolEvent = ClaudeConversationEvent.ToolCallUpdated(
                toolUseId = content.toolUseId,
                toolName = content.name,
                inputJson = content.inputJson,
                completed = false,
            )
            toolStates[content.toolUseId] = HistoryToolState(ownerId = uuid, event = toolEvent)
            if (!content.name.equals("TodoWrite", ignoreCase = true)) {
                events += ProviderEvent.ItemUpdated(
                    toolCallItemMapper.map(ownerId = uuid, event = toolEvent),
                )
            }
        }

        snapshot.content.filterIsInstance<ClaudeMessageContent.Thinking>().forEachIndexed { index, content ->
            if (content.text.isBlank()) return@forEachIndexed
            events += ProviderEvent.ItemUpdated(
                item = ProviderItem(
                    id = "${payload.messageIdOrUuid(uuid)}:reasoning:$index",
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.SUCCESS,
                    name = "reasoning",
                    text = content.text,
                ),
            )
        }

        val rawText = snapshot.content
            .filterIsInstance<ClaudeMessageContent.Text>()
            .joinToString(separator = "") { it.text }
        val text = stripThinkingTags(rawText).takeIf { it.isNotBlank() }
        if (text != null) {
            events += ProviderEvent.ItemUpdated(
                item = ProviderItem(
                    id = payload.messageIdOrUuid(uuid),
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.SUCCESS,
                    name = "message",
                    text = text,
                ),
            )
        }

        val hasToolUse = snapshot.content.any { it is ClaudeMessageContent.ToolUse }
        if (text != null && !hasToolUse) {
            events += ProviderEvent.TurnCompleted(
                turnId = pendingTurnId ?: uuid,
                outcome = TurnOutcome.SUCCESS,
            )
            return null
        }
        return pendingTurnId
    }

    /**
     * 从 message.content 中提取纯文本内容。
     * 支持数组格式（[{"type":"text","text":"..."}]）和字符串格式（"..."）。
     * 忽略 tool_use、tool_result 等非文本块。
     */
    private fun extractPlainMessageText(payload: JsonObject): String? {
        val messageObj = payload["message"]?.jsonObject ?: return null
        val contentElement = messageObj["content"] ?: return null

        val text = when {
            contentElement is kotlinx.serialization.json.JsonPrimitive -> {
                // 字符串格式：过滤掉 CLI 内部系统消息（以 < 开头的 XML 标签）
                val raw = contentElement.contentOrNull?.trim().orEmpty()
                if (raw.startsWith("<")) return null
                raw
            }
            else -> {
                val array = runCatching { contentElement.jsonArray }.getOrNull() ?: return null
                array.mapNotNull { element ->
                    runCatching {
                        val obj = element.jsonObject
                        if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                            obj["text"]?.jsonPrimitive?.contentOrNull
                        } else {
                            null
                        }
                    }.getOrNull()
                }.joinToString("\n")
            }
        }

        return text.trim().takeIf { it.isNotBlank() }
    }

    /**
     * 从文本中分离 <thinking>...</thinking> 块，返回主文本部分。
     */
    private fun stripThinkingTags(text: String): String {
        val startTag = "<thinking>"
        val endTag = "</thinking>"
        val startIdx = text.indexOf(startTag)
        if (startIdx == -1) return text
        val endIdx = text.indexOf(endTag)
        return if (endIdx == -1) "" else text.substring(endIdx + endTag.length).trim()
    }

    /** 读取 assistant message.id；缺失时回退到当前行 uuid。 */
    private fun JsonObject.messageIdOrUuid(uuid: String): String {
        return this["message"]?.jsonObject
            ?.get("id")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: uuid
    }

    /** 保存历史回放中的工具节点归属与当前状态。 */
    private data class HistoryToolState(
        val ownerId: String,
        val event: ClaudeConversationEvent.ToolCallUpdated,
    )
}
