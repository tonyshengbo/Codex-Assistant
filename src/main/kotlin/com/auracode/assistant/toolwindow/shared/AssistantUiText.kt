package com.auracode.assistant.toolwindow.shared

import com.auracode.assistant.i18n.AuraCodeBundle
import kotlin.math.floor

object AssistantUiText {
    const val PRIMARY_TOOL_WINDOW_ID = "Aura Code"
    val COMPOSER_HINT: String
        get() = AuraCodeBundle.message("composer.hint")

    fun selectionChipLabel(label: String): String {
        val normalized = label.trim()
        return normalized.substringAfter(':', normalized).trim()
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs < 1_000L) {
            return "${durationMs}ms"
        }

        val totalSeconds = floor(durationMs / 1_000.0).toLong()
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L

        return when {
            hours > 0L -> "%dh %02dm".format(hours, minutes)
            minutes > 0L -> "%dm %02ds".format(minutes, seconds)
            else -> "${totalSeconds}s"
        }
    }

    fun runningStatus(elapsedMs: Long): String {
        return AuraCodeBundle.message("toolwindow.running", formatDuration(elapsedMs.coerceAtLeast(0L)))
    }

    fun finishedStatus(label: String, durationMs: Long?): String {
        val normalized = label.trim().ifBlank { AuraCodeBundle.message("toolwindow.finished.default") }
        val duration = durationMs?.takeIf { it >= 0L } ?: return normalized
        return "$normalized · ${formatDuration(duration)}"
    }
}
