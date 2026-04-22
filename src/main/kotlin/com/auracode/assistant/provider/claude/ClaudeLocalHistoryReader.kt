package com.auracode.assistant.provider.claude

import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.UnifiedEvent
import com.auracode.assistant.protocol.UnifiedItem
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
    private val toolCallItemMapper: ClaudeToolCallItemMapper = ClaudeToolCallItemMapper(json = json)

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
     * 读取并解析 JSONL 文件，将 user/assistant 条目转换为 UnifiedEvent 列表。
     * 每对 user+assistant 构成一个 turn：TurnStarted → ItemUpdated(user) → ItemUpdated(assistant) → TurnCompleted。
     */
    fun readHistory(sessionId: String): ConversationHistoryPage {
        val file = findSessionFile(sessionId)
            ?: return ConversationHistoryPage(events = emptyList(), hasOlder = false, olderCursor = null)

        val events = mutableListOf<UnifiedEvent>()
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
                        appendUserEvents(
                            sessionId = sessionId,
                            uuid = uuid,
                            payload = payload,
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

        return ConversationHistoryPage(events = events, hasOlder = false, olderCursor = null)
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
        events: MutableList<UnifiedEvent>,
    ): String? {
        val nextPendingTurnId = extractPlainMessageText(payload)?.let { text ->
            events += UnifiedEvent.TurnStarted(turnId = uuid, threadId = sessionId)
            events += UnifiedEvent.ItemUpdated(
                item = UnifiedItem(
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
            events += UnifiedEvent.ItemUpdated(
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
        events: MutableList<UnifiedEvent>,
    ): String? {
        val snapshot = parser.parse(rawLine) as? ClaudeStreamEvent.AssistantSnapshot
        if (snapshot == null) {
            val text = extractPlainMessageText(payload) ?: return pendingTurnId
            events += UnifiedEvent.ItemUpdated(
                item = UnifiedItem(
                    id = payload.messageIdOrUuid(uuid),
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.SUCCESS,
                    name = "message",
                    text = text,
                ),
            )
            events += UnifiedEvent.TurnCompleted(
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
                events += UnifiedEvent.ItemUpdated(
                    toolCallItemMapper.map(ownerId = uuid, event = toolEvent),
                )
            }
        }

        snapshot.content.filterIsInstance<ClaudeMessageContent.Thinking>().forEachIndexed { index, content ->
            if (content.text.isBlank()) return@forEachIndexed
            events += UnifiedEvent.ItemUpdated(
                item = UnifiedItem(
                    id = "${payload.messageIdOrUuid(uuid)}:reasoning:$index",
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.SUCCESS,
                    name = "reasoning",
                    text = content.text,
                ),
            )
        }

        val text = snapshot.content
            .filterIsInstance<ClaudeMessageContent.Text>()
            .joinToString(separator = "") { it.text }
            .takeIf { it.isNotBlank() }
        if (text != null) {
            events += UnifiedEvent.ItemUpdated(
                item = UnifiedItem(
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
            events += UnifiedEvent.TurnCompleted(
                turnId = pendingTurnId ?: uuid,
                outcome = TurnOutcome.SUCCESS,
            )
            return null
        }
        return pendingTurnId
    }

    /**
     * 从 message.content 数组中提取所有 type=text 的块并拼接为字符串。
     * 忽略 tool_use、tool_result 等非文本块。
     */
    private fun extractPlainMessageText(payload: JsonObject): String? {
        val content = runCatching {
            payload["message"]?.jsonObject?.get("content")?.jsonArray
        }.getOrNull() ?: return null

        val text = content.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                    obj["text"]?.jsonPrimitive?.contentOrNull
                } else {
                    null
                }
            }.getOrNull()
        }.joinToString("\n")

        return text.trim().takeIf { it.isNotBlank() }
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
