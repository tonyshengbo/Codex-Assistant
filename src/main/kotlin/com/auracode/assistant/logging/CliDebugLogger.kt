package com.auracode.assistant.logging

import com.auracode.assistant.settings.AgentSettingsService
import com.intellij.openapi.diagnostic.Logger

/**
 * Gates CLI diagnostic info logs behind one shared settings switch while always preserving warnings and errors.
 */
internal class CliDebugLogger(
    private val logger: Logger,
    private val settings: AgentSettingsService = AgentSettingsService.getInstance(),
) {
    /** Writes an info-level message only when CLI debug logging is enabled. */
    fun info(message: () -> String) {
        if (!settings.cliDebugLoggingEnabled()) return
        logger.info(message())
    }

    /** Writes a warning-level message regardless of the CLI debug logging switch. */
    fun warn(message: () -> String) {
        logger.warn(message())
    }

    /** Writes an error-level message regardless of the CLI debug logging switch. */
    fun error(message: () -> String, throwable: Throwable? = null) {
        val resolvedMessage = message()
        if (throwable == null) {
            logger.error(resolvedMessage)
        } else {
            logger.error(resolvedMessage, throwable)
        }
    }
}
