package com.auracode.assistant.provider.codex.protocol

import com.auracode.assistant.protocol.ProviderEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

object CodexProviderEventParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val router = CodexProtocolMethodRouter()

    fun parseLine(line: String): ProviderEvent? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return null
        return router.parse(obj)
    }
}
