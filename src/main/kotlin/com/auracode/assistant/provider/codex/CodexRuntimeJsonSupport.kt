package com.auracode.assistant.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Provides small JSON helpers shared by the app-server integration classes. */
internal object CodexRuntimeJsonSupport {
    /** Builds the standard initialize payload used by all app-server clients. */
    fun initializeParams(): JsonObject {
        return buildJsonObject {
            putJsonObject("clientInfo") {
                put("name", "codex_sdk_ts")
                put("title", "Aura Code IntelliJ Plugin")
                put("version", "0.120.0")
            }
            putJsonObject("capabilities") {
                put("experimentalApi", true)
            }
        }
    }
}

internal fun JsonObjectBuilder.put(key: String, value: String) {
    put(key, JsonPrimitive(value))
}

internal fun JsonObjectBuilder.put(key: String, value: Boolean) {
    put(key, JsonPrimitive(value))
}

internal fun JsonObjectBuilder.putJsonObject(key: String, builder: JsonObjectBuilder.() -> Unit) {
    put(key, buildJsonObject(builder))
}

internal fun JsonObject.string(key: String): String? {
    return this[key]?.jsonPrimitive?.contentOrNull
}

internal fun JsonObject.objectValue(key: String): JsonObject? {
    return this[key]?.let { runCatching { it.jsonObject }.getOrNull() }
}

internal fun JsonObject.arrayValue(key: String): JsonArray? {
    return this[key] as? JsonArray
}

internal fun JsonObject.int(vararg keys: String): Int {
    return intOrNull(*keys) ?: 0
}

internal fun JsonObject.intOrNull(vararg keys: String): Int? {
    return keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    }
}

internal fun JsonObject.long(vararg keys: String): Long {
    return longOrNull(*keys) ?: 0L
}

internal fun JsonObject.longOrNull(vararg keys: String): Long? {
    return keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }
}
