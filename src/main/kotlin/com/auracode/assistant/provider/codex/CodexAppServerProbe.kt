package com.auracode.assistant.provider.codex

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Captures the raw outcome of one app-server probe execution. */
internal data class CodexAppServerProbeExecution(
    val initialized: Boolean = false,
    val exitedEarly: Boolean = false,
    val stderr: String = "",
    val failureMessage: String = "",
)

/** Describes one user-facing app-server probe result. */
internal data class CodexAppServerProbeOutcome(
    val status: CodexEnvironmentStatus,
    val message: String,
)

/** Verifies whether `codex app-server` can initialize with the resolved environment. */
internal class CodexAppServerProbe(
    private val runner: (String, Map<String, String>) -> CodexAppServerProbeExecution = ::runCodexAppServerProbeExecution,
) {
    fun probe(
        codexPath: String,
        environmentOverrides: Map<String, String>,
    ): CodexAppServerProbeOutcome {
        val execution = runner(codexPath, environmentOverrides)
        if (execution.initialized) {
            return CodexAppServerProbeOutcome(
                status = CodexEnvironmentStatus.DETECTED,
                message = "Aura Code, Node, and the built-in app-server look available.",
            )
        }
        val details = listOf(execution.failureMessage.trim(), execution.stderr.trim())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        if (details.contains("node: No such file or directory", ignoreCase = true)) {
            return CodexAppServerProbeOutcome(
                status = CodexEnvironmentStatus.FAILED,
                message = "Aura Code can start, but Node is not visible to the app-server.",
            )
        }
        if (execution.exitedEarly) {
            return CodexAppServerProbeOutcome(
                status = CodexEnvironmentStatus.FAILED,
                message = "codex app-server exited early${details.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}",
            )
        }
        return CodexAppServerProbeOutcome(
            status = CodexEnvironmentStatus.FAILED,
            message = "Failed to initialize codex app-server${details.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}",
        )
    }
}

private fun runCodexAppServerProbeExecution(
    codexPath: String,
    environmentOverrides: Map<String, String>,
): CodexAppServerProbeExecution {
    val process = runCatching {
        createCodexAppServerProcess(
            binary = codexPath,
            environmentOverrides = environmentOverrides,
            workingDirectory = File(System.getProperty("user.home").orEmpty().ifBlank { "." }),
        )
    }.getOrElse {
        return CodexAppServerProbeExecution(
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
        val session = CodexProcessAppServerSession(process = process, diagnosticLogger = {})
        session.start()
        runBlocking {
            withTimeout(APP_SERVER_HANDSHAKE_TIMEOUT_MS) {
                session.initialize()
            }
        }
        CodexAppServerProbeExecution(
            initialized = true,
            stderr = synchronized(stderrLines) { stderrLines.joinToString("\n") }.trim(),
        )
    } catch (error: Throwable) {
        CodexAppServerProbeExecution(
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
