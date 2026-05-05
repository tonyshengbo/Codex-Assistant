package com.auracode.assistant.logging

import com.auracode.assistant.settings.AgentSettingsService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger

/**
 * Gates CLI diagnostic info logs behind one shared settings switch while always preserving warnings and errors.
 */
internal class CliDebugLogger(
    private val logger: Logger,
    private val settingsProvider: () -> AgentSettingsService? = ::resolveSettingsService,
) {
    /** Writes an info-level message only when CLI debug logging is enabled. */
    fun info(message: () -> String) {
        if (settingsProvider()?.cliDebugLoggingEnabled() != true) return
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

    private companion object {
        /** Resolves the settings service only when an IntelliJ application is available. */
        fun resolveSettingsService(): AgentSettingsService? {
            val application = ApplicationManager.getApplication() ?: return null
            return application.getService(AgentSettingsService::class.java)
        }
    }
}
