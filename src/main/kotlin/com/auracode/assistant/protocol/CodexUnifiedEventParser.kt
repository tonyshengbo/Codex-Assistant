package com.auracode.assistant.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

object CodexUnifiedEventParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val router = CodexUnifiedMethodEventRouter()

    fun parseLine(line: String): UnifiedEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        return router.parse(obj)
    }
}
