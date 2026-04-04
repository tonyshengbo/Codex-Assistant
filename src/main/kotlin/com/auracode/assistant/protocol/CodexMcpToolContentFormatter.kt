package com.auracode.assistant.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object CodexMcpToolContentFormatter {
    /**
     * {"method":"item/started","params":{"item":{"type":"mcpToolCall","id":"call_mvmZLEPiStaVAJCjyL8a9C9B","server":"cloudview-gray","tool":"get_figma_node","status":"inProgress","arguments":{"fileKey":"kUYVH0Cp30Bt1KyItM2JtO","nodeId":"12:182","depth":2},"result":null,"error":null,"durationMs":null},"threadId":"019d5956-dbbc-7071-9d8c-ff08ae6eb533","turnId":"019d5957-fce2-7370-b9ff-d4b0f45bcf1e"}}
     * {"method":"item/completed","params":{"item":{"type":"mcpToolCall","id":"call_mvmZLEPiStaVAJCjyL8a9C9B","server":"cloudview-gray","tool":"get_figma_node","status":"completed","arguments":{"fileKey":"kUYVH0Cp30Bt1KyItM2JtO","nodeId":"12:182","depth":2},"result":{"content":[{"type":"text","text":"{\"name\":\"多窗口\"}"}]},"error":null,"durationMs":null},"threadId":"019d5956-dbbc-7071-9d8c-ff08ae6eb533","turnId":"019d5957-fce2-7370-b9ff-d4b0f45bcf1e"}}
     */
    fun formatBody(item: JsonObject): String {
        val server = item.string("server").orEmpty().trim()
        val tool = item.string("tool").orEmpty().trim()
        val arguments = item.objectValue("arguments")?.toString().orEmpty()
        val resultText = item.objectValue("result")?.let(::extractResultText).orEmpty()
        val errorText = item.objectValue("error")?.toString().orEmpty()

        return buildString {
            listOfNotNull(
                server.takeIf { it.isNotBlank() }?.let { "- Server: `$it`" },
                tool.takeIf { it.isNotBlank() }?.let { "- Tool: `$it`" },
            ).forEach { appendLine(it) }

            if (arguments.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine("**Arguments**")
                appendLine()
                appendLine(markdownCodeBlock(arguments))
            }

            if (resultText.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine("**Result**")
                appendLine()
                appendLine(markdownCodeBlock(resultText))
            } else if (errorText.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine("**Error**")
                appendLine()
                appendLine(markdownCodeBlock(errorText))
            }
        }.trim()
    }

    private fun extractResultText(result: JsonObject): String {
        return result.string("text")
            ?: result.objectValue("content")?.string("text")
            ?: result.arrayValue("content")?.firstTextBlock()
            ?: result.arrayValue("content")
                ?.mapNotNull { element ->
                    val obj = element as? JsonObject ?: return@mapNotNull null
                    obj.string("text")?.takeIf { it.isNotBlank() }
                        ?: (obj["json"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                }
                .orEmpty()
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
            ?: result.toString()
    }

    private fun markdownCodeBlock(content: String): String {
        val language = if (content.looksLikeJson()) "json" else "text"
        return buildString {
            appendLine("```$language")
            appendLine(content.trim())
            append("```")
        }
    }

    private fun String.looksLikeJson(): Boolean {
        val trimmed = trim()
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
    }
}
