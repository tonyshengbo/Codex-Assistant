package com.auracode.assistant.provider.claude

import com.auracode.assistant.conversation.ConversationHistoryPage
import com.auracode.assistant.conversation.ConversationSummary
import com.auracode.assistant.conversation.ConversationSummaryPage
import com.auracode.assistant.protocol.ItemKind
import com.auracode.assistant.protocol.ItemStatus
import com.auracode.assistant.protocol.TurnOutcome
import com.auracode.assistant.protocol.ProviderEvent
import com.auracode.assistant.protocol.ProviderItem
import com.auracode.assistant.protocol.ProviderMessageAttachment
import com.auracode.assistant.provider.PromptContextStripper
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
import java.time.Instant
import java.util.Base64
import java.util.UUID

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
     * 枚举指定工作目录下的所有 Claude 会话，返回分页摘要列表。
     *
     * 每个 .jsonl 文件对应一个会话，文件名（去掉 .jsonl）即为 sessionId。
     * 全量提取所有摘要后按 updatedAt 倒序排列，再做内存分页。
     * cursor 为上一页最后一条的 remoteConversationId。
     *
     * @param cwd 工作目录路径，用于定位对应的 encoded-cwd 子目录；为 null 时搜索所有子目录
     * @param pageSize 每页最多返回的会话数
     * @param cursor 分页游标，值为上一页最后一个 sessionId
     * @param searchTerm 在 title 中过滤的关键词（大小写不敏感）
     */
    fun listSessions(
        cwd: String?,
        pageSize: Int,
        cursor: String?,
        searchTerm: String?,
    ): ConversationSummaryPage {
        if (!Files.exists(claudeProjectsDir)) return ConversationSummaryPage(emptyList(), null)

        val encodedCwd = cwd?.replace("/", "-")

        val jsonlFiles = Files.list(claudeProjectsDir).use { stream ->
            stream.filter { Files.isDirectory(it) }
                .filter { dir -> encodedCwd == null || dir.fileName.toString() == encodedCwd }
                .flatMap { dir ->
                    runCatching {
                        Files.list(dir).filter { it.toString().endsWith(".jsonl") }
                    }.getOrElse { java.util.stream.Stream.empty() }
                }
                .toList()
        }

        // 全量提取摘要（每个文件只读头部若干行 + 尾部若干字节），按 updatedAt 倒序
        val allSummaries = jsonlFiles
            .mapNotNull { file ->
                val sessionId = file.fileName.toString().removeSuffix(".jsonl")
                extractSessionSummary(file, sessionId)
            }
            .let { list ->
                if (searchTerm != null) list.filter { it.title.contains(searchTerm, ignoreCase = true) }
                else list
            }
            .sortedByDescending { it.updatedAt }

        // cursor 定位：找到 cursor 对应条目的下一个位置
        val startIndex = if (cursor == null) {
            0
        } else {
            val idx = allSummaries.indexOfFirst { it.remoteConversationId == cursor }
            if (idx == -1) 0 else idx + 1
        }

        val page = allSummaries.subList(startIndex, minOf(startIndex + pageSize, allSummaries.size))
        val nextCursor = if (startIndex + pageSize < allSummaries.size) page.last().remoteConversationId else null

        return ConversationSummaryPage(conversations = page, nextCursor = nextCursor)
    }

    /**
     * 从单个 JSONL 文件中提取会话摘要，只读头部和尾部，避免全量扫描。
     *
     * - createdAt：从文件头部读取第一条有效行的 timestamp
     * - updatedAt：从文件尾部反向扫描最后一条有效行的 timestamp
     * - title：从文件头部读取第一条真实 user 消息内容（前 50 字符）
     */
    private fun extractSessionSummary(file: Path, sessionId: String): ConversationSummary? {
        val headLines = readHeadLines(file, HEAD_SCAN_LINES)
        val tailLines = readTailLines(file, TAIL_SCAN_BYTES)

        var title = ""
        var createdAtSeconds = 0L

        // 从头部提取 createdAt 和 title
        for (line in headLines) {
            val payload = parseJsonLine(line) ?: continue
            if (payload["isSidechain"]?.jsonPrimitive?.booleanOrNull == true) continue

            val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val timestamp = payload["timestamp"]?.jsonPrimitive?.contentOrNull

            if (createdAtSeconds == 0L && timestamp != null) {
                createdAtSeconds = parseIsoTimestampToSeconds(timestamp)
            }

            if (title.isEmpty() && type == "user") {
                val isMeta = payload["isMeta"]?.jsonPrimitive?.booleanOrNull == true
                if (!isMeta) {
                    title = extractPlainMessageText(payload)?.take(TITLE_MAX_LENGTH)?.trim().orEmpty()
                }
            }

            if (createdAtSeconds != 0L && title.isNotEmpty()) break
        }

        if (createdAtSeconds == 0L) return null

        // 从尾部反向扫描最后一条有效行的 timestamp 作为 updatedAt
        val updatedAtSeconds = tailLines.asReversed().firstNotNullOfOrNull { line ->
            val payload = parseJsonLine(line) ?: return@firstNotNullOfOrNull null
            if (payload["isSidechain"]?.jsonPrimitive?.booleanOrNull == true) return@firstNotNullOfOrNull null
            payload["timestamp"]?.jsonPrimitive?.contentOrNull?.let { parseIsoTimestampToSeconds(it) }
                ?.takeIf { it > 0L }
        } ?: createdAtSeconds

        return ConversationSummary(
            remoteConversationId = sessionId,
            title = title,
            createdAt = createdAtSeconds,
            updatedAt = updatedAtSeconds,
            status = "completed",
        )
    }

    /** 读取文件头部最多 [maxLines] 行。 */
    private fun readHeadLines(file: Path, maxLines: Int): List<String> {
        return runCatching {
            file.toFile().bufferedReader().useLines { seq ->
                seq.take(maxLines).toList()
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 从文件尾部读取最后 [tailBytes] 字节，返回其中的完整行列表。
     * 使用 RandomAccessFile 定位到文件末尾附近，避免读取整个文件。
     */
    private fun readTailLines(file: Path, tailBytes: Long): List<String> {
        return runCatching {
            java.io.RandomAccessFile(file.toFile(), "r").use { raf ->
                val fileSize = raf.length()
                val seekPos = maxOf(0L, fileSize - tailBytes)
                raf.seek(seekPos)
                // 如果不是从文件头开始，跳过可能截断的第一行
                if (seekPos > 0L) raf.readLine()
                val lines = mutableListOf<String>()
                var line = raf.readLine()
                while (line != null) {
                    lines += line
                    line = raf.readLine()
                }
                lines
            }
        }.getOrDefault(emptyList())
    }

    /** 解析单行 JSON，失败返回 null。 */
    private fun parseJsonLine(line: String): JsonObject? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    }

    /** 将 ISO 8601 时间字符串（如 "2026-04-19T02:10:07.730Z"）转换为秒级 epoch。 */
    private fun parseIsoTimestampToSeconds(iso: String): Long {
        return runCatching { Instant.parse(iso).epochSecond }.getOrDefault(0L)
    }

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

        val mapper = ProviderProtocolDomainMapper(stampTurnCompletionTime = false)
        return ConversationHistoryPage(
            events = events.flatMap(mapper::map),
            hasOlder = false,
            olderCursor = null,
        )
    }

    /**
     * 处理历史里的 user 行，既恢复真实用户消息，也回放工具结果更新。
     * 图片 content block 会被解码并写入临时文件，以 ProviderMessageAttachment 形式附加到消息。
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
            val imageAttachments = extractImageAttachments(payload)
            events += ProviderEvent.TurnStarted(turnId = uuid, threadId = sessionId)
            events += ProviderEvent.ItemUpdated(
                item = ProviderItem(
                    id = uuid,
                    kind = ItemKind.NARRATIVE,
                    status = ItemStatus.SUCCESS,
                    name = "user_message",
                    text = text,
                    attachments = imageAttachments,
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
            toolCallItemMapper.map(ownerId = current.ownerId, event = updated)?.let { item ->
                events += ProviderEvent.ItemUpdated(item)
            }
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
                toolCallItemMapper.map(ownerId = uuid, event = toolEvent)?.let { item ->
                    events += ProviderEvent.ItemUpdated(item)
                }
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
     * 从 message.content 中提取纯文本内容，并剥离 ClaudeCliLauncher 注入的 Context files 前缀。
     * 支持数组格式（[{"type":"text","text":"..."}]）和字符串格式（"..."）。
     * 忽略 tool_use、tool_result、image 等非文本块。
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

        val trimmed = text.trim().takeIf { it.isNotBlank() } ?: return null
        // Strip "Context files:" prefix block injected by ClaudeCliLauncher.buildPromptText
        return PromptContextStripper.stripClaudeContextPrefix(trimmed).takeIf { it.isNotBlank() }
    }

    /**
     * Extracts image content blocks from a user message payload and writes each image to a
     * temp file so the UI can load it via a regular file path.
     *
     * Temp files are written to the system temp directory under "aura-history-images/" and
     * are not cleaned up automatically — the OS will reclaim them on reboot. Each file is
     * named by a stable UUID derived from the message uuid + index to avoid duplicates on
     * repeated history loads.
     */
    private fun extractImageAttachments(payload: JsonObject): List<ProviderMessageAttachment> {
        val messageObj = payload["message"]?.jsonObject ?: return emptyList()
        val contentArray = runCatching {
            messageObj["content"]?.jsonArray
        }.getOrNull() ?: return emptyList()

        val tempDir = Path.of(System.getProperty("java.io.tmpdir"), "aura-history-images")
        val attachments = mutableListOf<ProviderMessageAttachment>()

        contentArray.forEachIndexed { index, element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@forEachIndexed
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "image") return@forEachIndexed

            val source = obj["source"]?.jsonObject ?: return@forEachIndexed
            if (source["type"]?.jsonPrimitive?.contentOrNull != "base64") return@forEachIndexed

            val mimeType = source["media_type"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
            val base64Data = source["data"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed

            runCatching {
                val ext = mimeType.substringAfter("/").substringBefore(";").ifBlank { "png" }
                // Use a stable name so repeated loads don't create duplicate files
                val stableId = UUID.nameUUIDFromBytes("${payload["uuid"]?.jsonPrimitive?.contentOrNull}:$index".toByteArray())
                Files.createDirectories(tempDir)
                val tempFile = tempDir.resolve("$stableId.$ext")
                if (!Files.exists(tempFile)) {
                    val bytes = Base64.getDecoder().decode(base64Data)
                    Files.write(tempFile, bytes)
                }
                attachments += ProviderMessageAttachment(
                    id = stableId.toString(),
                    kind = "image",
                    displayName = "image.$ext",
                    assetPath = tempFile.toString(),
                    originalPath = tempFile.toString(),
                    mimeType = mimeType,
                    sizeBytes = Files.size(tempFile),
                    status = ItemStatus.SUCCESS,
                )
            }
        }

        return attachments
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

    companion object {
        /** 会话 title 最大截取长度。 */
        private const val TITLE_MAX_LENGTH = 50
        /** 从文件头部扫描的最大行数，用于提取 createdAt 和 title。 */
        private const val HEAD_SCAN_LINES = 30
        /** 从文件尾部读取的字节数，用于提取 updatedAt。 */
        private const val TAIL_SCAN_BYTES = 4096L
    }
}
