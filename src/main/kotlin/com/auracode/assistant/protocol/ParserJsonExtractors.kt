package com.auracode.assistant.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

internal fun JsonObject.longOrNull(vararg keys: String): Long? {
    return keys.firstNotNullOfOrNull { key ->
        this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }
}

internal fun JsonArray.firstTextBlock(): String? {
    return firstNotNullOfOrNull { element ->
        val obj = element as? JsonObject ?: return@firstNotNullOfOrNull null
        obj.string("text")
    }
}

internal fun JsonElement.primitiveTextOrNull(): String? {
    return (this as? JsonPrimitive)?.contentOrNull
}
