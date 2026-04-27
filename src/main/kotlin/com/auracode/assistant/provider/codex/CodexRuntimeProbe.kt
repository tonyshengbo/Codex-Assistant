package com.auracode.assistant.provider.codex

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Captures the raw outcome of one app-server probe execution. */
internal data class CodexRuntimeProbeExecution(
    val initialized: Boolean = false,
    val exitedEarly: Boolean = false,
    val stderr: String = "",
    val failureMessage: String = "",
)

/** Describes one user-facing app-server probe result. */
internal data class CodexRuntimeProbeOutcome(
    val status: CodexEnvironmentStatus,
    val message: String,
)

/** Verifies whether `codex app-server` can initialize with the resolved environment. */
internal class CodexRuntimeProbe(
    private val runner: (String, Map<String, String>) -> CodexRuntimeProbeExecution = ::runCodexRuntimeProbeExecution,
) {
    fun probe(
        codexPath: String,
        environmentOverrides: Map<String, String>,
    ): CodexRuntimeProbeOutcome {
        val execution = runner(codexPath, environmentOverrides)
        if (execution.initialized) {
            return CodexRuntimeProbeOutcome(
                status = CodexEnvironmentStatus.DETECTED,
                message = "Aura Code, Node, and the built-in app-server look available.",
            )
        }
        val details = listOf(execution.failureMessage.trim(), execution.stderr.trim())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        if (details.contains("node: No such file or directory", ignoreCase = true)) {
            return CodexRuntimeProbeOutcome(
                status = CodexEnvironmentStatus.FAILED,
                message = "Aura Code can start, but Node is not visible to the app-server.",
            )
        }
        if (execution.exitedEarly) {
            return CodexRuntimeProbeOutcome(
                status = CodexEnvironmentStatus.FAILED,
                message = "codex app-server exited early${details.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}",
            )
        }
        return CodexRuntimeProbeOutcome(
            status = CodexEnvironmentStatus.FAILED,
            message = "Failed to initialize codex app-server${details.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}",
        )
    }
}

private fun runCodexRuntimeProbeExecution(
    codexPath: String,
    environmentOverrides: Map<String, String>,
): CodexRuntimeProbeExecution {
    val process = runCatching {
        createCodexRuntimeProcess(
            binary = codexPath,
            environmentOverrides = environmentOverrides,
            workingDirectory = File(System.getProperty("user.home").orEmpty().ifBlank { "." }),
        )
    }.getOrElse {
        return CodexRuntimeProbeExecution(
            failureMessage = it.message.orEmpty().ifBlank { "unknown start failure" },
        )
    }
    val stderrLines = mutableListOf<String>()
    val reader = thread(start = true, isDaemon = true) {
        runCatching {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(stderrLines) {
                        stderrLines += line
                    }
                }
            }
        }
    }
    return try {
        val session = CodexProcessRuntimeSession(process = process, diagnosticLogger = {})
        session.start()
        runBlocking {
            withTimeout(APP_SERVER_HANDSHAKE_TIMEOUT_MS) {
                session.initialize()
            }
        }
        CodexRuntimeProbeExecution(
            initialized = true,
            stderr = synchronized(stderrLines) { stderrLines.joinToString("\n") }.trim(),
        )
    } catch (error: Throwable) {
        CodexRuntimeProbeExecution(
            exitedEarly = process.waitFor(200, TimeUnit.MILLISECONDS),
            stderr = synchronized(stderrLines) { stderrLines.joinToString("\n") }.trim(),
            failureMessage = error.message.orEmpty(),
        )
    } finally {
        process.destroy()
        process.waitFor(200, TimeUnit.MILLISECONDS)
        if (process.isAlive) {
            process.destroyForcibly()
        }
        reader.interrupt()
    }
}
