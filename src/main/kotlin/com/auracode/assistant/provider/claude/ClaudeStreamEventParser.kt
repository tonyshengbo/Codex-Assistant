package com.auracode.assistant.provider.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 将 Claude CLI 的单行 stream-json 解析为内部原始事件模型。
 */
internal class ClaudeStreamEventParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /** 解析一行 Claude 输出；未知结构或噪声会返回 null。 */
    fun parse(line: String): ClaudeStreamEvent? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return null
        val payload = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        val type = payload.string("type") ?: return null
        return when (type) {
            "system" -> parseSystem(payload)
            "stream_event" -> parseStreamEvent(payload)
            "assistant" -> parseAssistant(payload)
            "user" -> parseUser(payload)
            "result" -> parseResult(payload)
            "error" -> ClaudeStreamEvent.Error(
                message = payload.string("message").orEmpty().ifBlank { "Claude CLI returned an error." },
                sessionId = payload.string("session_id", "sessionId"),
            )
            "control_request" -> parseControlRequest(payload)
            else -> null
        }
    }

    /** 解析 Claude 的 system/init 事件。 */
    private fun parseSystem(payload: JsonObject): ClaudeStreamEvent? {
        val subtype = payload.string("subtype")
        if (subtype != "init") return null
        val sessionId = payload.string("session_id", "sessionId") ?: return null
        return ClaudeStreamEvent.SessionStarted(
            sessionId = sessionId,
            model = payload.string("model"),
        )
    }

    /** 解析 stream_event 里的 message/content_block/message_delta 等细粒度事件。 */
    private fun parseStreamEvent(payload: JsonObject): ClaudeStreamEvent? {
        val event = payload.objectValue("event") ?: return null
        val sessionId = payload.string("session_id", "sessionId")
        return when (event.string("type")) {
            "message_start" -> {
                val message = event.objectValue("message") ?: return null
                val messageId = message.string("id") ?: return null
                ClaudeStreamEvent.MessageStart(
                    sessionId = sessionId,
                    messageId = messageId,
                    model = message.string("model"),
                    usage = message.objectValue("usage")?.toTokenUsage(),
                )
            }

            "content_block_start" -> {
                val block = event.objectValue("content_block") ?: return null
                val index = event.int("index")
                val parsedBlock = when (block.string("type")) {
                    "tool_use" -> {
                        val toolUseId = block.string("id") ?: return null
                        ClaudeContentBlockStart.ToolUse(
                            toolUseId = toolUseId,
                            name = block.string("name").orEmpty().ifBlank { toolUseId },
                            inputJson = block["input"]?.toCompactJson().orEmpty(),
                        )
                    }

                    "thinking" -> ClaudeContentBlockStart.Thinking(
                        thinking = block.rawString("thinking").orEmpty(),
                    )

                    "text" -> ClaudeContentBlockStart.Text(
                        text = block.rawString("text").orEmpty(),
                    )

                    else -> return null
                }
                ClaudeStreamEvent.ContentBlockStarted(
                    sessionId = sessionId,
                    index = index,
                    block = parsedBlock,
                )
            }

            "content_block_delta" -> {
                val delta = event.objectValue("delta") ?: return null
                val index = event.int("index")
                val parsedDelta = when (delta.string("type")) {
                    "input_json_delta" -> ClaudeContentDelta.InputJson(
                        partialJson = delta.rawString("partial_json").orEmpty(),
                    )

                    "thinking_delta" -> ClaudeContentDelta.Thinking(
                        thinking = delta.rawString("thinking").orEmpty(),
                    )

                    "text_delta" -> ClaudeContentDelta.Text(
                        text = delta.rawString("text").orEmpty(),
                    )

                    "signature_delta" -> ClaudeContentDelta.Signature(
                        signature = delta.rawString("signature").orEmpty(),
                    )

                    else -> return null
                }
                ClaudeStreamEvent.ContentBlockDelta(
                    sessionId = sessionId,
                    index = index,
                    delta = parsedDelta,
                )
            }

            "content_block_stop" -> ClaudeStreamEvent.ContentBlockStopped(
                sessionId = sessionId,
                index = event.int("index"),
            )

            "message_delta" -> ClaudeStreamEvent.MessageDelta(
                sessionId = sessionId,
                stopReason = event.objectValue("delta")?.string("stop_reason"),
                usage = event.objectValue("usage")?.toTokenUsage(),
            )

            "message_stop" -> ClaudeStreamEvent.MessageStopped(
                sessionId = sessionId,
            )

            else -> null
        }
    }

    /** 解析 Claude 的 assistant 快照。 */
    private fun parseAssistant(payload: JsonObject): ClaudeStreamEvent? {
        val message = payload.objectValue("message") ?: return null
        return ClaudeStreamEvent.AssistantSnapshot(
            sessionId = payload.string("session_id", "sessionId"),
            messageId = message.string("id"),
            content = parseMessageContent(message["content"]),
            errorType = payload.string("error"),
        )
    }

    /** 解析 Claude 的 user 快照，目前只提取 tool_result。 */
    private fun parseUser(payload: JsonObject): ClaudeStreamEvent? {
        val message = payload.objectValue("message") ?: return null
        val toolResult = parseMessageContent(message["content"]).filterIsInstance<ClaudeMessageContent.ToolResult>().firstOrNull()
            ?: return null
        return ClaudeStreamEvent.UserToolResult(
            sessionId = payload.string("session_id", "sessionId"),
            toolUseId = toolResult.toolUseId,
            content = toolResult.content,
            isError = toolResult.isError,
        )
    }

    /** 解析 Claude 的最终 result 事件，并保留最终 usage 与 modelUsage。 */
    private fun parseResult(payload: JsonObject): ClaudeStreamEvent.Result {
        return ClaudeStreamEvent.Result(
            sessionId = payload.string("session_id", "sessionId"),
            subtype = payload.string("subtype"),
            resultText = payload.string("result"),
            isError = payload.boolean("is_error", "isError"),
            usage = payload.objectValue("usage")?.toTokenUsage(),
            modelUsage = payload.objectValue("modelUsage")
                ?.entries
                ?.mapNotNull { (modelId, value) ->
                    val usage = value.jsonObject.toModelUsage()
                    modelId.trim().takeIf { it.isNotBlank() }?.let { it to usage }
                }
                ?.toMap()
                .orEmpty(),
        )
    }

    /** 解析 assistant/user content 数组。 */
    private fun parseMessageContent(content: JsonElement?): List<ClaudeMessageContent> {
        return when (content) {
            is JsonArray -> content.mapNotNull(::parseMessageContentItem)
            is JsonPrimitive -> content.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(ClaudeMessageContent.Text(text = it)) }
                .orEmpty()

            else -> emptyList()
        }
    }

    /** 解析单个 content 块。 */
    private fun parseMessageContentItem(element: JsonElement): ClaudeMessageContent? {
        val item = element as? JsonObject ?: return null
        return when (item.string("type")) {
            "tool_use" -> {
                val toolUseId = item.string("id") ?: return null
                ClaudeMessageContent.ToolUse(
                    toolUseId = toolUseId,
                    name = item.string("name").orEmpty().ifBlank { toolUseId },
                    inputJson = item["input"]?.toCompactJson().orEmpty(),
                )
            }

            "thinking" -> ClaudeMessageContent.Thinking(
                text = item.rawString("thinking").orEmpty(),
            )

            "text" -> ClaudeMessageContent.Text(
                text = item.rawString("text").orEmpty(),
            )

            "tool_result" -> {
                val toolUseId = item.string("tool_use_id", "toolUseId") ?: return null
                ClaudeMessageContent.ToolResult(
                    toolUseId = toolUseId,
                    content = item["content"]?.toToolResultText().orEmpty(),
                    isError = item.boolean("is_error", "isError"),
                )
            }

            else -> null
        }
    }

    /** 解析 Claude CLI 的工具授权请求事件。 */
    private fun parseControlRequest(payload: JsonObject): ClaudeStreamEvent.ControlRequest? {
        val requestId = payload.string("request_id", "requestId") ?: return null
        val request = payload.objectValue("request") ?: return null
        if (request.string("subtype") != "can_use_tool") return null
        val toolName = request.string("tool_name", "toolName") ?: return null
        val inputObj = request.objectValue("input") ?: JsonObject(emptyMap())
        val toolInput = inputObj.entries.associate { (k, v) ->
            k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString())
        }
        return ClaudeStreamEvent.ControlRequest(
            requestId = requestId,
            toolName = toolName,
            toolInput = toolInput,
            sessionId = payload.string("session_id", "sessionId"),
        )
    }

    /** 将 usage 对象转换为统一的 token usage 结构。 */
    private fun JsonObject.toTokenUsage(): ClaudeTokenUsage {
        return ClaudeTokenUsage(
            inputTokens = int("input_tokens", "inputTokens"),
            cachedInputTokens = int("cache_read_input_tokens", "cacheReadInputTokens"),
            outputTokens = int("output_tokens", "outputTokens"),
        )
    }

    /** 将 modelUsage 里的模型对象转换为统一模型用量结构。 */
    private fun JsonObject.toModelUsage(): ClaudeModelUsage {
        return ClaudeModelUsage(
            inputTokens = int("inputTokens", "input_tokens"),
            outputTokens = int("outputTokens", "output_tokens"),
            cachedInputTokens = int("cacheReadInputTokens", "cache_read_input_tokens"),
            contextWindow = intOrNull("contextWindow", "context_window"),
            maxOutputTokens = intOrNull("maxOutputTokens", "max_output_tokens"),
        )
    }

    /** 从多个备选 key 中读取非空字符串。 */
    private fun JsonObject.string(vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    /** 从多个备选 key 中读取原始字符串，不做 trim，适用于文本增量字段。 */
    private fun JsonObject.rawString(vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.contentOrNull
        }
    }

    /** 从多个备选 key 中读取布尔值。 */
    private fun JsonObject.boolean(vararg keys: String): Boolean {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        } == "true"
    }

    /** 从多个备选 key 中读取整型；缺失时返回 0。 */
    private fun JsonObject.int(vararg keys: String): Int {
        return intOrNull(*keys) ?: 0
    }

    /** 从多个备选 key 中读取可空整型。 */
    private fun JsonObject.intOrNull(vararg keys: String): Int? {
        return keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()
        }
    }

    /** 读取指定 key 对应的对象值。 */
    private fun JsonObject.objectValue(key: String): JsonObject? {
        return this[key]?.jsonObject
    }

    /** 将任意 JSON 元素压缩成单行 JSON 文本。 */
    private fun JsonElement.toCompactJson(): String {
        return when (this) {
            is JsonPrimitive -> contentOrNull.orEmpty()
            else -> toString()
        }
    }

    /** 将 tool_result 的原始内容转换为适合 UI 展示的文本。 */
    private fun JsonElement.toToolResultText(): String {
        return when (this) {
            is JsonPrimitive -> contentOrNull.orEmpty()
            is JsonObject -> string("text").orEmpty().ifBlank { toCompactJson() }
            is JsonArray -> mapNotNull { element ->
                element.toToolResultText().trim().takeIf { it.isNotBlank() }
            }.joinToString("\n\n").ifBlank { toCompactJson() }
        }
    }
}
