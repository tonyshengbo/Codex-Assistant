package com.auracode.assistant.toolwindow.history

import java.util.Locale
import kotlin.math.absoluteValue

internal fun formatHistoryStatus(status: String): String {
    return when (status.trim().lowercase(Locale.ROOT)) {
        "active" -> "Running"
        "idle" -> "Ready"
        "notloaded" -> "Unavailable"
        "systemerror" -> "Error"
        else -> "Unknown"
    }
}

internal fun formatHistoryUpdatedAt(
    updatedAtSeconds: Long,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    if (updatedAtSeconds == 0L) return "unknown"
    val deltaSeconds = ((nowMillis / 1000L) - updatedAtSeconds).absoluteValue
    return when {
        deltaSeconds < 60L -> "just now"
        deltaSeconds < 3_600L -> "${deltaSeconds / 60L}m ago"
        deltaSeconds < 86_400L -> "${deltaSeconds / 3_600L}h ago"
        deltaSeconds < 604_800L -> "${deltaSeconds / 86_400L}d ago"
        else -> "${deltaSeconds / 604_800L}w ago"
    }
}
