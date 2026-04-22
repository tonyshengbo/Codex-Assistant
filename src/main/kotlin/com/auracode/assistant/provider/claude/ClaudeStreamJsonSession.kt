package com.auracode.assistant.provider.claude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface ClaudeStreamJsonSession {
    suspend fun collect(
        onStdoutLine: suspend (String) -> Unit,
        onStderrLine: suspend (String) -> Unit,
    ): Int

    fun cancel()
}

internal class ProcessClaudeStreamJsonSession(
    private val process: Process,
) : ClaudeStreamJsonSession {
    override suspend fun collect(
        onStdoutLine: suspend (String) -> Unit,
        onStderrLine: suspend (String) -> Unit,
    ): Int = coroutineScope {
        val stderrJob = launch(Dispatchers.IO) {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        onStderrLine(line)
                    }
                }
            }
        }
        withContext(Dispatchers.IO) {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        onStdoutLine(line)
                    }
                }
            }
        }
        val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
        stderrJob.join()
        exitCode
    }

    override fun cancel() {
        process.destroy()
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}
