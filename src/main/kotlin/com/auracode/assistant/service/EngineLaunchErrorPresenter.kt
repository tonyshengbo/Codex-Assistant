package com.auracode.assistant.service

import com.auracode.assistant.provider.ProviderRegistry

/**
 * Normalizes engine startup failures into user-facing guidance.
 *
 * This keeps provider implementations focused on reporting factual failures
 * while the chat service decides how to present actionable recovery steps.
 */
internal class EngineLaunchErrorPresenter(
    private val registry: ProviderRegistry,
) {
    /**
     * Returns a friendly startup error message when the raw engine failure
     * indicates a missing CLI, a missing executable, or an invalid configured path.
     */
    fun present(engineId: String, rawMessage: String): String? {
        val normalizedMessage = rawMessage.trim()
        if (normalizedMessage.isBlank()) return null
        if (!looksLikeMissingExecutable(normalizedMessage)) return null

        val engineName = registry.engine(engineId)?.displayName?.trim().orEmpty().ifBlank { engineId }
        val pathHint = "Install the CLI for this engine first, or configure a valid executable path in Settings."
        return "The selected engine is unavailable because its CLI or executable could not be found. $pathHint Current engine: $engineName"
    }

    private fun looksLikeMissingExecutable(message: String): Boolean {
        val normalized = message.lowercase()
        return MISSING_EXECUTABLE_PATTERNS.any { normalized.contains(it) }
    }

    private companion object {
        private val MISSING_EXECUTABLE_PATTERNS = listOf(
            "no such file or directory",
            "cannot run program",
            "error=2",
            "not found",
            "command not found",
            "is not executable",
            "is not configured",
            "runtime path is not configured",
            "configured node path is not executable",
            "executable file not found",
            "spawn enoent",
        )
    }
}
